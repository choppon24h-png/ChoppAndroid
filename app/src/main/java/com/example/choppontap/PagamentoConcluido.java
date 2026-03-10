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
 * NUNCA enviar $ML antes de ACTION_WRITE_READY.
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Watchdog ──────────────────────────────────────────────────────────────
    private static final long WATCHDOG_TIMEOUT_MS = 30_000L;
    private final Handler  mWatchdogHandler  = new Handler(Looper.getMainLooper());
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

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView    txtQtd;
    private TextView    txtMls;
    private TextView    txtStatus;
    private Button      btnLiberar;
    private ImageView   imageView;
    private ProgressBar progressBar;

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
                    // É seguro enviar $ML agora.
                    // ─────────────────────────────────────────────────────────
                    Log.i(TAG, "[BLE] ACTION_WRITE_READY recebido — canal autenticado (READY). Enviando $ML.");
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");
                    enviarComandoML(qtd_ml);
                    break;

                case BluetoothService.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        Log.w(TAG, "[BLE] Dispositivo DESCONECTADO durante liberação");
                        atualizarStatus("⚠ Reconectando ao dispositivo...");
                        cancelarWatchdog();
                        // BluetoothService fará a reconexão automaticamente (mAutoReconnect=true).
                        // Quando reconectar e AUTH:OK chegar, ACTION_WRITE_READY será emitido
                        // novamente — mas mComandoEnviado=true bloqueará reenvio duplicado.
                    } else if ("connected".equals(status)) {
                        Log.i(TAG, "[BLE] Conectado — aguardando autenticação BLE (AUTH:OK)...");
                        atualizarStatus("⏳ Autenticando dispositivo...");
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

        // OK — válvula aberta
        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] OK — válvula ABERTA. Iniciando watchdog (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
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
                    if (liberado >= mlsSolicitado) {
                        btnLiberar.setVisibility(View.GONE);
                    }
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
        if (msg.startsWith("ML:") || "ML".equalsIgnoreCase(msg)) {
            cancelarWatchdog();
            mValvulaAberta  = false;
            mComandoEnviado = false; // Permite novo envio se usuário pressionar "Continuar"
            Log.i(TAG, "[BLE] " + msg + " — válvula FECHADA. liberado=" + liberado + "ml de " + qtd_ml + "ml");
            Log.i(TAG, "[APP] Operação concluída — liberado=" + liberado + "ml de " + qtd_ml + "ml solicitados");

            if (!mLiberacaoFinalizada) {
                mLiberacaoFinalizada = true;
                sendRequestFim(String.valueOf(liberado), checkout_id);
            }

            runOnUiThread(() -> {
                txtMls.setText(liberado + " ML");
                if (progressBar != null) progressBar.setVisibility(View.GONE);

                if (liberado < qtd_ml) {
                    int restante = qtd_ml - liberado;
                    atualizarStatus("⚠ Fluxo interrompido. " + restante + "ml restantes.");
                    btnLiberar.setText("Continuar servindo (" + restante + "ml)");
                    btnLiberar.setVisibility(View.VISIBLE);
                    mLiberacaoFinalizada = false;
                } else {
                    atualizarStatus("✓ Dosagem completa!");
                    btnLiberar.setVisibility(View.GONE);
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
                // disconnect() reseta o estado para DISCONNECTED e desabilita auto-reconnect.
                // Usamos scanLeDevice() para reconectar manualmente com novo bond.
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
        Log.i(TAG, "[BLE] Enviando comando: " + cmd);
        atualizarStatus("⏳ Aguardando abertura da válvula...");
        mBluetoothService.write(cmd);
        Log.i(TAG, "[BLE] Aguardando confirmação OK do ESP32...");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ServiceConnection
    // ─────────────────────────────────────────────────────────────────────────

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound   = true;

            if (mBluetoothService.isReady()) {
                // Canal já autenticado (raro: Activity recriada com serviço já em READY).
                Log.i(TAG, "[BLE] BluetoothService vinculado — já em estado READY. Enviando $ML.");
                atualizarStatus("✓ Dispositivo pronto. Liberando...");
                enviarComandoML(qtd_ml);

            } else if (mBluetoothService.connected()) {
                // GATT conectado mas estado BleState não é READY.
                // Isso ocorre quando o BluetoothService foi recriado (novo processo/serviço)
                // e perdeu o estado READY — mas o GATT físico ainda está conectado.
                // Verificar o bondState do dispositivo para decidir:
                //   BOND_BONDED  → canal já autenticado → forçar READY agora → enviar $ML
                //   sem bond     → aguardar AUTH:OK (primeiro pareamento em andamento)
                android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                boolean jaBonded = (dev != null
                        && dev.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED);

                if (jaBonded) {
                    Log.i(TAG, "[BLE] BluetoothService vinculado — GATT conectado + BOND_BONDED."
                            + " Forçando READY e enviando $ML.");
                    atualizarStatus("✓ Dispositivo autenticado. Liberando...");
                    mBluetoothService.forceReady(); // transita para READY e emite ACTION_WRITE_READY
                } else {
                    Log.i(TAG, "[BLE] BluetoothService vinculado — GATT conectado, sem bond. Aguardando AUTH:OK...");
                    atualizarStatus("⏳ Autenticando dispositivo...");
                    // ACTION_WRITE_READY chegará quando AUTH:OK for recebido.
                }

            } else {
                // Sem conexão: inicia scan/conexão.
                Log.i(TAG, "[BLE] BluetoothService vinculado — iniciando conexão BLE...");
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
        Log.i(TAG, "[APP] PagamentoConcluido iniciado — qtd_ml=" + qtd_ml
                + " | checkout_id=" + checkout_id);

        btnLiberar  = findViewById(R.id.btnLiberarRestante);
        imageView   = findViewById(R.id.imageBeer2);
        txtQtd      = findViewById(R.id.txtQtdPulsos);
        txtMls      = findViewById(R.id.txtMls);
        txtStatus   = findViewById(R.id.txtStatusLiberacao);
        progressBar = findViewById(R.id.progressLiberacao);

        txtQtd.setText(qtd_ml + " ML");
        txtMls.setText("0 ML");
        atualizarStatus("⏳ Conectando ao dispositivo...");

        if (progressBar != null) {
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }

        Sqlite banco = new Sqlite(getApplicationContext());
        byte[] img = banco.getActiveImageData();
        if (img != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
            imageView.setImageBitmap(bmp);
        }

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
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
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
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
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
        new ApiHelper().sendPost(body, "liberacao.php?action=finalizada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }
}
