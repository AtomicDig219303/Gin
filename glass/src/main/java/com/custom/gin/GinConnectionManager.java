package com.custom.gin;

import android.content.Context;
import android.util.Log;
import com.custom.gin.transport.BleTransport;
import com.custom.gin.transport.GinTransport;
import com.custom.gin.transport.RfcommTransport;

import java.util.ArrayList;
import java.util.List;

public class GinConnectionManager implements GinTransport.Listener {
    private static final String TAG = "GinConnectionManager";
    private static GinConnectionManager instance;

    private final Context context;
    private GinTransport activeTransport;
    private boolean useBle = false; // Default to Bluetooth Classic RFCOMM
    private final List<GinTransport.Listener> listeners = new ArrayList<>();

    private GinConnectionManager(Context context) {
        this.context = context.getApplicationContext();
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

        if (useBle) {
            activeTransport = new BleTransport(context, this);
        } else {
            activeTransport = new RfcommTransport(this);
        }
        
        if (activeTransport != null) {
            activeTransport.start();
        }
    }

    public synchronized void setUseBle(boolean useBle) {
        if (this.useBle != useBle) {
            this.useBle = useBle;
            initializeTransport();
        }
    }

    public boolean isUsingBle() {
        return useBle;
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
