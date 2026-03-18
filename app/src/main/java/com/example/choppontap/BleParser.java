package com.example.choppontap;

import android.util.Log;

/**
 * BleParser — Parser centralizado de respostas BLE do ESP32 v2.3.
 *
 * Protocolo ESP32 v2.3 suportado:
 *   AUTH:OK, AUTH:FAIL, ML:ACK, ACK|id, DONE, DONE|id|ml, DONE|id|ml|session,
 *   VP:ml, PONG, DUPLICATE, ML:DUPLICATE, ERROR:BUSY, ERROR:WATCHDOG, READY, BUSY
 *
 * Uso:
 *   BleParser.ParsedMessage msg = BleParser.parse(rawString);
 *   switch (msg.type) { case AUTH_OK: ... case ACK: ... case DONE: ... }
 */
public class BleParser {

    private static final String TAG = "BLE_PARSER";

    public enum MessageType {
        AUTH_OK,
        AUTH_FAIL,
        ACK,
        DONE,
        VP,
        PONG,
        DUPLICATE,
        ERROR_BUSY,
        ERROR_WATCHDOG,
        STATUS_READY,
        STATUS_BUSY,
        UNKNOWN
    }

    /** Resultado do parse de uma mensagem BLE recebida do ESP32. */
    public static class ParsedMessage {
        public final MessageType type;
        public final String      raw;
        public final String      commandId;   // presente em ACK|id, DONE|id|ml
        public final String      sessionId;   // presente em DONE|id|ml|session
        public final int         mlReal;      // presente em DONE|id|ml e VP:ml

        private ParsedMessage(MessageType type, String raw,
                              String commandId, String sessionId, int mlReal) {
            this.type      = type;
            this.raw       = raw;
            this.commandId = commandId;
            this.sessionId = sessionId;
            this.mlReal    = mlReal;
        }

        @Override
        public String toString() {
            return "ParsedMessage{type=" + type
                    + ", cmd=" + commandId
                    + ", session=" + sessionId
                    + ", ml=" + mlReal
                    + ", raw=[" + raw + "]}";
        }
    }

    /**
     * Faz o parse de uma string recebida via BLE do ESP32.
     * Nunca retorna null — retorna UNKNOWN para strings não reconhecidas.
     */
    public static ParsedMessage parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ParsedMessage(MessageType.UNKNOWN, "", null, null, 0);
        }

        String s = raw.trim();

        // ── AUTH:OK ───────────────────────────────────────────────────────────
        if ("AUTH:OK".equalsIgnoreCase(s)) {
            Log.i(TAG, "[BLE] → AUTH_OK");
            return new ParsedMessage(MessageType.AUTH_OK, s, null, null, 0);
        }

        // ── AUTH:FAIL ─────────────────────────────────────────────────────────
        if ("AUTH:FAIL".equalsIgnoreCase(s)) {
            Log.w(TAG, "[BLE] → AUTH_FAIL");
            return new ParsedMessage(MessageType.AUTH_FAIL, s, null, null, 0);
        }

        // ── ML:ACK (protocolo legado ESP32) ───────────────────────────────────
        if ("ML:ACK".equalsIgnoreCase(s)) {
            Log.i(TAG, "[BLE] → ACK (ML:ACK legado)");
            return new ParsedMessage(MessageType.ACK, s, null, null, 0);
        }

        // ── ACK|<id> (protocolo v2.3) ─────────────────────────────────────────
        if (s.startsWith("ACK|")) {
            String cmdId = s.substring(4).trim();
            Log.i(TAG, "[BLE] → ACK | id=" + cmdId);
            return new ParsedMessage(MessageType.ACK, s, cmdId, null, 0);
        }

        // ── DONE (vários formatos) ────────────────────────────────────────────
        if (s.startsWith("DONE")) {
            return parseDone(s);
        }

        // ── VP:<ml> — progresso parcial ───────────────────────────────────────
        if (s.startsWith("VP:")) {
            int ml = 0;
            try { ml = Integer.parseInt(s.substring(3).trim()); } catch (Exception ignored) {}
            Log.d(TAG, "[BLE] → VP | ml=" + ml);
            return new ParsedMessage(MessageType.VP, s, null, null, ml);
        }

        // ── PONG ──────────────────────────────────────────────────────────────
        if ("PONG".equalsIgnoreCase(s)) {
            Log.d(TAG, "[BLE] → PONG");
            return new ParsedMessage(MessageType.PONG, s, null, null, 0);
        }

        // ── DUPLICATE / ML:DUPLICATE ──────────────────────────────────────────
        if ("DUPLICATE".equalsIgnoreCase(s) || "ML:DUPLICATE".equalsIgnoreCase(s)) {
            Log.w(TAG, "[BLE] → DUPLICATE");
            return new ParsedMessage(MessageType.DUPLICATE, s, null, null, 0);
        }

        // ── ERROR:BUSY ────────────────────────────────────────────────────────
        if ("ERROR:BUSY".equalsIgnoreCase(s)) {
            Log.w(TAG, "[BLE] → ERROR_BUSY");
            return new ParsedMessage(MessageType.ERROR_BUSY, s, null, null, 0);
        }

        // ── ERROR:WATCHDOG ────────────────────────────────────────────────────
        if (s.startsWith("ERROR:WATCHDOG") || s.startsWith("ERROR:WDG")) {
            Log.e(TAG, "[BLE] → ERROR_WATCHDOG");
            return new ParsedMessage(MessageType.ERROR_WATCHDOG, s, null, null, 0);
        }

        // ── READY (resposta a STATUS) ─────────────────────────────────────────
        if ("READY".equalsIgnoreCase(s)) {
            Log.i(TAG, "[BLE] → STATUS_READY");
            return new ParsedMessage(MessageType.STATUS_READY, s, null, null, 0);
        }

        // ── BUSY (resposta a STATUS) ──────────────────────────────────────────
        if ("BUSY".equalsIgnoreCase(s)) {
            Log.i(TAG, "[BLE] → STATUS_BUSY");
            return new ParsedMessage(MessageType.STATUS_BUSY, s, null, null, 0);
        }

        // ── UNKNOWN ───────────────────────────────────────────────────────────
        Log.d(TAG, "[BLE] → UNKNOWN: [" + s + "]");
        return new ParsedMessage(MessageType.UNKNOWN, s, null, null, 0);
    }

    /**
     * Parse de mensagens DONE:
     *   DONE
     *   DONE|<id>|<ml>
     *   DONE|<id>|<ml>|<session>
     */
    private static ParsedMessage parseDone(String s) {
        String[] parts = s.split("\\|");
        String cmdId   = parts.length >= 2 ? parts[1].trim() : null;
        int    mlReal  = 0;
        String session = null;

        if (parts.length >= 3) {
            try { mlReal = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}
        }
        if (parts.length >= 4) {
            session = parts[3].trim();
        }

        Log.i(TAG, "[BLE] → DONE | id=" + cmdId + " | ml=" + mlReal + " | session=" + session);
        return new ParsedMessage(MessageType.DONE, s, cmdId, session, mlReal);
    }
}
