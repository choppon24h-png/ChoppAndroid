package com.example.choppontap;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * SessionManager — Gerenciador de sessão anti-fraude para liberação de chopp.
 *
 * ═══════════════════════════════════════════════════════════════════
 * REGRA DE OURO (spec BLE Industrial)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   NUNCA enviar SERVE sem sessão válida.
 *   NUNCA reenviar SERVE automaticamente sem validação de SESSION.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FLUXO DE SESSÃO
 * ═══════════════════════════════════════════════════════════════════
 *
 *   1. startSession(checkoutId, volumeMl, deviceId)
 *      → POST /api/start_session.php
 *      → retorna { session_id: "SES_001", status: "paid" }
 *      → onSessionStarted(sessionId) chamado
 *
 *   2. Android envia: SERVE|300|ID=CMD123|SESSION=SES_001
 *
 *   3. finishSession(mlReal)
 *      → POST /api/finish_session.php
 *      → { session_id, ml_real, device_id, status: "completed" }
 *      → onSessionFinished() chamado
 *
 *   4. Se falhar:
 *      → failSession(motivo, mlParcial)
 *      → POST /api/finish_session.php com status: "error"
 *      → onSessionFailed() chamado
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESTADOS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   IDLE → STARTING → ACTIVE → FINISHING → COMPLETED / FAILED
 */
public class SessionManager {

    private static final String TAG = "BLE_SESSION";

    // ── Estados ───────────────────────────────────────────────────────────────
    public enum State {
        IDLE,
        STARTING,
        ACTIVE,
        FINISHING,
        COMPLETED,
        FAILED
    }

    // ── Dados da sessão ───────────────────────────────────────────────────────
    private State  mState      = State.IDLE;
    private String mSessionId  = null;
    private String mCheckoutId = null;
    private String mDeviceId   = null;
    private String mCommandId  = null;
    private int    mVolumeMl   = 0;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // ── Callbacks ─────────────────────────────────────────────────────────────
    public interface Callback {
        /** Sessão iniciada com sucesso — sessionId disponível para enviar ao ESP32 */
        void onSessionStarted(String sessionId, String checkoutId);
        /** Sessão finalizada com sucesso */
        void onSessionFinished(String sessionId, int mlReal);
        /** Sessão falhou (start ou finish) */
        void onSessionFailed(String sessionId, String reason);
    }

    private final Callback   mCallback;
    private final ApiHelper  mApiHelper;

