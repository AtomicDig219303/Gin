package com.custom.gin.phone.llm;

import com.custom.gin.phone.GinConstants;
import com.custom.gin.phone.chat.ChatMessage;

import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class LlmClient {
    private static final String TAG = "LlmClient";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public interface Callback {
        void onSuccess(String responseText);
        void onFailure(String errorMessage);
    }

    public interface StreamCallback {
        void onDelta(String textDelta);
        void onComplete(String fullText);
        void onFailure(String errorMessage);
    }

    private final OkHttpClient client;
    private Call activeCall;

    public LlmClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public synchronized void cancelActiveCall() {
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    private synchronized void setActiveCall(Call call) {
        if (activeCall != null) {
            activeCall.cancel();
        }
        activeCall = call;
    }

    private synchronized void clearActiveCall(Call call) {
        if (activeCall == call) {
            activeCall = null;
        }
    }

    public void streamLlm(final String provider, String apiKey, String modelName, String customUrl,
                          String systemPrompt, List<ChatMessage> messages, final StreamCallback callback) {

        Log.d(TAG, "Streaming LLM (SSE): provider=" + provider + ", model=" + modelName);

        try {
            Request request = null;

            if (GinConstants.PROVIDER_GEMINI.equalsIgnoreCase(provider)) {
                request = buildGeminiRequest(apiKey, modelName, systemPrompt, messages, true);
            } else if (GinConstants.PROVIDER_ANTHROPIC.equalsIgnoreCase(provider)) {
                request = buildAnthropicRequest(apiKey, modelName, systemPrompt, messages, true);
            } else if (GinConstants.PROVIDER_OPENAI.equalsIgnoreCase(provider)) {
                request = buildOpenAiRequest(apiKey, modelName, systemPrompt, messages, true);
            } else if (GinConstants.PROVIDER_CUSTOM.equalsIgnoreCase(provider)) {
                request = buildCustomRequest(customUrl, apiKey, modelName, systemPrompt, messages, true);
            }

            if (request == null) {
                callback.onFailure("Unsupported LLM Provider: " + provider);
                return;
            }

            Call call = client.newCall(request);
            setActiveCall(call);

            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    clearActiveCall(call);
                    if (call.isCanceled()) {
                        Log.d(TAG, "Stream call canceled");
                        return;
                    }
                    Log.e(TAG, "Network stream call failed", e);
                    callback.onFailure("Network failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        ResponseBody responseBody = response.body();
                        if (!response.isSuccessful()) {
                            String errBody = responseBody != null ? responseBody.string() : "";
                            Log.e(TAG, "LLM Stream Response unsuccessful: " + response.code() + " - " + errBody);
                            callback.onFailure("HTTP " + response.code() + ": " + errBody);
                            return;
                        }

                        if (responseBody == null) {
                            callback.onFailure("Empty response body from " + provider);
                            return;
                        }

                        StringBuilder fullTextBuilder = new StringBuilder();
                        StringBuilder rawFallbackBuilder = new StringBuilder();
                        boolean sawSseData = false;

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (call.isCanceled()) {
                                return;
                            }
                            String trimmed = line.trim();
                            if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")) {
                                continue;
                            }
                            if (trimmed.startsWith("data:")) {
                                sawSseData = true;
                                String dataPayload = trimmed.substring(5).trim();
                                if ("[DONE]".equals(dataPayload)) {
                                    break;
                                }
                                try {
                                    String delta = parseSseDataLine(provider, dataPayload);
                                    if (delta != null && !delta.isEmpty()) {
                                        fullTextBuilder.append(delta);
                                        callback.onDelta(delta);
                                    }
                                } catch (SseStreamErrorException sseErr) {
                                    callback.onFailure(sseErr.getMessage());
                                    return;
                                } catch (Exception parseEx) {
                                    Log.w(TAG, "Ignoring malformed SSE chunk: " + parseEx.getMessage());
                                }
                            } else if (!sawSseData) {
                                rawFallbackBuilder.append(line).append('\n');
                            }
                        }

                        if (call.isCanceled()) {
                            return;
                        }

                        if (!sawSseData && rawFallbackBuilder.length() > 0) {
                            try {
                                String extractedText = parseLlmResponse(provider, rawFallbackBuilder.toString());
                                if (extractedText != null && !extractedText.isEmpty()) {
                                    callback.onDelta(extractedText);
                                }
                                callback.onComplete(extractedText != null ? extractedText : "");
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to parse non-SSE fallback body", e);
                                callback.onFailure("Parse failed: " + e.getMessage());
                            }
                        } else {
                            callback.onComplete(fullTextBuilder.toString());
                        }
                    } finally {
                        clearActiveCall(call);
                        response.close();
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Stream request build failed", e);
            callback.onFailure("Request build error: " + e.getMessage());
        }
    }

    public void queryLlm(final String provider, String apiKey, String modelName, String customUrl,
                         String systemPrompt, List<ChatMessage> messages, final Callback callback) {
        streamLlm(provider, apiKey, modelName, customUrl, systemPrompt, messages, new StreamCallback() {
            @Override
            public void onDelta(String textDelta) {
            }

            @Override
            public void onComplete(String fullText) {
                callback.onSuccess(fullText);
            }

            @Override
            public void onFailure(String errorMessage) {
                callback.onFailure(errorMessage);
            }
        });
    }

    public void transcribeAudioWithGemini(final byte[] wavBytes, String geminiApiKey, final Callback callback) {
        try {
            List<ChatMessage> messages = new ArrayList<>(1);
            messages.add(new ChatMessage(
                    ChatMessage.Role.USER,
                    "Transcribe this audio. Return only the exact spoken words.",
                    wavBytes,
                    GinConstants.MIME_TYPE_WAV));

            Request request = buildGeminiRequest(geminiApiKey, "gemini-3.1-flash-lite", "", messages, false);

            Call call = client.newCall(request);
            setActiveCall(call);

            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    clearActiveCall(call);
                    if (call.isCanceled()) {
                        return;
                    }
                    callback.onFailure("Gemini STT Network failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String body = response.body() != null ? response.body().string() : "";
                        if (!response.isSuccessful()) {
                            callback.onFailure("Gemini STT HTTP " + response.code() + ": " + body);
                            return;
                        }
                        try {
                            String extractedText = parseLlmResponse(GinConstants.PROVIDER_GEMINI, body);
                            callback.onSuccess(extractedText);
                        } catch (Exception e) {
                            callback.onFailure("Gemini STT Parse failed: " + e.getMessage());
                        }
                    } finally {
                        clearActiveCall(call);
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            callback.onFailure("Gemini STT setup failed: " + e.getMessage());
        }
    }

    private Request buildGeminiRequest(String apiKey, String model, String systemPrompt, List<ChatMessage> messages, boolean stream) {
        String endpoint = stream ? ":streamGenerateContent?alt=sse&key=" : ":generateContent?key=";
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + endpoint + apiKey;

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
                inlineData.addProperty("mimeType", msg.getMimeType() != null ? msg.getMimeType() : GinConstants.MIME_TYPE_JPEG);
                inlineData.addProperty("data", base64Media);
                mediaPart.add("inlineData", inlineData);
                partsArray.add(mediaPart);
            }

            contentObj.add("parts", partsArray);
            contentsArray.add(contentObj);
        }

        rootObj.add("contents", contentsArray);

        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(reqBody);
        if (stream) {
            builder.addHeader("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private Request buildAnthropicRequest(String apiKey, String model, String systemPrompt, List<ChatMessage> messages, boolean stream) {
        String url = "https://api.anthropic.com/v1/messages";

        JsonObject rootObj = new JsonObject();
        rootObj.addProperty("model", model);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            rootObj.addProperty("system", systemPrompt);
        }
        rootObj.addProperty("max_tokens", 1024);
        if (stream) {
            rootObj.addProperty("stream", true);
        }

        JsonArray messagesArray = new JsonArray();

        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant");

            if (msg.getMediaBytes() != null && msg.getRole() == ChatMessage.Role.USER
                    && (msg.getMimeType() == null || msg.getMimeType().startsWith("image/"))) {
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
                sourceObj.addProperty("media_type", GinConstants.MIME_TYPE_JPEG);
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
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(reqBody);
        if (stream) {
            builder.addHeader("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private Request buildOpenAiRequest(String apiKey, String model, String systemPrompt, List<ChatMessage> messages, boolean stream) {
        String url = "https://api.openai.com/v1/chat/completions";
        JsonObject rootObj = buildOpenAiCompatibleBody(model, systemPrompt, messages, stream);
        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(reqBody);
        if (stream) {
            builder.addHeader("Accept", "text/event-stream");
        }
        return builder.build();
    }

    private Request buildCustomRequest(String customUrl, String apiKey, String model, String systemPrompt, List<ChatMessage> messages, boolean stream) {
        String sanitizedUrl = customUrl != null ? customUrl.trim() : "";
        if (!sanitizedUrl.startsWith("http://") && !sanitizedUrl.startsWith("https://")) {
            sanitizedUrl = "http://" + sanitizedUrl;
        }
        String url;
        if (sanitizedUrl.endsWith("/chat/completions") || sanitizedUrl.endsWith("chat/completions")) {
            url = sanitizedUrl;
        } else {
            url = sanitizedUrl.endsWith("/") ? sanitizedUrl + "chat/completions" : sanitizedUrl + "/chat/completions";
        }

        JsonObject rootObj = buildOpenAiCompatibleBody(model, systemPrompt, messages, stream);
        RequestBody reqBody = RequestBody.create(rootObj.toString(), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder().url(url).post(reqBody);

        if (apiKey != null && !apiKey.isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }
        if (stream) {
            builder.addHeader("Accept", "text/event-stream");
        }

        return builder.build();
    }

    private JsonObject buildOpenAiCompatibleBody(String model, String systemPrompt, List<ChatMessage> messages, boolean stream) {
        JsonObject rootObj = new JsonObject();
        if (model != null && !model.isEmpty()) {
            rootObj.addProperty("model", model);
        }
        if (stream) {
            rootObj.addProperty("stream", true);
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

                String base64Media = Base64.encodeToString(msg.getMediaBytes(), Base64.NO_WRAP);
                String mime = msg.getMimeType();
                if (GinConstants.MIME_TYPE_WAV.equals(mime) || (mime != null && mime.startsWith("audio/"))) {
                    JsonObject audioObj = new JsonObject();
                    audioObj.addProperty("type", "input_audio");
                    JsonObject inputAudioObj = new JsonObject();
                    inputAudioObj.addProperty("data", base64Media);
                    inputAudioObj.addProperty("format", "wav");
                    audioObj.add("input_audio", inputAudioObj);
                    contents.add(audioObj);
                } else {
                    JsonObject imageObj = new JsonObject();
                    imageObj.addProperty("type", "image_url");
                    JsonObject imageUrlObj = new JsonObject();
                    imageUrlObj.addProperty("url", "data:image/jpeg;base64," + base64Media);
                    imageObj.add("image_url", imageUrlObj);
                    contents.add(imageObj);
                }

                msgObj.add("content", contents);
            } else {
                msgObj.addProperty("content", msg.getContent() != null ? msg.getContent() : "");
            }
            messagesArray.add(msgObj);
        }

        rootObj.add("messages", messagesArray);
        return rootObj;
    }

    private String parseLlmResponse(String provider, String jsonBody) throws Exception {
        JsonObject root = JsonParser.parseString(jsonBody).getAsJsonObject();

        if (GinConstants.PROVIDER_GEMINI.equalsIgnoreCase(provider)) {
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                throw new Exception("Empty candidates array from Gemini");
            }
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            if (content == null) {
                throw new Exception("Missing content object from Gemini");
            }
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) {
                throw new Exception("Empty parts array from Gemini");
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : parts) {
                JsonObject partObj = el.getAsJsonObject();
                if (partObj.has("thought") && partObj.get("thought").getAsBoolean()) {
                    continue;
                }
                if (partObj.has("text") && !partObj.get("text").isJsonNull()) {
                    sb.append(partObj.get("text").getAsString());
                }
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
            return parts.get(0).getAsJsonObject().get("text").getAsString();
        } else if (GinConstants.PROVIDER_ANTHROPIC.equalsIgnoreCase(provider)) {
            JsonArray content = root.getAsJsonArray("content");
            if (content == null || content.size() == 0) {
                throw new Exception("Empty content array from Anthropic");
            }
            for (JsonElement el : content) {
                JsonObject block = el.getAsJsonObject();
                if (block.has("type") && "thinking".equals(block.get("type").getAsString())) {
                    continue;
                }
                if (block.has("text") && !block.get("text").isJsonNull()) {
                    return block.get("text").getAsString();
                }
            }
            return content.get(0).getAsJsonObject().get("text").getAsString();
        } else if (GinConstants.PROVIDER_OPENAI.equalsIgnoreCase(provider)
                || GinConstants.PROVIDER_CUSTOM.equalsIgnoreCase(provider)) {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new Exception("Empty choices array from " + provider);
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new Exception("Missing message content from " + provider);
            }
            return message.get("content").getAsString();
        }

        throw new Exception("Unknown provider: " + provider);
    }

    private String parseSseDataLine(String provider, String jsonLine) throws Exception {
        if (jsonLine == null || jsonLine.isEmpty() || "[DONE]".equals(jsonLine)) {
            return "";
        }
        JsonElement parsed = JsonParser.parseString(jsonLine);
        if (!parsed.isJsonObject()) {
            return "";
        }
        JsonObject root = parsed.getAsJsonObject();

        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject errObj = root.getAsJsonObject("error");
            String msg = errObj.has("message") && !errObj.get("message").isJsonNull()
                    ? errObj.get("message").getAsString()
                    : errObj.toString();
            throw new SseStreamErrorException("API Error: " + msg);
        }

        if (GinConstants.PROVIDER_GEMINI.equalsIgnoreCase(provider)) {
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return "";
            }
            JsonObject cand = candidates.get(0).getAsJsonObject();
            if (!cand.has("content") || !cand.get("content").isJsonObject()) {
                return "";
            }
            JsonObject content = cand.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            if (parts == null || parts.size() == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : parts) {
                if (!el.isJsonObject()) continue;
                JsonObject partObj = el.getAsJsonObject();
                if (partObj.has("thought") && !partObj.get("thought").isJsonNull()
                        && partObj.get("thought").getAsBoolean()) {
                    continue;
                }
                if (partObj.has("text") && !partObj.get("text").isJsonNull()) {
                    sb.append(partObj.get("text").getAsString());
                }
            }
            return sb.toString();
        } else if (GinConstants.PROVIDER_ANTHROPIC.equalsIgnoreCase(provider)) {
            String type = root.has("type") && !root.get("type").isJsonNull()
                    ? root.get("type").getAsString()
                    : "";
            if ("error".equals(type)) {
                throw new SseStreamErrorException("Anthropic stream error: " + jsonLine);
            }
            if ("content_block_delta".equals(type) && root.has("delta") && root.get("delta").isJsonObject()) {
                JsonObject delta = root.getAsJsonObject("delta");
                String deltaType = delta.has("type") && !delta.get("type").isJsonNull()
                        ? delta.get("type").getAsString()
                        : "";
                if ("text_delta".equals(deltaType) && delta.has("text") && !delta.get("text").isJsonNull()) {
                    return delta.get("text").getAsString();
                }
            }
            return "";
        } else if (GinConstants.PROVIDER_OPENAI.equalsIgnoreCase(provider)
                || GinConstants.PROVIDER_CUSTOM.equalsIgnoreCase(provider)) {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return "";
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta.has("content") && !delta.get("content").isJsonNull()) {
                    return delta.get("content").getAsString();
                }
            } else if (choice.has("message") && choice.get("message").isJsonObject()) {
                JsonObject message = choice.getAsJsonObject("message");
                if (message.has("content") && !message.get("content").isJsonNull()) {
                    return message.get("content").getAsString();
                }
            }
            return "";
        }

        return "";
    }

    private static class SseStreamErrorException extends Exception {
        SseStreamErrorException(String message) {
            super(message);
        }
    }
}

