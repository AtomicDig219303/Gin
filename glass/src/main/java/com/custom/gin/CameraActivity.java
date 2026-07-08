package com.custom.gin;

import android.app.Activity;
import android.content.Intent;
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
import com.custom.gin.camera.ProgrammaticCamera;
import com.custom.gin.transport.GinTransport;

import org.json.JSONObject;

import java.util.Locale;

public class CameraActivity extends Activity implements GinTransport.Listener, TextToSpeech.OnInitListener, ProgrammaticCamera.Callback {
    private static final String TAG = "CameraActivity";

    private GinConnectionManager connectionManager;
    private ProgrammaticCamera programmaticCamera;
    private Handler mainHandler;
    private TextToSpeech tts;

    // UI elements
    private View statusLayout;
    private ImageView statusIcon;
    private TextView statusText;

    private View responseLayout;
    private ImageView responseProviderIcon;
    private ObservableScrollView responseScroll;
    private TextView responseText;
    private View scrollbarThumb;
    private View scrollbarTrack;

    private String lastResponseText = "";
    private boolean isTtsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Request voice commands feature BEFORE setContentView
        getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS);

        setContentView(R.layout.activity_camera);

        mainHandler = new Handler(Looper.getMainLooper());
        connectionManager = GinConnectionManager.getInstance(this);

        // Bind views
        statusLayout = findViewById(R.id.status_layout);
        statusIcon = (ImageView) findViewById(R.id.status_icon);
        statusText = (TextView) findViewById(R.id.status_text);

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

        // Initialize Camera module with root layout as parent for dummy SurfaceView
        ViewGroup rootLayout = (ViewGroup) findViewById(android.R.id.content);
        programmaticCamera = new ProgrammaticCamera(this, rootLayout, this);

        // Auto-capture photo if triggered
        if ("com.google.android.glass.action.VOICE_TRIGGER".equals(getIntent().getAction())
                || getIntent().getBooleanExtra("auto_capture", true)) {
            capturePhoto();
        } else {
            statusText.setText("Ready");
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

    private void capturePhoto() {
        statusLayout.setVisibility(View.VISIBLE);
        responseLayout.setVisibility(View.GONE);
        statusText.setText("Capturing...");
        setProviderIcon(statusIcon, "camera");
        programmaticCamera.capturePhoto();
    }

    // ProgrammaticCamera Callback
    @Override
    public void onPictureTaken(final byte[] jpegData) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText("Sending...");
                sendImageBytes(jpegData);
            }
        });
    }

    private void sendImageBytes(final byte[] jpegData) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                connectionManager.sendData(jpegData, GinTransport.TYPE_IMAGE);
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
                Intent intent = new Intent(this, VoiceActivity.class);
                intent.putExtra("auto_record", true);
                startActivity(intent);
                finish();
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