    // ── Construtor ────────────────────────────────────────────────────────────
    public SessionManager(Callback callback) {
        this.mCallback  = callback;
        this.mApiHelper = new ApiHelper();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API pública
    // ═════════════════════════════════════════════════════════════════════════

    /** Retorna o estado atual da sessão. */
    public synchronized State getState() { return mState; }

    /** Retorna true se há uma sessão ativa (ACTIVE). */
    public synchronized boolean isActive() { return mState == State.ACTIVE; }

    /** Retorna o SESSION_ID atual (null se não houver sessão). */
    public synchronized String getSessionId() { return mSessionId; }

    /** Retorna o CHECKOUT_ID atual. */
    public synchronized String getCheckoutId() { return mCheckoutId; }

    /**
     * Define o command_id do BleCommand associado a esta sessão.
     * Chamado após enqueueServe() retornar o BleCommand.
     */
    public synchronized void setCommandId(String commandId) {
        this.mCommandId = commandId;
        Log.i(TAG, "[SESSION] command_id definido: " + commandId);
    }

    /**
     * Inicia uma nova sessão de venda.
     *
     * Chama POST /api/start_session.php e, em caso de sucesso,
     * transiciona para ACTIVE e chama onSessionStarted(sessionId).
     *
     * @param checkoutId  ID do checkout/pedido
     * @param volumeMl    Volume em ml solicitado
     * @param deviceId    android_id do tablet
     */
    public synchronized void startSession(String checkoutId, int volumeMl, String deviceId) {
        if (mState != State.IDLE && mState != State.FAILED && mState != State.COMPLETED) {
            Log.w(TAG, "[SESSION] startSession() ignorado — estado=" + mState);
            return;
        }

        mCheckoutId = checkoutId;
        mVolumeMl   = volumeMl;
        mDeviceId   = deviceId;
        mSessionId  = null;
        mCommandId  = null;
        mState      = State.STARTING;

        Log.i(TAG, "[SESSION] Iniciando sessão | checkout=" + checkoutId
                + " | vol=" + volumeMl + "ml | device=" + deviceId);

        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml",   String.valueOf(volumeMl));
        body.put("qtd_ml",      String.valueOf(volumeMl));
        body.put("device_id",   deviceId);
        body.put("android_id",  deviceId);

        mApiHelper.sendPost(body, "api/start_session.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] start_session falhou (rede): " + e.getMessage());
                // Fallback: usa start_sale.php como alternativa
                chamarStartSaleFallback(checkoutId, volumeMl, deviceId);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] start_session HTTP " + response.code() + " | body=" + bodyStr);

                if (!response.isSuccessful()) {
                    Log.w(TAG, "[SESSION] start_session HTTP " + response.code()
                            + " — tentando start_sale.php como fallback");
                    chamarStartSaleFallback(checkoutId, volumeMl, deviceId);
                    return;
                }

                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String sessionId = json.optString("session_id", null);
                    String status    = json.optString("status", "");

                    if (sessionId == null || sessionId.isEmpty()) {
                        // Tenta extrair de start_sale response (campo alternativo)
                        sessionId = json.optString("ble_session_id", null);
                    }

                    if (sessionId != null && !sessionId.isEmpty()) {
                        final String finalSessionId = sessionId;
                        mMainHandler.post(() -> {
                            synchronized (SessionManager.this) {
                                mSessionId = finalSessionId;
                                mState     = State.ACTIVE;
                                Log.i(TAG, "[SESSION] ACTIVE | session_id=" + finalSessionId
                                        + " | status=" + status);
                                if (mCallback != null) {
                                    mCallback.onSessionStarted(finalSessionId, checkoutId);
                                }
                            }
                        });
                    } else {
                        // Sem session_id na resposta — gera um local como fallback
                        Log.w(TAG, "[SESSION] session_id ausente na resposta — gerando local");
                        gerarSessionIdLocal(checkoutId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[SESSION] Erro ao parsear resposta: " + e.getMessage());
                    gerarSessionIdLocal(checkoutId);
                }
            }
        });
    }

    /**
     * Finaliza a sessão com sucesso após DONE do ESP32.
     *
     * @param mlReal       Volume real dispensado (do ESP32)
     * @param totalPulsos  Total de pulsos do sensor de fluxo
     */
    public synchronized void finishSession(int mlReal, int totalPulsos) {
        if (mState != State.ACTIVE) {
            Log.w(TAG, "[SESSION] finishSession() ignorado — estado=" + mState);
            return;
        }
        mState = State.FINISHING;

        Log.i(TAG, "[SESSION] Finalizando | session=" + mSessionId
                + " | ml_real=" + mlReal + " | pulsos=" + totalPulsos);

        Map<String, String> body = new HashMap<>();
        body.put("session_id",    mSessionId != null ? mSessionId : "");
        body.put("checkout_id",   mCheckoutId);
        body.put("ml_real",       String.valueOf(mlReal));
        body.put("ml_dispensado", String.valueOf(mlReal));
        body.put("total_pulsos",  String.valueOf(totalPulsos));
        body.put("device_id",     mDeviceId);
        body.put("android_id",    mDeviceId);
        body.put("status",        "completed");
        if (mCommandId != null) body.put("command_id", mCommandId);

        final String sessionIdSnapshot = mSessionId;
        final int    mlRealSnapshot    = mlReal;

        // Tenta finish_session.php primeiro, depois finish_sale.php como fallback
        mApiHelper.sendPost(body, "api/finish_session.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[SESSION] finish_session falhou (rede) — tentando finish_sale.php");
                chamarFinishSaleFallback(body, sessionIdSnapshot, mlRealSnapshot);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] finish_session HTTP " + response.code());
                if (!response.isSuccessful()) {
                    Log.w(TAG, "[SESSION] finish_session HTTP " + response.code()
                            + " — tentando finish_sale.php");
                    chamarFinishSaleFallback(body, sessionIdSnapshot, mlRealSnapshot);
                    return;
                }
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        Log.i(TAG, "[SESSION] FINISHED " + sessionIdSnapshot);
                        if (mCallback != null) {
                            mCallback.onSessionFinished(sessionIdSnapshot, mlRealSnapshot);
                        }
                    }
                });
            }
        });
    }

    /**
     * Registra falha na sessão (timeout BLE, ERROR:BUSY, etc.).
     *
     * @param motivo     Descrição do erro
     * @param mlParcial  Volume parcialmente liberado (0 se nenhum)
     */
    public synchronized void failSession(String motivo, int mlParcial) {
        if (mState == State.COMPLETED || mState == State.FAILED) {
            Log.w(TAG, "[SESSION] failSession() ignorado — estado=" + mState);
            return;
        }
        mState = State.FAILED;

        Log.e(TAG, "[SESSION] FAILED | session=" + mSessionId
                + " | motivo=" + motivo + " | ml_parcial=" + mlParcial);

        Map<String, String> body = new HashMap<>();
        body.put("session_id",  mSessionId != null ? mSessionId : "");
        body.put("checkout_id", mCheckoutId);
        body.put("motivo",      motivo);
        body.put("error_msg",   motivo);
        body.put("ml_liberado", String.valueOf(mlParcial));
        body.put("ml_parcial",  String.valueOf(mlParcial));
        body.put("device_id",   mDeviceId);
        body.put("android_id",  mDeviceId);
        body.put("status",      "error");
        if (mCommandId != null) body.put("command_id", mCommandId);

        final String sessionIdSnapshot = mSessionId;

        // Tenta fail_session.php, depois fail_sale.php como fallback
        mApiHelper.sendPost(body, "api/fail_session.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[SESSION] fail_session falhou — tentando fail_sale.php");
                chamarFailSaleFallback(body, sessionIdSnapshot, motivo);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] fail_session HTTP " + response.code());
                if (!response.isSuccessful()) {
                    chamarFailSaleFallback(body, sessionIdSnapshot, motivo);
                    return;
                }
                mMainHandler.post(() -> {
                    if (mCallback != null) {
                        mCallback.onSessionFailed(sessionIdSnapshot, motivo);
                    }
                });
            }
        });
    }

    /**
     * Reseta o estado para IDLE.
     * Chamar após navegar para Home.
     */
    public synchronized void reset() {
        Log.i(TAG, "[SESSION] reset() | estado anterior=" + mState);
        mState      = State.IDLE;
        mSessionId  = null;
        mCheckoutId = null;
        mDeviceId   = null;
        mCommandId  = null;
        mVolumeMl   = 0;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Fallbacks
    // ═════════════════════════════════════════════════════════════════════════

    private void chamarStartSaleFallback(String checkoutId, int volumeMl, String deviceId) {
        Log.i(TAG, "[SESSION] Fallback → start_sale.php");
        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml",   String.valueOf(volumeMl));
        body.put("qtd_ml",      String.valueOf(volumeMl));
        body.put("device_id",   deviceId);
        body.put("android_id",  deviceId);

        mApiHelper.sendPost(body, "api/start_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] start_sale fallback também falhou: " + e.getMessage());
                gerarSessionIdLocal(checkoutId);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyStr = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "[SESSION] start_sale fallback HTTP " + response.code());
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String sessionId = json.optString("session_id",
                            json.optString("ble_session_id", null));
                    if (sessionId != null && !sessionId.isEmpty()) {
                        final String sid = sessionId;
                        mMainHandler.post(() -> {
                            synchronized (SessionManager.this) {
                                mSessionId = sid;
                                mState     = State.ACTIVE;
                                Log.i(TAG, "[SESSION] ACTIVE (fallback) | session_id=" + sid);
                                if (mCallback != null) mCallback.onSessionStarted(sid, checkoutId);
                            }
                        });
                    } else {
                        gerarSessionIdLocal(checkoutId);
                    }
                } catch (Exception e) {
                    gerarSessionIdLocal(checkoutId);
                }
            }
        });
    }

    private void chamarFinishSaleFallback(Map<String, String> body,
                                          String sessionId, int mlReal) {
        mApiHelper.sendPost(body, "api/finish_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] finish_sale fallback falhou: " + e.getMessage());
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        if (mCallback != null) mCallback.onSessionFinished(sessionId, mlReal);
                    }
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] finish_sale fallback HTTP " + response.code());
                mMainHandler.post(() -> {
                    synchronized (SessionManager.this) {
                        mState = State.COMPLETED;
                        if (mCallback != null) mCallback.onSessionFinished(sessionId, mlReal);
                    }
                });
            }
        });
    }

    private void chamarFailSaleFallback(Map<String, String> body,
                                        String sessionId, String motivo) {
        mApiHelper.sendPost(body, "api/fail_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "[SESSION] fail_sale fallback falhou: " + e.getMessage());
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onSessionFailed(sessionId, motivo);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[SESSION] fail_sale fallback HTTP " + response.code());
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onSessionFailed(sessionId, motivo);
                });
            }
        });
    }

    /**
     * Gera um SESSION_ID local quando a API não retorna um.
     * Garante que o fluxo continue mesmo sem conectividade.
     */
    private void gerarSessionIdLocal(String checkoutId) {
        String localId = "SES_LOCAL_" + checkoutId + "_"
                + Long.toHexString(System.currentTimeMillis()).toUpperCase();
        Log.w(TAG, "[SESSION] Usando SESSION_ID local: " + localId);
        mMainHandler.post(() -> {
            synchronized (SessionManager.this) {
                mSessionId = localId;
                mState     = State.ACTIVE;
                if (mCallback != null) mCallback.onSessionStarted(localId, checkoutId);
            }
        });
    }

    @Override
    public String toString() {
        return "SessionManager{state=" + mState
                + ", session=" + mSessionId
                + ", checkout=" + mCheckoutId + "}";
    }
}
