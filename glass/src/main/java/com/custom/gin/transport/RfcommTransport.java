package com.custom.gin.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class RfcommTransport implements GinTransport {
    private static final String TAG = "RfcommTransport";
    private static final UUID GIN_UUID = UUID.fromString("8ce255c0-200a-11eb-adc1-0242ac120002");
    private static final byte MAGIC_BYTE = 0x19;

    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private boolean isRunning = false;
    private boolean isConnected = false;

    public RfcommTransport(Listener listener) {
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        Log.d(TAG, "Starting RFCOMM Transport...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled");
            return;
        }

        // Search paired devices
        BluetoothDevice targetDevice = null;
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null && !pairedDevices.isEmpty()) {
            for (BluetoothDevice device : pairedDevices) {
                // Connect to the first paired device (typically the phone companion)
                targetDevice = device;
                break;
            }
        }

        if (targetDevice == null) {
            listener.onError("No paired Bluetooth devices found. Please pair Glass with your phone.");
            return;
        }

        Log.d(TAG, "Target phone device: " + targetDevice.getName() + " [" + targetDevice.getAddress() + "]");
        connectThread = new ConnectThread(targetDevice);
        connectThread.start();
    }

    @Override
    public synchronized void stop() {
        isRunning = false;
        isConnected = false;
        Log.d(TAG, "Stopping RFCOMM Transport...");
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
    }

    @Override
    public synchronized void sendData(final byte[] data, final byte type) {
        if (connectedThread == null || !isConnected) {
            listener.onError("Not connected to server");
            return;
        }
        connectedThread.write(data, type);
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    private synchronized void connectionSuccess(BluetoothSocket socket) {
        if (!isRunning) {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
            return;
        }

        if (connectThread != null) {
            connectThread = null;
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
        isConnected = true;
        listener.onConnected();
    }

    private synchronized void connectionFailed() {
        isConnected = false;
        if (isRunning) {
            listener.onError("Connection failed");
            // Retry logic could be added here, but let's notify for now
        }
    }

    private synchronized void connectionLost() {
        isConnected = false;
        listener.onDisconnected();
        if (isRunning) {
            Log.d(TAG, "Connection lost, attempting reconnect...");
            // Automatically attempt reconnect
            stop();
            start();
        }
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(GIN_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket create() failed", e);
            }
            socket = tmp;
        }

        public void run() {
            setName("ConnectThread");
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }

            try {
                socket.connect();
            } catch (IOException e) {
                Log.e(TAG, "Socket connect() failed", e);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            connectionSuccess(socket);
        }

        public void cancel() {
            try {
                socket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
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
                        // Skip unexpected byte to find boundary
                        continue;
                    }

                    // 2. Read TYPE
                    int typeVal = inputStream.read();
                    if (typeVal == -1) {
                        connectionLost();
                        break;
                    }
                    byte type = (byte) typeVal;

                    // 3. Read LENGTH (4 bytes, Big-Endian)
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

                    if (length < 0 || length > 10 * 1024 * 1024) { // Cap at 10MB to avoid OOM
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
                            
                            // Write in chunks to show progress
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
