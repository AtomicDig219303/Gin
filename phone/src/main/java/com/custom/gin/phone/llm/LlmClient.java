package com.custom.gin.phone.llm;

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
                         String systemPrompt, String userPrompt, byte[] mediaBytes, String mediaMimeType, final Callback callback) {
        
        Log.d(TAG, "Querying LLM: provider=" + provider + ", model=" + modelName);

        String base64Media = null;
        if (mediaBytes != null && mediaBytes.length > 0) {
            base64Media = Base64.encodeToString(mediaBytes, Base64.NO_WRAP);
        }

        try {
            Request request = null;

            if ("Gemini".equalsIgnoreCase(provider)) {
                request = buildGeminiRequest(apiKey, modelName, systemPrompt, userPrompt, base64Media, mediaMimeType);
            } else if ("Anthropic Claude".equalsIgnoreCase(provider)) {
                // Anthropic only supports images for now
                request = buildAnthropicRequest(apiKey, modelName, systemPrompt, userPrompt, base64Media);
            } else if ("OpenAI GPT".equalsIgnoreCase(provider)) {
                // OpenAI chat completion only supports images for now
                request = buildOpenAiRequest(apiKey, modelName, systemPrompt, userPrompt, base64Media);
            } else if ("Custom Self-Hosted".equalsIgnoreCase(provider)) {
                request = buildCustomRequest(customUrl, apiKey, modelName, systemPrompt, userPrompt, base64Media);
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

    private Request buildGeminiRequest(String apiKey, String model, String systemPrompt, String userPrompt, String base64Media, String mediaMimeType) {
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
        JsonArray contents = new JsonArray();
        JsonObject contentObj = new JsonObject();
        JsonArray partsArray = new JsonArray();

        // Text Part
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", userPrompt != null && !userPrompt.isEmpty() ? userPrompt : "Analyze this content.");
        partsArray.add(textPart);

        // Media Part (Image or Audio)
        if (base64Media != null && mediaMimeType != null) {
            JsonObject mediaPart = new JsonObject();
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mimeType", mediaMimeType);
            inlineData.addProperty("data", base64Media);
            mediaPart.add("inlineData", inlineData);
            partsArray.add(mediaPart);
        }

        contentObj.add("parts", partsArray);
        contents.add(contentObj);
        rootObj.add("contents", contents);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .post(reqBody)
                .build();
    }

    private Request buildAnthropicRequest(String apiKey, String model, String systemPrompt, String userPrompt, String base64Image) {
        String url = "https://api.anthropic.com/v1/messages";

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("model", model);
        rootObj.addProperty("system", systemPrompt);
        rootObj.addProperty("max_tokens", 512);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");

        if (base64Image != null) {
            JsonArray contents = new JsonArray();

            JsonObject textObj = new JsonObject();
            textObj.addProperty("type", "text");
            textObj.addProperty("text", userPrompt != null && !userPrompt.isEmpty() ? userPrompt : "Describe this image.");
            contents.add(textObj);

            JsonObject imageObj = new JsonObject();
            imageObj.addProperty("type", "image");
            JsonObject sourceObj = new JsonObject();
            sourceObj.addProperty("type", "base64");
            sourceObj.addProperty("media_type", "image/jpeg");
            sourceObj.addProperty("data", base64Image);
            imageObj.add("source", sourceObj);
            contents.add(imageObj);

            userMsg.add("content", contents);
        } else {
            userMsg.addProperty("content", userPrompt);
        }

        messages.add(userMsg);
        rootObj.add("messages", messages);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(reqBody)
                .build();
    }

    private Request buildOpenAiRequest(String apiKey, String model, String systemPrompt, String userPrompt, String base64Image) {
        String url = "https://api.openai.com/v1/chat/completions";

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("model", model);

        JsonArray messages = new JsonArray();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);
        }

        // User message
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");

        if (base64Image != null) {
            JsonArray contents = new JsonArray();

            JsonObject textObj = new JsonObject();
            textObj.addProperty("type", "text");
            textObj.addProperty("text", userPrompt != null && !userPrompt.isEmpty() ? userPrompt : "Describe this image.");
            contents.add(textObj);

            JsonObject imageObj = new JsonObject();
            imageObj.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Image);
            imageObj.add("image_url", imageUrlObj);
            contents.add(imageObj);

            userMsg.add("content", contents);
        } else {
            userMsg.addProperty("content", userPrompt);
        }

        messages.add(userMsg);
        rootObj.add("messages", messages);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(reqBody)
                .build();
    }

    private Request buildCustomRequest(String customUrl, String apiKey, String model, String systemPrompt, String userPrompt, String base64Image) {
        // Assume OpenAI schema compatible URL
        String url = customUrl.endsWith("/") ? customUrl + "chat/completions" : customUrl + "/chat/completions";

        JsonObject rootObj = new JsonObject();
        if (model != null && !model.isEmpty()) {
            rootObj.addProperty("model", model);
        }

        JsonArray messages = new JsonArray();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);
        }

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        if (base64Image != null) {
            JsonArray contents = new JsonArray();

            JsonObject textObj = new JsonObject();
            textObj.addProperty("type", "text");
            textObj.addProperty("text", userPrompt != null && !userPrompt.isEmpty() ? userPrompt : "Describe this image.");
            contents.add(textObj);

            JsonObject imageObj = new JsonObject();
            imageObj.addProperty("type", "image_url");
            JsonObject imageUrlObj = new JsonObject();
            imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Image);
            imageObj.add("image_url", imageUrlObj);
            contents.add(imageObj);

            userMsg.add("content", contents);
        } else {
            userMsg.addProperty("content", userPrompt);
        }
        messages.add(userMsg);
        rootObj.add("messages", messages);

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
