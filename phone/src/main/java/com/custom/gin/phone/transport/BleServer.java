package com.custom.gin.phone.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

public class BleServer implements GinTransport {
    private static final String TAG = "BleServer";

    private static final UUID SERVICE_UUID = UUID.fromString("8ce255c1-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_GLASS_WRITE = UUID.fromString("8ce255c2-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_GLASS_WRITE_ACK = UUID.fromString("8ce255c3-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_PHONE_NOTIFY = UUID.fromString("8ce255c4-200a-11eb-adc1-0242ac120002");
    private static final UUID CHAR_PHONE_NOTIFY_ACK = UUID.fromString("8ce255c5-200a-11eb-adc1-0242ac120002");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private final Listener listener;
    private final Handler handler;

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothDevice connectedDevice;

    private BluetoothGattCharacteristic glassWriteChar;
    private BluetoothGattCharacteristic glassWriteAckChar;
    private BluetoothGattCharacteristic phoneNotifyChar;
    private BluetoothGattCharacteristic phoneNotifyAckChar;

    private boolean isRunning = false;
    private boolean isConnected = false;

    // Send state
    private final Object writeLock = new Object();
    private int ackSequence = -1;
    private boolean ackReceived = false;

    // Receive buffer
    private ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private int expectedTotalPackets = 0;
    private int lastReceivedSeq = -1;
    private byte activeReceiveType = 0;

    public BleServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.bluetoothAdapter = bluetoothManager.getAdapter();
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        Log.d(TAG, "Starting BLE Server Transport...");

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled");
            return;
        }

        // Setup GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            listener.onError("Failed to open GATT Server");
            return;
        }

        setupServices();
        startAdvertising();
    }

    private void setupServices() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        glassWriteChar = new BluetoothGattCharacteristic(
                CHAR_GLASS_WRITE,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        glassWriteAckChar = new BluetoothGattCharacteristic(
                CHAR_GLASS_WRITE_ACK,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        BluetoothGattDescriptor glassWriteAckCccd = new BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE);
        glassWriteAckChar.addDescriptor(glassWriteAckCccd);

        phoneNotifyChar = new BluetoothGattCharacteristic(
                CHAR_PHONE_NOTIFY,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
        );
        BluetoothGattDescriptor phoneNotifyCccd = new BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE);
        phoneNotifyChar.addDescriptor(phoneNotifyCccd);

        phoneNotifyAckChar = new BluetoothGattCharacteristic(
                CHAR_PHONE_NOTIFY_ACK,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        service.addCharacteristic(glassWriteChar);
        service.addCharacteristic(glassWriteAckChar);
        service.addCharacteristic(phoneNotifyChar);
        service.addCharacteristic(phoneNotifyAckChar);

        gattServer.addService(service);
    }

    private void startAdvertising() {
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onError("GATT Advertising not supported on this phone");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        Log.d(TAG, "BLE GATT Server advertising started...");
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "BLE Advertising started successfully");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "BLE Advertising failed: " + errorCode);
            listener.onError("BLE Advertise failed: " + errorCode);
        }
    };

    @Override
    public synchronized void stop() {
        isRunning = false;
        isConnected = false;
        if (advertiser != null) {
            advertiser.stopAdvertising(advertiseCallback);
            advertiser = null;
        }
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        connectedDevice = null;
    }

    @Override
    public void sendData(final byte[] data, final byte type) {
        if (!isConnected || connectedDevice == null) {
            listener.onError("BLE client not connected");
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
        int payloadSize = 14;
        int totalPackets = (int) Math.ceil((double) data.length / payloadSize);
        Log.d(TAG, "Sending " + data.length + " bytes to Glass in " + totalPackets + " BLE packets");

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
                phoneNotifyChar.setValue(packet);
                
                boolean status = gattServer.notifyCharacteristicChanged(connectedDevice, phoneNotifyChar, false);
                if (!status) {
                    throw new Exception("GATT notify characteristic failed");
                }

                // Wait for ACK
                long startTime = System.currentTimeMillis();
                while (!ackReceived && (System.currentTimeMillis() - startTime) < 2000) {
                    writeLock.wait(100);
                }

                if (!ackReceived) {
                    throw new Exception("Timeout waiting for ACK from Glass on packet " + seq);
                }

                if (ackSequence != seq) {
                    throw new Exception("Mismatch in ACK sequence. Expected " + seq + " but got " + ackSequence);
                }
            }

            listener.onProgress(offset + size, data.length);
        }
        Log.d(TAG, "BLE Notification sequence finished");
    }

    @Override
    public boolean isConnected() {
        return isConnected;
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Glass connected to GATT server: " + device.getAddress());
                connectedDevice = device;
                // Wait for descriptor write (CCCD subscription) before setting isConnected to true
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Glass disconnected from GATT server");
                isConnected = false;
                connectedDevice = null;
                listener.onDisconnected();
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (descriptor.getCharacteristic().getUuid().equals(CHAR_GLASS_WRITE_ACK) || descriptor.getCharacteristic().getUuid().equals(CHAR_PHONE_NOTIFY)) {
                descriptor.setValue(value);
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                }
                
                // Once both notify descriptors are written (or just the final one), consider us fully connected
                if (descriptor.getCharacteristic().getUuid().equals(CHAR_PHONE_NOTIFY)) {
                    Log.d(TAG, "Glass subscribed to BLE notifications, transport is ready");
                    isConnected = true;
                    listener.onConnected();
                }
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }

            if (characteristic.getUuid().equals(CHAR_GLASS_WRITE)) {
                handleIncomingPacket(value);
            } else if (characteristic.getUuid().equals(CHAR_PHONE_NOTIFY_ACK)) {
                if (value != null && value.length >= 2) {
                    int seq = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
                    synchronized (writeLock) {
                        ackSequence = seq;
                        ackReceived = true;
                        writeLock.notifyAll();
                    }
                }
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

            // Send ACK via glassWriteAckChar notification
            byte[] ack = new byte[2];
            ack[0] = (byte) ((seq >> 8) & 0xFF);
            ack[1] = (byte) (seq & 0xFF);
            glassWriteAckChar.setValue(ack);
            gattServer.notifyCharacteristicChanged(connectedDevice, glassWriteAckChar, false);

            if (seq == expectedTotalPackets - 1) {
                byte[] fullData = receiveBuffer.toByteArray();
                listener.onDataReceived(fullData, activeReceiveType);
            }
        } else {
            Log.e(TAG, "Out of order BLE packet. Expected " + (lastReceivedSeq + 1) + " but got " + seq);
        }
    }
}
