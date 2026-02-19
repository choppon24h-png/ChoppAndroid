package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PagamentoConcluido extends AppCompatActivity {
    String android_id;
    private Handler handler = new Handler();
    Runnable runnable;
    ImageView imageView;
    Boolean checkout_status = false;
    Tap tap;
    private final OkHttpClient client = new OkHttpClient();
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;
    private Integer qtd_ml;
    private TextView txtQtd;
    private TextView txtMls;
    private Integer mlsLiberados = 0;
    private Button btnLiberar;
    private String checkout_id;
    private Integer mlsSolicitado;
    private Integer liberado = 0;

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothService.ACTION_WRITE_READY.equals(action)) {
                // ✅ ESTE É O MOMENTO SEGURO PARA ESCREVER
                Log.d("BLE", "Comunicação pronta. Enviando comando de liberação.");
                if (mBluetoothService != null && qtd_ml != null) {
                    mlsSolicitado = qtd_ml;
                    mBluetoothService.write("$ML:" + qtd_ml);
                }
            } else if (BluetoothService.ACTION_CONNECTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);

                if ("disconnected".equals(status)) {
                    View contextView = findViewById(R.id.main);
                    if (contextView != null) {
                        Snackbar.make(contextView, "Aguardando conexão com a TAP...", Snackbar.LENGTH_SHORT).show();
                    }
                    if (mBluetoothService != null) mBluetoothService.scanLeDevice(true);
                }
            } else if (BluetoothService.ACTION_DATA_AVAILABLE.equals(action)) {
                String receivedData = intent.getStringExtra(BluetoothService.EXTRA_DATA);
                if (receivedData == null) return;

                if (receivedData.contains("VP")) {
                    try {
                        String vp = receivedData.replace("VP:", "").trim();
                        Double mlsFloat = Double.valueOf(vp);
                        int mls = (int) Math.round(mlsFloat);
                        liberado = mls;
                        txtMls.setText(liberado + "ML");

                        if (liberado >= mlsSolicitado) {
                            btnLiberar.setVisibility(View.GONE);
                        }
                    } catch (Exception e) {
                        Log.e("BLE", "Erro ao processar VP: " + e.getMessage());
                    }
                }

                if (receivedData.contains("ML")) {
                    mlsLiberados = liberado;
                    sendRequestFim(mlsLiberados.toString(), checkout_id);
                    if (liberado < mlsSolicitado) {
                        btnLiberar.setVisibility(View.VISIBLE);
                    } else {
                        btnLiberar.setVisibility(View.GONE);
                    }
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

            // Inicia busca automática pela placa vinculada
            // O comando de escrita será disparado pelo ACTION_WRITE_READY
            mBluetoothService.scanLeDevice(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_pagamento_concluido);

        setupFullscreen();

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            qtd_ml = Integer.parseInt(extras.get("qtd_ml").toString());
            checkout_id = extras.get("checkout_id").toString();

            btnLiberar = findViewById(R.id.btnLiberarRestante);
            imageView = findViewById(R.id.imageBeer2);
            txtQtd = findViewById(R.id.txtQtdPulsos);
            txtMls = findViewById(R.id.txtMls);

            txtQtd.setText(qtd_ml + " ML");

            // Carregar imagem do banco
            Sqlite banco = new Sqlite(getApplicationContext());
            byte[] img = banco.getActiveImageData();
            if (img != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(img, 0, img.length);
                imageView.setImageBitmap(bmp);
            }

            sendRequestInicio(checkout_id);

            // Vincular ao serviço Bluetooth
            Intent serviceIntent = new Intent(this, BluetoothService.class);
            bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

            btnLiberar.setOnClickListener(v -> {
                if (mBluetoothService != null && mBluetoothService.connected()) {
                    btnLiberar.setVisibility(View.GONE);
                    int restante = qtd_ml - liberado;
                    if (restante > 0) {
                        mlsSolicitado = restante;
                        mBluetoothService.write("$ML:" + restante);
                    }
                }
            });
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                startActivity(new Intent(PagamentoConcluido.this, Home.class));
                finish();
            }
        });
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat windowInsetsController =
                new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothService.ACTION_CONNECTION_STATUS);
        filter.addAction(BluetoothService.ACTION_DATA_AVAILABLE);
        filter.addAction(BluetoothService.ACTION_WRITE_READY);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);

        if (mBluetoothService != null && !mBluetoothService.connected()) {
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
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }

    private void sendRequestInicio(String checkout_id) {
        Map<String, String> body = new HashMap<>();
        String aid = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        body.put("android_id", aid);
        body.put("checkout_id", checkout_id);
        new ApiHelper().sendPost(body, "liberacao.php?action=iniciada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }

    private void sendRequestFim(String volume, String checkout_id) {
        Map<String, String> body = new HashMap<>();
        String aid = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        body.put("android_id", aid);
        body.put("qtd_ml", volume);
        body.put("checkout_id", checkout_id);
        new ApiHelper().sendPost(body, "liberacao.php?action=finalizada", new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException { response.close(); }
        });
    }
}
