package com.custom.gin;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.custom.gin.transport.BleTransport;
import com.custom.gin.transport.GinTransport;
import com.custom.gin.transport.RfcommTransport;

import java.util.ArrayList;
import java.util.List;

public class GinConnectionManager implements GinTransport.Listener {
    private static final String TAG = "GinConnectionManager";
    private static final String PREFS_NAME = "gin_glass_prefs";
    private static final String KEY_TRANSPORT = "transport_type";
    private static final String TRANSPORT_BLE = "BLE";
    private static final String TRANSPORT_RFCOMM = "RFCOMM";

    private static GinConnectionManager instance;

    private final Context context;
    private final SharedPreferences prefs;
    private GinTransport activeTransport;
    private String currentTransportType;
    private final List<GinTransport.Listener> listeners = new ArrayList<>();

    private GinConnectionManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.currentTransportType = prefs.getString(KEY_TRANSPORT, TRANSPORT_RFCOMM);
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

        if (TRANSPORT_BLE.equals(currentTransportType)) {
            activeTransport = new BleTransport(context, this);
            Log.d(TAG, "Initialized BLE transport");
        } else {
            activeTransport = new RfcommTransport(this);
            Log.d(TAG, "Initialized RFCOMM transport");
        }

        if (activeTransport != null) {
            activeTransport.start();
        }
    }

    /**
     * Switch transport at runtime. Persists choice to SharedPreferences.
     * Disconnects the current transport in a background thread before
     * initializing the new one.
     */
    public void selectTransport(final String type) {
        final String normalized = TRANSPORT_BLE.equalsIgnoreCase(type) ? TRANSPORT_BLE : TRANSPORT_RFCOMM;
        if (normalized.equals(currentTransportType))
            return;

        prefs.edit().putString(KEY_TRANSPORT, normalized).apply();
        currentTransportType = normalized;

        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (GinConnectionManager.this) {
                    initializeTransport();
                }
            }
        }).start();
    }

    public String getCurrentTransportType() {
        return currentTransportType;
    }

    public GinTransport getTransport() {
        return activeTransport;
    }

    public synchronized void sendData(byte[] data, byte type) {
        if (activeTransport != null) {
            activeTransport.sendData(data, type);
        }
    }

    public synchronized boolean isConnected() {
        return activeTransport != null && activeTransport.isConnected();
    }

    public synchronized void addListener(GinTransport.Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public synchronized void removeListener(GinTransport.Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public void onConnected() {
        Log.d(TAG, "Transport connected");
        synchronized (listeners) {
            for (GinTransport.Listener l : listeners) {
                l.onConnected();
            }
        }
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Transport disconnected");
        synchronized (listeners) {
            for (GinTransport.Listener l : listeners) {
                l.onDisconnected();
            }
        }
    }

    @Override
    public void onDataReceived(byte[] data, byte type) {
        if (type == GinTransport.TYPE_CONTROL) {
            String cmd = new String(data);
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
        synchronized (listeners) {
            for (GinTransport.Listener l : listeners) {
                l.onDataReceived(data, type);
            }
        }
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Transport error: " + message);
        synchronized (listeners) {
            for (GinTransport.Listener l : listeners) {
                l.onError(message);
            }
        }
    }

    @Override
    public void onProgress(int bytesSent, int totalBytes) {
        synchronized (listeners) {
            for (GinTransport.Listener l : listeners) {
                l.onProgress(bytesSent, totalBytes);
            }
        }
    }
}
