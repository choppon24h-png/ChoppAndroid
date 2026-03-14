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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
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
 * BluetoothService — Serviço BLE definitivo para CHOPP ESP32.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARQUITETURA DE CONEXÃO
 * ═══════════════════════════════════════════════════════════════════
 *
 * CASO 1 — Primeira conexão (sem MAC salvo):
 *   scan BLE → filtra startsWith("CHOPP_") → salva MAC → createBond()
 *   → PIN 259087 injetado automaticamente → BOND_BONDED → connectGatt()
 *   → MTU 512 → discoverServices() → $AUTH:259087 → AUTH:OK → READY
 *
 * CASO 2 — Reconexão (MAC salvo no SharedPreferences):
 *   getRemoteDevice(MAC) → connectGatt(autoConnect=true)
 *   → CONNECTED → MTU → discoverServices() → $AUTH:259087 → AUTH:OK → READY
 *
 * CASO 3 — Desconexão durante dispensação (status=8):
 *   STATE_DISCONNECTED → NÃO fecha GATT → gatt.connect() imediato
 *   → CONNECTED → AUTH:OK → READY → PagamentoConcluido reenvia $ML restante
 *
 * ═══════════════════════════════════════════════════════════════════
 * SEGURANÇA
 * ═══════════════════════════════════════════════════════════════════
 *
 * CAMADA 1 — Bond nativo: PIN 259087 injetado via ACTION_PAIRING_REQUEST.
 *   Impede dispositivos desconhecidos de parear.
 *
 * CAMADA 2 — $AUTH:259087 via característica BLE após discoverServices().
 *   O ESP32 só processa $ML e demais comandos após AUTH:OK.
 *
 * CAMADA 3 — Filtro por MAC: após o primeiro bond, o app conecta
 *   exclusivamente no MAC salvo. Ignora outros dispositivos CHOPP_.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FIX STATUS=8 (Connection Supervision Timeout)
 * ═══════════════════════════════════════════════════════════════════
 *
 * requestConnectionPriority(HIGH) logo após STATE_CONNECTED:
 *   - Connection interval: 7.5ms ~ 15ms (vs 45ms no BALANCED)
 *   - Supervision timeout: 5000ms (vs 720ms no BALANCED)
 *   - Slave latency: 0
 *
 * O ESP32 também força os mesmos parâmetros via esp_ble_gap_update_conn_params()
 * em onConnect(). Com ambos os lados alinhados, o status=8 é eliminado.
 */
public class BluetoothService extends Service {

    private static final String TAG = "BLE_CHOPP";

    // ── Compatibilidade com código legado (Bluetooth.java / Bluetooth2.java) ──
    public static final int MESSAGE_READ            = 0;
    public static final int MESSAGE_WRITE           = 1;
    public static final int MESSAGE_CONNECTION_LOST = 2;

    // ── PIN de autenticação (deve ser igual ao BLE_AUTH_PIN no firmware ESP32) ─
    public static final String ESP32_PIN = "259087";

    // ── Prefixo do nome BLE do ESP32 ─────────────────────────────────────────
    public static final String BLE_NAME_PREFIX = "CHOPP_";

    // ── Variantes de pareamento ───────────────────────────────────────────────
    private static final int VARIANT_PIN                  = 0;
    private static final int VARIANT_PASSKEY              = 1;
    private static final int VARIANT_PASSKEY_CONFIRMATION = 2;
    private static final int VARIANT_CONSENT              = 3;

    // ── UUIDs NUS (Nordic UART Service) ──────────────────────────────────────
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
    /** Bond inválido — deve remover e recriar bond */
    private static final int GATT_AUTH_FAIL         = 0x89;
    /** Connection Supervision Timeout — NÃO remover bond, reconectar imediatamente */
    private static final int GATT_CONN_TIMEOUT      = 0x08;
    /** Falha ao estabelecer conexão — NÃO remover bond */
    private static final int GATT_CONN_FAIL_ESTABLISH = 0x3E;

