package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

/**
 * CommandQueueManager — Fila FIFO de comandos BLE Industrial v2.3.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARQUITETURA
 * ═══════════════════════════════════════════════════════════════════
 *
 * Fila FIFO: apenas 1 comando ativo por vez.
 * Fluxo por comando:
 *   enqueue() → send → aguarda ACK (5s) → aguarda DONE (15s) → remove → próximo
 *
 * Deduplicação:
 *   O firmware ESP32 usa CMD_ID para detectar duplicatas.
 *   O CommandQueueManager mantém o mesmo CMD_ID em reenvios,
 *   garantindo que o ESP32 responda ACK sem executar novamente.
 *
 * Reconexão inteligente:
 *   Se BLE desconectar com comando SENT/ACKED em andamento,
 *   ao reconectar o mesmo comando é reenviado com o mesmo ID.
 *   O ESP32 responde ACK (se ainda executando) ou DONE (se já terminou).
 *
 * ═══════════════════════════════════════════════════════════════════
 * CALLBACKS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   onSend(cmd)    — chamado quando o comando é enviado via BLE
 *   onAck(cmd)     — chamado quando ACK|ID é recebido
 *   onDone(cmd)    — chamado quando DONE|ID|ml é recebido
 *   onError(cmd)   — chamado em timeout ou erro irrecuperável
 */
public class CommandQueueManager {

    private static final String TAG = "BLE_CMD_QUEUE";

    // ── Timeouts ──────────────────────────────────────────────────────────────
    /** Timeout para receber ACK após envio (ms). Firmware garante ACK em < 100ms. */
    private static final long ACK_TIMEOUT_MS  = 5_000L;
    /** Timeout para receber DONE após ACK (ms). Operação máxima: 10s no firmware. */
    private static final long DONE_TIMEOUT_MS = 15_000L;

    // ── Estado interno ────────────────────────────────────────────────────────
    private final Queue<BleCommand> mQueue   = new LinkedList<>();
    private BleCommand              mActive  = null;  // Comando em andamento
    private boolean                 mPaused  = false; // Pausa durante desconexão BLE

