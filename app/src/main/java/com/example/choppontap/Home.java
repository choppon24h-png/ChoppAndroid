package com.example.choppontap;

import android.app.ActivityManager;
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
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Home extends AppCompatActivity {

    private static final String TAG = "HOME";

    // ── Cores dos botões ──────────────────────────────────────────────────────
    /** Cor padrão (laranja) de todos os botões quando nenhum está selecionado */
    private static final int COLOR_BTN_DEFAULT  = 0xFFFF8C00;
    /** Cor do botão quando selecionado (verde-escuro para contraste) */
    private static final int COLOR_BTN_SELECTED = 0xFF2E7D32;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView txtBebida;
    private ImageView imageView;
    private ImageView logoChoppOn;
    private MaterialButton btn100, btn300, btn500, btn700;
    private BluetoothStatusIndicator bluetoothStatusIndicator;

    // ── Estado da TAP ─────────────────────────────────────────────────────────
    private String android_id;
    private String bebida;
    private String imagemUrl;
    private Float valorBase;

    // ── Easter egg ────────────────────────────────────────────────────────────
    private int secretClickCount = 0;
    private final Handler handler = new Handler();

    // ── Pulsação aleatória ────────────────────────────────────────────────────
    /** Handler dedicado ao agendamento da pulsação aleatória */
    private final Handler pulseHandler = new Handler();
    /** Gerador de números aleatórios para escolha do botão e do intervalo */
    private final Random pulseRandom = new Random();
    /** Controla se o loop de pulsação aleatória está ativo */
    private boolean pulseRunning = false;
    /** Intervalo mínimo entre pulsações (ms) */
    private static final int PULSE_MIN_INTERVAL_MS = 800;
    /** Intervalo máximo entre pulsações (ms) */
    private static final int PULSE_MAX_INTERVAL_MS = 2500;

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
        setupKioskMode();

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
        stopRandomPulse();
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
        btn100.setOnClickListener(v -> {
            highlightSelectedButton(btn100);
            openIntent(3);
        });
        btn300.setOnClickListener(v -> {
            highlightSelectedButton(btn300);
            openIntent(5);
        });
        btn500.setOnClickListener(v -> {
            highlightSelectedButton(btn500);
            openIntent(7);
        });
        btn700.setOnClickListener(v -> {
            highlightSelectedButton(btn700);
            openIntent(10);
        });

        // Easter egg: 5 cliques no logo → AcessoMaster (senha de admin)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Kiosk Mode — impede saída não autorizada do app
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configura o Kiosk Mode para impedir que o cliente saia do app.
     *
     * ESTRATÉGIA:
     *   1. startLockTask() — Android Task Pinning: bloqueia botão Home e Recentes
     *      no nível do sistema operacional. Funciona sem Device Owner como
     *      "Screen Pinning" (o usuário vê um aviso ao tentar sair).
     *      Com Device Owner (ADB), o bloqueio é total e silencioso.
     *   2. OnBackPressedCallback — intercepta o Back e redireciona para
     *      AcessoMaster em vez de fechar a activity. O cliente não consegue
     *      sair sem a senha de 6 dígitos ou QR Code do admin.
     *   3. onWindowFocusChanged — se o app perder foco (notificação, etc.),
     *      força retorno imediato ao primeiro plano.
     */
    private void setupKioskMode() {
        // 1. Task Pinning — bloqueia Home e Recentes no nível do SO
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
                Log.i(TAG, "[KIOSK] Task Pinning ativado com sucesso");
            } else {
                Log.i(TAG, "[KIOSK] Task Pinning já estava ativo");
            }
        } catch (Exception e) {
            // Sem Device Owner, startLockTask() pode lançar SecurityException
            // Nesse caso o bloqueio via OnBackPressedCallback ainda funciona
            Log.w(TAG, "[KIOSK] startLockTask sem Device Owner: " + e.getMessage());
        }

        // 2. Intercepta o botão Back — redireciona para AcessoMaster (senha de admin)
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                Log.i(TAG, "[KIOSK] Botão Back interceptado → abrindo AcessoMaster");
                startActivity(new Intent(Home.this, AcessoMaster.class));
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Recupera fullscreen ao retornar ao foco (ex: após notificação)
            WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                    getWindow(), getWindow().getDecorView());
            wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            wic.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            // App perdeu foco — força retorno ao primeiro plano após 400ms
            // (cobre casos onde Task Pinning não está ativo)
            handler.postDelayed(() -> {
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    boolean lockActive = am != null &&
                            am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
                    if (!lockActive && !isFinishing() && !isDestroyed()) {
                        Log.i(TAG, "[KIOSK] App perdeu foco sem Task Pinning → forçando retorno");
                        Intent intent = new Intent(Home.this, Home.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(intent);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "[KIOSK] onWindowFocusChanged recovery: " + e.getMessage());
                }
            }, 400);
        }
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(
                getWindow(), getWindow().getDecorView());
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        // CORREÇÃO: o tablet fica fisicamente invertido (cabeça para baixo).
        // SCREEN_ORIENTATION_REVERSE_PORTRAIT = portrait rotacionado 180°.
        // O Manifest já declara reversePortrait, mas setRequestedOrientation()
        // em runtime sobrescrevia com PORTRAIT normal. Corrigido aqui.
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

    // ─────────────────────────────────────────────────────────────────────────
    // Pulsação aleatória — cada botão pulsa individualmente de forma aleatória
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inicia o loop de pulsação aleatória.
     * Ao invés de todos os botões pulsarem continuamente em cascata,
     * um botão aleatório pulsa de cada vez, com intervalo aleatório entre
     * PULSE_MIN_INTERVAL_MS e PULSE_MAX_INTERVAL_MS milissegundos.
     */
    private void startRandomPulse() {
        if (btn100 == null) return;
        pulseRunning = true;
        scheduleNextPulse();
        Log.d(TAG, "Pulsação aleatória iniciada");
    }

    /**
     * Agenda a próxima pulsação de um botão aleatório.
     */
    private void scheduleNextPulse() {
        if (!pulseRunning) return;
        int delay = PULSE_MIN_INTERVAL_MS
                + pulseRandom.nextInt(PULSE_MAX_INTERVAL_MS - PULSE_MIN_INTERVAL_MS);
        pulseHandler.postDelayed(pulseTask, delay);
    }

    /**
     * Tarefa que executa a pulsação de um botão aleatório e agenda a próxima.
     */
    private final Runnable pulseTask = new Runnable() {
        @Override
        public void run() {
            if (!pulseRunning || btn100 == null) return;

            // Escolhe aleatoriamente qual botão vai pulsar
            MaterialButton[] buttons = { btn100, btn300, btn500, btn700 };
            MaterialButton target = buttons[pulseRandom.nextInt(buttons.length)];

            // Carrega a animação e aplica somente no botão sorteado
            Animation pulse = AnimationUtils.loadAnimation(Home.this, R.anim.pulse_scale_once);
            pulse.setRepeatCount(1); // pulsa uma vez (vai e volta) e para
            target.startAnimation(pulse);

            Log.d(TAG, "Pulso aleatório no botão: " + target.getId());

            // Agenda o próximo pulso
            scheduleNextPulse();
        }
    };

    /**
     * Para o loop de pulsação aleatória e limpa as animações de todos os botões.
     */
    private void stopRandomPulse() {
        pulseRunning = false;
        pulseHandler.removeCallbacks(pulseTask);
        if (btn100 != null) {
            btn100.clearAnimation();
            btn300.clearAnimation();
            btn500.clearAnimation();
            btn700.clearAnimation();
        }
        Log.d(TAG, "Pulsação aleatória parada");
    }

    /**
     * Destaca visualmente o botão selecionado mudando sua cor,
     * e restaura a cor padrão nos demais botões.
     *
     * @param selected botão que foi clicado pelo cliente
     */
    private void highlightSelectedButton(MaterialButton selected) {
        if (btn100 == null) return;
        MaterialButton[] buttons = { btn100, btn300, btn500, btn700 };
        for (MaterialButton btn : buttons) {
            if (btn == selected) {
                btn.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(COLOR_BTN_SELECTED));
            } else {
                btn.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(COLOR_BTN_DEFAULT));
            }
        }
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
        int textColor = enabled ? Color.WHITE : Color.LTGRAY;
        btn100.setEnabled(enabled); btn100.setTextColor(textColor);
        btn300.setEnabled(enabled); btn300.setTextColor(textColor);
        btn500.setEnabled(enabled); btn500.setTextColor(textColor);
        btn700.setEnabled(enabled); btn700.setTextColor(textColor);

        if (enabled) {
            // Restaura a cor laranja padrão em todos os botões ao habilitar
            btn100.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BTN_DEFAULT));
            btn300.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BTN_DEFAULT));
            btn500.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BTN_DEFAULT));
            btn700.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BTN_DEFAULT));
            // Inicia pulsação aleatória quando BLE conectado
            startRandomPulse();
        } else {
            // Para a pulsação quando BLE desconectado
            stopRandomPulse();
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
            // FIX-4: passar URL da imagem para que PagamentoConcluido possa carregar
            if (imagemUrl != null && !imagemUrl.isEmpty()) {
                it.putExtra("imagem_url", imagemUrl);
            }
            Log.i(TAG, "Abrindo pagamento: " + volumeMl + "ml R$" + valor + " imagemUrl=" + imagemUrl);
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
