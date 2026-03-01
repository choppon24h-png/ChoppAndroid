package com.example.choppontap;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * FormaPagamento — Tela de seleção e acompanhamento do pagamento
 *
 * Fluxo:
 *  1. Usuário escolhe PIX, Débito ou Crédito
 *  2. App chama create_order.php → recebe checkout_id
 *  3. App inicia polling via verify_checkout.php (a cada 7s)
 *  4. Ao receber status "success" → navega para PagamentoConcluido
 *
 * CORREÇÕES v2.1.1 (Android):
 *  - verifyPayment: tratamento robusto de falhas de rede (onFailure)
 *  - verifyPayment: tratamento de respostas HTTP de erro (4xx / 5xx)
 *  - consecutiveErrors: após MAX_API_ERRORS falhas consecutivas do servidor,
 *    o polling é interrompido e o usuário é informado, evitando loop infinito
 *    quando o backend está com erro (ex: SQLSTATE[42S22]).
 *  - Logs detalhados para facilitar diagnóstico futuro
 */
public class FormaPagamento extends AppCompatActivity {
    private String android_id;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    private Handler handlerCountDown = new Handler(Looper.getMainLooper());
    private Runnable runnableCountDown;

    private static final int STATE_CHOOSING = 0;
    private static final int STATE_LOADING  = 1;
    private static final int STATE_PIX      = 2;
    private static final int STATE_CARD     = 3;

    private ImageView imageView;
    private volatile Boolean checkout_status = false;
    private EditText edt;
    private ConstraintLayout constLoader;
    private TextView txtPreloader;
    private Button btnPix, btnCard, btnCardDebit, btnCancelarCartao, btnVoltar, btnConfirmarPagamento;
    private String checkout_id = null;
    private CardView cardQrCode;
    private String quantidade;

    private LinearLayout layoutEscolhaPagamento, layoutQrPix, layoutInstrucaoCartao;
    private TextView txtTimerCartao, txtSetaPiscando, txtInstrucaoCartao;

    private static final String TAG = "PAGAMENTO_DEBUG";

    /**
     * Contador de erros consecutivos do servidor durante o polling.
     * Incrementado em onFailure e em respostas HTTP de erro (5xx).
     * Zerado ao receber qualquer resposta HTTP 200 válida.
     */
    private int consecutiveApiErrors = 0;