    private final Handler  mHandler = new Handler(Looper.getMainLooper());
    private Runnable       mAckTimeoutRunnable  = null;
    private Runnable       mDoneTimeoutRunnable = null;

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        void onSend(BleCommand cmd);
        void onAck(BleCommand cmd);
        void onDone(BleCommand cmd);
        void onError(BleCommand cmd, String reason);
    }

    private Callback mCallback;

    // ── Interface de envio BLE (injetada pelo BluetoothService) ──────────────
    public interface BleWriter {
        /** Envia a string via BLE. Retorna true se enviado com sucesso. */
        boolean write(String data);
    }

    private BleWriter mWriter;

    // ── Construtor ────────────────────────────────────────────────────────────
    public CommandQueueManager(BleWriter writer, Callback callback) {
        this.mWriter   = writer;
        this.mCallback = callback;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Gera um novo BleCommand de SERVE com IDs únicos e o enfileira.
     *
     * @param volumeMl Volume em ml a ser liberado
     * @return O BleCommand criado (para rastreamento pelo chamador)
     */
    public synchronized BleCommand enqueueServe(int volumeMl) {
        String cmdId    = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String sessionId = "SES_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        BleCommand cmd = new BleCommand(BleCommand.Type.SERVE, cmdId, sessionId, volumeMl);
        mQueue.add(cmd);
        Log.i(TAG, "[BLE_CMD] enqueue → " + cmd);
        processQueue();
        return cmd;
    }

    /**
     * Enfileira um comando PING para heartbeat.
     */
    public synchronized void enqueuePing() {
        String cmdId = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        BleCommand cmd = new BleCommand(BleCommand.Type.PING, cmdId, "", 0);
        mQueue.add(cmd);
        processQueue();
    }

    /**
     * Processa a resposta BLE recebida do ESP32.
     * Deve ser chamado pelo BluetoothService em onCharacteristicChanged().
     *
     * Formatos tratados:
     *   ACK|<id>
     *   DONE|<id>|<ml>
     *   DONE|<id>|<ml>|<session>
     *   DONE (sem ID — legado)
     *   DUPLICATE / ML:DUPLICATE
     *   ERROR:BUSY
     *   ERROR:WATCHDOG
     *   PONG
     */
    public synchronized void onBleResponse(String response) {
        if (response == null || response.isEmpty()) return;

        Log.d(TAG, "[BLE_CMD] resposta recebida: [" + response + "] | ativo=" + mActive);

        // ── ML:ACK (protocolo real ESP32) ou ACK|<id> (formato v2.3 doc) ───────────
        if (response.equalsIgnoreCase("ML:ACK") || response.startsWith("ACK|")) {
            String ackId = response.startsWith("ACK|") ? response.substring(4).trim() : null;
            if (mActive != null) {
                // Se o ID está presente, valida; se não, aceita para o comando ativo
                boolean idMatch = (ackId == null || ackId.isEmpty()
                        || mActive.commandId.equalsIgnoreCase(ackId));
                if (idMatch) {
                    Log.i(TAG, "[BLE_CMD] ack → " + mActive.commandId);
                    cancelAckTimeout();
                    mActive.state = BleCommand.State.ACKED;
                    if (mCallback != null) mCallback.onAck(mActive);
                    iniciarDoneTimeout();
                } else {
                    Log.w(TAG, "[BLE_CMD] ACK ignorado — id=" + ackId + " | ativo=" + mActive.commandId);
                }
            } else {
                Log.w(TAG, "[BLE_CMD] ACK recebido sem comando ativo");
            }
            return;
        }

        // ── DONE com ou sem ID ────────────────────────────────────────────────
        if (response.startsWith("DONE")) {
            handleDone(response);
            return;
        }

        // ── DUPLICATE / ML:DUPLICATE ──────────────────────────────────────────
        if (response.equalsIgnoreCase("DUPLICATE") || response.equalsIgnoreCase("ML:DUPLICATE")) {
            Log.w(TAG, "[BLE_CMD] DUPLICATE recebido — sincronizando estado");
            if (mActive != null) {
                // O firmware já executou este comando — tratar como DONE sem ml_real
                mActive.state = BleCommand.State.DONE;
                cancelAllTimeouts();
                if (mCallback != null) mCallback.onDone(mActive);
                mActive = null;
                processQueue();
            }
            return;
        }

        // ── ERROR:BUSY ────────────────────────────────────────────────────────
        if (response.equalsIgnoreCase("ERROR:BUSY")) {
            Log.w(TAG, "[BLE_CMD] ERROR:BUSY — ESP32 ocupado, aguardando 2s para reenvio");
            if (mActive != null && mActive.canRetry()) {
                mActive.retryCount++;
                mActive.state = BleCommand.State.QUEUED;
                cancelAllTimeouts();
                mHandler.postDelayed(this::processQueue, 2_000L);
            } else if (mActive != null) {
                falharComando(mActive, "ERROR:BUSY — máximo de retries atingido");
            }
            return;
        }

        // ── ERROR:WATCHDOG ────────────────────────────────────────────────────
        if (response.startsWith("ERROR:WATCHDOG")) {
            Log.e(TAG, "[BLE_CMD] ERROR:WATCHDOG recebido do ESP32");
            if (mActive != null) {
                falharComando(mActive, "ERROR:WATCHDOG");
            }
            return;
        }

        // ── PONG ──────────────────────────────────────────────────────────────
        if (response.equalsIgnoreCase("PONG")) {
            Log.d(TAG, "[BLE_CMD] PONG recebido — BLE ativo");
            // Remove PING da fila se for o ativo
            if (mActive != null && mActive.type == BleCommand.Type.PING) {
                cancelAllTimeouts();
                mActive.state = BleCommand.State.DONE;
                mActive = null;
                processQueue();
            }
        }
    }

    /**
     * Chamado pelo BluetoothService quando o BLE desconecta.
     * Pausa a fila — o comando ativo permanece para reenvio após reconexão.
     */
    public synchronized void onBleDisconnected() {
        Log.w(TAG, "[BLE_CMD] BLE desconectado — pausando fila | ativo=" + mActive);
        mPaused = true;
        cancelAllTimeouts();
        // Não remove o comando ativo — será reenviado na reconexão
        if (mActive != null && mActive.state == BleCommand.State.SENT) {
            mActive.state = BleCommand.State.QUEUED; // Volta para QUEUED para reenvio
            Log.i(TAG, "[BLE_CMD] retry → " + mActive.commandId + " (mesmo ID para deduplicação)");
        }
    }

    /**
     * Chamado pelo BluetoothService quando o BLE reconecta e está READY.
     * Retoma a fila — reenvio do comando ativo com mesmo ID.
     */
    public synchronized void onBleReady() {
        Log.i(TAG, "[BLE_CMD] BLE READY — retomando fila | ativo=" + mActive + " | fila=" + mQueue.size());
        mPaused = false;
        processQueue();
    }

    /**
     * Retorna o comando SERVE ativo (se houver), para consulta pelo PagamentoConcluido.
     */
    public synchronized BleCommand getActiveCommand() {
        return mActive;
    }

    /**
     * Limpa toda a fila e cancela o comando ativo.
     * Usar apenas em reset de emergência.
     */
    public synchronized void reset() {
        Log.w(TAG, "[BLE_CMD] reset() — limpando fila e cancelando ativo");
        cancelAllTimeouts();
        mQueue.clear();
        mActive = null;
        mPaused = false;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Processamento interno
    // ═════════════════════════════════════════════════════════════════════════

    private void processQueue() {
        if (mPaused) {
            Log.d(TAG, "[BLE_CMD] processQueue() — PAUSADO (BLE desconectado)");
            return;
        }
        if (mActive != null) {
            Log.d(TAG, "[BLE_CMD] processQueue() — aguardando conclusão de " + mActive.commandId);
            return;
        }
        if (mQueue.isEmpty()) {
            Log.d(TAG, "[BLE_CMD] processQueue() — fila vazia");
            return;
        }

        mActive = mQueue.poll();
        enviarComandoAtivo();
    }

    private void enviarComandoAtivo() {
        if (mActive == null) return;

        String bleStr = mActive.toBleString();
        Log.i(TAG, "[BLE_CMD] sent → " + mActive.commandId + " | cmd=[" + bleStr + "]");

        boolean ok = mWriter.write(bleStr);
        if (ok) {
            mActive.state = BleCommand.State.SENT;
            if (mCallback != null) mCallback.onSend(mActive);
            // PINGs não precisam de ACK/DONE — timeout curto
            if (mActive.type == BleCommand.Type.PING) {
                iniciarAckTimeout(3_000L);
            } else {
                iniciarAckTimeout(ACK_TIMEOUT_MS);
            }
        } else {
            Log.e(TAG, "[BLE_CMD] write() falhou para " + mActive.commandId + " — agendando retry");
            mActive.retryCount++;
            if (mActive.canRetry()) {
                mActive.state = BleCommand.State.QUEUED;
                mQueue.add(mActive); // Recoloca no final da fila
                mActive = null;
                mHandler.postDelayed(this::processQueue, 1_000L);
            } else {
                falharComando(mActive, "write() falhou após " + BleCommand.MAX_RETRIES + " tentativas");
            }
        }
    }

    private void handleDone(String response) {
        // Formatos:
        //   DONE
        //   DONE|<id>|<ml>
        //   DONE|<id>|<ml>|<session>
        String[] parts = response.split("\\|");
        String doneId = parts.length >= 2 ? parts[1].trim() : null;
        int mlReal = 0;
        if (parts.length >= 3) {
            try { mlReal = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}
        }

        if (mActive == null) {
            Log.w(TAG, "[BLE_CMD] DONE recebido sem comando ativo — ignorando");
            return;
        }

        // Verifica se o ID bate (se houver ID no DONE)
        if (doneId != null && !doneId.isEmpty() && !doneId.equalsIgnoreCase(mActive.commandId)) {
            Log.w(TAG, "[BLE_CMD] DONE id=" + doneId + " não bate com ativo=" + mActive.commandId + " — ignorando");
            return;
        }

        Log.i(TAG, "[BLE_CMD] done → " + mActive.commandId + " | ml_real=" + mlReal);
        cancelAllTimeouts();
        mActive.state  = BleCommand.State.DONE;
        mActive.mlReal = mlReal;
        if (mCallback != null) mCallback.onDone(mActive);
        mActive = null;
        processQueue();
    }

    private void falharComando(BleCommand cmd, String reason) {
        Log.e(TAG, "[BLE_CMD] error → " + cmd.commandId + " | motivo=" + reason);
        cancelAllTimeouts();
        cmd.state        = BleCommand.State.ERROR;
        cmd.errorMessage = reason;
        if (mCallback != null) mCallback.onError(cmd, reason);
        mActive = null;
        mQueue.clear(); // Limpa fila em caso de erro irrecuperável
    }

    // ── Timeouts ──────────────────────────────────────────────────────────────

    private void iniciarAckTimeout(long ms) {
        cancelAckTimeout();
        mAckTimeoutRunnable = () -> {
            synchronized (CommandQueueManager.this) {
                if (mActive == null || mActive.state != BleCommand.State.SENT) return;
                Log.e(TAG, "[BLE_CMD] ACK TIMEOUT (" + ms + "ms) para " + mActive.commandId
                        + " | retry=" + mActive.retryCount);
                if (mActive.canRetry()) {
                    mActive.retryCount++;
                    mActive.state = BleCommand.State.QUEUED;
                    Log.i(TAG, "[BLE_CMD] retry → " + mActive.commandId
                            + " (tentativa " + mActive.retryCount + "/" + BleCommand.MAX_RETRIES + ")");
                    enviarComandoAtivo();
                } else {
                    falharComando(mActive, "ACK timeout após " + BleCommand.MAX_RETRIES + " tentativas");
                }
            }
        };
        mHandler.postDelayed(mAckTimeoutRunnable, ms);
    }

    private void iniciarDoneTimeout() {
        cancelDoneTimeout();
        mDoneTimeoutRunnable = () -> {
            synchronized (CommandQueueManager.this) {
                if (mActive == null || mActive.state != BleCommand.State.ACKED) return;
                Log.e(TAG, "[BLE_CMD] DONE TIMEOUT (" + DONE_TIMEOUT_MS + "ms) para " + mActive.commandId);
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
}
