package com.example.choppontap;

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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * BluetoothService — Serviço BLE com bond explícito antes de connectGatt().
 *
 * PROBLEMA RAIZ IDENTIFICADO NO LOG:
 *   O ACTION_PAIRING_REQUEST NUNCA foi disparado pelo Android em nenhuma das
 *   tentativas. O bondState permaneceu BOND_NONE em todo o ciclo. Isso significa
 *   que o ESP32 está configurado com "Security Mode 1, Level 2" (Just Works ou
 *   PIN) mas o Android não iniciou o processo de pareamento porque o GATT
 *   conectou sem exigir bond (o stack BLE Android não solicita bond
 *   automaticamente para conexões GATT a menos que o periférico force).
 *
 * SOLUÇÃO:
 *   1. Antes de connectGatt(), verificar se já há bond (BOND_BONDED).
 *   2. Se NÃO há bond → chamar device.createBond() PRIMEIRO.
 *   3. Registrar ACTION_BOND_STATE_CHANGED para detectar quando o bond
 *      for concluído (BOND_BONDED).
 *   4. Somente após BOND_BONDED → chamar connectGatt().
 *   5. Com bond já existente, o ESP32 executa onAuthenticationComplete()
 *      e envia AUTH:OK → READY → $ML.
 *
 * FLUXO CORRETO:
 *   createBond() → BOND_BONDING → ACTION_PAIRING_REQUEST → setPin(259087)
 *   → BOND_BONDED → connectGatt() → discoverServices() → AUTH:OK → READY → $ML
 */
public class BluetoothService extends Service {

    private static final String TAG = "BLE_ADVANCED";

    // ── Mensagens Handler (compatibilidade legado) ────────────────────────────
    public static final int MESSAGE_READ            = 0;
    public static final int MESSAGE_WRITE           = 1;
    public static final int MESSAGE_CONNECTION_LOST = 2;

    // ── PIN do ESP32 ──────────────────────────────────────────────────────────
    private static final String ESP32_PIN = "259087";

    // ── Variantes de pareamento ───────────────────────────────────────────────
    private static final int VARIANT_PIN                  = 0;
    private static final int VARIANT_PASSKEY              = 1;
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2;

    // ── UUIDs NUS ─────────────────────────────────────────────────────────────
    private static final UUID NUS_SERVICE_UUID           = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID                  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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

    // ── GATT_AUTH_FAIL ────────────────────────────────────────────────────────
    private static final int GATT_AUTH_FAIL = 0x89;

    // ── Timeout para bond concluir após createBond() ──────────────────────────
    private static final long BOND_TIMEOUT_MS    = 15_000L;
    // ── Timeout para AUTH:OK após connectGatt() (bond já existe) ─────────────
    private static final long AUTH_OK_TIMEOUT_MS = 5_000L;

    // ─────────────────────────────────────────────────────────────────────────
    // MÁQUINA DE ESTADOS
    // ─────────────────────────────────────────────────────────────────────────
    public enum BleState { DISCONNECTED, CONNECTED, READY }

    private BleState mBleState = BleState.DISCONNECTED;

    // ── Campos internos ───────────────────────────────────────────────────────
    private BluetoothGatt               mBluetoothGatt;
    private BluetoothAdapter            mBluetoothAdapter;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private String                      mTargetMac;
    private boolean                     mAutoReconnect = true;

    // ── Handlers de timeout ───────────────────────────────────────────────────
    private final Handler  mTimeoutHandler   = new Handler(Looper.getMainLooper());
    private Runnable       mTimeoutRunnable  = null;

