package com.example.choppontap;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

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
 * Dois modos de acesso:
 *   1. QR Code: lê o QR Code gerado em permissoes.php no ERP e valida via API.
 *   2. Senha: digita a senha de 6 dígitos (padrão 259087 ou via API).
 *
 * Fluxo QR Code:
 *   Botão "Acesso QR Code"
 *     → solicita permissão de câmera (se necessário)
 *     → abre scanner ZXing
 *     → lê "CHOPPON_MASTER:<64-char-hex-token>"
 *     → POST /api/validate_master_qr.php com JWT + qr_token + device_id
 *     → sucesso → liberarAcesso()
 */
public class AcessoMaster extends AppCompatActivity {

    private static final String TAG = "ACESSO_MASTER";

    private Button btnAcessoQrCode, btnAcessoSenha;
    private LinearLayout layoutQrAcesso;
    private TextInputLayout layoutInputSenha;
    private TextInputEditText edtSenhaAcesso;
    private ProgressBar progressQr;
    private TextView txtStatusQr;

    private String android_id;
    private ApiHelper apiHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Launcher para solicitar permissão de câmera
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    iniciarScannerQr();
                } else {
                    Toast.makeText(this, "Permissão de câmera necessária para ler QR Code", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_acesso_master);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        apiHelper  = new ApiHelper(this);

        btnAcessoQrCode  = findViewById(R.id.btnAcessoQrCode);
        btnAcessoSenha   = findViewById(R.id.btnAcessoSenha);
        layoutQrAcesso   = findViewById(R.id.layoutQrAcesso);
        layoutInputSenha = findViewById(R.id.layoutInputSenha);
        edtSenhaAcesso   = findViewById(R.id.edtSenhaAcesso);
        progressQr       = findViewById(R.id.progressQr);
        txtStatusQr      = findViewById(R.id.txtStatusQr);

        btnAcessoQrCode.setOnClickListener(v -> solicitarCameraEEscanear());
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

    // ── Câmera e Scanner ─────────────────────────────────────────────────────

    private void solicitarCameraEEscanear() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            iniciarScannerQr();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void iniciarScannerQr() {
        layoutQrAcesso.setVisibility(View.GONE);
        layoutInputSenha.setVisibility(View.GONE);

        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Aponte para o QR Code Master do ERP");
        integrator.setCameraId(0);
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String conteudo = result.getContents();
            if (conteudo == null) {
                Toast.makeText(this, "Leitura cancelada", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d(TAG, "[QR] Conteúdo lido: " + conteudo);
            processarQrCode(conteudo);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ── Processamento do QR Code ──────────────────────────────────────────────

    private void processarQrCode(String conteudo) {
        final String PREFIX = "CHOPPON_MASTER:";
        if (!conteudo.startsWith(PREFIX)) {
            Log.w(TAG, "[QR] Formato inválido: " + conteudo);
            mostrarErroQr("QR Code inválido. Use o QR Code gerado no painel ERP.");
            return;
        }
        String token = conteudo.substring(PREFIX.length()).trim();
        if (!token.matches("[0-9a-f]{64}")) {
            Log.w(TAG, "[QR] Token com formato inválido: " + token);
            mostrarErroQr("QR Code corrompido ou expirado. Gere um novo no ERP.");
            return;
        }
        Log.i(TAG, "[QR] Token válido, validando na API...");
        validarTokenNaApi(token);
    }

    private void mostrarErroQr(String mensagem) {
        runOnUiThread(() -> {
            progressQr.setVisibility(View.GONE);
            txtStatusQr.setVisibility(View.VISIBLE);
            txtStatusQr.setText(mensagem);
            txtStatusQr.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            layoutQrAcesso.setVisibility(View.VISIBLE);
        });
    }

    // ── Validação na API ────────────────────────────────────────────────────────────

    private void validarTokenNaApi(String qrToken) {
        runOnUiThread(() -> {
            layoutQrAcesso.setVisibility(View.VISIBLE);
            progressQr.setVisibility(View.VISIBLE);
            txtStatusQr.setVisibility(View.VISIBLE);
            txtStatusQr.setText("Validando QR Code...");
            txtStatusQr.setTextColor(getResources().getColor(android.R.color.darker_gray, null));
            btnAcessoQrCode.setEnabled(false);
            btnAcessoSenha.setEnabled(false);
        });

        Map<String, String> body = new HashMap<>();
        body.put("qr_token",  qrToken);
        body.put("device_id", android_id != null ? android_id : "");

        apiHelper.sendPost(body, "validate_master_qr", new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "[QR] Falha de rede: " + e.getMessage());
                runOnUiThread(() -> {
                    progressQr.setVisibility(View.GONE);
                    btnAcessoQrCode.setEnabled(true);
                    btnAcessoSenha.setEnabled(true);
                    mostrarErroQr("Erro de conexão. Verifique o Wi-Fi e tente novamente.");
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "[QR] Resposta API (" + response.code() + "): " + responseBody);
                runOnUiThread(() -> {
                    progressQr.setVisibility(View.GONE);
                    btnAcessoQrCode.setEnabled(true);
                    btnAcessoSenha.setEnabled(true);
                });
                try {
                    JSONObject json = new JSONObject(responseBody);
                    boolean success = json.optBoolean("success", false);
                    if (success) {
                        String userName = json.optString("user_name", "Técnico");
                        int    userType = json.optInt("user_type", 3);
                        Log.i(TAG, "[QR] Acesso autorizado para: " + userName);
                        runOnUiThread(() -> liberarAcesso(userName, userType));
                    } else {
                        String msg = json.optString("message", "QR Code inválido ou expirado.");
                        Log.w(TAG, "[QR] Acesso negado: " + msg);
                        runOnUiThread(() -> mostrarErroQr(msg));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[QR] Erro ao parsear resposta: " + e.getMessage());
                    runOnUiThread(() -> mostrarErroQr("Erro ao processar resposta do servidor."));
                }
            }
        });
    }

    // ── Acesso por Senha ────────────────────────────────────────────────────────────

    private void mostrarInputSenha() {
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

    // ── Liberar Acesso ──────────────────────────────────────────────────────────────

    private void liberarAcesso(String userName, int userType) {
        Log.i(TAG, "[ACESSO] Liberado para: " + userName);
        Toast.makeText(this, "Acesso Master Liberado — " + userName, Toast.LENGTH_SHORT).show();
        boolean fromOffline = getIntent().getBooleanExtra("from_offline", false);
        Intent intent = new Intent(AcessoMaster.this, ServiceTools.class);
        intent.putExtra("from_offline", fromOffline);
        intent.putExtra("master_user_name", userName);
        intent.putExtra("master_user_type", userType);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
