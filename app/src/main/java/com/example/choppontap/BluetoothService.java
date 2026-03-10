package com.example.choppontap;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
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

/**
 * BluetoothService — Serviço BLE com máquina de estados completa.
 *
 * MÁQUINA DE ESTADOS:
 *   DISCONNECTED → CONNECTED → AUTHENTICATED → READY → (comandos aceitos)
 *
 * O estado READY só é atingido após o ESP32 enviar AUTH:OK, que ocorre
 * ao final do processo de pareamento BLE com PIN 259087.
 * Somente no estado READY o broadcast ACTION_WRITE_READY é emitido,
 * garantindo que $ML nunca seja enviado antes da autenticação.
 *
 * FLUXO CORRETO:
 *   1. connectGatt()
 *   2. onConnectionStateChange → STATE_CONNECTED → requestMtu()
 *   3. onMtuChanged → discoverServices()
 *   4. onServicesDiscovered → setupNotifications() → BLE_STATE = AUTHENTICATED (aguardando AUTH:OK)
 *   5. ESP32 envia AUTH:OK → broadcastData("AUTH:OK")
 *   6. PagamentoConcluido recebe AUTH:OK → BLE_STATE = READY → envia $ML
 */
public class BluetoothService extends Service {

    private static final String TAG = "BLE_ADVANCED";

    // ── Mensagens Handler (compatibilidade com ConnectedThread legado) ────────
    public static final int MESSAGE_READ = 0;
    public static final int MESSAGE_WRITE = 1;
    public static final int MESSAGE_CONNECTION_LOST = 2;

    // ── PIN de autenticação do ESP32 (usado no pareamento BLE, não como comando) ──
    private static final String ESP32_PIN = "259087";

    // ── Constantes de Pareamento (variantes ocultas no SDK público) ───────────
    private static final int VARIANT_PIN                  = 0; // PAIRING_VARIANT_PIN
    private static final int VARIANT_PASSKEY              = 1; // PAIRING_VARIANT_PASSKEY
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2; // PAIRING_VARIANT_PASSKEY_CONFIRMATION

    // ── UUIDs NUS (Nordic UART Service) ──────────────────────────────────────
    private static final UUID NUS_SERVICE_UUID         = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID                = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ── Ações de Broadcast ────────────────────────────────────────────────────
    public static final String ACTION_DATA_AVAILABLE    = "com.example.choppontap.ACTION_DATA_AVAILABLE";
    public static final String ACTION_CONNECTION_STATUS = "com.example.choppontap.ACTION_CONNECTION_STATUS";
    public static final String ACTION_WRITE_READY       = "com.example.choppontap.ACTION_WRITE_READY";
    public static final String ACTION_DEVICE_FOUND      = "com.example.choppontap.ACTION_DEVICE_FOUND";
    public static final String ACTION_BLE_STATE_CHANGED = "com.example.choppontap.ACTION_BLE_STATE_CHANGED";

    public static final String EXTRA_DATA      = "com.example.choppontap.EXTRA_DATA";
    public static final String EXTRA_STATUS    = "com.example.choppontap.EXTRA_STATUS";
    public static final String EXTRA_DEVICE    = "com.example.choppontap.EXTRA_DEVICE";
    public static final String EXTRA_BLE_STATE = "com.example.choppontap.EXTRA_BLE_STATE";

    // ── Código GATT_AUTH_FAIL não exposto pelo SDK (0x89 = 137) ──────────────
    private static final int GATT_AUTH_FAIL = 0x89;

    // ─────────────────────────────────────────────────────────────────────────
    // MÁQUINA DE ESTADOS BLE
    // ─────────────────────────────────────────────────────────────────────────
    public enum BleState {
        /**
         * Sem conexão GATT ativa.
         */
        DISCONNECTED,

        /**
         * GATT conectado. MTU negociado. Serviços descobertos.
         * Notificações NUS ativadas. Aguardando AUTH:OK do ESP32
         * (que ocorre após o pareamento BLE com PIN ser concluído).
         */
        CONNECTED,

        /**
         * ESP32 enviou AUTH:OK → deviceAuthenticated = true no firmware.
         * Canal NUS pronto para receber comandos.
         * ACTION_WRITE_READY é emitido neste estado.
         */
        READY
    }

    private BleState mBleState = BleState.DISCONNECTED;

