package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * CommandQueue — Fila FIFO de comandos BLE Industrial v2.3.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESPECIFICAÇÃO (conforme documento BLE Industrial)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   Capacidade máxima: 10 comandos
 *   Execução:          sequencial (1 comando ativo por vez)
 *   ACK timeout:       2s  (firmware garante ACK em <100ms)
 *   DONE timeout:      15s (operação máxima no firmware)
 *   Retry automático:  máx 2x por comando
 *
 * Fluxo por comando:
 *   enqueue(cmd) → envia → aguarda ACK (2s) → aguarda DONE (15s) → remove → próximo
 *
 * Formato de comando SERVE (protocolo v2.3):
 *   SERVE|<ml>|ID=<cmdId>|SESSION=<sessionId>
 *
 * Deduplicação:
 *   O firmware ESP32 usa CMD_ID para detectar duplicatas.
 *   Em reenvios, o mesmo CMD_ID é mantido — o ESP32 responde ACK
 *   sem executar novamente (idempotência).
 *
 * ═══════════════════════════════════════════════════════════════════
 * CALLBACKS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   onSend(cmd)          — comando enviado via BLE
 *   onAck(cmd)           — ACK recebido do ESP32
 *   onDone(cmd)          — DONE recebido, ml_real disponível em cmd.mlReal
 *   onError(cmd, reason) — timeout ou erro irrecuperável
 *   onQueueFull()        — tentativa de enqueue com fila cheia (máx 10)
 */
public class CommandQueue {

    private static final String TAG = "BLE_CMD_QUEUE";

    // ── Limites e timeouts ────────────────────────────────────────────────────
    public  static final int  MAX_QUEUE_SIZE  = 10;
    private static final long ACK_TIMEOUT_MS  = 2_000L;
    private static final long DONE_TIMEOUT_MS = 15_000L;
    public  static final int  MAX_RETRIES     = 2;

    // ── Estado interno ────────────────────────────────────────────────────────
    private final Queue<BleCommand> mQueue  = new LinkedList<>();
    private BleCommand              mActive = null;
    private boolean                 mPaused = false;

