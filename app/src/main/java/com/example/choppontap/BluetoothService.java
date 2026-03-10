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
 *   DISCONNECTED → CONNECTED → READY → (comandos aceitos)
 *
 * FLUXO CORRETO:
 *   1. connectGatt()
 *   2. onConnectionStateChange → STATE_CONNECTED → requestMtu()
 *   3. onMtuChanged → discoverServices()
 *   4. onServicesDiscovered:
 *      - Se BOND_BONDED  → READY imediato → ACTION_WRITE_READY
 *      - Se sem bond     → aguarda AUTH:OK + timer fallback 5s
 *   5. AUTH:OK recebido → READY → ACTION_WRITE_READY
 *   6. Fallback: se AUTH:OK não chega em 5s e GATT conectado → READY forçado
 */
public class BluetoothService extends Service {

    private static final String TAG = "BLE_ADVANCED";

    // ── Mensagens Handler (compatibilidade com ConnectedThread legado) ────────
    public static final int MESSAGE_READ = 0;
    public static final int MESSAGE_WRITE = 1;
    public static final int MESSAGE_CONNECTION_LOST = 2;

    // ── PIN de autenticação do ESP32 ──────────────────────────────────────────
    private static final String ESP32_PIN = "259087";

    // ── Constantes de Pareamento ──────────────────────────────────────────────
    private static final int VARIANT_PIN                  = 0;
    private static final int VARIANT_PASSKEY              = 1;
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2;

    // ── UUIDs NUS (Nordic UART Service) ──────────────────────────────────────
    private static final UUID NUS_SERVICE_UUID            = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID  = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID  = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID                   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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

    // ── GATT_AUTH_FAIL não exposto pelo SDK (0x89 = 137) ─────────────────────
    private static final int GATT_AUTH_FAIL = 0x89;

    // ── Fallback: tempo máximo aguardando AUTH:OK após serviços descobertos ───
    // Se AUTH:OK não chegar neste prazo, assumimos que o ESP32 não vai enviar
    // (bond em estado inconsistente) e forçamos READY.
    private static final long AUTH_OK_FALLBACK_MS = 5_000L;

    // ─────────────────────────────────────────────────────────────────────────
    // MÁQUINA DE ESTADOS BLE
    // ─────────────────────────────────────────────────────────────────────────
    public enum BleState {
        DISCONNECTED,
        CONNECTED,
        READY
    }

    private BleState mBleState = BleState.DISCONNECTED;

    // ── Campos internos ───────────────────────────────────────────────────────
    private BluetoothGatt                mBluetoothGatt;
    private BluetoothAdapter             mBluetoothAdapter;
    private BluetoothGattCharacteristic  mWriteCharacteristic;
    private String                       mTargetMac;
    private boolean                      mAutoReconnect = true;

    // ── Timer fallback AUTH:OK ────────────────────────────────────────────────
    private final Handler  mAuthFallbackHandler  = new Handler(Looper.getMainLooper());
    private Runnable       mAuthFallbackRunnable = null;

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

