package com.custom.gin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
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
import com.custom.gin.camera.ProgrammaticCamera;
import org.json.JSONObject;

import android.view.MotionEvent;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.widget.Slider;

import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity
        implements GinTransport.Listener, TextToSpeech.OnInitListener, ProgrammaticCamera.Callback {
    private static final String TAG = "MainActivity";

    // Duration tiers in seconds: 10 -> 15 -> 20 -> 30
    private static final int[] DURATION_TIERS = { 10, 15, 20, 30 };

    // AudioRecord config
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private GinConnectionManager connectionManager;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private ByteArrayOutputStream pcmBuffer;
    private Handler mainHandler;
    private TextToSpeech tts;
    private AudioManager audioManager;
    private ProgrammaticCamera programmaticCamera;
    private ExecutorService backgroundExecutor;

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

    private Slider mSlider;
    private Slider.Determinate mDeterminateSlider;
    private Slider.Indeterminate mIndeterminateSlider;

    private boolean isRecording = false;
    private int recordDurationSec = 0;
    private int currentDurationTier = 0; // index into DURATION_TIERS
    private String lastResponseText = "";
    private boolean isTtsReady = false;
    private GestureDetector gestureDetector;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                recordDurationSec++;
                int maxSec = DURATION_TIERS[currentDurationTier];
                timerText.setText(recordDurationSec + "/" + maxSec + "s");
                if (mDeterminateSlider != null) {
                    mDeterminateSlider.setPosition(recordDurationSec);
                }
                if (recordDurationSec >= maxSec) {
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

        getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS);

        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        backgroundExecutor = Executors.newSingleThreadExecutor();
        connectionManager = GinConnectionManager.getInstance(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Bind views
        statusLayout = findViewById(R.id.status_layout);
        statusIcon = (ImageView) findViewById(R.id.status_icon);
        statusText = (TextView) findViewById(R.id.status_text);
        timerText = (TextView) findViewById(R.id.overlay_timer_text);

        responseLayout = findViewById(R.id.response_layout);
        responseProviderIcon = (ImageView) findViewById(R.id.response_provider_icon);
        responseScroll = (ObservableScrollView) findViewById(R.id.response_scroll);
        responseText = (TextView) findViewById(R.id.response_text);
        scrollbarThumb = findViewById(R.id.scrollbar_thumb);
        scrollbarTrack = findViewById(R.id.scrollbar_track);

        mSlider = Slider.from(findViewById(R.id.root_layout));

        tts = new TextToSpeech(this, this);

        responseLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopTtsIfSpeaking();
            }
        });

        responseScroll.setScrollListener(new ObservableScrollView.ScrollListener() {
            @Override
            public void onScrollChanged(int l, int t, int oldl, int oldt) {
                updateScrollbar();
            }
        });

        gestureDetector = new GestureDetector(this);
        gestureDetector.setBaseListener(new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                if (gesture == Gesture.SWIPE_RIGHT) {
                    if (responseScroll != null) {
                        responseScroll.smoothScrollBy(0, 150);
                    }
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    if (responseScroll != null) {
                        responseScroll.smoothScrollBy(0, -150);
                    }
                    return true;
                } else if (gesture == Gesture.TAP) {
                    if (isRecording) {
                        extendRecordingDuration();
                        return true;
                    } else {
                        stopTtsIfSpeaking();
                        openOptionsMenu();
                        return true;
                    }
                } else if (gesture == Gesture.TWO_TAP) {
                    if (isRecording) {
                        stopRecordingAndSend();
                        return true;
                    }
                }
                return false;
            }
        });

        ViewGroup rootLayout = (ViewGroup) findViewById(android.R.id.content);
        programmaticCamera = new ProgrammaticCamera(this, rootLayout, this);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.d(TAG, "onNewIntent received");
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        // Reset state if coming from a new trigger while something was open
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
        if (isRecording) {
            stopRecording(); // discard current recording
        }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String mode = intent.getStringExtra("start_mode");
        if ("audio".equals(mode)) {
            startRecording();
        } else if ("camera".equals(mode)) {
            capturePhoto();
        } else {
            statusLayout.setVisibility(View.VISIBLE);
            responseLayout.setVisibility(View.GONE);
            statusText.setText("Ready");
            timerText.setVisibility(View.GONE);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gestureDetector != null) {
            return gestureDetector.onMotionEvent(event);
        }
        return super.onGenericMotionEvent(event);
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
        if (programmaticCamera != null) {
            programmaticCamera.release();
        }
        if (tts != null) {
            tts.stop();
        }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.shutdown();
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
    }

    private void startRecording() {
        if (isRecording)
            return;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        isRecording = true;
        recordDurationSec = 0;
        currentDurationTier = 0;

        int maxSec = DURATION_TIERS[currentDurationTier];

        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Recording...");
        timerText.setVisibility(View.VISIBLE);
        timerText.setText("0/" + maxSec + "s");

        if (mIndeterminateSlider != null) {
            mIndeterminateSlider.hide();
            mIndeterminateSlider = null;
        }
        if (mDeterminateSlider != null) {
            mDeterminateSlider.hide();
        }
        mDeterminateSlider = mSlider.startDeterminate(maxSec, 0);

        setProviderIcon(statusIcon, "voice");

        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufSize == AudioRecord.ERROR_BAD_VALUE || minBufSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid AudioRecord buffer size: " + minBufSize);
            statusText.setText("Mic init failed");
            isRecording = false;
            return;
        }

        final int bufferSize = Math.max(minBufSize, 4096);
        pcmBuffer = new ByteArrayOutputStream();

        // Zero-copy optimization: reserve 44 bytes for the WAV header upfront
        for (int i = 0; i < 44; i++) {
            pcmBuffer.write(0);
        }

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                statusText.setText("Mic init failed");
                audioRecord.release();
                audioRecord = null;
                isRecording = false;
                return;
            }

            audioRecord.startRecording();

            recordingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] readBuf = new byte[bufferSize];
                    while (isRecording) {
                        int bytesRead = audioRecord.read(readBuf, 0, readBuf.length);
                        if (bytesRead > 0) {
                            pcmBuffer.write(readBuf, 0, bytesRead);
                        }
                    }
                }
            }, "AudioRecordThread");
            recordingThread.start();

            mainHandler.postDelayed(timerRunnable, 1000);

        } catch (Exception e) {
            Log.e(TAG, "AudioRecord setup failed", e);
            statusText.setText("Record failed");
            isRecording = false;
        }
    }

    private void extendRecordingDuration() {
        if (!isRecording)
            return;
        if (currentDurationTier >= DURATION_TIERS.length - 1) {
            return;
        }

        currentDurationTier++;
        int newMaxSec = DURATION_TIERS[currentDurationTier];

        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);

        statusText.setText("Recording...");
        timerText.setText(recordDurationSec + "/" + newMaxSec + "s");

        if (mDeterminateSlider != null) {
            mDeterminateSlider.hide();
        }
        mDeterminateSlider = mSlider.startDeterminate(newMaxSec, recordDurationSec);
    }

    private void stopRecording() {
        if (!isRecording)
            return;
        isRecording = false;
        mainHandler.removeCallbacks(timerRunnable);

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
            }
            recordingThread = null;
        }
    }

    private void stopRecordingAndSend() {
        stopRecording();

        if (pcmBuffer == null || pcmBuffer.size() == 0) {
            statusText.setText("No audio captured");
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }

        final byte[] finalAudio = pcmBuffer.toByteArray();
        pcmBuffer = null;

        statusText.setText("Sending...");
        timerText.setVisibility(View.GONE);

        if (mDeterminateSlider != null) {
            mDeterminateSlider.hide();
            mDeterminateSlider = null;
        }
        if (mIndeterminateSlider != null) {
            mIndeterminateSlider.hide();
            mIndeterminateSlider = null;
        }
        mIndeterminateSlider = mSlider.startIndeterminate();
        mIndeterminateSlider.show();

        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                buildWavHeaderInPlace(finalAudio, finalAudio.length - 44, SAMPLE_RATE, 1, 16);
                connectionManager.sendData(finalAudio, GinTransport.TYPE_AUDIO);
            }
        });
    }

    private static void buildWavHeaderInPlace(byte[] wav, int dataLen, int sampleRate, int channels,
            int bitsPerSample) {
        int totalLen = 44 + dataLen;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        intToLittleEndian(totalLen - 8, wav, 4);
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';

        wav[12] = 'f';
        wav[13] = 'm';
        wav[14] = 't';
        wav[15] = ' ';
        intToLittleEndian(16, wav, 16);
        shortToLittleEndian((short) 1, wav, 20);
        shortToLittleEndian((short) channels, wav, 22);
        intToLittleEndian(sampleRate, wav, 24);
        intToLittleEndian(byteRate, wav, 28);
        shortToLittleEndian((short) blockAlign, wav, 32);
        shortToLittleEndian((short) bitsPerSample, wav, 34);

        wav[36] = 'd';
        wav[37] = 'a';
        wav[38] = 't';
        wav[39] = 'a';
        intToLittleEndian(dataLen, wav, 40);
    }

    private static void intToLittleEndian(int value, byte[] buf, int offset) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void shortToLittleEndian(short value, byte[] buf, int offset) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    // Camera features
    private void capturePhoto() {
        if (isRecording) {
            stopRecording();
        }
        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Capturing...");
        timerText.setVisibility(View.GONE);
        setProviderIcon(statusIcon, "camera");
        programmaticCamera.capturePhoto();
    }

    @Override
    public void onPictureTaken(final byte[] jpegData) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("Sending...");
                if (mIndeterminateSlider != null) {
                    mIndeterminateSlider.hide();
                }
                mIndeterminateSlider = mSlider.startIndeterminate();
                mIndeterminateSlider.show();
                sendImageBytes(jpegData);
            }
        });
    }

    private void sendImageBytes(final byte[] jpegData) {
        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                connectionManager.sendData(jpegData, GinTransport.TYPE_IMAGE);
            }
        });
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
                        if (mIndeterminateSlider == null) {
                            mIndeterminateSlider = mSlider.startIndeterminate();
                            mIndeterminateSlider.show();
                        }
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

            if (mIndeterminateSlider != null) {
                mIndeterminateSlider.hide();
                mIndeterminateSlider = null;
            }

            statusLayout.setVisibility(View.GONE);
            responseLayout.setVisibility(View.VISIBLE);

            responseText.setText(lastResponseText);
            setProviderIcon(responseProviderIcon, provider);

            responseScroll.scrollTo(0, 0);
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateScrollbar();
                }
            }, 100);

            if (isTtsReady) {
                speakResponse(lastResponseText);
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
        if ("camera".equals(provider)) {
            imageView.setImageResource(R.drawable.ic_camera_50);
            return;
        } else if ("voice".equals(provider)) {
            imageView.setImageResource(R.drawable.ic_microphone_on_50);
            return;
        }

        String resName = "ic_" + provider.toLowerCase();
        int resId = getResources().getIdentifier(resName, "drawable", getPackageName());
        if (resId != 0) {
            imageView.setImageResource(resId);
        } else {
            imageView.setImageResource(android.graphics.Color.TRANSPARENT);
        }
    }

    private void updateScrollbar() {
        int contentHeight = responseText.getHeight();
        int scrollHeight = responseScroll.getHeight();
        int scrollY = responseScroll.getScrollY();

        int maxScroll = contentHeight - scrollHeight;
        if (maxScroll <= 0) {
            scrollbarTrack.setVisibility(View.GONE);
            return;
        }

        scrollbarTrack.setVisibility(View.VISIBLE);
        int trackHeight = scrollbarTrack.getHeight();

        int thumbHeight = Math.max(30, (scrollHeight * trackHeight) / contentHeight);

        ViewGroup.LayoutParams params = scrollbarThumb.getLayoutParams();
        params.height = thumbHeight;
        scrollbarThumb.setLayoutParams(params);

        float progress = (float) scrollY / maxScroll;
        int maxThumbTranslate = trackHeight - thumbHeight;
        scrollbarThumb.setTranslationY(progress * maxThumbTranslate);
    }

    private void speakResponse(String text) {
        if (!isTtsReady || text.isEmpty())
            return;
        java.util.HashMap<String, String> params = new java.util.HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "gin_tts_utterance");
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
    }

    private void stopTtsIfSpeaking() {
        if (!isTtsReady || lastResponseText.isEmpty())
            return;
        if (tts.isSpeaking()) {
            tts.stop();
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
                setupTtsListener();
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    private void setupTtsListener() {
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed())
                            return;
                        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
            }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed())
                            return;
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed())
                            return;
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
            }
        });
    }

    @Override
    public void onError(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mIndeterminateSlider != null) {
                    mIndeterminateSlider.hide();
                    mIndeterminateSlider = null;
                }
                statusText.setText("Error: " + message);
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
    }

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
                capturePhoto();
                return true;
            } else if (itemId == R.id.menu_stop) {
                finish();
                return true;
            }
        }
        return super.onMenuItemSelected(featureId, item);
    }
}
