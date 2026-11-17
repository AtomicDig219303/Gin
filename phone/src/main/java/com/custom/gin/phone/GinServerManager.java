package com.custom.gin.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.custom.gin.phone.chat.ChatMessage;
import com.custom.gin.phone.chat.ChatSession;
import com.custom.gin.phone.llm.LlmClient;
import com.custom.gin.phone.transport.GinTransport;
import com.custom.gin.phone.transport.RfcommServer;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class GinServerManager implements GinTransport.Listener {
    private static final String TAG = "GinServerManager";
    private static final Pattern THINKING_PATTERN = Pattern.compile("(?s)<thinking>.*?</thinking>");
    private static final int MAX_LOG_HISTORY = 100;

    private static GinServerManager instance;

    public interface StatusListener {
        void onServerStateChanged(boolean running, boolean connected);

        void onLogAdded(String logLine);

        void onChatModeChanged(boolean isChatMode);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final LlmClient llmClient;
    private final List<ChatMessage> logHistory = new ArrayList<>();

    private GinTransport serverTransport;
    private StatusListener statusListener;
    private boolean isRunning = false;
    private boolean isConnected = false;

    private final ChatSession[] chatSessions;
    private boolean isChatMode = true;

    private ChatSession getActiveChatSession() {
        int index = prefs.getInt("active_preset_index", 1);
        if (index < 1 || index > 3) index = 1;
        return chatSessions[index];
    }

    private GinServerManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("gin_prefs", Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.llmClient = new LlmClient();
        this.chatSessions = new ChatSession[4];
        for (int i = 1; i <= 3; i++) {
            this.chatSessions[i] = new ChatSession();
        }
    }

    public static synchronized GinServerManager getInstance(Context context) {
        if (instance == null) {
            instance = new GinServerManager(context);
        }
        return instance;
    }

    public boolean isChatMode() {
        return isChatMode;
    }

    public void setChatMode(final boolean chatMode) {
        this.isChatMode = chatMode;
        if (!chatMode) {
            for (int i = 1; i <= 3; i++) {
                chatSessions[i].clear();
            }
            synchronized (logHistory) {
                logHistory.clear();
            }
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onChatModeChanged(chatMode);
                }
            }
        });
    }

    public List<ChatMessage> getLogHistory() {
        synchronized (logHistory) {
            return new ArrayList<>(logHistory);
        }
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
        notifyState();
    }

    public synchronized void startServer() {
        if (isRunning)
            return;
        isRunning = true;
        addLog("Starting server...");

        serverTransport = new RfcommServer(this);
        addLog("Using RFCOMM transport");

        serverTransport.start();
        notifyState();
    }

    public synchronized void stopServer() {
        isRunning = false;
        isConnected = false;
        llmClient.cancelActiveCall();
        addLog("Stopping server...");
        if (serverTransport != null) {
            serverTransport.stop();
            serverTransport = null;
        }
        notifyState();
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isConnected() {
        return isConnected;
    }

    private void notifyState() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onServerStateChanged(isRunning, isConnected);
                }
            }
        });
    }

    private void addLog(final String line) {
        Log.d(TAG, line);
        synchronized (logHistory) {
            logHistory.add(new ChatMessage(ChatMessage.Role.SYSTEM, line));
            while (logHistory.size() > MAX_LOG_HISTORY) {
                logHistory.remove(0);
            }
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (statusListener != null) {
                    statusListener.onLogAdded(line);
                }
            }
        });
    }

    // Transport Listener callbacks
    @Override
    public void onConnected() {
        isConnected = true;
        addLog("Glass client connected");
        notifyState();
        syncPresetsToGlass();
    }

    private static String sanitizePipe(String s) {
        return s == null ? "" : s.replace('|', '-');
    }

    public void syncPresetsToGlass() {
        try {
            int activeIndex = prefs.getInt("active_preset_index", 1);
            String name1 = sanitizePipe(prefs.getString("preset_name_1", "Preset 1"));
            String name2 = sanitizePipe(prefs.getString("preset_name_2", "Preset 2"));
            String name3 = sanitizePipe(prefs.getString("preset_name_3", "Preset 3"));

            String p1 = sanitizePipe(prefs.getString("preset_provider_1", GinConstants.PROVIDER_GEMINI));
            String p2 = sanitizePipe(prefs.getString("preset_provider_2", GinConstants.PROVIDER_GEMINI));
            String p3 = sanitizePipe(prefs.getString("preset_provider_3", GinConstants.PROVIDER_GEMINI));

            String payload = "SYNC_PRESETS:" + activeIndex + "|" + name1 + "|" + name2 + "|" + name3 + "|" + p1 + "|" + p2 + "|" + p3;
            if (serverTransport != null && isConnected) {
                serverTransport.sendData(payload.getBytes(StandardCharsets.UTF_8), GinTransport.TYPE_CONTROL);
                addLog("Sent preset sync to Glass: " + payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing presets", e);
        }
    }

    @Override
    public void onDisconnected() {
        isConnected = false;
        llmClient.cancelActiveCall();
        addLog("Glass client disconnected");
        notifyState();
    }

    @Override
    public void onDataReceived(final byte[] data, final byte type) {
        if (type == GinTransport.TYPE_IMAGE) {
            addLog("Received image from Glass: " + data.length + " bytes");
            handleImageQuery(data);
        } else if (type == GinTransport.TYPE_AUDIO) {
            addLog("Received audio from Glass: " + data.length + " bytes");
            handleAudioQuery(data);
        } else if (type == GinTransport.TYPE_CONTROL) {
            handleControlMessage(new String(data, StandardCharsets.UTF_8));
        }
    }

    private void handleControlMessage(String cmd) {
        addLog("Received control: " + cmd);
        if (cmd.startsWith("SET_PRESET:")) {
            try {
                int index = Integer.parseInt(cmd.substring("SET_PRESET:".length()).trim());
                if (index >= 1 && index <= 3) {
                    prefs.edit().putInt("active_preset_index", index).apply();
                    addLog("Active preset switched to " + index + ". Context preserved independently.");
                    notifyState();
                }
            } catch (Exception e) {
                Log.e(TAG, "Invalid preset index", e);
            }
        } else if (cmd.startsWith("SET_CHAT_MODE:")) {
            boolean mode = Boolean.parseBoolean(cmd.substring("SET_CHAT_MODE:".length()).trim());
            addLog("Glass requested chat mode: " + mode);
            setChatMode(mode);
        } else if ("CANCEL_STREAM".equals(cmd)) {
            llmClient.cancelActiveCall();
            addLog("Canceled active LLM stream");
        }
    }

    private String getSystemPrompt(int activeIndex) {
        String basePrompt = prefs.getString("system_prompt",
                "You are an AI assistant on Google Glass. Keep responses very short, concise, and direct (max 2 sentences). Don't fear going into details if the user has explicitly asked for it.");
        String reasoning = prefs.getString("preset_reasoning_" + activeIndex, "None");
        if ("High".equals(reasoning)) {
            basePrompt += "\n\nBefore answering, please think step by step about your response. Put your thoughts inside <thinking> and </thinking> XML tags. After the closing tag, provide your final concise answer for the user. Do not leak the <thinking> tags into your final answer.";
        }
        return basePrompt;
    }

    private String getApiKeyForProvider(String provider) {
        if (GinConstants.PROVIDER_GEMINI.equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_gemini", "");
        } else if (GinConstants.PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_openai", "");
        } else if (GinConstants.PROVIDER_ANTHROPIC.equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_anthropic", "");
        }
        return "";
    }

    private static String extractCleanText(String rawText, boolean isFinal) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }
        // 1. Strip completed <thinking>...</thinking> blocks
        String clean = THINKING_PATTERN.matcher(rawText).replaceAll("");
        // 2. Strip any unclosed <thinking> block
        int unclosedStart = clean.indexOf("<thinking>");
        if (unclosedStart != -1) {
            clean = clean.substring(0, unclosedStart);
        }
        // 3. While streaming, hold back any trailing partial "<thinking>" tag prefix
        if (!isFinal) {
            final String tag = "<thinking>";
            for (int len = tag.length() - 1; len >= 1; len--) {
                if (clean.endsWith(tag.substring(0, len))) {
                    clean = clean.substring(0, clean.length() - len);
                    break;
                }
            }
        }
        // Strip leading whitespace so emitted offsets stay stable
        int start = 0;
        while (start < clean.length() && Character.isWhitespace(clean.charAt(start))) {
            start++;
        }
        return isFinal ? clean.substring(start).trim() : clean.substring(start);
    }

    private static int findSentenceSplitEnd(String unemitted) {
        int len = unemitted.length();
        if (len < 20) {
            return -1;
        }
        int lastSentenceBoundary = -1;
        for (int i = 18; i < len; i++) {
            char c = unemitted.charAt(i);
            if (c == '\n') {
                lastSentenceBoundary = i + 1;
            } else if ((c == '.' || c == '!' || c == '?') && i + 1 < len
                    && Character.isWhitespace(unemitted.charAt(i + 1))) {
                lastSentenceBoundary = i + 1;
            }
        }
        if (lastSentenceBoundary != -1) {
            return lastSentenceBoundary;
        }
        // If a long run-on clause exceeds 110 chars without sentence punctuation, split at a clause delimiter
        if (len >= 110) {
            for (int i = len - 2; i >= 40; i--) {
                char c = unemitted.charAt(i);
                if ((c == ',' || c == ';' || c == ':') && Character.isWhitespace(unemitted.charAt(i + 1))) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private void startStreamingLlmQuery(final ChatSession targetSession, final ChatMessage userMsg,
                                        final String provider, String apiKey, String modelName,
                                        String customUrl, String systemPrompt, List<ChatMessage> snapshot) {
        final StringBuilder rawBuilder = new StringBuilder();
        final int[] emittedLength = { 0 };

        llmClient.streamLlm(provider, apiKey, modelName, customUrl, systemPrompt, snapshot,
                new LlmClient.StreamCallback() {
                    @Override
                    public void onDelta(String textDelta) {
                        rawBuilder.append(textDelta);
                        String cleanNow = extractCleanText(rawBuilder.toString(), false);
                        if (cleanNow.length() <= emittedLength[0]) {
                            return;
                        }
                        String unemitted = cleanNow.substring(emittedLength[0]);
                        int splitEnd = findSentenceSplitEnd(unemitted);
                        if (splitEnd > 0) {
                            String sentenceDelta = unemitted.substring(0, splitEnd).trim();
                            emittedLength[0] += splitEnd;
                            String cumulativeClean = cleanNow.substring(0, emittedLength[0]).trim();
                            if (!sentenceDelta.isEmpty()) {
                                sendStreamingTextResponse(provider, cumulativeClean, sentenceDelta, false);
                            }
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        String rawFinal = fullText != null && !fullText.isEmpty() ? fullText : rawBuilder.toString();
                        String cleanText = extractCleanText(rawFinal, true);
                        String remainingDelta;
                        if (cleanText.isEmpty()) {
                            cleanText = "(Thinking complete, but no answer provided)";
                            remainingDelta = cleanText;
                        } else if (emittedLength[0] < cleanText.length()) {
                            remainingDelta = cleanText.substring(emittedLength[0]).trim();
                        } else {
                            remainingDelta = "";
                        }

                        addLog("LLM response (" + provider + "): " + cleanText);
                        targetSession.clearMediaFromHistory();
                        targetSession.addMessage(new ChatMessage(ChatMessage.Role.ASSISTANT, cleanText));

                        sendStreamingTextResponse(provider, cleanText, remainingDelta, true);
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        targetSession.removeMessage(userMsg);
                        targetSession.clearMediaFromHistory();
                        addLog("LLM Query failed: " + errorMessage);
                        String errText = "Query failed: " + errorMessage;
                        sendStreamingTextResponse(provider, errText, errText, true);
                    }
                });
    }

    private void handleImageQuery(byte[] imageBytes) {
        if (serverTransport != null && isConnected) {
            serverTransport.sendData("THINKING".getBytes(StandardCharsets.UTF_8), GinTransport.TYPE_CONTROL);
            addLog("Sent THINKING state to Glass");
        }

        final int activeIndex = prefs.getInt("active_preset_index", 1);
        final ChatSession targetSession = getActiveChatSession();
        if (!isChatMode) {
            targetSession.clear();
        }

        String userPrompt = "Describe what is in this photo taken from my Google Glass camera.";
        final ChatMessage msg = new ChatMessage(
                ChatMessage.Role.USER, userPrompt, imageBytes, GinConstants.MIME_TYPE_JPEG);
        targetSession.addMessage(msg);
        List<ChatMessage> snapshot = targetSession.getMessages();

        final String provider = prefs.getString("preset_provider_" + activeIndex, GinConstants.PROVIDER_GEMINI);
        String modelName = prefs.getString("preset_model_" + activeIndex, "gemini-1.5-flash");
        String apiKey = getApiKeyForProvider(provider);
        String customUrl = prefs.getString("custom_url", "");

        addLog("Streaming LLM (" + provider + ") with captured photo...");
        startStreamingLlmQuery(targetSession, msg, provider, apiKey, modelName, customUrl,
                getSystemPrompt(activeIndex), snapshot);
        // Clear raw media bytes from session after request payload is built
        targetSession.clearMediaFromHistory();
    }

    private void handleAudioQuery(final byte[] audioBytes) {
        if (serverTransport != null && isConnected) {
            serverTransport.sendData("THINKING".getBytes(StandardCharsets.UTF_8), GinTransport.TYPE_CONTROL);
            addLog("Sent THINKING state to Glass");
        }

        final int activeIndex = prefs.getInt("active_preset_index", 1);
        final ChatSession targetSession = getActiveChatSession();
        if (!isChatMode) {
            targetSession.clear();
        }

        final String provider = prefs.getString("preset_provider_" + activeIndex, GinConstants.PROVIDER_GEMINI);
        final String modelName = prefs.getString("preset_model_" + activeIndex, "gemini-1.5-flash");
        final String apiKey = getApiKeyForProvider(provider);
        final String customUrl = prefs.getString("custom_url", "");
        // Anthropic /v1/messages does not accept raw audio/wav blocks; always transcribe first
        boolean audioSupported = prefs.getBoolean("preset_audio_" + activeIndex, true)
                && !GinConstants.PROVIDER_ANTHROPIC.equalsIgnoreCase(provider);

        if (audioSupported) {
            addLog(provider + " selected, streaming WAV audio directly to multimodal API...");
            final ChatMessage msg = new ChatMessage(
                    ChatMessage.Role.USER, "Please answer this voice query", audioBytes,
                    GinConstants.MIME_TYPE_WAV);
            targetSession.addMessage(msg);
            List<ChatMessage> snapshot = targetSession.getMessages();

            startStreamingLlmQuery(targetSession, msg, provider, apiKey, modelName, customUrl,
                    getSystemPrompt(activeIndex), snapshot);
            targetSession.clearMediaFromHistory();
        } else {
            addLog("Transcribing WAV audio using gemini-3.1-flash-lite...");
            String geminiApiKey = getApiKeyForProvider(GinConstants.PROVIDER_GEMINI);
            if (geminiApiKey.trim().isEmpty()) {
                addLog("Transcription failed: Gemini API Key is missing.");
                String errText = "Error: Please set a Gemini API Key in the Phone Settings to enable cloud transcription for " + provider + ".";
                sendStreamingTextResponse(provider, errText, errText, true);
                return;
            }

            llmClient.transcribeAudioWithGemini(audioBytes, geminiApiKey, new LlmClient.Callback() {
                @Override
                public void onSuccess(String transcribedText) {
                    addLog("Cloud Transcribed Text: \"" + transcribedText + "\"");
                    final ChatMessage msg = new ChatMessage(ChatMessage.Role.USER, transcribedText);
                    targetSession.addMessage(msg);

                    addLog("Streaming LLM (" + provider + ") with query text...");
                    startStreamingLlmQuery(targetSession, msg, provider, apiKey, modelName, customUrl,
                            getSystemPrompt(activeIndex), targetSession.getMessages());
                }

                @Override
                public void onFailure(String errorMessage) {
                    addLog("Cloud transcription failed: " + errorMessage);
                    String errText = "Transcription failed: " + errorMessage;
                    sendStreamingTextResponse(provider, errText, errText, true);
                }
            });
        }
    }

    private void sendStreamingTextResponse(String provider, String text, String delta, boolean done) {
        try {
            JSONObject response = new JSONObject();
            response.put("provider", provider);
            response.put("text", text);
            response.put("delta", delta);
            response.put("done", done);

            byte[] payload = response.toString().getBytes(StandardCharsets.UTF_8);
            if (serverTransport != null && isConnected) {
                serverTransport.sendData(payload, GinTransport.TYPE_TEXT);
                if (done) {
                    addLog("Completed streaming response to Glass (" + text.length() + " chars)");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send streaming response", e);
            addLog("Error sending response: " + e.getMessage());
        }
    }

    @Override
    public void onError(String message) {
        addLog("Transport error: " + message);
        if (serverTransport != null && !serverTransport.isConnected()) {
            isConnected = false;
        }
        notifyState();
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
    }
}
