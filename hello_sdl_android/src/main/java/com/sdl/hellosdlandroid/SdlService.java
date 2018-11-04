package com.sdl.hellosdlandroid;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.smartdevicelink.managers.CompletionListener;
import com.smartdevicelink.managers.SdlManager;
import com.smartdevicelink.managers.SdlManagerListener;
import com.smartdevicelink.managers.file.filetypes.SdlArtwork;
import com.smartdevicelink.protocol.enums.FunctionID;
import com.smartdevicelink.proxy.RPCNotification;
import com.smartdevicelink.proxy.TTSChunkFactory;
import com.smartdevicelink.proxy.rpc.AddCommand;
import com.smartdevicelink.proxy.rpc.MenuParams;
import com.smartdevicelink.proxy.rpc.OnCommand;
import com.smartdevicelink.proxy.rpc.OnHMIStatus;
import com.smartdevicelink.proxy.rpc.Speak;
import com.smartdevicelink.proxy.rpc.enums.AppHMIType;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.listeners.OnRPCNotificationListener;
import com.smartdevicelink.transport.BaseTransportConfig;
import com.smartdevicelink.transport.MultiplexTransportConfig;
import com.smartdevicelink.transport.TCPTransportConfig;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Random;
import java.util.Vector;

public class SdlService extends Service {
	private JSONArray qAndA = new JSONArray();
	private int[] scores;
	private int playerNumber;
	private static final String TAG 					= "SDL Service";

	private static final String APP_NAME 				= "Real Ford Fiesta";
	private static final String APP_ID 					= "8678309";

	private static final String ICON_FILENAME 			= "hello_sdl_icon.png";
	private static final String SDL_IMAGE_FILENAME  	= "fordfiestalogo.png";

	private static final String WELCOME_SHOW 			= "Welcome to HelloSDL";
	private static final String WELCOME_SPEAK 			= "Welcome to Ford Real Fiesta Fiesta Fiesta Fiesta";

	private static final String TEST_COMMAND_NAME 		= "Test Command";
	private static final int TEST_COMMAND_ID 			= 1;

	private static final int FOREGROUND_SERVICE_ID = 111;

	// TCP/IP transport config
	// The default port is 12345
	// The IP is of the machine that is running SDL Core
	private static final int TCP_PORT = 12345;
	private static final String DEV_MACHINE_IP_ADDRESS = "10.142.168.108";

