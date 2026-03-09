package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

public class CalibrarPulsos extends AppCompatActivity {

    private static final String TAG = "CALIBRAR_PULSOS";

    private Handler handler = new Handler();
    private ConstraintLayout main;
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    // Flag para controle de liberação contínua
    private boolean mLiberacaoContinuaAtiva = false;

    TextView qtdAtual;
    TextView txtVolumeLiberado;
    Button btnPulsos;
    Button btnLiberar;
    Button btnTimeout;
    Button btnLiberacaoContinua;

    // ─────────────────────────────────────────────────────────────────────────
    // BroadcastReceiver: recebe eventos do BluetoothService
    // ─────────────────────────────────────────────────────────────────────────
    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (BluetoothService.ACTION_CONNECTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                if (status == null) return;
                Log.d(TAG, "Status BLE: " + status);

                if (status.equals("disconnected")) {
                    changeButtons(false);
                    mLiberacaoContinuaAtiva = false;
                    View contextView = findViewById(R.id.mainCalibrar);
                    if (contextView != null) {
                        Snackbar.make(contextView, "TAP Desconectada", Snackbar.LENGTH_SHORT)
                                .setAction("Conectar", v -> {
                                    if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                                }).show();
                    }
                } else if (status.equals("connected")) {
                    changeButtons(true);
                    // Solicita valor atual de pulsos ao conectar
                    if (mBluetoothService != null) mBluetoothService.write("$PL:0");
                }

            } else if (BluetoothService.ACTION_DATA_AVAILABLE.equals(action)) {
                String receivedData = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                if (receivedData == null) return;
                Log.d(TAG, "Dado BLE recebido: " + receivedData);

                // Atualiza quantidade de pulsos atual
                if (receivedData.contains("PL")) {
                    qtdAtual.setText(receivedData.replace("\n", "").trim());
                }
                // Atualiza volume liberado
                if (receivedData.contains("VP")) {
                    try {
                        String vp = receivedData.replace("VP:", "").trim();
                        Double mlsFloat = Double.valueOf(vp);
                        int mls = (int) Math.round(mlsFloat);
                        txtVolumeLiberado.setText(mls + "ML");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro parse VP: " + e.getMessage());
                    }
                }
                // Encerramento de liberação contínua
                if (receivedData.startsWith("ML:") || receivedData.equals("ML")) {
                    Log.d(TAG, "Liberação concluída: " + receivedData);
                    mLiberacaoContinuaAtiva = false;
                    runOnUiThread(() -> btnLiberacaoContinua.setText("Liberação continua"));
                }

            } else if (BluetoothService.ACTION_DEVICE_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothService.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothService.EXTRA_DEVICE);
                }
                if (device != null) {
                    Log.d(TAG, "Dispositivo em alcance: " + device.getAddress());
                }
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // ServiceConnection: vincula ao BluetoothService
    // ─────────────────────────────────────────────────────────────────────────
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            Log.d(TAG, "BluetoothService vinculado. Conectado: " + mBluetoothService.connected());

            if (mBluetoothService.connected()) {
                changeButtons(true);
                mBluetoothService.write("$PL:0");
            } else {
                changeButtons(false);
                mBluetoothService.scanLeDevice(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothService desvinculado");
            mIsServiceBound = false;
        }
    };

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate iniciado");

        EdgeToEdge.enable(this);
        setContentView(R.layout.calibrar_pulsos);
        setupFullscreen();

        // Bind de views
        qtdAtual          = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado  = findViewById(R.id.txtVolumeLiberado);
        main               = findViewById(R.id.mainCalibrar);
        btnPulsos          = findViewById(R.id.btnChangePulsos);
        btnLiberar         = findViewById(R.id.btnSalvarTimeout);
        btnTimeout         = findViewById(R.id.btnTimeout);
        btnLiberacaoContinua = findViewById(R.id.btnLiberacaoContinua);
        Button btnConfig   = findViewById(R.id.btnConfig);
        Button btnVoltar   = findViewById(R.id.btnVoltar);
        EditText novaQtd   = findViewById(R.id.edtNovoTimeout);

        // Garante que a tela está visível (defesa contra visibility=gone residual)
        if (main != null) main.setVisibility(View.VISIBLE);

        // Desabilita botões até BLE conectar
        changeButtons(false);

        // Vincula ao BluetoothService
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Listeners ──────────────────────────────────────────────────────

        btnConfig.setOnClickListener(v -> abrirConfiguracoesPrincipais());

        // Calibrar: calcula novos pulsos por litro e envia ao ESP32
        btnPulsos.setOnClickListener(v -> {
            try {
                String input = novaQtd.getText().toString().trim();
                if (input.isEmpty()) {
                    Toast.makeText(this, "Informe o volume aferido", Toast.LENGTH_SHORT).show();
                    return;
                }
                int volumeAferido = Integer.parseInt(input);
                if (volumeAferido <= 0) {
                    Toast.makeText(this, "Volume deve ser maior que zero", Toast.LENGTH_SHORT).show();
                    return;
                }
                String pulsoStr = qtdAtual.getText().toString().replace("PL:", "").trim();
                if (pulsoStr.isEmpty()) {
                    Toast.makeText(this, "Aguardando leitura de pulsos do ESP32", Toast.LENGTH_SHORT).show();
                    return;
                }
                int pulsosAtual = Integer.parseInt(pulsoStr);
                int qtd = (pulsosAtual * 100) / volumeAferido;
                Log.d(TAG, "Calibrando: pulsosAtual=" + pulsosAtual + " volumeAferido=" + volumeAferido + " novosPL=" + qtd);
                if (mBluetoothService != null) mBluetoothService.write("$PL:" + qtd);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Erro ao calibrar: " + e.getMessage());
                Toast.makeText(this, "Erro ao calcular pulsos", Toast.LENGTH_SHORT).show();
            }
        });

        // Liberar líquido: envia $ML:100 (libera 100ml para teste)
        btnLiberar.setOnClickListener(v -> {
            Log.d(TAG, "Enviando $ML:100 para teste de fluxo");
            if (mBluetoothService != null) mBluetoothService.write("$ML:100");
        });

        // Liberação contínua: alterna entre iniciar ($ML:9999) e parar ($ML:0)
        btnLiberacaoContinua.setOnClickListener(v -> {
            if (mBluetoothService == null) return;
            if (!mLiberacaoContinuaAtiva) {
                Log.d(TAG, "Iniciando liberação contínua");
                mBluetoothService.write("$ML:9999");
                mLiberacaoContinuaAtiva = true;
                btnLiberacaoContinua.setText("Parar liberação");
            } else {
                Log.d(TAG, "Parando liberação contínua");
                mBluetoothService.write("$ML:0");
                mLiberacaoContinuaAtiva = false;
                btnLiberacaoContinua.setText("Liberação continua");
            }
        });

        // Modificar Timeout: navega para ModificarTimeout
        btnTimeout.setOnClickListener(v ->
                startActivity(new Intent(CalibrarPulsos.this, ModificarTimeout.class)));

        // Voltar para Home
        btnVoltar.setOnClickListener(v -> {
            Log.d(TAG, "Voltando para Home");
            Intent intent = new Intent(CalibrarPulsos.this, Home.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });

        Log.d(TAG, "onCreate concluído — layout visível");
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothService.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothService.ACTION_DEVICE_FOUND);
        filter.addAction(BluetoothService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
        Log.d(TAG, "onResume — receiver registrado");
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
        Log.d(TAG, "onPause — receiver desregistrado");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
        Log.d(TAG, "onDestroy");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────
    private void setupFullscreen() {
        WindowInsetsControllerCompat wic =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    public void abrirConfiguracoesPrincipais() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

    /**
     * Habilita ou desabilita os botões de ação conforme o estado da conexão BLE.
     * Chamado tanto pelo ServiceConnection quanto pelo BroadcastReceiver.
     */
    public void changeButtons(boolean enabled) {
        runOnUiThread(() -> {
            int color = enabled ? Color.WHITE : Color.GRAY;
            btnLiberacaoContinua.setTextColor(color);
            btnPulsos.setTextColor(color);
            btnLiberar.setTextColor(color);
            btnTimeout.setTextColor(color);
            btnLiberacaoContinua.setEnabled(enabled);
            btnPulsos.setEnabled(enabled);
            btnLiberar.setEnabled(enabled);
            btnTimeout.setEnabled(enabled);
        });
    }
}
