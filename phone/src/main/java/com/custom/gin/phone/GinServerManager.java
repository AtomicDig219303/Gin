package com.custom.gin.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.custom.gin.phone.chat.ChatSession;
import com.custom.gin.phone.llm.LlmClient;
import com.custom.gin.phone.transport.BleServer;
import com.custom.gin.phone.transport.GinTransport;
import com.custom.gin.phone.transport.RfcommServer;

import org.json.JSONObject;

public class GinServerManager implements GinTransport.Listener {
    private static final String TAG = "GinServerManager";
    private static GinServerManager instance;

    public interface StatusListener {
        void onServerStateChanged(boolean running, boolean connected);

        void onLogAdded(String logLine);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler;
    private final LlmClient llmClient;

    private GinTransport serverTransport;
    private StatusListener statusListener;
    private boolean isRunning = false;
    private boolean isConnected = false;

    private com.custom.gin.phone.chat.ChatSession chatSession;
    private boolean isChatMode = true;

    private GinServerManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("gin_prefs", Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.llmClient = new LlmClient();
        this.chatSession = new com.custom.gin.phone.chat.ChatSession();
    }

    public static synchronized GinServerManager getInstance(Context context) {
        if (instance == null) {
            instance = new GinServerManager(context);
        }
        return instance;
    }

