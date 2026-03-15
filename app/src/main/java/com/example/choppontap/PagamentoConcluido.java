package com.example.choppontap;

import android.content.BroadcastReceiver;
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
 * FLUXO BLE CORRETO (máquina de estados):
 *
 *   DISCONNECTED → CONNECTED → READY → SEND_ML
 *
 *   1. Activity inicia → bind no BluetoothService
 *   2. BluetoothService conecta GATT → descobre serviços NUS → aguarda AUTH:OK
 *   3. ESP32 conclui pareamento BLE (PIN 259087) → envia AUTH:OK
 *   4. BluetoothService recebe AUTH:OK → estado READY → emite ACTION_WRITE_READY
 *   5. PagamentoConcluido recebe ACTION_WRITE_READY → envia $ML:<N>
 *   6. ESP32 abre válvula → envia OK → VP: → ML:
 *
 * CORREÇÕES APLICADAS (2026-03-10):
 *   [FIX-1] Válvula abrindo/fechando ao entrar na tela:
 *           Causa: ao entrar na tela com BLE já em READY (bond existente),
 *           o onServiceConnected() enviava $ML imediatamente. Porém o ESP32
 *           ainda estava processando a reconexão e abria/fechava a válvula
 *           rapidamente. Correção: aguardar 800ms após READY antes de enviar $ML.
 *
 *   [FIX-2] Reconexão BLE sem perder estado:
 *           Causa: ao desconectar, mComandoEnviado=true bloqueava o reenvio,
 *           mas a tela não mostrava label de "Reconectando". Correção: exibir
 *           label "Reconectando..." e ao receber ACTION_WRITE_READY após
 *           reconexão, verificar se liberado < qtd_ml para reenviar o restante.
 *
 *   [FIX-3] Botão "Continuar servindo" não aparecia:
 *           Causa: o botão era exibido no processarMensagemESP32() (ML:), mas
 *           o código na linha 246 tinha um `if (progressBar != null)` incompleto
 *           que cortava a execução antes de chegar no bloco que exibe o botão.
 *           Correção: reorganizar o bloco runOnUiThread dentro de ML: para
 *           garantir que o botão seja exibido quando liberado < qtd_ml.
 *
 *   [FIX-4] Imagem da bebida não carregava:
 *           Causa: a imagem era carregada via Sqlite.getActiveImageData() (bytes
 *           do banco local), mas o banco pode estar vazio na primeira execução.
 *           Correção: tentar banco local primeiro; se null, baixar da URL via
 *           ApiHelper.getImage() em background thread.
 *
 *   [FIX-5] Fechamento da válvula ao terminar:
 *           Confirmado: o ESP32 envia ML: quando a válvula fecha. O Android
 *           já trata corretamente em processarMensagemESP32(). Adicionado log
 *           explícito e garantia de que sendRequestFim() é chamado.
 *
 *   [FIX-6] Retorno para Home após servir:
 *           Causa: após ML: (dosagem completa), a tela ficava parada sem
 *           navegar para Home. Correção: após 3s de exibir "Dosagem completa!",
 *           navegar automaticamente para Home.java.
 *
 * NUNCA enviar $ML antes de ACTION_WRITE_READY.
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Watchdog ──────────────────────────────────────────────────────────────
    private static final long WATCHDOG_TIMEOUT_MS   = 30_000L;
    /** Delay de segurança antes de enviar $ML após READY (FIX-1) */
    private static final long ML_SEND_DELAY_MS      = 800L;
    /** Delay antes de navegar para Home após dosagem completa (FIX-6) */
    private static final long HOME_NAVIGATE_DELAY_MS = 3_000L;

    // ── DIAGNÓSTICO: Timeout de segurança BLE após envio de $ML ──────────────
    // Se o ESP32 não responder com OK em BLE_RESPONSE_TIMEOUT_MS após o $ML,
    // o sistema tenta reenviar automaticamente (até BLE_MAX_RETRIES vezes).
    private static final long BLE_RESPONSE_TIMEOUT_MS = 5_000L;  // 5s
    private static final int  BLE_MAX_RETRIES         = 3;
    private int  mBleRetryCount  = 0;
    private Runnable mBleResponseTimeoutRunnable = null;

    private final Handler  mWatchdogHandler  = new Handler(Looper.getMainLooper());
    private final Handler  mMainHandler      = new Handler(Looper.getMainLooper());
    private boolean        mWatchdogActive   = false;

    // ── Estado da liberação ───────────────────────────────────────────────────
    private int     qtd_ml              = 0;
    private int     liberado            = 0;
    private int     mlsSolicitado       = 0;
    private int     totalPulsos         = 0;
    private boolean mValvulaAberta      = false;
    private boolean mLiberacaoFinalizada = false;

    /**
     * Protege contra envio duplicado de $ML.
     * Zerado apenas quando:
     *   a) A liberação é concluída (ML: recebido do ESP32)
     *   b) O usuário pressiona "Continuar servindo" explicitamente
     *   c) ERROR:NOT_AUTHENTICATED é recebido (reautenticação necessária)
     */
    private boolean mComandoEnviado = false;

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

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private BluetoothService mBluetoothService;
    private boolean          mIsServiceBound = false;

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // BroadcastReceiver — mensagens do BluetoothService
    // ─────────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {

                case BluetoothService.ACTION_WRITE_READY:
                    // ─────────────────────────────────────────────────────────
                    // Este broadcast só chega APÓS o ESP32 enviar AUTH:OK,
                    // ou seja, o canal está autenticado e pronto.
                    //
                    // FIX-1: aguardar ML_SEND_DELAY_MS antes de enviar $ML
                    // para evitar que a válvula abra/feche rapidamente ao
                    // entrar na tela (race condition entre READY e ESP32 pronto).
                    //
                    // FIX-2: se houve reconexão e já liberou parte, enviar
                    // apenas o restante (qtd_ml - liberado).
                    // ─────────────────────────────────────────────────────────
                    Log.i(TAG, "[BLE] ACTION_WRITE_READY recebido — canal autenticado (READY). "
                            + "Aguardando " + ML_SEND_DELAY_MS + "ms antes de enviar $ML.");
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");

                    mMainHandler.postDelayed(() -> {
                        if (mComandoEnviado) {
                            // Reconexão após queda: já enviou antes, reenviar restante
                            int restante = qtd_ml - liberado;
                            if (restante > 0 && !mLiberacaoFinalizada) {
                                Log.i(TAG, "[BLE] Reconexão detectada — reenviando restante: "
                                        + restante + "ml (liberado=" + liberado + "ml)");
                                mComandoEnviado = false; // Permite reenvio do restante
                                enviarComandoML(restante);
                            } else {
                                Log.i(TAG, "[BLE] DUPLICAÇÃO BLOQUEADA: $ML já foi enviado "
                                        + "e liberado=" + liberado + "ml >= qtd_ml=" + qtd_ml + "ml");
                            }
                        } else {
                            enviarComandoML(qtd_ml);
                        }
                    }, ML_SEND_DELAY_MS);
                    break;

                case BluetoothService.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        // FIX-2: exibir label "Reconectando..." ao desconectar
                        Log.w(TAG, "[BLE] Dispositivo DESCONECTADO durante liberação");
                        atualizarStatus("🔄 Reconectando ao dispositivo...");
                        cancelarWatchdog();
                        // BluetoothService fará a reconexão automaticamente (mAutoReconnect=true).
                        // Quando reconectar e AUTH:OK chegar, ACTION_WRITE_READY será emitido
                        // novamente — o bloco acima verificará liberado < qtd_ml.
                        runOnUiThread(() -> {
                            // Mostrar botão "Continuar servindo" durante reconexão
                            // para que o usuário saiba que pode retomar
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
                        // Reabilitar botão se estava desabilitado durante reconexão
                        runOnUiThread(() -> btnLiberar.setEnabled(true));
                    }
                    break;

                case BluetoothService.ACTION_BLE_STATE_CHANGED:
                    String stateName = intent.getStringExtra(BluetoothService.EXTRA_BLE_STATE);
                    Log.d(TAG, "[BLE] Estado BLE: " + stateName);
                    break;

                case BluetoothService.ACTION_DATA_AVAILABLE:
                    String data = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                    if (data != null) processarMensagemESP32(data.trim());
                    break;
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Processamento de mensagens do ESP32
    // ─────────────────────────────────────────────────────────────────────────

    private void processarMensagemESP32(String msg) {
        Log.d(TAG, "[ESP32→Android] " + msg);

        // AUTH:OK — ESP32 concluiu o pareamento. BluetoothService já transitou
        // para READY e emitiu ACTION_WRITE_READY. Apenas atualizamos o status aqui.
        if ("AUTH:OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] AUTH:OK — dispositivo autenticado e pronto");
            atualizarStatus("✓ Dispositivo autenticado");
            // $ML será enviado via ACTION_WRITE_READY (já tratado acima)
            return;
        }

        // AUTH:FAIL — falha de autenticação, logar mas não travar o fluxo
        if ("AUTH:FAIL".equalsIgnoreCase(msg)) {
            Log.w(TAG, "[BLE] AUTH:FAIL recebido — aguardando nova tentativa automática");
            atualizarStatus("⚠ Falha de autenticação. Reconectando...");
            return;
        }

        // OK — válvula aberta
        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] OK — válvula ABERTA. Iniciando watchdog (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
            // ── DIAGNÓSTICO: cancela timeout de resposta BLE (OK recebido com sucesso) ──
            cancelarTimeoutRespostaBLE();
            mBleRetryCount = 0;
            Log.i(TAG, "[DIAG] OK recebido — timeout BLE cancelado, retry zerado");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
            // Esconder botão "Continuar servindo" enquanto está servindo
            runOnUiThread(() -> btnLiberar.setVisibility(View.GONE));
            iniciarWatchdog();
            return;
        }

        // VP:<float> — volume parcial (ml servidos até agora)
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
                    // Esconder botão enquanto está servindo ativamente
                    btnLiberar.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                Log.e(TAG, "[APP] Erro ao parsear VP: " + e.getMessage());
            }
            return;
        }

        // QP:<int> — total de pulsos do sensor de fluxo
        if (msg.startsWith("QP:")) {
            try {
                totalPulsos = Integer.parseInt(msg.substring(3).trim());
                Log.i(TAG, "[APP] QP: total de pulsos=" + totalPulsos);
            } catch (Exception ignored) {}
            return;
        }

        // ML: ou ML:<valor> — válvula fechada, liberação concluída
        // FIX-3: reorganizado para garantir exibição do botão "Continuar servindo"
        // FIX-5: confirmado que válvula é fechada pelo ESP32 ao enviar ML:
        // FIX-6: navegar para Home após dosagem completa
        if (msg.startsWith("ML:") || "ML".equalsIgnoreCase(msg)) {
            cancelarWatchdog();
            mValvulaAberta  = false;
            mComandoEnviado = false; // Permite novo envio se usuário pressionar "Continuar"
            Log.i(TAG, "[BLE] " + msg + " — válvula FECHADA pelo ESP32. liberado=" + liberado + "ml de " + qtd_ml + "ml");
            Log.i(TAG, "[APP] Operação concluída — liberado=" + liberado + "ml de " + qtd_ml + "ml solicitados");

            // Tentar parsear o valor final de ML:<valor> se disponível
            if (msg.startsWith("ML:") && msg.length() > 3) {
                try {
                    double mlFinal = Double.parseDouble(msg.substring(3).trim());
                    if (mlFinal > 0) liberado = (int) Math.round(mlFinal);
                } catch (Exception ignored) {}
            }

            if (!mLiberacaoFinalizada) {
                mLiberacaoFinalizada = true;
                sendRequestFim(String.valueOf(liberado), checkout_id);
            }

            // FIX-3: bloco runOnUiThread completo e sem truncamento
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
                            Intent intent = new Intent(PagamentoConcluido.this, Home.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(intent);
                            finish();
                        }
                    }, HOME_NAVIGATE_DELAY_MS);
                }
            });
            return;
        }

        // ERROR:NOT_AUTHENTICATED — ESP32 recebeu $ML sem autenticação prévia.
        // Isso NÃO deveria ocorrer com a máquina de estados correta, mas tratamos
        // como fallback de segurança: remover bond e forçar novo pareamento.
        if (msg.contains("ERROR:NOT_AUTHEN")) {
            cancelarWatchdog();
            Log.e(TAG, "[BLE] ERROR:NOT_AUTHENTICATED recebido do ESP32");
            Log.e(TAG, "[BLE] ATENÇÃO: $ML foi enviado antes de AUTH:OK. Verifique a máquina de estados.");
            Log.e(TAG, "[BLE] Ação: removendo bond e reconectando para forçar novo pareamento com PIN 259087.");
            atualizarStatus("🔑 Reautenticando dispositivo...");
            mComandoEnviado = false; // Permite reenvio após nova autenticação

            if (mBluetoothService != null) {
                android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                if (dev != null) {
                    BluetoothService.removeBond(dev);
                    Log.i(TAG, "[BLE] Bond removido para " + dev.getAddress());
                }
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

        // ERRO — erro genérico do firmware
        if ("ERRO".equalsIgnoreCase(msg) || msg.startsWith("ERRO")) {
            cancelarWatchdog();
            Log.e(TAG, "[BLE] Erro reportado pelo ESP32: " + msg);
            atualizarStatus("❌ Erro no dispositivo.");
            runOnUiThread(() -> mostrarSnackbar("Erro no dispositivo de chopp."));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envio do comando $ML
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envia o comando $ML:<volumeMl> para o ESP32.
     *
     * PRÉ-CONDIÇÃO: só deve ser chamado após ACTION_WRITE_READY,
     * que garante que o estado BLE é READY (AUTH:OK já recebido).
     */
    // ─────────────────────────────────────────────────────────────────────
    // Timeout de segurança BLE: se ESP32 não responder com OK em 5s, reenviar
    // ─────────────────────────────────────────────────────────────────────────

    private void iniciarTimeoutRespostaBLE(int volumeMl) {
        cancelarTimeoutRespostaBLE();
        Log.d(TAG, "[DIAG] Iniciando timeout de resposta BLE (" + BLE_RESPONSE_TIMEOUT_MS / 1000 + "s) — aguardando OK do ESP32");
        mBleResponseTimeoutRunnable = () -> {
            if (mValvulaAberta || mLiberacaoFinalizada) {
                Log.d(TAG, "[DIAG] Timeout BLE cancelado — válvula já aberta ou liberação finalizada");
                return;
            }
            mBleRetryCount++;
            Log.e(TAG, "[DIAG] *** TIMEOUT BLE *** ESP32 não respondeu com OK em "
                    + BLE_RESPONSE_TIMEOUT_MS / 1000 + "s. Tentativa " + mBleRetryCount + "/" + BLE_MAX_RETRIES);

            // Diagnóstico do estado atual
            if (mBluetoothService != null) {
                Log.e(TAG, "[DIAG] Estado BLE: " + mBluetoothService.getBleState().name()
                        + " | isReady=" + mBluetoothService.isReady()
                        + " | connected=" + mBluetoothService.connected());
                android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                if (dev != null) {
                    Log.e(TAG, "[DIAG] Device: " + dev.getAddress()
                            + " | bond=" + dev.getBondState());
                }
            } else {
                Log.e(TAG, "[DIAG] BluetoothService é NULO — serviço desconectado!");
            }

            if (mBleRetryCount <= BLE_MAX_RETRIES) {
                if (mBluetoothService != null && mBluetoothService.isReady()) {
                    Log.w(TAG, "[DIAG] Reenviando $ML:" + volumeMl + " (retry " + mBleRetryCount + ")");
                    atualizarStatus("⚠ Sem resposta do ESP32. Reenviando comando... (" + mBleRetryCount + "/" + BLE_MAX_RETRIES + ")");
                    mComandoEnviado = false;
                    enviarComandoML(volumeMl);
                } else {
                    Log.e(TAG, "[DIAG] BLE não está READY para reenvio — aguardando reconexão");
                    atualizarStatus("⚠ Aguardando reconexão com o dispositivo...");
                    // O ACTION_WRITE_READY cuidará do reenvio quando reconectar
                }
            } else {
                Log.e(TAG, "[DIAG] Máximo de retries (" + BLE_MAX_RETRIES + ") atingido — exibindo botão manual");
                atualizarStatus("❌ Sem resposta do ESP32 após " + BLE_MAX_RETRIES + " tentativas.");
                runOnUiThread(() -> {
                    btnLiberar.setText("Tentar novamente (" + volumeMl + "ml)");
                    btnLiberar.setEnabled(true);
                    btnLiberar.setVisibility(View.VISIBLE);
                });
                mBleRetryCount = 0;
                mComandoEnviado = false;
            }
        };
        mMainHandler.postDelayed(mBleResponseTimeoutRunnable, BLE_RESPONSE_TIMEOUT_MS);
    }

    private void cancelarTimeoutRespostaBLE() {
        if (mBleResponseTimeoutRunnable != null) {
            mMainHandler.removeCallbacks(mBleResponseTimeoutRunnable);
            mBleResponseTimeoutRunnable = null;
            Log.d(TAG, "[DIAG] Timeout resposta BLE cancelado");
        }
    }

    private void enviarComandoML(int volumeMl) {
        if (mBluetoothService == null) {
            Log.e(TAG, "[BLE] enviarComandoML(" + volumeMl + ") — BluetoothService nulo!");
            return;
        }
        if (!mBluetoothService.isReady()) {
            Log.e(TAG, "[BLE] enviarComandoML(" + volumeMl + ") BLOQUEADO — estado="
                    + mBluetoothService.getBleState().name()
                    + ". Aguardar AUTH:OK antes de enviar $ML!");
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[BLE] DUPLICAÇÃO BLOQUEADA: $ML já foi enviado (mComandoEnviado=true).");
            return;
        }
        mComandoEnviado = true;
        mlsSolicitado   = volumeMl;
        String cmd = "$ML:" + volumeMl;

        // ── DIAGNÓSTICO: log detalhado do estado antes do envio ──────────────
        Log.i(TAG, "[DIAG] ════════════════════════════════════════════════");
        Log.i(TAG, "[DIAG] ENVIANDO $ML — diagnóstico completo:");
        Log.i(TAG, "[DIAG]   comando    = " + cmd);
        Log.i(TAG, "[DIAG]   bleState   = " + mBluetoothService.getBleState().name());
        Log.i(TAG, "[DIAG]   isReady    = " + mBluetoothService.isReady());
        Log.i(TAG, "[DIAG]   connected  = " + mBluetoothService.connected());
        Log.i(TAG, "[DIAG]   targetMac  = " + mBluetoothService.getTargetMac());
        android.bluetooth.BluetoothDevice devDiag = mBluetoothService.getBoundDevice();
        if (devDiag != null) {
            Log.i(TAG, "[DIAG]   device     = " + devDiag.getAddress()
                    + " | bond=" + devDiag.getBondState());
        } else {
            Log.w(TAG, "[DIAG]   device     = NULL (GATT nulo)");
        }
        Log.i(TAG, "[DIAG]   qtd_ml     = " + qtd_ml);
        Log.i(TAG, "[DIAG]   liberado   = " + liberado);
        Log.i(TAG, "[DIAG]   retryCount = " + mBleRetryCount);
        Log.i(TAG, "[DIAG] ════════════════════════════════════════════════");

        atualizarStatus("⏳ Aguardando abertura da válvula...");
        mBluetoothService.write(cmd);
        Log.i(TAG, "[BLE] Aguardando confirmação OK do ESP32...");

        // ── DIAGNÓSTICO: inicia timeout de segurança BLE ─────────────────────
        // Se o ESP32 não responder com OK em BLE_RESPONSE_TIMEOUT_MS, reenviar
        iniciarTimeoutRespostaBLE(volumeMl);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ServiceConnection
    // ─────────────────────────────────────────────────────────────────────────

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound   = true;

            // ── DEBUG COMPLETO DO ESTADO AO VINCULAR ──────────────────────────
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
            // ─────────────────────────────────────────────────────────────────

            if (mBluetoothService.isReady()) {
                // FIX-1: aguardar ML_SEND_DELAY_MS antes de enviar $ML
                // para evitar válvula abrindo/fechando rapidamente ao entrar na tela
                Log.i(TAG, "[BLE] → CAMINHO 1: já em READY. Aguardando " + ML_SEND_DELAY_MS
                        + "ms antes de enviar $ML (FIX-1: evitar abertura/fechamento rápido).");
                atualizarStatus("✓ Dispositivo pronto. Liberando...");
                mMainHandler.postDelayed(() -> enviarComandoML(qtd_ml), ML_SEND_DELAY_MS);

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
                    // ACTION_WRITE_READY será emitido pelo forceReady() e tratado no receiver
                } else if (bonding) {
                    Log.i(TAG, "[BLE] → CAMINHO 2B: BOND_BONDING em andamento. Aguardando AUTH:OK + fallback.");
                    atualizarStatus("⏳ Autenticando dispositivo...");
                } else {
                    Log.i(TAG, "[BLE] → CAMINHO 2C: BOND_NONE. Aguardando AUTH:OK + fallback do BluetoothService.");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida da Activity
    // ─────────────────────────────────────────────────────────────────────────

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
        // FIX-4: receber URL da imagem via Intent para fallback de download
        imagemUrl   = extras.containsKey("imagem_url") ? extras.getString("imagem_url") : null;

        Log.i(TAG, "[APP] PagamentoConcluido iniciado — qtd_ml=" + qtd_ml
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

        // Botão oculto inicialmente — só aparece após interrupção parcial (FIX-3)
        btnLiberar.setVisibility(View.GONE);

        if (progressBar != null) {
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        // FIX-4: carregar imagem — tenta banco local primeiro, depois URL
        carregarImagemComFallback();

        sendRequestInicio(checkout_id);

        // Inicia e vincula o BluetoothService
        Log.i(TAG, "[BLE] Iniciando BluetoothService...");
        Intent serviceIntent = new Intent(this, BluetoothService.class);
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
            Log.i(TAG, "[APP] Usuário solicitou liberação do restante: " + restante + "ml");
            btnLiberar.setVisibility(View.GONE);
            mLiberacaoFinalizada = false;
            mComandoEnviado      = false; // Autorizado explicitamente pelo usuário
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            enviarComandoML(restante);
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
        filter.addAction(BluetoothService.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothService.ACTION_DATA_AVAILABLE);
        filter.addAction(BluetoothService.ACTION_WRITE_READY);
        filter.addAction(BluetoothService.ACTION_BLE_STATE_CHANGED);
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
        cancelarTimeoutRespostaBLE();
        mMainHandler.removeCallbacksAndMessages(null);
        if (currentImageTask != null) currentImageTask.cancel(true);
        imageExecutor.shutdown();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carregamento de imagem (FIX-4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * FIX-4: Carrega a imagem da bebida com fallback em duas etapas:
     *   1. Tenta carregar do banco SQLite local (getActiveImageData)
     *   2. Se null/vazio, baixa da URL via ApiHelper em background thread
     */
    private void carregarImagemComFallback() {
        // Etapa 1: banco local
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

        // Etapa 2: fallback — baixar da URL
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

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Chamadas à API
    // ─────────────────────────────────────────────────────────────────────────

    private void sendRequestInicio(String checkoutId) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("checkout_id", checkoutId);
        new ApiHelper().sendPost(body, "liberacao.php?action=iniciada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] sendRequestInicio falhou: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "[API] sendRequestInicio HTTP " + response.code());
                response.close();
            }
        });
    }

    private void sendRequestFim(String volume, String checkoutId) {
        Log.i(TAG, "[API] Enviando liberacao finalizada: " + volume + "ml | checkout=" + checkoutId);
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("qtd_ml", volume);
        body.put("checkout_id", checkoutId);
        body.put("total_pulsos", String.valueOf(totalPulsos));
        new ApiHelper().sendPost(body, "liberacao.php?action=finalizada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[API] sendRequestFim falhou: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.i(TAG, "[API] sendRequestFim HTTP " + response.code() + " — liberação registrada");
                response.close();
            }
        });
    }
}
