package com.custom.gin.phone.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RfcommServer implements GinTransport {
    private static final String TAG = "RfcommServer";
    private static final UUID GIN_UUID = UUID.fromString("8ce255c0-200a-11eb-adc1-0242ac120002");
    private static final byte MAGIC_BYTE = 0x19;
    private static final int STREAM_BUFFER_SIZE = 8192;

    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private AcceptThread acceptThread;
    private ConnectedThread connectedThread;
    private volatile boolean isRunning = false;
    private volatile boolean isConnected = false;

    public RfcommServer(Listener listener) {
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        Log.d(TAG, "Starting RFCOMM Server...");

        try {
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                isRunning = false;
                listener.onError("Bluetooth is not enabled");
                return;
            }
        } catch (SecurityException e) {
            isRunning = false;
            listener.onError("Bluetooth permission denied");
            return;
        }

        isRunning = true;
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
            } catch (IOException ignored) {
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
        if (!isRunning) return;
        isConnected = false;
        listener.onDisconnected();

        Log.d(TAG, "Connection lost, restarting accept thread...");
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        acceptThread = new AcceptThread();
        acceptThread.start();
    }

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket serverSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;
            try {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord("GinServer", GIN_UUID);
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Server Socket listen() failed", e);
            }
            serverSocket = tmp;
        }

        @Override
        public void run() {
            setName("AcceptThread");
            if (serverSocket == null) {
                synchronized (RfcommServer.this) {
                    isRunning = false;
                }
                listener.onError("Failed to open RFCOMM server socket");
                return;
            }

            while (isRunning && !isConnected) {
                BluetoothSocket socket;
                try {
                    socket = serverSocket.accept();
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "Server Socket accept() failed", e);
                    }
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
        private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();

        public ConnectedThread(BluetoothSocket socket) {
            this.socket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = new BufferedInputStream(socket.getInputStream(), STREAM_BUFFER_SIZE);
                tmpOut = new BufferedOutputStream(socket.getOutputStream(), STREAM_BUFFER_SIZE);
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }
            inputStream = tmpIn;
            outputStream = tmpOut;
        }

        @Override
        public void run() {
            setName("ConnectedThread");
            if (inputStream == null || outputStream == null) {
                connectionLost();
                return;
            }
            byte[] lengthBuffer = new byte[4];

            try {
                while (isRunning) {
                    // 1. Read MAGIC_BYTE
                    int magic = inputStream.read();
                    if (magic == -1) {
                        Log.d(TAG, "Stream reached EOF");
                        connectionLost();
                        break;
                    }
                    if ((byte) magic != MAGIC_BYTE) {
                        continue;
                    }

                    // 2. Read TYPE
                    int typeVal = inputStream.read();
                    if (typeVal == -1) {
                        connectionLost();
                        break;
                    }
                    byte type = (byte) typeVal;
                    if (type < TYPE_TEXT || type > TYPE_CONTROL) {
                        continue;
                    }

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
                    }

                    listener.onDataReceived(payload, type);
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                }
            } finally {
                writeExecutor.shutdownNow();
            }
        }

        public void write(final byte[] data, final byte type) {
            if (writeExecutor.isShutdown() || outputStream == null) return;
            writeExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        int length = data.length;
                        byte[] header = new byte[6];
                        header[0] = MAGIC_BYTE;
                        header[1] = type;
                        header[2] = (byte) ((length >> 24) & 0xFF);
                        header[3] = (byte) ((length >> 16) & 0xFF);
                        header[4] = (byte) ((length >> 8) & 0xFF);
                        header[5] = (byte) (length & 0xFF);

                        outputStream.write(header);
                        outputStream.write(data, 0, length);
                        outputStream.flush();
                    } catch (IOException e) {
                        Log.e(TAG, "Error writing data", e);
                        listener.onError("Failed to send data");
                    }
                }
            });
        }

        public void cancel() {
            writeExecutor.shutdownNow();
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
