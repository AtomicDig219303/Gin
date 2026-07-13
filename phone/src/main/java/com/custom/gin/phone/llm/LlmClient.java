package com.custom.gin.phone.llm;

import com.custom.gin.phone.chat.ChatMessage;

import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LlmClient {
    private static final String TAG = "LlmClient";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public interface Callback {
        void onSuccess(String responseText);
        void onFailure(String errorMessage);
    }

    private final OkHttpClient client;

    public LlmClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void queryLlm(String provider, String apiKey, String modelName, String customUrl, 
                         String systemPrompt, java.util.List<ChatMessage> messages, final Callback callback) {
        
        Log.d(TAG, "Querying LLM: provider=" + provider + ", model=" + modelName);

        try {
            Request request = null;

            if ("Gemini".equalsIgnoreCase(provider)) {
                request = buildGeminiRequest(apiKey, modelName, systemPrompt, messages);
            } else if ("Anthropic Claude".equalsIgnoreCase(provider)) {
                request = buildAnthropicRequest(apiKey, modelName, systemPrompt, messages);
            } else if ("OpenAI GPT".equalsIgnoreCase(provider)) {
                request = buildOpenAiRequest(apiKey, modelName, systemPrompt, messages);
            } else if ("Custom Self-Hosted".equalsIgnoreCase(provider)) {
                request = buildCustomRequest(customUrl, apiKey, modelName, systemPrompt, messages);
            }

            if (request == null) {
                callback.onFailure("Unsupported LLM Provider: " + provider);
                return;
            }

            final Request finalRequest = request;
            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Network call failed", e);
                    callback.onFailure("Network failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "LLM Response unsuccessful: " + response.code() + " - " + body);
                        callback.onFailure("HTTP " + response.code() + ": " + body);
                        return;
                    }

                    try {
                        String extractedText = parseLlmResponse(provider, body);
                        callback.onSuccess(extractedText);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse response body", e);
                        callback.onFailure("Parse failed: " + e.getMessage());
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Request build failed", e);
            callback.onFailure("Request build error: " + e.getMessage());
        }
    }

    public void transcribeAudioWithGemini(final byte[] wavBytes, String geminiApiKey, final Callback callback) {
        try {
            java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
            messages.add(new ChatMessage(ChatMessage.Role.USER, "Transcribe this audio exactly as spoken. Return only the transcription text.", wavBytes, "audio/wav"));

            Request request = buildGeminiRequest(geminiApiKey, "gemini-3.1-flash-lite", "", messages);

            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    callback.onFailure("Gemini STT Network failed: " + e.getMessage());
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    String body = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        callback.onFailure("Gemini STT HTTP " + response.code() + ": " + body);
                        return;
                    }
                    try {
                        String extractedText = parseLlmResponse("Gemini", body);
                        callback.onSuccess(extractedText);
                    } catch (Exception e) {
                        callback.onFailure("Gemini STT Parse failed: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Gemini STT setup failed: " + e.getMessage());
        }
    }

    private Request buildGeminiRequest(String apiKey, String model, String systemPrompt, java.util.List<ChatMessage> messages) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        JsonObject rootObj = new JsonObject();
        
        // System instruction
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemInstruction = new JsonObject();
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", systemPrompt);
            parts.add(part);
            systemInstruction.add("parts", parts);
            rootObj.add("systemInstruction", systemInstruction);
        }

        // Contents
        JsonArray contentsArray = new JsonArray();
        
        for (ChatMessage msg : messages) {
            JsonObject contentObj = new JsonObject();
            contentObj.addProperty("role", msg.getRole() == ChatMessage.Role.USER ? "user" : "model");
            
            JsonArray partsArray = new JsonArray();
            
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", msg.getContent() != null ? msg.getContent() : "");
            partsArray.add(textPart);
            
            if (msg.getMediaBytes() != null) {
                String base64Media = Base64.encodeToString(msg.getMediaBytes(), Base64.NO_WRAP);
                JsonObject mediaPart = new JsonObject();
                JsonObject inlineData = new JsonObject();
                inlineData.addProperty("mimeType", msg.getMimeType() != null ? msg.getMimeType() : "image/jpeg");
                inlineData.addProperty("data", base64Media);
                mediaPart.add("inlineData", inlineData);
                partsArray.add(mediaPart);
            }
            
            contentObj.add("parts", partsArray);
            contentsArray.add(contentObj);
        }

        rootObj.add("contents", contentsArray);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .post(reqBody)
                .build();
    }

    private Request buildAnthropicRequest(String apiKey, String model, String systemPrompt, java.util.List<ChatMessage> messages) {
        String url = "https://api.anthropic.com/v1/messages";

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("model", model);
        rootObj.addProperty("system", systemPrompt);
        rootObj.addProperty("max_tokens", 1024);

        JsonArray messagesArray = new JsonArray();
        
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant");

            if (msg.getMediaBytes() != null && msg.getRole() == ChatMessage.Role.USER && (msg.getMimeType() == null || msg.getMimeType().startsWith("image/"))) {
                JsonArray contents = new JsonArray();

                JsonObject textObj = new JsonObject();
                textObj.addProperty("type", "text");
                textObj.addProperty("text", msg.getContent() != null ? msg.getContent() : "");
                contents.add(textObj);

                String base64Image = Base64.encodeToString(msg.getMediaBytes(), Base64.NO_WRAP);
                JsonObject imageObj = new JsonObject();
                imageObj.addProperty("type", "image");
                JsonObject sourceObj = new JsonObject();
                sourceObj.addProperty("type", "base64");
                sourceObj.addProperty("media_type", "image/jpeg");
                sourceObj.addProperty("data", base64Image);
                imageObj.add("source", sourceObj);
                contents.add(imageObj);

                msgObj.add("content", contents);
            } else {
                msgObj.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
            }
            messagesArray.add(msgObj);
        }

        rootObj.add("messages", messagesArray);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(reqBody)
                .build();
    }

    private Request buildOpenAiRequest(String apiKey, String model, String systemPrompt, java.util.List<ChatMessage> messages) {
        String url = "https://api.openai.com/v1/chat/completions";

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("model", model);

        JsonArray messagesArray = new JsonArray();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messagesArray.add(sysMsg);
        }

        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant");

            if (msg.getMediaBytes() != null && msg.getRole() == ChatMessage.Role.USER) {
                JsonArray contents = new JsonArray();

                JsonObject textObj = new JsonObject();
                textObj.addProperty("type", "text");
                textObj.addProperty("text", msg.getContent() != null ? msg.getContent() : "");
                contents.add(textObj);

                String base64Image = Base64.encodeToString(msg.getMediaBytes(), Base64.NO_WRAP);
                JsonObject imageObj = new JsonObject();
                imageObj.addProperty("type", "image_url");
                JsonObject imageUrlObj = new JsonObject();
                imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Image);
                imageObj.add("image_url", imageUrlObj);
                contents.add(imageObj);

                msgObj.add("content", contents);
            } else {
                msgObj.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
            }
            messagesArray.add(msgObj);
        }

        rootObj.add("messages", messagesArray);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(reqBody)
                .build();
    }

    private Request buildCustomRequest(String customUrl, String apiKey, String model, String systemPrompt, java.util.List<ChatMessage> messages) {
        // Assume OpenAI schema compatible URL
        String url = customUrl.endsWith("/") ? customUrl + "chat/completions" : customUrl + "/chat/completions";

        JsonObject rootObj = new JsonObject();
        if (model != null && !model.isEmpty()) {
            rootObj.addProperty("model", model);
        }

        JsonArray messagesArray = new JsonArray();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messagesArray.add(sysMsg);
        }

        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant");

            if (msg.getMediaBytes() != null && msg.getRole() == ChatMessage.Role.USER) {
                JsonArray contents = new JsonArray();

                JsonObject textObj = new JsonObject();
                textObj.addProperty("type", "text");
                textObj.addProperty("text", msg.getContent() != null ? msg.getContent() : "");
                contents.add(textObj);

                String base64Image = Base64.encodeToString(msg.getMediaBytes(), Base64.NO_WRAP);
                JsonObject imageObj = new JsonObject();
                imageObj.addProperty("type", "image_url");
                JsonObject imageUrlObj = new JsonObject();
                imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Image);
                imageObj.add("image_url", imageUrlObj);
                contents.add(imageObj);

                msgObj.add("content", contents);
            } else {
                msgObj.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
            }
            messagesArray.add(msgObj);
        }
        
        rootObj.add("messages", messagesArray);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder().url(url).post(reqBody);
        
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }
        
        return builder.build();
    }

    private String parseLlmResponse(String provider, String jsonBody) throws Exception {
        JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();

        if ("Gemini".equalsIgnoreCase(provider)) {
            return root.getAsJsonArray("candidates")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("content")
                    .getAsJsonArray("parts")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        } else if ("Anthropic Claude".equalsIgnoreCase(provider)) {
            return root.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();
        } else if ("OpenAI GPT".equalsIgnoreCase(provider) || "Custom Self-Hosted".equalsIgnoreCase(provider)) {
            return root.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        }

        throw new Exception("Unknown provider: " + provider);
    }
}
