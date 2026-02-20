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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    
    // Handler para Inatividade
    private Handler inactivityHandler = new Handler(Looper.getMainLooper());
    private Runnable inactivityRunnable = () -> {
        Log.d(TAG, "Inatividade detectada. Voltando para Home.");
        voltarParaHome();
    };

    private ImageView imageView;
    private Boolean checkout_status = false;
    private EditText edt;
    private Snackbar snackbar = null;
    private ConstraintLayout constraintLayout;
    private TextView txtPreloader;
    private Button btnPix;
    private Button btnCard;
    private Button btnCardDebit;
    private Button btnCancelarCredito;
    private Button btnVoltar;
    private Button btnConfirmarPagamento;
    private String checkout_id = null;
    private CardView cardQrCode;
    private String quantidade;
    
    // ✅ NOVO: Referências para ocultar elementos da escolha
    private LinearLayout layoutBotoes;
    private TextView txtTituloForma;
    private View inputLayoutCpf;
    private TextView textView6; // Label do CPF

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
        resetInactivityTimer();
    }

    private void setupUI() {
        constraintLayout = findViewById(R.id.constLoader);
        txtPreloader = findViewById(R.id.txtPreloader);
        imageView = findViewById(R.id.imageView);
        cardQrCode = findViewById(R.id.cardQrCode);
        
        btnPix = findViewById(R.id.btnPix);
        btnCard = findViewById(R.id.btnCard);
        btnCardDebit = findViewById(R.id.btnCardDebit);
        btnCancelarCredito = findViewById(R.id.btnCancelarCredito);
        btnVoltar = findViewById(R.id.btnVoltar);
        btnConfirmarPagamento = findViewById(R.id.btnConfirmarPagamento);
        edt = findViewById(R.id.edtCpf);
        
        // ✅ NOVO: Inicializando referências de UI
        layoutBotoes = findViewById(R.id.layoutBotoes);
        txtTituloForma = findViewById(R.id.txtTituloForma);
        inputLayoutCpf = findViewById(R.id.inputLayoutCpf);
        textView6 = findViewById(R.id.textView6);

        if (textView6 != null) textView6.setText("Informe seu CPF para pontuar");

        setupFullscreen();
        setupCpfMask();
        
        btnPix.setOnClickListener(v -> handlePaymentClick("pix"));
        btnCard.setOnClickListener(v -> handlePaymentClick("credit"));
        btnCardDebit.setOnClickListener(v -> handlePaymentClick("debit"));
        btnCancelarCredito.setOnClickListener(v -> SendCardCancel());
        btnVoltar.setOnClickListener(v -> voltarParaHome());
        
        btnConfirmarPagamento.setOnClickListener(v -> {
            if (checkout_id != null) {
                Toast.makeText(this, "Verificando pagamento...", Toast.LENGTH_SHORT).show();
                verifyPayment(checkout_id);
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        resetInactivityTimer();
        return super.dispatchTouchEvent(ev);
    }

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable);
        inactivityHandler.postDelayed(inactivityRunnable, 60000);
    }

    private void voltarParaHome() {
        runOnUiThread(() -> {
            resetPaymentState();
            Intent intent = new Intent(FormaPagamento.this, Home.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetInactivityTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inactivityHandler.removeCallbacks(inactivityRunnable);
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
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

            Sqlite banco = new Sqlite(getApplicationContext());
            boolean cartaoEnabled = banco.getCartaoEnabled();
            Log.d(TAG, "Cartao habilitado no banco: " + cartaoEnabled);
            
            if (!cartaoEnabled) {
                Log.w(TAG, "Desabilitando botões de cartão (banco retornou false)");
                disableCardButtons();
            } else {
                Log.i(TAG, "Botões de cartão habilitados (banco retornou true)");
            }
        }
    }

    private void disableCardButtons() {
        runOnUiThread(() -> {
            btnCard.setEnabled(false);
            btnCardDebit.setEnabled(false);
            btnCard.setBackgroundColor(Color.GRAY);
            btnCardDebit.setBackgroundColor(Color.GRAY);
        });
    }

    private void handlePaymentClick(String method) {
        String cpfInput = edt.getText().toString();
        if (validateCpfFacultativo(cpfInput)) {
            resetPaymentState();
            Bundle extras = getIntent().getExtras();
            if (extras == null) return;

            Object valorObj = extras.get("valor");
            String valorFormatado;
            if (valorObj instanceof Float || valorObj instanceof Double) {
                valorFormatado = String.format(Locale.US, "%.2f", ((Number) valorObj).doubleValue());
            } else {
                valorFormatado = valorObj.toString();
            }

            String desc = extras.get("descricao").toString();

            Log.d(TAG, "Iniciando request: Method=" + method + ", Valor=" + valorFormatado);
            sendRequest(valorFormatado, desc, quantidade, cpfInput, method);
        }
    }

    private void resetPaymentState() {
        stopRunnable();
        checkout_status = false;
        checkout_id = null;
        runOnUiThread(() -> {
            cardQrCode.setVisibility(View.INVISIBLE);
            if (imageView != null) imageView.setImageBitmap(null);
            constraintLayout.setVisibility(View.INVISIBLE);
            findViewById(R.id.txtTimer).setVisibility(View.GONE);
            findViewById(R.id.txtInfo).setVisibility(View.GONE);
            findViewById(R.id.progressBar2).setVisibility(View.GONE);
            btnConfirmarPagamento.setVisibility(View.GONE);
            
            // ✅ RESET: Mostrar botões novamente se necessário
            if (layoutBotoes != null) layoutBotoes.setVisibility(View.VISIBLE);
            if (txtTituloForma != null) txtTituloForma.setVisibility(View.VISIBLE);
            if (inputLayoutCpf != null) inputLayoutCpf.setVisibility(View.VISIBLE);
            if (textView6 != null) textView6.setVisibility(View.VISIBLE);
            
            changeButtonsFunction(true);
        });
    }

    private void showErrorMessage(String message) {
        runOnUiThread(() -> {
            resetPaymentState();
            changeButtonsFunction(true);
            View contextView = findViewById(R.id.constLayout);
            if (contextView != null) {
                Snackbar.make(contextView, message, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    public void sendRequest(String valor, String descricao, String quantidade, String cpf, String method) {
        String cpfFinal = CpfMask.unmask(cpf);
        if (cpfFinal == null || cpfFinal.trim().isEmpty()) {
            cpfFinal = "11144477735";
            Log.d(TAG, "CPF vazio, usando CPF padrão: " + cpfFinal);
        }
        
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("cpf", cpfFinal);
        body.put("valor", valor);
        body.put("quantidade", quantidade);
        body.put("descricao", descricao);
        body.put("payment_method", method);

        runOnUiThread(() -> {
            constraintLayout.setVisibility(View.VISIBLE);
            txtPreloader.setText("Gerando meio de pagamento...");
            changeButtonsFunction(false);
        });

        new ApiHelper().sendPost(body, "create_order.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha na requisição create_order: " + e.getMessage());
                if (retryCount < MAX_RETRIES) {
                    retryCount++;
                    int delay = (int) Math.pow(2, retryCount) * 1000;
                    runOnUiThread(() -> txtPreloader.setText("Tentando novamente (" + retryCount + "/" + MAX_RETRIES + ")..."));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sendRequest(valor, descricao, quantidade, cpf, method), delay);
                } else {
                    retryCount = 0;
                    showErrorMessage("Falha de rede após múltiplas tentativas. Verifique a conexão.");
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                retryCount = 0;
                try (ResponseBody responseBody = response.body()) {
                    String json = responseBody != null ? responseBody.string() : "";

                    // CORRECAO: log da resposta completa para diagnostico
                    Log.d(TAG, "Resposta create_order - HTTP " + response.code() + ": " + json);

                    if (!response.isSuccessful() || json.isEmpty()) {
                        // CORRECAO: extrair mensagem de erro especifica da API
                        String errorMsg = "Erro no servidor. Tente novamente.";
                        String errorType = "";
                        if (!json.isEmpty()) {
                            try {
                                JsonObject jsonObj = JsonParser.parseString(json).getAsJsonObject();
                                if (jsonObj.has("error")) {
                                    errorMsg = jsonObj.get("error").getAsString();
                                }
                                if (jsonObj.has("error_type")) {
                                    errorType = jsonObj.get("error_type").getAsString();
                                }
                            } catch (Exception parseEx) {
                                Log.e(TAG, "Erro ao parsear resposta de erro: " + parseEx.getMessage());
                            }
                        }
                        Log.e(TAG, "Erro na API - HTTP " + response.code()
                                + " | Tipo: " + errorType
                                + " | Mensagem: " + errorMsg);
                        // CORRECAO: mensagem especifica por tipo de erro SumUp
                        final String finalErrorMsg;
                        switch (errorType) {
                            case "READER_OFFLINE":
                                finalErrorMsg = "Leitor de cartao desligado. Verifique se o SumUp Solo esta ligado.";
                                break;
                            case "READER_BUSY":
                                finalErrorMsg = "Leitor de cartao ocupado. Aguarde e tente novamente.";
                                break;
                            case "NO_READER_CONFIGURED":
                                finalErrorMsg = "Leitora de cartao nao configurada. Contate o suporte.";
                                break;
                            default:
                                finalErrorMsg = errorMsg;
                                break;
                        }
                        showErrorMessage(finalErrorMsg);
                        return;
                    }

                    Qr qr = new Gson().fromJson(json, Qr.class);
                    if (qr != null && qr.checkout_id != null && !qr.checkout_id.isEmpty()) {
                        checkout_id = qr.checkout_id;
                        Log.i(TAG, "Checkout criado com sucesso: " + checkout_id);
                        if (method.equals("pix")) {
                            if (qr.qr_code != null && !qr.qr_code.isEmpty()) {
                                runOnUiThread(() -> {
                                    constraintLayout.setVisibility(View.INVISIBLE);
                                    if (layoutBotoes != null) layoutBotoes.setVisibility(View.GONE);
                                    if (txtTituloForma != null) txtTituloForma.setVisibility(View.GONE);
                                    if (inputLayoutCpf != null) inputLayoutCpf.setVisibility(View.GONE);
                                    if (textView6 != null) textView6.setVisibility(View.GONE);
                                    cardQrCode.setVisibility(View.VISIBLE);
                                    findViewById(R.id.txtTimer).setVisibility(View.VISIBLE);
                                    findViewById(R.id.txtInfo).setVisibility(View.VISIBLE);
                                    btnConfirmarPagamento.setVisibility(View.VISIBLE);
                                    updateQrCode(qr);
                                });
                                startCountDown();
                                startVerifing(qr.checkout_id);
                            } else {
                                showErrorMessage("Erro ao gerar QR Code.");
                            }
                        } else {
                            runOnUiThread(() -> {
                                txtPreloader.setText("Insira ou aproxime o cartao no leitor SumUp Solo.");
                                constraintLayout.setVisibility(View.VISIBLE);
                            });
                            startVerifing(qr.checkout_id);
                        }
                    } else {
                        Log.e(TAG, "Resposta invalida - checkout_id nulo. JSON: " + json);
                        showErrorMessage("Dados de pagamento invalidos.");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Excecao ao processar resposta: " + e.getMessage());
                    showErrorMessage("Erro ao processar dados do servidor.");
                }
            }
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
        resetPaymentState();
        Toast.makeText(FormaPagamento.this, "Operação cancelada.", Toast.LENGTH_SHORT).show();
    }

    private void setupCpfMask() {
        edt.addTextChangedListener(CpfMask.insert(edt));
        edt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                resetInactivityTimer();
                if (CpfMask.unmask(s.toString()).length() == 11) hide_keyboard();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private boolean validateCpfFacultativo(String cpf) {
        String cleanCpf = CpfMask.unmask(cpf);
        if (cleanCpf.isEmpty()) return true;
        if (snackbar != null) snackbar.dismiss();
        ValidaCPF valida = new ValidaCPF();
        if (cleanCpf.length() != 11 || !valida.isCPF(cleanCpf)) {
            showCpfMessage("O CPF informado não é válido");
            return false;
        }
        return true;
    }

    private void showCpfMessage(String message) {
        View contextView = findViewById(R.id.constLayout);
        snackbar = Snackbar.make(contextView, message, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAction("Fechar", v -> snackbar.dismiss()).show();
        edt.requestFocus();
    }

    public void startVerifing(String checkout_id) {
        if (checkout_id == null || checkout_id.isEmpty()) return;
        this.checkout_id = checkout_id;
        final int delay = 5000;
        runnable = new Runnable() {
            int i = 0;
            public void run() {
                if (checkout_status) {
                    navigateToSuccess();
                } else if (i >= 36) { // 180 segundos
                    // ✅ NOVO: Retornar para home ao esgotar o tempo
                    Log.w(TAG, "Tempo esgotado. Retornando para Home.");
                    voltarParaHome();
                } else if (FormaPagamento.this.checkout_id == null) {
                    Log.d(TAG, "Monitoramento parado.");
                } else {
                    verifyPayment(FormaPagamento.this.checkout_id);
                    i++;
                    handler.postDelayed(this, delay);
                }
            }
        };
        handler.postDelayed(runnable, delay);
    }

    private void navigateToSuccess() {
        stopRunnable();
        Intent it = new Intent(FormaPagamento.this, PagamentoConcluido.class);
        it.putExtra("qtd_ml", quantidade);
        it.putExtra("checkout_id", checkout_id);
        startActivity(it);
        finish();
    }

    public void verifyPayment(String checkout_id) {
        if (checkout_id == null) return;
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("checkout_id", checkout_id);

        new ApiHelper().sendPost(body, "verify_checkout.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (response.isSuccessful()) {
                        CheckoutResponse cr = new Gson().fromJson(responseBody.string(), CheckoutResponse.class);
                        if (cr != null && "success".equals(cr.status)) checkout_status = true;
                    }
                } catch (Exception e) {}
            }
        });
    }

    public void changeButtonsFunction(Boolean enabled) {
        runOnUiThread(() -> {
            int orangeColor = Color.parseColor("#FF8C00");
            int grayColor = Color.parseColor("#808080");
            int currentColor = enabled ? orangeColor : grayColor;
            btnPix.setEnabled(enabled);
            btnPix.setBackgroundColor(currentColor);
            Sqlite banco = new Sqlite(getApplicationContext());
            boolean cardEnabled = enabled && banco.getCartaoEnabled();
            btnCard.setEnabled(cardEnabled);
            btnCardDebit.setEnabled(cardEnabled);
            btnCard.setBackgroundColor(cardEnabled ? orangeColor : grayColor);
            btnCardDebit.setBackgroundColor(cardEnabled ? orangeColor : grayColor);
        });
    }

    public void updateQrCode(Qr qr) {
        if (qr == null || qr.qr_code == null) return;
        final String base64Image = qr.qr_code;
        runOnUiThread(() -> {
            try {
                byte[] decodedString = Base64.decode(base64Image, Base64.DEFAULT);
                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                if (decodedByte != null) {
                    imageView.setImageBitmap(decodedByte);
                    imageView.setVisibility(View.VISIBLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro render QR: " + e.getMessage());
            }
        });
    }

    public void startCountDown() {
        runnableCountDown = new Runnable() {
            int i = 1;
            public void run() {
                if (i <= 180) { 
                    final int currentI = i;
                    runOnUiThread(() -> {
                        ProgressBar pb = findViewById(R.id.progressBar2);
                        TextView txtTimer = findViewById(R.id.txtTimer);
                        if (pb != null) {
                            pb.setVisibility(View.VISIBLE);
                            pb.setProgress((currentI * 100) / 180);
                        }
                        if (txtTimer != null) txtTimer.setText((180 - currentI) + "s");
                    });
                    i++;
                    handlerCountDown.postDelayed(this, 1000);
                }
            }
        };
        handlerCountDown.postDelayed(runnableCountDown, 1000);
    }

    private void hide_keyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edt.getWindowToken(), 0);
    }

    private void stopRunnable() {
        if (runnable != null) handler.removeCallbacks(runnable);
        if (runnableCountDown != null) handlerCountDown.removeCallbacks(runnableCountDown);
        inactivityHandler.removeCallbacks(inactivityRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRunnable();
    }
}
