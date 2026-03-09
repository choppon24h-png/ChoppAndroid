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
                    Log.i(TAG, "ACTION_WRITE_READY recebido — canal NUS pronto");
                    if (!mAuthOk) {
                        Log.d(TAG, "Firmware sem AUTH — enviando $ML diretamente");
                        enviarComandoML(qtd_ml);
                    }
                    break;

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
            Log.i(TAG, "ESP32 autenticado. Enviando $ML:" + qtd_ml);
            mAuthOk = true;
            atualizarStatus("✓ Dispositivo autenticado");
            enviarComandoML(qtd_ml);
            return;
        }

        if ("OK".equalsIgnoreCase(msg)) {
            Log.i(TAG, "Válvula ABERTA. Watchdog iniciado (" + WATCHDOG_TIMEOUT_MS / 1000 + "s)");
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
            Log.i(TAG, "Válvula FECHADA. liberado=" + liberado + "ml");

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
            Log.e(TAG, "ESP32: NOT_AUTHENTICATED");
            atualizarStatus("❌ Erro de autenticação. Reconectando...");
            mAuthOk = false;
            if (mBluetoothService != null) {
                mBluetoothService.disconnect();
                new Handler(Looper.getMainLooper()).postDelayed(() -> mBluetoothService.scanLeDevice(true), 1500);
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
        if (mBluetoothService == null || !mBluetoothService.connected()) return;
        mlsSolicitado = volumeMl;
        String cmd = "$ML:" + volumeMl;
        Log.i(TAG, "Android → ESP32: [" + cmd + "]");
        atualizarStatus("⏳ Aguardando abertura da válvula...");
        mBluetoothService.write(cmd);
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            if (!mBluetoothService.connected()) {
                mBluetoothService.scanLeDevice(true);
            } else {
                if (!mAuthOk) enviarComandoML(qtd_ml);
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

        Intent serviceIntent = new Intent(this, BluetoothService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        btnLiberar.setOnClickListener(v -> {
            if (mBluetoothService == null || !mBluetoothService.connected()) return;
            int restante = qtd_ml - liberado;
            if (restante <= 0) return;
            btnLiberar.setVisibility(View.GONE);
            mLiberacaoFinalizada = false;
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
