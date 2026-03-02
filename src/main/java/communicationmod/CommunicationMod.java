package communicationmod;

import basemod.*;
import basemod.interfaces.PostDungeonUpdateSubscriber;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import basemod.interfaces.PreUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireConfig;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.google.gson.Gson;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import communicationmod.patches.InputActionPatch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@SpireInitializer
public class CommunicationMod implements PostInitializeSubscriber, PostUpdateSubscriber, PostDungeonUpdateSubscriber, PreUpdateSubscriber, OnStateChangeSubscriber {

    private static Process listener;
    private static StringBuilder inputBuffer = new StringBuilder();
    public static boolean messageReceived = false;
    private static final Logger logger = LogManager.getLogger(CommunicationMod.class.getName());
    private static Thread writeThread;
    private static BlockingQueue<String> writeQueue;
    private static Thread readThread;
    private static BlockingQueue<String> readQueue;
    private static Socket socket;
    private static boolean usingSocket = false;
    private static boolean socketModeEnabled = false;
    private static boolean socketConnecting = false;
    private static String socketCommandString = "";
    private static final String MODNAME = "Communication Mod";
    private static final String AUTHOR = "Forgotten Arbiter";
    private static final String DESCRIPTION = "This mod communicates with an external program to play Slay the Spire.";
    public static boolean mustSendGameState = false;
    private static ArrayList<OnStateChangeSubscriber> onStateChangeSubscribers;

    private static SpireConfig communicationConfig;
    private static final String COMMAND_OPTION = "command";
    private static final String GAME_START_OPTION = "runAtGameStart";
    private static final String VERBOSE_OPTION = "verbose";
    private static final String INITIALIZATION_TIMEOUT_OPTION = "maxInitializationTimeout";
    private static final String DEFAULT_COMMAND = "socket://127.0.0.1:25433";
    private static final String SOCKET_PREFIX = "socket://";
    private static final long DEFAULT_TIMEOUT = 10L;
    private static final boolean DEFAULT_VERBOSITY = true;
    private static final String JAVA_SUBAGENT_OPTION = "javaSubagentEnabled";
    private static final String LLM_WS_HOST_OPTION = "llmWsHost";
    private static final String LLM_WS_PORT_OPTION = "llmWsPort";
    private static final String DEFAULT_LLM_WS_HOST = "127.0.0.1";
    private static final int DEFAULT_LLM_WS_PORT = 12345;
    private static boolean javaSubagentEnabled = false;

    public CommunicationMod(){
        BaseMod.subscribe(this);
        onStateChangeSubscribers = new ArrayList<>();
        CommunicationMod.subscribe(this);
        readQueue = new LinkedBlockingQueue<>();
        try {
            Properties defaults = new Properties();
            defaults.put(GAME_START_OPTION, Boolean.toString(true));
            defaults.put(INITIALIZATION_TIMEOUT_OPTION, Long.toString(DEFAULT_TIMEOUT));
            defaults.put(VERBOSE_OPTION, Boolean.toString(DEFAULT_VERBOSITY));
            defaults.put(JAVA_SUBAGENT_OPTION, Boolean.toString(true));
            defaults.put(LLM_WS_HOST_OPTION, DEFAULT_LLM_WS_HOST);
            defaults.put(LLM_WS_PORT_OPTION, Integer.toString(DEFAULT_LLM_WS_PORT));
            communicationConfig = new SpireConfig("CommunicationMod", "config", defaults);
            String command = communicationConfig.getString(COMMAND_OPTION);
            if (command == null || command.trim().isEmpty()) {
                communicationConfig.setString(COMMAND_OPTION, DEFAULT_COMMAND);
                communicationConfig.setBool(GAME_START_OPTION, true);
                communicationConfig.save();
                command = DEFAULT_COMMAND;
            }
            communicationConfig.save();
            logger.info("CommunicationMod config: command=" + command + " runAtGameStart=" + getRunOnGameStartOption());
        } catch (IOException e) {
            e.printStackTrace();
        }

        javaSubagentEnabled = getJavaSubagentEnabled();
        if (javaSubagentEnabled) {
            SubagentCoordinator.init(getLlmWsHost(), getLlmWsPort());
        } else if(getRunOnGameStartOption()) {
            boolean success = startExternalProcess();
        }
    }