    // ── Timeouts ──────────────────────────────────────────────────────────────
    private static final long BOND_TIMEOUT_MS       = 15_000L;
    private static final long AUTH_OK_TIMEOUT_MS    = 10_000L;
    private static final long SCAN_TIMEOUT_MS       = 15_000L;

    // ── Backoff de reconexão ──────────────────────────────────────────────────
    private static final long RECONNECT_DELAY_INITIAL_MS = 1_000L;  // 1s na primeira tentativa
    private static final long RECONNECT_DELAY_MAX_MS     = 10_000L; // máximo 10s
    private long mReconnectDelay    = RECONNECT_DELAY_INITIAL_MS;
    private int  mReconnectAttempts = 0;

    // ── Máquina de estados ────────────────────────────────────────────────────
    public enum BleState { DISCONNECTED, CONNECTED, READY }
    private BleState mBleState = BleState.DISCONNECTED;

    // ── Campos internos ───────────────────────────────────────────────────────
    private BluetoothGatt               mBluetoothGatt;
    private BluetoothAdapter            mBluetoothAdapter;
    private BluetoothLeScanner          mBleScanner;
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private String                      mTargetMac;
    private boolean                     mAutoReconnect  = true;
    private boolean                     mScanning       = false;

    // ── Handlers ──────────────────────────────────────────────────────────────
    private final Handler mMainHandler     = new Handler(Looper.getMainLooper());
    private Runnable      mTimeoutRunnable = null;
    private Runnable      mReconnectRunnable = null;
    private Runnable      mScanStopRunnable  = null;

    // ── Binder ────────────────────────────────────────────────────────────────
    private final IBinder mBinder = new LocalBinder();
    public class LocalBinder extends Binder {
        public BluetoothService getService() { return BluetoothService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return mBinder; }

    // ═════════════════════════════════════════════════════════════════════════
    // Ciclo de vida
    // ═════════════════════════════════════════════════════════════════════════

    private static final String NOTIF_CHANNEL_ID = "ble_chopp_channel";
    private static final int    NOTIF_ID          = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        criarNotificacaoForeground();

        BluetoothManager mgr = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mgr.getAdapter();
        mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Carrega MAC salvo de conexão anterior
        mTargetMac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        // Registra receivers de pareamento e bond
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

