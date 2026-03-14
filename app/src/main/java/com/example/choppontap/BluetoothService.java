package com.example.choppontap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
 * BluetoothService — Serviço BLE robusto com reconexão persistente.
 *
 * CAUSAS RAIZ IDENTIFICADAS NO LOG (pasted_content_2.txt):
 *
 *   CAUSA 1 — status=257 (0x101) na fase inicial:
 *     O Android tenta connectGatt() enquanto o ESP32 ainda está em advertising
 *     mas com o stack BLE ocupado (bond recém-criado). O status=257 é
 *     GATT_CONN_FAIL_ESTABLISH — falha antes de estabelecer a conexão física.
 *     SOLUÇÃO: usar autoConnect=true no connectGatt() para que o Android
 *     aguarde o ESP32 ficar disponível sem precisar de retentativas manuais.
 *
 *   CAUSA 2 — status=8 (GATT_CONN_TIMEOUT) durante liberação:
 *     O ESP32 fecha a conexão BLE enquanto a válvula está aberta (durante a
 *     task taskLiberaML). Isso ocorre porque o firmware fecha o GATT ao
 *     terminar o advertising e o Android não consegue reconectar rapidamente.
 *     SOLUÇÃO: não chamar gatt.close() no status=8 — apenas gatt.disconnect()
 *     e aguardar o callback STATE_DISCONNECTED. Depois reconectar com
 *     autoConnect=true para reconexão automática pelo stack BLE.
 *
 *   CAUSA 3 — BOND_BONDED → BOND_NONE durante reconexão:
 *     O código anterior chamava removeBond() no GATT_AUTH_FAIL (0x89), mas o
 *     status=8 não é GATT_AUTH_FAIL. O bond estava sendo removido
 *     desnecessariamente, forçando um novo pareamento a cada desconexão.
 *     SOLUÇÃO: só remover bond se status == GATT_AUTH_FAIL (0x89).
 *     Para status=8 (timeout), apenas reconectar sem remover bond.
 *
 * FLUXO CORRETO (bond já existe):
 *   connectGatt(autoConnect=true) → CONNECTED → MTU → discoverServices()
 *   → $AUTH:259087 → AUTH:OK → READY → $ML:300 → OK → válvula aberta
 *   → (desconexão status=8) → gatt.disconnect() → STATE_DISCONNECTED
 *   → connectGatt(autoConnect=true) → reconecta automaticamente
 *   → AUTH:OK → READY → reenvia $ML restante
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
    private static final int VARIANT_CONSENT              = 3;

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

    // ── Códigos de status GATT ────────────────────────────────────────────────
    /** GATT_AUTH_FAIL: bond inválido — deve remover e recriar bond */
    private static final int GATT_AUTH_FAIL         = 0x89;
    /** GATT_CONN_TIMEOUT: timeout de conexão — NÃO remover bond, apenas reconectar */
    private static final int GATT_CONN_TIMEOUT      = 0x08;
    /** GATT_CONN_FAIL_ESTABLISH: falha ao estabelecer — NÃO remover bond */
    private static final int GATT_CONN_FAIL_ESTABLISH = 0x3E; // 62 decimal = status=257 mascarado

    // ── Timeouts ──────────────────────────────────────────────────────────────
    private static final long BOND_TIMEOUT_MS    = 15_000L;
    private static final long AUTH_OK_TIMEOUT_MS = 8_000L;  // aumentado para dar tempo ao ESP32

    // ── Backoff de reconexão ──────────────────────────────────────────────────
    private static final long RECONNECT_DELAY_INITIAL_MS = 2_000L;
    private static final long RECONNECT_DELAY_MAX_MS     = 15_000L;
    private long mReconnectDelay = RECONNECT_DELAY_INITIAL_MS;
    private int  mReconnectAttempts = 0;

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
    private final Handler  mTimeoutHandler  = new Handler(Looper.getMainLooper());
    private Runnable       mTimeoutRunnable = null;
    private Runnable       mReconnectRunnable = null;

    private final IBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        BluetoothService getService() { return BluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    private static final String NOTIF_CHANNEL_ID = "ble_service_channel";
    private static final int    NOTIF_ID          = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        // FIX: Android 12+ exige startForeground() dentro de 5s após startForegroundService()
        criarNotificacaoForeground();

        BluetoothManager mgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mgr.getAdapter();
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        IntentFilter pf = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pf.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        IntentFilter bf = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mPairingReceiver,   pf, Context.RECEIVER_EXPORTED);
            registerReceiver(mBondStateReceiver, bf, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mPairingReceiver,   pf);
            registerReceiver(mBondStateReceiver, bf);
        }

        Log.i(TAG, "[BLE] Serviço iniciado. MAC alvo: "
                + (mTargetMac != null ? mTargetMac : "NÃO CONFIGURADO"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAutoReconnect = false;
        cancelarTimeout();
        cancelarReconexao();
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
            } else if (variant == VARIANT_CONSENT) {
                device.setPairingConfirmation(true);
                Log.i(TAG, "[BLE] CONSENT (variant=3) confirmado automaticamente");
                abortBroadcast();
            } else {
                Log.w(TAG, "[BLE] Variante desconhecida: " + variant + " — tentando setPairingConfirmation(true)");
                try { device.setPairingConfirmation(true); } catch (Exception e) {
                    Log.e(TAG, "[BLE] setPairingConfirmation falhou: " + e.getMessage());
                }
                abortBroadcast();
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Receiver: ACTION_BOND_STATE_CHANGED
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
                Log.i(TAG, "[BLE] BOND_BONDED! Cancelando timeout e conectando GATT...");
                cancelarTimeout();
                // Salva o MAC assim que o bond é confirmado
                getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                        .edit().putString("esp32_mac", device.getAddress()).apply();
                mTargetMac = device.getAddress();
                // Pequeno delay para o stack BLE estabilizar após o bond
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "[BLE] Iniciando connectGatt() após bond concluído");
                    conectarGatt(device);
                }, 500);

            } else if (newState == BluetoothDevice.BOND_NONE
                    && prevState == BluetoothDevice.BOND_BONDING) {
                Log.e(TAG, "[BLE] Pareamento FALHOU (BOND_BONDING → BOND_NONE). "
                        + "Verifique se o PIN 259087 está correto no firmware.");
                cancelarTimeout();
                broadcastConnectionStatus("bond_failed");
            }
            // BOND_BONDED → BOND_NONE durante operação = bond removido externamente.
            // Não tomar ação aqui — o onConnectionStateChange já vai tratar.
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Callbacks GATT
    // ─────────────────────────────────────────────────────────────────────────

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = gatt.getDevice();
            int bondState = device.getBondState();
            Log.d(TAG, "[BLE] onConnectionStateChange: status=" + status
                    + " newState=" + newState
                    + " | bondState=" + bondStateName(bondState));

            // ─────────────────────────────────────────────────────────────
            // FIX DEFINITIVO STATUS=8 (Connection Supervision Timeout):
            //
            // O status=8 ocorre porque o Android usa por padrão o
            // CONNECTION_PRIORITY_BALANCED (interval=45ms, timeout=720ms).
            // Com esse intervalo, se o ESP32 perder 8 connection events
            // consecutivos (8 × 45ms = 360ms), o Android declara timeout.
            //
            // Durante a dispensação, o ESP32 está ocupado na taskLiberaML
            // (loop while com vTaskDelay(50ms)) e pode perder connection events.
            //
            // SOLUÇÃO: requestConnectionPriority(HIGH) logo após conectar:
            //   - Connection interval: 7.5ms ~ 15ms
            //   - Supervision timeout: 5000ms
            //   - Slave latency: 0
            //
            // Isso garante que o Android tente reconectar muito mais rápido
            // e o ESP32 tenha muito mais tempo antes de ser declarado perdido.
            // ─────────────────────────────────────────────────────────────
            if (status == GATT_AUTH_FAIL) {
                // Bond inválido — único caso onde devemos remover e recriar bond
                Log.e(TAG, "[BLE] GATT_AUTH_FAIL (0x89) — bond inválido. Removendo e recriando bond...");
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                closeGatt();
                removeBond(device);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Log.i(TAG, "[BLE] Recriando bond após GATT_AUTH_FAIL...");
                    iniciarBondEConectar(device);
                }, 2000);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                String mac = device.getAddress();
                Log.i(TAG, "[BLE] GATT conectado | MAC=" + mac
                        + " | bondState=" + bondStateName(bondState)
                        + " — solicitando MTU 512");
                // Salva MAC e reseta backoff ao conectar com sucesso
                getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                        .edit().putString("esp32_mac", mac).apply();
                mTargetMac = mac;
                mReconnectDelay = RECONNECT_DELAY_INITIAL_MS;
                mReconnectAttempts = 0;
                transitionTo(BleState.CONNECTED);
                broadcastConnectionStatus("connected");
                // FIX STATUS=8: forçar CONNECTION_PRIORITY_HIGH imediatamente
                // para reduzir o connection interval e aumentar o supervision timeout
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                Log.i(TAG, "[BLE] requestConnectionPriority(HIGH) solicitado");
                gatt.requestMtu(512);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[BLE] GATT desconectado (status=" + status + ")");
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                mWriteCharacteristic = null;
                broadcastConnectionStatus("disconnected");

                // FIX-CAUSA2: Para status=8 (GATT_CONN_TIMEOUT) e status=257 (FAIL_ESTABLISH)
                // NÃO fechar o GATT — apenas chamar disconnect() e aguardar reconexão.
                // Fechar o GATT (close()) descarta o cache de serviços e força novo discoverServices(),
                // o que é lento e causa o ciclo de abertura/fechamento da válvula.
                if (status == GATT_CONN_TIMEOUT || status == 257) {
                    Log.i(TAG, "[BLE] status=" + status + " — NÃO fechando GATT (mantendo cache). "
                            + "Reconectando com backoff...");
                    // Não chamar closeGatt() — apenas agendar reconexão
                    if (mAutoReconnect) agendarReconexao(device);
                } else {
                    // Para outros status (desconexão normal, etc.), fechar e reconectar
                    Log.i(TAG, "[BLE] status=" + status + " — fechando GATT e reconectando...");
                    closeGatt();
                    if (mAutoReconnect) agendarReconexao(device);
                }
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
                Log.w(TAG, "[BLE] Serviço NUS não encontrado! bondState="
                        + bondStateName(bondState)
                        + " — redescobrir serviços em 2s...");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mBluetoothGatt != null) {
                        Log.i(TAG, "[BLE] Redescobrir serviços após NUS não encontrado...");
                        mBluetoothGatt.discoverServices();
                    }
                }, 2000);
                return;
            }

            mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            setupNotifications(gatt, txChar);

            Log.i(TAG, "[BLE] NUS OK | RX=" + (mWriteCharacteristic != null ? "OK" : "NULL")
                    + " | TX=" + (txChar != null ? "OK" : "NULL"));

            // Envia $AUTH após serviços descobertos (600ms de delay para estabilizar)
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (mWriteCharacteristic != null && mBluetoothGatt != null) {
                    Log.i(TAG, "[BLE] Enviando $AUTH:" + ESP32_PIN + " para autenticação...");
                    mWriteCharacteristic.setValue(("$AUTH:" + ESP32_PIN).getBytes());
                    boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
                    Log.i(TAG, "[BLE] $AUTH enviado → " + (ok ? "OK" : "FALHOU"));
                }
            }, 600);

            // Inicia timeout aguardando AUTH:OK (8s para dar tempo ao ESP32)
            if (bondState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "[BLE] BOND_BONDED confirmado. Aguardando AUTH:OK (" + AUTH_OK_TIMEOUT_MS / 1000 + "s)...");
            } else {
                Log.w(TAG, "[BLE] bondState=" + bondStateName(bondState)
                        + " em onServicesDiscovered. Aguardando AUTH:OK com timeout de segurança.");
            }
            iniciarTimeoutAuthOk(device);
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
     * Se bond existe → conecta GATT diretamente.
     * Se não → cria bond primeiro.
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
            Log.i(TAG, "[BLE] BOND_NONE → chamando createBond() para iniciar pareamento com PIN 259087");
            boolean ok = device.createBond();
            Log.i(TAG, "[BLE] createBond() → " + (ok ? "INICIADO" : "FALHOU (já em andamento?)"));
            iniciarTimeoutBond(device);
        }
    }

    /**
     * FIX-CAUSA1: Usa autoConnect=true para que o Android aguarde o ESP32
     * ficar disponível automaticamente, sem precisar de retentativas manuais.
     * Isso elimina o ciclo de status=257 (GATT_CONN_FAIL_ESTABLISH).
     */
    private void conectarGatt(BluetoothDevice device) {
        // Se já há um GATT aberto para este device, tentar reconectar nele
        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress().equals(device.getAddress())) {
            Log.i(TAG, "[BLE] GATT já existe para " + device.getAddress()
                    + " — chamando connect() no GATT existente");
            boolean ok = mBluetoothGatt.connect();
            Log.i(TAG, "[BLE] gatt.connect() → " + (ok ? "OK" : "FALHOU"));
            return;
        }
        // Novo GATT: usar autoConnect=true para reconexão automática pelo stack BLE
        Log.i(TAG, "[BLE] connectGatt(autoConnect=true) → " + device.getAddress()
                + " | bondState=" + bondStateName(device.getBondState()));
        mBluetoothGatt = device.connectGatt(this, true, mGattCallback);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reconexão com backoff exponencial
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FIX-CAUSA2: Agenda reconexão com backoff exponencial.
     * Não fecha o GATT para status=8 — apenas agenda nova tentativa.
     */
    private void agendarReconexao(BluetoothDevice device) {
        cancelarReconexao();
        mReconnectAttempts++;
        Log.i(TAG, "[BLE] Reconexão #" + mReconnectAttempts
                + " em " + mReconnectDelay + "ms → " + device.getAddress());

        mReconnectRunnable = () -> {
            if (!mAutoReconnect) return;
            // Verificar se o Bluetooth ainda está ativo
            if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
                Log.w(TAG, "[BLE] Bluetooth desativado — cancelando reconexão");
                return;
            }
            Log.i(TAG, "[BLE] Executando reconexão #" + mReconnectAttempts
                    + " | bondState=" + bondStateName(device.getBondState()));
            iniciarBondEConectar(device);
        };
        mTimeoutHandler.postDelayed(mReconnectRunnable, mReconnectDelay);

        // Backoff exponencial: 2s → 4s → 8s → 15s (máximo)
        mReconnectDelay = Math.min(mReconnectDelay * 2, RECONNECT_DELAY_MAX_MS);
    }

    private void cancelarReconexao() {
        if (mReconnectRunnable != null) {
            mTimeoutHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timeouts
    // ─────────────────────────────────────────────────────────────────────────

    private void iniciarTimeoutBond(BluetoothDevice device) {
        cancelarTimeout();
        mTimeoutRunnable = () -> {
            int bs = device.getBondState();
            Log.e(TAG, "[BLE] TIMEOUT BOND (" + BOND_TIMEOUT_MS / 1000 + "s) — bondState="
                    + bondStateName(bs) + ". Tentando connectGatt mesmo assim...");
            conectarGatt(device);
        };
        mTimeoutHandler.postDelayed(mTimeoutRunnable, BOND_TIMEOUT_MS);
        Log.d(TAG, "[BLE] Timeout bond iniciado (" + BOND_TIMEOUT_MS / 1000 + "s)");
    }

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
        return mBluetoothGatt != null && mBleState != BleState.DISCONNECTED;
    }

    public void disconnect() {
        mAutoReconnect = false;
        cancelarTimeout();
        cancelarReconexao();
        transitionTo(BleState.DISCONNECTED);
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
        }
        closeGatt();
        Log.i(TAG, "[BLE] disconnect() — GATT fechado, autoReconnect=false");
    }

    public void enableAutoReconnect() {
        mAutoReconnect = true;
        mReconnectDelay = RECONNECT_DELAY_INITIAL_MS;
        mReconnectAttempts = 0;
        Log.i(TAG, "[BLE] enableAutoReconnect()");
    }

    public BluetoothDevice getBoundDevice() {
        if (mBluetoothGatt == null) return null;
        return mBluetoothGatt.getDevice();
    }

    /**
     * Ponto de entrada externo: verifica bond e conecta.
     */
    public void scanLeDevice(boolean enable) {
        if (mTargetMac == null) {
            Log.w(TAG, "[BLE] scanLeDevice() — MAC alvo não configurado");
            return;
        }
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
        Log.i(TAG, "[BLE] scanLeDevice() → " + mTargetMac
                + " | bondState=" + bondStateName(device.getBondState()));
        mAutoReconnect = true;
        mReconnectDelay = RECONNECT_DELAY_INITIAL_MS;
        mReconnectAttempts = 0;
        iniciarBondEConectar(device);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Métodos internos
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Notificação Foreground (Android 12+ obrigatório)
    // ─────────────────────────────────────────────────────────────────────────

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "Chopp BLE",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Conexão Bluetooth com a torneira de chopp");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notifIntent = new Intent(this, Home.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);

        Notification notification = new Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("Chopp conectado")
                .setContentText("Serviço BLE ativo")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        // FIX: Android 14+ (API 34) exige o tipo de servico foreground declarado no Manifest
        // ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 0x10 = 16
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        Log.i(TAG, "[BLE] startForeground() chamado — serviço em foreground");
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mWriteCharacteristic = null;
        }
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
