package com.example.choppontap;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * AcessoMaster — Tela de autenticação para acesso ao ServiceTools.
 *
 * NOVO FLUXO QR Code (lógica invertida):
 *   1. Android gera token via API (POST /api/request_master_qr.php?action=generate)
 *   2. Android exibe QR Code na tela com o token
 *   3. Admin no ERP (permissoes.php) clica "Habilitar", abre câmera do PC,
 *      escaneia o QR Code do tablet e aprova
 *   4. Android faz polling a cada 3s (POST /api/request_master_qr.php?action=poll)
 *   5. Ao receber status=approved → abre ServiceTools
 *
 * Fluxo Senha:
 *   Digita 6 dígitos → 259087 libera localmente, outros validam via API
 */
public class AcessoMaster extends AppCompatActivity {

    private static final String TAG          = "ACESSO_MASTER";
    private static final int    POLL_INTERVAL_MS = 3000;  // 3 segundos
    private static final int    QR_EXPIRY_MS     = 300000; // 5 minutos

    // Views
    private Button          btnAcessoQrCode, btnAcessoSenha;
    private LinearLayout    layoutQrAcesso;
    private TextInputLayout layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ProgressBar     progressQr;
    private TextView        txtStatusQr;
    private ImageView       imgQrCode;

    // Estado
    private String  android_id;
    private int     mTokenId    = -1;
    private boolean mPolling    = false;
    private boolean mAprovado   = false;