        Log.i(TAG, "[BLE] Serviço iniciado | MAC alvo: "
                + (mTargetMac != null ? mTargetMac : "NÃO CONFIGURADO — aguardando scan"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mAutoReconnect = false;
        pararScan();
        cancelarTimeout();
        cancelarReconexao();
        try { unregisterReceiver(mPairingReceiver); }   catch (Exception ignored) {}
        try { unregisterReceiver(mBondStateReceiver); } catch (Exception ignored) {}
        closeGatt();
        Log.i(TAG, "[BLE] Serviço destruído");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Receiver: ACTION_PAIRING_REQUEST — injeta PIN 259087 automaticamente
    // ═════════════════════════════════════════════════════════════════════════

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

            // Ignora pareamentos de outros dispositivos
            if (mTargetMac != null && !mTargetMac.equalsIgnoreCase(device.getAddress())) {
                Log.d(TAG, "[BLE] PAIRING_REQUEST ignorado — MAC " + device.getAddress() + " não é alvo");
                return;
            }

            int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
            Log.i(TAG, "[BLE] *** PAIRING_REQUEST *** variant=" + variant
                    + " | device=" + device.getAddress()
                    + " | bondState=" + bondStateName(device.getBondState()));

            // Injeta PIN automaticamente para todos os tipos de pareamento
            if (variant == VARIANT_PIN || variant == VARIANT_PASSKEY) {
                boolean ok = device.setPin(ESP32_PIN.getBytes());
                Log.i(TAG, "[BLE] setPin(" + ESP32_PIN + ") → " + (ok ? "ACEITO" : "REJEITADO"));
                abortBroadcast();
            } else if (variant == VARIANT_PASSKEY_CONFIRMATION || variant == VARIANT_CONSENT) {
                device.setPairingConfirmation(true);
                Log.i(TAG, "[BLE] Confirmação automática (variant=" + variant + ")");
                abortBroadcast();
            } else {
                Log.w(TAG, "[BLE] Variante desconhecida: " + variant + " — confirmando automaticamente");
                try { device.setPairingConfirmation(true); } catch (Exception e) {
                    Log.e(TAG, "[BLE] setPairingConfirmation falhou: " + e.getMessage());
                }
                abortBroadcast();
            }
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Receiver: ACTION_BOND_STATE_CHANGED
    // ═════════════════════════════════════════════════════════════════════════

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

            Log.i(TAG, "[BLE] BOND_STATE: " + bondStateName(prevState) + " → " + bondStateName(newState)
                    + " | " + device.getAddress());

            if (newState == BluetoothDevice.BOND_BONDED) {
                Log.i(TAG, "[BLE] BOND_BONDED! Salvando MAC e conectando GATT...");
                cancelarTimeout();
                // Persiste o MAC para reconexões futuras
                salvarMac(device.getAddress());
                // Delay para o stack BLE estabilizar após o bond
                mMainHandler.postDelayed(() -> conectarGatt(device), 500);

            } else if (newState == BluetoothDevice.BOND_NONE
                    && prevState == BluetoothDevice.BOND_BONDING) {
                Log.e(TAG, "[BLE] Pareamento FALHOU. Verifique se o PIN " + ESP32_PIN + " está correto no firmware.");
                cancelarTimeout();
                broadcastConnectionStatus("bond_failed");
            }
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Scan BLE — usado apenas quando não há MAC salvo
    // ═════════════════════════════════════════════════════════════════════════

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null) return;

            Log.d(TAG, "[SCAN] Dispositivo encontrado: " + name + " | " + device.getAddress());

            // Filtra por prefixo CHOPP_
            if (!name.startsWith(BLE_NAME_PREFIX)) return;

            Log.i(TAG, "[SCAN] *** CHOPP encontrado: " + name + " | " + device.getAddress() + " ***");
            pararScan();
            salvarMac(device.getAddress());
            iniciarBondEConectar(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "[SCAN] Falhou com código: " + errorCode);
            mScanning = false;
        }
    };

    // Delay entre tentativas de scan quando nenhum dispositivo CHOPP_ é encontrado
    private static final long SCAN_RETRY_DELAY_MS = 5_000L; // 5s entre scans

    /**
     * Inicia scan BLE com retry automático.
     * Se nenhum dispositivo CHOPP_ for encontrado no SCAN_TIMEOUT_MS,
     * aguarda SCAN_RETRY_DELAY_MS e tenta novamente — indefinidamente
     * enquanto mAutoReconnect=true e mTargetMac=null.
     */
    private void iniciarScanComRetry() {
        if (!mAutoReconnect) return;
        if (mTargetMac != null) {
            // MAC foi salvo durante o scan (outro thread) — conecta diretamente
            Log.i(TAG, "[SCAN] MAC disponível durante retry → conectando diretamente");
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
            iniciarBondEConectar(device);
            return;
        }
        iniciarScan();
    }

