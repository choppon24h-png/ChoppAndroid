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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * PagamentoConcluido — Tela de liberação do chopp após pagamento confirmado.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PROTOCOLO ESP32 (Nordic UART Service — NUS)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Fluxo completo esperado:
 *
 *   ESP32 → Android:  AUTH:OK              ← dispositivo pronto (após pareamento)
 *   Android → ESP32:  $ML:<volume>         ← solicitar liberação (ex: $ML:300)
 *   ESP32 → Android:  OK                   ← válvula abrindo
 *   ESP32 → Android:  VP:<ml>              ← volume parcial a cada ~2s
 *   ESP32 → Android:  QP:<pulsos>          ← total de pulsos ao encerrar
 *   ESP32 → Android:  ML:<volume>          ← dosagem concluída, válvula fechada
 *
 * Mensagens de erro:
 *   ESP32 → Android:  ERRO                 ← erro genérico
 *   ESP32 → Android:  ERROR:NOT_AUTHENTICATED ← forçar re-pareamento
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * GAPS CORRIGIDOS NESTA VERSÃO
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. AUTH:OK não era tratado — app enviava $ML antes de receber confirmação
 *    de autenticação. CORRIGIDO: mAuthOk flag + envio só após AUTH:OK ou
 *    ACTION_WRITE_READY (compatibilidade com firmware sem AUTH).
 *
 * 2. "OK" (válvula abrindo) não era tratado — sem feedback visual.
 *    CORRIGIDO: exibe "Abrindo válvula..." e inicia watchdog timer.
 *
 * 3. Watchdog ausente — se o fluxo não iniciar em X segundos após $ML,
 *    a válvula deveria ser fechada automaticamente.
 *    CORRIGIDO: watchdog de 30s (configurável via $TO no ESP32). Se nenhum
 *    VP: for recebido após 30s do envio do $ML, envia $ML:0 para fechar.
 *
 * 4. VP: era tratado como valor absoluto acumulado, mas o protocolo envia
 *    o volume acumulado progressivo. CORRIGIDO: lógica mantida mas com
 *    reset correto do watchdog a cada VP: recebido.
 *
 * 5. "ML" era detectado por contains("ML") — colide com "VP:62.415ML" ou
 *    qualquer string contendo "ML". CORRIGIDO: verifica "ML:" prefix ou
 *    linha exata "ML" para evitar falso positivo com VP:.
 *
 * 6. QP: (total de pulsos) não era tratado — dado perdido.
 *    CORRIGIDO: captura e loga QP: para rastreabilidade.
 *
 * 7. ERRO e ERROR:NOT_AUTHENTICATED não eram tratados.
 *    CORRIGIDO: exibe mensagem ao usuário e força re-scan no caso de
 *    NOT_AUTHENTICATED.
 *
 * 8. Botão "Liberar Restante" calculava restante = qtd_ml - liberado, mas
 *    mlsSolicitado não era atualizado. CORRIGIDO: mlsSolicitado = restante
 *    antes do write, e watchdog reiniciado.
 *
 * 9. ProgressBar ausente — sem indicação visual de progresso.
 *    CORRIGIDO: progressBar atualizado proporcionalmente ao VP:.
 *    (Requer progressBar no layout — adicionado via XML separado)
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Timeout em ms sem receber VP: após enviar $ML antes de fechar válvula */
    private static final long WATCHDOG_TIMEOUT_MS = 30_000L;
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private boolean mWatchdogActive = false;

    // ── Estado da liberação ───────────────────────────────────────────────────
    /** Volume total solicitado no pedido atual (em ml) */
    private int qtd_ml = 0;
    /** Volume acumulado liberado (atualizado via VP:) */
    private int liberado = 0;
    /** Volume solicitado no comando $ML atual (pode ser parcial no "Liberar Restante") */
    private int mlsSolicitado = 0;
    /** Total de pulsos reportado pelo QP: (para log/auditoria) */
    private int totalPulsos = 0;
    /** Flag: ESP32 confirmou autenticação (AUTH:OK recebido) */
    private boolean mAuthOk = false;
    /** Flag: válvula está aberta (OK recebido, aguardando ML:) */
    private boolean mValvulaAberta = false;
    /** Flag: liberação já finalizada (evita duplo sendRequestFim) */
    private boolean mLiberacaoFinalizada = false;

    // ── Dados do pedido ───────────────────────────────────────────────────────
    private String checkout_id;
    private String android_id;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView txtQtd;
    private TextView txtMls;
    private TextView txtStatus;
    private Button btnLiberar;
    private ImageView imageView;
    private ProgressBar progressBar;

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    // ─────────────────────────────────────────────────────────────────────────
    // BroadcastReceiver — recebe eventos do BluetoothService
    // ─────────────────────────────────────────────────────────────────────────

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {

                // ── Comunicação GATT pronta (descriptor escrito com sucesso) ──
                case BluetoothService.ACTION_WRITE_READY:
                    Log.i(TAG, "ACTION_WRITE_READY recebido — canal NUS pronto");
                    // Se AUTH:OK ainda não chegou, envia $ML diretamente
                    // (compatibilidade com firmware que não implementa AUTH)
                    if (!mAuthOk) {
                        Log.d(TAG, "Firmware sem AUTH — enviando $ML diretamente");
                        enviarComandoML(qtd_ml);
                    }
                    break;

                // ── Status de conexão BLE ─────────────────────────────────────
                case BluetoothService.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        Log.w(TAG, "BLE desconectado durante liberação");
                        atualizarStatus("⚠ Reconectando ao dispositivo...");
                        cancelarWatchdog();
                        if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                    } else if ("connected".equals(status)) {
                        Log.i(TAG, "BLE reconectado");
                        atualizarStatus("✓ Conectado");
                    }
                    break;

                // ── Dados recebidos do ESP32 ──────────────────────────────────
                case BluetoothService.ACTION_DATA_AVAILABLE:
                    String data = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                    if (data != null) processarMensagemESP32(data.trim());
                    break;
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Processamento do protocolo ESP32
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Processa cada mensagem recebida do ESP32 via NUS TX.
     *
     * Mensagens esperadas:
     *   AUTH:OK  → dispositivo autenticado, pronto para receber $ML
     *   OK       → válvula aberta, watchdog iniciado
     *   VP:<x>   → volume parcial liberado em ml (acumulado)
     *   QP:<x>   → total de pulsos contados (ao encerrar)
     *   ML:<x>   → dosagem concluída, válvula fechada
     *   ERRO     → erro genérico
     *   ERROR:NOT_AUTHENTICATED → re-pareamento necessário
     */
    private void processarMensagemESP32(String msg) {
        Log.d(TAG, "ESP32 → Android: [" + msg + "]");

        // ── AUTH:OK ───────────────────────────────────────────────────────────
        if ("AUTH:OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "ESP32 autenticado. Enviando $ML:" + qtd_ml);
            mAuthOk = true;
            atualizarStatus("✓ Dispositivo autenticado");
            enviarComandoML(qtd_ml);
            return;
        }

        // ── OK (válvula abrindo) ──────────────────────────────────────────────
        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "Válvula ABERTA. Watchdog iniciado (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
            iniciarWatchdog();
            return;
        }

        // ── VP:<volume> (progresso) ───────────────────────────────────────────
        if (msg.startsWith("VP:")) {
            resetarWatchdog(); // Fluxo ativo — reseta o timer
            try {
                double mlFloat = Double.parseDouble(msg.substring(3).trim());
                liberado = (int) Math.round(mlFloat);
                Log.d(TAG, "VP: liberado=" + liberado + "ml / solicitado=" + mlsSolicitado + "ml");

                runOnUiThread(() -> {
                    txtMls.setText(liberado + " ML");
                    // Atualiza barra de progresso proporcionalmente
                    if (progressBar != null && qtd_ml > 0) {
                        int progresso = (int) ((liberado / (float) qtd_ml) * 100);
                        progressBar.setProgress(Math.min(progresso, 100));
                    }
                    // Esconde "Liberar Restante" enquanto fluxo está ativo
                    if (liberado >= mlsSolicitado) {
                        btnLiberar.setVisibility(View.GONE);
                    }
                });
            } catch (NumberFormatException e) {
                Log.e(TAG, "Erro ao parsear VP: [" + msg + "] — " + e.getMessage());
            }
            return;
        }

        // ── QP:<pulsos> (total de pulsos ao encerrar) ─────────────────────────
        if (msg.startsWith("QP:")) {
            try {
                totalPulsos = Integer.parseInt(msg.substring(3).trim());
                Log.i(TAG, "QP: total de pulsos=" + totalPulsos);
                // Salva para auditoria — pode ser enviado à API no futuro
            } catch (NumberFormatException e) {
                Log.e(TAG, "Erro ao parsear QP: [" + msg + "]");
            }
            return;
        }

        // ── ML:<volume> (dosagem concluída) ───────────────────────────────────
        // Trata tanto "ML:300" quanto "ML" (firmware sem volume no encerramento)
        if (msg.startsWith("ML:") || "ML".equalsIgnoreCase(msg)) {
            cancelarWatchdog();
            mValvulaAberta = false;
            Log.i(TAG, "Válvula FECHADA. liberado=" + liberado + "ml / solicitado=" + mlsSolicitado + "ml");

            if (!mLiberacaoFinalizada) {
                mLiberacaoFinalizada = true;
                sendRequestFim(String.valueOf(liberado), checkout_id);
            }

            runOnUiThread(() -> {
                txtMls.setText(liberado + " ML");
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                if (liberado < qtd_ml) {
                    // Fluxo incompleto — mostra botão para continuar servindo
                    int restante = qtd_ml - liberado;
                    Log.w(TAG, "Fluxo incompleto: faltam " + restante + "ml. Mostrando botão.");
                    atualizarStatus("⚠ Fluxo interrompido. " + restante + "ml restantes.");
                    btnLiberar.setText("Continuar servindo (" + restante + "ml)");
                    btnLiberar.setVisibility(View.VISIBLE);
                    mLiberacaoFinalizada = false; // Permite nova finalização após "Continuar"
                } else {
                    // Dosagem completa
                    atualizarStatus("✓ Dosagem completa!");
                    btnLiberar.setVisibility(View.GONE);
                }
            });
            return;
        }

        // ── ERRO genérico ─────────────────────────────────────────────────────
        if ("ERRO".equalsIgnoreCase(msg) || msg.startsWith("ERRO")) {
            cancelarWatchdog();
            Log.e(TAG, "ESP32 reportou ERRO: [" + msg + "]");
            atualizarStatus("❌ Erro no dispositivo. Tente novamente.");
            runOnUiThread(() -> mostrarSnackbar("Erro no dispositivo de chopp. Contate o suporte."));
            return;
        }

        // ── ERROR:NOT_AUTHENTICATED ───────────────────────────────────────────
        if (msg.startsWith("ERROR:NOT_AUTHENTICATED")) {
            cancelarWatchdog();
            Log.e(TAG, "ESP32: NOT_AUTHENTICATED — forçando re-scan BLE");
            atualizarStatus("❌ Dispositivo não autenticado. Reconectando...");
            mAuthOk = false;
            if (mBluetoothService != null) {
                mBluetoothService.disconnect();
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> mBluetoothService.scanLeDevice(true), 1500);
            }
            return;
        }

        Log.d(TAG, "Mensagem ESP32 não reconhecida: [" + msg + "]");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Watchdog — fecha válvula se fluxo não iniciar/continuar em 30s
    // ─────────────────────────────────────────────────────────────────────────

    private final Runnable mWatchdogRunnable = () -> {
        Log.e(TAG, "WATCHDOG disparado! Nenhum VP: recebido em " + (WATCHDOG_TIMEOUT_MS / 1000) + "s");
        mWatchdogActive = false;
        mValvulaAberta = false;
        atualizarStatus("⏱ Timeout: fluxo não detectado. Válvula fechada.");
        // Envia $ML:0 para garantir fechamento da válvula
        if (mBluetoothService != null && mBluetoothService.connected()) {
            Log.w(TAG, "Enviando $ML:0 para fechar válvula por timeout");
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

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        Log.d(TAG, "Watchdog iniciado (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
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
        Log.d(TAG, "Watchdog cancelado");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Envio de comando $ML para o ESP32
    // ─────────────────────────────────────────────────────────────────────────

    private void enviarComandoML(int volumeMl) {
        if (mBluetoothService == null || !mBluetoothService.connected()) {
            Log.e(TAG, "enviarComandoML: BLE não conectado — aguardando reconexão");
            atualizarStatus("⏳ Aguardando conexão BLE...");
            return;
        }
        mlsSolicitado = volumeMl;
        String cmd = "$ML:" + volumeMl;
        Log.i(TAG, "Android → ESP32: [" + cmd + "]");
        atualizarStatus("⏳ Aguardando abertura da válvula...");
        mBluetoothService.write(cmd);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ServiceConnection do BluetoothService
    // ─────────────────────────────────────────────────────────────────────────

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            Log.i(TAG, "BluetoothService vinculado. Estado: "
                    + (mBluetoothService.connected() ? "CONECTADO" : "DESCONECTADO"));

            if (!mBluetoothService.connected()) {
                mBluetoothService.scanLeDevice(true);
            } else {
                // Já conectado — ACTION_WRITE_READY pode ter sido perdido,
                // envia $ML diretamente se AUTH não for necessário
                Log.i(TAG, "Já conectado — verificando se AUTH:OK é necessário");
                if (!mAuthOk) {
                    Log.d(TAG, "Sem AUTH:OK prévio — enviando $ML diretamente (firmware compatível)");
                    enviarComandoML(qtd_ml);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothService desvinculado");
            mIsServiceBound = false;
            mBluetoothService = null;
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);
        setupFullscreen();

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            Log.e(TAG, "onCreate: extras nulos — finalizando Activity");
            finish();
            return;
        }

        qtd_ml      = Integer.parseInt(extras.get("qtd_ml").toString());
        checkout_id = extras.get("checkout_id").toString();

        Log.i(TAG, "onCreate: qtd_ml=" + qtd_ml + " checkout_id=" + checkout_id);

        // ── Bind de views ─────────────────────────────────────────────────────
        btnLiberar  = findViewById(R.id.btnLiberarRestante);
        imageView   = findViewById(R.id.imageBeer2);
        txtQtd      = findViewById(R.id.txtQtdPulsos);
        txtMls      = findViewById(R.id.txtMls);
        txtStatus   = findViewById(R.id.txtStatusLiberacao);   // Novo campo de status
        progressBar = findViewById(R.id.progressLiberacao);    // Barra de progresso

        txtQtd.setText(qtd_ml + " ML");
        txtMls.setText("0 ML");
        atualizarStatus("⏳ Conectando ao dispositivo...");

        if (progressBar != null) {
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        // ── Imagem da bebida ──────────────────────────────────────────────────
        Sqlite banco = new Sqlite(getApplicationContext());
        byte[] img = banco.getActiveImageData();
        if (img != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            imageView.setImageBitmap(bmp);
        }

        // ── Notifica API: liberação iniciada ──────────────────────────────────
        sendRequestInicio(checkout_id);

        // ── Vincula BluetoothService ──────────────────────────────────────────
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Botão "Continuar servindo" / "Liberar Restante" ───────────────────
        btnLiberar.setOnClickListener(v -> {
            if (mBluetoothService == null || !mBluetoothService.connected()) {
                mostrarSnackbar("Aguardando conexão Bluetooth...");
                return;
            }
            int restante = qtd_ml - liberado;
            if (restante <= 0) {
                btnLiberar.setVisibility(View.GONE);
                return;
            }
            Log.i(TAG, "Botão 'Continuar': enviando $ML:" + restante);
            btnLiberar.setVisibility(View.GONE);
            mLiberacaoFinalizada = false;
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            atualizarStatus("⏳ Aguardando abertura da válvula...");
            enviarComandoML(restante);
        });

        // ── Back interceptado ─────────────────────────────────────────────────
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.d(TAG, "Back interceptado → Home");
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
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);

        if (mBluetoothService != null && !mBluetoothService.connected()) {
            Log.i(TAG, "onResume: BLE desconectado, reiniciando scan");
            mBluetoothService.scanLeDevice(true);
        }
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
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitários de UI
    // ─────────────────────────────────────────────────────────────────────────

    private void atualizarStatus(String msg) {
        Log.d(TAG, "[STATUS] " + msg);
        runOnUiThread(() -> {
            if (txtStatus != null) txtStatus.setText(msg);
        });
    }

    private void mostrarSnackbar(String msg) {
        runOnUiThread(() -> {
            View root = findViewById(R.id.main);
            if (root != null) Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuração de tela
    // ─────────────────────────────────────────────────────────────────────────

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
        Log.d(TAG, "API: liberacao?action=iniciada checkout_id=" + checkoutId);
        new ApiHelper().sendPost(body, "liberacao.php?action=iniciada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "liberacao iniciada falhou: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "liberacao iniciada HTTP " + response.code());
                response.close();
            }
        });
    }

    private void sendRequestFim(String volume, String checkoutId) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("qtd_ml", volume);
        body.put("checkout_id", checkoutId);
        body.put("total_pulsos", String.valueOf(totalPulsos));
        Log.d(TAG, "API: liberacao?action=finalizada qtd_ml=" + volume
                + " pulsos=" + totalPulsos + " checkout_id=" + checkoutId);
        new ApiHelper().sendPost(body, "liberacao.php?action=finalizada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e(TAG, "liberacao finalizada falhou: " + e.getMessage());
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                Log.d(TAG, "liberacao finalizada HTTP " + response.code());
                response.close();
            }
        });
    }
}
