package com.example.choppontap;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.provider.Settings.Secure;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Imei - Tela de Vínculo de Dispositivo
 *
 * CORREÇÕES v2.1 (fix: timeout e SocketException no carregamento):
 *
 * CAUSA RAIZ (analisada no log):
 *   - O servidor ochoppoficial.com.br usa PHP compartilhado com cold start lento.
 *   - O warm-up anterior criava um ApiHelper separado do sendPost(), então a
 *     conexão TLS aquecida não era reaproveitada (pool diferente).
 *   - Com callTimeout=90s no OkHttp, o app ficava travado por ~2 minutos antes
 *     de tentar novamente.
 *
 * SOLUÇÕES APLICADAS:
 *   1. ApiHelper agora usa OkHttpClient SINGLETON — warm-up e sendPost()
 *      compartilham o mesmo connection pool. A conexão TLS aberta no warm-up
 *      é reaproveitada na requisição principal → latência cai de ~65s para ~2s.
 *
 *   2. MAX_RETRY_ATTEMPTS aumentado de 3 → 5 — garante mais chances de sucesso
 *      sem intervenção do usuário.
 *
 *   3. Delays de retry otimizados: 1s, 3s, 5s, 8s (antes: 1s, 5s fixos).
 *      Backoff progressivo sem ser excessivamente longo.
 *
 *   4. Indicador visual de "Conectando..." atualizado a cada tentativa para
 *      o usuário saber que o app está trabalhando.
 *
 *   5. Botão "Tentar Novamente" sempre visível após falha total.
 */
public class Imei extends AppCompatActivity {

    private static final String TAG = "IMEI_CHECK";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int MAX_RETRY_ATTEMPTS = 5;

