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
    
    // Estados da UI para evitar tela branca
    private static final int STATE_CHOOSING = 0;
    private static final int STATE_LOADING = 1;
    private static final int STATE_PIX = 2;
    private static final int STATE_CARD = 3;

    private ImageView imageView;
    private Boolean checkout_status = false;
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
    private int retryCount = 0;
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
            if (checkout_id != null) verifyPayment(checkout_id);
        });

        updateUIState(STATE_CHOOSING);
    }

    /**
     * ✅ GERENCIADOR DE ESTADO SÊNIOR
     * Garante que os containers corretos fiquem visíveis e limpa o vácuo visual.
     */
    private void updateUIState(int state) {
        runOnUiThread(() -> {
            Log.d(TAG, "🔄 Mudando estado da UI para: " + state);
            
            // Esconder tudo primeiro
            layoutEscolhaPagamento.setVisibility(View.GONE);
            constLoader.setVisibility(View.GONE);
            layoutQrPix.setVisibility(View.GONE);
            layoutInstrucaoCartao.setVisibility(View.GONE);

            // Mostrar apenas o necessário
            switch (state) {
                case STATE_CHOOSING:
                    layoutEscolhaPagamento.setVisibility(View.VISIBLE);
                    changeButtonsFunction(true);
                    break;
                case STATE_LOADING:
                    constLoader.setVisibility(View.VISIBLE);
                    break;
                case STATE_PIX:
                    layoutQrPix.setVisibility(View.VISIBLE);
                    break;
                case STATE_CARD:
                    layoutInstrucaoCartao.setVisibility(View.VISIBLE);
                    break;
            }
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
                Log.e(TAG, "Falha na rede: " + e.getMessage());
                showErrorMessage("Erro de conexão. Tente novamente.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "";
                    Log.d(TAG, "✅ Resposta API: " + json);

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
                            // Configura instrução do cartão com dados do leitor
                            runOnUiThread(() -> {
                                updateUIState(STATE_CARD);
                                String msg = "INSIRA OU APROXIME\nO CARTÃO";
                                if (qr.reader_name != null) msg += "\n\nNO LEITOR: " + qr.reader_name;
                                txtInstrucaoCartao.setText(msg);
                                startBlinkingSeta();
                            });
                            startCountDown(80);
                            startVerifing(qr.checkout_id, 80);
                        }
                    } else {
                        runOnUiThread(() -> showErrorMessage("Resposta inválida do servidor."));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Erro processamento: " + e.getMessage());
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

    private void resetPaymentState() {
        stopRunnable();
        checkout_id = null;
        updateUIState(STATE_CHOOSING);
    }

    private void voltarParaHome() {
        stopRunnable();
        startActivity(new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
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

    // --- Métodos de Suporte (Timer, Bluetooth, Fullscreen, etc.) permanecem inalterados ---
    private void setupFullscreen() {
        WindowInsetsControllerCompat windowInsetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    private void loadInitialData() {
        android_id = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            quantidade = extras.get("quantidade").toString();
            TextView txtValor = findViewById(R.id.txtValor);
            txtValor.setText("R$ " + String.format("%.2f", extras.get("valor")).replace(".", ","));
        }
    }

    private void setupCpfMask() {
        edt.addTextChangedListener(CpfMask.insert(edt));
    }

    private boolean validateCpfFacultativo(String cpf) {
        String cleanCpf = CpfMask.unmask(cpf);
        if (cleanCpf.isEmpty()) return true;
        ValidaCPF valida = new ValidaCPF();
        if (cleanCpf.length() != 11 || !valida.isCPF(cleanCpf)) {
            Toast.makeText(this, "CPF inválido", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    public void startVerifing(String checkout_id, int totalSeconds) {
        if (checkout_id == null) return;
        final int delay = 5000;
        final int maxIterations = totalSeconds / (delay / 1000);
        runnable = new Runnable() {
            int i = 0;
            public void run() {
                if (checkout_status) navigateToSuccess();
                else if (i >= maxIterations) voltarParaHome();
                else {
                    verifyPayment(checkout_id);
                    i++;
                    handler.postDelayed(this, delay);
                }
            }
        };
        handler.postDelayed(runnable, delay);
    }

    private void navigateToSuccess() {
        stopRunnable();
        startActivity(new Intent(this, PagamentoConcluido.class).putExtra("qtd_ml", quantidade).putExtra("checkout_id", checkout_id));
        finish();
    }

    public void verifyPayment(String checkout_id) {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("checkout_id", checkout_id);
        new ApiHelper().sendPost(body, "verify_checkout.php", new Callback() {
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    CheckoutResponse cr = new Gson().fromJson(rb.string(), CheckoutResponse.class);
                    if (cr != null && "success".equals(cr.status)) checkout_status = true;
                } catch (Exception e) {}
            }
            @Override public void onFailure(Call call, IOException e) {}
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
                        else ((TextView)findViewById(R.id.txtTimer)).setText(t);
                    });
                    i++;
                    handlerCountDown.postDelayed(this, 1000);
                }
            }
        };
        handlerCountDown.postDelayed(runnableCountDown, 1000);
    }

    public void changeButtonsFunction(Boolean enabled) {
        Sqlite banco = new Sqlite(getApplicationContext());
        boolean card = enabled && banco.getCartaoEnabled();
        runOnUiThread(() -> {
            btnPix.setEnabled(enabled);
            btnCard.setEnabled(card);
            btnCardDebit.setEnabled(card);
            int c = enabled ? Color.parseColor("#FF8C00") : Color.GRAY;
            btnPix.setBackgroundColor(c);
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
