package com.custom.gin.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.custom.gin.phone.llm.LlmClient;
import com.custom.gin.phone.stt.AudioTranscriber;
import com.custom.gin.phone.transport.BleServer;
import com.custom.gin.phone.transport.GinTransport;
import com.custom.gin.phone.transport.RfcommServer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

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
    private final AudioTranscriber audioTranscriber;

    private GinTransport serverTransport;
    private StatusListener statusListener;
    private boolean isRunning = false;
    private boolean isConnected = false;

    private GinServerManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("gin_prefs", Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.llmClient = new LlmClient();
        this.audioTranscriber = new AudioTranscriber();
    }

    public static synchronized GinServerManager getInstance(Context context) {
        if (instance == null) {
            instance = new GinServerManager(context);
        }
        return instance;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
        notifyState();
    }

    public synchronized void startServer() {
        if (isRunning) return;
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
        }
    }

    private void handleImageQuery(byte[] imageBytes) {
        // 1. Tell Glass we are thinking
        serverTransport.sendData("THINKING".getBytes(), GinTransport.TYPE_CONTROL);
        addLog("Sent THINKING state to Glass");

        // 2. Load configurations
        final String provider = prefs.getString("provider", "Gemini");
        String apiKey = prefs.getString("api_key", "");
        String modelName = prefs.getString("model_name", "gemini-1.5-flash");
        String customUrl = prefs.getString("custom_url", "");
        String systemPrompt = prefs.getString("system_prompt", "");
        String userPrompt = "Describe what is in this photo taken from my Google Glass camera.";

        addLog("Querying LLM (" + provider + ") with captured photo...");
        llmClient.queryLlm(provider, apiKey, modelName, customUrl, systemPrompt, userPrompt, imageBytes, "image/jpeg", new LlmClient.Callback() {
            @Override
            public void onSuccess(String responseText) {
                addLog("LLM response: " + responseText);
                sendTextResponse(provider, responseText);
            }

            @Override
            public void onFailure(String errorMessage) {
                addLog("LLM Query failed: " + errorMessage);
                sendTextResponse(provider, "Query failed: " + errorMessage);
            }
        });
    }

    private void handleAudioQuery(final byte[] audioBytes) {
        // 1. Tell Glass we are thinking
        serverTransport.sendData("THINKING".getBytes(), GinTransport.TYPE_CONTROL);
        addLog("Sent THINKING state to Glass");

        final String provider = prefs.getString("provider", "Gemini");
        final String apiKey = prefs.getString("api_key", "");
        final String modelName = prefs.getString("model_name", "gemini-1.5-flash");
        final String customUrl = prefs.getString("custom_url", "");
        final String systemPrompt = prefs.getString("system_prompt", "");

        // If Gemini, send raw audio bytes directly to Gemini (multimodal)
        if ("Gemini".equalsIgnoreCase(provider)) {
            addLog("Gemini selected, sending raw audio bytes directly to Gemini multimodal API...");
            llmClient.queryLlm(provider, apiKey, modelName, customUrl, systemPrompt, 
                    "Please answer this voice query", audioBytes, "audio/3gpp", new LlmClient.Callback() {
                @Override
                public void onSuccess(String responseText) {
                    addLog("Gemini Audio response: " + responseText);
                    sendTextResponse(provider, responseText);
                }

                @Override
                public void onFailure(String errorMessage) {
                    addLog("Gemini Audio query failed: " + errorMessage);
                    sendTextResponse(provider, "Query failed: " + errorMessage);
                }
            });
        } else {
            // For other providers (OpenAI, Claude, Custom), transcribe audio to text first using Whisper
            addLog("Transcribing audio using Whisper API...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final File tempFile = File.createTempFile("temp_voice", ".3gp", context.getCacheDir());
                        FileOutputStream fos = new FileOutputStream(tempFile);
                        fos.write(audioBytes);
                        fos.close();

                        // Get OpenAI API key (either saved specifically or fall back to general api_key)
                        // In this basic version, we assume the user saves the OpenAI key in settings if using OpenAI GPT/Whisper
                        String sttKey = "OpenAI GPT".equalsIgnoreCase(provider) ? apiKey : prefs.getString("api_key", "");

                        audioTranscriber.transcribeAudio(tempFile, sttKey, new AudioTranscriber.Callback() {
                            @Override
                            public void onSuccess(String transcribedText) {
                                addLog("Transcribed Text: \"" + transcribedText + "\"");
                                tempFile.delete();

                                // Query LLM with the transcribed text
                                addLog("Querying LLM (" + provider + ") with query text...");
                                llmClient.queryLlm(provider, apiKey, modelName, customUrl, systemPrompt, transcribedText, null, null, new LlmClient.Callback() {
                                    @Override
                                    public void onSuccess(String responseText) {
                                        addLog("LLM response: " + responseText);
                                        sendTextResponse(provider, responseText);
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
                                addLog("Transcription failed: " + errorMessage);
                                tempFile.delete();
                                sendTextResponse(provider, "Transcription failed: " + errorMessage);
                            }
                        });

                    } catch (IOException e) {
                        Log.e(TAG, "Failed to transcribe audio file", e);
                        addLog("Error handling audio file: " + e.getMessage());
                        sendTextResponse(provider, "Audio error: " + e.getMessage());
                    }
                }
            }).start();
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
