package com.custom.gin.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RfcommTransport implements GinTransport {
    private static final String TAG = "RfcommTransport";
    private static final UUID GIN_UUID = UUID.fromString("8ce255c0-200a-11eb-adc1-0242ac120002");
    private static final byte MAGIC_BYTE = 0x19;
    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_RETRY_DELAY_MS = 2000;
    private static final int STREAM_BUFFER_SIZE = 8192;

    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private volatile boolean isRunning = false;
    private volatile boolean isConnected = false;
    private int retryCount = 0;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private List<BluetoothDevice> candidateDevices;
    private int currentDeviceIndex = 0;
    private String preferredDeviceAddress;

    public RfcommTransport(Listener listener) {
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public synchronized void setPreferredDeviceAddress(String address) {
        this.preferredDeviceAddress = address;
    }

    public synchronized String getPreferredDeviceAddress() {
        return preferredDeviceAddress;
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        Log.d(TAG, "Starting RFCOMM Transport...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            isRunning = false;
            listener.onError("Bluetooth is not enabled");
            return;
        }

        // Collect paired devices, prioritizing the last connected phone and PHONE class devices
        // to avoid wasting 15s of high-power Bluetooth paging on paired headsets/accessories.
        candidateDevices = new ArrayList<>();
        List<BluetoothDevice> phoneDevices = new ArrayList<>();
        List<BluetoothDevice> otherDevices = new ArrayList<>();
        BluetoothDevice preferredDevice = null;

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                if (preferredDeviceAddress != null && preferredDeviceAddress.equals(device.getAddress())) {
                    preferredDevice = device;
                } else if (isPhoneDevice(device)) {
                    phoneDevices.add(device);
                } else {
                    otherDevices.add(device);
                }
            }
        }

        if (preferredDevice != null) {
            candidateDevices.add(preferredDevice);
        }
        candidateDevices.addAll(phoneDevices);
        candidateDevices.addAll(otherDevices);

        if (candidateDevices.isEmpty()) {
            isRunning = false;
            listener.onError("No paired Bluetooth devices found. Please pair Glass with your phone.");
            return;
        }

        isRunning = true;
        retryCount = 0;
        currentDeviceIndex = 0;
        connectToNextCandidate();
    }

    private static boolean isPhoneDevice(BluetoothDevice device) {
        try {
            BluetoothClass btClass = device.getBluetoothClass();
            return btClass != null && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void connectToNextCandidate() {
        if (!isRunning || candidateDevices == null || candidateDevices.isEmpty()) return;

        BluetoothDevice targetDevice = candidateDevices.get(currentDeviceIndex);
        Log.d(TAG, "Attempting connection to: " + targetDevice.getName()
                + " [" + targetDevice.getAddress() + "]"
                + " (attempt " + (retryCount + 1) + "/" + MAX_RETRIES
                + ", device " + (currentDeviceIndex + 1) + "/" + candidateDevices.size() + ")");

        connectThread = new ConnectThread(targetDevice);
        connectThread.start();
    }

    @Override
    public synchronized void stop() {
        isRunning = false;
        isConnected = false;
        retryHandler.removeCallbacksAndMessages(null);
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

    private synchronized void connectionSuccess(BluetoothSocket socket, BluetoothDevice device) {
        if (!isRunning) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }

        connectThread = null;

        // Pin this device as preferred so future reconnects target it immediately
        if (device != null) {
            preferredDeviceAddress = device.getAddress();
            if (candidateDevices != null && candidateDevices.remove(device)) {
                candidateDevices.add(0, device);
            }
        }

        connectedThread = new ConnectedThread(socket);
        connectedThread.start();
        isConnected = true;
        retryCount = 0;
        currentDeviceIndex = 0;
        listener.onConnected();
    }

    private synchronized void connectionFailed() {
        isConnected = false;
        if (!isRunning) return;

        // Try the next paired device before retrying
        currentDeviceIndex++;
        if (candidateDevices != null && currentDeviceIndex < candidateDevices.size()) {
            Log.d(TAG, "Trying next paired device...");
            connectToNextCandidate();
            return;
        }

        // Exhausted all devices; increment retry count and cycle back
        retryCount++;
        currentDeviceIndex = 0;

        if (retryCount >= MAX_RETRIES) {
            isRunning = false;
            listener.onError("Connection failed after " + MAX_RETRIES + " retries");
            return;
        }

        // Exponential backoff: 2s, 4s, 8s, 16s...
        long delay = INITIAL_RETRY_DELAY_MS * (1L << (retryCount - 1));
        Log.d(TAG, "All devices failed. Retrying in " + delay + "ms (retry " + retryCount + "/" + MAX_RETRIES + ")");

        retryHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (RfcommTransport.this) {
                    if (isRunning && !isConnected) {
                        connectToNextCandidate();
                    }
                }
            }
        }, delay);
    }

    private synchronized void connectionLost() {
        if (!isRunning) return;
        isConnected = false;
        listener.onDisconnected();

        Log.d(TAG, "Connection lost, attempting reconnect...");
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }
        retryCount = 0;
        currentDeviceIndex = 0;
        connectToNextCandidate();
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket socket;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(GIN_UUID);
            } catch (Exception e) {
                Log.e(TAG, "Socket create() failed", e);
            }
            socket = tmp;
        }

        @Override
        public void run() {
            setName("ConnectThread");
            if (socket == null) {
                connectionFailed();
                return;
            }
            try {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
                socket.connect();
            } catch (Exception e) {
                Log.e(TAG, "Socket connect() failed", e);
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                connectionFailed();
                return;
            }

            connectionSuccess(socket, device);
        }

        public void cancel() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "close() of connect socket failed", e);
                }
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