    public static void initialize() {
        CommunicationMod mod = new CommunicationMod();
    }

    public void receivePreUpdate() {
        if(usingSocket) {
            if(socket != null && (socket.isClosed() || !socket.isConnected())) {
                logger.info("Socket connection lost or closed. Disposing and scheduling reconnect...");
                disposeSocket();
                scheduleSocketReconnect();
            }
        } else if(listener != null && !listener.isAlive() && writeThread != null && writeThread.isAlive()) {
            logger.info("Child process has died...");
            writeThread.interrupt();
            readThread.interrupt();
        }
        if(messageAvailable()) {
            try {
                boolean stateChanged = CommandExecutor.executeCommand(readMessage());
                if(stateChanged) {
                    GameStateListener.registerCommandExecution();
                }
            } catch (InvalidCommandException e) {
                HashMap<String, Object> jsonError = new HashMap<>();
                jsonError.put("error", e.getMessage());
                jsonError.put("ready_for_command", GameStateListener.isWaitingForCommand());
                Gson gson = new Gson();
                sendMessage(gson.toJson(jsonError));
            }
        }
    }

    public static void subscribe(OnStateChangeSubscriber sub) {
        onStateChangeSubscribers.add(sub);
    }

    public static void publishOnGameStateChange() {
        for(OnStateChangeSubscriber sub : onStateChangeSubscribers) {
            sub.receiveOnStateChange();
        }
    }

    public void receiveOnStateChange() {
        if (javaSubagentEnabled) {
            SubagentCoordinator.onStateStable(GameStateConverter.getCommunicationState());
        } else {
            sendGameState();
        }
    }

    public static void queueCommand(String command) {
        readQueue.add(command);
    }

    public void receivePostInitialize() {
        setUpOptionsMenu();
    }

    public void receivePostUpdate() {
        if(!mustSendGameState && GameStateListener.checkForMenuStateChange()) {
            mustSendGameState = true;
        }
        if(mustSendGameState) {
            publishOnGameStateChange();
            mustSendGameState = false;
        }
        InputActionPatch.doKeypress = false;
    }

    public void receivePostDungeonUpdate() {
        if (GameStateListener.checkForDungeonStateChange()) {
            mustSendGameState = true;
        }
        if(AbstractDungeon.getCurrRoom().isBattleOver) {
            GameStateListener.signalTurnEnd();
        }
    }