	// variable to create and call functions of the SyncProxy
	private SdlManager sdlManager = null;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		Log.d(TAG, "onCreate");
		super.onCreate();

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			enterForeground();
		}
	}

	// Helper method to let the service enter foreground mode
	@SuppressLint("NewApi")
	public void enterForeground() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(APP_ID, "SdlService", NotificationManager.IMPORTANCE_DEFAULT);
			NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			if (notificationManager != null) {
				notificationManager.createNotificationChannel(channel);
				Notification serviceNotification = new Notification.Builder(this, channel.getId())
						.setContentTitle("Connected through SDL")
						.setSmallIcon(R.drawable.ic_sdl)
						.build();
				startForeground(FOREGROUND_SERVICE_ID, serviceNotification);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startProxy();
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			stopForeground(true);
		}

		if (sdlManager != null) {
			sdlManager.dispose();
		}

		super.onDestroy();
	}

	private void startProxy() {
		// This logic is to select the correct transport and security levels defined in the selected build flavor
		// Build flavors are selected by the "build variants" tab typically located in the bottom left of Android Studio
		// Typically in your app, you will only set one of these.
		if (sdlManager == null) {
			Log.i(TAG, "Starting SDL Proxy");
			BaseTransportConfig transport = null;
			if (BuildConfig.TRANSPORT.equals("MULTI")) {
				int securityLevel;
				if (BuildConfig.SECURITY.equals("HIGH")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_HIGH;
				} else if (BuildConfig.SECURITY.equals("MED")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_MED;
				} else if (BuildConfig.SECURITY.equals("LOW")) {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_LOW;
				} else {
					securityLevel = MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF;
				}
				transport = new MultiplexTransportConfig(this, APP_ID, securityLevel);
			} else if (BuildConfig.TRANSPORT.equals("TCP")) {
				transport = new TCPTransportConfig(TCP_PORT, DEV_MACHINE_IP_ADDRESS, true);
			} else if (BuildConfig.TRANSPORT.equals("MULTI_HB")) {
				MultiplexTransportConfig mtc = new MultiplexTransportConfig(this, APP_ID, MultiplexTransportConfig.FLAG_MULTI_SECURITY_OFF);
				mtc.setRequiresHighBandwidth(true);
				transport = mtc;
			}

			// The app type to be used
			Vector<AppHMIType> appType = new Vector<>();
			appType.add(AppHMIType.INFORMATION);

			// The manager listener helps you know when certain events that pertain to the SDL Manager happen
			// Here we will listen for ON_HMI_STATUS and ON_COMMAND notifications
			SdlManagerListener listener = new SdlManagerListener() {
				@Override
				public void onStart() {
					try {
						JSONParser parser = new JSONParser();
						qAndA = (JSONArray) parser.parse(loadJSONFromAsset());
					} catch (Exception e) {
						e.printStackTrace();
					}
					// HMI Status Listener

					sdlManager.addOnRPCNotificationListener(FunctionID.ON_HMI_STATUS, new OnRPCNotificationListener() {
						@Override
						public void onNotified(RPCNotification notification) {
							OnHMIStatus status = (OnHMIStatus) notification;
							if (status.getHmiLevel() == HMILevel.HMI_FULL && ((OnHMIStatus) notification).getFirstRun()) {
//								sendCommands();
								playerNumber = 0;
								startFiesta();
							}
						}
					});

					// Menu Selected Listener
//					sdlManager.addOnRPCNotificationListener(FunctionID.ON_COMMAND, new OnRPCNotificationListener() {
//						@Override
//						public void onNotified(RPCNotification notification) {
//							OnCommand command = (OnCommand) notification;
//							Integer id = command.getCmdID();
//							if(id != null){
//								switch(id){
//									case TEST_COMMAND_ID:
//										showTest();
//										break;
//								}
//							}
//						}
//					});
				}

				@Override
				public void onDestroy() {
					SdlService.this.stopSelf();
				}

				@Override
				public void onError(String info, Exception e) {
				}
			};

			// Create App Icon, this is set in the SdlManager builder
			SdlArtwork appIcon = new SdlArtwork(ICON_FILENAME, FileType.GRAPHIC_PNG, R.mipmap.fordfiestalogo, true);

			// The manager builder sets options for your session
			SdlManager.Builder builder = new SdlManager.Builder(this, APP_ID, APP_NAME, listener);
			builder.setAppTypes(appType);
			builder.setTransportType(transport);
			builder.setAppIcon(appIcon);
			sdlManager = builder.build();
			sdlManager.start();
		}
	}

	/**
	 *  Add commands for the app on SDL.
	 */
	private void sendCommands(){
		AddCommand command = new AddCommand();
		MenuParams params = new MenuParams();
		params.setMenuName(TEST_COMMAND_NAME);
		command.setCmdID(TEST_COMMAND_ID);
		command.setMenuParams(params);
		command.setVrCommands(Collections.singletonList(TEST_COMMAND_NAME));
		sdlManager.sendRPC(command);
	}

	private void clearTextFields() {
		sdlManager.getScreenManager().setTextField1("");
		sdlManager.getScreenManager().setTextField2("");
		sdlManager.getScreenManager().setTextField3("");
		sdlManager.getScreenManager().setTextField4("");

	}

	private void startFiesta() {
		scores = new int[4];
//		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks("welcome to the ford real fiesta")));
		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks("MY BALLS IS HOT. Lick a my dick a")));
		clearTextFields();
		sdlManager.getScreenManager().setTextField1("Ford Real Fiesta");

		try {
			Thread.sleep(5000);
		} catch (Exception e) {

		}
		askQuestion();
	}
	public String loadJSONFromAsset() {
		String json = null;
		try {
			InputStream is = getAssets().open("questions.json");
			int size = is.available();
			byte[] buffer = new byte[size];
			is.read(buffer);
			is.close();
			json = new String(buffer, "UTF-8");
		} catch (IOException ex) {
			ex.printStackTrace();
			return null;
		}
		return json;
	}
	private void setAnswerCommands() {
		final String answer1Name = "One";
		final MenuParams params1 = new MenuParams();
		final AddCommand command1 = new AddCommand();
		params1.setMenuName("one");
		command1.setCmdID(1);
		command1.setMenuParams(params1);
		command1.setVrCommands(Collections.singletonList(answer1Name));
		sdlManager.sendRPC(command1);

		final String answer2Name = "Two";
		final MenuParams params2 = new MenuParams();
		final AddCommand command2 = new AddCommand();
		params2.setMenuName("two");
		command2.setCmdID(2);
		command2.setMenuParams(params2);
		command2.setVrCommands(Collections.singletonList(answer2Name));
		sdlManager.sendRPC(command2);

		final String answer3Name = "Three";
		final MenuParams params3 = new MenuParams();
		final AddCommand command3 = new AddCommand();
		params3.setMenuName("three");
		command3.setCmdID(3);
		command3.setMenuParams(params3);
		command3.setVrCommands(Collections.singletonList(answer3Name));
		sdlManager.sendRPC(command3);

		final String answer4Name = "Four";
		final MenuParams params4 = new MenuParams();
		final AddCommand command4 = new AddCommand();
		params4.setMenuName("four");
		command4.setCmdID(4);
		command4.setMenuParams(params4);
		command4.setVrCommands(Collections.singletonList(answer4Name));
		sdlManager.sendRPC(command4);

	}

	private OnRPCNotificationListener setAnswerListeners(final int answer, final int player) {
		OnRPCNotificationListener listener = new OnRPCNotificationListener() {
			@Override
			public void onNotified(RPCNotification notification) {
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				System.out.println(answer);
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				System.out.println("AHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHHh");
				OnCommand command = (OnCommand) notification;
				Integer id = command.getCmdID();
				System.out.println(id);
				if(id != null){
					System.out.println("REEEEEEEEEEEEEEEEEEEEE");
					if (id.intValue() == answer) {
						scores[player] += 10;
						System.out.println(scores[0]);
					} else if (id.intValue() == 69) {
						//restart
					}
				}
				displayScores();
				try{
					Thread.sleep(2000);
				} catch (Exception e) {

				}
				sdlManager.removeOnRPCNotificationListener(FunctionID.ON_COMMAND, this);
				playerNumber += 1;
				if (playerNumber < 4) {
					clearTextFields();
					askQuestion();
				}

			}
		};
		sdlManager.addOnRPCNotificationListener(FunctionID.ON_COMMAND, listener);
		return listener;
	}


	private OnRPCNotificationListener askQuestion() {
		clearTextFields();
		setAnswerCommands();	// Init voice commands
		String[] questionData = getRandomQuestionData();
		String question = questionData[0];
		String answer1 = questionData[1];
		String answer2 = questionData[2];
		String answer3 = questionData[3];
		String answer4 = questionData[4];
		String realAnswer = questionData[5];

		int intAnswer = 0;
		switch (realAnswer){
			case("One"):
				intAnswer = 1;
				break;
			case("Two"):
				intAnswer = 2;
				break;
			case("Three"):
				intAnswer = 3;
				break;
			case("Four"):
				intAnswer = 4;
				break;
		}
		OnRPCNotificationListener listener = setAnswerListeners(intAnswer, playerNumber);

		StringBuffer buf = new StringBuffer();
		buf.append(question);
		buf.append(". One. ");
		buf.append(answer1);
		buf.append(". Two. ");
		buf.append(answer2);
		buf.append(". Three. ");
		buf.append(answer3);
		buf.append(". Four. ");
		buf.append(answer4);
		buf.append(".");
		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks(buf.toString())));

		sdlManager.getScreenManager().setTextField1("Player" + String.valueOf(playerNumber + 1));
		sdlManager.getScreenManager().setTextField2(question);
		sdlManager.getScreenManager().setTextField3("1. " + answer1 + "  2. " + answer2);
		sdlManager.getScreenManager().setTextField4("3. " + answer3 + "  4. " + answer4);
		sdlManager.getScreenManager().setPrimaryGraphic(new SdlArtwork(SDL_IMAGE_FILENAME, FileType.GRAPHIC_PNG, R.drawable.fordfiestalogo, true));
		return listener;
