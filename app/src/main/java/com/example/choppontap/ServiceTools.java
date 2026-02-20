package com.example.choppontap;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ServiceTools extends AppCompatActivity {

    private static final String TAG = "SERVICE_TOOLS";

    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;
    private TextView txtLeitoraNome, txtLeitoraStatus, txtApiStatus, txtLeitoraMensagem;
    private View viewStatusDot, viewApiDot;
    private ProgressBar progressLeitora;
    private Button btnCalibrarPulsos, btnTempoAbertura, btnSairTools, btnAtualizarLeitora;

    private String android_id;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_tools);

        // Referências do card de sistema
        txtInfoImei       = findViewById(R.id.txtInfoImei);
        txtInfoBluetooth  = findViewById(R.id.txtInfoBluetooth);
        txtInfoWifi       = findViewById(R.id.txtInfoWifi);

        // Referências do card de leitora
        txtLeitoraNome    = findViewById(R.id.txtLeitoraNome);
        txtLeitoraStatus  = findViewById(R.id.txtLeitoraStatus);
        txtApiStatus      = findViewById(R.id.txtApiStatus);
        txtLeitoraMensagem = findViewById(R.id.txtLeitoraMensagem);
        viewStatusDot     = findViewById(R.id.viewStatusDot);
        viewApiDot        = findViewById(R.id.viewApiDot);
        progressLeitora   = findViewById(R.id.progressLeitora);

        // Botões
        btnCalibrarPulsos  = findViewById(R.id.btnCalibrarPulsos);
        btnTempoAbertura   = findViewById(R.id.btnTempoAbertura);
        btnSairTools       = findViewById(R.id.btnSairTools);
        btnAtualizarLeitora = findViewById(R.id.btnAtualizarLeitora);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        loadSystemInfo();
        loadReaderStatus();

        btnCalibrarPulsos.setOnClickListener(v -> startActivity(new Intent(this, CalibrarPulsos.class)));
        btnTempoAbertura.setOnClickListener(v -> startActivity(new Intent(this, ModificarTimeout.class)));
        btnSairTools.setOnClickListener(v -> finish());
        btnAtualizarLeitora.setOnClickListener(v -> loadReaderStatus());
    }

    // ─────────────────────────────────────────────────────────────
    // Informações do sistema (IMEI, Wi-Fi, Bluetooth)
    // ─────────────────────────────────────────────────────────────
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

        txtInfoBluetooth.setText("Bluetooth: Ativo / Pronto");
    }

    // ─────────────────────────────────────────────────────────────
    // Status da Leitora de Cartão SumUp
    // ─────────────────────────────────────────────────────────────
    private void loadReaderStatus() {
        // Mostrar loading
        runOnUiThread(() -> {
            progressLeitora.setVisibility(View.VISIBLE);
            btnAtualizarLeitora.setEnabled(false);
            txtLeitoraNome.setText("Leitora: Consultando...");
            txtLeitoraStatus.setText("Consultando...");
            txtLeitoraStatus.setTextColor(Color.parseColor("#888888"));
            txtApiStatus.setText("Verificando...");
            txtApiStatus.setTextColor(Color.parseColor("#888888"));
            txtLeitoraMensagem.setText("");
            viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
            viewApiDot.setBackgroundResource(R.drawable.circle_gray);
        });

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "reader_status.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha ao consultar status da leitora: " + e.getMessage());
                runOnUiThread(() -> {
                    progressLeitora.setVisibility(View.GONE);
                    btnAtualizarLeitora.setEnabled(true);
                    txtLeitoraNome.setText("Leitora: Erro de conexão");
                    txtLeitoraStatus.setText("Erro");
                    txtLeitoraStatus.setTextColor(Color.parseColor("#F44336"));
                    viewStatusDot.setBackgroundResource(R.drawable.circle_red);
                    txtApiStatus.setText("Indisponível");
                    txtApiStatus.setTextColor(Color.parseColor("#F44336"));
                    viewApiDot.setBackgroundResource(R.drawable.circle_red);
                    txtLeitoraMensagem.setText("Falha de rede ao consultar a API. Verifique a conexão.");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String json = responseBody != null ? responseBody.string() : "{}";
                    Log.d(TAG, "Reader status response: " + json);

                    ReaderStatusResponse status = parseReaderStatus(json);

                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);

                        // Nome da leitora
                        String nome = (status.leitora_nome != null && !status.leitora_nome.isEmpty())
                                ? status.leitora_nome : "Não configurada";
                        txtLeitoraNome.setText("Leitora: " + nome);

                        // Status online/offline
                        if ("online".equals(status.status_leitora)) {
                            txtLeitoraStatus.setText("ONLINE");
                            txtLeitoraStatus.setTextColor(Color.parseColor("#4CAF50"));
                            viewStatusDot.setBackgroundResource(R.drawable.circle_green);
                        } else if ("offline".equals(status.status_leitora)) {
                            txtLeitoraStatus.setText("OFFLINE");
                            txtLeitoraStatus.setTextColor(Color.parseColor("#F44336"));
                            viewStatusDot.setBackgroundResource(R.drawable.circle_red);
                        } else if ("sem_leitora".equals(status.status_leitora)) {
                            txtLeitoraStatus.setText("NÃO CONFIGURADA");
                            txtLeitoraStatus.setTextColor(Color.parseColor("#FF8C00"));
                            viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
                        } else {
                            txtLeitoraStatus.setText("DESCONHECIDO");
                            txtLeitoraStatus.setTextColor(Color.parseColor("#888888"));
                            viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
                        }

                        // Status da API SumUp
                        if (status.api_ativa) {
                            txtApiStatus.setText("ATIVA");
                            txtApiStatus.setTextColor(Color.parseColor("#4CAF50"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_green);
                        } else {
                            txtApiStatus.setText("INATIVA / TOKEN INVÁLIDO");
                            txtApiStatus.setTextColor(Color.parseColor("#F44336"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_red);
                        }

                        // Mensagem de detalhes
                        if (status.mensagem != null && !status.mensagem.isEmpty()) {
                            txtLeitoraMensagem.setText(status.mensagem);
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar resposta: " + e.getMessage());
                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);
                        txtLeitoraNome.setText("Leitora: Erro ao processar");
                        txtLeitoraMensagem.setText("Erro interno ao interpretar a resposta da API.");
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Parser manual do JSON de status (sem dependência extra)
    // ─────────────────────────────────────────────────────────────
    private ReaderStatusResponse parseReaderStatus(String json) {
        ReaderStatusResponse r = new ReaderStatusResponse();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            r.leitora_nome   = obj.has("leitora_nome")   ? obj.get("leitora_nome").getAsString()   : "";
            r.reader_id      = obj.has("reader_id")      ? obj.get("reader_id").getAsString()       : "";
            r.status_leitora = obj.has("status_leitora") ? obj.get("status_leitora").getAsString()  : "offline";
            r.api_ativa      = obj.has("api_ativa")      && obj.get("api_ativa").getAsBoolean();
            r.mensagem       = obj.has("mensagem")       ? obj.get("mensagem").getAsString()        : "";
        } catch (Exception e) {
            Log.e(TAG, "Erro parse JSON: " + e.getMessage());
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // Modelo de resposta
    // ─────────────────────────────────────────────────────────────
    private static class ReaderStatusResponse {
        String leitora_nome   = "";
        String reader_id      = "";
        String status_leitora = "offline";
        boolean api_ativa     = false;
        String mensagem       = "";
    }
}