    private final Handler mHandler              = new Handler(Looper.getMainLooper());
    private Runnable      mAckTimeoutRunnable   = null;
    private Runnable      mDoneTimeoutRunnable  = null;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        void onSend(BleCommand cmd);
        void onAck(BleCommand cmd);
        void onDone(BleCommand cmd);
        void onError(BleCommand cmd, String reason);
        void onQueueFull();
    }

    private final Callback mCallback;

    // ── Interface de escrita BLE ──────────────────────────────────────────────
    public interface BleWriter {
        boolean write(String data);
    }

    private final BleWriter mWriter;

    // ── Construtor ────────────────────────────────────────────────────────────
    public CommandQueue(BleWriter writer, Callback callback) {
        this.mWriter   = writer;
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Enfileira um comando SERVE com IDs únicos gerados automaticamente.
     *
     * Formato enviado ao ESP32:
     *   SERVE|<ml>|ID=<cmdId>|SESSION=<sessionId>
     *
     * @param volumeMl  Volume em ml a ser liberado
     * @param sessionId SESSION_ID da venda (do SessionManager)
     * @return O BleCommand criado (para rastreamento pelo chamador), ou null se fila cheia
     */
    public synchronized BleCommand enqueueServe(int volumeMl, String sessionId) {
        if (mQueue.size() >= MAX_QUEUE_SIZE) {
            Log.e(TAG, "[QUEUE] Fila cheia (" + MAX_QUEUE_SIZE + ") — rejeitando SERVE vol=" + volumeMl);
            if (mCallback != null) mCallback.onQueueFull();
            return null;
        }

        String cmdId = gerarCmdId();
        BleCommand cmd = new BleCommand(BleCommand.Type.SERVE, cmdId, sessionId, volumeMl);
        mQueue.add(cmd);
        Log.i(TAG, "[QUEUE] enqueue → " + cmd + " | fila=" + mQueue.size());
        processQueue();
        return cmd;
    }

    /**
     * Enfileira um comando PING para heartbeat.
     */
    public synchronized void enqueuePing() {
        if (mActive != null && mActive.type == BleCommand.Type.PING) return; // já tem PING ativo
        String cmdId = gerarCmdId().substring(0, 4);
        BleCommand cmd = new BleCommand(BleCommand.Type.PING, cmdId, "", 0);
        mQueue.add(cmd);
        processQueue();
    }

    /**
     * Processa resposta BLE recebida do ESP32.
     * Deve ser chamado pelo BluetoothService em onCharacteristicChanged().
     */
    public synchronized void onBleResponse(BleParser.ParsedMessage msg) {
        if (msg == null) return;

        Log.d(TAG, "[QUEUE] resposta: " + msg + " | ativo=" + mActive);

        switch (msg.type) {
            case ACK:
                handleAck(msg.commandId);
                break;
            case DONE:
                handleDone(msg.commandId, msg.mlReal);
                break;
            case DUPLICATE:
                handleDuplicate();
                break;
            case ERROR_BUSY:
                handleErrorBusy();
                break;
            case ERROR_WATCHDOG:
                handleErrorWatchdog();
                break;
            case PONG:
                handlePong();
                break;
            case STATUS_READY:
                Log.i(TAG, "[QUEUE] STATUS:READY recebido — BLE pronto");
                break;
            case STATUS_BUSY:
                Log.w(TAG, "[QUEUE] STATUS:BUSY recebido — ESP32 ocupado");
                break;
            default:
                break;
        }
    }

    /**
     * Chamado quando BLE desconecta.
     * Pausa a fila — o comando ativo é preservado para reenvio após reconexão.
     */
    public synchronized void onBleDisconnected() {
        Log.w(TAG, "[QUEUE] BLE desconectado — pausando | ativo=" + mActive);
        mPaused = true;
        cancelAllTimeouts();
        if (mActive != null && mActive.state == BleCommand.State.SENT) {
            mActive.state = BleCommand.State.QUEUED;
            Log.i(TAG, "[QUEUE] Comando ativo volta para QUEUED (mesmo ID para deduplicação)");
        }
    }

    /**
     * Chamado quando BLE reconecta e está READY.
     * Retoma a fila — reenvio do comando ativo com mesmo CMD_ID.
     */
    public synchronized void onBleReady() {
        Log.i(TAG, "[QUEUE] BLE READY — retomando | ativo=" + mActive + " | fila=" + mQueue.size());
        mPaused = false;
        processQueue();
    }

    /** Retorna o comando SERVE ativo (se houver). */
    public synchronized BleCommand getActiveCommand() {
        return mActive;
    }

    /** Retorna o tamanho atual da fila (sem contar o ativo). */
    public synchronized int size() {
        return mQueue.size();
    }

    /**
     * Limpa toda a fila e cancela o comando ativo.
     * Usar apenas em reset de emergência.
     */
    public synchronized void reset() {
        Log.w(TAG, "[QUEUE] reset() — limpando fila e cancelando ativo");
        cancelAllTimeouts();
        mQueue.clear();
        mActive = null;
        mPaused = false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Handlers de resposta
    // ═════════════════════════════════════════════════════════════════════════

    private void handleAck(String ackId) {
        if (mActive == null) {
            Log.w(TAG, "[QUEUE] ACK recebido sem comando ativo");
            return;
        }
        boolean idMatch = (ackId == null || ackId.isEmpty()
                || mActive.commandId.equalsIgnoreCase(ackId));
        if (!idMatch) {
            Log.w(TAG, "[QUEUE] ACK id=" + ackId + " não bate com ativo=" + mActive.commandId);
            return;
        }
        Log.i(TAG, "[QUEUE] ACK → " + mActive.commandId);
        cancelAckTimeout();
        mActive.state = BleCommand.State.ACKED;
        if (mCallback != null) mCallback.onAck(mActive);
        iniciarDoneTimeout();
    }

    private void handleDone(String doneId, int mlReal) {
        if (mActive == null) {
            Log.w(TAG, "[QUEUE] DONE recebido sem comando ativo — ignorando");
            return;
        }
        if (doneId != null && !doneId.isEmpty()
                && !doneId.equalsIgnoreCase(mActive.commandId)) {
            Log.w(TAG, "[QUEUE] DONE id=" + doneId + " não bate com ativo=" + mActive.commandId);
            return;
        }
        Log.i(TAG, "[QUEUE] DONE → " + mActive.commandId + " | ml_real=" + mlReal);
        cancelAllTimeouts();
        mActive.state  = BleCommand.State.DONE;
        mActive.mlReal = mlReal;
        if (mCallback != null) mCallback.onDone(mActive);
        mActive = null;
        processQueue();
    }

    private void handleDuplicate() {
        Log.w(TAG, "[QUEUE] DUPLICATE — firmware já executou este comando");
        if (mActive != null) {
            mActive.state = BleCommand.State.DONE;
            cancelAllTimeouts();
            if (mCallback != null) mCallback.onDone(mActive);
            mActive = null;
            processQueue();
        }
    }

    private void handleErrorBusy() {
        Log.w(TAG, "[QUEUE] ERROR:BUSY — ESP32 ocupado, aguardando 2s para reenvio");
        if (mActive != null && mActive.canRetry()) {
            mActive.retryCount++;
            mActive.state = BleCommand.State.QUEUED;
            cancelAllTimeouts();
            mHandler.postDelayed(this::processQueue, 2_000L);
        } else if (mActive != null) {
            falharComando(mActive, "ERROR:BUSY — máximo de retries atingido");
        }
    }

    private void handleErrorWatchdog() {
        Log.e(TAG, "[QUEUE] ERROR:WATCHDOG recebido do ESP32");
        if (mActive != null) {
            falharComando(mActive, "ERROR:WATCHDOG");
        }
    }

    private void handlePong() {
        Log.d(TAG, "[QUEUE] PONG — removendo PING ativo");
        if (mActive != null && mActive.type == BleCommand.Type.PING) {
            cancelAllTimeouts();
            mActive.state = BleCommand.State.DONE;
            mActive = null;
            processQueue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Processamento interno
    // ═════════════════════════════════════════════════════════════════════════

    private void processQueue() {
        if (mPaused) {
            Log.d(TAG, "[QUEUE] processQueue() — PAUSADO");
            return;
        }
        if (mActive != null) {
            Log.d(TAG, "[QUEUE] processQueue() — aguardando " + mActive.commandId);
            return;
        }
        if (mQueue.isEmpty()) {
            Log.d(TAG, "[QUEUE] processQueue() — fila vazia");
            return;
        }
        mActive = mQueue.poll();
        enviarComandoAtivo();
    }

    private void enviarComandoAtivo() {
        if (mActive == null) return;

        String bleStr = mActive.toBleString();
        Log.i(TAG, "[QUEUE] SEND " + mActive.type.name() + " " + mActive.commandId
                + " | cmd=[" + bleStr + "]");

        boolean ok = mWriter.write(bleStr);
        if (ok) {
            mActive.state = BleCommand.State.SENT;
            if (mCallback != null) mCallback.onSend(mActive);
            long ackTimeout = (mActive.type == BleCommand.Type.PING) ? 3_000L : ACK_TIMEOUT_MS;
            iniciarAckTimeout(ackTimeout);
        } else {
            Log.e(TAG, "[QUEUE] write() falhou para " + mActive.commandId);
            mActive.retryCount++;
            if (mActive.canRetry()) {
                mActive.state = BleCommand.State.QUEUED;
                mQueue.add(mActive);
                mActive = null;
                mHandler.postDelayed(this::processQueue, 1_000L);
            } else {
                falharComando(mActive, "write() falhou após " + MAX_RETRIES + " tentativas");
            }
        }
    }

    private void falharComando(BleCommand cmd, String reason) {
        Log.e(TAG, "[QUEUE] ERROR → " + cmd.commandId + " | motivo=" + reason);
        cancelAllTimeouts();
        cmd.state        = BleCommand.State.ERROR;
        cmd.errorMessage = reason;
        if (mCallback != null) mCallback.onError(cmd, reason);
        mActive = null;
        mQueue.clear();
    }

    // ── Timeouts ──────────────────────────────────────────────────────────────

    private void iniciarAckTimeout(long ms) {
        cancelAckTimeout();
        mAckTimeoutRunnable = () -> {
            synchronized (CommandQueue.this) {
                if (mActive == null || mActive.state != BleCommand.State.SENT) return;
                Log.e(TAG, "[QUEUE] ACK TIMEOUT (" + ms + "ms) para " + mActive.commandId
                        + " | retry=" + mActive.retryCount + "/" + MAX_RETRIES);
                if (mActive.canRetry()) {
                    mActive.retryCount++;
                    mActive.state = BleCommand.State.QUEUED;
                    enviarComandoAtivo();
                } else {
                    falharComando(mActive, "ACK timeout após " + MAX_RETRIES + " tentativas");
                }
            }
        };
        mHandler.postDelayed(mAckTimeoutRunnable, ms);
    }

    private void iniciarDoneTimeout() {
        cancelDoneTimeout();
        mDoneTimeoutRunnable = () -> {
            synchronized (CommandQueue.this) {
                if (mActive == null || mActive.state != BleCommand.State.ACKED) return;
                Log.e(TAG, "[QUEUE] DONE TIMEOUT (" + DONE_TIMEOUT_MS + "ms) para " + mActive.commandId);
                falharComando(mActive, "DONE timeout após " + DONE_TIMEOUT_MS + "ms");
            }
        };
        mHandler.postDelayed(mDoneTimeoutRunnable, DONE_TIMEOUT_MS);
    }

    private void cancelAckTimeout() {
        if (mAckTimeoutRunnable != null) {
            mHandler.removeCallbacks(mAckTimeoutRunnable);
            mAckTimeoutRunnable = null;
        }
    }

    private void cancelDoneTimeout() {
        if (mDoneTimeoutRunnable != null) {
            mHandler.removeCallbacks(mDoneTimeoutRunnable);
            mDoneTimeoutRunnable = null;
        }
    }

    private void cancelAllTimeouts() {
        cancelAckTimeout();
        cancelDoneTimeout();
    }

    // ── Utilitários ───────────────────────────────────────────────────────────

    private static String gerarCmdId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
