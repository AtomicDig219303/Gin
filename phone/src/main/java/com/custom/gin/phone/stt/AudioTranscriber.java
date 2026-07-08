package com.custom.gin.phone.stt;

import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AudioTranscriber {
    private static final String TAG = "AudioTranscriber";
    
    public interface Callback {
        void onSuccess(String transcribedText);
        void onFailure(String errorMessage);
    }

    private final OkHttpClient client;

    public AudioTranscriber() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void transcribeAudio(File audioFile, String apiKey, final Callback callback) {
        Log.d(TAG, "Transcribing audio file: " + audioFile.length() + " bytes using Whisper API");

        if (apiKey == null || apiKey.isEmpty()) {
            callback.onFailure("OpenAI API Key is required for Whisper transcription");
            return;
        }

        RequestBody fileBody = RequestBody.create(audioFile, MediaType.parse("audio/3gpp"));
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.3gp", fileBody)
                .addFormDataPart("model", "whisper-1")
                .build();

        Request request = new Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Whisper transcription failed", e);
                callback.onFailure("Whisper call failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Whisper Response unsuccessful: " + response.code() + " - " + body);
                    callback.onFailure("Whisper HTTP " + response.code() + ": " + body);
                    return;
                }

                try {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    String text = root.get("text").getAsString();
                    Log.d(TAG, "Whisper Transcription output: " + text);
                    callback.onSuccess(text);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to parse Whisper response", e);
                    callback.onFailure("Whisper parse failed: " + e.getMessage());
                }
            }
        });
    }
}
