package com.custom.gin.phone.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class RfcommServer implements GinTransport {
    private static final String TAG = "RfcommServer";
    private static final UUID GIN_UUID = UUID.fromString("8ce255c0-200a-11eb-adc1-0242ac120002");
    private static final byte MAGIC_BYTE = 0x19;

    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private AcceptThread acceptThread;
    private ConnectedThread connectedThread;
    private boolean isRunning = false;
    private boolean isConnected = false;

    public RfcommServer(Listener listener) {
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        Log.d(TAG, "Starting RFCOMM Server...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled");
            return;
        }

        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    @Override
    public synchronized void stop() {
        isRunning = false;
        isConnected = false;
        Log.d(TAG, "Stopping RFCOMM Server...");
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    @Override
    public synchronized void sendData(final byte[] data, final byte type) {
        if (connectedThread == null || !isConnected) {
            listener.onError("No client connected");
            return;
        }
        connectedThread.write(data, type);
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    private synchronized void clientConnected(BluetoothSocket socket) {
        if (!isRunning) {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
            return;
        }

        // Cancel accept thread since we only support one client
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
        isConnected = true;
        listener.onConnected();
    }

    private synchronized void connectionLost() {
        isConnected = false;
        listener.onDisconnected();
        if (isRunning) {
            Log.d(TAG, "Connection lost, restarting server...");
            stop();
            start();
        }
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord("GinServer", GIN_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Server Socket listen() failed", e);
            }
            serverSocket = tmp;
        }

        public void run() {
            setName("AcceptThread");
            BluetoothSocket socket = null;

            while (isRunning && !isConnected) {
                try {
                    if (serverSocket == null) {
                        break;
                    }
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Server Socket accept() failed", e);
                    break;
                }

                if (socket != null) {
                    clientConnected(socket);
                    break;
                }
            }
        }

        public void cancel() {
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Server Socket close() failed", e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        public void run() {
            setName("ConnectedThread");
            byte[] lengthBuffer = new byte[4];

            while (isRunning) {
                try {
                    // 1. Read MAGIC_BYTE
                    int magic = inputStream.read();
                    if (magic == -1) {
                        Log.d(TAG, "Stream reached EOF");
                        connectionLost();
                        break;
                    }
                    if (magic != MAGIC_BYTE) {
                        continue;
                    }

                    // 2. Read TYPE
                    int typeVal = inputStream.read();
                    if (typeVal == -1) {
                        connectionLost();
                        break;
                    }
                    byte type = (byte) typeVal;

                    // 3. Read LENGTH
                    int readBytes = 0;
                    while (readBytes < 4) {
                        int r = inputStream.read(lengthBuffer, readBytes, 4 - readBytes);
                        if (r == -1) {
                            connectionLost();
                            return;
                        }
                        readBytes += r;
                    }

                    int length = ((lengthBuffer[0] & 0xFF) << 24) |
                                 ((lengthBuffer[1] & 0xFF) << 16) |
                                 ((lengthBuffer[2] & 0xFF) << 8)  |
                                 (lengthBuffer[3] & 0xFF);

                    if (length < 0 || length > 10 * 1024 * 1024) {
                        Log.e(TAG, "Invalid payload length: " + length);
                        continue;
                    }

                    // 4. Read PAYLOAD
                    byte[] payload = new byte[length];
                    int payloadBytesRead = 0;
                    while (payloadBytesRead < length) {
                        int r = inputStream.read(payload, payloadBytesRead, length - payloadBytesRead);
                        if (r == -1) {
                            connectionLost();
                            return;
                        }
                        payloadBytesRead += r;
                        listener.onProgress(payloadBytesRead, length);
                    }

                    listener.onDataReceived(payload, type);

                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        public void write(final byte[] data, final byte type) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (ConnectedThread.this) {
                            int length = data.length;
                            byte[] header = new byte[6];
                            header[0] = MAGIC_BYTE;
                            header[1] = type;
                            header[2] = (byte) ((length >> 24) & 0xFF);
                            header[3] = (byte) ((length >> 16) & 0xFF);
                            header[4] = (byte) ((length >> 8) & 0xFF);
                            header[5] = (byte) (length & 0xFF);

                            outputStream.write(header);
                            
                            int chunkSize = 4096;
                            for (int i = 0; i < length; i += chunkSize) {
                                int size = Math.min(chunkSize, length - i);
                                outputStream.write(data, i, size);
                                listener.onProgress(i + size, length);
                            }
                            outputStream.flush();
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing data", e);
                        listener.onError("Failed to send data");
                    }
                }
            }).start();
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
