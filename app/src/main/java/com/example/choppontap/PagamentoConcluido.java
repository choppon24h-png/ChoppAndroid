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
import okhttp3.ResponseBody;

/**
 * PagamentoConcluido — Tela de liberação do chopp após pagamento confirmado.
 */
public class PagamentoConcluido extends AppCompatActivity {

    private static final String TAG = "PAGAMENTO_CONCLUIDO";

    // ── Watchdog ──────────────────────────────────────────────────────────────
    private static final long WATCHDOG_TIMEOUT_MS = 30_000L;
    private final Handler mWatchdogHandler = new Handler(Looper.getMainLooper());
    private boolean mWatchdogActive = false;

    // ── Estado da liberação ───────────────────────────────────────────────────
    private int qtd_ml = 0;
    private int liberado = 0;
    private int mlsSolicitado = 0;
    private int totalPulsos = 0;
    private boolean mAuthOk = false;
    private boolean mValvulaAberta = false;
    private boolean mLiberacaoFinalizada = false;
    /**
     * Protege contra envio duplicado de $ML após reconexão BLE.
     * Zerado apenas quando a liberação é concluída (ML recebido) ou
     * quando o usuário pressiona "Liberar Restante" explicitamente.
     */
    private boolean mComandoEnviado = false;

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

