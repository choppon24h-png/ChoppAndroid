package com.example.choppontap;

import android.util.Log;

/**
 * BleManager — Orquestrador central da camada BLE Industrial v2.3.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARQUITETURA
 * ═══════════════════════════════════════════════════════════════════
 *
 *   BluetoothServiceIndustrial
 *        │
 *        ▼
 *   BleManager  ◄──── PagamentoConcluido (via getCommandQueue)
 *        │
 *        ├── BleParser         (parse de mensagens BLE)
 *        ├── ConnectionManager (estados + heartbeat + reconexão)
 *        └── CommandQueue      (fila FIFO ACK/DONE)
 *
 * ═══════════════════════════════════════════════════════════════════
 * RESPONSABILIDADES
 * ═══════════════════════════════════════════════════════════════════
 *
 *   - Recebe dados brutos do ESP32 via onBleDataReceived()
 *   - Roteia mensagens para os módulos corretos
 *   - Coordena reconexão, heartbeat e fila de comandos
 *   - Envia STATUS ao ESP32 após reconexão para sincronizar estado
 *   - Expõe API unificada para BluetoothServiceIndustrial e Activities
 *
 * ═══════════════════════════════════════════════════════════════════
 * LOGS INDUSTRIAIS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   [BLE]     — eventos de conexão/desconexão
 *   [QUEUE]   — fila de comandos
 *   [SESSION] — sessão de venda
 *   [API]     — chamadas HTTP
 */
public class BleManager {

    private static final String TAG = "BLE_MANAGER";

    // ── Módulos ───────────────────────────────────────────────────────────────
    private final ConnectionManager mConnectionManager;
    private final CommandQueue       mCommandQueue;

    // ── Interface de escrita BLE (injetada pelo BluetoothServiceIndustrial) ─────────────
    private CommandQueue.BleWriter mWriter;

    // ── Callbacks para o BluetoothServiceIndustrial ─────────────────────────────────────
    public interface Callback {
        /** BLE transitou para um novo estado */
        void onStateChanged(ConnectionManager.State newState, ConnectionManager.State oldState);

        /**
         * Solicitação de conexão GATT.
         * @param mac         Endereço MAC do dispositivo
         * @param autoConnect true para reconexão automática (spec v2.3),
         *                    false para primeira conexão (mais rápido)
         */
        void onConnectRequested(String mac, boolean autoConnect);

        /** Comando enviado via BLE */
        void onCommandSent(BleCommand cmd);

        /** ACK recebido do ESP32 */
        void onCommandAck(BleCommand cmd);

        /** DONE recebido do ESP32 — mlReal disponível */
        void onCommandDone(BleCommand cmd);

        /** Erro irrecuperável no comando */
        void onCommandError(BleCommand cmd, String reason);

        /** Heartbeat falhou — deve forçar reconexão GATT */
        void onHeartbeatFailed();
    }

    private final Callback mCallback;