        IntentFilter pairingFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver, pairingFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver, pairingFilter);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. Estado inicial: DISCONNECTED. Receiver de pareamento registrado.");
        Log.i(TAG, "[BLE] MAC alvo configurado: " + (mTargetMac != null ? mTargetMac : "NÃO CONFIGURADO"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelarAuthFallback();
        try { unregisterReceiver(mPairingReceiver); } catch (Exception ignored) {}
        closeGatt();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timer fallback AUTH:OK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia o timer de fallback: se AUTH:OK não chegar em AUTH_OK_FALLBACK_MS,
     * verifica o bondState e força READY se o bond existir.
     *
     * Isso cobre o cenário onde o pareamento ocorre mas AUTH:OK é perdido
     * (ex: CCCD ainda não ativado quando AUTH:OK foi enviado pelo ESP32).
     */
    private void iniciarAuthFallback(BluetoothDevice device) {
        cancelarAuthFallback();
        mAuthFallbackRunnable = () -> {
            if (mBleState == BleState.READY) {
                Log.d(TAG, "[BLE] Fallback AUTH:OK — já em READY, ignorado.");
                return;
            }
            if (mBluetoothGatt == null) {
                Log.w(TAG, "[BLE] Fallback AUTH:OK — GATT nulo, ignorado.");
                return;
            }
            int bondState = device.getBondState();
            String bondName = bondStateName(bondState);
            Log.w(TAG, "[BLE] ⚠ FALLBACK AUTH:OK disparado após " + (AUTH_OK_FALLBACK_MS / 1000)
                    + "s sem AUTH:OK. bondState=" + bondName + " (" + bondState + ")");

            if (bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "[BLE] Fallback: BOND_BONDED confirmado → forçando READY");
                transitionTo(BleState.READY);
                broadcastWriteReady();
            } else if (bondState == BluetoothDevice.BOND_BONDING) {
                Log.w(TAG, "[BLE] Fallback: BOND_BONDING ainda em andamento — aguardando mais 3s");
                // Reagenda por mais 3s para dar tempo ao pareamento concluir
                mAuthFallbackHandler.postDelayed(() -> {
                    if (mBleState == BleState.READY) return;
                    int bs2 = device.getBondState();
                    Log.w(TAG, "[BLE] Fallback 2a tentativa: bondState=" + bondStateName(bs2));
                    if (bs2 == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "[BLE] Fallback 2a: BOND_BONDED → forçando READY");
                        transitionTo(BleState.READY);
                        broadcastWriteReady();
                    } else {
                        Log.e(TAG, "[BLE] Fallback 2a: bond NÃO concluído (" + bondStateName(bs2)
                                + "). AUTH:OK nunca chegará. Forçando READY como último recurso.");
                        transitionTo(BleState.READY);
                        broadcastWriteReady();
                    }
                }, 3000);
            } else {
                // BOND_NONE: pareamento falhou ou não ocorreu
                Log.e(TAG, "[BLE] Fallback: BOND_NONE — pareamento não ocorreu. "
                        + "Forçando READY como último recurso para não travar o usuário.");
                transitionTo(BleState.READY);
                broadcastWriteReady();
            }
        };
        mAuthFallbackHandler.postDelayed(mAuthFallbackRunnable, AUTH_OK_FALLBACK_MS);
        Log.d(TAG, "[BLE] Timer fallback AUTH:OK iniciado (" + AUTH_OK_FALLBACK_MS / 1000 + "s)");
    }

    private void cancelarAuthFallback() {
        if (mAuthFallbackRunnable != null) {
            mAuthFallbackHandler.removeCallbacks(mAuthFallbackRunnable);
            mAuthFallbackRunnable = null;
            Log.d(TAG, "[BLE] Timer fallback AUTH:OK cancelado");
        }
    }

    private static String bondStateName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE:    return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING: return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED:  return "BOND_BONDED";
            default:                           return "UNKNOWN(" + state + ")";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver de pareamento automático com PIN 259087
    // ─────────────────────────────────────────────────────────────────────────

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

            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) {
                Log.d(TAG, "[BLE] Pairing request ignorado — MAC " + device.getAddress() + " não é o alvo");
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] *** PAIRING REQUEST *** variant=" + variant
                    + " device=" + device.getAddress()
                    + " bondState=" + bondStateName(device.getBondState()));

            if (variant == VARIANT_PIN || variant == VARIANT_PASSKEY) {
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + (ok ? "ACEITO" : "REJEITADO")
                        + " | Aguardando conclusão do bond...");
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
            Log.d(TAG, "[BLE] onConnectionStateChange: status=" + status + " newState=" + newState
                    + " device=" + gatt.getDevice().getAddress()
                    + " bondState=" + bondStateName(gatt.getDevice().getBondState()));

            if (status == GATT_AUTH_FAIL) {
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL (0x89) — bond inválido. Removendo e reconectando...");
                cancelarAuthFallback();
                transitionTo(BleState.DISCONNECTED);
                removeBond(gatt.getDevice());
                closeGatt();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> connectToDevice(gatt.getDevice()), 1500);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[BLE] GATT conectado — solicitando MTU 512"
                        + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
                transitionTo(BleState.CONNECTED);
                broadcastConnectionStatus("connected");
                gatt.requestMtu(512);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[BLE] GATT desconectado (status=" + status + ")"
                        + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
                cancelarAuthFallback();
                transitionTo(BleState.DISCONNECTED);
                mWriteCharacteristic = null;
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU negociado: " + mtu + " — descobrindo serviços..."
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothDevice device = gatt.getDevice();
            int bondState = device.getBondState();
            Log.i(TAG, "[BLE] onServicesDiscovered: status=" + status
                    + " | bondState=" + bondStateName(bondState)
                    + " | device=" + device.getAddress());

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] discoverServices falhou: status=" + status);
                return;
            }
            BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "[BLE] Serviço NUS não encontrado no dispositivo! Verificar firmware.");
                return;
            }
            mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            setupNotifications(gatt, txChar);

            Log.i(TAG, "[BLE] NUS encontrado. mWriteCharacteristic="
                    + (mWriteCharacteristic != null ? "OK" : "NULL")
                    + " | txChar=" + (txChar != null ? "OK" : "NULL"));

            // ── Decisão baseada no bondState ──────────────────────────────────
            if (bondState == BluetoothDevice.BOND_BONDED) {
                // Bond já existe: ESP32 NÃO enviará AUTH:OK novamente.
                // Transitar para READY imediatamente.
                Log.i(TAG, "[BLE] BOND_BONDED detectado — transitando para READY imediatamente.");
                cancelarAuthFallback();
                transitionTo(BleState.READY);
                broadcastWriteReady();

            } else if (bondState == BluetoothDevice.BOND_BONDING) {
                // Pareamento em andamento: aguardar AUTH:OK + fallback
                Log.i(TAG, "[BLE] BOND_BONDING em andamento — aguardando AUTH:OK do ESP32."
                        + " Fallback em " + AUTH_OK_FALLBACK_MS / 1000 + "s.");
                iniciarAuthFallback(device);

            } else {
                // BOND_NONE: primeiro pareamento ou bond removido.
                // mPairingReceiver interceptará ACTION_PAIRING_REQUEST e injetará o PIN.
                Log.i(TAG, "[BLE] BOND_NONE — aguardando ACTION_PAIRING_REQUEST + AUTH:OK."
                        + " Fallback em " + AUTH_OK_FALLBACK_MS / 1000 + "s.");
                iniciarAuthFallback(device);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            String data = new String(characteristic.getValue()).trim();
            Log.d(TAG, "[BLE] ESP32 → Android: [" + data + "]"
                    + " | estado=" + mBleState.name()
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));

            if ("AUTH:OK".equalsIgnoreCase(data)) {
                Log.i(TAG, "[BLE] AUTH:OK recebido do ESP32"
                        + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
                cancelarAuthFallback(); // AUTH:OK chegou, cancelar fallback
                if (mBleState != BleState.READY) {
                    Log.i(TAG, "[BLE] AUTH:OK → transitando para READY");
                    transitionTo(BleState.READY);
                    broadcastWriteReady();
                } else {
                    Log.d(TAG, "[BLE] AUTH:OK recebido mas já em READY — ignorado");
                }
            }

            broadcastData(data);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "[BLE] onDescriptorWrite: status=" + status
                    + " | CCCD=" + (status == BluetoothGatt.GATT_SUCCESS ? "OK" : "FALHOU")
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
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

    public BleState getBleState() { return mBleState; }

    public boolean isReady() { return mBleState == BleState.READY; }

    /**
     * Força a transição para READY e emite ACTION_WRITE_READY.
     * Chamado por PagamentoConcluido quando o serviço é recriado e o GATT
     * já está conectado com bond válido, mas o estado interno foi perdido.
     */
    public void forceReady() {
        Log.i(TAG, "[BLE] forceReady() chamado externamente"
                + " | estadoAtual=" + mBleState.name()
                + " | gatt=" + (mBluetoothGatt != null ? "OK" : "NULL")
                + " | bondState=" + (mBluetoothGatt != null
                    ? bondStateName(mBluetoothGatt.getDevice().getBondState()) : "N/A"));
        cancelarAuthFallback();
        transitionTo(BleState.READY);
        broadcastWriteReady();
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
            boolean ok = gatt.writeDescriptor(desc);
            Log.d(TAG, "[BLE] CCCD writeDescriptor → " + (ok ? "OK" : "FALHOU"));
        } else {
            Log.e(TAG, "[BLE] CCCD descriptor não encontrado na TX characteristic!");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    public void connectToDevice(BluetoothDevice device) {
        Log.i(TAG, "[BLE] connectGatt() → " + device.getAddress()
                + " | bondState=" + bondStateName(device.getBondState()));
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    public void write(String data) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — GATT nulo");
            return;
        }
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — mWriteCharacteristic nulo");
            return;
        }
        if (!isReady()) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") BLOQUEADO — estado=" + mBleState.name()
                    + " | bondState=" + bondStateName(mBluetoothGatt.getDevice().getBondState())
                    + " | NUNCA enviar $ML antes de READY!");
            return;
        }
        mWriteCharacteristic.setValue(data.getBytes());
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[BLE] write(\"" + data + "\") → " + (ok ? "ENVIADO" : "FALHOU (retornou false)"));
        if (!ok) {
            Log.e(TAG, "[BLE] writeCharacteristic retornou false! Verificar: GATT conectado? "
                    + "Characteristic writable? Operação GATT em andamento?");
        }
    }

    public boolean connected() {
        boolean gattOk = mBluetoothGatt != null;
        Log.d(TAG, "[BLE] connected() → " + gattOk + " | estado=" + mBleState.name());
        return gattOk;
    }

    public void disconnect() {
        mAutoReconnect = false;
        cancelarAuthFallback();
        transitionTo(BleState.DISCONNECTED);
        closeGatt();
        Log.i(TAG, "[BLE] disconnect() — mAutoReconnect=false, GATT fechado");
    }

    public void enableAutoReconnect() {
        mAutoReconnect = true;
        Log.i(TAG, "[BLE] enableAutoReconnect() — mAutoReconnect=true");
    }

    public BluetoothDevice getBoundDevice() {
        if (mBluetoothGatt == null) {
            Log.d(TAG, "[BLE] getBoundDevice() → null (GATT nulo)");
            return null;
        }
        BluetoothDevice dev = mBluetoothGatt.getDevice();
        Log.d(TAG, "[BLE] getBoundDevice() → " + dev.getAddress()
                + " bondState=" + bondStateName(dev.getBondState()));
        return dev;
    }

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

    public void scanLeDevice(boolean enable) {
        if (mTargetMac != null) {
            Log.i(TAG, "[BLE] scanLeDevice() → conectando diretamente ao MAC alvo: " + mTargetMac);
            BluetoothDevice dev = mBluetoothAdapter.getRemoteDevice(mTargetMac);
            Log.i(TAG, "[BLE] bondState antes de connectGatt: " + bondStateName(dev.getBondState()));
            connectToDevice(dev);
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

    private void broadcastWriteReady() {
        Log.i(TAG, "[BLE] *** ACTION_WRITE_READY emitido *** — canal pronto para $ML");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(BleState state) {
        Intent intent = new Intent(ACTION_BLE_STATE_CHANGED);
        intent.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
