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
import android.provider.Settings.Secure;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class CalibrarPulsos extends AppCompatActivity {

    private final OkHttpClient client = new OkHttpClient();
    String android_id;
    private Handler handler = new Handler();
    private ConstraintLayout main;
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;
    TextView qtdAtual;
    TextView txtVolumeLiberado;
    Button btnPulsos;
    Button btnLiberar;
    Button btnTimeout;
    Button btnLiberacaoContinua;

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            
            if (BluetoothService.ACTION_CONNECTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                if (status == null) return;

                if (status.equals("disconnected")) {
                    changeButtons(false);
                    View contextView = findViewById(R.id.mainCalibrar);
                    if (contextView != null) {
                        Snackbar.make(contextView, "TAP Desconectada", Snackbar.LENGTH_SHORT)
                                .setAction("Conectar", v -> {
                                    if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                                }).show();
                    }
                } else if (status.equals("connected")) {
                    changeButtons(true);
                    if (mBluetoothService != null) mBluetoothService.write("$PL:0");
                }

            } else if (BluetoothService.ACTION_DATA_AVAILABLE.equals(action)) {
                String receivedData = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                if (receivedData != null) {
                    if (receivedData.contains("PL")) {
                        qtdAtual.setText(receivedData.replace("\n", "").trim());
                    }
                    if (receivedData.contains("VP")) {
                        try {
                            String vp = receivedData.replace("VP:", "").trim();
                            Double mlsFloat = Double.valueOf(vp);
                            int mls = (int) Math.round(mlsFloat);
                            txtVolumeLiberado.setText(mls + "ML");
                        } catch (Exception e) {
                            Log.e("CALIBRAR", "Erro parse VP: " + e.getMessage());
                        }
                    }
                }
            } else if (BluetoothService.ACTION_DEVICE_FOUND.equals(action)) {
                BluetoothDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(BluetoothService.EXTRA_DEVICE, BluetoothDevice.class);
                } else {
                    device = intent.getParcelableExtra(BluetoothService.EXTRA_DEVICE);
                }
                if (device != null) {
                    Log.d("CALIBRAR", "Dispositivo em alcance: " + device.getAddress());
                }
            }
        }
    };


    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothService.LocalBinder binder = (BluetoothService.LocalBinder) service;
            mBluetoothService = binder.getService();
            mIsServiceBound = true;
            
            if (mBluetoothService.connected()) {
                changeButtons(true);
                mBluetoothService.write("$PL:0");
            } else {
                mBluetoothService.scanLeDevice(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothService.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothService.ACTION_DEVICE_FOUND);
        filter.addAction(BluetoothService.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.calibrar_pulsos);
        
        setupFullscreen();
        exibirDialogoSenha();

        qtdAtual = findViewById(R.id.txtTimeoutAtual);
        txtVolumeLiberado = findViewById(R.id.txtVolumeLiberado);
        main = findViewById(R.id.mainCalibrar);
        
        btnPulsos = findViewById(R.id.btnChangePulsos);
        btnLiberar = findViewById(R.id.btnSalvarTimeout);
        btnTimeout = findViewById(R.id.btnTimeout);
        btnLiberacaoContinua = findViewById(R.id.btnLiberacaoContinua);
        Button btnConfig = findViewById(R.id.btnConfig);

        EditText novaQtd = findViewById(R.id.edtNovoTimeout);

        Intent serviceIntent = new Intent(this, BluetoothService.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        btnConfig.setOnClickListener(v -> abrirConfiguracoesPrincipais());

        btnPulsos.setOnClickListener(v -> {
            try {
                String input = novaQtd.getText().toString();
                if (!input.isEmpty()) {
                    int volumeAferido = Integer.parseInt(input);
                    String pulsoStr = qtdAtual.getText().toString().replace("PL:", "").trim();
                    if (!pulsoStr.isEmpty()) {
                        int pulsosAtual = Integer.parseInt(pulsoStr);
                        int qtd = (pulsosAtual * 100) / volumeAferido;
                        if (mBluetoothService != null) mBluetoothService.write("$PL:" + qtd);
                    }
                }
            } catch (Exception e) {
                Toast.makeText(CalibrarPulsos.this, "Erro ao calcular pulsos", Toast.LENGTH_SHORT).show();
            }
        });

        btnLiberar.setOnClickListener(v -> {
            if (mBluetoothService != null) mBluetoothService.write("$ML:100");
        });

        btnTimeout.setOnClickListener(v -> {
            startActivity(new Intent(CalibrarPulsos.this, ModificarTimeout.class));
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    public void abrirConfiguracoesPrincipais() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }

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

    private void exibirDialogoSenha() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Autenticação");
        builder.setMessage("Senha de administrador:");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);
        builder.setPositiveButton("OK", (d, w) -> {
            String pass = input.getText().toString();
            if (!pass.isEmpty()) sendRequest(pass);
        });
        builder.setNegativeButton("Sair", (d, w) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    private void sendRequest(String senha) {
        Map<String, String> body = new HashMap<>();
        String aid = Secure.getString(getContentResolver(), Secure.ANDROID_ID);
        body.put("android_id", aid);
        body.put("senha", senha);
        
        new ApiHelper().sendPost(body, "verificar_senha_admin.php", new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> Toast.makeText(CalibrarPulsos.this, "Erro de conexão", Toast.LENGTH_SHORT).show());
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful() && rb != null) {
                        String res = rb.string();
                        runOnUiThread(() -> {
                            if (res.contains("success")) main.setVisibility(View.VISIBLE);
                            else {
                                Toast.makeText(CalibrarPulsos.this, "Senha Incorreta!", Toast.LENGTH_SHORT).show();
                                exibirDialogoSenha();
                            }
                        });
                    }
                }
            }
        });
    }
}
