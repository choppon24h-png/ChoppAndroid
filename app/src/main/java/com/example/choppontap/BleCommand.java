package com.example.choppontap;

/**
 * BleCommand — Modelo de um comando BLE Industrial v2.3.
 *
 * Representa um comando na fila do CommandQueue/CommandQueueManager com ciclo de vida:
 *   QUEUED → SENT → ACKED → DONE (ou ERROR)
 *
 * Formato de envio ao ESP32 Industrial:
 *   SERVE:  SERVE|<ml>|ID=<cmdId>|SESSION=<sessionId>
 *   AUTH:   $AUTH:<pin>:<cmdId>
 *   STOP:   $STOP:<cmdId>
 *   STATUS: $STATUS:<cmdId>
 *   PING:   $PING:<cmdId>
 *
 * O firmware ESP32 normaliza SERVE → ML internamente.
 *
 * MUDANÇA v3.0-INDUSTRIAL:
 *   - toBleString() SERVE agora usa formato SERVE|<ml>|ID=<id>|SESSION=<session>
 *   - AUTH usa BluetoothServiceIndustrial.ESP32_PIN (fonte única de verdade)
 */
public class BleCommand {

    // ── Tipos de comando ──────────────────────────────────────────────────────
    public enum Type {
        AUTH,    // $AUTH:259087:<id> — autenticação por PIN
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
     * Gera a string de comando BLE para envio ao ESP32 Industrial.
     *
     * Formato SERVE (v3.0-INDUSTRIAL):
     *   SERVE|<ml>|ID=<cmdId>|SESSION=<sessionId>
     *   Exemplo: SERVE|300|ID=A1B2C3D4|SESSION=SES_8472ABCD
     *
     * Para outros tipos, usa o formato legado com prefixo $.
     */
    public String toBleString() {
        switch (type) {
            case SERVE:
                // Protocolo Industrial ESP32 v3.0: SERVE|<ml>|ID=<id>|SESSION=<session>
                // O firmware CHOPP_* normaliza SERVE → ML internamente
                return "SERVE|" + volumeMl + "|ID=" + commandId + "|SESSION=" + sessionId;
            case AUTH:
                // Usa BluetoothServiceIndustrial como fonte única do PIN
                return "$AUTH:" + BluetoothServiceIndustrial.ESP32_PIN + ":" + commandId;
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
