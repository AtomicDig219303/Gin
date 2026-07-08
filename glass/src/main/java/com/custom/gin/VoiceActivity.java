package com.custom.gin;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.glass.view.WindowUtils;
import com.custom.gin.transport.GinTransport;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

public class VoiceActivity extends Activity implements GinTransport.Listener, TextToSpeech.OnInitListener {
    private static final String TAG = "VoiceActivity";
    private static final int MAX_RECORD_TIME_MS = 30000;

    private GinConnectionManager connectionManager;
    private MediaRecorder mediaRecorder;
    private File audioFile;
    private Handler mainHandler;
    private TextToSpeech tts;

    // UI elements
    private View statusLayout;
    private ImageView statusIcon;
    private TextView statusText;
    private TextView timerText;

    private View responseLayout;
    private ImageView responseProviderIcon;
    private ObservableScrollView responseScroll;
    private TextView responseText;
    private View scrollbarThumb;
    private View scrollbarTrack;

    private boolean isRecording = false;
    private int recordDurationSec = 0;
    private String lastResponseText = "";
    private boolean isTtsReady = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                recordDurationSec++;
                timerText.setText(recordDurationSec + "/30s");
                if (recordDurationSec >= 30) {
                    stopRecordingAndSend();
                } else {
                    mainHandler.postDelayed(this, 1000);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Request voice commands feature BEFORE setContentView
        getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS);
        
        setContentView(R.layout.activity_voice);

        mainHandler = new Handler(Looper.getMainLooper());
        connectionManager = GinConnectionManager.getInstance(this);

        // Bind views
        statusLayout = findViewById(R.id.status_layout);
        statusIcon = (ImageView) findViewById(R.id.status_icon);
        statusText = (TextView) findViewById(R.id.status_text);
        timerText = (TextView) findViewById(R.id.timer_text);

        responseLayout = findViewById(R.id.response_layout);
        responseProviderIcon = (ImageView) findViewById(R.id.response_provider_icon);
        responseScroll = (ObservableScrollView) findViewById(R.id.response_scroll);
        responseText = (TextView) findViewById(R.id.response_text);
        scrollbarThumb = findViewById(R.id.scrollbar_thumb);
        scrollbarTrack = findViewById(R.id.scrollbar_track);

        // Initialize TTS
        tts = new TextToSpeech(this, this);

