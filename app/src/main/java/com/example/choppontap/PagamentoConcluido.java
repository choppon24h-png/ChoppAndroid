package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * PagamentoConcluido — Tela de liberação do chopp após pagamento confirmado.
 *
 * ═══════════════════════════════════════════════════════════════════
 * FLUXO COM FILA DE COMANDOS BLE (v2.3)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   1. Activity inicia → bind no BluetoothService
 *   2. BluetoothService conecta GATT → AUTH:OK → READY → ACTION_WRITE_READY
 *   3. PagamentoConcluido recebe WRITE_READY → chama start_sale.php
 *   4. Após start_sale OK → enfileira BleCommand SERVE via CommandQueueManager
 *   5. CommandQueueManager envia $ML:<N>:<cmdId> → aguarda ML:ACK (10s)
 *   6. ESP32 responde ML:ACK → BluetoothService roteia para CommandQueueManager
 *   7. CommandQueueManager aguarda DONE (60s)
 *   8. ESP32 envia DONE → BluetoothService roteia → CommandQueueManager.onDone()
 *   9. BluetoothService emite QUEUE:DONE:<cmdId>:<ml> via broadcast
 *  10. PagamentoConcluido recebe QUEUE:DONE → chama finish_sale.php → navega Home
 *
 * Em caso de falha (timeout ACK, timeout DONE, ERROR:BUSY):
 *   - CommandQueueManager faz retry automático (3x com backoff)
 *   - Após 3 falhas → emite QUEUE:ERROR → PagamentoConcluido chama fail_sale.php
 *
 * ═══════════════════════════════════════════════════════════════════
 * CORREÇÕES MANTIDAS
 * ═══════════════════════════════════════════════════════════════════
 *
 *   [FIX-1] Delay de 800ms antes de enviar $ML após READY
 *   [FIX-2] Reconexão BLE sem perder estado — CommandQueueManager preserva cmd ativo
 *   [FIX-3] Botão "Continuar servindo" exibido em dosagem incompleta
 *   [FIX-4] Imagem da bebida com fallback banco → URL
 *   [FIX-5] Fechamento da válvula ao terminar (DONE)
 *   [FIX-6] Retorno para Home após dosagem completa
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Timeouts e delays ─────────────────────────────────────────────────────
    /** Delay de segurança antes de enfileirar $ML após READY (FIX-1) */
    private static final long ML_SEND_DELAY_MS       = 800L;
    /** Delay antes de navegar para Home após dosagem completa (FIX-6) */
    private static final long HOME_NAVIGATE_DELAY_MS = 3_000L;
    /** Watchdog: se VP: não chegar em 30s após DONE, algo errou */
    private static final long WATCHDOG_TIMEOUT_MS    = 30_000L;

    // ── Handlers ──────────────────────────────────────────────────────────────
    private final Handler mMainHandler    = new Handler(Looper.getMainLooper());
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());

    // ── Estado da liberação ───────────────────────────────────────────────────
    private int     qtd_ml               = 0;
    private int     liberado             = 0;
    private int     totalPulsos          = 0;
    private boolean mValvulaAberta       = false;
    private boolean mLiberacaoFinalizada = false;
    private boolean mWatchdogActive      = false;

    /**
     * Protege contra enfileiramento duplicado de comandos.
     * Zerado apenas quando:
     *   a) DONE recebido com sucesso
     *   b) QUEUE:ERROR recebido (falha irrecuperável)
     *   c) Usuário pressiona "Continuar servindo" explicitamente
     */
    private boolean mComandoEnviado = false;

    /** ID do BleCommand ativo — para correlacionar QUEUE:DONE e QUEUE:ERROR */
    private String mActiveCommandId = null;
    /** SESSION_ID do BleCommand ativo — enviado às APIs ERP */
    private String mActiveSessionId = null;

    // ── Dados do pedido ───────────────────────────────────────────────────────
    private String checkout_id;
    private String android_id;
    private String imagemUrl;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView    txtQtd;
    private TextView    txtMls;
    private TextView    txtStatus;
    private Button      btnLiberar;
    private ImageView   imageView;
    private ProgressBar progressBar;

    // ── Carregamento de imagem ────────────────────────────────────────────────
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Future<?> currentImageTask = null;

    // ── Bluetooth ────────────────────────────────────────────────────────────────────────────────
    private BluetoothServiceIndustrial mBluetoothService;
    private boolean          mIsServiceBound = false;
    // ── SessionManager v2.3 — anti-fraude com start_session/finish_session ─────────────────
    private SessionManager   mSessionManager;

    // ════════════════════════════════════════════════════════════════════════════════
    // Watchdog
    // ═════════════════════════════════════════════════════════════════════════

    private final Runnable mWatchdogRunnable = () -> {
        Log.e(TAG, "[APP] WATCHDOG disparado! Fluxo não detectado em "
                + (WATCHDOG_TIMEOUT_MS / 1000) + "s");
        mWatchdogActive  = false;
        mValvulaAberta   = false;
        atualizarStatus("⏱ Timeout: fluxo não detectado. Válvula fechada.");
        if (mBluetoothService != null && mBluetoothService.isReady()) {
            Log.w(TAG, "[BLE] Enviando $ML:0 para fechar válvula por timeout");
            mBluetoothService.write("$ML:0");
        }
        runOnUiThread(() -> {
            if (liberado < qtd_ml) {
                int restante = qtd_ml - liberado;
                btnLiberar.setText("Tentar novamente (" + restante + "ml)");
                btnLiberar.setVisibility(View.VISIBLE);
                mLiberacaoFinalizada = false;
            }
            mostrarSnackbar("Tempo esgotado. Verifique o sensor de fluxo.");
        });
    };

    // ═════════════════════════════════════════════════════════════════════════
    // BroadcastReceiver — mensagens do BluetoothService
    // ═════════════════════════════════════════════════════════════════════════

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {

                // ─────────────────────────────────────────────────────────────
                // BLE READY: canal autenticado — enfileirar comando
                // ─────────────────────────────────────────────────────────────
                case BluetoothServiceIndustrial.ACTION_WRITE_READY:
                    Log.i(TAG, "[BLE] ACTION_WRITE_READY — canal autenticado (READY). "
                            + "Aguardando " + ML_SEND_DELAY_MS + "ms antes de enfileirar $ML.");
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");

                    mMainHandler.postDelayed(() -> {
                        if (mComandoEnviado) {
                            // Reconexão após queda: CommandQueueManager já preservou o comando ativo
                            // e chamará onBleReady() automaticamente para reenvio
                            Log.i(TAG, "[QUEUE] Reconexão detectada — CommandQueueManager retomará fila automaticamente");
                        } else {
                            // Primeira vez: chamar start_sale e depois enfileirar
                            iniciarVendaEEnfileirar();
                        }
                    }, ML_SEND_DELAY_MS);
                    break;

                // ─────────────────────────────────────────────────────────────
                // STATUS DE CONEXÃO
                // ─────────────────────────────────────────────────────────────
                case BluetoothServiceIndustrial.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothServiceIndustrial.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        Log.w(TAG, "[BLE] Dispositivo DESCONECTADO durante liberação");
                        atualizarStatus("🔄 Reconectando ao dispositivo...");
                        cancelarWatchdog();
                        // CommandQueueManager.onBleDisconnected() já foi chamado pelo BluetoothService
                        runOnUiThread(() -> {
                            if (liberado > 0 && liberado < qtd_ml && !mLiberacaoFinalizada) {
                                int restante = qtd_ml - liberado;
                                btnLiberar.setText("Aguardando reconexão... (" + restante + "ml restantes)");
                                btnLiberar.setEnabled(false);
                                btnLiberar.setVisibility(View.VISIBLE);
                            }
                        });
                    } else if ("connected".equals(status)) {
                        Log.i(TAG, "[BLE] Conectado — aguardando autenticação BLE (AUTH:OK)...");
                        atualizarStatus("⏳ Autenticando dispositivo...");
                        runOnUiThread(() -> btnLiberar.setEnabled(true));
                    }
                    break;

                // ─────────────────────────────────────────────────────────────
                // ESTADO BLE MUDOU
                // ─────────────────────────────────────────────────────────────
                case BluetoothServiceIndustrial.ACTION_BLE_STATE_CHANGED:
                    String stateName = intent.getStringExtra(BluetoothServiceIndustrial.EXTRA_BLE_STATE);
                    Log.d(TAG, "[BLE] Estado BLE: " + stateName);
                    break;

                // ─────────────────────────────────────────────────────────────
                // DADOS DO ESP32 / FILA
                // ─────────────────────────────────────────────────────────────
                case BluetoothServiceIndustrial.ACTION_DATA_AVAILABLE:
                    String data = intent.getStringExtra(BluetoothServiceIndustrial.EXTRA_DATA);
                    if (data != null) processarMensagem(data.trim());
                    break;
            }
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Processamento de mensagens (ESP32 direto + fila)
    // ═════════════════════════════════════════════════════════════════════════

    private void processarMensagem(String msg) {
        Log.d(TAG, "[ESP32→Android] " + msg);

        // ── Mensagens da fila de comandos (prefixo QUEUE:) ────────────────────
        if (msg.startsWith("QUEUE:")) {
            processarMensagemFila(msg);
            return;
        }

        // ── AUTH:OK — apenas informativo (ACTION_WRITE_READY já cuida do fluxo) ─
        if ("AUTH:OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] AUTH:OK — dispositivo autenticado e pronto");
            atualizarStatus("✓ Dispositivo autenticado");
            return;
        }

        // ── AUTH:FAIL ─────────────────────────────────────────────────────────
        if ("AUTH:FAIL".equalsIgnoreCase(msg)) {
            Log.w(TAG, "[BLE] AUTH:FAIL recebido — aguardando nova tentativa automática");
            atualizarStatus("⚠ Falha de autenticação. Reconectando...");
            return;
        }

        // ── VP:<float> — progresso parcial de dispensação ────────────────────
        if (msg.startsWith("VP:")) {
            resetarWatchdog();
            try {
                double mlFloat = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFloat);
                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    if (progressBar != null && qtd_ml > 0) {
                        int progresso = (int) ((liberado / (float) qtd_ml) * 100);
                        progressBar.setProgress(Math.min(progresso, 100));
                    }
                    btnLiberar.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                Log.e(TAG, "[APP] Erro ao parsear VP: " + e.getMessage());
            }
            return;
        }

        // ── QP:<int> — total de pulsos do sensor de fluxo ────────────────────
        if (msg.startsWith("QP:")) {
            try {
                totalPulsos = Integer.parseInt(msg.substring(3).trim());
                Log.i(TAG, "[APP] QP: total de pulsos=" + totalPulsos);
            } catch (Exception ignored) {}
            return;
        }

        // ── VALVE:OPEN — válvula aberta (informativo) ─────────────────────────
        if ("VALVE:OPEN".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] VALVE:OPEN — válvula aberta. Iniciando watchdog.");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
            runOnUiThread(() -> btnLiberar.setVisibility(View.GONE));
            iniciarWatchdog();
            return;
        }

        // ── OK — válvula aberta (protocolo legado) ────────────────────────────
        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] OK — válvula ABERTA (legado). Iniciando watchdog.");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
            runOnUiThread(() -> btnLiberar.setVisibility(View.GONE));
            iniciarWatchdog();
            return;
        }

        // ── ML: ou ML:<valor> — válvula fechada (protocolo legado) ───────────
        // NOTA: com a fila, o fluxo principal usa DONE. ML: é tratado como fallback.
        if (msg.startsWith("ML:") || "ML".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] ML recebido (legado) — válvula FECHADA. liberado=" + liberado + "ml");
            cancelarWatchdog();
            mValvulaAberta  = false;

            if (msg.startsWith("ML:") && msg.length() > 3) {
                try {
                    double mlFinal = Double.parseDouble(msg.substring(3).trim());
                    if (mlFinal > 0) liberado = (int) Math.round(mlFinal);
                } catch (Exception ignored) {}
            }

            // Se a fila não finalizou ainda, tratar como DONE
            if (!mLiberacaoFinalizada) {
                mLiberacaoFinalizada = true;
                mComandoEnviado = false;
                Log.i(TAG, "[PAYMENT] ML legado → finalizando sessão (liberado=" + liberado + "ml)");
                if (mSessionManager != null && mSessionManager.isActive()) {
                    mSessionManager.finishSession(liberado, totalPulsos);
                }
                chamarFinishSale(liberado);
            }
            return;
        }

        // ── ERROR:NOT_AUTHENTICATED ───────────────────────────────────────────
        if (msg.contains("ERROR:NOT_AUTHEN")) {
            cancelarWatchdog();
            Log.e(TAG, "[BLE] ERROR:NOT_AUTHENTICATED recebido do ESP32");
            atualizarStatus("🔑 Reautenticando dispositivo...");
            mComandoEnviado = false;

            if (mBluetoothService != null) {
                android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                if (dev != null) BluetoothServiceIndustrial.removeBond(dev);
                mBluetoothService.disconnect();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mBluetoothService != null) {
                        mBluetoothService.enableAutoReconnect();
                        mBluetoothService.scanLeDevice(true);
                    }
                }, 2000);
            }
            return;
        }

        // ── ERRO genérico ─────────────────────────────────────────────────────
        if ("ERRO".equalsIgnoreCase(msg) || msg.startsWith("ERRO")) {
            cancelarWatchdog();
            Log.e(TAG, "[BLE] Erro reportado pelo ESP32: " + msg);
            atualizarStatus("❌ Erro no dispositivo.");
            runOnUiThread(() -> mostrarSnackbar("Erro no dispositivo de chopp."));
        }
    }

    /**
     * Processa mensagens da fila de comandos (prefixo QUEUE:).
     *
     * Formatos:
     *   QUEUE:ACK:<cmdId>
     *   QUEUE:DONE:<cmdId>:<mlReal>
     *   QUEUE:ERROR:<cmdId>:<motivo>
     */
    private void processarMensagemFila(String msg) {
        String[] parts = msg.split(":", 4);
        if (parts.length < 2) return;

        String tipo = parts[1]; // ACK, DONE, ERROR

        switch (tipo) {
            case "ACK":
                // ── QUEUE:ACK:<cmdId> ─────────────────────────────────────────
                String ackId = parts.length >= 3 ? parts[2] : "?";
                Log.i(TAG, "[QUEUE] ACK recebido para cmdId=" + ackId);
                atualizarStatus("🍺 Servindo... (ACK confirmado)");
                // Inicia watchdog — se VP: não chegar, algo errou
                iniciarWatchdog();
                break;

            case "DONE":
                // ── QUEUE:DONE:<cmdId>:<mlReal> ───────────────────────────────
                String doneId = parts.length >= 3 ? parts[2] : "?";
                int mlReal = 0;
                if (parts.length >= 4) {
                    try { mlReal = Integer.parseInt(parts[3]); } catch (Exception ignored) {}
                }
                Log.i(TAG, "[QUEUE] DONE recebido — cmdId=" + doneId + " | ml_real=" + mlReal);
                cancelarWatchdog();
                mValvulaAberta  = false;
                mComandoEnviado = false;

                // Atualiza ml liberado com o valor real do ESP32 (se disponível)
                if (mlReal > 0) liberado = mlReal;

                if (!mLiberacaoFinalizada) {
                    mLiberacaoFinalizada = true;
                    Log.i(TAG, "[PAYMENT] DONE → finalizando sessão (liberado=" + liberado + "ml)");
                    // Usa SessionManager v2.3 se disponível
                    if (mSessionManager != null && mSessionManager.isActive()) {
                        mSessionManager.finishSession(liberado, totalPulsos);
                    }
                    // Sempre chama finish_sale legado para compatibilidade
                    chamarFinishSale(liberado);
                }

                // Atualiza UI
                final int liberadoFinal = liberado;
                runOnUiThread(() -> {
                    txtMls.setText(liberadoFinal + " ML");
                    if (progressBar != null && qtd_ml > 0) {
                        int progresso = (int) ((liberadoFinal / (float) qtd_ml) * 100);
                        progressBar.setProgress(Math.min(progresso, 100));
                    }

                    if (liberadoFinal < qtd_ml) {
                        // Dosagem incompleta — mostrar botão "Continuar servindo"
                        int restante = qtd_ml - liberadoFinal;
                        Log.i(TAG, "[APP] Dosagem incompleta: " + liberadoFinal + "ml de " + qtd_ml
                                + "ml. Exibindo botão 'Continuar servindo (" + restante + "ml)'");
                        atualizarStatus("⚠ Fluxo interrompido. " + restante + "ml restantes.");
                        btnLiberar.setText("Continuar servindo (" + restante + "ml)");
                        btnLiberar.setEnabled(true);
                        btnLiberar.setVisibility(View.VISIBLE);
                        mLiberacaoFinalizada = false;
                    } else {
                        // FIX-6: dosagem completa → navegar para Home após 3s
                        Log.i(TAG, "[APP] Dosagem completa! Navegando para Home em "
                                + HOME_NAVIGATE_DELAY_MS / 1000 + "s...");
                        atualizarStatus("✓ Dosagem completa! Obrigado!");
                        btnLiberar.setVisibility(View.GONE);

                        mMainHandler.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Log.i(TAG, "[APP] Navegando para Home.java");
                                // Reseta SessionManager antes de sair
                                if (mSessionManager != null) mSessionManager.reset();
                                Intent intent = new Intent(PagamentoConcluido.this, Home.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                startActivity(intent);
                                finish();
                            }
                        }, HOME_NAVIGATE_DELAY_MS);
                    }
                });
                break;

            case "ERROR":
                // ── QUEUE:ERROR:<cmdId>:<motivo> ──────────────────────────────
                String errorId     = parts.length >= 3 ? parts[2] : "?";
                String errorMotivo = parts.length >= 4 ? parts[3] : "desconhecido";
                Log.e(TAG, "[QUEUE] ERROR — cmdId=" + errorId + " | motivo=" + errorMotivo);
                cancelarWatchdog();
                mComandoEnviado = false;
                mLiberacaoFinalizada = true;

                // Registra falha via SessionManager v2.3 e legado
                Log.i(TAG, "[PAYMENT] QUEUE:ERROR → registrando falha na sessão");
                if (mSessionManager != null) {
                    mSessionManager.failSession(errorMotivo, liberado);
                }
                chamarFailSale(errorMotivo);

                atualizarStatus("❌ Falha na dispensação: " + errorMotivo);
                runOnUiThread(() -> {
                    int restante = qtd_ml - liberado;
                    if (restante > 0) {
                        btnLiberar.setText("Tentar novamente (" + restante + "ml)");
                        btnLiberar.setEnabled(true);
                        btnLiberar.setVisibility(View.VISIBLE);
                        mLiberacaoFinalizada = false;
                    }
                    mostrarSnackbar("Falha na dispensação. Tente novamente.");
                });
                break;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Fluxo principal: start_sale → enfileirar → DONE → finish_sale
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Inicia a sessão via SessionManager v2.3 e enfileira o BleCommand SERVE.
     * Chamado após ACTION_WRITE_READY (BLE está READY).
     *
     * Fluxo v2.3:
     *   1. SessionManager.startSession() → POST /api/start_session.php
     *   2. onSessionStarted() → enfileirarComandoServe()
     *   3. QUEUE:DONE → SessionManager.finishSession()
     *
     * Fluxo legado (fallback):
     *   1. chamarStartSale() → POST /api/start_sale.php
     *   2. enfileirarComandoServe()
     */
    private void iniciarVendaEEnfileirar() {
        // MUDANÇA 4: bloquear venda sem internet — NÃO enviar comando BLE
        if (!isInternetAvailable()) {
            Log.e(TAG, "[NET] Sem internet — venda bloqueada, comando BLE NÃO enviado");
            atualizarStatus("❌ Sem internet. Verifique sua rede.");
            runOnUiThread(() ->
                Toast.makeText(PagamentoConcluido.this,
                        "Sem conexão com a internet. Verifique sua rede.",
                        Toast.LENGTH_LONG).show()
            );
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — mComandoEnviado=true");
            return;
        }
        if (mBluetoothService == null || !mBluetoothService.isReady()) {
            Log.e(TAG, "[PAYMENT] iniciarVendaEEnfileirar() BLOQUEADO — BLE não está READY");
            return;
        }

        Log.i(TAG, "[PAYMENT] Iniciando venda v2.3 — checkout_id=" + checkout_id
                + " | qtd_ml=" + qtd_ml);

        // ── Usa SessionManager v2.3 se disponível ────────────────────────────────
        if (mSessionManager != null) {
            Log.i(TAG, "[SESSION] Iniciando sessão via SessionManager v2.3");
            mSessionManager.startSession(checkout_id, qtd_ml, android_id);
            // O enfileiramento ocorrerá no callback onSessionStarted()
        } else {
            // Fallback: fluxo legado com start_sale.php
            Log.w(TAG, "[PAYMENT] SessionManager não disponível — usando fluxo legado");
            chamarStartSale(checkout_id, qtd_ml, android_id, () -> enfileirarComandoServe(qtd_ml));
        }
    }

    /**
     * Verifica se há conexão com a internet disponível.
     * MUDANÇA 4: bloqueia início de venda sem rede.
     */
    private boolean isInternetAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            Log.e(TAG, "[NET] isInternetAvailable() erro: " + e.getMessage());
            return false;
        }
    }

    /**
     * Enfileira um BleCommand SERVE via CommandQueue v2.3 (getCommandQueueV2).
     * Deve ser chamado APÓS start_session/start_sale retornar sucesso.
     */
    private void enfileirarComandoServe(int volumeMl) {
        if (mBluetoothService == null) {
            Log.e(TAG, "[QUEUE] enfileirarComandoServe() — BluetoothService nulo!");
            return;
        }

        // MUDANÇA 3: usar CommandQueue v2.3 (getCommandQueueV2) — sem CommandQueueManager legado
        CommandQueue queue = mBluetoothService.getCommandQueueV2();
        if (queue == null) {
            Log.e(TAG, "[QUEUE] CommandQueue v2.3 nula — usando envio direto como fallback");
            mComandoEnviado = true;
            mBluetoothService.write("$ML:" + volumeMl);
            return;
        }

        mComandoEnviado = true;
        // CommandQueue v2.3: enqueueServe(volumeMl, sessionId)
        String sessionId = mActiveSessionId != null ? mActiveSessionId : "";
        BleCommand cmd = queue.enqueueServe(volumeMl, sessionId);
        if (cmd == null) {
            Log.e(TAG, "[QUEUE] Fila cheia (QUEUE:FULL) — comando SERVE rejeitado");
            atualizarStatus("⚠️ Fila cheia. Tente novamente.");
            return;
        }
        mActiveCommandId = cmd.commandId;
        // Usa session_id do SessionManager v2.3 se disponível, senão usa o do BleCommand
        if (mActiveSessionId == null) mActiveSessionId = cmd.sessionId;
        // Registra o command_id no SessionManager para envio ao ERP
        if (mSessionManager != null) mSessionManager.setCommandId(mActiveCommandId);

        Log.i(TAG, "[QUEUE] Comando enfileirado v2.3 — " + cmd
                + " | session_id=" + mActiveSessionId);
        Log.i(TAG, "[QUEUE] Aguardando ACK (2s) e DONE (15s)...");
        atualizarStatus("⏳ Aguardando abertura da válvula...");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ServiceConnection
    // ═════════════════════════════════════════════════════════════════════════

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothServiceIndustrial.LocalBinder) service).getService();
            mIsServiceBound   = true;

            // ── Inicializa SessionManager v2.3 ───────────────────────────────────────
            if (mSessionManager == null) {
                mSessionManager = new SessionManager(new SessionManager.Callback() {
                    @Override
                    public void onSessionStarted(String sessionId, String checkoutId) {
                        Log.i(TAG, "[SESSION] Sessão iniciada | session_id=" + sessionId);
                        mActiveSessionId = sessionId;
                        // Enfileira o comando BLE com o session_id da sessão
                        enfileirarComandoServe(qtd_ml);
                    }
                    @Override
                    public void onSessionFinished(String sessionId, int mlReal) {
                        Log.i(TAG, "[SESSION] Sessão finalizada | session_id=" + sessionId
                                + " | ml_real=" + mlReal);
                    }
                    @Override
                    public void onSessionFailed(String sessionId, String reason) {
                        Log.e(TAG, "[SESSION] Sessão falhou | session_id=" + sessionId
                                + " | motivo=" + reason);
                    }
                });
            }

            android.bluetooth.BluetoothDevice devDebug = mBluetoothService.getBoundDevice();
            String bondDebug = (devDebug != null)
                    ? (devDebug.getAddress() + " bondState="
                        + (devDebug.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED ? "BOND_BONDED" :
                           devDebug.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDING ? "BOND_BONDING" :
                           "BOND_NONE("+devDebug.getBondState()+")"))
                    : "sem device (GATT nulo)";
            Log.i(TAG, "[BLE] onServiceConnected:"
                    + " bleState=" + mBluetoothService.getBleState().name()
                    + " | isReady=" + mBluetoothService.isReady()
                    + " | connected=" + mBluetoothService.connected()
                    + " | " + bondDebug);

            if (mBluetoothService.isReady()) {
                // FIX-1: aguardar ML_SEND_DELAY_MS antes de enfileirar
                Log.i(TAG, "[BLE] → CAMINHO 1: já em READY. Aguardando " + ML_SEND_DELAY_MS
                        + "ms antes de enfileirar $ML (FIX-1).");
                atualizarStatus("✓ Dispositivo pronto. Liberando...");
                mMainHandler.postDelayed(() -> iniciarVendaEEnfileirar(), ML_SEND_DELAY_MS);

            } else if (mBluetoothService.connected()) {
                android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                int bondState = (dev != null) ? dev.getBondState() : -1;
                boolean jaBonded = (bondState == android.bluetooth.BluetoothDevice.BOND_BONDED);
                boolean bonding  = (bondState == android.bluetooth.BluetoothDevice.BOND_BONDING);

                Log.i(TAG, "[BLE] → CAMINHO 2: GATT conectado, estado=" + mBluetoothService.getBleState().name()
                        + " | bondState=" + bondState
                        + " | jaBonded=" + jaBonded
                        + " | bonding=" + bonding);

                if (jaBonded) {
                    Log.i(TAG, "[BLE] → CAMINHO 2A: BOND_BONDED → forceReady() → aguardar ACTION_WRITE_READY");
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");
                    mBluetoothService.forceReady();
                } else if (bonding) {
                    Log.i(TAG, "[BLE] → CAMINHO 2B: BOND_BONDING em andamento. Aguardando AUTH:OK.");
                    atualizarStatus("⏳ Autenticando dispositivo...");
                } else {
                    Log.i(TAG, "[BLE] → CAMINHO 2C: BOND_NONE. Aguardando AUTH:OK.");
                    atualizarStatus("⏳ Autenticando dispositivo...");
                }

            } else {
                Log.i(TAG, "[BLE] → CAMINHO 3: sem GATT. Iniciando scan/conexão.");
                atualizarStatus("⏳ Conectando ao dispositivo...");
                mBluetoothService.scanLeDevice(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound   = false;
            mBluetoothService = null;
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    // Ciclo de vida da Activity
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);
        setupFullscreen();

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        Bundle extras = getIntent().getExtras();
        if (extras == null) { finish(); return; }

        qtd_ml      = Integer.parseInt(extras.get("qtd_ml").toString());
        checkout_id = extras.get("checkout_id").toString();
        imagemUrl   = extras.containsKey("imagem_url") ? extras.getString("imagem_url") : null;

        Log.i(TAG, "[PAYMENT] PagamentoConcluido iniciado — qtd_ml=" + qtd_ml
                + " | checkout_id=" + checkout_id
                + " | imagemUrl=" + imagemUrl);

        btnLiberar  = findViewById(R.id.btnLiberarRestante);
        imageView   = findViewById(R.id.imageBeer2);
        txtQtd      = findViewById(R.id.txtQtdPulsos);
        txtMls      = findViewById(R.id.txtMls);
        txtStatus   = findViewById(R.id.txtStatusLiberacao);
        progressBar = findViewById(R.id.progressLiberacao);

        txtQtd.setText(qtd_ml + " ML");
        txtMls.setText("0 ML");
        atualizarStatus("⏳ Conectando ao dispositivo...");

        btnLiberar.setVisibility(View.GONE);

        if (progressBar != null) {
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        // FIX-4: carregar imagem — tenta banco local primeiro, depois URL
        carregarImagemComFallback();

        // Inicia e vincula o BluetoothService
        Log.i(TAG, "[BLE] Iniciando BluetoothService...");
        Intent serviceIntent = new Intent(this, BluetoothServiceIndustrial.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // Botão "Continuar servindo" — só disponível após interrupção parcial
        btnLiberar.setOnClickListener(v -> {
            if (mBluetoothService == null || !mBluetoothService.isReady()) {
                Log.e(TAG, "[BLE] Botão 'Continuar' pressionado mas BLE não está READY!");
                mostrarSnackbar("Aguarde a conexão com o dispositivo.");
                return;
            }
            int restante = qtd_ml - liberado;
            if (restante <= 0) return;
            Log.i(TAG, "[PAYMENT] Usuário solicitou liberação do restante: " + restante + "ml");
            btnLiberar.setVisibility(View.GONE);
            mLiberacaoFinalizada = false;
            mComandoEnviado      = false; // Autorizado explicitamente pelo usuário
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            // Re-inicia o fluxo com o volume restante
            iniciarVendaEEnfileirar();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                startActivity(new Intent(PagamentoConcluido.this, Home.class));
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothServiceIndustrial.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothServiceIndustrial.ACTION_DATA_AVAILABLE);
        filter.addAction(BluetoothServiceIndustrial.ACTION_WRITE_READY);
        filter.addAction(BluetoothServiceIndustrial.ACTION_BLE_STATE_CHANGED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelarWatchdog();
        mMainHandler.removeCallbacksAndMessages(null);
        if (currentImageTask != null) currentImageTask.cancel(true);
        imageExecutor.shutdown();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Chamadas à API de controle de vendas
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Registra o início da venda no ERP.
     * Endpoint: api/start_sale.php
     *
     * @param checkoutId  ID do checkout
     * @param volumeMl    Volume solicitado em ml
     * @param deviceId    android_id do tablet
     * @param onSuccess   Callback chamado em caso de sucesso (HTTP 200)
     */
    private void chamarStartSale(String checkoutId, int volumeMl, String deviceId,
                                  Runnable onSuccess) {
        Log.i(TAG, "[API] start_sale → checkout_id=" + checkoutId
                + " | volume_ml=" + volumeMl + " | device_id=" + deviceId
                + " | command_id=" + mActiveCommandId + " | session_id=" + mActiveSessionId);

        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkoutId);
        body.put("volume_ml",   String.valueOf(volumeMl));
        body.put("qtd_ml",      String.valueOf(volumeMl));
        body.put("device_id",   deviceId);
        body.put("android_id",  deviceId);
        if (mActiveCommandId != null) body.put("command_id", mActiveCommandId);
        if (mActiveSessionId != null) body.put("session_id", mActiveSessionId);

        new ApiHelper().sendPost(body, "api/start_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] start_sale FALHOU (rede): " + e.getMessage()
                        + " — prosseguindo com enfileiramento mesmo assim");
                // Não bloquear o fluxo por falha de rede — enfileirar mesmo assim
                if (onSuccess != null) {
                    mMainHandler.post(onSuccess);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String body2 = response.body() != null ? response.body().string() : "";
                response.close();
                Log.i(TAG, "[API] start_sale HTTP " + code + " | body=" + body2);

                // Enfileirar independente do código (200 ou erro de negócio)
                if (onSuccess != null) {
                    mMainHandler.post(onSuccess);
                }
            }
        });
    }

    /**
     * Finaliza a venda com sucesso no ERP.
     * Endpoint: api/finish_sale.php
     *
     * @param mlDispensado  Volume real dispensado (do ESP32)
     */
    private void chamarFinishSale(int mlDispensado) {
        Log.i(TAG, "[API] finish_sale → checkout_id=" + checkout_id
                + " | ml_dispensado=" + mlDispensado
                + " | total_pulsos=" + totalPulsos
                + " | command_id=" + mActiveCommandId + " | session_id=" + mActiveSessionId);

        Map<String, String> body = new HashMap<>();
        body.put("checkout_id",   checkout_id);
        body.put("ml_dispensado", String.valueOf(mlDispensado));
        body.put("ml_real",       String.valueOf(mlDispensado));
        body.put("total_pulsos",  String.valueOf(totalPulsos));
        body.put("android_id",    android_id);
        if (mActiveCommandId != null) body.put("command_id", mActiveCommandId);
        if (mActiveSessionId != null) body.put("session_id", mActiveSessionId);

        new ApiHelper().sendPost(body, "api/finish_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] finish_sale FALHOU (rede): " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String body2 = response.body() != null ? response.body().string() : "";
                response.close();
                Log.i(TAG, "[API] finish_sale HTTP " + code + " | body=" + body2);
            }
        });

        // Também chama a API legada de liberação finalizada para compatibilidade
        chamarLiberacaoFinalizada(String.valueOf(mlDispensado), checkout_id);
    }

    /**
     * Registra falha na venda no ERP.
     * Endpoint: api/fail_sale.php
     *
     * @param motivo  Descrição do erro
     */
    private void chamarFailSale(String motivo) {
        Log.i(TAG, "[API] fail_sale → checkout_id=" + checkout_id
                + " | motivo=" + motivo
                + " | ml_liberado=" + liberado
                + " | command_id=" + mActiveCommandId + " | session_id=" + mActiveSessionId);

        Map<String, String> body = new HashMap<>();
        body.put("checkout_id", checkout_id);
        body.put("motivo",      motivo);
        body.put("error_msg",   motivo);
        body.put("ml_liberado", String.valueOf(liberado));
        body.put("ml_parcial",  String.valueOf(liberado));
        body.put("android_id",  android_id);
        if (mActiveCommandId != null) body.put("command_id", mActiveCommandId);
        if (mActiveSessionId != null) body.put("session_id", mActiveSessionId);

        new ApiHelper().sendPost(body, "api/fail_sale.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] fail_sale FALHOU (rede): " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                int code = response.code();
                String body2 = response.body() != null ? response.body().string() : "";
                response.close();
                Log.i(TAG, "[API] fail_sale HTTP " + code + " | body=" + body2);
            }
        });
    }

    /** API legada — mantida para compatibilidade com liberacao.php */
    private void chamarLiberacaoFinalizada(String volume, String checkoutId) {
        Log.i(TAG, "[API] liberacao finalizada (legado): " + volume + "ml | checkout=" + checkoutId);
        Map<String, String> body = new HashMap<>();
        body.put("android_id",   android_id);
        body.put("qtd_ml",       volume);
        body.put("checkout_id",  checkoutId);
        body.put("total_pulsos", String.valueOf(totalPulsos));
        new ApiHelper().sendPost(body, "liberacao.php?action=finalizada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] liberacao finalizada (legado) FALHOU: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[API] liberacao finalizada (legado) HTTP " + response.code());
                response.close();
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Carregamento de imagem (FIX-4)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * FIX-4: Carrega a imagem da bebida com fallback em duas etapas:
     *   1. Tenta carregar do banco SQLite local (getActiveImageData)
     *   2. Se null/vazio, baixa da URL via ApiHelper em background thread
     */
    private void carregarImagemComFallback() {
        Sqlite banco = new Sqlite(getApplicationContext());
        byte[] img = banco.getActiveImageData();
        if (img != null && img.length > 0) {
            Log.i(TAG, "[IMG] Imagem carregada do banco local (" + img.length + " bytes)");
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            if (bmp != null && imageView != null) {
                imageView.setImageBitmap(bmp);
                return;
            }
        }

        if (imagemUrl == null || imagemUrl.isEmpty()) {
            Log.w(TAG, "[IMG] Banco local vazio e URL não disponível — imagem não carregada");
            return;
        }

        Log.i(TAG, "[IMG] Banco local vazio — baixando imagem da URL: " + imagemUrl);
        if (currentImageTask != null && !currentImageTask.isDone()) {
            currentImageTask.cancel(true);
        }

        final String urlFinal = imagemUrl;
        currentImageTask = imageExecutor.submit(() -> {
            try {
                Tap tempTap = new Tap();
                tempTap.image = urlFinal;
                Bitmap bmp = new ApiHelper().getImage(tempTap);
                if (bmp != null) {
                    Log.i(TAG, "[IMG] Imagem baixada com sucesso da URL");
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && imageView != null) {
                            imageView.setImageBitmap(bmp);
                        }
                    });
                } else {
                    Log.w(TAG, "[IMG] getImage retornou null para URL: " + urlFinal);
                }
            } catch (Exception e) {
                Log.e(TAG, "[IMG] Erro ao baixar imagem da URL: " + e.getMessage());
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Watchdog
    // ═════════════════════════════════════════════════════════════════════════

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        Log.d(TAG, "[APP] Watchdog iniciado (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
    }

    private void resetarWatchdog() {
        if (mWatchdogActive) {
            mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
            mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        }
    }

    private void cancelarWatchdog() {
        mWatchdogActive = false;
        mWatchdogHandler.removeCallbacks(mWatchdogRunnable);
        Log.d(TAG, "[APP] Watchdog cancelado");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void atualizarStatus(String msg) {
        runOnUiThread(() -> { if (txtStatus != null) txtStatus.setText(msg); });
    }

    private void mostrarSnackbar(String msg) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (root != null) Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }
}
