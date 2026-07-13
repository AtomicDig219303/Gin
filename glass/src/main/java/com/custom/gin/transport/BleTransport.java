package com.custom.gin.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

public class BleTransport implements GinTransport {
    private static final String TAG = "BleTransport";

    private static final UUID SERVICE_UUID = UUID.fromString("8ce255c1-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_GLASS_WRITE = UUID.fromString("8ce255c2-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_GLASS_WRITE_ACK = UUID.fromString("8ce255c3-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_PHONE_NOTIFY = UUID.fromString("8ce255c4-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_PHONE_NOTIFY_ACK = UUID.fromString("8ce255c5-200a-11eb-adc1-0242ac120002");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private final Handler handler;

    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic glassWriteChar;
    private BluetoothGattCharacteristic glassWriteAckChar;
    private BluetoothGattCharacteristic phoneNotifyChar;
    private BluetoothGattCharacteristic phoneNotifyAckChar;

    private boolean isRunning = false;
    private boolean isConnected = false;
    private boolean isScanning = false;

    // Send state
    private final Object writeLock = new Object();
    private int ackSequence = -1;
    private boolean ackReceived = false;

    // Receive buffer
    private ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private int expectedTotalPackets = 0;
    private int lastReceivedSeq = -1;
    private byte activeReceiveType = 0;

    public BleTransport(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        Log.d(TAG, "Starting BLE Client Transport...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled");
            return;
        }

        startScanning();
    }

    private void startScanning() {
        if (isScanning) return;
        isScanning = true;
        Log.d(TAG, "Scanning for BLE Phone Server...");

        // Scan in a burst to save battery and avoid Bluedroid crashes on API 19
        bluetoothAdapter.startLeScan(leScanCallback);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isScanning) {
                    stopScanning();
                    if (!isConnected) {
                        listener.onError("BLE Scan timeout, phone not found");
                        // Automatically restart scanning later if still running
                        if (isRunning) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    startScanning();
                                }
                            }, 5000);
                        }
                    }
                }
            }
        }, 10000); // 10s scan burst
    }

    private void stopScanning() {
        if (!isScanning) return;
        isScanning = false;
        Log.d(TAG, "Stopping BLE Scan");
        bluetoothAdapter.stopLeScan(leScanCallback);
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
            // Convert scanRecord to hex to check for our 128-bit UUID (little endian)
            StringBuilder sb = new StringBuilder();
            if (scanRecord != null) {
                for (byte b : scanRecord) {
                    sb.append(String.format("%02x", b));
                }
            }
            
            // SERVICE_UUID = 8ce255c1-200a-11eb-adc1-0242ac120002
            // In little endian hex: 020012ac4202c1adeb110a20c155e28c
            String targetUuid = "020012ac4202c1adeb110a20c155e28c";
            
            if (sb.toString().contains(targetUuid)) {
                Log.d(TAG, "Found target Gin Phone device: " + device.getName() + " [" + device.getAddress() + "]");
                stopScanning();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        connectToDevice(device);
                    }
                });
            } else {
                Log.d(TAG, "Ignoring unrelated BLE device: " + device.getAddress());
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        Log.d(TAG, "Connecting to GATT on " + device.getName());
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    @Override
    public synchronized void stop() {
        isRunning = false;
        isConnected = false;
        stopScanning();
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }

    @Override
    public void sendData(final byte[] data, final byte type) {
        if (!isConnected || glassWriteChar == null) {
            listener.onError("BLE Not connected");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sendDataBlocking(data, type);
                } catch (Exception e) {
                    Log.e(TAG, "Error in BLE write thread", e);
                    listener.onError("BLE Send error: " + e.getMessage());
                }
            }
        }).start();
    }

    private void sendDataBlocking(byte[] data, byte type) throws Exception {
        int payloadSize = 14; // 20 - 6 bytes header
        int totalPackets = (int) Math.ceil((double) data.length / payloadSize);
        Log.d(TAG, "Sending " + data.length + " bytes in " + totalPackets + " BLE packets of type " + type);

        for (int seq = 0; seq < totalPackets; seq++) {
            if (!isRunning || !isConnected) {
                throw new Exception("Disconnected during transmission");
            }

            int offset = seq * payloadSize;
            int size = Math.min(payloadSize, data.length - offset);

            byte[] packet = new byte[6 + size];
            packet[0] = type;
            packet[1] = (byte) ((seq >> 8) & 0xFF);
            packet[2] = (byte) (seq & 0xFF);
            packet[3] = (byte) ((totalPackets >> 8) & 0xFF);
            packet[4] = (byte) (totalPackets & 0xFF);
            packet[5] = (byte) size;
            System.arraycopy(data, offset, packet, 6, size);

            synchronized (writeLock) {
                ackReceived = false;
                glassWriteChar.setValue(packet);
                
                int retries = 3;
                boolean writeSuccess = false;
                
                while (retries > 0 && !writeSuccess) {
                    boolean status = bluetoothGatt.writeCharacteristic(glassWriteChar);
                    if (status) {
                        writeSuccess = true;
                    } else {
                        retries--;
                        Thread.sleep(100);
                    }
                }

                if (!writeSuccess) {
                    throw new Exception("GATT write characteristic call failed");
                }

                // Wait for ACK
                long startTime = System.currentTimeMillis();
                while (!ackReceived && (System.currentTimeMillis() - startTime) < 2000) {
                    writeLock.wait(100);
                }

                if (!ackReceived) {
                    throw new Exception("Timeout waiting for ACK on packet " + seq);
                }

                if (ackSequence != seq) {
                    throw new Exception("Mismatch in ACK sequence. Expected " + seq + " but got " + ackSequence);
                }
            }

            listener.onProgress(offset + size, data.length);
        }
        Log.d(TAG, "BLE Transmission completed successfully");
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server, discovering services...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server");
                isConnected = false;
                listener.onDisconnected();
                
                // Clear connection slots (KitKat status 133 fix)
                if (bluetoothGatt != null) {
                    bluetoothGatt.close();
                    bluetoothGatt = null;
                }

                if (isRunning) {
                    // Try to reconnect
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            startScanning();
                        }
                    }, 5000);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "Gin Service not found!");
                listener.onError("Gin BLE Service not found");
                return;
            }

            glassWriteChar = service.getCharacteristic(CHAR_GLASS_WRITE);
            glassWriteAckChar = service.getCharacteristic(CHAR_GLASS_WRITE_ACK);
            phoneNotifyChar = service.getCharacteristic(CHAR_PHONE_NOTIFY);
            phoneNotifyAckChar = service.getCharacteristic(CHAR_PHONE_NOTIFY_ACK);

            if (glassWriteChar == null || glassWriteAckChar == null || phoneNotifyChar == null || phoneNotifyAckChar == null) {
                Log.e(TAG, "One or more characteristics missing!");
                listener.onError("Gin BLE Characteristics missing");
                return;
            }

            // Enable notifications on GLASS_WRITE_ACK and PHONE_NOTIFY
            enableNotification(gatt, glassWriteAckChar);
        }

        private void enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            gatt.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (descriptor.getCharacteristic().getUuid().equals(CHAR_GLASS_WRITE_ACK)) {
                // Now enable notification on phoneNotifyChar
                enableNotification(gatt, phoneNotifyChar);
            } else if (descriptor.getCharacteristic().getUuid().equals(CHAR_PHONE_NOTIFY)) {
                Log.d(TAG, "BLE Transport fully connected and subscribed");
                isConnected = true;
                listener.onConnected();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (characteristic.getUuid().equals(CHAR_GLASS_WRITE_ACK)) {
                if (value != null && value.length >= 2) {
                    int seq = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                    synchronized (writeLock) {
                        ackSequence = seq;
                        ackReceived = true;
                        writeLock.notifyAll();
                    }
                }
            } else if (characteristic.getUuid().equals(CHAR_PHONE_NOTIFY)) {
                handleIncomingPacket(value);
            }
        }
    };

    private void handleIncomingPacket(byte[] packet) {
        if (packet == null || packet.length < 6) return;

        byte type = packet[0];
        int seq = ((packet[1] & 0xFF) << 8) | (packet[2] & 0xFF);
        int total = ((packet[3] & 0xFF) << 8) | (packet[4] & 0xFF);
        int size = packet[5] & 0xFF;

        if (seq == 0) {
            receiveBuffer.reset();
            expectedTotalPackets = total;
            lastReceivedSeq = -1;
            activeReceiveType = type;
        }

        if (seq == lastReceivedSeq + 1) {
            receiveBuffer.write(packet, 6, size);
            lastReceivedSeq = seq;
            listener.onProgress(receiveBuffer.size(), receiveBuffer.size() + (total - seq - 1) * 14);

            // Send ACK
            byte[] ack = new byte[2];
            ack[0] = (byte) ((seq >> 8) & 0xFF);
            ack[1] = (byte) (seq & 0xFF);
            phoneNotifyAckChar.setValue(ack);
            bluetoothGatt.writeCharacteristic(phoneNotifyAckChar);

            if (seq == expectedTotalPackets - 1) {
                // Completed!
                byte[] fullData = receiveBuffer.toByteArray();
                listener.onDataReceived(fullData, activeReceiveType);
            }
        } else {
            Log.e(TAG, "Packet out of order. Expected " + (lastReceivedSeq + 1) + " but got " + seq);
        }
    }
}