        // Set up click listener on response layout to toggle TTS on Tap
        responseLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTts();
            }
        });

        // Set up custom scrollbar listener
        responseScroll.setScrollListener(new ObservableScrollView.ScrollListener() {
            @Override
            public void onScrollChanged(int l, int t, int oldl, int oldt) {
                updateScrollbar();
            }
        });

        // Auto-start recording if launched via voice trigger
        if ("com.google.android.glass.action.VOICE_TRIGGER".equals(getIntent().getAction()) 
            || getIntent().getBooleanExtra("auto_record", true)) {
            startRecording();
        } else {
            statusText.setText("Ready");
            timerText.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        connectionManager.addListener(this);
        if (!connectionManager.getTransport().isConnected()) {
            statusText.setText("Connecting...");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        connectionManager.removeListener(this);
        if (isRecording) {
            stopRecording();
        }
        if (tts != null) {
            tts.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.shutdown();
        }
    }

    private void startRecording() {
        if (isRecording) return;
        isRecording = true;
        recordDurationSec = 0;

        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Recording...");
        timerText.setVisibility(View.VISIBLE);
        timerText.setText("0/30s");

        // Set status icon to recording state (we can use the default or a drawable)
        setProviderIcon(statusIcon, "voice");

        try {
            audioFile = File.createTempFile("gin_voice", ".3gp", getCacheDir());
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.setMaxDuration(MAX_RECORD_TIME_MS);
            
            mediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopRecordingAndSend();
                    }
                }
            });

            mediaRecorder.prepare();
            mediaRecorder.start();

            // Start countdown timer
            mainHandler.postDelayed(timerRunnable, 1000);

        } catch (IOException e) {
            Log.e(TAG, "MediaRecorder failed", e);
            statusText.setText("Record failed");
            isRecording = false;
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        mainHandler.removeCallbacks(timerRunnable);

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (Exception e) {
                // Ignore
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    private void stopRecordingAndSend() {
        stopRecording();
        if (audioFile != null && audioFile.exists()) {
            sendAudioFile(audioFile);
        } else {
            statusText.setText("No audio captured");
        }
    }

    private void sendAudioFile(final File file) {
        statusText.setText("Sending...");
        timerText.setVisibility(View.GONE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    FileInputStream fis = new FileInputStream(file);
                    byte[] data = new byte[(int) file.length()];
                    int bytesRead = 0;
                    while (bytesRead < data.length) {
                        int r = fis.read(data, bytesRead, data.length - bytesRead);
                        if (r == -1) break;
                        bytesRead += r;
                    }
                    fis.close();

                    connectionManager.sendData(data, GinTransport.TYPE_AUDIO);

                    // Delete temp file
                    file.delete();

                } catch (IOException e) {
                    Log.e(TAG, "Failed to read audio file", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setText("File read error");
                        }
                    });
                }
            }
        }).start();
    }

    // Bluetooth callbacks
    @Override
    public void onConnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusText.getText().toString().equals("Connecting...")) {
                    statusText.setText("Ready");
                }
            }
        });
    }

    @Override
    public void onDisconnected() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("Connecting...");
            }
        });
    }

    @Override
    public void onDataReceived(final byte[] data, final byte type) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (type == GinTransport.TYPE_CONTROL) {
                    String cmd = new String(data);
                    if ("THINKING".equals(cmd)) {
                        statusText.setText("Thinking...");
                    }
                } else if (type == GinTransport.TYPE_TEXT) {
                    handleTextResponse(new String(data));
                }
            }
        });
    }

    private void handleTextResponse(String jsonString) {
        try {
            JSONObject obj = new JSONObject(jsonString);
            String provider = obj.optString("provider", "custom");
            lastResponseText = obj.optString("text", "");

            statusLayout.setVisibility(View.GONE);
            responseLayout.setVisibility(View.VISIBLE);

            responseText.setText(lastResponseText);
            setProviderIcon(responseProviderIcon, provider);

            // Reset scroll position and recalculate scrollbar
            responseScroll.scrollTo(0, 0);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateScrollbar();
                }
            }, 100);

            // Auto-speak if setting is enabled or default
            if (isTtsReady) {
                tts.speak(lastResponseText, TextToSpeech.QUEUE_FLUSH, null);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing text response", e);
            statusLayout.setVisibility(View.GONE);
            responseLayout.setVisibility(View.VISIBLE);
            responseText.setText(jsonString);
            setProviderIcon(responseProviderIcon, "custom");
        }
    }

    private void setProviderIcon(ImageView imageView, String provider) {
        String resName = "ic_" + provider.toLowerCase();
        int resId = getResources().getIdentifier(resName, "drawable", getPackageName());
        if (resId != 0) {
            imageView.setImageResource(resId);
        } else {
            // Default built-in fallback
            imageView.setImageResource(android.graphics.Color.TRANSPARENT);
        }
    }

    private void updateScrollbar() {
        int contentHeight = responseText.getHeight();
        int scrollHeight = responseScroll.getHeight();
        int scrollY = responseScroll.getScrollY();

        int maxScroll = contentHeight - scrollHeight;
        if (maxScroll <= 0) {
            // Content fits, hide scrollbar track
            scrollbarTrack.setVisibility(View.GONE);
            return;
        }

        scrollbarTrack.setVisibility(View.VISIBLE);
        int trackHeight = scrollbarTrack.getHeight();
        
        // Calculate thumb size proportionally
        int thumbHeight = Math.max(30, (scrollHeight * trackHeight) / contentHeight);
        
        // Update layout parameters of thumb
        ViewGroup.LayoutParams params = scrollbarThumb.getLayoutParams();
        params.height = thumbHeight;
        scrollbarThumb.setLayoutParams(params);

        // Calculate thumb position
        float progress = (float) scrollY / maxScroll;
        int maxThumbTranslate = trackHeight - thumbHeight;
        scrollbarThumb.setTranslationY(progress * maxThumbTranslate);
    }

    private void toggleTts() {
        if (!isTtsReady || lastResponseText.isEmpty()) return;
        if (tts.isSpeaking()) {
            tts.stop();
        } else {
            tts.speak(lastResponseText, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported");
            } else {
                isTtsReady = true;
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("Error: " + message);
            }
        });
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
        // Option to display progress % on screen
    }

    // Contextual voice commands implementation
    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId == WindowUtils.FEATURE_VOICE_COMMANDS) {
            getMenuInflater().inflate(R.menu.voice_menu, menu);
            return true;
        }
        return super.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.voice_menu, menu);
        return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (featureId == WindowUtils.FEATURE_VOICE_COMMANDS ||
                featureId == Window.FEATURE_OPTIONS_PANEL) {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_record) {
                startRecording();
                return true;
            } else if (itemId == R.id.menu_camera) {
                Intent intent = new Intent(this, CameraActivity.class);
                intent.putExtra("auto_capture", true);
                startActivity(intent);
                finish();
                return true;
            } else if (itemId == R.id.menu_stop) {
                finish();
                return true;
            }
        }
        return super.onMenuItemSelected(featureId, item);
    }
}