    // ── Campos internos ───────────────────────────────────────────────────────
    private BluetoothGatt                mBluetoothGatt;
    private BluetoothAdapter             mBluetoothAdapter;
    private BluetoothGattCharacteristic  mWriteCharacteristic;
    private String                       mTargetMac;
    private boolean                      mAutoReconnect = true;

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        BluetoothService getService() { return BluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida do serviço
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        // Registra receiver de pareamento com prioridade máxima para interceptar
        // o diálogo de PIN antes que o sistema o exiba ao usuário.
        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver, pairingFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver, pairingFilter);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. Estado inicial: DISCONNECTED. Receiver de pareamento registrado.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(mPairingReceiver); } catch (Exception ignored) {}
        closeGatt();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver de pareamento automático com PIN 259087
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Intercepta ACTION_PAIRING_REQUEST antes do sistema e injeta o PIN 259087
     * automaticamente, sem exibir nenhum diálogo ao operador.
     *
     * Variantes tratadas:
     *   VARIANT_PIN (0)                  → setPin("259087")
     *   VARIANT_PASSKEY (1)              → setPin("259087")
     *   VARIANT_PASSKEY_CONFIRMATION (2) → setPairingConfirmation(true)
     */
    private final BroadcastReceiver mPairingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) return;

            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (device == null) return;

            // Ignora dispositivos que não são o ESP32 alvo
            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) {
                Log.d(TAG, "[BLE] Pairing request ignorado — MAC " + device.getAddress() + " não é o alvo");
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] Pairing request recebido — variant=" + variant + " device=" + device.getAddress());

            if (variant == VARIANT_PIN || variant == VARIANT_PASSKEY) {
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + ok
                        + " | Aguardando AUTH:OK do ESP32 após bond...");
                abortBroadcast();
            } else if (variant == VARIANT_PASSKEY_CONFIRMATION) {
                device.setPairingConfirmation(true);
                Log.i(TAG, "[BLE] Numeric Comparison confirmado automaticamente");
                abortBroadcast();
            } else {
                Log.w(TAG, "[BLE] Variante de pareamento desconhecida: " + variant + " — não interceptado");
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks GATT
    // ─────────────────────────────────────────────────────────────────────────

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            // GATT_AUTH_FAIL: bond Android inválido (ESP32 foi resetado e apagou seus bonds).
            // Solução: remover bond local e reconectar para forçar novo pareamento com PIN.
            if (status == GATT_AUTH_FAIL) {
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL (0x89) — bond inválido. Removendo e reconectando...");
                transitionTo(BleState.DISCONNECTED);
                removeBond(gatt.getDevice());
                closeGatt();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> connectToDevice(gatt.getDevice()), 1500);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[BLE] GATT conectado — solicitando MTU 512");
                transitionTo(BleState.CONNECTED);
                broadcastConnectionStatus("connected");
                gatt.requestMtu(512);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[BLE] GATT desconectado (status=" + status + ")");
                transitionTo(BleState.DISCONNECTED);
                mWriteCharacteristic = null;
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU negociado: " + mtu + " — descobrindo serviços...");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] discoverServices falhou: status=" + status);
                return;
            }
            BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "[BLE] Serviço NUS não encontrado no dispositivo!");
                return;
            }
            mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            setupNotifications(gatt, txChar);
            Log.i(TAG, "[BLE] Serviço NUS encontrado. Notificações ativadas."
                    + " Estado: CONNECTED — aguardando AUTH:OK do ESP32...");
            // NÃO emite ACTION_WRITE_READY aqui.
            // O broadcast só será emitido quando o ESP32 enviar AUTH:OK
            // e o estado transitar para READY (ver notifyAuthOk()).
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            String data = new String(characteristic.getValue()).trim();
            Log.d(TAG, "[BLE] ESP32 → Android: [" + data + "]");

            // AUTH:OK recebido: ESP32 concluiu o pareamento e está pronto.
            // Transitar para READY e notificar PagamentoConcluido.
            if ("AUTH:OK".equalsIgnoreCase(data)) {
                Log.i(TAG, "[BLE] AUTH:OK recebido — transitando para estado READY");
                transitionTo(BleState.READY);
                broadcastWriteReady(); // ← ACTION_WRITE_READY emitido SOMENTE aqui
            }

            // Propaga TODOS os dados (incluindo AUTH:OK, VP:, ML:, etc.) para a Activity
            broadcastData(data);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Máquina de estados — transições
    // ─────────────────────────────────────────────────────────────────────────

    private void transitionTo(BleState newState) {
        if (mBleState == newState) return;
        Log.i(TAG, "[BLE] Estado: " + mBleState.name() + " → " + newState.name());
        mBleState = newState;
        broadcastBleState(newState);
    }

    /** Retorna o estado atual da máquina de estados BLE. */
    public BleState getBleState() {
        return mBleState;
    }

    /** Retorna true somente se o canal NUS está autenticado e pronto para comandos. */
    public boolean isReady() {
        return mBleState == BleState.READY;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuração de notificações NUS
    // ─────────────────────────────────────────────────────────────────────────

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx == null) {
            Log.e(TAG, "[BLE] TX characteristic (NUS) não encontrada!");
            return;
        }
        gatt.setCharacteristicNotification(tx, true);
        BluetoothGattDescriptor desc = tx.getDescriptor(CCCD_UUID);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(desc);
            Log.d(TAG, "[BLE] CCCD escrito — notificações NUS ativadas");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /** Conecta ao dispositivo BLE pelo objeto BluetoothDevice. */
    public void connectToDevice(BluetoothDevice device) {
        Log.i(TAG, "[BLE] connectGatt() → " + device.getAddress());
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    /**
     * Escreve dados no canal NUS (RX characteristic do ESP32).
     * ATENÇÃO: só deve ser chamado quando isReady() == true.
     * Caso contrário, o ESP32 responderá ERROR:NOT_AUTHENTICATED.
     */
    public void write(String data) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") ignorado — GATT nulo");
            return;
        }
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") ignorado — mWriteCharacteristic nulo");
            return;
        }
        if (!isReady()) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") BLOQUEADO — estado=" + mBleState.name()
                    + ". Aguardar AUTH:OK antes de enviar comandos!");
            return;
        }
        mWriteCharacteristic.setValue(data.getBytes());
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[BLE] write(\"" + data + "\") → " + (ok ? "OK" : "FALHOU"));
    }

    /** Retorna true se há uma conexão GATT ativa (qualquer estado). */
    public boolean connected() {
        return mBluetoothGatt != null;
    }

    /**
     * Desconecta o GATT e desabilita o auto-reconnect.
     * Chamado quando a TAP é desativada intencionalmente.
     */
    public void disconnect() {
        mAutoReconnect = false;
        transitionTo(BleState.DISCONNECTED);
        closeGatt();
        Log.i(TAG, "[BLE] disconnect() — mAutoReconnect=false, GATT fechado");
    }

    /**
     * Reabilita o auto-reconnect após uma desativação intencional.
     * Chamado por Home.onServiceConnected() e Home.onResume().
     */
    public void enableAutoReconnect() {
        mAutoReconnect = true;
        Log.i(TAG, "[BLE] enableAutoReconnect() — mAutoReconnect=true");
    }

    /**
     * Retorna o BluetoothDevice atualmente conectado via GATT, ou null.
     * Usado por PagamentoConcluido para remover o bond inválido.
     */
    public BluetoothDevice getBoundDevice() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getDevice();
    }

    /**
     * Remove o bond (vínculo de pareamento) armazenado no Android para o dispositivo.
     * O método removeBond() é @hide no SDK, acessado via reflexão.
     * Necessário quando o ESP32 é resetado e apaga seus bonds (causa GATT_AUTH_FAIL).
     */
    public static void removeBond(BluetoothDevice device) {
        if (device == null) return;
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
            Log.i(TAG, "[BLE] removeBond() chamado para " + device.getAddress());
        } catch (Exception e) {
            Log.e(TAG, "[BLE] Erro ao remover bond: " + e.getMessage());
        }
    }

    /** Inicia scan BLE ou conecta diretamente ao MAC alvo se já conhecido. */
    public void scanLeDevice(boolean enable) {
        if (mTargetMac != null) {
            Log.i(TAG, "[BLE] scanLeDevice() → conectando diretamente ao MAC alvo: " + mTargetMac);
            connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac));
        } else {
            Log.w(TAG, "[BLE] scanLeDevice() — MAC alvo não configurado em tap_config");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos internos
    // ─────────────────────────────────────────────────────────────────────────

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mWriteCharacteristic = null;
        }
    }

    private void retryConnection() {
        if (mTargetMac != null) {
            Log.i(TAG, "[BLE] Agendando reconexão em 5s para " + mTargetMac);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> connectToDevice(mBluetoothAdapter.getRemoteDevice(mTargetMac)), 5000);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcasts
    // ─────────────────────────────────────────────────────────────────────────

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

    /**
     * Emitido SOMENTE quando o estado transita para READY (após AUTH:OK).
     * PagamentoConcluido usa este broadcast como gatilho para enviar $ML.
     */
    private void broadcastWriteReady() {
        Log.i(TAG, "[BLE] ACTION_WRITE_READY emitido — canal autenticado e pronto");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(BleState state) {
        Intent intent = new Intent(ACTION_BLE_STATE_CHANGED);
        intent.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