    // ── Construtor ────────────────────────────────────────────────────────────
    public BleManager(Callback callback) {
        this.mCallback = callback;

        // Inicializa ConnectionManager com callbacks
        mConnectionManager = new ConnectionManager(new ConnectionManager.Callback() {
            @Override
            public void onStateChanged(ConnectionManager.State newState,
                                       ConnectionManager.State oldState) {
                Log.i(TAG, "[BLE] " + oldState.name() + " → " + newState.name());
                if (mCallback != null) mCallback.onStateChanged(newState, oldState);

                if (newState == ConnectionManager.State.READY) {
                    // BLE READY — sincroniza estado do ESP32 e retoma fila
                    // Envia STATUS para verificar se ESP32 tem comando pendente
                    if (mWriter != null) {
                        Log.i(TAG, "[BLE] READY → enviando STATUS para sync pós-reconexão");
                        mWriter.write("STATUS");
                    }
                    mCommandQueue.onBleReady();
                } else if (newState == ConnectionManager.State.DISCONNECTED) {
                    // BLE desconectado — pausa fila
                    mCommandQueue.onBleDisconnected();
                }
            }

            @Override
            public void onPingRequested() {
                // Enfileira PING para heartbeat
                mCommandQueue.enqueuePing();
            }

            @Override
            public void onHeartbeatFailed() {
                Log.e(TAG, "[BLE] Heartbeat falhou — solicitando reconexão");
                if (mCallback != null) mCallback.onHeartbeatFailed();
            }

            @Override
            public void onConnectRequested(String mac, boolean autoConnect) {
                Log.i(TAG, "[BLE] Conexão solicitada → " + mac
                        + " (autoConnect=" + autoConnect + ")");
                if (mCallback != null) mCallback.onConnectRequested(mac, autoConnect);
            }
        });

        // Inicializa CommandQueue (writer será injetado depois via setWriter)
        mCommandQueue = new CommandQueue(
                data -> {
                    if (mWriter == null) {
                        Log.e(TAG, "[QUEUE] write() bloqueado — BleWriter não configurado");
                        return false;
                    }
                    if (!mConnectionManager.isReady()) {
                        Log.e(TAG, "[QUEUE] write() bloqueado — BLE não está READY (estado="
                                + mConnectionManager.getState() + ")");
                        return false;
                    }
                    return mWriter.write(data);
                },
                new CommandQueue.Callback() {
                    @Override
                    public void onSend(BleCommand cmd) {
                        Log.i(TAG, "[QUEUE] SEND " + cmd.type.name() + " " + cmd.commandId);
                        if (mCallback != null) mCallback.onCommandSent(cmd);
                    }

                    @Override
                    public void onAck(BleCommand cmd) {
                        Log.i(TAG, "[QUEUE] ACK " + cmd.commandId);
                        mConnectionManager.onDataReceived(); // reseta ping failures
                        if (mCallback != null) mCallback.onCommandAck(cmd);
                    }

                    @Override
                    public void onDone(BleCommand cmd) {
                        Log.i(TAG, "[QUEUE] DONE " + cmd.commandId + " | ml_real=" + cmd.mlReal);
                        if (mCallback != null) mCallback.onCommandDone(cmd);
                    }

                    @Override
                    public void onError(BleCommand cmd, String reason) {
                        Log.e(TAG, "[QUEUE] ERROR " + cmd.commandId + " | " + reason);
                        if (mCallback != null) mCallback.onCommandError(cmd, reason);
                    }

                    @Override
                    public void onQueueFull() {
                        Log.e(TAG, "[QUEUE] Fila cheia — comando rejeitado");
                    }
                }
        );
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Configuração
    // ═════════════════════════════════════════════════════════════════════════

    /** Injeta o BleWriter (deve ser chamado pelo BluetoothServiceIndustrial após inicialização). */
    public void setWriter(CommandQueue.BleWriter writer) {
        this.mWriter = writer;
    }

    /** Define o MAC alvo para reconexão automática. */
    public void setTargetMac(String mac) {
        mConnectionManager.setTargetMac(mac);
    }

    /** Habilita ou desabilita reconexão automática. */
    public void setAutoReconnect(boolean enabled) {
        mConnectionManager.setAutoReconnect(enabled);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Eventos de scan (chamados pelo BluetoothServiceIndustrial)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Notifica que o scan BLE foi iniciado.
     * Transiciona para SCANNING.
     */
    public void onScanStarted() {
        mConnectionManager.onScanStarted();
    }

    /**
     * Notifica que um dispositivo CHOPP_* foi encontrado no scan.
     * Agenda conexão após delay de 800ms (spec BLE Industrial v2.3).
     *
     * REGRA CRÍTICA: NÃO conectar dentro do callback do scan.
     *
     * @param mac  Endereço MAC do dispositivo encontrado
     */
    public void onDeviceFoundInScan(String mac) {
        mConnectionManager.onDeviceFoundInScan(mac);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Eventos GATT (chamados pelo BluetoothServiceIndustrial)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Notifica que o GATT foi conectado (STATE_CONNECTED).
     */
    public void onGattConnected(String mac) {
        Log.i(TAG, "[BLE] CONNECTED → " + mac);
        mConnectionManager.setTargetMac(mac);
        mConnectionManager.onGattConnected();
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
    public void onGattDisconnected(int status, boolean closeGatt) {
        Log.w(TAG, "[BLE] DISCONNECTED | status=" + status);
        mConnectionManager.onGattDisconnected(status, closeGatt);
    }

    /**
     * Notifica que AUTH:OK foi recebido — transiciona para READY.
     */
    public void onAuthOk() {
        Log.i(TAG, "[BLE] AUTH:OK → READY");
        mConnectionManager.onAuthOk();
    }

    /**
     * Processa dados recebidos do ESP32 via BLE.
     * Deve ser chamado em onCharacteristicChanged().
     */
    public void onBleDataReceived(String raw) {
        if (raw == null || raw.isEmpty()) return;

        // Notifica ConnectionManager de qualquer RX (reseta ping failures)
        mConnectionManager.onDataReceived();

        // Faz parse centralizado
        BleParser.ParsedMessage msg = BleParser.parse(raw);

        Log.d(TAG, "[BLE] RX " + msg.type.name()
                + (msg.commandId != null ? " " + msg.commandId : "")
                + (msg.mlReal > 0 ? " " + msg.mlReal + "ml" : ""));

        // Roteia para os módulos corretos
        switch (msg.type) {
            case AUTH_OK:
                onAuthOk();
                break;

            case PONG:
                mConnectionManager.onPongReceived();
                mCommandQueue.onBleResponse(msg);
                break;

            case ACK:
            case DONE:
            case DUPLICATE:
            case ERROR_BUSY:
            case ERROR_WATCHDOG:
                mCommandQueue.onBleResponse(msg);
                break;

            case STATUS_READY:
                // ESP32 respondeu READY a um STATUS query — fila pode continuar
                Log.i(TAG, "[BLE] STATUS:READY — ESP32 pronto, retomando fila");
                mCommandQueue.onBleResponse(msg);
                break;

            case STATUS_BUSY:
                // ESP32 ainda ocupado — aguarda DONE
                Log.w(TAG, "[BLE] STATUS:BUSY — ESP32 ocupado, aguardando DONE");
                mCommandQueue.onBleResponse(msg);
                break;

            case VP:
                // Progresso parcial — apenas log (tratado pela Activity via broadcast)
                Log.d(TAG, "[BLE] VP: " + msg.mlReal + "ml");
                break;

            case AUTH_FAIL:
                Log.e(TAG, "[BLE] AUTH:FAIL — PIN incorreto ou bond inválido");
                break;

            case UNKNOWN:
            default:
                Log.d(TAG, "[BLE] Mensagem não reconhecida: [" + raw + "]");
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API para Activities e BluetoothServiceIndustrial
    // ═════════════════════════════════════════════════════════════════════════

    /** Retorna o estado de conexão atual. */
    public ConnectionManager.State getConnectionState() {
        return mConnectionManager.getState();
    }

    /** Retorna true se o BLE está READY para enviar comandos. */
    public boolean isReady() {
        return mConnectionManager.isReady();
    }

    /** Retorna true se está conectado (CONNECTED ou READY). */
    public boolean isConnected() {
        return mConnectionManager.isConnected();
    }

    /** Retorna a fila de comandos para uso pelas Activities. */
    public CommandQueue getCommandQueue() {
        return mCommandQueue;
    }

    /** Retorna o ConnectionManager para uso pelo BluetoothServiceIndustrial. */
    public ConnectionManager getConnectionManager() {
        return mConnectionManager;
    }

    /**
     * Para tudo — chamado no onDestroy() do BluetoothServiceIndustrial.
     */
    public void destroy() {
        mConnectionManager.destroy();
        mCommandQueue.reset();
    }

    @Override
    public String toString() {
        return "BleManager{conn=" + mConnectionManager.getState()
                + ", queue=" + mCommandQueue.size() + "}";
    }
}