    private ApiHelper       apiHelper;
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ── Runnable de polling ───────────────────────────────────────────────────
    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mPolling || mAprovado) return;
            verificarStatusToken();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acesso_master);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        apiHelper  = new ApiHelper(this);

        // Bind views
        btnAcessoQrCode  = findViewById(R.id.btnAcessoQrCode);
        btnAcessoSenha   = findViewById(R.id.btnAcessoSenha);
        layoutQrAcesso   = findViewById(R.id.layoutQrAcesso);
        layoutInputSenha = findViewById(R.id.layoutInputSenha);
        edtSenhaAcesso   = findViewById(R.id.edtSenhaAcesso);
        progressQr       = findViewById(R.id.progressQr);
        txtStatusQr      = findViewById(R.id.txtStatusQr);
        imgQrCode        = findViewById(R.id.imgQrCode);

        btnAcessoQrCode.setOnClickListener(v -> iniciarFluxoQrCode());
        btnAcessoSenha.setOnClickListener(v -> mostrarInputSenha());

        edtSenhaAcesso.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() == 6) {
                    String senha = s.toString();
                    if (senha.equals("259087")) {
                        liberarAcesso("Acesso Master", 1);
                    } else {
                        validarSenhaAPI(senha);
                    }
                }
            }
        });
    }

    // ── Fluxo QR Code ────────────────────────────────────────────────────────

    /**
     * Passo 1: solicitar token ao servidor e exibir QR Code
     */
    private void iniciarFluxoQrCode() {
        // Resetar estado anterior
        pararPolling();
        mTokenId  = -1;
        mAprovado = false;

        // Mostrar área QR com loading
        layoutQrAcesso.setVisibility(View.VISIBLE);
        layoutInputSenha.setVisibility(View.GONE);
        imgQrCode.setVisibility(View.GONE);
        progressQr.setVisibility(View.VISIBLE);
        txtStatusQr.setVisibility(View.VISIBLE);
        txtStatusQr.setText("Gerando QR Code...");
        btnAcessoQrCode.setEnabled(false);
        btnAcessoSenha.setEnabled(false);

        Map<String, String> body = new HashMap<>();
        body.put("action",    "generate");
        body.put("device_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "request_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "[QR] Falha ao gerar token: " + e.getMessage());
                runOnUiThread(() -> {
                    progressQr.setVisibility(View.GONE);
                    txtStatusQr.setText("Erro de conexão. Verifique o Wi-Fi.");
                    btnAcessoQrCode.setEnabled(true);
                    btnAcessoSenha.setEnabled(true);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "[QR] Resposta generate: " + rb);
                try {
                    JSONObject json = new JSONObject(rb);
                    if (json.optBoolean("success", false)) {
                        mTokenId = json.optInt("token_id", -1);
                        String qrData = json.optString("qr_data", "");
                        runOnUiThread(() -> exibirQrCode(qrData));
                    } else {
                        String msg = json.optString("message", "Erro ao gerar QR Code.");
                        runOnUiThread(() -> {
                            progressQr.setVisibility(View.GONE);
                            txtStatusQr.setText(msg);
                            btnAcessoQrCode.setEnabled(true);
                            btnAcessoSenha.setEnabled(true);
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[QR] Erro ao parsear generate: " + e.getMessage());
                    runOnUiThread(() -> {
                        progressQr.setVisibility(View.GONE);
                        txtStatusQr.setText("Erro ao processar resposta do servidor.");
                        btnAcessoQrCode.setEnabled(true);
                        btnAcessoSenha.setEnabled(true);
                    });
                }
            }
        });
    }

    /**
     * Passo 2: gerar bitmap do QR Code localmente e exibir na tela
     */
    private void exibirQrCode(String qrData) {
        progressQr.setVisibility(View.GONE);
        try {
            Bitmap bmp = gerarBitmapQr(qrData, 600);
            imgQrCode.setImageBitmap(bmp);
            imgQrCode.setVisibility(View.VISIBLE);
            txtStatusQr.setText("Aguardando aprovação do administrador...\nAponte a câmera do PC para este QR Code em Permissões.");
            txtStatusQr.setVisibility(View.VISIBLE);
            Log.i(TAG, "[QR] QR Code exibido. token_id=" + mTokenId + " | qr_data=" + qrData);

            // Iniciar polling
            iniciarPolling();

            // Timer de expiração: após 5min, cancelar e avisar
            mainHandler.postDelayed(() -> {
                if (!mAprovado) {
                    pararPolling();
                    txtStatusQr.setText("QR Code expirado. Toque em 'Acesso QR Code' para gerar um novo.");
                    imgQrCode.setVisibility(View.GONE);
                    btnAcessoQrCode.setEnabled(true);
                    btnAcessoSenha.setEnabled(true);
                }
            }, QR_EXPIRY_MS);

        } catch (WriterException e) {
            Log.e(TAG, "[QR] Erro ao gerar bitmap: " + e.getMessage());
            txtStatusQr.setText("Erro ao gerar QR Code. Tente novamente.");
            btnAcessoQrCode.setEnabled(true);
            btnAcessoSenha.setEnabled(true);
        }
    }

    /**
     * Gera Bitmap do QR Code usando ZXing (sem câmera — apenas geração)
     */
    private Bitmap gerarBitmapQr(String content, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bmp;
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void iniciarPolling() {
        mPolling = true;
        mainHandler.postDelayed(pollingRunnable, POLL_INTERVAL_MS);
        Log.d(TAG, "[QR] Polling iniciado. token_id=" + mTokenId);
    }

    private void pararPolling() {
        mPolling = false;
        mainHandler.removeCallbacks(pollingRunnable);
        Log.d(TAG, "[QR] Polling parado.");
    }

    /**
     * Passo 3: verificar se o admin aprovou o token
     */
    private void verificarStatusToken() {
        if (mTokenId <= 0) return;

        Map<String, String> body = new HashMap<>();
        body.put("action",    "poll");
        body.put("token_id",  String.valueOf(mTokenId));
        body.put("device_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "request_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.w(TAG, "[QR] Polling falhou (rede): " + e.getMessage());
                // Continuar polling mesmo com falha de rede temporária
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "[QR] Poll resposta: " + rb);
                try {
                    JSONObject json = new JSONObject(rb);
                    String status = json.optString("status", "pending");

                    switch (status) {
                        case "approved":
                            mAprovado = true;
                            pararPolling();
                            String userName = json.optString("user_name", "Técnico");
                            int    userType = json.optInt("user_type", 3);
                            Log.i(TAG, "[QR] APROVADO para: " + userName);
                            runOnUiThread(() -> liberarAcesso(userName, userType));
                            break;

                        case "rejected":
                            pararPolling();
                            Log.w(TAG, "[QR] REJEITADO pelo admin.");
                            runOnUiThread(() -> {
                                imgQrCode.setVisibility(View.GONE);
                                txtStatusQr.setText("Acesso negado pelo administrador.");
                                btnAcessoQrCode.setEnabled(true);
                                btnAcessoSenha.setEnabled(true);
                            });
                            break;

                        case "expired":
                            pararPolling();
                            Log.w(TAG, "[QR] Token expirado.");
                            runOnUiThread(() -> {
                                imgQrCode.setVisibility(View.GONE);
                                txtStatusQr.setText("QR Code expirado. Toque em 'Acesso QR Code' para gerar um novo.");
                                btnAcessoQrCode.setEnabled(true);
                                btnAcessoSenha.setEnabled(true);
                            });
                            break;

                        default: // pending
                            Log.d(TAG, "[QR] Aguardando aprovação...");
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[QR] Erro ao parsear poll: " + e.getMessage());
                }
            }
        });
    }

    // ── Acesso por Senha ──────────────────────────────────────────────────────

    private void mostrarInputSenha() {
        pararPolling();
        layoutInputSenha.setVisibility(View.VISIBLE);
        layoutQrAcesso.setVisibility(View.GONE);
        edtSenhaAcesso.requestFocus();
    }

    private void validarSenhaAPI(String senha) {
        Toast.makeText(this, "Validando senha...", Toast.LENGTH_SHORT).show();
        Map<String, String> body = new HashMap<>();
        body.put("senha",     senha);
        body.put("device_id", android_id != null ? android_id : "");
        apiHelper.sendPost(body, "validate_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                        "Erro de conexão ao validar senha", Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String rb = response.body() != null ? response.body().string() : "";
                try {
                    JSONObject json = new JSONObject(rb);
                    if (json.optBoolean("success", false)) {
                        runOnUiThread(() -> liberarAcesso(
                                json.optString("user_name", "Técnico"),
                                json.optInt("user_type", 3)));
                    } else {
                        runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                                "Senha inválida", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(AcessoMaster.this,
                            "Erro ao validar senha", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    // ── Liberar Acesso ────────────────────────────────────────────────────────

    private void liberarAcesso(String userName, int userType) {
        Log.i(TAG, "[ACESSO] Liberado para: " + userName);
        Toast.makeText(this, "Acesso Master Liberado — " + userName, Toast.LENGTH_SHORT).show();
        boolean fromOffline = getIntent().getBooleanExtra("from_offline", false);
        Intent intent = new Intent(AcessoMaster.this, ServiceTools.class);
        intent.putExtra("from_offline",      fromOffline);
        intent.putExtra("master_user_name",  userName);
        intent.putExtra("master_user_type",  userType);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pararPolling();
        executor.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Manter polling em background (usuário pode minimizar enquanto admin aprova)
    }
}
