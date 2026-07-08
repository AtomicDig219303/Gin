package com.custom.gin.transport;

public interface GinTransport {
    byte TYPE_TEXT = 1;
    byte TYPE_AUDIO = 2;
    byte TYPE_IMAGE = 3;
    byte TYPE_CONTROL = 4;

    interface Listener {
        void onConnected();
        void onDisconnected();
        void onDataReceived(byte[] data, byte type);
        void onError(String message);
        void onProgress(int bytesSent, int totalBytes);
    }

    void start();
    void stop();
    void sendData(byte[] data, byte type);
    boolean isConnected();
}
