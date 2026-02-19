package com.example.choppontap;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.provider.Settings.Secure;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Home extends AppCompatActivity {
    String bebida;
    String imagemUrl;
    TextView txtBebida;
    ImageView imageView;
    private Handler handler = new Handler();
    Button btn100, btn300, btn500, btn700;
    String android_id;
    Float valorBase;
    Integer countClick = 0;
    private Button btnCalibrar;

    private BluetoothStatusIndicator bluetoothStatusIndicator;
    private String mAndroidId;
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    private final BroadcastReceiver mServiceUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothService.ACTION_CONNECTION_STATUS.equals(action)) {
                String status = intent.getStringExtra(BluetoothService.EXTRA_STATUS);
                if (status != null) {
                    updateBluetoothStatus(status);
                    changeButtons("connected".equals(status));
                }
            }
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            if (!mBluetoothService.connected()) mBluetoothService.scanLeDevice(true);
        }
        @Override public void onServiceDisconnected(ComponentName name) { mIsServiceBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_home);

        setupFullscreen();
        android_id = Secure.getString(this.getContentResolver(), Secure.ANDROID_ID);

        // Inicializa a UI primeiro para evitar NullPointerException
        setupUI();

        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.getString("bebida") != null) {
            bebida = extras.getString("bebida");
            valorBase = extras.getFloat("preco", 0.0f);
            imagemUrl = extras.getString("imagem");

            Log.d("HOME_LIFECYCLE", "Dados recebidos da Intent. Atualizando UI.");
            updateFields(bebida, valorBase, imagemUrl);
        } else {
            Log.w("HOME_LIFECYCLE", "Sem dados na Intent. Verificando via API...");
            sendRequestCheckSecurity();
        }

        bindBluetoothService();
    }

    private void setupUI() {
        txtBebida = findViewById(R.id.txtBebida);
        imageView = findViewById(R.id.imageBeer2);
        btn100 = findViewById(R.id.btn100);
        btn300 = findViewById(R.id.btn300);
        btn500 = findViewById(R.id.btn500);
        btn700 = findViewById(R.id.btn700);
        btnCalibrar = findViewById(R.id.btnCalibrar);

        LinearLayout statusContainer = findViewById(R.id.bluetooth_status_container);
        bluetoothStatusIndicator = new BluetoothStatusIndicator(statusContainer);

        changeButtons(false); // Desabilita botões até conectar

        btnCalibrar.setOnClickListener(v -> {
            if (countClick > 3) startActivity(new Intent(Home.this, CalibrarPulsos.class));
            countClick++;
        });

        btn100.setOnClickListener(v -> openIntent(1));
        btn300.setOnClickListener(v -> openIntent(3));
        btn500.setOnClickListener(v -> openIntent(5));
        btn700.setOnClickListener(v -> openIntent(7));
    }

    private void sendRequestCheckSecurity() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override public void onFailure(Call call, IOException e) { redirecionarImei(); }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    if (response.isSuccessful()) {
                        Tap tap = new Gson().fromJson(rb.string(), Tap.class);
                        if (tap == null || tap.bebida == null || tap.bebida.isEmpty()) {
                            redirecionarImei();
                        } else {
                            runOnUiThread(() -> updateFields(tap.bebida, tap.preco, tap.image));
                        }
                    } else {
                        redirecionarImei();
                    }
                } catch (Exception e) {
                    redirecionarImei();
                }
            }
        });
    }

    private void redirecionarImei() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Home.this, Imei.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });
    }

    private void bindBluetoothService() {
        Intent serviceIntent = new Intent(this, BluetoothService.class);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setupFullscreen() {
        WindowInsetsControllerCompat windowInsetsController = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        windowInsetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
    }

    private void updateBluetoothStatus(String status) {
        if (status == null || bluetoothStatusIndicator == null) return;
        switch (status.toLowerCase()) {
            case "connected":
                bluetoothStatusIndicator.setStatus(BluetoothStatusIndicator.STATUS_CONNECTED, "✓ Conectado ao Chopp");
                break;
            case "conectando...":
                bluetoothStatusIndicator.setStatus(BluetoothStatusIndicator.STATUS_CONNECTING, "⏳ Conectando...");
                break;
            default:
                bluetoothStatusIndicator.setStatus(BluetoothStatusIndicator.STATUS_ERROR, "🔴 Desconectado");
        }
    }

    protected void openIntent(Integer multiplicador) {
        if (mBluetoothService != null && mBluetoothService.connected()) {
            Intent it = new Intent(Home.this, FormaPagamento.class);
            it.putExtra("quantidade", multiplicador * 100);
            it.putExtra("valor", valorBase != null ? valorBase * multiplicador : 0f);
            it.putExtra("descricao", bebida + " " + (multiplicador * 100) + "ml");
            startActivity(it);
        } else {
            Toast.makeText(this, "Conectando ao Bluetooth...", Toast.LENGTH_SHORT).show();
        }
    }

    public void updateFields(String bebida, Float preco, String imageUrl) {
        this.bebida = bebida;
        this.valorBase = preco;
        if (txtBebida != null) txtBebida.setText(bebida);
        updateValue(preco);
        carregarImagem(imageUrl);
    }
    
    private void carregarImagem(String url) {
        if (url == null || url.isEmpty()) {
            Log.w("HOME", "URL da imagem vazia ou nula");
            return;
        }
        
        Log.d("HOME", "Carregando imagem: " + url);
        
        new Thread(() -> {
            try {
                Tap tempTap = new Tap();
                tempTap.image = url;
                Bitmap bmp = new ApiHelper().getImage(tempTap);
                
                if (bmp != null) {
                    runOnUiThread(() -> {
                        if (imageView != null) {
                            imageView.setImageBitmap(bmp);
                            Log.d("HOME", "Imagem carregada com sucesso");
                        }
                    });
                } else {
                    Log.e("HOME", "Bitmap retornado é null");
                }
            } catch (Exception e) {
                Log.e("HOME", "Erro ao carregar imagem: " + e.getMessage(), e);
            }
        }).start();
    }

    public void updateValue(Float value) {
        if (btn100 == null) return; // UI não pronta
        float val = (value != null) ? value : 0f;
        btn100.setText("100 ml \nR$ " + String.format("%.2f", val).replace(".", ","));
        btn300.setText("300 ml \nR$ " + String.format("%.2f", val * 3).replace(".", ","));
        btn500.setText("500 ml \nR$ " + String.format("%.2f", val * 5).replace(".", ","));
        btn700.setText("700 ml \nR$ " + String.format("%.2f", val * 7).replace(".", ","));
    }

    public void changeButtons(Boolean enabled) {
        if (btn100 == null) return; // UI não pronta
        int color = enabled ? Color.WHITE : Color.GRAY;
        btn100.setEnabled(enabled); btn100.setTextColor(color);
        btn300.setEnabled(enabled); btn300.setTextColor(color);
        btn500.setEnabled(enabled); btn500.setTextColor(color);
        btn700.setEnabled(enabled); btn700.setTextColor(color);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(BluetoothService.ACTION_CONNECTION_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(mServiceUpdateReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mServiceUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) unbindService(mServiceConnection);
    }
}
