package com.example.choppontap;

import android.content.BroadcastReceiver;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings.Secure;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Home extends AppCompatActivity {

    private static final String TAG = "HOME";

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView txtBebida;
    private ImageView imageView;
    private ImageView logoChoppOn;
    private Button btn100, btn300, btn500, btn700;
    private BluetoothStatusIndicator bluetoothStatusIndicator;

    // ── Estado da TAP ─────────────────────────────────────────────────────────
    private String android_id;
    private String bebida;
    private String imagemUrl;
    private Float valorBase;

    // ── Easter egg ────────────────────────────────────────────────────────────
    private int secretClickCount = 0;
    private final Handler handler = new Handler();

    // ── Carregamento de imagem ────────────────────────────────────────────────
    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private Future<?> currentImageTask = null;

    // ── Bluetooth ─────────────────────────────────────────────────────────────
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    /**
     * BroadcastReceiver que recebe atualizações de status do BluetoothService.
     * Registrado em onResume() e desregistrado em onPause() para evitar leaks.
     */
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothService.ACTION_CONNECTION_STATUS.equals(intent.getAction())) {
                String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                if (status != null) {
                    Log.d(TAG, "BLE status recebido via broadcast: " + status);
                    updateBluetoothStatus(status);
                    // Habilita os botões SOMENTE quando conectado
                    changeButtons("connected".equals(status));
                }
            }
        }
    };

    /**
     * ServiceConnection do BluetoothService.
     *
     * PROBLEMA IDENTIFICADO (BLE preso em "conectando..."):
     *   Quando o ServiceTools chama disconnect() → mAutoReconnect = false.
     *   Depois, ao ativar a TAP, o ServiceTools chama scanLeDevice(true) antes
     *   de navegar para a Home. Porém, o BluetoothService é um Service singleton
     *   — o mesmo objeto é reusado. Quando a Home faz bindService(), o
     *   onServiceConnected() é chamado, mas mAutoReconnect ainda está false
     *   (foi setado pelo disconnect() anterior). Resultado: o scan inicia,
     *   encontra o ESP32, conecta via GATT, mas ao desconectar por qualquer
     *   motivo, retryConnection() não é chamado porque mAutoReconnect = false.
     *
     * CORREÇÃO: ao fazer bind, chamar enableAutoReconnect() para resetar a flag
     *   antes de iniciar o scan.
     */
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            Log.i(TAG, "BluetoothService vinculado. Estado atual: "
                    + (mBluetoothService.connected() ? "CONECTADO" : "DESCONECTADO"));

            // CORREÇÃO CRÍTICA: reabilita o auto-reconnect que foi desativado pelo
            // disconnect() do ServiceTools ao desativar a TAP. Sem isso, o BLE
            // conecta uma vez mas nunca reconecta após quedas.
            mBluetoothService.enableAutoReconnect();

            if (!mBluetoothService.connected()) {
                Log.i(TAG, "Iniciando scan BLE para conectar ao ESP32...");
                mBluetoothService.scanLeDevice(true);
            } else {
                // Já conectado — atualiza a UI imediatamente
                updateBluetoothStatus("connected");
                changeButtons(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothService desvinculado inesperadamente");
            mIsServiceBound = false;
            mBluetoothService = null;
            changeButtons(false);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);

        setupFullscreen();
        android_id = Secure.getString(getContentResolver(), Secure.ANDROID_ID);
        setupUI();

        // Verifica se os dados vieram por Intent (ex: fluxo de login inicial)
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getString("bebida") != null) {
            // Dados passados diretamente — usa sem chamar a API
            bebida    = extras.getString("bebida");
            valorBase = extras.getFloat("preco", 0.0f);
            imagemUrl = extras.getString("imagem");

            boolean cartaoHabilitado = extras.getBoolean("cartao", false);
            new Sqlite(getApplicationContext()).tapCartao(cartaoHabilitado);

            Log.i(TAG, "Dados da TAP recebidos via Intent: bebida=" + bebida + " preco=" + valorBase);
            updateFields(bebida, valorBase, imagemUrl);
        } else {
            // Sem dados no Intent — busca da API (inclui reativação via ServiceTools)
            Log.i(TAG, "onCreate: buscando dados da TAP via verify_tap.php...");
            sendRequestCheckSecurity();
        }

        // Vincula o BluetoothService — onServiceConnected() dispara o scan
        bindBluetoothService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        secretClickCount = 0;

        // Registra o receiver para receber broadcasts de status BLE
        IntentFilter filter = new IntentFilter(BluetoothService.ACTION_CONNECTION_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);

        // Se já vinculado mas desconectado, reinicia o scan
        if (mIsServiceBound && mBluetoothService != null && !mBluetoothService.connected()) {
            Log.i(TAG, "onResume: BLE desconectado, reiniciando scan...");
            mBluetoothService.enableAutoReconnect();
            mBluetoothService.scanLeDevice(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentImageTask != null) currentImageTask.cancel(true);
        imageExecutor.shutdown();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup de UI
    // ─────────────────────────────────────────────────────────────────────────

    private void setupUI() {
        txtBebida   = findViewById(R.id.txtBebida);
        imageView   = findViewById(R.id.imageBeer2);
        btn100      = findViewById(R.id.btn100);
        btn300      = findViewById(R.id.btn300);
        btn500      = findViewById(R.id.btn500);
        btn700      = findViewById(R.id.btn700);
        logoChoppOn = findViewById(R.id.logoChoppOn);

        LinearLayout statusContainer = findViewById(R.id.bluetooth_status_container);
        bluetoothStatusIndicator = new BluetoothStatusIndicator(statusContainer);

        // Botões desabilitados até BLE conectar
        changeButtons(false);

        // Volumes: btn100=300ml, btn300=500ml, btn500=700ml, btn700=1000ml
        btn100.setOnClickListener(v -> openIntent(3));
        btn300.setOnClickListener(v -> openIntent(5));
        btn500.setOnClickListener(v -> openIntent(7));
        btn700.setOnClickListener(v -> openIntent(10));

        // Animação de pulso — convida o cliente a clicar
        startPulseAnimation();

        // Easter egg: 5 cliques no logo → AcessoMaster
        logoChoppOn.setOnClickListener(v -> {
            secretClickCount++;
            if (secretClickCount >= 5) {
                secretClickCount = 0;
                startActivity(new Intent(Home.this, AcessoMaster.class));
            }
            handler.removeCallbacksAndMessages("secret_timer");
            handler.postAtTime(() -> secretClickCount = 0,
                    "secret_timer", android.os.SystemClock.uptimeMillis() + 2000);
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API: verify_tap
    // ─────────────────────────────────────────────────────────────────────────

    private void sendRequestCheckSecurity() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "verify_tap falhou: " + e.getMessage());
                redirecionarImei();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    if (!response.isSuccessful()) {
                        Log.e(TAG, "verify_tap HTTP " + response.code());
                        redirecionarImei();
                        return;
                    }

                    String jsonStr = rb != null ? rb.string() : "";
                    Log.d(TAG, "verify_tap resposta: " + jsonStr);

                    // Limpa possíveis caracteres antes do JSON (BOM, whitespace, etc.)
                    int braceIdx = jsonStr.indexOf('{');
                    if (braceIdx > 0) {
                        Log.w(TAG, "verify_tap: " + braceIdx + " caractere(s) antes do JSON removidos");
                        jsonStr = jsonStr.substring(braceIdx);
                    }

                    // Deserializa com Gson
                    Tap tap = null;
                    try {
                        tap = new Gson().fromJson(jsonStr, Tap.class);
                    } catch (Exception e) {
                        Log.e(TAG, "Gson falhou ao parsear verify_tap: " + e.getMessage());
                    }

                    if (tap == null || tap.bebida == null || tap.bebida.isEmpty()) {
                        Log.w(TAG, "verify_tap: TAP não encontrada ou sem bebida configurada");
                        redirecionarImei();
                        return;
                    }

                    // Verifica se a TAP está desativada
                    if (tap.tap_status != null && tap.tap_status == 0) {
                        Log.w(TAG, "verify_tap: tap_status=0 → TAP OFFLINE → redirecionando");
                        redirecionarOffline();
                        return;
                    }

                    // TAP ativa — atualiza a UI
                    Log.i(TAG, "verify_tap OK: bebida=" + tap.bebida
                            + " preco=" + tap.preco
                            + " image=" + tap.image
                            + " tap_status=" + tap.tap_status);

                    boolean cartaoHabilitado = (tap.cartao != null) && tap.cartao;
                    new Sqlite(getApplicationContext()).tapCartao(cartaoHabilitado);

                    final Tap tapFinal = tap;
                    runOnUiThread(() -> updateFields(tapFinal.bebida, tapFinal.preco, tapFinal.image));

                } catch (Exception e) {
                    Log.e(TAG, "Erro inesperado ao processar verify_tap: " + e.getMessage());
                    redirecionarImei();
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atualização de UI
    // ─────────────────────────────────────────────────────────────────────────

    public void updateFields(String bebida, Float preco, String imageUrl) {
        this.bebida    = bebida;
        this.valorBase = preco;
        this.imagemUrl = imageUrl;

        Log.d(TAG, "updateFields: bebida=" + bebida + " preco=" + preco + " imageUrl=" + imageUrl);

        if (txtBebida != null) txtBebida.setText(bebida);
        updateValue(preco);
        carregarImagem(imageUrl);
    }

    /**
     * Inicia a animação de pulso nos 4 botões.
     * Cada botão começa com um pequeno atraso para criar efeito cascata.
     */
    private void startPulseAnimation() {
        if (btn100 == null) return;
        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        Animation pulse2 = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        Animation pulse3 = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        Animation pulse4 = AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        // Atraso escalonado para efeito cascata
        pulse.setStartOffset(0);
        pulse2.setStartOffset(180);
        pulse3.setStartOffset(360);
        pulse4.setStartOffset(540);
        btn100.startAnimation(pulse);
        btn300.startAnimation(pulse2);
        btn500.startAnimation(pulse3);
        btn700.startAnimation(pulse4);
        Log.d(TAG, "Animação de pulso iniciada nos 4 botões");
    }

    /**
     * Para a animação de pulso (ex: quando BLE desconecta e botões ficam cinza).
     */
    private void stopPulseAnimation() {
        if (btn100 == null) return;
        btn100.clearAnimation();
        btn300.clearAnimation();
        btn500.clearAnimation();
        btn700.clearAnimation();
    }

    public void updateValue(Float value) {
        if (btn100 == null) return;
        // Volumes: btn100=300ml, btn300=500ml, btn500=700ml, btn700=1000ml
        if (value == null || value == 0f) {
            Log.w(TAG, "updateValue: preço nulo ou zero — aguardando dados da API");
            btn100.setText("300 ml");
            btn300.setText("500 ml");
            btn500.setText("700 ml");
            btn700.setText("1000 ml");
            return;
        }
        btn100.setText("300 ml\nR$ " + String.format("%.2f", value * 3).replace(".", ","));
        btn300.setText("500 ml\nR$ " + String.format("%.2f", value * 5).replace(".", ","));
        btn500.setText("700 ml\nR$ " + String.format("%.2f", value * 7).replace(".", ","));
        btn700.setText("1000 ml\nR$ " + String.format("%.2f", value * 10).replace(".", ","));
    }

    /**
     * Carrega a imagem da bebida em background thread.
     *
     * PROBLEMA IDENTIFICADO (imagem não carrega):
     *   Após ativação, o imageExecutor pode estar em estado shutdown se a
     *   Activity anterior foi destruída. Além disso, se a URL vier vazia
     *   do verify_tap, a imagem nunca é carregada.
     *
     * CORREÇÃO: verifica se o executor está ativo antes de submeter a tarefa,
     *   e loga a URL recebida para diagnóstico.
     */
    private void carregarImagem(String url) {
        Log.d(TAG, "carregarImagem: url=" + url);

        if (url == null || url.isEmpty()) {
            Log.w(TAG, "carregarImagem: URL vazia — imagem não será carregada");
            return;
        }

        if (imageExecutor.isShutdown()) {
            Log.e(TAG, "carregarImagem: imageExecutor foi encerrado — não é possível carregar imagem");
            return;
        }

        if (currentImageTask != null && !currentImageTask.isDone()) {
            currentImageTask.cancel(true);
        }

        currentImageTask = imageExecutor.submit(() -> {
            try {
                Log.d(TAG, "carregarImagem: baixando " + url);
                Tap tempTap = new Tap();
                tempTap.image = url;
                Bitmap bmp = new ApiHelper().getImage(tempTap);

                if (bmp != null) {
                    Log.i(TAG, "carregarImagem: imagem carregada com sucesso");
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && imageView != null) {
                            imageView.setImageBitmap(bmp);
                        }
                    });
                } else {
                    Log.w(TAG, "carregarImagem: getImage retornou null para " + url);
                    runOnUiThread(() -> {
                        if (txtBebida != null) {
                            txtBebida.setText(bebida + " (sem imagem)");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "carregarImagem: erro ao baixar imagem: " + e.getMessage());
            }
        });
    }

    public void changeButtons(Boolean enabled) {
        if (btn100 == null) return;
        int color = enabled ? Color.WHITE : Color.LTGRAY;
        btn100.setEnabled(enabled); btn100.setTextColor(color);
        btn300.setEnabled(enabled); btn300.setTextColor(color);
        btn500.setEnabled(enabled); btn500.setTextColor(color);
        btn700.setEnabled(enabled); btn700.setTextColor(color);
        // Pulso ativo só quando BLE conectado
        if (enabled) {
            startPulseAnimation();
        } else {
            stopPulseAnimation();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bluetooth
    // ─────────────────────────────────────────────────────────────────────────

    private void bindBluetoothService() {
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        // startService garante que o serviço continue rodando mesmo sem binding
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void updateBluetoothStatus(String status) {
        if (bluetoothStatusIndicator == null) return;
        switch (status.toLowerCase()) {
            case "connected":
                bluetoothStatusIndicator.setStatus(
                        BluetoothStatusIndicator.STATUS_CONNECTED, "✓ Conectado ao Chopp");
                break;
            case "conectando...":
                bluetoothStatusIndicator.setStatus(
                        BluetoothStatusIndicator.STATUS_CONNECTING, "⏳ Conectando...");
                break;
            default:
                bluetoothStatusIndicator.setStatus(
                        BluetoothStatusIndicator.STATUS_ERROR, "🔴 Desconectado");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Navegação
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Abre a tela de pagamento.
     * @param multiplicador fator de volume: 3=300ml, 5=500ml, 7=700ml, 10=1000ml
     */
    protected void openIntent(Integer multiplicador) {
        if (mBluetoothService != null && mBluetoothService.connected()) {
            int volumeMl = multiplicador * 100;
            float valor  = valorBase != null ? valorBase * multiplicador : 0f;
            Intent it = new Intent(Home.this, FormaPagamento.class);
            it.putExtra("quantidade", volumeMl);
            it.putExtra("valor", valor);
            it.putExtra("descricao", bebida + " " + volumeMl + "ml");
            Log.i(TAG, "Abrindo pagamento: " + volumeMl + "ml R$" + valor);
            startActivity(it);
        } else {
            Toast.makeText(this, "Aguardando conexão Bluetooth...", Toast.LENGTH_SHORT).show();
        }
    }

    private void redirecionarOffline() {
        runOnUiThread(() -> {
            Log.i(TAG, "TAP desativada → desconectando BT e navegando para OfflineTap");
            if (mIsServiceBound && mBluetoothService != null) {
                mBluetoothService.disconnect();
            }
            Intent intent = new Intent(Home.this, OfflineTap.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void redirecionarImei() {
        runOnUiThread(() -> {
            Log.w(TAG, "TAP não encontrada → redirecionando para Imei");
            Intent intent = new Intent(Home.this, Imei.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }
}
