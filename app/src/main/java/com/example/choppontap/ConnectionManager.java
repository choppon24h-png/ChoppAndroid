package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ConnectionManager — Gerenciador de estado de conexão BLE Industrial v2.3.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESTADOS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   DISCONNECTED → CONNECTING → CONNECTED → READY
 *        ↑                                     │
 *        └─────────────────────────────────────┘
 *                    (reconexão automática)
 *
 *   DISCONNECTED  — sem conexão BLE ativa
 *   CONNECTING    — connectGatt() chamado, aguardando STATE_CONNECTED
 *   CONNECTED     — GATT conectado, aguardando AUTH:OK
 *   READY         — AUTH:OK recebido, pronto para enviar comandos
 *
 * ═══════════════════════════════════════════════════════════════════
 * REGRAS DE RECONEXÃO (spec BLE Industrial)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   - SEMPRE usar autoConnect=true para reconexão após primeira conexão
 *   - NUNCA chamar gatt.close() em status=8 (GATT_CONN_TIMEOUT)
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
        CONNECTING,
        CONNECTED,
        READY
    }

    // ── Retry exponencial: 1s → 2s → 5s → 10s ────────────────────────────────
    private static final long[] RETRY_DELAYS_MS = { 1_000L, 2_000L, 5_000L, 10_000L };
    private int  mRetryIndex    = 0;
    private int  mRetryCount    = 0;

    // ── Heartbeat PING/PONG ───────────────────────────────────────────────────
    private static final long PING_INTERVAL_MS      = 3_000L;
    private static final int  PING_MAX_FAILURES      = 3;
    private int               mPingFailures          = 0;
    private Runnable          mPingRunnable          = null;

    // ── Estado interno ────────────────────────────────────────────────────────
    private State   mState          = State.DISCONNECTED;
    private boolean mAutoReconnect  = true;
    private String  mTargetMac      = null;

    private final Handler  mHandler = new Handler(Looper.getMainLooper());
    private Runnable       mReconnectRunnable = null;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        /** Chamado quando o estado muda */
        void onStateChanged(State newState, State oldState);
        /** Chamado para solicitar envio de PING via BLE */
        void onPingRequested();
        /** Chamado quando 3 PINGs consecutivos falharam — deve reconectar */
        void onHeartbeatFailed();
        /** Chamado para solicitar conexão GATT com o device */
        void onConnectRequested(String mac);
    }

    private final Callback mCallback;

    // ── Interface de escrita BLE (injetada pelo BluetoothService) ─────────────
    public interface BleWriter {
        boolean write(String data);
    }

    private BleWriter mWriter;

    // ── Construtor ────────────────────────────────────────────────────────────
    public ConnectionManager(Callback callback) {
        this.mCallback = callback;
    }

    public void setWriter(BleWriter writer) {
        this.mWriter = writer;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /** Retorna o estado atual. */
    public synchronized State getState() {
        return mState;
    }

    /** Retorna true se o BLE está READY para enviar comandos. */
    public synchronized boolean isReady() {
        return mState == State.READY;
    }

    /** Define o MAC alvo para reconexão automática. */
    public synchronized void setTargetMac(String mac) {
        this.mTargetMac = mac;
        Log.i(TAG, "[CONN] MAC alvo definido: " + mac);
    }

    /** Retorna o MAC alvo. */
    public synchronized String getTargetMac() {
        return mTargetMac;
    }

    /**
     * Notifica que a conexão GATT foi estabelecida (STATE_CONNECTED).
     * Transiciona para CONNECTED.
     */
    public synchronized void onGattConnected() {
        Log.i(TAG, "[CONN] GATT conectado → CONNECTED");
        mRetryIndex = 0;
        mRetryCount = 0;
        transitionTo(State.CONNECTED);
    }

    /**
     * Notifica que AUTH:OK foi recebido.
     * Transiciona para READY e inicia heartbeat.
     */
    public synchronized void onAuthOk() {
        Log.i(TAG, "[CONN] AUTH:OK → READY");
        transitionTo(State.READY);
        iniciarHeartbeat();
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
     * Notifica que a resposta PONG foi recebida.
     * Reseta o contador de falhas de PING.
     */
    public synchronized void onPongReceived() {
        Log.d(TAG, "[CONN] PONG recebido — heartbeat OK");
        mPingFailures = 0;
    }

    /**
     * Notifica que o BLE foi desconectado (STATE_DISCONNECTED).
     * Para heartbeat e agenda reconexão se autoReconnect=true.
     *
     * @param status  Código de status GATT (8=timeout, 0x89=auth fail, etc.)
     * @param closeGatt  true se deve chamar gatt.close() antes de reconectar
     */
    public synchronized void onGattDisconnected(int status, boolean closeGatt) {
        Log.w(TAG, "[CONN] GATT desconectado | status=" + status
                + " | estado anterior=" + mState);
        pararHeartbeat();
        transitionTo(State.DISCONNECTED);

        if (mAutoReconnect && mTargetMac != null) {
            agendarReconexao();
        }
    }

    /**
     * Habilita ou desabilita reconexão automática.
     */
    public synchronized void setAutoReconnect(boolean enabled) {
        mAutoReconnect = enabled;
        if (!enabled) {
            cancelarReconexao();
            pararHeartbeat();
        }
    }

    /**
     * Para tudo — chamado no onDestroy() do BluetoothService.
     */
    public synchronized void destroy() {
        mAutoReconnect = false;
        cancelarReconexao();
        pararHeartbeat();
        mState = State.DISCONNECTED;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reconexão
    // ═════════════════════════════════════════════════════════════════════════

    private void agendarReconexao() {
        cancelarReconexao();
        long delay = RETRY_DELAYS_MS[Math.min(mRetryIndex, RETRY_DELAYS_MS.length - 1)];
        mRetryCount++;
        mRetryIndex = Math.min(mRetryIndex + 1, RETRY_DELAYS_MS.length - 1);

        Log.i(TAG, "[CONN] Reconexão #" + mRetryCount + " agendada em " + delay + "ms → " + mTargetMac);

        mReconnectRunnable = () -> {
            synchronized (ConnectionManager.this) {
                if (!mAutoReconnect || mTargetMac == null) return;
                if (mState != State.DISCONNECTED) {
                    Log.d(TAG, "[CONN] Reconexão cancelada — estado=" + mState);
                    return;
                }
                Log.i(TAG, "[CONN] Executando reconexão #" + mRetryCount + " → " + mTargetMac);
                transitionTo(State.CONNECTING);
                if (mCallback != null) mCallback.onConnectRequested(mTargetMac);
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

                // Incrementa falha antes de enviar — será resetado pelo PONG/onDataReceived
                mPingFailures++;
                Log.d(TAG, "[CONN] PING enviado | falhas=" + mPingFailures + "/" + PING_MAX_FAILURES);

                if (mCallback != null) mCallback.onPingRequested();

                if (mPingFailures >= PING_MAX_FAILURES) {
                    Log.e(TAG, "[CONN] " + PING_MAX_FAILURES + " PINGs sem resposta → forçando reconexão");
                    pararHeartbeat();
                    if (mCallback != null) mCallback.onHeartbeatFailed();
                    return;
                }

                // Agenda próximo PING
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