    private final IBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        BluetoothService getService() { return BluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager mgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mgr.getAdapter();
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        // Receiver de pareamento (ACTION_PAIRING_REQUEST)
        IntentFilter pf = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pf.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        // Receiver de mudança de bond (ACTION_BOND_STATE_CHANGED)
        IntentFilter bf = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver, pf, Context.RECEIVER_EXPORTED);
            registerReceiver(mBondStateReceiver, bf, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver, pf);
            registerReceiver(mBondStateReceiver, bf);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. MAC alvo: "
                + (mTargetMac != null ? mTargetMac : "NÃO CONFIGURADO"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelarTimeout();
        try { unregisterReceiver(mPairingReceiver); }   catch (Exception ignored) {}
        try { unregisterReceiver(mBondStateReceiver); } catch (Exception ignored) {}
        closeGatt();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver: ACTION_PAIRING_REQUEST — injeta PIN 259087
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
                Log.d(TAG, "[BLE] PAIRING_REQUEST ignorado — MAC " + device.getAddress() + " não é alvo");
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] *** ACTION_PAIRING_REQUEST *** variant=" + variant
                    + " | device=" + device.getAddress()
                    + " | bondState=" + bondStateName(device.getBondState()));

            if (variant == VARIANT_PIN || variant == VARIANT_PASSKEY) {
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + (ok ? "ACEITO" : "REJEITADO"));
                abortBroadcast();
            } else if (variant == VARIANT_PASSKEY_CONFIRMATION) {
                device.setPairingConfirmation(true);
                Log.i(TAG, "[BLE] Numeric Comparison confirmado automaticamente");
                abortBroadcast();
            } else {
                Log.w(TAG, "[BLE] Variante de pareamento desconhecida: " + variant);
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver: ACTION_BOND_STATE_CHANGED — aguarda BOND_BONDED para conectar GATT
    // ─────────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver mBondStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;

            BluetoothDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            }
            if (device == null) return;

            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) return;

            int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
            int newState  = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);

            Log.i(TAG, "[BLE] *** BOND_STATE_CHANGED *** "
                    + bondStateName(prevState) + " → " + bondStateName(newState)
                    + " | device=" + device.getAddress());

            if (newState == BluetoothDevice.BOND_BONDED) {
                // Bond concluído com sucesso — agora conectar o GATT
                Log.i(TAG, "[BLE] BOND_BONDED! Cancelando timeout e conectando GATT...");
                cancelarTimeout();
                // Pequeno delay para o stack BLE estabilizar após o bond
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "[BLE] Iniciando connectGatt() após bond concluído");
                    conectarGatt(device);
                }, 500);

            } else if (newState == BluetoothDevice.BOND_NONE
                    && prevState == BluetoothDevice.BOND_BONDING) {
                // Pareamento falhou
                Log.e(TAG, "[BLE] Pareamento FALHOU (BOND_BONDING → BOND_NONE). "
                        + "Verifique se o PIN 259087 está correto no firmware.");
                cancelarTimeout();
                broadcastConnectionStatus("bond_failed");
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks GATT
    // ─────────────────────────────────────────────────────────────────────────

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "[BLE] onConnectionStateChange: status=" + status
                    + " newState=" + newState
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));

            if (status == GATT_AUTH_FAIL) {
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL (0x89) — bond inválido. Removendo e recriando bond...");
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                BluetoothDevice dev = gatt.getDevice();
                closeGatt();
                removeBond(dev);
                // Após removeBond, aguardar BOND_NONE e recriar bond
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "[BLE] Recriando bond após GATT_AUTH_FAIL...");
                    iniciarBondEConectar(dev);
                }, 1500);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[BLE] GATT conectado | bondState="
                        + bondStateName(gatt.getDevice().getBondState())
                        + " — solicitando MTU 512");
                transitionTo(BleState.CONNECTED);
                broadcastConnectionStatus("connected");
                gatt.requestMtu(512);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[BLE] GATT desconectado (status=" + status + ")");
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                mWriteCharacteristic = null;
                broadcastConnectionStatus("disconnected");
                if (mAutoReconnect) retryConnection();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[BLE] MTU=" + mtu + " | bondState="
                    + bondStateName(gatt.getDevice().getBondState())
                    + " — descobrindo serviços...");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothDevice device = gatt.getDevice();
            int bondState = device.getBondState();
            Log.i(TAG, "[BLE] onServicesDiscovered: status=" + status
                    + " | bondState=" + bondStateName(bondState));

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[BLE] discoverServices falhou: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
            if (service == null) {
                Log.e(TAG, "[BLE] Serviço NUS não encontrado!");
                return;
            }
            mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            setupNotifications(gatt, txChar);

            Log.i(TAG, "[BLE] NUS OK | RX=" + (mWriteCharacteristic != null ? "OK" : "NULL")
                    + " | TX=" + (txChar != null ? "OK" : "NULL"));

            // Neste ponto o bond JÁ DEVE EXISTIR (criamos antes de connectGatt).
            // Se por algum motivo não existe, aguardar AUTH:OK com timeout de segurança.
            if (bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "[BLE] BOND_BONDED confirmado em onServicesDiscovered."
                        + " Aguardando AUTH:OK do ESP32 (timeout " + AUTH_OK_TIMEOUT_MS / 1000 + "s)...");
                iniciarTimeoutAuthOk(device);
            } else {
                Log.w(TAG, "[BLE] AVISO: bondState=" + bondStateName(bondState)
                        + " em onServicesDiscovered. Aguardando AUTH:OK com timeout de segurança.");
                iniciarTimeoutAuthOk(device);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            String data = new String(characteristic.getValue()).trim();
            Log.d(TAG, "[BLE] ESP32→Android: [" + data + "]"
                    + " | estado=" + mBleState.name()
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));

            if ("AUTH:OK".equalsIgnoreCase(data)) {
                Log.i(TAG, "[BLE] AUTH:OK recebido — bond autenticado, transitando para READY");
                cancelarTimeout();
                if (mBleState != BleState.READY) {
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
            Log.d(TAG, "[BLE] onDescriptorWrite: CCCD="
                    + (status == BluetoothGatt.GATT_SUCCESS ? "OK" : "FALHOU status=" + status)
                    + " | bondState=" + bondStateName(gatt.getDevice().getBondState()));
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Lógica de bond + conexão
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ponto de entrada principal para conectar ao ESP32.
     *
     * Se o device já tem BOND_BONDED → conecta GATT diretamente.
     * Se não tem bond → chama createBond() e aguarda BOND_BONDED via receiver.
     */
    private void iniciarBondEConectar(BluetoothDevice device) {
        int bondState = device.getBondState();
        Log.i(TAG, "[BLE] iniciarBondEConectar() | bondState=" + bondStateName(bondState)
                + " | device=" + device.getAddress());

        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "[BLE] Bond já existe → conectando GATT diretamente");
            conectarGatt(device);
        } else if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.i(TAG, "[BLE] Bond em andamento → aguardando BOND_STATE_CHANGED...");
            iniciarTimeoutBond(device);
        } else {
            // BOND_NONE → criar bond agora
            Log.i(TAG, "[BLE] BOND_NONE → chamando createBond() para iniciar pareamento com PIN 259087");
            boolean ok = device.createBond();
            Log.i(TAG, "[BLE] createBond() → " + (ok ? "INICIADO" : "FALHOU (já em andamento?)"));
            if (ok) {
                iniciarTimeoutBond(device);
            } else {
                // createBond() retornou false: pode já estar em BOND_BONDING
                // Aguardar o receiver resolver
                Log.w(TAG, "[BLE] createBond() retornou false — aguardando BOND_STATE_CHANGED...");
                iniciarTimeoutBond(device);
            }
        }
    }

    private void conectarGatt(BluetoothDevice device) {
        Log.i(TAG, "[BLE] connectGatt() → " + device.getAddress()
                + " | bondState=" + bondStateName(device.getBondState()));
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timeouts
    // ─────────────────────────────────────────────────────────────────────────

    /** Timeout aguardando bond concluir (BOND_BONDED). */
    private void iniciarTimeoutBond(BluetoothDevice device) {
        cancelarTimeout();
        mTimeoutRunnable = () -> {
            int bs = device.getBondState();
            Log.e(TAG, "[BLE] TIMEOUT BOND (" + BOND_TIMEOUT_MS / 1000 + "s) — bondState="
                    + bondStateName(bs) + ". Tentando connectGatt mesmo assim...");
            // Tenta conectar mesmo sem bond confirmado — como último recurso
            conectarGatt(device);
        };
        mTimeoutHandler.postDelayed(mTimeoutRunnable, BOND_TIMEOUT_MS);
        Log.d(TAG, "[BLE] Timeout bond iniciado (" + BOND_TIMEOUT_MS / 1000 + "s)");
    }

    /** Timeout aguardando AUTH:OK após connectGatt() com bond existente. */
    private void iniciarTimeoutAuthOk(BluetoothDevice device) {
        cancelarTimeout();
        mTimeoutRunnable = () -> {
            if (mBleState == BleState.READY) return;
            int bs = device.getBondState();
            Log.w(TAG, "[BLE] TIMEOUT AUTH:OK (" + AUTH_OK_TIMEOUT_MS / 1000 + "s)"
                    + " — bondState=" + bondStateName(bs)
                    + ". Forçando READY.");
            transitionTo(BleState.READY);
            broadcastWriteReady();
        };
        mTimeoutHandler.postDelayed(mTimeoutRunnable, AUTH_OK_TIMEOUT_MS);
        Log.d(TAG, "[BLE] Timeout AUTH:OK iniciado (" + AUTH_OK_TIMEOUT_MS / 1000 + "s)");
    }

    private void cancelarTimeout() {
        if (mTimeoutRunnable != null) {
            mTimeoutHandler.removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Máquina de estados
    // ─────────────────────────────────────────────────────────────────────────

    private void transitionTo(BleState newState) {
        if (mBleState == newState) return;
        Log.i(TAG, "[BLE] Estado: " + mBleState.name() + " → " + newState.name());
        mBleState = newState;
        broadcastBleState(newState);
    }

    public BleState getBleState() { return mBleState; }
    public boolean isReady()      { return mBleState == BleState.READY; }

    public void forceReady() {
        Log.i(TAG, "[BLE] forceReady() — estado=" + mBleState.name()
                + " | gatt=" + (mBluetoothGatt != null ? "OK" : "NULL")
                + " | bondState=" + (mBluetoothGatt != null
                    ? bondStateName(mBluetoothGatt.getDevice().getBondState()) : "N/A"));
        cancelarTimeout();
        transitionTo(BleState.READY);
        broadcastWriteReady();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuração de notificações NUS
    // ─────────────────────────────────────────────────────────────────────────

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx == null) { Log.e(TAG, "[BLE] TX characteristic NUS não encontrada!"); return; }
        gatt.setCharacteristicNotification(tx, true);
        BluetoothGattDescriptor desc = tx.getDescriptor(CCCD_UUID);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(desc);
            Log.d(TAG, "[BLE] CCCD writeDescriptor → " + (ok ? "OK" : "FALHOU"));
        } else {
            Log.e(TAG, "[BLE] CCCD descriptor não encontrado!");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    public void write(String data) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — GATT nulo"); return;
        }
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — characteristic nula"); return;
        }
        if (!isReady()) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") BLOQUEADO — estado=" + mBleState.name()
                    + " | bondState=" + bondStateName(mBluetoothGatt.getDevice().getBondState()));
            return;
        }
        mWriteCharacteristic.setValue(data.getBytes());
        boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
        Log.i(TAG, "[BLE] write(\"" + data + "\") → " + (ok ? "ENVIADO" : "FALHOU"));
    }

    public boolean connected() {
        return mBluetoothGatt != null;
    }

    public void disconnect() {
        mAutoReconnect = false;
        cancelarTimeout();
        transitionTo(BleState.DISCONNECTED);
        closeGatt();
        Log.i(TAG, "[BLE] disconnect() — GATT fechado, autoReconnect=false");
    }

    public void enableAutoReconnect() {
        mAutoReconnect = true;
        Log.i(TAG, "[BLE] enableAutoReconnect()");
    }

    public BluetoothDevice getBoundDevice() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getDevice();
    }

    public static void removeBond(BluetoothDevice device) {
        if (device == null) return;
        try {
            Method m = device.getClass().getMethod("removeBond", (Class[]) null);
            m.invoke(device, (Object[]) null);
            Log.i(TAG, "[BLE] removeBond() → " + device.getAddress());
        } catch (Exception e) {
            Log.e(TAG, "[BLE] removeBond() erro: " + e.getMessage());
        }
    }

    /**
     * Ponto de entrada externo: verifica bond e conecta.
     * Substitui o antigo scanLeDevice() que chamava connectGatt() diretamente
     * sem verificar o bond.
     */
    public void scanLeDevice(boolean enable) {
        if (mTargetMac == null) {
            Log.w(TAG, "[BLE] scanLeDevice() — MAC alvo não configurado");
            return;
        }
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
        Log.i(TAG, "[BLE] scanLeDevice() → " + mTargetMac
                + " | bondState=" + bondStateName(device.getBondState()));
        iniciarBondEConectar(device);
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
            Log.i(TAG, "[BLE] Reconexão em 5s → " + mTargetMac);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                BluetoothDevice dev = mBluetoothAdapter.getRemoteDevice(mTargetMac);
                iniciarBondEConectar(dev);
            }, 5000);
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
    // Broadcasts
    // ─────────────────────────────────────────────────────────────────────────

    private void broadcastConnectionStatus(String status) {
        Intent i = new Intent(ACTION_CONNECTION_STATUS);
        i.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(ACTION_DATA_AVAILABLE);
        i.putExtra(EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastWriteReady() {
        Log.i(TAG, "[BLE] *** ACTION_WRITE_READY emitido *** — pronto para $ML");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(BleState state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }
}
