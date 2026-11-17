package com.custom.gin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.custom.gin.camera.ProgrammaticCamera;
import com.custom.gin.transport.GinTransport;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.view.WindowUtils;
import com.google.android.glass.widget.Slider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity
        implements GinTransport.Listener, TextToSpeech.OnInitListener, ProgrammaticCamera.Callback {
    private static final String TAG = "MainActivity";

    // Duration tiers in seconds: 10 -> 15 -> 20 -> 30
    private static final int[] DURATION_TIERS = { 10, 15, 20, 30 };

    // AudioRecord config (8kHz 16-bit mono = 16,000 bytes/sec)
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int WAV_HEADER_SIZE = 44;
    private static final int INITIAL_PCM_CAPACITY = WAV_HEADER_SIZE + (SAMPLE_RATE * 2 * DURATION_TIERS[0]);

    // Watchdog timeout so the Glass prism display & 60fps slider never stay on indefinitely
    private static final long REQUEST_TIMEOUT_MS = 60000L;
    private static final int MENU_ID_SETTINGS = 1001;

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
    private TextView statusFootnote;

    private View responseLayout;
    private ImageView responseProviderIcon;
    private ObservableScrollView responseScroll;
    private TextView responseText;
    private View scrollbarThumb;
    private View scrollbarTrack;

    private Slider mSlider;
    private Slider.Determinate mDeterminateSlider;
    private Slider.Indeterminate mIndeterminateSlider;

    private volatile boolean isRecording = false;
    private boolean isResumed = false;
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

    private final Runnable requestTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            hideSliders();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (statusLayout.getVisibility() == View.VISIBLE) {
                statusText.setText("Request timed out");
                statusFootnote.setVisibility(View.GONE);
                audioManager.playSoundEffect(Sounds.DISALLOWED);
            }
        }
    };

    private final Runnable updateScrollbarRunnable = new Runnable() {
        @Override
        public void run() {
            updateScrollbar();
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
        statusFootnote = (TextView) findViewById(R.id.status_footnote);

        responseLayout = findViewById(R.id.response_layout);
        responseProviderIcon = (ImageView) findViewById(R.id.response_provider_icon);
        responseScroll = (ObservableScrollView) findViewById(R.id.response_scroll);
        responseText = (TextView) findViewById(R.id.response_text);
        scrollbarThumb = findViewById(R.id.scrollbar_thumb);
        scrollbarTrack = findViewById(R.id.scrollbar_track);

        mSlider = Slider.from(findViewById(R.id.root_layout));

        tts = new TextToSpeech(this, this);

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
                    if (responseLayout.getVisibility() == View.VISIBLE && responseScroll != null) {
                        responseScroll.smoothScrollBy(0, 150);
                        return true;
                    }
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    if (responseLayout.getVisibility() == View.VISIBLE && responseScroll != null) {
                        responseScroll.smoothScrollBy(0, -150);
                        return true;
                    }
                } else if (gesture == Gesture.TAP) {
                    if (isRecording) {
                        extendRecordingDuration();
                        return true;
                    } else {
                        stopTtsIfSpeaking();
                        audioManager.playSoundEffect(Sounds.TAP);
                        openOptionsMenu();
                        return true;
                    }
                } else if (gesture == Gesture.TWO_TAP) {
                    if (isRecording) {
                        stopRecordingAndSend();
                        return true;
                    }
                } else if (gesture == Gesture.SWIPE_DOWN) {
                    audioManager.playSoundEffect(Sounds.DISMISSED);
                    finish();
                    return true;
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
        stopTtsIfSpeaking();
        if (isRecording) {
            stopRecording(); // discard current recording
        }
        cancelRequestTimeout();
        hideSliders();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String mode = intent != null ? intent.getStringExtra("start_mode") : null;
        if ("audio".equals(mode)) {
            startRecording();
        } else if ("camera".equals(mode)) {
            capturePhoto();
        } else {
            statusLayout.setVisibility(View.VISIBLE);
            responseLayout.setVisibility(View.GONE);
            setProviderIcon(statusIcon, getActiveProviderName());
            statusText.setText(connectionManager.isConnected() ? "Ready" : "Connecting...");
            timerText.setVisibility(View.GONE);
            statusFootnote.setText("Tap for options");
            statusFootnote.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (gestureDetector != null && gestureDetector.onMotionEvent(event)) {
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        connectionManager.addListener(this);
        if (!connectionManager.isConnected() && !isRecording) {
            statusText.setText("Connecting...");
        }
    }

    @Override
    protected void onPause() {
        isResumed = false;
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
        cancelRequestTimeout();
        hideSliders();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.shutdown();
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
    }

    private void startRequestTimeout() {
        mainHandler.removeCallbacks(requestTimeoutRunnable);
        mainHandler.postDelayed(requestTimeoutRunnable, REQUEST_TIMEOUT_MS);
    }

    private void cancelRequestTimeout() {
        mainHandler.removeCallbacks(requestTimeoutRunnable);
    }

    private void hideSliders() {
        if (mDeterminateSlider != null) {
            mDeterminateSlider.hide();
            mDeterminateSlider = null;
        }
        if (mIndeterminateSlider != null) {
            mIndeterminateSlider.hide();
            mIndeterminateSlider = null;
        }
    }

    private String getActiveProviderName() {
        SharedPreferences p = getSharedPreferences("gin_glass_prefs", Context.MODE_PRIVATE);
        int activeIndex = p.getInt("active_preset_index", 1);
        return p.getString("preset_provider_" + activeIndex, "custom");
    }

    private void startRecording() {
        if (isRecording)
            return;
        stopTtsIfSpeaking();
        cancelRequestTimeout();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        isRecording = true;
        recordDurationSec = 0;
        currentDurationTier = 0;

        int maxSec = DURATION_TIERS[currentDurationTier];

        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Recording...");
        timerText.setVisibility(View.VISIBLE);
        timerText.setText("0/" + maxSec + "s");
        statusFootnote.setText("Tap to extend  •  Two-tap to send");
        statusFootnote.setVisibility(View.VISIBLE);

        hideSliders();
        mDeterminateSlider = mSlider.startDeterminate(maxSec, 0);

        setProviderIcon(statusIcon, "voice");

        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufSize == AudioRecord.ERROR_BAD_VALUE || minBufSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid AudioRecord buffer size: " + minBufSize);
            statusText.setText("Mic init failed");
            statusFootnote.setVisibility(View.GONE);
            hideSliders();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            isRecording = false;
            return;
        }

        final int bufferSize = Math.max(minBufSize, 4096);
        // Pre-allocate for initial 10s tier + 44-byte WAV header to prevent Dalvik heap reallocations
        pcmBuffer = new ByteArrayOutputStream(INITIAL_PCM_CAPACITY);
        pcmBuffer.write(new byte[WAV_HEADER_SIZE], 0, WAV_HEADER_SIZE);

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
                statusFootnote.setVisibility(View.GONE);
                audioRecord.release();
                audioRecord = null;
                hideSliders();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                isRecording = false;
                return;
            }

            audioRecord.startRecording();

            final AudioRecord activeRecord = audioRecord;
            final ByteArrayOutputStream activeBuffer = pcmBuffer;
            recordingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] readBuf = new byte[bufferSize];
                    while (isRecording) {
                        int bytesRead = activeRecord.read(readBuf, 0, readBuf.length);
                        if (bytesRead > 0) {
                            activeBuffer.write(readBuf, 0, bytesRead);
                        }
                    }
                }
            }, "AudioRecordThread");
            recordingThread.start();

            mainHandler.postDelayed(timerRunnable, 1000);

        } catch (Exception e) {
            Log.e(TAG, "AudioRecord setup failed", e);
            statusText.setText("Record failed");
            statusFootnote.setVisibility(View.GONE);
            hideSliders();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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

        audioManager.playSoundEffect(Sounds.TAP);

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
                Log.w(TAG, "audioRecord.stop() failed", e);
            }
        }

        if (recordingThread != null) {
            try {
                recordingThread.join(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "recordingThread.join() interrupted", e);
                Thread.currentThread().interrupt();
            }
            recordingThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "audioRecord.release() failed", e);
            }
            audioRecord = null;
        }

        if (mDeterminateSlider != null) {
            mDeterminateSlider.hide();
            mDeterminateSlider = null;
        }
        timerText.setVisibility(View.GONE);
        statusFootnote.setVisibility(View.GONE);
    }

    private void stopRecordingAndSend() {
        stopRecording();

        if (pcmBuffer == null || pcmBuffer.size() <= WAV_HEADER_SIZE) {
            pcmBuffer = null;
            statusText.setText("No audio captured");
            audioManager.playSoundEffect(Sounds.DISALLOWED);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }
        audioManager.playSoundEffect(Sounds.SUCCESS);

        final byte[] finalAudio = pcmBuffer.toByteArray();
        pcmBuffer = null;

        statusText.setText("Sending...");
        setProviderIcon(statusIcon, getActiveProviderName());

        hideSliders();
        mIndeterminateSlider = mSlider.startIndeterminate();
        mIndeterminateSlider.show();
        startRequestTimeout();

        backgroundExecutor.execute(new Runnable() {
            @Override
            public void run() {
                buildWavHeaderInPlace(finalAudio, finalAudio.length - WAV_HEADER_SIZE, SAMPLE_RATE, 1, 16);
                connectionManager.sendData(finalAudio, GinTransport.TYPE_AUDIO);
            }
        });
    }

    private static void buildWavHeaderInPlace(byte[] wav, int dataLen, int sampleRate, int channels,
            int bitsPerSample) {
        int totalLen = WAV_HEADER_SIZE + dataLen;
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
        stopTtsIfSpeaking();
        if (isRecording) {
            stopRecording();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Capturing...");
        timerText.setVisibility(View.GONE);
        statusFootnote.setVisibility(View.GONE);
        setProviderIcon(statusIcon, "camera");
        startRequestTimeout();
        programmaticCamera.capturePhoto();
    }

    @Override
    public void onPictureTaken(final byte[] jpegData) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isResumed || isFinishing() || isDestroyed())
                    return;
                audioManager.playSoundEffect(Sounds.SUCCESS);
                statusText.setText("Sending...");
                setProviderIcon(statusIcon, getActiveProviderName());

                hideSliders();
                mIndeterminateSlider = mSlider.startIndeterminate();
                mIndeterminateSlider.show();
                startRequestTimeout();
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
                cancelRequestTimeout();
                hideSliders();
                if (!isRecording) {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    statusText.setText("Connecting...");
                }
            }
        });
    }

    @Override
    public void onDataReceived(final byte[] data, final byte type) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isResumed || isFinishing() || isDestroyed())
                    return;
                if (type == GinTransport.TYPE_CONTROL) {
                    String cmd = new String(data, StandardCharsets.UTF_8);
                    if ("THINKING".equals(cmd)) {
                        statusText.setText("Thinking...");
                        setProviderIcon(statusIcon, getActiveProviderName());

                        if (mIndeterminateSlider == null) {
                            mIndeterminateSlider = mSlider.startIndeterminate();
                            mIndeterminateSlider.show();
                        }
                        startRequestTimeout();
                    }
                } else if (type == GinTransport.TYPE_TEXT) {
                    handleTextResponse(new String(data, StandardCharsets.UTF_8));
                }
            }
        });
    }

    private void handleTextResponse(String jsonString) {
        cancelRequestTimeout();
        hideSliders();

        try {
            JSONObject obj = new JSONObject(jsonString);
            String provider = obj.optString("provider", "custom");
            lastResponseText = obj.optString("text", "");

            statusLayout.setVisibility(View.GONE);
            responseLayout.setVisibility(View.VISIBLE);

            responseText.setText(lastResponseText);
            setProviderIcon(responseProviderIcon, provider);
            audioManager.playSoundEffect(Sounds.SUCCESS);

            responseScroll.scrollTo(0, 0);
            mainHandler.removeCallbacks(updateScrollbarRunnable);
            mainHandler.postDelayed(updateScrollbarRunnable, 100);

            if (isTtsReady && !lastResponseText.isEmpty()) {
                speakResponse(lastResponseText);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error parsing text response", e);
            statusLayout.setVisibility(View.GONE);
            responseLayout.setVisibility(View.VISIBLE);
            responseText.setText(jsonString);
            setProviderIcon(responseProviderIcon, "custom");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void setProviderIcon(ImageView imageView, String provider) {
        if ("camera".equals(provider)) {
            imageView.setImageResource(R.drawable.ic_camera_50);
        } else if ("voice".equals(provider)) {
            imageView.setImageResource(R.drawable.ic_microphone_on_50);
        } else if ("Gemini".equalsIgnoreCase(provider)) {
            imageView.setImageResource(R.drawable.ic_aimodel_gemini_64x64);
        } else if ("OpenAI GPT".equalsIgnoreCase(provider)) {
            imageView.setImageResource(R.drawable.ic_aimodel_openai_64x64);
        } else if ("Anthropic Claude".equalsIgnoreCase(provider)) {
            imageView.setImageResource(R.drawable.ic_aimodel_claude_64x64);
        } else {
            imageView.setImageResource(R.drawable.ic_aimodel_generic_64x64);
        }
    }

    private void updateScrollbar() {
        int contentHeight = responseText.getHeight();
        int scrollHeight = responseScroll.getHeight();
        int scrollY = responseScroll.getScrollY();

        int maxScroll = contentHeight - scrollHeight;
        if (maxScroll <= 0) {
            if (scrollbarTrack.getVisibility() != View.GONE) {
                scrollbarTrack.setVisibility(View.GONE);
            }
            return;
        }

        if (scrollbarTrack.getVisibility() != View.VISIBLE) {
            scrollbarTrack.setVisibility(View.VISIBLE);
        }
        int trackHeight = scrollbarTrack.getHeight();
        if (trackHeight <= 0) return;

        int thumbHeight = Math.max(30, (scrollHeight * trackHeight) / contentHeight);

        ViewGroup.LayoutParams params = scrollbarThumb.getLayoutParams();
        if (params.height != thumbHeight) {
            params.height = thumbHeight;
            scrollbarThumb.setLayoutParams(params);
        }

        float progress = (float) scrollY / maxScroll;
        int maxThumbTranslate = trackHeight - thumbHeight;
        scrollbarThumb.setTranslationY(progress * maxThumbTranslate);
    }

    private void speakResponse(String text) {
        if (!isTtsReady || text.isEmpty()) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            return;
        }
        HashMap<String, String> params = new HashMap<>();
        params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "gin_tts_utterance");
        int res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
        if (res == TextToSpeech.ERROR) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void stopTtsIfSpeaking() {
        if (tts != null && isTtsReady && tts.isSpeaking()) {
            tts.stop();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isResumed || isFinishing() || isDestroyed())
                            return;
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
                if (isFinishing() || isDestroyed())
                    return;
                cancelRequestTimeout();
                hideSliders();
                statusLayout.setVisibility(View.VISIBLE);
                responseLayout.setVisibility(View.GONE);
                timerText.setVisibility(View.GONE);
                statusFootnote.setVisibility(View.GONE);
                statusText.setText("Error: " + message);
                audioManager.playSoundEffect(Sounds.DISALLOWED);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        // Humanize titles for touchpad menu while keeping ContextualMenus.Command enums in voice_menu.xml
        MenuItem recordItem = menu.findItem(R.id.menu_record);
        if (recordItem != null) {
            recordItem.setTitle("Make a request");
        }
        MenuItem cameraItem = menu.findItem(R.id.menu_camera);
        if (cameraItem != null) {
            cameraItem.setTitle("Check this out");
        }
        menu.add(Menu.NONE, MENU_ID_SETTINGS, Menu.NONE, "Settings")
                .setIcon(R.drawable.ic_gear_50);
        MenuItem stopItem = menu.findItem(R.id.menu_stop);
        if (stopItem != null) {
            stopItem.setTitle("Dismiss");
        }
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
            } else if (itemId == MENU_ID_SETTINGS) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            } else if (itemId == R.id.menu_stop) {
                audioManager.playSoundEffect(Sounds.DISMISSED);
                finish();
                return true;
            }
        }
        return super.onMenuItemSelected(featureId, item);
    }
}
