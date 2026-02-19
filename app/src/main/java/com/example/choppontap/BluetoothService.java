package com.example.choppontap;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.UUID;

public class BluetoothService extends Service {

    private static final String TAG = "BLE_ADVANCED";
    
    // UUIDs NUS
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // Ações de Broadcast
    public static final String ACTION_DATA_AVAILABLE = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_WRITE_READY = "com.example.choppontap.ACTION_WRITE_READY";
    public static final String ACTION_DEVICE_FOUND = "com.example.choppontap.ACTION_DEVICE_FOUND";
    
    public static final String EXTRA_DATA = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_DEVICE = "com.example.choppontap.EXTRA_DEVICE";

    // Constantes de compatibilidade
    public static final int MESSAGE_READ = 0;
    public static final int MESSAGE_WRITE = 1;
    public static final int MESSAGE_CONNECTION_LOST = 5;

    private BluetoothGatt mBluetoothGatt;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private boolean mScanning = false;
    private String mTargetMac;
    private boolean mAutoReconnect = true;
    
    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        BluetoothService getService() { return BluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE).getString("esp32_mac", null);
        Log.i(TAG, "[System] Serviço Iniciado.");
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt();
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastConnectionStatus("connected");
                if (ActivityCompat.checkSelfPermission(BluetoothService.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt();
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
                if (service != null) {
                    mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
                    BluetoothGattCharacteristic tx = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
                    setupNotifications(gatt, tx);
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) broadcastWriteReady();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            broadcastData(new String(characteristic.getValue()));
        }
    };

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            gatt.setCharacteristicNotification(tx, true);
            android.bluetooth.BluetoothGattDescriptor descriptor = tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (descriptor != null) {
                descriptor.setValue(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }
    }

    public void scanLeDevice(boolean enable) {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) return;
        if (connected()) {
            broadcastConnectionStatus("connected");
            return;
        }

        if (mTargetMac != null && !mScanning) {
            connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac));
            return;
        }

        if (mBluetoothLeScanner == null) mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        if (enable && !mScanning) {
            mScanning = true;
            broadcastConnectionStatus("conectando...");
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                mBluetoothLeScanner.startScan(null, settings, mLeScanCallback);
                new Handler(Looper.getMainLooper()).postDelayed(this::stopScan, 15000);
            }
        } else {
            stopScan();
        }
    }

    private final ScanCallback mLeScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            
            // Notifica descoberta para telas de calibração
            broadcastDeviceFound(device);

            if (name != null && name.startsWith("CHOPP_")) {
                mTargetMac = device.getAddress();
                saveTargetMac(mTargetMac);
                stopScan();
                connectToDevice(device);
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        }
    }

    public void disconnect() {
        mAutoReconnect = false;
        closeGatt();
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                mBluetoothGatt.disconnect();
                mBluetoothGatt.close();
            }
            mBluetoothGatt = null;
            mWriteCharacteristic = null;
        }
    }

    private void retryConnection() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!connected() && mTargetMac != null) scanLeDevice(true);
        }, 5000);
    }

    public boolean connected() {
        if (mBluetoothGatt == null) return false;
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return bm.getConnectionState(mBluetoothGatt.getDevice(), BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED;
    }

    public void write(String data) {
        if (connected() && mWriteCharacteristic != null) {
            mWriteCharacteristic.setValue(data.getBytes());
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
            }
        }
    }

    private void stopScan() {
        if (!mScanning) return;
        if (mBluetoothLeScanner != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            mBluetoothLeScanner.stopScan(mLeScanCallback);
        }
        mScanning = false;
    }

    private void saveTargetMac(String mac) {
        getSharedPreferences("tap_config", Context.MODE_PRIVATE).edit().putString("esp32_mac", mac).apply();
    }

    private void broadcastConnectionStatus(String status) {
        Intent intent = new Intent(ACTION_CONNECTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastDeviceFound(BluetoothDevice device) {
        Intent intent = new Intent(ACTION_DEVICE_FOUND);
        intent.putExtra(EXTRA_DEVICE, device);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastData(String data) {
        Intent intent = new Intent(ACTION_DATA_AVAILABLE);
        intent.putExtra(EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastWriteReady() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_WRITE_READY));
    }
}
