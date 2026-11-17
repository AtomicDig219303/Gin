package com.custom.gin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.custom.gin.transport.GinTransport;
import com.custom.gin.transport.RfcommTransport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class GinConnectionManager implements GinTransport.Listener {
    private static final String TAG = "GinConnectionManager";
    private static final String PREFS_NAME = "gin_glass_prefs";
    private static final String KEY_LAST_BT_ADDRESS = "last_connected_bt_address";
    private static final long IDLE_STOP_DELAY_MS = 15000L;

    private static GinConnectionManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private RfcommTransport activeTransport;
    private final List<GinTransport.Listener> listeners = new CopyOnWriteArrayList<>();

    private final Runnable idleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (GinConnectionManager.this) {
                if (listeners.isEmpty() && activeTransport != null && !activeTransport.isConnected()) {
                    Log.d(TAG, "No active UI listeners and not connected; stopping background RFCOMM retries to save battery");
                    activeTransport.stop();
                }
            }
        }
    };

    private GinConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        initializeTransport();
    }

    public static synchronized GinConnectionManager getInstance(Context context) {
        if (instance == null) {
            instance = new GinConnectionManager(context);
        }
        return instance;
    }

    private void initializeTransport() {
        if (activeTransport != null) {
            activeTransport.stop();
        }

        activeTransport = new RfcommTransport(this);
        activeTransport.setPreferredDeviceAddress(prefs.getString(KEY_LAST_BT_ADDRESS, null));
        Log.d(TAG, "Initialized RFCOMM transport");
        activeTransport.start();
    }

    public synchronized void ensureConnected() {
        mainHandler.removeCallbacks(idleCheckRunnable);
        if (activeTransport == null) {
            initializeTransport();
        } else if (!activeTransport.isConnected()) {
            activeTransport.start();
        }
    }

    public GinTransport getTransport() {
        return activeTransport;
    }

    public synchronized void sendData(byte[] data, byte type) {
        if (activeTransport != null) {
            if (!activeTransport.isConnected()) {
                activeTransport.start();
            }
            activeTransport.sendData(data, type);
        }
    }

    public synchronized boolean isConnected() {
        return activeTransport != null && activeTransport.isConnected();
    }

    public void addListener(GinTransport.Listener listener) {
        mainHandler.removeCallbacks(idleCheckRunnable);
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        ensureConnected();
    }

    public void removeListener(GinTransport.Listener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            mainHandler.postDelayed(idleCheckRunnable, IDLE_STOP_DELAY_MS);
        }
    }

    @Override
    public void onConnected() {
        Log.d(TAG, "Transport connected");
        if (activeTransport != null) {
            String connectedAddr = activeTransport.getPreferredDeviceAddress();
            if (connectedAddr != null) {
                prefs.edit().putString(KEY_LAST_BT_ADDRESS, connectedAddr).apply();
            }
        }
        for (GinTransport.Listener l : listeners) {
            l.onConnected();
        }
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Transport disconnected");
        if (listeners.isEmpty()) {
            mainHandler.postDelayed(idleCheckRunnable, IDLE_STOP_DELAY_MS);
        }
        for (GinTransport.Listener l : listeners) {
            l.onDisconnected();
        }
    }

    @Override
    public void onDataReceived(byte[] data, byte type) {
        if (type == GinTransport.TYPE_CONTROL) {
            String cmd = new String(data, StandardCharsets.UTF_8);
            if (cmd.startsWith("SYNC_PRESETS:")) {
                String[] parts = cmd.substring("SYNC_PRESETS:".length()).split("\\|");
                if (parts.length >= 7) {
                    try {
                        prefs.edit()
                                .putInt("active_preset_index", Integer.parseInt(parts[0]))
                                .putString("preset_name_1", parts[1])
                                .putString("preset_name_2", parts[2])
                                .putString("preset_name_3", parts[3])
                                .putString("preset_provider_1", parts[4])
                                .putString("preset_provider_2", parts[5])
                                .putString("preset_provider_3", parts[6])
                                .apply();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing presets", e);
                    }
                } else if (parts.length >= 4) {
                    try {
                        prefs.edit()
                                .putInt("active_preset_index", Integer.parseInt(parts[0]))
                                .putString("preset_name_1", parts[1])
                                .putString("preset_name_2", parts[2])
                                .putString("preset_name_3", parts[3])
                                .apply();
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing presets", e);
                    }
                }
            }
        }
        for (GinTransport.Listener l : listeners) {
            l.onDataReceived(data, type);
        }
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Transport error: " + message);
        for (GinTransport.Listener l : listeners) {
            l.onError(message);
        }
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
        for (GinTransport.Listener l : listeners) {
            l.onProgress(bytesSent, totalBytes);
        }
    }
}