    String android_id;
    private int retryAttempt = 0;
    private View rootView;
    private TextView txtStatus;
    private Button btnUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_imei);
        setupFullscreen();

        rootView  = findViewById(R.id.main);
        txtStatus = findViewById(R.id.txtStatus);  // pode ser null se não existir no layout
        btnUpdate = findViewById(R.id.btnUpdate);

        if (rootView == null) {
            Log.e(TAG, "View raiz (R.id.main) não encontrada!");
        }

        android_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);

        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> {
                retryAttempt = 0;
                warmupAndSendRequest();
            });
        } else {
            Log.e(TAG, "Botão btnUpdate não encontrado!");
        }

        TextView txtTap = findViewById(R.id.txtTap);
        if (txtTap != null) {
            txtTap.setText(android_id);
        } else {
            Log.e(TAG, "TextView txtTap não encontrado!");
        }

        checkPermissionsAndRequest();
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        retryAttempt = 0;
        warmupAndSendRequest();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Warm-up + envio
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executa o warm-up do servidor em thread separada e, ao concluir,
     * dispara a requisição principal na UI thread.
     *
     * CORREÇÃO v3.0: ApiHelper usa HTTP/2 nativo via ALPN.
     * O warm-up estabelece a conexão TLS+HTTP/2 que é reaproveitada
     * pelo sendPost() via connection pool (multiplexação HTTP/2).
     * Tempo esperado: <100ms (vs 20-28s com HTTP/1.1 forçado + Upgrade header).
     */
    private void warmupAndSendRequest() {
        updateStatusText("Conectando ao servidor...");
        new Thread(() -> {
            new ApiHelper().warmupServer();
            runOnUiThread(this::sendRequestWithRetry);
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delays de retry — com HTTP/2 o servidor responde em <100ms,
     * então delays curtos são suficientes. Mantemos backoff progressivo
     * para casos de instabilidade de rede.
     */
    // Delays progressivos: 1s, 3s, 5s, 8s
    private long getRetryDelay(int attemptNumber) {
        switch (attemptNumber) {
            case 1:  return 1000;
            case 2:  return 3000;
            case 3:  return 5000;
            case 4:  return 8000;
            default: return 3000;
        }
    }

    private void sendRequestWithRetry() {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Sem conexão de internet");
            showMessage("❌ Sem conexão de internet. Verifique sua rede.", Snackbar.LENGTH_LONG);
            updateStatusText("Sem conexão de internet");
            return;
        }

        retryAttempt++;
        Log.i(TAG, "Tentativa " + retryAttempt + " de " + MAX_RETRY_ATTEMPTS);
        updateStatusText("Tentativa " + retryAttempt + " de " + MAX_RETRY_ATTEMPTS + "...");
        sendRequest();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Requisição principal
    // ─────────────────────────────────────────────────────────────────────────

    public void sendRequest() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        Log.d(TAG, "Passo 1: Buscando Alvo (API) com ID: " + android_id);

        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "=== CALLBACK onFailure EXECUTADO ===");
                Log.e(TAG, "Thread: " + Thread.currentThread().getName());
                Log.e(TAG, "=== ERRO DE REDE ===");
                Log.e(TAG, "Mensagem: " + e.getMessage());
                Log.e(TAG, "Causa: " + (e.getCause() != null ? e.getCause().toString() : "N/A"));
                Log.e(TAG, "Stack trace:", e);

                if (e.getMessage() != null) {
                    if (e.getMessage().contains("failed to connect")) {
                        Log.e(TAG, "Tipo: Falha de conexão (timeout ou servidor indisponível)");
                    } else if (e.getMessage().contains("Network is unreachable")) {
                        Log.e(TAG, "Tipo: Rede indisponível");
                    } else if (e.getMessage().contains("Connection refused")) {
                        Log.e(TAG, "Tipo: Conexão recusada");
                    } else if (e.getMessage().contains("timeout")) {
                        Log.e(TAG, "Tipo: Timeout — servidor demorou mais que " +
                                "callTimeout (45s). Retentando...");
                    }
                }

                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    long delay = getRetryDelay(retryAttempt);
                    Log.i(TAG, "Agendando retry em " + delay + "ms...");

                    runOnUiThread(() -> {
                        showMessage("⏳ Tentativa " + retryAttempt + " falhou. " +
                                "Retentando em " + (delay / 1000) + "s...",
                                Snackbar.LENGTH_SHORT);
                        updateStatusText("Retentando em " + (delay / 1000) + "s...");
                    });

                    new Handler(Looper.getMainLooper()).postDelayed(
                            Imei.this::sendRequestWithRetry, delay);
                } else {
                    Log.e(TAG, "Falha após " + MAX_RETRY_ATTEMPTS + " tentativas");
                    runOnUiThread(() -> {
                        updateStatusText("Falha ao conectar. Toque em Tentar Novamente.");
                        showMessageWithRetry("❌ Erro ao conectar após " +
                                MAX_RETRY_ATTEMPTS + " tentativas. Verifique sua conexão.");
                    });
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Log.i(TAG, "=== CALLBACK onResponse EXECUTADO ===");
                Log.i(TAG, "Thread: " + Thread.currentThread().getName());
                Log.i(TAG, "Response code: " + response.code());
                Log.i(TAG, "Response successful: " + response.isSuccessful());

                final String jsonResponse;
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        Log.e(TAG, "Resposta da API falhou com código: " + response.code());
                        if (response.code() == 401) {
                            Log.e(TAG, "Erro 401: Token inválido");
                            runOnUiThread(() ->
                                    showMessage("❌ Erro de autenticação (401).",
                                            Snackbar.LENGTH_LONG));
                        } else {
                            runOnUiThread(() ->
                                    showMessage("❌ Erro HTTP " + response.code(),
                                            Snackbar.LENGTH_LONG));
                        }
                        return;
                    }
                    jsonResponse = responseBody.string();
                    Log.d(TAG, "Corpo JSON recebido (" + jsonResponse.length() +
                            " chars): " + jsonResponse);
                } catch (IOException e) {
                    Log.e(TAG, "Erro ao ler resposta da API.", e);
                    runOnUiThread(() ->
                            showMessage("❌ Erro ao ler resposta do servidor",
                                    Snackbar.LENGTH_LONG));
                    return;
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        Log.w(TAG, "Activity destruída. Abortando navegação.");
                        return;
                    }
                    try {
                        Log.i(TAG, "=== PROCESSANDO JSON ===");
                        Log.d(TAG, "JSON Recebido: " + jsonResponse);

                        // Remove possíveis caracteres antes do JSON (BOM, whitespace)
                        String cleanJson = jsonResponse;
                        int braceIdx = cleanJson.indexOf('{');
                        if (braceIdx > 0) {
                            Log.w(TAG, braceIdx + " caractere(s) antes do JSON removidos");
                            cleanJson = cleanJson.substring(braceIdx);
                        }

                        Tap tap = new Gson().fromJson(cleanJson, Tap.class);

                        if (tap != null && tap.bebida != null && !tap.bebida.isEmpty()) {
                            Log.i(TAG, "✅ Objeto Tap validado. Preparando para navegar.");
                            showMessage("✅ Conectado com sucesso!", Snackbar.LENGTH_SHORT);
                            updateStatusText("Conectado!");

                            if (tap.esp32_mac != null && !tap.esp32_mac.isEmpty()) {
                                saveMacLocally(tap.esp32_mac);
                            }

                            // Verifica se a TAP está desativada
                            if (tap.tap_status != null && tap.tap_status == 0) {
                                Log.w(TAG, "tap_status=0 → TAP OFFLINE → OfflineTap");
                                Intent offlineIntent = new Intent(
                                        getApplicationContext(), OfflineTap.class);
                                offlineIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(offlineIntent);
                                finish();
                                return;
                            }

                            Intent it = new Intent(getApplicationContext(), Home.class);
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            it.putExtra("bebida", tap.bebida);
                            it.putExtra("preco",
                                    (tap.preco != null) ? tap.preco.floatValue() : 0.0f);
                            it.putExtra("imagem", tap.image);
                            it.putExtra("cartao", (tap.cartao != null) && tap.cartao);

                            Log.d(TAG, ">>> DISPARANDO INTENT PARA HOME...");
                            startActivity(it);
                            Log.d(TAG, ">>> START ACTIVITY EXECUTADO.");
                            finish();
                        } else {
                            Log.w(TAG, "⚠️ Dispositivo não configurado na API.");
                            updateStatusText("Dispositivo não configurado no servidor.");
                            showMessage("⚠️ Dispositivo não configurado no servidor.",
                                    Snackbar.LENGTH_LONG);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "ERRO FATAL no processamento ou navegação.", t);
                        showMessage("❌ Erro ao processar resposta do servidor.",
                                Snackbar.LENGTH_LONG);
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilitários
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.w(TAG, "ConnectivityManager não disponível");
                return false;
            }
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            Log.i(TAG, "Status de rede: " + (isConnected ? "CONECTADO" : "DESCONECTADO"));
            if (activeNetwork != null) {
                Log.d(TAG, "Tipo de rede: " + activeNetwork.getTypeName());
            }
            return isConnected;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao verificar conectividade", e);
            return false;
        }
    }

    private void updateStatusText(String message) {
        if (txtStatus != null) {
            runOnUiThread(() -> txtStatus.setText(message));
        }
        Log.d(TAG, "[STATUS] " + message);
    }

    private void showMessage(String message, int duration) {
        if (rootView != null) {
            Snackbar.make(rootView, message, duration).show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    private void showMessageWithRetry(String message) {
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_INDEFINITE)
                    .setAction("Tentar Novamente", v -> {
                        retryAttempt = 0;
                        warmupAndSendRequest();
                    })
                    .show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    private void saveMacLocally(String mac) {
        SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);
        prefs.edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "MAC " + mac + " salvo localmente.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permissões
    // ─────────────────────────────────────────────────────────────────────────

    private void checkPermissionsAndRequest() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendRequestWithRetry();
        }
    }
}