    // ── Watchdog Runnable ─────────────────────────────────────────────────────
    private final Runnable mWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            Log.e(TAG, "WATCHDOG disparado! Fluxo não detectado em " + (WATCHDOG_TIMEOUT_MS / 1000) + "s");
            mWatchdogActive = false;
            mValvulaAberta = false;
            atualizarStatus("⏱ Timeout: fluxo não detectado. Válvula fechada.");
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
        }
    };

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case BluetoothService.ACTION_WRITE_READY:
                    Log.i(TAG, "[BLE] Canal NUS pronto (ACTION_WRITE_READY)");
                    if (!mAuthOk) {
                        Log.d(TAG, "[BLE] Firmware sem AUTH — enviando $ML diretamente");
                        enviarComandoML(qtd_ml);
                    } else {
                        Log.d(TAG, "[BLE] mAuthOk=true — aguardando AUTH:OK do firmware antes de enviar $ML");
                    }
                    break;

                case BluetoothService.ACTION_CONNECTION_STATUS:
                    String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                    if ("disconnected".equals(status)) {
                        Log.w(TAG, "[BLE] Dispositivo DESCONECTADO durante liberação");
                        atualizarStatus("⚠ Reconectando ao dispositivo...");
                        cancelarWatchdog();
                        if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                    } else if ("connected".equals(status)) {
                        Log.i(TAG, "[BLE] Conectado ao dispositivo");
                        atualizarStatus("✓ Conectado");
                    }
                    break;

                case BluetoothService.ACTION_DATA_AVAILABLE:
                    String data = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                    if (data != null) processarMensagemESP32(data.trim());
                    break;
            }
        }
    };

    private void processarMensagemESP32(String msg) {
        Log.d(TAG, "ESP32 → Android: [" + msg + "]");

        if ("AUTH:OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] Resposta recebida: AUTH:OK — dispositivo autenticado");
            mAuthOk = true;
            atualizarStatus("✓ Dispositivo autenticado");
            enviarComandoML(qtd_ml);
            return;
        }

        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "[BLE] Resposta recebida: OK — válvula ABERTA. Watchdog iniciado (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
            mValvulaAberta = true;
            atualizarStatus("🍺 Servindo...");
            iniciarWatchdog();
            return;
        }

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
                Log.e(TAG, "Erro parse VP: " + e.getMessage());
            }
            return;
        }

        if (msg.startsWith("QP:")) {
            try {
                totalPulsos = Integer.parseInt(msg.substring(3).trim());
                Log.i(TAG, "QP: total de pulsos=" + totalPulsos);
            } catch (Exception e) {}
            return;
        }

        if (msg.startsWith("ML:") || "ML".equalsIgnoreCase(msg)) {
            cancelarWatchdog();
            mValvulaAberta = false;
            mComandoEnviado = false; // Reset: permite novo envio se usuário pressionar "Liberar Restante"
            Log.i(TAG, "[BLE] Resposta recebida: " + msg + " — válvula FECHADA. liberado=" + liberado + "ml");
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

        if (msg.contains("ERROR:NOT_AUTHEN")) {
            cancelarWatchdog();
            Log.e(TAG, "[BLE] ERROR:NOT_AUTHENTICATED recebido do ESP32");
            Log.e(TAG, "[BLE] Causa provável: bond Android inválido (ESP32 não reconhece o vínculo).");
            Log.e(TAG, "[BLE] Ação: removendo bond local e reconectando para forçar novo pareamento com PIN.");
            atualizarStatus("🔑 Reautenticando dispositivo...");
            mAuthOk = false;
            mComandoEnviado = false; // Permite reenvio após novo pareamento
            if (mBluetoothService != null) {
                // Remove o bond inválido antes de reconectar.
                // Isso força o Android a iniciar um novo pareamento com PIN (259087),
                // que será injetado automaticamente pelo mPairingReceiver no BluetoothService.
                android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (adapter != null && mBluetoothService.connected()) {
                    android.bluetooth.BluetoothDevice dev = mBluetoothService.getBoundDevice();
                    if (dev != null) {
                        BluetoothService.removeBond(dev);
                        Log.i(TAG, "[BLE] Bond removido para " + dev.getAddress());
                    }
                }
                mBluetoothService.disconnect();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                }, 2000);
            }
            return;
        }

        if ("ERRO".equalsIgnoreCase(msg) || msg.startsWith("ERRO")) {
            cancelarWatchdog();
            atualizarStatus("❌ Erro no dispositivo.");
            runOnUiThread(() -> mostrarSnackbar("Erro no dispositivo de chopp."));
        }
    }

    private void iniciarWatchdog() {
        cancelarWatchdog();
        mWatchdogActive = true;
        mWatchdogHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TIMEOUT_MS);
        Log.d(TAG, "Watchdog iniciado");
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

    private void enviarComandoML(int volumeMl) {
        if (mBluetoothService == null || !mBluetoothService.connected()) {
            Log.e(TAG, "[BLE] ERRO: enviarComandoML(" + volumeMl + ") chamado mas BLE não está conectado!");
            return;
        }
        if (mComandoEnviado) {
            Log.w(TAG, "[BLE] DUPLICAÇÃO BLOQUEADA: $ML já foi enviado (mComandoEnviado=true). Ignorando envio duplicado.");
            return;
        }
        mComandoEnviado = true;
        mlsSolicitado = volumeMl;
        String cmd = "$ML:" + volumeMl;
        Log.i(TAG, "[BLE] Enviando comando: " + cmd);
        atualizarStatus("⏳ Aguardando abertura da válvula...");
        mBluetoothService.write(cmd);
        Log.i(TAG, "[BLE] Aguardando confirmação do dispositivo (resposta OK/AUTH:OK esperada)");
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            if (!mBluetoothService.connected()) {
                Log.i(TAG, "[BLE] BluetoothService vinculado — iniciando scan BLE...");
                mBluetoothService.scanLeDevice(true);
            } else {
                Log.i(TAG, "[BLE] BluetoothService vinculado — BLE já conectado");
                if (!mAuthOk) {
                    Log.d(TAG, "[BLE] mAuthOk=false — enviando $ML diretamente (firmware sem AUTH)");
                    enviarComandoML(qtd_ml);
                } else {
                    Log.d(TAG, "[BLE] mAuthOk=true — aguardando AUTH:OK do firmware");
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
            mBluetoothService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);
        setupFullscreen();

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            finish();
            return;
        }

        qtd_ml      = Integer.parseInt(extras.get("qtd_ml").toString());
        checkout_id = extras.get("checkout_id").toString();
        Log.i(TAG, "[APP] PagamentoConcluido iniciado — qtd_ml=" + qtd_ml + " | checkout_id=" + checkout_id);

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

        Log.i(TAG, "[BLE] Conectando ao dispositivo...");
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        btnLiberar.setOnClickListener(v -> {
            if (mBluetoothService == null || !mBluetoothService.connected()) {
                Log.e(TAG, "[BLE] Botão 'Liberar Restante' pressionado mas BLE não está conectado!");
                return;
            }
            int restante = qtd_ml - liberado;
            if (restante <= 0) return;
            Log.i(TAG, "[APP] Usuário solicitou liberação do restante: " + restante + " ml");
            btnLiberar.setVisibility(View.GONE);
            mLiberacaoFinalizada = false;
            mComandoEnviado = false; // Permite reenvio explicitamente autorizado pelo usuário
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
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    private void sendRequestInicio(String checkoutId) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("checkout_id", checkoutId);
        new ApiHelper().sendPost(body, "liberacao.php?action=iniciada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
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
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }
}
