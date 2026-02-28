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

public class FormaPagamento extends AppCompatActivity {
    private String android_id;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    private Handler handlerCountDown = new Handler(Looper.getMainLooper());
    private Runnable runnableCountDown;
    
    private static final int STATE_CHOOSING = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_PIX = 2;
    private static final int STATE_CARD = 3;

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
    private int consecutiveErrors = 0;
    private static final int MAX_RETRIES = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_forma_pagamento);
        setupUI();
        loadInitialData();
    }

    private void setupUI() {
        constLoader = findViewById(R.id.constLoader);
        txtPreloader = findViewById(R.id.txtPreloader);
        imageView = findViewById(R.id.imageView);
        cardQrCode = findViewById(R.id.cardQrCode);
        
        btnPix = findViewById(R.id.btnPix);
        btnCard = findViewById(R.id.btnCard);
        btnCardDebit = findViewById(R.id.btnCardDebit);
        btnCancelarCartao = findViewById(R.id.btnCancelarCartao);
        btnVoltar = findViewById(R.id.btnVoltar);
        btnConfirmarPagamento = findViewById(R.id.btnConfirmarPagamento);
        edt = findViewById(R.id.edtCpf);
        
        layoutEscolhaPagamento = findViewById(R.id.layoutEscolhaPagamento);
        layoutQrPix = findViewById(R.id.layoutQrPix);
        layoutInstrucaoCartao = findViewById(R.id.layoutInstrucaoCartao);
        txtTimerCartao = findViewById(R.id.txtTimerCartao);
        txtSetaPiscando = findViewById(R.id.txtSetaPiscando);
        txtInstrucaoCartao = findViewById(R.id.txtInstrucaoCartao);

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
            String valorFormatado = String.format(Locale.US, "%.2f", ((Number) extras.get("valor")).doubleValue());
            String desc = extras.get("descricao") != null ? extras.get("descricao").toString() : "Pagamento ChoppOn";
            sendRequest(valorFormatado, desc, quantidade, cpfInput, method);
        }
    }

    public void sendRequest(String valor, String descricao, String quantidade, String cpf, String method) {
        updateUIState(STATE_LOADING);
        txtPreloader.setText("Gerando meio de pagamento...");

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("cpf", CpfMask.unmask(cpf).isEmpty() ? "11144477735" : CpfMask.unmask(cpf));
        body.put("valor", valor);
        body.put("quantidade", quantidade);
        body.put("descricao", descricao);
        body.put("payment_method", method);

        new ApiHelper().sendPost(body, "create_order.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> showErrorMessage("Falha na rede. Tente novamente."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "";
                    if (!response.isSuccessful() || json.isEmpty()) {
                        runOnUiThread(() -> showErrorMessage("Erro no servidor (" + response.code() + ")"));
                        return;
                    }

                    Qr qr = new Gson().fromJson(json, Qr.class);
                    if (qr != null && qr.checkout_id != null) {
                        checkout_id = qr.checkout_id;
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
                            startCountDown(60);
                            startVerifing(qr.checkout_id, 60);
                        }
                    } else {
                        runOnUiThread(() -> showErrorMessage("Dados inválidos do servidor."));
                    }
                } catch (Exception e) {
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
            @Override public void run() {
                if (layoutInstrucaoCartao.getVisibility() == View.VISIBLE) {
                    txtSetaPiscando.setVisibility(txtSetaPiscando.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
                    handler.postDelayed(this, 500);
                }
            }
        });
    }

    private void voltarParaHome() {
        runOnUiThread(() -> {
            stopRunnable();
            startActivity(new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
    }

    public void SendCardCancel() {
        if (checkout_id != null) {
            Map<String, String> body = new HashMap<>();
            body.put("android_id", android_id);
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
        final int delay = 7000; // ✅ OTIMIZADO PARA 7s
        final int maxIterations = totalSeconds / (delay / 1000);
        runnable = new Runnable() {
            int i = 0;
            public void run() {
                if (checkout_status) {
                    Log.i(TAG, "🚀 Transição para sucesso confirmada!");
                    navigateToSuccess();
                } else if (i >= maxIterations) {
                    voltarParaHome();
                } else {
                    Log.d(TAG, "📡 Consultando status (tentativa " + (i+1) + ")...");
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
            it.putExtra("qtd_ml", quantidade);
            it.putExtra("checkout_id", checkout_id);
            startActivity(it);
            finish();
        });
    }

    public void verifyPayment(String checkout_id) {
        if (checkout_id == null) return;
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("checkout_id", checkout_id);

        new ApiHelper().sendPost(body, "verify_checkout.php", new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "";
                    Log.d(TAG, "🔍 RESPOSTA VERIFICAÇÃO: " + json);
                    
                    if (response.isSuccessful() && !json.isEmpty()) {
                        CheckoutResponse cr = new Gson().fromJson(json, CheckoutResponse.class);
                        
                        // Aceita variações de "success" ou status "SUCCESSFUL" da SumUp
                        if (cr.status != null && (cr.status.equalsIgnoreCase("success") || 
                            (cr.checkout_status != null && cr.checkout_status.equalsIgnoreCase("SUCCESSFUL")))) {
                            
                            Log.i(TAG, "💰 PAGAMENTO APROVADO! Redirecionando...");
                            checkout_status = true;
                            navigateToSuccess();
                        } else {
                            Log.d(TAG, "⏳ Pagamento ainda pendente: " + (cr.checkout_status != null ? cr.checkout_status : "WAITING"));
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro parse verificação: " + e.getMessage());
                }
            }
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Erro de rede na verificação: " + e.getMessage());
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
                        if (layoutInstrucaoCartao.getVisibility() == View.VISIBLE) txtTimerCartao.setText(t);
                        else {
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
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    private void loadInitialData() {
        android_id = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            quantidade = extras.get("quantidade").toString();
            ((TextView)findViewById(R.id.txtValor)).setText("R$ " + String.format("%.2f", extras.get("valor")).replace(".", ","));
        }
    }

    private void setupCpfMask() { edt.addTextChangedListener(CpfMask.insert(edt)); }

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
            btnPix.setEnabled(enabled); btnCard.setEnabled(card); btnCardDebit.setEnabled(card);
            int color = enabled ? Color.parseColor("#FF8C00") : Color.GRAY;
            btnPix.setBackgroundColor(color);
            btnCard.setBackgroundColor(card ? Color.parseColor("#FF8C00") : Color.GRAY);
            btnCardDebit.setBackgroundColor(card ? Color.parseColor("#FF8C00") : Color.GRAY);
        });
    }

    public void updateQrCode(Qr qr) {
        try {
            byte[] b = Base64.decode(qr.qr_code, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            runOnUiThread(() -> imageView.setImageBitmap(bmp));
        } catch (Exception e) {}
    }

    private void stopRunnable() {
        if (runnable != null) handler.removeCallbacks(runnable);
        if (runnableCountDown != null) handlerCountDown.removeCallbacks(runnableCountDown);
    }

    @Override protected void onDestroy() { super.onDestroy(); stopRunnable(); }
}