    /**
     * Número máximo de erros consecutivos tolerados antes de interromper o polling.
     * Com 7s de intervalo, 5 erros = ~35s de tentativas antes de desistir.
     */
    private static final int MAX_API_ERRORS = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_forma_pagamento);
        setupUI();
        loadInitialData();
    }

    private void setupUI() {
        constLoader   = findViewById(R.id.constLoader);
        txtPreloader  = findViewById(R.id.txtPreloader);
        imageView     = findViewById(R.id.imageView);
        cardQrCode    = findViewById(R.id.cardQrCode);

        btnPix                = findViewById(R.id.btnPix);
        btnCard               = findViewById(R.id.btnCard);
        btnCardDebit          = findViewById(R.id.btnCardDebit);
        btnCancelarCartao     = findViewById(R.id.btnCancelarCartao);
        btnVoltar             = findViewById(R.id.btnVoltar);
        btnConfirmarPagamento = findViewById(R.id.btnConfirmarPagamento);
        edt                   = findViewById(R.id.edtCpf);

        layoutEscolhaPagamento = findViewById(R.id.layoutEscolhaPagamento);
        layoutQrPix            = findViewById(R.id.layoutQrPix);
        layoutInstrucaoCartao  = findViewById(R.id.layoutInstrucaoCartao);
        txtTimerCartao         = findViewById(R.id.txtTimerCartao);
        txtSetaPiscando        = findViewById(R.id.txtSetaPiscando);
        txtInstrucaoCartao     = findViewById(R.id.txtInstrucaoCartao);

        setupFullscreen();
        setupCpfMask();

        btnPix.setOnClickListener(v -> handlePaymentClick("pix"));
        btnCard.setOnClickListener(v -> handlePaymentClick("credit"));
        btnCardDebit.setOnClickListener(v -> handlePaymentClick("debit"));
        btnCancelarCartao.setOnClickListener(v -> SendCardCancel());
        btnVoltar.setOnClickListener(v -> voltarParaHome());

        btnConfirmarPagamento.setOnClickListener(v -> {
            if (checkout_id != null) {
                Log.i(TAG, "👆 Confirmação MANUAL disparada.");
                verifyPayment(checkout_id);
            }
        });

        updateUIState(STATE_CHOOSING);
    }

    private void updateUIState(int state) {
        runOnUiThread(() -> {
            Log.d(TAG, "🔄 Mudando estado da UI para: " + state);
            layoutEscolhaPagamento.setVisibility(state == STATE_CHOOSING ? View.VISIBLE : View.GONE);
            constLoader.setVisibility(state == STATE_LOADING ? View.VISIBLE : View.GONE);
            layoutQrPix.setVisibility(state == STATE_PIX ? View.VISIBLE : View.GONE);
            layoutInstrucaoCartao.setVisibility(state == STATE_CARD ? View.VISIBLE : View.GONE);
            if (state == STATE_CHOOSING) changeButtonsFunction(true);
        });
    }

    private void handlePaymentClick(String method) {
        String cpfInput = edt.getText().toString();
        if (validateCpfFacultativo(cpfInput)) {
            Bundle extras = getIntent().getExtras();
            if (extras == null) return;
            String valorFormatado = String.format(Locale.US, "%.2f",
                    ((Number) extras.get("valor")).doubleValue());
            String desc = extras.get("descricao") != null
                    ? extras.get("descricao").toString()
                    : "Pagamento ChoppOn";
            sendRequest(valorFormatado, desc, quantidade, cpfInput, method);
        }
    }

    public void sendRequest(String valor, String descricao, String quantidade,
                            String cpf, String method) {
        updateUIState(STATE_LOADING);
        txtPreloader.setText("Gerando meio de pagamento...");

        Map<String, String> body = new HashMap<>();
        body.put("android_id",      android_id);
        body.put("cpf",             CpfMask.unmask(cpf).isEmpty() ? "11144477735" : CpfMask.unmask(cpf));
        body.put("valor",           valor);
        body.put("quantidade",      quantidade);
        body.put("descricao",       descricao);
        body.put("payment_method",  method);

        new ApiHelper().sendPost(body, "create_order.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "❌ create_order falhou na rede: " + e.getMessage());
                runOnUiThread(() -> showErrorMessage("Falha na rede. Tente novamente."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "";
                    Log.d(TAG, "📦 create_order resposta HTTP " + response.code() + ": " + json);

                    if (!response.isSuccessful() || json.isEmpty()) {
                        runOnUiThread(() -> showErrorMessage("Erro no servidor (" + response.code() + ")"));
                        return;
                    }

                    Qr qr = new Gson().fromJson(json, Qr.class);
                    if (qr != null && qr.checkout_id != null) {
                        checkout_id = qr.checkout_id;
                        consecutiveApiErrors = 0; // reset ao iniciar novo checkout

                        if (method.equals("pix")) {
                            updateUIState(STATE_PIX);
                            updateQrCode(qr);
                            startCountDown(180);
                            startVerifing(qr.checkout_id, 180);
                        } else {
                            runOnUiThread(() -> {
                                updateUIState(STATE_CARD);
                                String msg = "INSIRA OU APROXIME\nO CARTÃO";
                                if (qr.reader_name != null) msg += "\n\nNO LEITOR: " + qr.reader_name;
                                txtInstrucaoCartao.setText(msg);
                                startBlinkingSeta();
                            });
                            startCountDown(120);
                            startVerifing(qr.checkout_id, 120);
                        }
                    } else {
                        runOnUiThread(() -> showErrorMessage("Dados inválidos do servidor."));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro ao processar resposta create_order: " + e.getMessage());
                    runOnUiThread(() -> showErrorMessage("Erro ao processar pagamento."));
                }
            }
        });
    }

    private void showErrorMessage(String message) {
        runOnUiThread(() -> {
            updateUIState(STATE_CHOOSING);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void startBlinkingSeta() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (layoutInstrucaoCartao.getVisibility() == View.VISIBLE) {
                    txtSetaPiscando.setVisibility(
                            txtSetaPiscando.getVisibility() == View.VISIBLE
                                    ? View.INVISIBLE : View.VISIBLE);
                    handler.postDelayed(this, 500);
                }
            }
        });
    }

    private void voltarParaHome() {
        runOnUiThread(() -> {
            stopRunnable();
            startActivity(new Intent(this, Home.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
    }

    public void SendCardCancel() {
        if (checkout_id != null) {
            Map<String, String> body = new HashMap<>();
            body.put("android_id",  android_id);
            body.put("checkout_id", checkout_id);
            new ApiHelper().sendPost(body, "cancel_order.php", new Callback() {
                @Override public void onFailure(Call call, IOException e) {}
                @Override public void onResponse(Call call, Response response) throws IOException {}
            });
        }
        voltarParaHome();
    }

    public void startVerifing(String checkout_id, int totalSeconds) {
        if (checkout_id == null) return;
        this.checkout_id = checkout_id;

        final int delay         = 7000; // polling a cada 7 segundos
        final int maxIterations = totalSeconds / (delay / 1000);

        runnable = new Runnable() {
            int i = 0;

            public void run() {
                if (checkout_status) {
                    Log.i(TAG, "🚀 Transição para sucesso confirmada!");
                    navigateToSuccess();
                } else if (i >= maxIterations) {
                    Log.w(TAG, "⏰ Timeout atingido após " + i + " tentativas. Voltando para Home.");
                    voltarParaHome();
                } else {
                    Log.d(TAG, "📡 Consultando status (tentativa " + (i + 1) + "/" + maxIterations + ")...");
                    verifyPayment(FormaPagamento.this.checkout_id);
                    i++;
                    handler.postDelayed(this, delay);
                }
            }
        };
        handler.postDelayed(runnable, 3000);
    }

    private void navigateToSuccess() {
        runOnUiThread(() -> {
            stopRunnable();
            Intent it = new Intent(this, PagamentoConcluido.class);
            it.putExtra("qtd_ml",     quantidade);
            it.putExtra("checkout_id", checkout_id);
            startActivity(it);
            finish();
        });
    }

    /**
     * Verifica o status do pagamento junto ao servidor.
     *
     * CORREÇÃO v2.1.1:
     *  - onFailure: incrementa consecutiveApiErrors e loga a falha de rede.
     *    O polling continua, mas se atingir MAX_API_ERRORS, o loop é interrompido
     *    e o usuário é avisado, evitando que o app fique preso silenciosamente.
     *
     *  - onResponse com HTTP de erro (4xx/5xx): incrementa consecutiveApiErrors.
     *    Antes desta correção, o app simplesmente ignorava a resposta de erro e
     *    continuava o loop indefinidamente sem nunca receber "success".
     *
     *  - onResponse com HTTP 200: zera consecutiveApiErrors e processa o JSON.
     */
    public void verifyPayment(String checkout_id) {
        if (checkout_id == null) return;

        Map<String, String> body = new HashMap<>();
        body.put("android_id",  android_id);
        body.put("checkout_id", checkout_id);

        new ApiHelper().sendPost(body, "verify_checkout.php", new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                consecutiveApiErrors++;
                Log.e(TAG, "❌ Falha de REDE na verificação (erro " + consecutiveApiErrors
                        + "/" + MAX_API_ERRORS + "): " + e.getMessage());

                if (consecutiveApiErrors >= MAX_API_ERRORS) {
                    Log.e(TAG, "🛑 Muitos erros de rede consecutivos. Interrompendo polling.");
                    runOnUiThread(() -> {
                        stopRunnable();
                        Toast.makeText(FormaPagamento.this,
                                "Sem conexão com o servidor. Verifique sua rede.",
                                Toast.LENGTH_LONG).show();
                        updateUIState(STATE_CHOOSING);
                    });
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "";
                    Log.d(TAG, "🔍 RESPOSTA VERIFICAÇÃO HTTP " + response.code() + ": " + json);

                    // ── Tratar erros HTTP (4xx / 5xx) ────────────────────────
                    if (!response.isSuccessful()) {
                        consecutiveApiErrors++;
                        Log.e(TAG, "❌ Servidor retornou HTTP " + response.code()
                                + " (erro " + consecutiveApiErrors + "/" + MAX_API_ERRORS + ")");

                        if (consecutiveApiErrors >= MAX_API_ERRORS) {
                            Log.e(TAG, "🛑 Muitos erros do servidor. Interrompendo polling.");
                            runOnUiThread(() -> {
                                stopRunnable();
                                Toast.makeText(FormaPagamento.this,
                                        "Erro no servidor de pagamento. Tente novamente.",
                                        Toast.LENGTH_LONG).show();
                                updateUIState(STATE_CHOOSING);
                            });
                        }
                        return;
                    }

                    // ── Resposta HTTP 200 — zerar contador de erros ───────────
                    consecutiveApiErrors = 0;

                    if (json.isEmpty()) {
                        Log.w(TAG, "⚠️ Resposta vazia do servidor.");
                        return;
                    }

                    CheckoutResponse cr = new Gson().fromJson(json, CheckoutResponse.class);

                    if (cr == null) {
                        Log.w(TAG, "⚠️ JSON inválido na resposta de verificação.");
                        return;
                    }

                    // ── Verificar status de sucesso ───────────────────────────
                    boolean isSuccess = cr.status != null
                            && (cr.status.equalsIgnoreCase("success")
                                || (cr.checkout_status != null
                                    && cr.checkout_status.equalsIgnoreCase("SUCCESSFUL")));

                    if (isSuccess) {
                        Log.i(TAG, "💰 PAGAMENTO APROVADO! Redirecionando para PagamentoConcluido...");
                        checkout_status = true;
                        navigateToSuccess();

                    } else if (cr.status != null && cr.status.equalsIgnoreCase("failed")) {
                        Log.w(TAG, "💔 Pagamento RECUSADO: " + cr.checkout_status);
                        runOnUiThread(() -> {
                            stopRunnable();
                            Toast.makeText(FormaPagamento.this,
                                    "Pagamento não aprovado. Tente novamente.",
                                    Toast.LENGTH_LONG).show();
                            updateUIState(STATE_CHOOSING);
                        });

                    } else {
                        Log.d(TAG, "⏳ Pagamento pendente: "
                                + (cr.checkout_status != null ? cr.checkout_status : "WAITING"));
                    }

                } catch (Exception e) {
                    Log.e(TAG, "❌ Erro no parse da verificação: " + e.getMessage());
                }
            }
        });
    }

    public void startCountDown(int seconds) {
        runnableCountDown = new Runnable() {
            int i = 1;

            public void run() {
                if (i <= seconds) {
                    final int currentI = i;
                    runOnUiThread(() -> {
                        String t = (seconds - currentI) + "s";
                        if (layoutInstrucaoCartao.getVisibility() == View.VISIBLE) {
                            txtTimerCartao.setText(t);
                        } else {
                            TextView tv = findViewById(R.id.txtTimer);
                            if (tv != null) tv.setText(t);
                        }
                    });
                    i++;
                    handlerCountDown.postDelayed(this, 1000);
                }
            }
        };
        handlerCountDown.postDelayed(runnableCountDown, 1000);
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    private void loadInitialData() {
        android_id = Settings.Secure.getString(
                this.getContentResolver(), Settings.Secure.ANDROID_ID);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            quantidade = extras.get("quantidade").toString();
            ((TextView) findViewById(R.id.txtValor)).setText(
                    "R$ " + String.format("%.2f", extras.get("valor")).replace(".", ","));
        }
    }

    private void setupCpfMask() {
        edt.addTextChangedListener(CpfMask.insert(edt));
    }

    private boolean validateCpfFacultativo(String cpf) {
        String c = CpfMask.unmask(cpf);
        if (c.isEmpty()) return true;
        ValidaCPF v = new ValidaCPF();
        if (c.length() != 11 || !v.isCPF(c)) {
            Toast.makeText(this, "CPF inválido", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    public void changeButtonsFunction(Boolean enabled) {
        Sqlite banco = new Sqlite(getApplicationContext());
        boolean card = enabled && banco.getCartaoEnabled();
        runOnUiThread(() -> {
            btnPix.setEnabled(enabled);
            btnCard.setEnabled(card);
            btnCardDebit.setEnabled(card);
            int color = enabled ? Color.parseColor("#FF8C00") : Color.GRAY;
            btnPix.setBackgroundColor(color);
            btnCard.setBackgroundColor(card ? Color.parseColor("#FF8C00") : Color.GRAY);
            btnCardDebit.setBackgroundColor(card ? Color.parseColor("#FF8C00") : Color.GRAY);
        });
    }

    public void updateQrCode(Qr qr) {
        try {
            byte[] b   = Base64.decode(qr.qr_code, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            runOnUiThread(() -> imageView.setImageBitmap(bmp));
        } catch (Exception e) {
            Log.e(TAG, "Erro ao decodificar QR Code: " + e.getMessage());
        }
    }

    private void stopRunnable() {
        if (runnable != null)          handler.removeCallbacks(runnable);
        if (runnableCountDown != null) handlerCountDown.removeCallbacks(runnableCountDown);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRunnable();
    }
}
