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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
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
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Imei - Tela de Vínculo de Dispositivo
 *
 * MELHORIAS IMPLEMENTADAS:
 * ✅ Verificação de conectividade
 * ✅ Retry automático com backoff
 * ✅ Feedback visual ao usuário via Snackbar
 * ✅ Logging detalhado
 * ✅ Tratamento de erro melhorado
 * ✅ Validação de permissões
 * ✅ Sem dependência de txtStatus (não existe no layout)
 */
public class Imei extends AppCompatActivity {

    private final OkHttpClient client = new OkHttpClient();
    String android_id;
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String TAG = "IMEI_CHECK";

    // ✅ NOVO: Variáveis para retry
    private int retryAttempt = 0;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private View rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_imei);
        setupFullscreen();

        // ✅ NOVO: Obter referência da view raiz para Snackbar
        rootView = findViewById(R.id.main);
        if (rootView == null) {
            Log.e(TAG, "View raiz (R.id.main) não encontrada!");
        }

        android_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);

        Button btnUpdate = findViewById(R.id.btnUpdate);
        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v -> {
                retryAttempt = 0;  // Reset retry counter
                sendRequestWithRetry();
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
        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        );
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        retryAttempt = 0;  // Reset retry counter
        sendRequestWithRetry();
    }

    private void checkPermissionsAndRequest() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendRequestWithRetry();
        }
    }

    /**
     * ✅ NOVO: Verificar conectividade de internet
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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

    /**
     * ✅ NOVO: Exibir mensagem de status via Snackbar
     */
    private void showMessage(String message, int duration) {
        if (rootView != null) {
            Snackbar.make(rootView, message, duration).show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    /**
     * ✅ NOVO: Exibir mensagem com ação de retry
     */
    private void showMessageWithRetry(String message) {
        if (rootView != null) {
            Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
                    .setAction("Repetir", v -> {
                        retryAttempt = 0;
                        sendRequestWithRetry();
                    })
                    .show();
        } else {
            Log.w(TAG, "rootView não disponível. Mensagem: " + message);
        }
    }

    /**
     * ✅ NOVO: Enviar requisição com retry automático
     */
    private void sendRequestWithRetry() {
        if (!isNetworkAvailable()) {
            Log.e(TAG, "Sem conexão de internet");
            showMessage("❌ Sem conexão de internet. Verifique sua rede.", Snackbar.LENGTH_LONG);
            return;
        }

        retryAttempt++;
        Log.i(TAG, "Tentativa " + retryAttempt + " de " + MAX_RETRY_ATTEMPTS);

        sendRequest();
    }

    /**
     * ✅ MELHORADO: Enviar requisição com logging detalhado
     */
    public void sendRequest() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        Log.d(TAG, "Passo 1: Buscando Alvo (API) com ID: " + android_id);

        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // ✅ MELHORADO: Logging completo
                Log.e(TAG, "=== CALLBACK onFailure EXECUTADO ===");
                Log.e(TAG, "Thread: " + Thread.currentThread().getName());
                Log.e(TAG, "=== ERRO DE REDE ===");
                Log.e(TAG, "Mensagem: " + e.getMessage());
                Log.e(TAG, "Causa: " + (e.getCause() != null ? e.getCause().toString() : "N/A"));
                Log.e(TAG, "Stack trace:", e);

                // Verificar tipo de erro
                if (e.getMessage() != null) {
                    if (e.getMessage().contains("failed to connect")) {
                        Log.e(TAG, "Tipo: Falha de conexão (timeout ou servidor indisponível)");
                    } else if (e.getMessage().contains("Network is unreachable")) {
                        Log.e(TAG, "Tipo: Rede indisponível");
                    } else if (e.getMessage().contains("Connection refused")) {
                        Log.e(TAG, "Tipo: Conexão recusada");
                    }
                }

                // ✅ NOVO: Retry automático com backoff exponencial
                if (retryAttempt < MAX_RETRY_ATTEMPTS) {
                    long delay = (long) Math.pow(2, retryAttempt) * 1000;  // 2s, 4s, 8s
                    Log.i(TAG, "Agendando retry em " + delay + "ms...");

                    runOnUiThread(() -> {
                        showMessage("⏳ Tentativa " + retryAttempt + " falhou. Retentando em " + (delay / 1000) + "s...", Snackbar.LENGTH_SHORT);
                    });

                    new Handler(Looper.getMainLooper()).postDelayed(
                            Imei.this::sendRequestWithRetry,
                            delay
                    );
                } else {
                    Log.e(TAG, "Falha após " + MAX_RETRY_ATTEMPTS + " tentativas");

                    runOnUiThread(() -> {
                        showMessageWithRetry("❌ Erro ao conectar após " + MAX_RETRY_ATTEMPTS + " tentativas. Verifique sua conexão.");
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
                            runOnUiThread(() -> {
                                showMessage("❌ Erro de autenticação (401). Verifique a configuração.", Snackbar.LENGTH_LONG);
                            });
                        } else {
                            runOnUiThread(() -> {
                                showMessage("❌ Erro HTTP " + response.code(), Snackbar.LENGTH_LONG);
                            });
                        }
                        return;
                    }
                    jsonResponse = responseBody.string();
                    Log.d(TAG, "Corpo JSON recebido (" + jsonResponse.length() + " chars): " + jsonResponse);
                } catch (IOException e) {
                    Log.e(TAG, "Erro ao ler resposta da API.", e);
                    runOnUiThread(() -> {
                        showMessage("❌ Erro ao ler resposta do servidor", Snackbar.LENGTH_LONG);
                    });
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
                        Gson gson = new Gson();
                        Tap tap = gson.fromJson(jsonResponse, Tap.class);

                        if (tap != null && tap.bebida != null && !tap.bebida.isEmpty()) {
                            Log.i(TAG, "✅ Objeto Tap validado. Preparando para navegar.");
                            showMessage("✅ Conectado com sucesso!", Snackbar.LENGTH_SHORT);

                            if (tap.esp32_mac != null && !tap.esp32_mac.isEmpty()) {
                                saveMacLocally(tap.esp32_mac);
                            }

                            Intent it = new Intent(getApplicationContext(), Home.class);
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                            it.putExtra("bebida", tap.bebida);
                            it.putExtra("preco", (tap.preco != null) ? tap.preco.floatValue() : 0.0f);
                            it.putExtra("imagem", tap.image);
                            it.putExtra("cartao", (tap.cartao != null) && tap.cartao);

                            Log.d(TAG, ">>> DISPARANDO INTENT PARA HOME...");
                            startActivity(it);
                            Log.d(TAG, ">>> START ACTIVITY EXECUTADO.");

                            finish();
                        } else {
                            Log.w(TAG, "⚠️ Dispositivo não configurado na API.");
                            showMessage("⚠️ Dispositivo não configurado no servidor.", Snackbar.LENGTH_LONG);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "ERRO FATAL no processamento ou navegação.", t);
                        showMessage("❌ Erro ao processar resposta do servidor.", Snackbar.LENGTH_LONG);
                    }
                });
            }
        });
    }

    private void saveMacLocally(String mac) {
        SharedPreferences prefs = getSharedPreferences("tap_config", Context.MODE_PRIVATE);
        prefs.edit().putString("esp32_mac", mac).apply();
        Log.i(TAG, "MAC " + mac + " salvo localmente.");
    }
}