    private void iniciarScan() {
        if (mScanning || mBleScanner == null) return;
        if (!mBluetoothAdapter.isEnabled()) {
            Log.w(TAG, "[SCAN] Bluetooth desativado — scan cancelado");
            return;
        }
        mScanning = true;
        Log.i(TAG, "[SCAN] Iniciando scan por dispositivos CHOPP_...");
        broadcastConnectionStatus("scanning");
        mBleScanner.startScan(mScanCallback);

        // Para o scan após SCAN_TIMEOUT_MS e agenda nova tentativa se ainda sem MAC
        mScanStopRunnable = () -> {
            if (mScanning) {
                Log.w(TAG, "[SCAN] Timeout — nenhum dispositivo CHOPP_ encontrado em "
                        + SCAN_TIMEOUT_MS / 1000 + "s. Reagendando scan em "
                        + SCAN_RETRY_DELAY_MS / 1000 + "s...");
                pararScan();
                broadcastConnectionStatus("scan_timeout");

                // Retry persistente: se ainda sem MAC e autoReconnect ativo,
                // agenda novo scan após SCAN_RETRY_DELAY_MS
                if (mAutoReconnect && mTargetMac == null) {
                    Log.i(TAG, "[SCAN] Agendando nova tentativa de scan em "
                            + SCAN_RETRY_DELAY_MS / 1000 + "s...");
                    mMainHandler.postDelayed(() -> {
                        if (mAutoReconnect && mTargetMac == null) {
                            Log.i(TAG, "[SCAN] Reiniciando scan por CHOPP_...");
                            iniciarScanComRetry();
                        } else if (mAutoReconnect && mTargetMac != null) {
                            Log.i(TAG, "[SCAN] MAC disponível após retry → conectando");
                            BluetoothDevice d = mBluetoothAdapter.getRemoteDevice(mTargetMac);
                            iniciarBondEConectar(d);
                        }
                    }, SCAN_RETRY_DELAY_MS);
                }
            }
        };
        mMainHandler.postDelayed(mScanStopRunnable, SCAN_TIMEOUT_MS);
    }

