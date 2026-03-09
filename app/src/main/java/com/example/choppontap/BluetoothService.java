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
    // O firmware ESP32 exige pareamento BLE com este PIN fixo.
    // O receiver mPairingReceiver intercepta ACTION_PAIRING_REQUEST e injeta
    // o PIN automaticamente, sem exibir diálogo ao usuário.
    private static final String ESP32_PIN = "259087";

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

    // Constantes de compatibilidade
    public static final int MESSAGE_READ            = 0;
    public static final int MESSAGE_WRITE           = 1;
    public static final int MESSAGE_CONNECTION_LOST = 5;

    // Código GATT_AUTH_FAIL não exposto pelo SDK (0x89 = 137)
    private static final int GATT_AUTH_FAIL = 0x89;

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

    // ── Ciclo de vida do serviço ───────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE).getString("esp32_mac", null);

        // Registra o receiver de pareamento com prioridade máxima para interceptar
        // o ACTION_PAIRING_REQUEST antes que o sistema exiba o diálogo ao usuário.
        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver, pairingFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver, pairingFilter);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. Receiver de pareamento registrado (PIN=" + ESP32_PIN + ")");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mPairingReceiver); } catch (Exception ignored) {}
        closeGatt();
        Log.i(TAG, "[BLE] Serviço destruído.");
    }

    // ── Receiver de pareamento automático por PIN ─────────────────────────────
    /**
     * Intercepta ACTION_PAIRING_REQUEST antes do sistema operacional.
     *
     * Quando o ESP32 exige pareamento (porque o bond foi apagado ou nunca existiu),
     * o Android emite este broadcast ordenado. Ao chamar setPin() + abortBroadcast(),
     * o PIN é injetado silenciosamente e o diálogo de pareamento nunca é exibido
     * ao operador do tablet.
     *
     * Variantes tratadas:
     *   PAIRING_VARIANT_PIN (0)        — PIN numérico/alfanumérico → setPin()
     *   PAIRING_VARIANT_PASSKEY (1)    — Passkey de 6 dígitos      → setPin() (mesmo mecanismo)
     *   PAIRING_VARIANT_PASSKEY_CONFIRMATION (2) — Numeric Comparison → setPairingConfirmation()
     */
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

            // Filtra: só intercepta para o nosso dispositivo ESP32
            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) {
                Log.w(TAG, "[BLE] Pairing request de dispositivo desconhecido (" + device.getAddress() + ") — ignorado");
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] ACTION_PAIRING_REQUEST recebido — device=" + device.getAddress()
                    + " variant=" + variant);

            if (variant == BluetoothDevice.PAIRING_VARIANT_PIN
                    || variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY) {
                // Injeta o PIN automaticamente
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + ok
                        + " — diálogo suprimido via abortBroadcast()");
                abortBroadcast(); // Impede que o sistema exiba o diálogo de PIN
            } else if (variant == BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION) {
                // Numeric Comparison: confirma automaticamente
                if (ActivityCompat.checkSelfPermission(BluetoothService.this,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    device.setPairingConfirmation(true);
                    Log.i(TAG, "[BLE] Numeric Comparison confirmado automaticamente");
                    abortBroadcast();
                }
            } else {
                Log.w(TAG, "[BLE] Variante de pareamento inesperada: " + variant + " — deixando para o sistema tratar");
            }
        }
    };

    // ── GATT Callback ─────────────────────────────────────────────────────────

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (status == GATT_AUTH_FAIL) {
                // Bond inválido (ESP32 apagou o vínculo). Remove o bond local e reconecta.
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL (0x89) — bond inválido. Removendo bond e reconectando...");
                removeBond(gatt.getDevice());
                closeGatt();
                new Handler(Looper.getMainLooper()).postDelayed(() -> connectToDevice(
                        mBluetoothAdapter.getRemoteDevice(gatt.getDevice().getAddress())), 1500);
                return;
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] onConnectionStateChange erro status=" + status + " (0x" + Integer.toHexString(status) + ")");
                closeGatt();
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[BLE] Conectado ao dispositivo: " + gatt.getDevice().getAddress()
                        + " bondState=" + gatt.getDevice().getBondState());
                broadcastConnectionStatus("connected");
                if (ActivityCompat.checkSelfPermission(BluetoothService.this,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    // Negocia MTU máximo (512 bytes) antes de descobrir serviços.
                    // Sem isso, o BLE usa MTU padrão de 23 bytes (payload útil = 20 bytes),
                    // truncando mensagens como "ERROR:NOT_AUTHENTICATED" (23 bytes) para
                    // "ERROR:NOT_AUTHENTICA" (20 bytes) — bug confirmado no log 2026-03-07.
                    Log.d(TAG, "[BLE] Solicitando MTU=512...");
                    gatt.requestMtu(512);
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "[BLE] Desconectado do dispositivo");
                closeGatt();
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[MTU] Negociado: " + mtu + " bytes (status=" + status + ")");
            // Após MTU negociado, descobre os serviços GATT
            if (ActivityCompat.checkSelfPermission(BluetoothService.this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] Serviços GATT descobertos com sucesso");
                BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
                if (service != null) {
                    Log.i(TAG, "[BLE] Serviço NUS encontrado — canal de escrita pronto");
                    mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
                    BluetoothGattCharacteristic tx = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
                    setupNotifications(gatt, tx);
                } else {
                    Log.e(TAG, "[BLE] ERRO: Serviço NUS não encontrado! UUID esperado: " + NUS_SERVICE_UUID);
                }
            } else {
                Log.e(TAG, "[BLE] ERRO: onServicesDiscovered status=" + status + " (esperado GATT_SUCCESS=0)");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "[BLE] Notificações NUS habilitadas — canal pronto");
                broadcastWriteReady();
            } else {
                Log.e(TAG, "[BLE] ERRO ao habilitar notificações: status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            String received = new String(characteristic.getValue());
            Log.d(TAG, "[BLE] Recebido do ESP32: [" + received + "]");
            broadcastData(received);
        }
    };

    // ── Configuração de notificações NUS ──────────────────────────────────────

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx != null && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            gatt.setCharacteristicNotification(tx, true);
            android.bluetooth.BluetoothGattDescriptor descriptor =
                    tx.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (descriptor != null) {
                descriptor.setValue(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
        }
    }

    // ── Scan e conexão ────────────────────────────────────────────────────────

    public void scanLeDevice(boolean enable) {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "scanLeDevice: Bluetooth desabilitado ou não disponível");
            return;
        }
        if (connected()) {
            Log.d(TAG, "scanLeDevice: já conectado, broadcast connected");
            broadcastConnectionStatus("connected");
            return;
        }

        if (mTargetMac != null && !mScanning) {
            Log.i(TAG, "[BLE] MAC salvo encontrado (" + mTargetMac + "), conectando diretamente...");
            broadcastConnectionStatus("conectando...");
            connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac));
            return;
        }

        if (mBluetoothLeScanner == null) mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        if (enable && !mScanning) {
            mScanning = true;
            Log.i(TAG, "[BLE] Scan BLE iniciado (timeout=15s)");
            broadcastConnectionStatus("conectando...");
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
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
                Log.i(TAG, "[BLE] Dispositivo CHOPP encontrado: " + name + " (" + device.getAddress() + ")");
                mTargetMac = device.getAddress();
                saveTargetMac(mTargetMac);
                stopScan();
                connectToDevice(device);
            }
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "[BLE] connectGatt → " + device.getAddress()
                    + " bondState=" + device.getBondState());
            mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        }
    }

    // ── Gerenciamento de bond ─────────────────────────────────────────────────

    /**
     * Remove o bond (vínculo de pareamento) armazenado no Android para o dispositivo.
     *
     * Necessário quando o ESP32 apaga seu banco de bonds (ex.: após reset de fábrica
     * ou reflash do firmware). Nesse caso, o Android ainda tem o bond antigo salvo
     * e o GATT retorna GATT_AUTH_FAIL (0x89) ao tentar reconectar. Remover o bond
     * local força um novo pareamento com PIN na próxima conexão.
     *
     * O método removeBond() é @hide no SDK, portanto acessado via reflexão.
     */
    public static void removeBond(BluetoothDevice device) {
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
            Log.i(TAG, "[BLE] Bond removido para " + device.getAddress());
        } catch (Exception e) {
            Log.e(TAG, "[BLE] Falha ao remover bond: " + e.getMessage());
        }
    }

    // ── Controle de conexão ───────────────────────────────────────────────────

    public void disconnect() {
        mAutoReconnect = false;
        closeGatt();
        Log.i(TAG, "[BLE] disconnect() — mAutoReconnect=false, GATT fechado");
    }

    /**
     * Reabilita o auto-reconnect após uma desativação intencional da TAP.
     * Deve ser chamado pela Home antes de iniciar o scan.
     */
    public void enableAutoReconnect() {
        mAutoReconnect = true;
        Log.i(TAG, "[BLE] enableAutoReconnect() — mAutoReconnect=true");
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
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
        return bm.getConnectionState(mBluetoothGatt.getDevice(), BluetoothProfile.GATT)
                == BluetoothProfile.STATE_CONNECTED;
    }

    /**
     * Retorna o BluetoothDevice atualmente vinculado ao GATT, ou null se não houver conexão.
     * Usado por PagamentoConcluido para remover o bond inválido ao receber ERROR:NOT_AUTHENTICATED.
     */
    public BluetoothDevice getBoundDevice() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getDevice();
    }

    // ── Escrita BLE ───────────────────────────────────────────────────────────

    public void write(String data) {
        Log.d(TAG, "[BLE] write() chamado com: [" + data + "]");
        if (!connected()) {
            Log.e(TAG, "[BLE] ERRO: write() chamado mas BLE não está conectado! Dado descartado: [" + data + "]");
            return;
        }
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "[BLE] ERRO: write() chamado mas mWriteCharacteristic é null! Canal NUS não pronto. Dado descartado: [" + data + "]");
            return;
        }
        mWriteCharacteristic.setValue(data.getBytes());
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
            if (ok) {
                Log.i(TAG, "[BLE] Comando enviado com sucesso: [" + data + "]");
            } else {
                Log.e(TAG, "[BLE] FALHA ao enviar comando: [" + data + "] — writeCharacteristic retornou false");
            }
        } else {
            Log.e(TAG, "[BLE] ERRO: sem permissão BLUETOOTH_CONNECT para escrever");
        }
    }

    // ── Utilitários internos ──────────────────────────────────────────────────

    private void stopScan() {
        if (!mScanning) return;
        if (mBluetoothLeScanner != null
                && ActivityCompat.checkSelfPermission(this,
                        Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            mBluetoothLeScanner.stopScan(mLeScanCallback);
        }
        mScanning = false;
        Log.d(TAG, "[BLE] Scan encerrado");
    }

    private void saveTargetMac(String mac) {
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
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