    public void setChatMode(boolean chatMode) {
        this.isChatMode = chatMode;
        if (!chatMode) {
            chatSession.clear();
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

        boolean useBle = prefs.getBoolean("use_ble", false);
        if (useBle) {
            serverTransport = new BleServer(context, this);
            addLog("Using BLE transport");
        } else {
            serverTransport = new RfcommServer(this);
            addLog("Using RFCOMM transport");
        }

        serverTransport.start();
        notifyState();
    }

    public synchronized void stopServer() {
        isRunning = false;
        isConnected = false;
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

    private void syncPresetsToGlass() {
        try {
            int activeIndex = prefs.getInt("active_preset_index", 1);
            String name1 = prefs.getString("preset_name_1", "Preset 1");
            String name2 = prefs.getString("preset_name_2", "Preset 2");
            String name3 = prefs.getString("preset_name_3", "Preset 3");

            String payload = "SYNC_PRESETS:" + activeIndex + "|" + name1 + "|" + name2 + "|" + name3;
            if (serverTransport != null && isConnected) {
                serverTransport.sendData(payload.getBytes("UTF-8"), GinTransport.TYPE_CONTROL);
                addLog("Sent preset sync to Glass: " + payload);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing presets", e);
        }
    }

    @Override
    public void onDisconnected() {
        isConnected = false;
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
            handleControlMessage(new String(data));
        }
    }

    private void handleControlMessage(String cmd) {
        addLog("Received control: " + cmd);
        if (cmd.startsWith("SET_PRESET:")) {
            try {
                int index = Integer.parseInt(cmd.substring("SET_PRESET:".length()));
                prefs.edit().putInt("active_preset_index", index).apply();
                addLog("Active preset switched to " + index);
            } catch (Exception e) {
                Log.e(TAG, "Invalid preset index", e);
            }
        }
    }

    private String getSystemPrompt() {
        String basePrompt = prefs.getString("system_prompt", "");
        int activeIndex = prefs.getInt("active_preset_index", 1);
        String reasoning = prefs.getString("preset_reasoning_" + activeIndex, "None");
        if ("High".equals(reasoning)) {
            basePrompt += "\n\nBefore answering, please think step by step about your response. Put your thoughts inside <thinking> and </thinking> XML tags. After the closing tag, provide your final concise answer for the user. Do not leak the <thinking> tags into your final answer.";
        }
        return basePrompt;
    }

    private String getApiKeyForProvider(String provider) {
        if ("Gemini".equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_gemini", "");
        } else if ("OpenAI GPT".equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_openai", "");
        } else if ("Anthropic Claude".equalsIgnoreCase(provider)) {
            return prefs.getString("api_key_anthropic", "");
        }
        return "";
    }

    private void handleLlmResponse(String provider, String responseText) {
        addLog("Raw LLM response: " + responseText);

        // Strip out <thinking> tags before sending back to Glass
        String cleanText = responseText;
        int thinkStart = cleanText.indexOf("<thinking>");
        int thinkEnd = cleanText.indexOf("</thinking>");
        if (thinkStart != -1 && thinkEnd != -1 && thinkEnd > thinkStart) {
            cleanText = cleanText.substring(0, thinkStart) + cleanText.substring(thinkEnd + "</thinking>".length());
            cleanText = cleanText.trim();
        } else if (thinkStart != -1) {
            // Unclosed thinking tag
            cleanText = cleanText.substring(0, thinkStart).trim();
        }

        // Ensure there is something to say
        if (cleanText.isEmpty()) {
            cleanText = "(Thinking complete, but no answer provided)";
        }

        // Add assistant response to history
        chatSession.addMessage(new com.custom.gin.phone.chat.ChatMessage(
                com.custom.gin.phone.chat.ChatMessage.Role.ASSISTANT, responseText));

        sendTextResponse(provider, cleanText);
    }

    private void handleImageQuery(byte[] imageBytes) {
        serverTransport.sendData("THINKING".getBytes(), GinTransport.TYPE_CONTROL);
        addLog("Sent THINKING state to Glass");

        if (!isChatMode) {
            chatSession.clear();
        }

        String userPrompt = "Describe what is in this photo taken from my Google Glass camera.";
        com.custom.gin.phone.chat.ChatMessage msg = new com.custom.gin.phone.chat.ChatMessage(
                com.custom.gin.phone.chat.ChatMessage.Role.USER, userPrompt, imageBytes, "image/jpeg");
        chatSession.addMessage(msg);

        int activeIndex = prefs.getInt("active_preset_index", 1);
        final String provider = prefs.getString("preset_provider_" + activeIndex, "Gemini");
        String modelName = prefs.getString("preset_model_" + activeIndex, "gemini-1.5-flash");
        String apiKey = getApiKeyForProvider(provider);
        String customUrl = prefs.getString("custom_url", "");

        addLog("Querying LLM (" + provider + ") with captured photo...");
        llmClient.queryLlm(provider, apiKey, modelName, customUrl, getSystemPrompt(), chatSession.getMessages(),
                new LlmClient.Callback() {
                    @Override
                    public void onSuccess(String responseText) {
                        handleLlmResponse(provider, responseText);
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        addLog("LLM Query failed: " + errorMessage);
                        sendTextResponse(provider, "Query failed: " + errorMessage);
                    }
                });
    }

    private void handleAudioQuery(final byte[] audioBytes) {
        serverTransport.sendData("THINKING".getBytes(), GinTransport.TYPE_CONTROL);
        addLog("Sent THINKING state to Glass");

        if (!isChatMode) {
            chatSession.clear();
        }

        int activeIndex = prefs.getInt("active_preset_index", 1);
        final String provider = prefs.getString("preset_provider_" + activeIndex, "Gemini");
        final String modelName = prefs.getString("preset_model_" + activeIndex, "gemini-1.5-flash");
        final String apiKey = getApiKeyForProvider(provider);
        final String customUrl = prefs.getString("custom_url", "");
        boolean audioSupported = prefs.getBoolean("preset_audio_" + activeIndex, true);

        if (audioSupported) {
            addLog(provider + " selected, sending WAV audio directly to multimodal API...");
            com.custom.gin.phone.chat.ChatMessage msg = new com.custom.gin.phone.chat.ChatMessage(
                    com.custom.gin.phone.chat.ChatMessage.Role.USER, "Please answer this voice query", audioBytes,
                    "audio/wav");
            chatSession.addMessage(msg);

            llmClient.queryLlm(provider, apiKey, modelName, customUrl, getSystemPrompt(), chatSession.getMessages(),
                    new LlmClient.Callback() {
                        @Override
                        public void onSuccess(String responseText) {
                            handleLlmResponse(provider, responseText);
                        }

                        @Override
                        public void onFailure(String errorMessage) {
                            addLog(provider + " Audio query failed: " + errorMessage);
                            sendTextResponse(provider, "Query failed: " + errorMessage);
                        }
                    });
        } else {
            addLog("Transcribing WAV audio using gemini-3.1-flash-lite...");
            String geminiApiKey = getApiKeyForProvider("Gemini");
            if (geminiApiKey.trim().isEmpty()) {
                addLog("Transcription failed: Gemini API Key is missing.");
                sendTextResponse(provider, "Error: Please set a Gemini API Key in the Phone Settings to enable cloud transcription for " + provider + ".");
                return;
            }
            
            llmClient.transcribeAudioWithGemini(audioBytes, geminiApiKey, new LlmClient.Callback() {
                @Override
                public void onSuccess(String transcribedText) {
                    addLog("Cloud Transcribed Text: \"" + transcribedText + "\"");
                    com.custom.gin.phone.chat.ChatMessage msg = new com.custom.gin.phone.chat.ChatMessage(
                            com.custom.gin.phone.chat.ChatMessage.Role.USER, transcribedText);
                    chatSession.addMessage(msg);

                    addLog("Querying LLM (" + provider + ") with query text...");
                    llmClient.queryLlm(provider, apiKey, modelName, customUrl, getSystemPrompt(),
                            chatSession.getMessages(), new LlmClient.Callback() {
                                @Override
                                public void onSuccess(String responseText) {
                                    handleLlmResponse(provider, responseText);
                                }

                                @Override
                                public void onFailure(String errorMessage) {
                                    addLog("LLM Query failed: " + errorMessage);
                                    sendTextResponse(provider, "Query failed: " + errorMessage);
                                }
                            });
                }

                @Override
                public void onFailure(String errorMessage) {
                    addLog("Cloud transcription failed: " + errorMessage);
                    sendTextResponse(provider, "Transcription failed: " + errorMessage);
                }
            });
        }
    }

    private void sendTextResponse(String provider, String text) {
        try {
            JSONObject response = new JSONObject();
            response.put("provider", provider);
            response.put("text", text);

            byte[] payload = response.toString().getBytes("UTF-8");
            if (serverTransport != null && isConnected) {
                serverTransport.sendData(payload, GinTransport.TYPE_TEXT);
                addLog("Sent response back to Glass client (" + payload.length + " bytes)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send response", e);
            addLog("Error sending response: " + e.getMessage());
        }
    }

    @Override
    public void onError(String message) {
        addLog("Transport error: " + message);
        notifyState();
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
        // Log progress updates for debug
    }
}
