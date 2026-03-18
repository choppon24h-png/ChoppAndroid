package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ConnectionManager — Máquina de estados BLE Industrial v2.3
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESTADOS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   DISCONNECTED → SCANNING → CONNECTING → CONNECTED → READY
 *        ↑                                                 │
 *        └─────────────────────────────────────────────────┘
 *                         (reconexão automática)
 *
 *   DISCONNECTED  — sem conexão BLE ativa
 *   SCANNING      — scan BLE ativo procurando CHOPP_*
 *   CONNECTING    — connectGatt() chamado, aguardando STATE_CONNECTED
 *   CONNECTED     — GATT conectado, aguardando AUTH:OK
 *   READY         — AUTH:OK recebido, pronto para enviar comandos
 *
 * ═══════════════════════════════════════════════════════════════════
 * REGRAS CRÍTICAS (spec BLE Industrial v2.3)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   - SEMPRE usar autoConnect=true para reconexão após primeira conexão
 *   - NUNCA conectar direto do callback do scan
 *   - Aguardar SCAN_TO_CONNECT_DELAY_MS (800ms) após scan antes de conectar
 *   - Reconectar sempre pelo MAC salvo (não pelo scan)
 *   - NUNCA chamar gatt.close() em status=8 (GATT_CONN_TIMEOUT)
 *   - Usar apenas disconnect() em status=8
 *   - Retry exponencial: 1s → 2s → 5s → 10s (loop)
 *   - Máximo de retries: ilimitado (operação 24h)
 *
 * ═══════════════════════════════════════════════════════════════════
 * HEARTBEAT PING/PONG
 * ═══════════════════════════════════════════════════════════════════
 *
 *   - PING enviado a cada 3s quando READY
 *   - 3 falhas consecutivas → força reconexão
 *   - Qualquer RX do ESP32 reseta o contador de falhas
 */
public class ConnectionManager {

    private static final String TAG = "BLE_CONN_MGR";

    // ── Estados ───────────────────────────────────────────────────────────────
    public enum State {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        READY
    }

    // ── Delay pós-scan antes de conectar (spec: 800ms) ────────────────────────
    public static final long SCAN_TO_CONNECT_DELAY_MS = 800L;

    // ── Retry exponencial: 1s → 2s → 5s → 10s (loop) ────────────────────────
    private static final long[] RETRY_DELAYS_MS = { 1_000L, 2_000L, 5_000L, 10_000L };

    // ── Heartbeat PING/PONG ───────────────────────────────────────────────────
    private static final long PING_INTERVAL_MS  = 3_000L;
    private static final int  PING_MAX_FAILURES = 3;

    // ── Estado interno ────────────────────────────────────────────────────────
    private State   mState         = State.DISCONNECTED;
    private boolean mAutoReconnect = true;
    private String  mTargetMac     = null;
    private int     mRetryIndex    = 0;
    private int     mRetryCount    = 0;
    private int     mPingFailures  = 0;

    private final Handler mHandler              = new Handler(Looper.getMainLooper());
    private Runnable      mReconnectRunnable    = null;
    private Runnable      mScanConnectRunnable  = null;
    private Runnable      mPingRunnable         = null;

    // ── Interface de escrita BLE ──────────────────────────────────────────────
    public interface BleWriter {
        boolean write(String data);
    }

    private BleWriter mWriter;

    public void setWriter(BleWriter writer) {
        this.mWriter = writer;
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        /** Estado transitou para newState */
        void onStateChanged(State newState, State oldState);
        /** Solicitar envio de PING via BLE */
        void onPingRequested();
        /** 3 PINGs consecutivos sem resposta — deve reconectar */
        void onHeartbeatFailed();
        /**
         * Solicitar conexão GATT.
         * @param mac         Endereço MAC do dispositivo
         * @param autoConnect true para reconexão automática (spec v2.3),
         *                    false para primeira conexão (mais rápido)
         */
        void onConnectRequested(String mac, boolean autoConnect);
    }

    private final Callback mCallback;