//		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks("Where is Ari born? One. Maryland. Two. California. Three. New York. Four. Missouri.")));
//		sdlManager.removeOnRPCNotificationListener(FunctionID.ON_COMMAND, listener);
	}

	private void displayScores() {
		clearTextFields();
		sdlManager.getScreenManager().setTextField1("Player 1:" + scores[0]);
		System.out.println("my balls was hot");
		System.out.println(scores[0]);
		sdlManager.getScreenManager().setTextField2("Player 2:" + scores[1]);
		sdlManager.getScreenManager().setTextField3("Player 3:" + scores[2]);
		sdlManager.getScreenManager().setTextField4("Player 4:" + scores[3]);

	}
	/**
	 * Will speak a sample welcome message
	 */
	private void performWelcomeSpeak(){
		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks(WELCOME_SPEAK)));
	}

	/**
	 * Use the Screen Manager to set the initial screen text and set the image.
	 * Because we are setting multiple items, we will call beginTransaction() first,
	 * and finish with commit() when we are done.
	 */
	private void performWelcomeShow() {
		sdlManager.getScreenManager().beginTransaction();
		sdlManager.getScreenManager().setTextField1(APP_NAME);
		sdlManager.getScreenManager().setTextField2(WELCOME_SHOW);
		sdlManager.getScreenManager().setTextField3(WELCOME_SHOW);
		sdlManager.getScreenManager().setTextField4(WELCOME_SHOW);
		sdlManager.getScreenManager().setPrimaryGraphic(new SdlArtwork(SDL_IMAGE_FILENAME, FileType.GRAPHIC_PNG, R.drawable.sdl, true));
		sdlManager.getScreenManager().commit(new CompletionListener() {
			@Override
			public void onComplete(boolean success) {
				if (success){
					Log.i(TAG, "welcome show successful");
				}
			}
		});
	}
	private String[] getRandomQuestionData() {
		// Index mappings
		// 0 - question
		// 1-4 - options
		// 5 - answer
		String[] questionData = new String[6];

		try {
			Random rand = new Random();
			int index = rand.nextInt(546);

			questionData[0] = (String) ((JSONObject)qAndA.get(index)).get("question");
			questionData[1] = (String) ((JSONObject)qAndA.get(index)).get("One");
			questionData[2] = (String) ((JSONObject)qAndA.get(index)).get("Two");
			questionData[3] = (String) ((JSONObject)qAndA.get(index)).get("Three");
			questionData[4] = (String) ((JSONObject)qAndA.get(index)).get("Four");
			questionData[5] = (String) ((JSONObject)qAndA.get(index)).get("answer");
		} catch (Exception e) {
			e.printStackTrace();
		}

		return questionData;
	}
	/**
	 * Will show a sample test message on screen as well as speak a sample test message
	 */
	private void showTest(){
		sdlManager.getScreenManager().beginTransaction();
		sdlManager.getScreenManager().setTextField1("Command has been selected");
		sdlManager.getScreenManager().setTextField2("");
		sdlManager.getScreenManager().commit(null);

		sdlManager.sendRPC(new Speak(TTSChunkFactory.createSimpleTTSChunks(TEST_COMMAND_NAME)));
	}


}
