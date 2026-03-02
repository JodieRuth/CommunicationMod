package communicationmod;

import com.google.gson.Gson;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LlmWebSocketClient {
    private final String host;
    private final int port;
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, ResponseWaiter> pending = new ConcurrentHashMap<>();
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private Thread reconnectThread;
    private volatile boolean connected = false;
    private volatile boolean running = true;
    private volatile int llmMaxTokens = 512;
    private volatile double llmTemperature = 0.7;
    private volatile double llmTopP = 1.0;

    public LlmWebSocketClient(String host, int port) {
        this.host = host;
        this.port = port;
        startReconnectThread();
    }

    private void startReconnectThread() {
        reconnectThread = new Thread(() -> {
            while (running) {
                if (!connected) {
                    connect();
                }
                try {
                    Thread.sleep(5000); // Check/retry every 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "LlmWsReconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public boolean isConnected() {
        return connected;
    }

    public int getLlmMaxTokens() {
        return llmMaxTokens;
    }

    public double getLlmTemperature() {
        return llmTemperature;
    }

    public double getLlmTopP() {
        return llmTopP;
    }

    public Map<String, Object> request(Map<String, Object> payload, long timeoutMs) {
        if (!ensureConnected()) {
            return error("no_connection", "websocket not connected");
        }
        Object idObj = payload.get("id");
        if (idObj == null) {
            String id = "llm-" + System.currentTimeMillis();
            payload.put("id", id);
            idObj = id;
        }
        String id = String.valueOf(idObj);
        ResponseWaiter waiter = new ResponseWaiter();
        pending.put(id, waiter);
        try {
            sendText(gson.toJson(payload));
        } catch (IOException e) {
            pending.remove(id);
            return error("send_failed", e.getMessage());
        }
        try {
            boolean ok = waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            pending.remove(id);
            if (!ok) {
                return error("timeout", "llm response timeout");
            }
            return waiter.data;
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            return error("interrupted", "llm response interrupted");
        }
    }

    public void sendLog(String level, String message) {
        if (!ensureConnected()) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "log");
        payload.put("level", level);
        payload.put("message", message);
        send(payload);
    }

    public void send(Map<String, Object> payload) {
        if (!ensureConnected()) return;
        try {
            sendText(gson.toJson(payload));
        } catch (IOException ignored) {
        }
    }

    private boolean ensureConnected() {
        if (connected) return true;
        return connect();
    }

    private synchronized boolean connect() {
        if (connected) return true;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 2000);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
            String key = generateKey();
            String req =
                "GET / HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            readHttpResponse(in);
            connected = true;
            registerMod();
            startReader();
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    private void registerMod() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "mcp_register");
        payload.put("role", "sts_mod");
        payload.put("prefixes", java.util.Arrays.asList("sts", "game"));
        send(payload);
        requestConfig();
    }

    private void requestConfig() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "config_request");
        payload.put("id", "cfg-" + System.currentTimeMillis());
        send(payload);
    }

    private void startReader() {
        if (readerThread != null && readerThread.isAlive()) return;
        readerThread = new Thread(() -> {
            while (connected && !Thread.currentThread().isInterrupted()) {
                try {
                    String msg = readFrame(in);
                    if (msg == null) {
                        connected = false;
                        break;
                    }
                    if (!msg.isEmpty()) {
                        handleMessage(msg);
                    }
                } catch (Exception e) {
                    connected = false;
                    break;
                }
            }
            close();
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleMessage(String msg) {
        Map<String, Object> data;
        try {
            data = gson.fromJson(msg, Map.class);
        } catch (Exception e) {
            return;
        }
        if (data == null) return;
        Object typeObj = data.get("type");
        if (typeObj == null) return;
        String type = String.valueOf(typeObj);
        if ("llm_response".equals(type)) {
            Object idObj = data.get("id");
            if (idObj == null) return;
            ResponseWaiter waiter = pending.get(String.valueOf(idObj));
            if (waiter != null) {
                waiter.data = data;
                waiter.latch.countDown();
            }
        } else if ("config_response".equals(type)) {
            Object maxTokens = data.get("max_tokens");
            Object temperature = data.get("temperature");
            Object topP = data.get("top_p");
            if (maxTokens instanceof Number) {
                llmMaxTokens = ((Number) maxTokens).intValue();
            }
            if (temperature instanceof Number) {
                llmTemperature = ((Number) temperature).doubleValue();
            }
            if (topP instanceof Number) {
                llmTopP = ((Number) topP).doubleValue();
            }
        } else if ("mcp_request".equals(type)) {
            handleMcpRequest(data);
        }
    }

    public void sendResponse(Object id, Map<String, Object> result) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "mcp_response");
        response.put("id", id);
        response.put("result", result);
        send(response);
    }

    public void sendError(Object id, String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "mcp_response");
        response.put("id", id);
        response.put("status", "error");
        response.put("message", message);
        send(response);
    }

    private void handleMcpRequest(Map<String, Object> request) {
        String method = String.valueOf(request.get("method"));
        Object id = request.get("id");
        Map<String, Object> params = (Map<String, Object>) request.get("params");
        
        try {
            if ("call_tool".equals(method)) {
                String toolName = String.valueOf(params.get("name"));
                Map<String, Object> toolArgs = (Map<String, Object>) params.get("arguments");
                
                if ("game_play_battle".equals(toolName) || "game_play_turn".equals(toolName)) {
                    String mode = toolName.contains("battle") ? "battle" : "turn";
                    String question = String.valueOf(toolArgs.get("question"));
                    SubagentCoordinator.startAutomation(mode, question);
                    
                    Map<String, Object> res = new HashMap<>();
                    res.put("status", "delegate");
                    sendResponse(id, res);
                } else if ("sts_get_state".equals(toolName) || "game_sts_get_status".equals(toolName)) {
                    boolean summaryOnly = toolArgs != null && Boolean.TRUE.equals(toolArgs.get("summary_only"));
                    if (summaryOnly) {
                        Map<String, Object> full = GameStateConverter.getCommunicationStateMap();
                        Map<String, Object> res = new HashMap<>();
                        res.put("in_game", full.get("in_game"));
                        res.put("ready_for_command", full.get("ready_for_command"));
                        res.put("available_commands", full.get("available_commands"));
                        res.put("text_summary", full.get("text_summary"));
                        sendResponse(id, res);
                    } else {
                        sendResponse(id, GameStateConverter.getCommunicationStateMap());
                    }
                } else if ("game_sts_get_manual".equals(toolName)) {
                    Map<String, Object> res = new HashMap<>();
                    res.put("manual", SubagentCoordinator.getManual());
                    sendResponse(id, res);
                } else {
                    sendError(id, "Tool not handled by WebSocket yet: " + toolName);
                }
            }
        } catch (Exception e) {
            sendError(id, e.getMessage());
        }
    }

    private String generateKey() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void readHttpResponse(InputStream input) throws IOException {
        int prev = 0;
        int curr;
        int match = 0;
        while ((curr = input.read()) != -1) {
            if (prev == '\r' && curr == '\n') {
                match++;
                if (match == 2) {
                    return;
                }
            } else if (curr != '\r') {
                match = 0;
            }
            prev = curr;
        }
    }

    private void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int frameLen = 2 + 4 + payload.length;
        if (payload.length >= 126 && payload.length <= 65535) frameLen += 2;
        if (payload.length > 65535) frameLen += 8;
        ByteBuffer buffer = ByteBuffer.allocate(frameLen);
        buffer.put((byte) 0x81);
        if (payload.length < 126) {
            buffer.put((byte) (0x80 | payload.length));
        } else if (payload.length <= 65535) {
            buffer.put((byte) (0x80 | 126));
            buffer.putShort((short) payload.length);
        } else {
            buffer.put((byte) (0x80 | 127));
            buffer.putLong(payload.length);
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        buffer.put(mask);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        buffer.put(payload);
        out.write(buffer.array());
        out.flush();
    }

    private void sendPong(byte[] payload) throws IOException {
        int frameLen = 2 + 4 + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(frameLen);
        buffer.put((byte) 0x8A); 
        buffer.put((byte) (0x80 | payload.length));
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        buffer.put(mask);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        buffer.put(payload);
        out.write(buffer.array());
        out.flush();
    }

    private String readFrame(InputStream input) throws IOException {
        int b1 = input.read();
        if (b1 == -1) return null;
        int b2 = input.read();
        if (b2 == -1) return null;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((input.read() & 0xFF) << 8) | (input.read() & 0xFF);
        } else if (len == 127) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (input.read() & 0xFF);
            }
            len = v;
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            if (input.read(mask) != 4) return null;
        }
        byte[] payload = new byte[(int) len];
        int read = 0;
        while (read < payload.length) {
            int r = input.read(payload, read, payload.length - read);
            if (r == -1) return null;
            read += r;
        }
        if (masked && mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        if (opcode == 0x8) return null; 
        if (opcode == 0x9) { 
            sendPong(payload);
            return ""; 
        }
        if (opcode == 0x1) { 
            return new String(payload, StandardCharsets.UTF_8);
        }
        return ""; 
    }

    private void close() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    private Map<String, Object> error(String status, String message) {
        Map<String, Object> res = new HashMap<>();
        res.put("type", "llm_response");
        res.put("status", status);
        res.put("message", message);
        return res;
    }

    private static class ResponseWaiter {
        private final CountDownLatch latch = new CountDownLatch(1);
        private Map<String, Object> data = new HashMap<>();
    }
}
