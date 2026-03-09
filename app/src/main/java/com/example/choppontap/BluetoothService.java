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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.Method;
import java.util.UUID;

public class BluetoothService extends Service {

    private static final String TAG = "BLE_ADVANCED";

    // ── PIN de autenticação do ESP32 ──────────────────────────────────────────
    private static final String ESP32_PIN = "259087";

    // ── Constantes de Pareamento (Variantes ocultas no SDK) ─────────────────────
    private static final int VARIANT_PIN = 0;      // BluetoothDevice.PAIRING_VARIANT_PIN
    private static final int VARIANT_PASSKEY = 1;  // BluetoothDevice.PAIRING_VARIANT_PASSKEY (Oculta)
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2; // BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION

    // ── UUIDs NUS ─────────────────────────────────────────────────────────────
    private static final UUID NUS_SERVICE_UUID        = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    // ── Ações de Broadcast ────────────────────────────────────────────────────
    public static final String ACTION_DATA_AVAILABLE  = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_WRITE_READY     = "com.example.choppontap.ACTION_WRITE_READY";
    public static final String ACTION_DEVICE_FOUND    = "com.example.choppontap.ACTION_DEVICE_FOUND";

    public static final String EXTRA_DATA   = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_DEVICE = "com.example.choppontap.EXTRA_DEVICE";

    // Código GATT_AUTH_FAIL não exposto pelo SDK (0x89 = 137)
    private static final int GATT_AUTH_FAIL = 0x89;

    private BluetoothGatt mBluetoothGatt;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGattCharacteristic mWriteCharacteristic;
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

        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver, pairingFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver, pairingFilter);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. Receiver de pareamento registrado.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mPairingReceiver); } catch (Exception ignored) {}
        closeGatt();
    }

    private final BroadcastReceiver mPairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) return;

            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (device == null) return;

            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) return;

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] Pairing request — variant=" + variant);

            // ✅ CORREÇÃO: Usando as constantes locais para evitar erro de compilação
            if (variant == VARIANT_PIN || variant == VARIANT_PASSKEY) {
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + ok);
                abortBroadcast(); 
            } else if (variant == VARIANT_PASSKEY_CONFIRMATION) {
                device.setPairingConfirmation(true);
                Log.i(TAG, "[BLE] Numeric Comparison confirmado");
                abortBroadcast();
            }
        }
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == GATT_AUTH_FAIL) {
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL — Removendo bond e reconectando...");
                removeBond(gatt.getDevice());
                closeGatt();
                new Handler(Looper.getMainLooper()).postDelayed(() -> connectToDevice(gatt.getDevice()), 1500);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastConnectionStatus("connected");
                gatt.requestMtu(512);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
                if (service != null) {
                    mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
                    setupNotifications(gatt, service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID));
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            broadcastData(new String(characteristic.getValue()));
        }
    };

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx == null) return;
        gatt.setCharacteristicNotification(tx, true);
        android.bluetooth.BluetoothGattDescriptor desc = tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (desc != null) {
            desc.setValue(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
        }
    }

    public void connectToDevice(BluetoothDevice device) {
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    public void write(String data) {
        if (mBluetoothGatt != null && mWriteCharacteristic != null) {
            mWriteCharacteristic.setValue(data.getBytes());
            mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        }
    }

    public boolean connected() {
        return mBluetoothGatt != null;
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }

    private void removeBond(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
        } catch (Exception e) { Log.e(TAG, "Erro ao remover bond", e); }
    }

    private void retryConnection() {
        if (mTargetMac != null) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> 
                connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac)), 5000);
        }
    }

    private void broadcastConnectionStatus(String status) {
        Intent intent = new Intent(ACTION_CONNECTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
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

    public void scanLeDevice(boolean enable) {
        if (mTargetMac != null) connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac));
    }
}
