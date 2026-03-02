package com.example.choppontap;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ServiceTools — Tela de ferramentas do técnico.
 *
 * Fluxo de DESATIVAÇÃO da TAP:
 *   1. Chama toggle_tap.php com action=desativar
 *   2. Recebe success=true, status=offline
 *   3. Desconecta o Bluetooth (mBluetoothService.disconnect())
 *   4. Navega para OfflineTap com FLAG_ACTIVITY_CLEAR_TOP + finishAffinity()
 *
 * Fluxo de ATIVAÇÃO da TAP:
 *   1. Chama toggle_tap.php com action=ativar
 *   2. Recebe success=true, status=online
 *   3. Inicia reconexão Bluetooth (mBluetoothService.scanLeDevice(true))
 *   4. Navega para Home com FLAG_ACTIVITY_CLEAR_TOP + finishAffinity()
 *      → Home.onCreate() chama sendRequestCheckSecurity() + bindBluetoothService()
 *        que recarrega bebida, imagem, preço e conecta ao ESP32
 */
public class ServiceTools extends AppCompatActivity {

    private static final String TAG = "SERVICE_TOOLS";

    // ── Card de sistema ───────────────────────────────────────────────────────
    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;

    // ── Card de leitora ───────────────────────────────────────────────────────
    private TextView txtLeitoraNome, txtLeitoraStatus, txtApiStatus, txtLeitoraMensagem;
    private TextView txtLeitoraBateria, txtLeitoraConexao, txtLeitoraFirmware;
    private LinearLayout layoutLeitoraDetalhes;
    private View viewStatusDot, viewApiDot;
    private ProgressBar progressLeitora;

    // ── Botões ────────────────────────────────────────────────────────────────
    private MaterialButton btnCalibrarPulsos, btnTempoAbertura, btnSairTools;
    private MaterialButton btnAtualizarLeitora, btnToggleTap;
    private ProgressBar progressToggle;

    // ── Estado da TAP ─────────────────────────────────────────────────────────
    private boolean tapAtiva = true;
    private String android_id;
    private boolean fromOffline = false;