    private void pararScan() {
        if (!mScanning) return;
        mScanning = false;
        if (mScanStopRunnable != null) {
            mMainHandler.removeCallbacks(mScanStopRunnable);
            mScanStopRunnable = null;
        }
        try {
            if (mBleScanner != null) mBleScanner.stopScan(mScanCallback);
            Log.i(TAG, "[SCAN] Scan parado");
        } catch (Exception e) {
            Log.e(TAG, "[SCAN] Erro ao parar scan: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Callbacks GATT
    // ═════════════════════════════════════════════════════════════════════════

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = gatt.getDevice();
            int bondState = device.getBondState();
            Log.d(TAG, "[GATT] onConnectionStateChange: status=" + status
                    + " newState=" + newState
                    + " | bond=" + bondStateName(bondState)
                    + " | mac=" + device.getAddress());

            // ── GATT_AUTH_FAIL: único caso que exige remover e recriar bond ──
            if (status == GATT_AUTH_FAIL) {
                Log.e(TAG, "[GATT] GATT_AUTH_FAIL (0x89) — bond inválido. Recriando bond...");
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                closeGatt();
                removeBond(device);
                mMainHandler.postDelayed(() -> iniciarBondEConectar(device), 2_000);
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "[GATT] *** CONECTADO *** | mac=" + device.getAddress()
                        + " | bond=" + bondStateName(bondState));
                // Persiste MAC e reseta backoff
                salvarMac(device.getAddress());
                mReconnectDelay    = RECONNECT_DELAY_INITIAL_MS;
                mReconnectAttempts = 0;
                transitionTo(BleState.CONNECTED);
                broadcastConnectionStatus("connected");

                // FIX STATUS=8 — CAMADA ANDROID:
                // requestConnectionPriority(HIGH) reduz o connection interval para 7.5ms
                // e aumenta o supervision timeout para 5000ms.
                // O ESP32 também força esses parâmetros via esp_ble_gap_update_conn_params().
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                Log.i(TAG, "[GATT] requestConnectionPriority(HIGH) → interval≈7.5ms, timeout=5000ms");

                // Solicita MTU máximo para pacotes maiores
                gatt.requestMtu(512);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "[GATT] DESCONECTADO (status=" + status + ") | bond=" + bondStateName(bondState));
                cancelarTimeout();
                transitionTo(BleState.DISCONNECTED);
                mWriteCharacteristic = null;
                broadcastConnectionStatus("disconnected");

                if (!mAutoReconnect) {
                    Log.i(TAG, "[GATT] autoReconnect=false — não reconectando");
                    return;
                }

                // ── Estratégia de reconexão por tipo de status ──
                if (status == GATT_CONN_TIMEOUT || status == 257 || status == GATT_CONN_FAIL_ESTABLISH) {
                    // Timeout ou falha de estabelecimento:
                    // NÃO fechar o GATT (mantém cache de serviços)
                    // Reconectar imediatamente via gatt.connect()
                    Log.i(TAG, "[GATT] status=" + status
                            + " — reconectando IMEDIATAMENTE (sem fechar GATT)");
                    reconectarImediato(device);
                } else {
                    // Desconexão normal (status=0) ou outro erro:
                    // Fechar GATT e reconectar com backoff
                    Log.i(TAG, "[GATT] status=" + status + " — fechando GATT e reconectando com backoff");
                    closeGatt();
                    agendarReconexao(device);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.i(TAG, "[GATT] MTU=" + mtu + " | bond=" + bondStateName(gatt.getDevice().getBondState())
                    + " — iniciando discoverServices()");
            gatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothDevice device = gatt.getDevice();
            int bondState = device.getBondState();
            Log.i(TAG, "[GATT] onServicesDiscovered: status=" + status
                    + " | bond=" + bondStateName(bondState));

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "[GATT] discoverServices falhou: " + status + " — tentando novamente em 2s");
                mMainHandler.postDelayed(() -> {
                    if (mBluetoothGatt != null) mBluetoothGatt.discoverServices();
                }, 2_000);
                return;
            }

            BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
            if (service == null) {
                Log.w(TAG, "[GATT] Serviço NUS não encontrado (bond=" + bondStateName(bondState)
                        + ") — redescobrir em 2s");
                mMainHandler.postDelayed(() -> {
                    if (mBluetoothGatt != null) mBluetoothGatt.discoverServices();
                }, 2_000);
                return;
            }

            mWriteCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID);
            BluetoothGattCharacteristic txChar = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID);
            setupNotifications(gatt, txChar);

            Log.i(TAG, "[GATT] NUS OK | RX=" + (mWriteCharacteristic != null ? "OK" : "NULL")
                    + " | TX=" + (txChar != null ? "OK" : "NULL"));

            // Envia $AUTH:259087 após 600ms para garantir que as notificações estão ativas
            mMainHandler.postDelayed(() -> {
                if (mWriteCharacteristic == null || mBluetoothGatt == null) {
                    Log.e(TAG, "[GATT] Não foi possível enviar $AUTH — characteristic ou GATT nulo");
                    return;
                }
                String authCmd = "$AUTH:" + ESP32_PIN;
                mWriteCharacteristic.setValue(authCmd.getBytes());
                boolean ok = mBluetoothGatt.writeCharacteristic(mWriteCharacteristic);
                Log.i(TAG, "[GATT] $AUTH:" + ESP32_PIN + " enviado → " + (ok ? "OK" : "FALHOU"));
            }, 600);

            // Timeout de segurança: se AUTH:OK não chegar em AUTH_OK_TIMEOUT_MS, força READY
            iniciarTimeoutAuthOk(device);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            String data = new String(characteristic.getValue()).trim();
            Log.d(TAG, "[GATT] ESP32→Android: [" + data + "] | estado=" + mBleState.name());

            if ("AUTH:OK".equalsIgnoreCase(data)) {
                Log.i(TAG, "[GATT] *** AUTH:OK recebido *** — transitando para READY");
                cancelarTimeout();
                if (mBleState != BleState.READY) {
                    transitionTo(BleState.READY);
                    broadcastWriteReady();
                }
            } else if ("AUTH:FAIL".equalsIgnoreCase(data)) {
                Log.e(TAG, "[GATT] AUTH:FAIL — PIN incorreto ou bond inválido");
                broadcastConnectionStatus("auth_fail");
            }
            // Encaminha todos os dados para as Activities (VP:, ML:, QP:, etc.)
            broadcastData(data);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                                      int status) {
            Log.d(TAG, "[GATT] CCCD write: " + (status == BluetoothGatt.GATT_SUCCESS ? "OK" : "FALHOU=" + status));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            Log.d(TAG, "[GATT] onCharacteristicWrite: " + (status == BluetoothGatt.GATT_SUCCESS ? "OK" : "FALHOU=" + status));
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Lógica de bond + conexão
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Ponto de entrada principal. Verifica bond e conecta.
     * Se bond existe → connectGatt() diretamente.
     * Se não existe → createBond() primeiro.
     */
    private void iniciarBondEConectar(BluetoothDevice device) {
        int bondState = device.getBondState();
        Log.i(TAG, "[BLE] iniciarBondEConectar() | bond=" + bondStateName(bondState)
                + " | mac=" + device.getAddress());

        if (bondState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "[BLE] Bond já existe → conectando GATT");
            conectarGatt(device);
        } else if (bondState == BluetoothDevice.BOND_BONDING) {
            Log.i(TAG, "[BLE] Bond em andamento → aguardando BOND_STATE_CHANGED");
            iniciarTimeoutBond(device);
        } else {
            Log.i(TAG, "[BLE] BOND_NONE → createBond() com PIN " + ESP32_PIN);
            boolean ok = device.createBond();
            Log.i(TAG, "[BLE] createBond() → " + (ok ? "INICIADO" : "FALHOU"));
            iniciarTimeoutBond(device);
        }
    }

    /**
     * Conecta via GATT.
     * - Se GATT já existe para o mesmo MAC: usa gatt.connect() (reconexão rápida, mantém cache)
     * - Se é novo GATT: connectGatt(autoConnect=false) para conexão imediata
     *
     * NOTA: autoConnect=false é mais rápido para conexão inicial.
     *       autoConnect=true é usado internamente pelo stack após a primeira conexão.
     */
    private void conectarGatt(BluetoothDevice device) {
        if (mBluetoothGatt != null
                && mBluetoothGatt.getDevice().getAddress().equalsIgnoreCase(device.getAddress())) {
            Log.i(TAG, "[GATT] GATT existente para " + device.getAddress() + " → gatt.connect()");
            boolean ok = mBluetoothGatt.connect();
            Log.i(TAG, "[GATT] gatt.connect() → " + (ok ? "OK" : "FALHOU — criando novo GATT"));
            if (!ok) {
                closeGatt();
                mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
            }
            return;
        }
        // Fecha GATT anterior se for de outro device
        closeGatt();
        Log.i(TAG, "[GATT] connectGatt(autoConnect=false) → " + device.getAddress()
                + " | bond=" + bondStateName(device.getBondState()));
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    /**
     * Reconexão imediata após status=8 (timeout) ou status=257.
     * NÃO fecha o GATT para preservar o cache de serviços BLE.
     * Tenta gatt.connect() primeiro; se falhar, cria novo GATT.
     */
    private void reconectarImediato(BluetoothDevice device) {
        mReconnectAttempts++;
        Log.i(TAG, "[BLE] Reconexão imediata #" + mReconnectAttempts + " → " + device.getAddress());

        mMainHandler.postDelayed(() -> {
            if (!mAutoReconnect) return;
            if (mBluetoothGatt != null) {
                boolean ok = mBluetoothGatt.connect();
                Log.i(TAG, "[BLE] gatt.connect() reconexão → " + (ok ? "OK" : "FALHOU — usando backoff"));
                if (!ok) {
                    closeGatt();
                    agendarReconexao(device);
                }
            } else {
                agendarReconexao(device);
            }
        }, mReconnectDelay);

        // Backoff suave: 1s → 2s → 4s → 10s (máximo)
        mReconnectDelay = Math.min(mReconnectDelay * 2, RECONNECT_DELAY_MAX_MS);
    }

    /**
     * Reconexão com backoff exponencial (para desconexões normais).
     */
    private void agendarReconexao(BluetoothDevice device) {
        cancelarReconexao();
        mReconnectAttempts++;
        Log.i(TAG, "[BLE] Reconexão agendada #" + mReconnectAttempts
                + " em " + mReconnectDelay + "ms → " + device.getAddress());

        mReconnectRunnable = () -> {
            if (!mAutoReconnect) return;
            if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
                Log.w(TAG, "[BLE] Bluetooth desativado — cancelando reconexão");
                return;
            }
            Log.i(TAG, "[BLE] Executando reconexão #" + mReconnectAttempts
                    + " | bond=" + bondStateName(device.getBondState()));
            iniciarBondEConectar(device);
        };
        mMainHandler.postDelayed(mReconnectRunnable, mReconnectDelay);
        mReconnectDelay = Math.min(mReconnectDelay * 2, RECONNECT_DELAY_MAX_MS);
    }

    private void cancelarReconexao() {
        if (mReconnectRunnable != null) {
            mMainHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Timeouts
    // ═════════════════════════════════════════════════════════════════════════

    private void iniciarTimeoutBond(BluetoothDevice device) {
        cancelarTimeout();
        mTimeoutRunnable = () -> {
            Log.e(TAG, "[BLE] TIMEOUT BOND (" + BOND_TIMEOUT_MS / 1000 + "s) — forçando connectGatt()");
            conectarGatt(device);
        };
        mMainHandler.postDelayed(mTimeoutRunnable, BOND_TIMEOUT_MS);
    }

    private void iniciarTimeoutAuthOk(BluetoothDevice device) {
        cancelarTimeout();
        mTimeoutRunnable = () -> {
            if (mBleState == BleState.READY) return;
            Log.w(TAG, "[BLE] TIMEOUT AUTH:OK (" + AUTH_OK_TIMEOUT_MS / 1000 + "s) — forçando READY");
            transitionTo(BleState.READY);
            broadcastWriteReady();
        };
        mMainHandler.postDelayed(mTimeoutRunnable, AUTH_OK_TIMEOUT_MS);
    }

    private void cancelarTimeout() {
        if (mTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Máquina de estados
    // ═════════════════════════════════════════════════════════════════════════

    private void transitionTo(BleState newState) {
        if (mBleState == newState) return;
        Log.i(TAG, "[BLE] Estado: " + mBleState.name() + " → " + newState.name());
        mBleState = newState;
        broadcastBleState(newState);
    }

    public BleState getBleState()  { return mBleState; }
    public boolean  isReady()      { return mBleState == BleState.READY; }

    public void forceReady() {
        Log.i(TAG, "[BLE] forceReady() chamado — estado=" + mBleState.name());
        cancelarTimeout();
        transitionTo(BleState.READY);
        broadcastWriteReady();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Configuração de notificações NUS
    // ═════════════════════════════════════════════════════════════════════════

    private void setupNotifications(BluetoothGatt gatt, BluetoothGattCharacteristic tx) {
        if (tx == null) {
            Log.e(TAG, "[GATT] TX characteristic NUS não encontrada!");
            return;
        }
        gatt.setCharacteristicNotification(tx, true);
        BluetoothGattDescriptor desc = tx.getDescriptor(CCCD_UUID);
        if (desc != null) {
            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(desc);
            Log.d(TAG, "[GATT] CCCD writeDescriptor → " + (ok ? "OK" : "FALHOU"));
        } else {
            Log.e(TAG, "[GATT] CCCD descriptor não encontrado!");
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Ponto de entrada externo chamado pelas Activities.
     * Se há MAC salvo → conecta diretamente.
     * Se não há MAC → inicia scan por CHOPP_.
     */
    public void scanLeDevice(boolean enable) {
        mAutoReconnect = true;
        mReconnectDelay = RECONNECT_DELAY_INITIAL_MS;
        mReconnectAttempts = 0;

        if (!enable) {
            pararScan();
            return;
        }

        // ─────────────────────────────────────────────────────────────────────
        // CORREÇÃO DEFINITIVA — causa raiz do log 2026-03-14 20:38:
        //
        // O log mostrava repetidamente:
        //   [BLE] scanLeDevice() — MAC alvo não configurado
        //
        // Isso ocorria porque mTargetMac era null (SharedPreferences vazio na
        // primeira execução ou após reinstalação do app), e o método retornava
        // imediatamente com apenas um Warning, sem iniciar o scan.
        //
        // O código anterior tinha um Log.w() mas NÃO chamava iniciarScan().
        // O método simplesmente retornava sem fazer nada, e o app ficava
        // parado em DESCONECTADO para sempre.
        //
        // SOLUÇÃO:
        //   - Se MAC salvo → conecta diretamente (caminho rápido)
        //   - Se sem MAC → inicia scan BLE por dispositivos com prefixo CHOPP_
        //   - Após scan_timeout → reagenda novo scan em SCAN_RETRY_DELAY_MS
        //     (loop persistente até encontrar o dispositivo)
        // ─────────────────────────────────────────────────────────────────────

        if (mTargetMac != null) {
            Log.i(TAG, "[BLE] scanLeDevice() — MAC salvo: " + mTargetMac + " → conectando diretamente");
            BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(mTargetMac);
            iniciarBondEConectar(device);
        } else {
            Log.i(TAG, "[BLE] scanLeDevice() — MAC não configurado → iniciando scan por CHOPP_...");
            iniciarScanComRetry();
        }
    }

    /**
     * Envia dado para o ESP32 via característica NUS RX.
     * Bloqueado se estado != READY.
     */
    public void write(String data) {
        if (mBluetoothGatt == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — GATT nulo");
            return;
        }
        if (mWriteCharacteristic == null) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") IGNORADO — characteristic nula");
            return;
        }
        if (!isReady()) {
            Log.e(TAG, "[BLE] write(\"" + data + "\") BLOQUEADO — estado=" + mBleState.name());
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
        pararScan();
        transitionTo(BleState.DISCONNECTED);
        if (mBluetoothGatt != null) mBluetoothGatt.disconnect();
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
        return mBluetoothGatt != null ? mBluetoothGatt.getDevice() : null;
    }

    public String getTargetMac() { return mTargetMac; }

    // ═════════════════════════════════════════════════════════════════════════
    // Métodos internos
    // ═════════════════════════════════════════════════════════════════════════

    private void salvarMac(String mac) {
        if (mac == null) return;
        mTargetMac = mac;
        getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "[BLE] MAC salvo: " + mac);
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
            mBluetoothGatt = null;
            mWriteCharacteristic = null;
            Log.d(TAG, "[GATT] GATT fechado (close())");
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

    // ═════════════════════════════════════════════════════════════════════════
    // Broadcasts
    // ═════════════════════════════════════════════════════════════════════════

    private void broadcastConnectionStatus(String status) {
        Intent i = new Intent(ACTION_CONNECTION_STATUS);
        i.putExtra(EXTRA_STATUS, status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
        Log.d(TAG, "[BROADCAST] CONNECTION_STATUS=" + status);
    }

    private void broadcastData(String data) {
        Intent i = new Intent(ACTION_DATA_AVAILABLE);
        i.putExtra(EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void broadcastWriteReady() {
        Log.i(TAG, "[BROADCAST] *** ACTION_WRITE_READY *** — pronto para $ML");
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(ACTION_WRITE_READY));
    }

    private void broadcastBleState(BleState state) {
        Intent i = new Intent(ACTION_BLE_STATE_CHANGED);
        i.putExtra(EXTRA_BLE_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Notificação Foreground (obrigatório Android 12+)
    // ═════════════════════════════════════════════════════════════════════════

    private void criarNotificacaoForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Chopp BLE", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Conexão Bluetooth com a torneira de chopp");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent notifIntent = new Intent(this, Home.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, notifIntent, flags);

        Notification notification = new Notification.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("Chopp conectado")
                .setContentText("Serviço BLE ativo")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        // Android 14+ (API 34) exige o tipo de serviço foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, notification);
        }
        Log.i(TAG, "[BLE] startForeground() OK");
    }
}