    private void setUpOptionsMenu() {
        ModPanel settingsPanel = new ModPanel();
        ModLabeledToggleButton gameStartOptionButton = new ModLabeledToggleButton(
                "Start external process at game launch",
                350, 550, Settings.CREAM_COLOR, FontHelper.charDescFont,
                getRunOnGameStartOption(), settingsPanel, modLabel -> {},
                modToggleButton -> {
                    if (communicationConfig != null) {
                        communicationConfig.setBool(GAME_START_OPTION, modToggleButton.enabled);
                        try {
                            communicationConfig.save();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
        settingsPanel.addUIElement(gameStartOptionButton);

        ModLabel externalCommandLabel = new ModLabel(
                "", 350, 600, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {
                    modLabel.text = String.format("External Process Command: %s", getSubprocessCommandString());
                });
        settingsPanel.addUIElement(externalCommandLabel);

        ModButton startProcessButton = new ModButton(
                350, 650, settingsPanel, modButton -> {
                    BaseMod.modSettingsUp = false;
                    startExternalProcess();
                });
        settingsPanel.addUIElement(startProcessButton);

        ModLabel startProcessLabel = new ModLabel(
                "(Re)start external process",
                475, 700, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {
                    if(listener != null && listener.isAlive()) {
                        modLabel.text = "Restart external process";
                    } else {
                        modLabel.text = "Start external process";
                    }
                });
        settingsPanel.addUIElement(startProcessLabel);

        ModButton editProcessButton = new ModButton(
                850, 650, settingsPanel, modButton -> {});
        settingsPanel.addUIElement(editProcessButton);

        ModLabel editProcessLabel = new ModLabel(
                "Set command (not implemented)",
                975, 700, Settings.CREAM_COLOR, FontHelper.charDescFont,
                settingsPanel, modLabel -> {});
        settingsPanel.addUIElement(editProcessLabel);

        ModLabeledToggleButton verbosityOption = new ModLabeledToggleButton(
                "Suppress verbose log output",
                350, 500, Settings.CREAM_COLOR, FontHelper.charDescFont,
                getVerbosityOption(), settingsPanel, modLabel -> {},
                modToggleButton -> {
                    if (communicationConfig != null) {
                        communicationConfig.setBool(VERBOSE_OPTION, modToggleButton.enabled);
                        try {
                            communicationConfig.save();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });
        settingsPanel.addUIElement(verbosityOption);
        BaseMod.registerModBadge(ImageMaster.loadImage("Icon.png"),"Communication Mod", "Forgotten Arbiter", null, settingsPanel);
    }

    private void startCommunicationThreads(InputStream inputStream, OutputStream outputStream) {
        writeQueue = new LinkedBlockingQueue<>();
        writeThread = new Thread(new DataWriter(writeQueue, outputStream, getVerbosityOption()));
        writeThread.start();
        readThread = new Thread(new DataReader(readQueue, inputStream, getVerbosityOption()));
        readThread.start();
    }

    private static void sendGameState() {
        String state = GameStateConverter.getCommunicationState();
        sendMessage(state);
    }

    public static void dispose() {
        logger.info("Shutting down child process...");
        if(listener != null) {
            listener.destroy();
        }
        if(socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void sendMessage(String message) {
        if(writeQueue != null && writeThread.isAlive()) {
            writeQueue.add(message);
        }
    }

    private static boolean messageAvailable() {
        return readQueue != null && !readQueue.isEmpty();
    }

    private static String readMessage() {
        if(messageAvailable()) {
            return readQueue.remove();
        } else {
            return null;
        }
    }

    private static String readMessageBlocking() {
        try {
            return readQueue.poll(getInitializationTimeoutOption(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to read message from subprocess.");
        }
    }

    private static String[] getSubprocessCommand() {
        if (communicationConfig == null) {
            return new String[0];
        }
        return communicationConfig.getString(COMMAND_OPTION).trim().split("\\s+");
    }

    private static String getSubprocessCommandString() {
        if (communicationConfig == null) {
            return "";
        }
        return communicationConfig.getString(COMMAND_OPTION).trim();
    }

    private static boolean getRunOnGameStartOption() {
        if (communicationConfig == null) {
            return false;
        }
        return communicationConfig.getBool(GAME_START_OPTION);
    }

    private static long getInitializationTimeoutOption() {
        if (communicationConfig == null) {
            return DEFAULT_TIMEOUT;
        }
        return (long)communicationConfig.getInt(INITIALIZATION_TIMEOUT_OPTION);
    }

    private static boolean getVerbosityOption() {
        if (communicationConfig == null) {
            return DEFAULT_VERBOSITY;
        }
        return communicationConfig.getBool(VERBOSE_OPTION);
    }

    private static boolean getJavaSubagentEnabled() {
        if (communicationConfig == null) {
            return false;
        }
        return communicationConfig.getBool(JAVA_SUBAGENT_OPTION);
    }

    private static String getLlmWsHost() {
        if (communicationConfig == null) {
            return DEFAULT_LLM_WS_HOST;
        }
        String host = communicationConfig.getString(LLM_WS_HOST_OPTION);
        return host == null || host.trim().isEmpty() ? DEFAULT_LLM_WS_HOST : host.trim();
    }

    private static int getLlmWsPort() {
        if (communicationConfig == null) {
            return DEFAULT_LLM_WS_PORT;
        }
        return communicationConfig.getInt(LLM_WS_PORT_OPTION);
    }

    private boolean startExternalProcess() {
        if(readThread != null) {
            readThread.interrupt();
        }
        if(writeThread != null) {
            writeThread.interrupt();
        }
        if(socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            socket = null;
            usingSocket = false;
        }
        if(listener != null) {
            listener.destroy();
            try {
                boolean success = listener.waitFor(2, TimeUnit.SECONDS);
                if (!success) {
                    listener.destroyForcibly();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                listener.destroyForcibly();
            }
        }
        String commandString = getSubprocessCommandString();
        if(commandString.startsWith(SOCKET_PREFIX)) {
            socketModeEnabled = true;
            socketCommandString = commandString;
            boolean connected = startSocketConnection(commandString, getInitializationTimeoutOption() * 1000L);
            if(!connected) {
                scheduleSocketReconnect();
            }
            return connected;
        }
        socketModeEnabled = false;
        socketCommandString = "";
        ProcessBuilder builder = new ProcessBuilder(getSubprocessCommand());
        File errorLog = new File("communication_mod_errors.log");
        builder.redirectError(ProcessBuilder.Redirect.appendTo(errorLog));
        try {
            listener = builder.start();
        } catch (IOException e) {
            logger.error("Could not start external process.");
            e.printStackTrace();
        }
        if(listener != null) {
            startCommunicationThreads(listener.getInputStream(), listener.getOutputStream());
            String message = readMessageBlocking();
            if(message == null) {
                readThread.interrupt();
                writeThread.interrupt();
                listener.destroy();
                logger.error("Timed out while waiting for signal from external process.");
                logger.error("Check communication_mod_errors.log for stderr from the process.");
                return false;
            } else {
                logger.info(String.format("Received message from external process: %s", message));
                if (GameStateListener.isWaitingForCommand()) {
                    mustSendGameState = true;
                }
                return true;
            }
        }
        return false;
    }

    private boolean startSocketConnection(String commandString, long timeoutMs) {
        String target = commandString.substring(SOCKET_PREFIX.length()).trim();
        String host = "127.0.0.1";
        int port;
        try {
            if(target.contains(":")) {
                String[] parts = target.split(":", 2);
                if(!parts[0].isEmpty()) {
                    host = parts[0];
                }
                port = Integer.parseInt(parts[1]);
            } else {
                port = Integer.parseInt(target);
            }
        } catch (Exception e) {
            logger.error("Invalid socket command: " + commandString);
            return false;
        }
        
        logger.info("Attempting to connect to socket server at " + host + ":" + port);
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                Socket newSocket = new Socket();
                newSocket.connect(new InetSocketAddress(host, port), 500);
                socket = newSocket;
                usingSocket = true;
                startCommunicationThreads(socket.getInputStream(), socket.getOutputStream());
                
                logger.info("Connected! Waiting for 'ready' signal from server...");
                String message = readMessageBlocking();
                if(message == null) {
                    logger.error("Timed out while waiting for signal from external socket server.");
                    disposeSocket();
                    return false;
                } else {
                    logger.info(String.format("Received message from external socket server: %s", message));
                    mustSendGameState = true;
                    return true;
                }
            } catch (IOException e) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException interruptedException) {
                    return false;
                }
            }
        }
        logger.error("Timed out while trying to connect to socket server at " + host + ":" + port);
        return false;
    }

    private void disposeSocket() {
        if (readThread != null) readThread.interrupt();
        if (writeThread != null) writeThread.interrupt();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
            }
        }
        socket = null;
        usingSocket = false;
    }

    private void scheduleSocketReconnect() {
        if(!socketModeEnabled || socketConnecting) {
            return;
        }
        socketConnecting = true;
        Thread reconnectThread = new Thread(() -> {
            logger.info("Socket reconnection thread started...");
            while(socketModeEnabled && socket == null) {
                boolean connected = startSocketConnection(socketCommandString, 1000L);
                if(connected) {
                    logger.info("Socket reconnection successful.");
                    break;
                }
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            socketConnecting = false;
            logger.info("Socket reconnection thread finished.");
        });
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

}