    // ── Bluetooth Service ─────────────────────────────────────────────────────
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            Log.i(TAG, "BluetoothService vinculado ao ServiceTools");
            // Atualiza o card de Bluetooth com o estado real
            runOnUiThread(() -> {
                boolean conectado = mBluetoothService.connected();
                txtInfoBluetooth.setText("Bluetooth: " + (conectado ? "Conectado ao ESP32" : "Desconectado"));
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mIsServiceBound = false;
            mBluetoothService = null;
            Log.w(TAG, "BluetoothService desvinculado");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_tools);

        fromOffline = getIntent().getBooleanExtra("from_offline", false);

        // ── Referências ───────────────────────────────────────────────────────
        txtInfoImei           = findViewById(R.id.txtInfoImei);
        txtInfoBluetooth      = findViewById(R.id.txtInfoBluetooth);
        txtInfoWifi           = findViewById(R.id.txtInfoWifi);

        txtLeitoraNome        = findViewById(R.id.txtLeitoraNome);
        txtLeitoraStatus      = findViewById(R.id.txtLeitoraStatus);
        txtApiStatus          = findViewById(R.id.txtApiStatus);
        txtLeitoraMensagem    = findViewById(R.id.txtLeitoraMensagem);
        txtLeitoraBateria     = findViewById(R.id.txtLeitoraBateria);
        txtLeitoraConexao     = findViewById(R.id.txtLeitoraConexao);
        txtLeitoraFirmware    = findViewById(R.id.txtLeitoraFirmware);
        layoutLeitoraDetalhes = findViewById(R.id.layoutLeitoraDetalhes);
        viewStatusDot         = findViewById(R.id.viewStatusDot);
        viewApiDot            = findViewById(R.id.viewApiDot);
        progressLeitora       = findViewById(R.id.progressLeitora);

        btnCalibrarPulsos     = findViewById(R.id.btnCalibrarPulsos);
        btnTempoAbertura      = findViewById(R.id.btnTempoAbertura);
        btnSairTools          = findViewById(R.id.btnSairTools);
        btnAtualizarLeitora   = findViewById(R.id.btnAtualizarLeitora);
        btnToggleTap          = findViewById(R.id.btnToggleTap);
        progressToggle        = findViewById(R.id.progressToggle);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // ── Inicialização ─────────────────────────────────────────────────────
        loadSystemInfo();
        loadReaderStatus();
        sincronizarEstadoTap();

        // ── Vincula o BluetoothService ────────────────────────────────────────
        bindService(new Intent(this, BluetoothService.class), mServiceConnection, Context.BIND_AUTO_CREATE);

        // ── Listeners ─────────────────────────────────────────────────────────
        btnCalibrarPulsos.setOnClickListener(v ->
                startActivity(new Intent(this, CalibrarPulsos.class)));

        btnTempoAbertura.setOnClickListener(v ->
                startActivity(new Intent(this, ModificarTimeout.class)));

        btnSairTools.setOnClickListener(v -> finish());

        btnAtualizarLeitora.setOnClickListener(v -> loadReaderStatus());

        btnToggleTap.setOnClickListener(v -> confirmarToggleTap());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sincroniza o estado atual da TAP via verify_tap.php
    // ─────────────────────────────────────────────────────────────────────────
    private void sincronizarEstadoTap() {
        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Falha ao sincronizar estado da TAP: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    boolean ativa = true;
                    try {
                        String jsonLimpo = json;
                        int idx = json.indexOf('{');
                        if (idx > 0) jsonLimpo = json.substring(idx);

                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseString(jsonLimpo).getAsJsonObject();

                        // Suporta campo novo (tap_status) e legado (status)
                        if (obj.has("tap_status")) {
                            ativa = (obj.get("tap_status").getAsInt() == 1);
                        } else if (obj.has("status")) {
                            ativa = (obj.get("status").getAsInt() == 1);
                        }
                        Log.d(TAG, "Estado TAP sincronizado: " + (ativa ? "ATIVA" : "DESATIVADA"));
                    } catch (Exception ignored) {
                        Log.w(TAG, "Não foi possível parsear status da TAP");
                    }

                    final boolean estadoFinal = ativa;
                    runOnUiThread(() -> {
                        tapAtiva = estadoFinal;
                        atualizarBotaoToggle();
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atualiza o visual do botão conforme o estado da TAP
    // ─────────────────────────────────────────────────────────────────────────
    private void atualizarBotaoToggle() {
        if (tapAtiva) {
            btnToggleTap.setText("Desativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
            btnToggleTap.setIconResource(android.R.drawable.ic_lock_power_off);
        } else {
            btnToggleTap.setText("Ativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            btnToggleTap.setIconResource(android.R.drawable.ic_media_play);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diálogo de confirmação antes de executar o toggle
    // ─────────────────────────────────────────────────────────────────────────
    private void confirmarToggleTap() {
        String acao   = tapAtiva ? "desativar" : "ativar";
        String titulo = tapAtiva ? "Desativar esta TAP?" : "Ativar esta TAP?";
        String msg    = tapAtiva
                ? "A torneira ficará OFFLINE.\nO Bluetooth será desconectado e os clientes serão redirecionados.\nDeseja continuar?"
                : "A torneira voltará a funcionar normalmente.\nO Bluetooth será reconectado automaticamente.\nDeseja ativar?";

        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setPositiveButton("Confirmar", (dialog, which) -> executarToggleTap(acao))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Executa o toggle via API e aplica o fluxo completo de BT + navegação
    // ─────────────────────────────────────────────────────────────────────────
    private void executarToggleTap(String acao) {
        btnToggleTap.setEnabled(false);
        progressToggle.setVisibility(View.VISIBLE);

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("action", acao);

        new ApiHelper().sendPost(body, "toggle_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha de rede ao executar toggle_tap: " + e.getMessage());
                runOnUiThread(() -> {
                    btnToggleTap.setEnabled(true);
                    progressToggle.setVisibility(View.GONE);
                    Toast.makeText(ServiceTools.this, "Erro de conexão", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    boolean sucesso   = false;
                    String novoStatus = "";

                    // Limpa possíveis caracteres antes do JSON
                    String jsonLimpo = json;
                    int braceIdx = json.indexOf('{');
                    if (braceIdx > 0) jsonLimpo = json.substring(braceIdx);

                    try {
                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseString(jsonLimpo).getAsJsonObject();
                        sucesso    = obj.has("success") && obj.get("success").getAsBoolean();
                        novoStatus = obj.has("status")  ? obj.get("status").getAsString() : "";
                        Log.d(TAG, "toggle_tap resposta: success=" + sucesso + " status=" + novoStatus);
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear resposta toggle_tap: " + e.getMessage());
                    }

                    final boolean ok         = sucesso;
                    final String  statusFinal = novoStatus;

                    runOnUiThread(() -> {
                        btnToggleTap.setEnabled(true);
                        progressToggle.setVisibility(View.GONE);

                        if (ok) {
                            tapAtiva = "online".equals(statusFinal);
                            atualizarBotaoToggle();

                            if (!tapAtiva) {
                                // ── DESATIVAÇÃO ──────────────────────────────────────────
                                // 1. Desconecta o Bluetooth do ESP32
                                desconectarBluetooth();
                                // 2. Navega para OfflineTap limpando toda a pilha de activities
                                Log.i(TAG, "TAP desativada → BT desconectado → navegando para OfflineTap");
                                Intent intent = new Intent(ServiceTools.this, OfflineTap.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();

                            } else {
                                // ── ATIVAÇÃO ─────────────────────────────────────────────
                                // 1. Inicia reconexão Bluetooth ao ESP32
                                reconectarBluetooth();
                                // 2. Navega para Home limpando toda a pilha de activities
                                //    Home.onCreate() → sendRequestCheckSecurity() recarrega
                                //    bebida, imagem, preço e bindBluetoothService() reconecta
                                Log.i(TAG, "TAP ativada → BT reconectando → navegando para Home");
                                Intent intent = new Intent(ServiceTools.this, Home.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();
                            }
                        } else {
                            Toast.makeText(ServiceTools.this, "Falha ao alterar status da TAP", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Desconecta o Bluetooth do ESP32 (TAP sendo desativada)
    // ─────────────────────────────────────────────────────────────────────────
    private void desconectarBluetooth() {
        if (mIsServiceBound && mBluetoothService != null) {
            Log.i(TAG, "Desconectando Bluetooth (TAP desativada)");
            mBluetoothService.disconnect();
        } else {
            Log.w(TAG, "BluetoothService não vinculado — não foi possível desconectar");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inicia reconexão Bluetooth ao ESP32 (TAP sendo ativada)
    // ─────────────────────────────────────────────────────────────────────────
    private void reconectarBluetooth() {
        if (mIsServiceBound && mBluetoothService != null) {
            Log.i(TAG, "Iniciando reconexão Bluetooth (TAP ativada)");
            // Reabilita auto-reconnect e inicia scan/conexão
            mBluetoothService.scanLeDevice(true);
        } else {
            Log.w(TAG, "BluetoothService não vinculado — Home fará a reconexão no onCreate()");
            // Home.bindBluetoothService() → onServiceConnected() → scanLeDevice(true)
            // cobre este caso automaticamente
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Informações do sistema
    // ─────────────────────────────────────────────────────────────────────────
    private void loadSystemInfo() {
        txtInfoImei.setText("IMEI/ID: " + android_id);
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info.getSSID();
        if (ssid != null && !ssid.equals("<unknown ssid>")) {
            txtInfoWifi.setText("Wi-Fi: Conectado a " + ssid.replace("\"", ""));
        } else {
            txtInfoWifi.setText("Wi-Fi: Desconectado");
        }
        // Estado do BT será atualizado pelo onServiceConnected
        txtInfoBluetooth.setText("Bluetooth: Verificando...");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status da leitora SumUp
    // ─────────────────────────────────────────────────────────────────────────
    private void loadReaderStatus() {
        runOnUiThread(() -> {
            progressLeitora.setVisibility(View.VISIBLE);
            btnAtualizarLeitora.setEnabled(false);
            txtLeitoraNome.setText("Leitora: Consultando...");
        });

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "reader_status.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    progressLeitora.setVisibility(View.GONE);
                    btnAtualizarLeitora.setEnabled(true);
                    txtLeitoraStatus.setText("ERRO");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String json = responseBody != null ? responseBody.string() : "{}";
                    ReaderStatusResponse status = parseReaderStatus(json);

                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);
                        txtLeitoraNome.setText("Leitora: " + (status.leitora_nome.isEmpty() ? "Não configurada" : status.leitora_nome));
                        txtLeitoraStatus.setText("● " + status.status_leitora.toUpperCase());
                        txtApiStatus.setText(status.api_ativa ? "● ATIVA" : "● INATIVA");

                        if (!status.bateria.isEmpty()) {
                            layoutLeitoraDetalhes.setVisibility(View.VISIBLE);
                            txtLeitoraBateria.setText("Bat: " + status.bateria);
                            txtLeitoraConexao.setText("Rede: " + status.conexao.toUpperCase());
                            txtLeitoraFirmware.setText("FW: " + status.firmware);
                        }
                    });
                }
            }
        });
    }

    private ReaderStatusResponse parseReaderStatus(String json) {
        ReaderStatusResponse r = new ReaderStatusResponse();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            r.leitora_nome   = obj.has("leitora_nome")   ? obj.get("leitora_nome").getAsString()   : "";
            r.status_leitora = obj.has("status_leitora") ? obj.get("status_leitora").getAsString() : "offline";
            r.api_ativa      = obj.has("api_ativa")      && obj.get("api_ativa").getAsBoolean();
            r.bateria        = obj.has("bateria")  && !obj.get("bateria").isJsonNull()  ? obj.get("bateria").getAsString()  : "";
            r.conexao        = obj.has("conexao")  && !obj.get("conexao").isJsonNull()  ? obj.get("conexao").getAsString()  : "";
            r.firmware       = obj.has("firmware") && !obj.get("firmware").isJsonNull() ? obj.get("firmware").getAsString() : "";
        } catch (Exception e) {
            Log.e(TAG, "Erro ao parsear reader_status: " + e.getMessage());
        }
        return r;
    }

    private static class ReaderStatusResponse {
        String  leitora_nome   = "";
        String  status_leitora = "offline";
        boolean api_ativa      = false;
        String  bateria        = "";
        String  conexao        = "";
        String  firmware       = "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida — desvincula o serviço ao destruir a Activity
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
    }
}