    // ── Construtor ────────────────────────────────────────────────────────────
    public ConnectionManager(Callback callback) {
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — leitura de estado
    // ═════════════════════════════════════════════════════════════════════════

    /** Retorna o estado atual. */
    public synchronized State getState() {
        return mState;
    }

    /** Retorna true se o BLE está READY para enviar comandos. */
    public synchronized boolean isReady() {
        return mState == State.READY;
    }

    /** Retorna true se está conectado (CONNECTED ou READY). */
    public synchronized boolean isConnected() {
        return mState == State.CONNECTED || mState == State.READY;
    }

    /** Retorna o MAC alvo salvo. */
    public synchronized String getTargetMac() {
        return mTargetMac;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — configuração
    // ═════════════════════════════════════════════════════════════════════════

    /** Define o MAC alvo para reconexão automática. */
    public synchronized void setTargetMac(String mac) {
        if (mac != null && !mac.equals(mTargetMac)) {
            Log.i(TAG, "[CONN] MAC alvo definido: " + mac);
            mTargetMac = mac;
        }
    }

    /** Habilita ou desabilita reconexão automática. */
    public synchronized void setAutoReconnect(boolean enabled) {
        mAutoReconnect = enabled;
        Log.i(TAG, "[CONN] autoReconnect=" + enabled);
        if (!enabled) {
            cancelarReconexao();
            pararHeartbeat();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — eventos de scan (chamados pelo BluetoothService)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Notifica que o scan BLE foi iniciado.
     * Transiciona para SCANNING.
     */
    public synchronized void onScanStarted() {
        Log.i(TAG, "[CONN] Scan iniciado → SCANNING");
        transitionTo(State.SCANNING);
    }

    /**
     * Notifica que um dispositivo CHOPP_* foi encontrado no scan.
     * Armazena o MAC e agenda conexão após SCAN_TO_CONNECT_DELAY_MS.
     *
     * REGRA CRÍTICA: NÃO conectar dentro do callback do scan.
     * Armazenar device e conectar após delay de 800ms.
     *
     * @param mac  Endereço MAC do dispositivo encontrado
     */
    public synchronized void onDeviceFoundInScan(String mac) {
        if (mac == null) return;
        Log.i(TAG, "[CONN] Dispositivo encontrado no scan: " + mac
                + " | Agendando conexão em " + SCAN_TO_CONNECT_DELAY_MS + "ms");
        mTargetMac = mac;

        // Cancela qualquer conexão pós-scan pendente
        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
        }

        // Agenda conexão após delay (NÃO conectar direto do scan)
        mScanConnectRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (mTargetMac == null) return;
                Log.i(TAG, "[CONN] Delay pós-scan concluído → conectando " + mTargetMac
                        + " (autoConnect=false — primeira conexão)");
                transitionTo(State.CONNECTING);
                // Primeira conexão: autoConnect=false (mais rápido)
                if (mCallback != null) mCallback.onConnectRequested(mTargetMac, false);
            }
        };
        mHandler.postDelayed(mScanConnectRunnable, SCAN_TO_CONNECT_DELAY_MS);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública — eventos GATT (chamados pelo BluetoothService)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Notifica que a conexão GATT foi estabelecida (STATE_CONNECTED).
     * Transiciona para CONNECTED.
     */
    public synchronized void onGattConnected() {
        Log.i(TAG, "[CONN] GATT conectado → CONNECTED");
        cancelarReconexao();
        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
            mScanConnectRunnable = null;
        }
        mRetryIndex = 0;
        mRetryCount = 0;
        transitionTo(State.CONNECTED);
    }

    /**
     * Notifica que AUTH:OK foi recebido — transiciona para READY.
     * Inicia heartbeat PING/PONG automaticamente.
     */
    public synchronized void onAuthOk() {
        Log.i(TAG, "[CONN] AUTH:OK → READY");
        transitionTo(State.READY);
    }

    /**
     * Notifica que qualquer dado foi recebido do ESP32.
     * Reseta o contador de falhas de PING.
     */
    public synchronized void onDataReceived() {
        if (mPingFailures > 0) {
            Log.d(TAG, "[CONN] RX recebido — resetando ping failures (" + mPingFailures + " → 0)");
            mPingFailures = 0;
        }
    }

    /**
     * Notifica que PONG foi recebido.
     * Reseta o contador de falhas de PING.
     */
    public synchronized void onPongReceived() {
        Log.d(TAG, "[CONN] PONG recebido — heartbeat OK");
        mPingFailures = 0;
    }

    /**
     * Notifica que o GATT foi desconectado.
     *
     * REGRA STATUS=8 (Connection Supervision Timeout):
     *   - NÃO chamar gatt.close() em status=8
     *   - Apenas disconnect() e aguardar reconexão automática
     *
     * @param status     Código de status GATT (8=timeout, 257=connect precoce)
     * @param closeGatt  true se deve fechar o GATT antes de reconectar
     */
    public synchronized void onGattDisconnected(int status, boolean closeGatt) {
        Log.w(TAG, "[CONN] GATT desconectado | status=" + status
                + " | closeGatt=" + closeGatt
                + " | estado=" + mState);

        pararHeartbeat();

        if (mState == State.DISCONNECTED) {
            Log.d(TAG, "[CONN] Já DISCONNECTED — ignorando");
            return;
        }

        transitionTo(State.DISCONNECTED);

        if (mAutoReconnect && mTargetMac != null) {
            agendarReconexao();
        }
    }

    /**
     * Para tudo — chamado no onDestroy() do BluetoothService.
     */
    public synchronized void destroy() {
        Log.i(TAG, "[CONN] destroy()");
        mAutoReconnect = false;
        cancelarReconexao();
        pararHeartbeat();
        if (mScanConnectRunnable != null) {
            mHandler.removeCallbacks(mScanConnectRunnable);
            mScanConnectRunnable = null;
        }
        mState = State.DISCONNECTED;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reconexão automática com retry exponencial
    // ═════════════════════════════════════════════════════════════════════════

    private void agendarReconexao() {
        cancelarReconexao();
        long delay = RETRY_DELAYS_MS[Math.min(mRetryIndex, RETRY_DELAYS_MS.length - 1)];
        mRetryCount++;
        mRetryIndex = Math.min(mRetryIndex + 1, RETRY_DELAYS_MS.length - 1);

        Log.i(TAG, "[CONN] Reconexão #" + mRetryCount
                + " agendada em " + delay + "ms → " + mTargetMac);

        mReconnectRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (!mAutoReconnect || mTargetMac == null) return;
                if (mState != State.DISCONNECTED) {
                    Log.d(TAG, "[CONN] Reconexão cancelada — estado=" + mState);
                    return;
                }
                Log.i(TAG, "[CONN] Executando reconexão #" + mRetryCount
                        + " → " + mTargetMac + " (autoConnect=true)");
                transitionTo(State.CONNECTING);
                // Reconexão: autoConnect=true (spec BLE Industrial v2.3)
                if (mCallback != null) mCallback.onConnectRequested(mTargetMac, true);
            }
        };
        mHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void cancelarReconexao() {
        if (mReconnectRunnable != null) {
            mHandler.removeCallbacks(mReconnectRunnable);
            mReconnectRunnable = null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Heartbeat PING/PONG
    // ═════════════════════════════════════════════════════════════════════════

    private void iniciarHeartbeat() {
        pararHeartbeat();
        mPingFailures = 0;
        agendarProximoPing();
        Log.d(TAG, "[CONN] Heartbeat iniciado (intervalo=" + PING_INTERVAL_MS + "ms)");
    }

    private void agendarProximoPing() {
        mPingRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (mState != State.READY) return;

                mPingFailures++;
                Log.d(TAG, "[CONN] PING enviado | falhas=" + mPingFailures
                        + "/" + PING_MAX_FAILURES);
                if (mCallback != null) mCallback.onPingRequested();

                if (mPingFailures >= PING_MAX_FAILURES) {
                    Log.e(TAG, "[CONN] " + PING_MAX_FAILURES
                            + " PINGs sem resposta → forçando reconexão");
                    pararHeartbeat();
                    if (mCallback != null) mCallback.onHeartbeatFailed();
                    return;
                }

                agendarProximoPing();
            }
        };
        mHandler.postDelayed(mPingRunnable, PING_INTERVAL_MS);
    }

    private void pararHeartbeat() {
        if (mPingRunnable != null) {
            mHandler.removeCallbacks(mPingRunnable);
            mPingRunnable = null;
        }
        mPingFailures = 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Transição de estado
    // ═════════════════════════════════════════════════════════════════════════

    private void transitionTo(State newState) {
        if (mState == newState) return;
        State old = mState;
        mState = newState;
        Log.i(TAG, "[CONN] " + old.name() + " → " + newState.name());

        // Inicia heartbeat ao entrar em READY; para ao sair de READY
        if (newState == State.READY) {
            iniciarHeartbeat();
        } else if (old == State.READY) {
            pararHeartbeat();
        }

        if (mCallback != null) mCallback.onStateChanged(newState, old);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Diagnóstico
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public String toString() {
        return "ConnectionManager{state=" + mState
                + ", mac=" + mTargetMac
                + ", retries=" + mRetryCount
                + ", pingFail=" + mPingFailures + "}";
    }
}
