package communicationmod;

import com.google.gson.Gson;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SubagentCoordinator {
    private static final Gson gson = new Gson();
    private static LlmWebSocketClient llmClient;
    private static String lastStateSnapshot = "";
    private static String lastAssistant = "";
    private static String lastReasoning = "";
    private static boolean running = false;
    private static String currentMode = "battle";
    private static String userQuestion = "";
    private static final int MAX_HISTORY_ROUNDS = 3;
    private static List<Map<String, Object>> history = new ArrayList<>();
    private static volatile long lastStateAt = 0L;
    private static volatile int actionSeq = 0;
    private static volatile boolean llmBusy = false;
    private static int retryCount = 0;
    private static final int MAX_RETRY_COUNT = 20;
    private static final long BASE_RETRY_DELAY = 200L;
    private static String initialEnemyName = "";

    public static void init(String host, int port) {
        if (llmClient == null) {
            llmClient = new LlmWebSocketClient(host, port);
        }
    }

    public static String getManual() {
        return "Slay the Spire Game Manual:\n" +
               "1. Combat: Use 'play card_index monster_index' (e.g. 'play 1 0'). Card indices are 1-based. Monster indices are 0-based (only alive monsters are shown, re-indexed dynamically after each death).\n" +
               "2. Ending Turn: Use 'end'.\n" +
               "3. Choices: Use 'choose choice_index' (e.g. 'choose 0').\n" +
               "4. Potions: Use 'potion use/discard potion_index' (e.g. 'potion use 0'). Potion indices are 0-based (includes empty slots). For target potions (Fire, Weak, etc.), add monster_index (e.g. 'potion use 0 1').\n" +
               "5. General: Use 'wait' to poll for stable state.";
    }

    public static boolean isRunning() {
        return running;
    }

    public static void onStateStable(String stateJson) {
        lastStateSnapshot = stateJson;
        lastStateAt = System.currentTimeMillis();
        if (!GameStateListener.isWaitingForCommand()) {
            return;
        }
        if (running) {
            continueSubagent(stateJson);
        }
    }

    public static void startAutomation(String mode, String question) {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
            log("warn", "Refusing to start battle automation: already at combat reward screen");
            return;
        }
        
        running = true;
        currentMode = mode;
        userQuestion = question;
        lastAssistant = "";
        lastReasoning = "";
        history.clear();
        initialEnemyName = "";
        log("info", "Starting automation mode: " + mode + " with question: " + question);
        
        if (AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().monsters != null && !AbstractDungeon.getCurrRoom().monsters.monsters.isEmpty()) {
            AbstractMonster firstEnemy = AbstractDungeon.getCurrRoom().monsters.monsters.get(0);
            if (firstEnemy != null && !firstEnemy.isDeadOrEscaped()) {
                initialEnemyName = firstEnemy.name;
                log("info", "Initial enemy detected: " + initialEnemyName);
            }
        }
        
        requestNextAction();
    }

    private static void continueSubagent(String stateJson) {
        if (!running) return;
        requestNextAction();
    }

    private static void requestNextAction() {
        if (!GameStateListener.isWaitingForCommand()) {
            scheduleRetry();
            return;
        }
        if (llmBusy) {
            scheduleRetry();
            return;
        }
        
        if (isBattleOver()) {
            sendGameOverReport();
            return;
        }
        
        retryCount = 0;
        lastStateSnapshot = GameStateConverter.getSimplifiedTextState();
        String currentState = lastStateSnapshot;
        List<Map<String, Object>> messages = new ArrayList<>();
        
        messages.add(systemMessage(getBasePrompt()));
        
        for (Map<String, Object> msg : history) {
            messages.add(msg);
        }
        
        String toolGuide = "\n\n【工具使用指南】\n" +
            "- 出牌：play [手牌索引] [敌人索引] (如 'play 1 0' 表示对0号敌人出第1张牌)\n" +
            "- 结束回合：end\n" +
            "- 选择：choose [选项索引] (如 'choose 0')\n" +
            "- 药水：potion use/discard [药水索引] (如 'potion use 0'，0-based索引，包含空槽位)。需要目标的药水 (火焰、虚弱等) 添加怪物索引 (如 'potion use 0 1')\n" +
            "- 确认/取消：confirm / cancel\n" +
            "- 按键：key [键名] (如 'key esc')\n" +
            "- 等待：wait (当状态不稳定时使用)";

        String prompt = "User Request: " + (userQuestion.isEmpty() ? "Play optimally." : userQuestion) + 
            "\n\n[CURRENT_STATE_TEXT]\n" + currentState + 
            toolGuide +
            "\n\n[MANDATORY INSTRUCTION]\n" + getMandatoryInstruction();
        messages.add(userMessage(prompt));
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "llm_request");
        payload.put("messages", messages);
        payload.put("max_tokens", llmClient.getLlmMaxTokens());
        payload.put("temperature", llmClient.getLlmTemperature());
        payload.put("top_p", llmClient.getLlmTopP());
        runLlmRequest(payload, currentState);
    }

    private static void runLlmRequest(Map<String, Object> payload, String currentState) {
        llmBusy = true;
        new Thread(() -> {
            if (!llmClient.isConnected()) {
                log("error", "WebSocket not connected, stopping automation");
                running = false;
                llmBusy = false;
                return;
            }
            
            Map<String, Object> response = llmClient.request(payload, 8000);
            llmBusy = false;
            
            if (!llmClient.isConnected()) {
                log("error", "WebSocket disconnected during request, stopping automation");
                running = false;
                return;
            }
            
            Object statusObj = response.get("status");
            if (statusObj != null && "error".equals(String.valueOf(statusObj))) {
                log("error", "LLM request failed: " + response.get("message"));
                scheduleRetry();
                return;
            }
            
            String rawText = String.valueOf(response.get("raw_text"));
            String reasoning = String.valueOf(response.get("reasoning"));
            if (rawText == null) rawText = "";
            if (reasoning == null || "null".equals(reasoning)) reasoning = "";
            
            if (!reasoning.isEmpty()) {
                log("info", "[THOUGHT] " + reasoning);
            }
            
            String command = parseCommand(rawText);
            if (command == null || command.isEmpty()) {
                log("warn", "No command returned, retrying.");
                scheduleRetry();
                return;
            }
            log("info", "LLM command: " + command);
            
            if (isStopSignal(command)) {
                running = false;
                log("info", "Subagent stopped: " + command);
                sendTaskFinished(rawText);
                return;
            }
            
            if ("end".equals(command.toLowerCase())) {
                if (shouldAvoidEndTurn()) {
                    log("warn", "End turn blocked due to remaining energy and playable cards.");
                    history.add(userMessage("[SYSTEM_OVERRIDE] You still have playable cards and energy. Do NOT end turn unless no legal play exists."));
                    requestNextAction();
                    return;
                }
                if ("turn".equals(currentMode)) {
                    running = false;
                    log("info", "Turn mode finished via end turn.");
                    sendTaskFinished(rawText);
                    CommunicationMod.queueCommand(command);
                    return;
                }
            }
            
            history.add(userMessage("[STATE_SNAPSHOT_BEFORE_ACTION]\n" + currentState));
            history.add(assistantMessage(rawText + (reasoning.isEmpty() ? "" : "\n\n(Reasoning: " + reasoning + ")")));
            
            if (history.size() > MAX_HISTORY_ROUNDS * 2) {
                history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_ROUNDS * 2, history.size()));
            }
            
            CommunicationMod.queueCommand(command);
            int seq = ++actionSeq;
            long issuedAt = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    long waitTime = estimateCommandDuration(command);
                    Thread.sleep(waitTime);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (!running || actionSeq != seq) return;
                long elapsed = System.currentTimeMillis() - issuedAt;
                if (elapsed > 5000L || lastStateAt < issuedAt) {
                    lastStateAt = System.currentTimeMillis();
                    requestNextAction();
                }
            }).start();
        }).start();
    }

    private static void sendTaskFinished(String report) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "task_finished");
        payload.put("mode", currentMode);
        payload.put("report", report);
        llmClient.send(payload);
    }

    private static String parseCommand(String rawText) {
        if (rawText == null) return "";
        String text = rawText.trim();
        if (text.isEmpty()) return "";
        if (text.startsWith("{")) {
            try {
                Map<String, Object> data = gson.fromJson(text, Map.class);
                Object cmd = data.get("command");
                if (cmd != null) return String.valueOf(cmd);
            } catch (Exception ignored) {
            }
        }
        return text.split("\n")[0].trim();
    }

    private static boolean isStopSignal(String cmd) {
        String c = cmd.toLowerCase();
        return c.contains("victory") || c.contains("defeat") || c.contains("fatal") || c.contains("stop");
    }

    private static boolean shouldAvoidEndTurn() {
        AbstractPlayer p = AbstractDungeon.player;
        if (p == null) return false;
        if (EnergyPanel.totalCount <= 0) return false;
        for (AbstractCard c : p.hand.group) {
            if (c.canUse(p, null)) return true;
        }
        return false;
    }

    private static String getBasePrompt() {
        return "You are an EXPERT Slay the Spire combat agent. You see ONLY the current full state and must play optimally.";
    }

    private static String getMandatoryInstruction() {
        return "You MUST return a single command string (play/end/choose/potion/confirm/cancel/key/wait). " +
            "Card indices are 1-based. Monster indices are 0-based (only alive monsters are shown, re-indexed dynamically). " +
            "Do NOT ask for more info. Use the provided state.";
    }

    private static Map<String, Object> systemMessage(String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "system");
        msg.put("content", content);
        return msg;
    }

    private static Map<String, Object> userMessage(String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "user");
        msg.put("content", content);
        return msg;
    }

    private static Map<String, Object> assistantMessage(String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("role", "assistant");
        msg.put("content", content);
        return msg;
    }

    private static void log(String level, String message) {
        llmClient.sendLog(level, message);
    }

    private static long estimateCommandDuration(String command) {
        if (command == null) return 1500L;
        
        String cmd = command.toLowerCase().trim();
        
        if (cmd.startsWith("play")) {
            return 2500L;  
        }
        else if (cmd.startsWith("potion")) {
            return 2000L;   
        }
        else if (cmd.equals("end")) {
            return 5000L;  
        }
        else if (cmd.startsWith("choose")) {
            return 1500L;  
        }
        else if (cmd.equals("confirm") || cmd.equals("cancel")) {
            return 1000L;  
        }
        else if (cmd.startsWith("wait")) {
            return 500L;   
        }
        else {
            return 1500L;
        }
    }

    private static void scheduleRetry() {
        retryCount++;
        
        if (retryCount > MAX_RETRY_COUNT) {
            log("error", "Max retry count (" + MAX_RETRY_COUNT + ") exceeded, stopping automation");
            running = false;
            sendTaskFinished("重试次数超过限制 (" + MAX_RETRY_COUNT + "), 自动化已停止");
            return;
        }
        
        long delay = BASE_RETRY_DELAY * (1L << (retryCount - 1));
        delay = Math.min(delay, 5000L); 
        
        final long finalDelay = delay;
        new Thread(() -> {
            try {
                Thread.sleep(finalDelay);
            } catch (InterruptedException ignored) {
                return;
            }
            if (running) {
                requestNextAction();
            }
        }).start();
    }

    private static void sendGameOverReport() {
        log("info", "Game over detected, generating summary report...");
        
        boolean playerDead = AbstractDungeon.player.isDead;
        boolean allEnemiesDead = true;
        if (AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().monsters != null) {
            for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
                if (!m.isDead && !m.isDeadOrEscaped()) {
                    allEnemiesDead = false;
                    break;
                }
            }
        }
        
        boolean isVictory = allEnemiesDead;
        boolean isDeath = playerDead;
        
        AbstractPlayer p = AbstractDungeon.player;
        int currentGold = p.gold;
        int currentHealth = p.currentHealth;
        int maxHealth = p.maxHealth;
        
        String enemyName = initialEnemyName.isEmpty() ? "未知敌人" : initialEnemyName;
        
        String previousState = lastStateSnapshot.isEmpty() ? "无历史状态" : lastStateSnapshot;
        
        String gameOverState = String.format(
            "【游戏结束状态】\n" +
            "结果：%s\n" +
            "生命值：%d/%d | 金钱：%d\n" +
            "【关卡敌人】\n" +
            "%s\n" +
            "【与上一状态的差异】\n" +
            "%s",
            isVictory ? "胜利" : "失败",
            currentHealth, maxHealth, currentGold,
            enemyName,
            previousState
        );
        
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage("You are analyzing a Slay the Spire run result. Generate a BRIEF summary in 2-3 sentences max. Only include: result (win/loss), current HP, key rewards if any. Be concise."));
        messages.add(userMessage(gameOverState));
        
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "llm_request");
        payload.put("messages", messages);
        payload.put("max_tokens", llmClient.getLlmMaxTokens());
        payload.put("temperature", llmClient.getLlmTemperature());
        payload.put("top_p", llmClient.getLlmTopP());
        
        llmBusy = true;
        new Thread(() -> {
            Map<String, Object> response = llmClient.request(payload, 60000);
            llmBusy = false;
            
            if (response != null && "success".equals(String.valueOf(response.get("status")))) {
                String rawText = String.valueOf(response.get("raw_text"));
                String summary = rawText.isEmpty() ? "战斗结束。" : rawText;
                
                if (isVictory) {
                    summary += "\n\n【注意】战斗已经结束，现在处于奖励选择界面。请主程序自行处理奖励选择。";
                } else {
                    summary += "\n\n【注意】战斗已经结束。";
                }
                
                running = false;
                log("info", "Game over report generated: " + summary);
                sendTaskFinished(summary);
            } else {
                running = false;
                log("info", "Game over, sending default report.");
                String defaultReport = isVictory 
                    ? String.format("战胜了%s！战斗胜利！战斗已经结束，请注意现在处于奖励选择界面，需要手动选择奖励。", enemyName)
                    : String.format("被%s打败了，战斗失败。战斗已经结束。", enemyName);
                sendTaskFinished(defaultReport);
            }
        }).start();
    }

    private static boolean isBattleOver() {
        if (AbstractDungeon.getCurrRoom() == null) return false;
        if (AbstractDungeon.getCurrRoom().monsters == null) return false;
        
        boolean allEnemiesDead = true;
        for (AbstractMonster m : AbstractDungeon.getCurrRoom().monsters.monsters) {
            if (!m.isDead && !m.isDeadOrEscaped()) {
                allEnemiesDead = false;
                break;
            }
        }
        
        boolean playerDead = AbstractDungeon.player.isDead;
        
        boolean battleOver = AbstractDungeon.getCurrRoom().isBattleOver;
        
        return allEnemiesDead || playerDead || battleOver;
    }

    private static String getMonsterIntent(AbstractMonster m) {
        if (m.intent == null) return "未知";
        return m.intent.name();
    }
}
