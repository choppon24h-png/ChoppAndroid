package com.example.choppontap;

/**
 * BleCommand — Modelo de um comando BLE Industrial v2.3.
 *
 * Representa um comando na fila do CommandQueueManager com ciclo de vida:
 *   QUEUED → SENT → ACKED → DONE (ou ERROR)
 *
 * Formatos suportados:
 *   Formato A (legado):   $ML:300:CMD_A1B2
 *   Formato B (v2.3):     SERVE|300|ID=A1B2|SESSION=SES_ABC
 *
 * O firmware ESP32 aceita ambos os formatos e normaliza SERVE → ML internamente.
 */
public class BleCommand {

    // ── Tipos de comando ──────────────────────────────────────────────────────
    public enum Type {
        AUTH,    // $AUTH:259087 — autenticação por PIN
        SERVE,   // SERVE|<ml>|ID=<id>|SESSION=<session> — liberação de chopp
        STOP,    // $STOP:<id> — parar dispensação
        STATUS,  // $STATUS:<id> — consultar estado do ESP32
        PING     // $PING:<id> — heartbeat
    }

    // ── Estados do ciclo de vida ──────────────────────────────────────────────
    public enum State {
        QUEUED,   // Na fila, aguardando envio
        SENT,     // Enviado via BLE, aguardando ACK
        ACKED,    // ACK recebido, aguardando DONE
        DONE,     // DONE recebido — operação concluída com sucesso
        ERROR     // Erro (timeout, BUSY, watchdog, etc.)
    }

    // ── Campos ────────────────────────────────────────────────────────────────
    public final Type   type;
    public final String commandId;   // ID único de 8 chars (ex: "A1B2C3D4")
    public final String sessionId;   // SESSION_ID da venda (ex: "SES_8472abcd")
    public final int    volumeMl;    // Volume em ml (apenas para SERVE)
    public final long   timestamp;   // Timestamp de criação (ms)

    public State  state        = State.QUEUED;
    public int    mlReal       = 0;    // Volume real confirmado pelo ESP32 no DONE
    public int    retryCount   = 0;    // Número de tentativas de reenvio
    public String errorMessage = null; // Mensagem de erro se state == ERROR

    // ── Constantes ────────────────────────────────────────────────────────────
    public static final int MAX_RETRIES = 3;

    // ── Construtor ────────────────────────────────────────────────────────────
    public BleCommand(Type type, String commandId, String sessionId, int volumeMl) {
        this.type      = type;
        this.commandId = commandId;
        this.sessionId = sessionId;
        this.volumeMl  = volumeMl;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Gera a string de comando BLE no Formato B (v2.3) para SERVE.
     * Formato: SERVE|<ml>|ID=<id>|SESSION=<session>
     *
     * Para outros tipos, usa o Formato A com prefixo $.
     */
    public String toBleString() {
        switch (type) {
            case SERVE:
                // Protocolo real ESP32 v2.3: $ML:<ml>:<CMD_ID>
                // Compatível com $ML:300:CMD_A1B2 (item 13 do documento)
                return "$ML:" + volumeMl + ":" + commandId;
            case AUTH:
                return "$AUTH:" + BluetoothService.ESP32_PIN + ":" + commandId;
            case STOP:
                return "$STOP:" + commandId;
            case STATUS:
                return "$STATUS:" + commandId;
            case PING:
                return "$PING:" + commandId;
            default:
                return "$" + type.name() + ":" + commandId;
        }
    }

    /**
     * Retorna true se o comando pode ser reenviado (não excedeu MAX_RETRIES).
     */
    public boolean canRetry() {
        return retryCount < MAX_RETRIES;
    }

    @Override
    public String toString() {
        return "BleCommand{"
                + "type=" + type
                + ", id=" + commandId
                + ", session=" + sessionId
                + ", vol=" + volumeMl + "ml"
                + ", state=" + state
                + ", retry=" + retryCount
                + "}";
    }
}
