package com.example.choppontap;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ServiceTools extends AppCompatActivity {

    private static final String TAG = "SERVICE_TOOLS";

    // Card de sistema
    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;

    // Card de leitora
    private TextView txtLeitoraNome, txtLeitoraStatus, txtApiStatus, txtLeitoraMensagem;
    private TextView txtLeitoraBateria, txtLeitoraConexao, txtLeitoraFirmware;
    private LinearLayout layoutLeitoraDetalhes;
    private View viewStatusDot, viewApiDot;
    private ProgressBar progressLeitora;

    // Botões
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
        txtLeitoraNome         = findViewById(R.id.txtLeitoraNome);
        txtLeitoraStatus       = findViewById(R.id.txtLeitoraStatus);
        txtApiStatus           = findViewById(R.id.txtApiStatus);
        txtLeitoraMensagem     = findViewById(R.id.txtLeitoraMensagem);
        txtLeitoraBateria      = findViewById(R.id.txtLeitoraBateria);
        txtLeitoraConexao      = findViewById(R.id.txtLeitoraConexao);
        txtLeitoraFirmware     = findViewById(R.id.txtLeitoraFirmware);
        layoutLeitoraDetalhes  = findViewById(R.id.layoutLeitoraDetalhes);
        viewStatusDot          = findViewById(R.id.viewStatusDot);
        viewApiDot             = findViewById(R.id.viewApiDot);
        progressLeitora        = findViewById(R.id.progressLeitora);

        // Botões
        btnCalibrarPulsos   = findViewById(R.id.btnCalibrarPulsos);
        btnTempoAbertura    = findViewById(R.id.btnTempoAbertura);
        btnSairTools        = findViewById(R.id.btnSairTools);
        btnAtualizarLeitora = findViewById(R.id.btnAtualizarLeitora);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        Log.d(TAG, "ServiceTools iniciado | android_id=" + android_id);

        loadSystemInfo();
        loadReaderStatus();

        btnCalibrarPulsos.setOnClickListener(v -> startActivity(new Intent(this, CalibrarPulsos.class)));
        btnTempoAbertura.setOnClickListener(v -> startActivity(new Intent(this, ModificarTimeout.class)));
        btnSairTools.setOnClickListener(v -> finish());
        btnAtualizarLeitora.setOnClickListener(v -> {
            Log.d(TAG, "Botão Atualizar Leitora clicado");
            loadReaderStatus();
        });
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
        Log.d(TAG, "loadReaderStatus: iniciando consulta ao backend");

        runOnUiThread(() -> {
            progressLeitora.setVisibility(View.VISIBLE);
            btnAtualizarLeitora.setEnabled(false);
            txtLeitoraNome.setText("Leitora: Consultando...");
            txtLeitoraStatus.setText("Consultando...");
            txtLeitoraStatus.setTextColor(Color.parseColor("#888888"));
            txtApiStatus.setText("Verificando...");
            txtApiStatus.setTextColor(Color.parseColor("#888888"));
            txtLeitoraMensagem.setText("");
            layoutLeitoraDetalhes.setVisibility(View.GONE);
            viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
            viewApiDot.setBackgroundResource(R.drawable.circle_gray);
        });

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "reader_status.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha de rede ao consultar reader_status.php: " + e.getMessage());
                runOnUiThread(() -> {
                    progressLeitora.setVisibility(View.GONE);
                    btnAtualizarLeitora.setEnabled(true);
                    txtLeitoraNome.setText("Leitora: Erro de conexão");
                    txtLeitoraStatus.setText("ERRO");
                    txtLeitoraStatus.setTextColor(Color.parseColor("#F44336"));
                    viewStatusDot.setBackgroundResource(R.drawable.circle_red);
                    txtApiStatus.setText("INDISPONÍVEL");
                    txtApiStatus.setTextColor(Color.parseColor("#F44336"));
                    viewApiDot.setBackgroundResource(R.drawable.circle_red);
                    txtLeitoraMensagem.setText("Falha de rede ao consultar a API. Verifique a conexão do tablet.");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    String json = responseBody != null ? responseBody.string() : "{}";
                    Log.d(TAG, "Reader status response (HTTP " + response.code() + "): " + json);

                    ReaderStatusResponse status = parseReaderStatus(json);

                    Log.d(TAG, "Reader status parsed:"
                            + " nome=" + status.leitora_nome
                            + " serial=" + status.serial
                            + " status=" + status.status_leitora
                            + " api_ativa=" + status.api_ativa
                            + " bateria=" + status.bateria
                            + " conexao=" + status.conexao
                            + " firmware=" + status.firmware);

                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);

                        // ── Nome / Serial da leitora ──────────────────────────
                        String nome = (status.leitora_nome != null && !status.leitora_nome.isEmpty())
                                ? status.leitora_nome : "Não configurada";
                        String serialInfo = (status.serial != null && !status.serial.isEmpty())
                                ? "\nSerial: " + status.serial : "";
                        txtLeitoraNome.setText("Leitora: " + nome + serialInfo);

                        // ── Status: online / idle / offline / sem_leitora ─────
                        // NOVO: "idle" = dispositivo pronto (state=IDLE + Wi-Fi)
                        //       mesmo que status=OFFLINE na API SumUp
                        switch (status.status_leitora) {
                            case "online":
                                txtLeitoraStatus.setText("● ONLINE");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#4CAF50"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_green);
                                break;
                            case "idle":
                                // Dispositivo pronto para transacionar (tela "Pronto" no SumUp Solo)
                                txtLeitoraStatus.setText("● PRONTO");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#4CAF50"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_green);
                                break;
                            case "offline":
                                txtLeitoraStatus.setText("● OFFLINE");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#F44336"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_red);
                                break;
                            case "nao_encontrada":
                                txtLeitoraStatus.setText("● NÃO ENCONTRADA");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#FF8C00"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
                                break;
                            case "sem_leitora":
                                txtLeitoraStatus.setText("● NÃO CONFIGURADA");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#FF8C00"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
                                break;
                            default:
                                txtLeitoraStatus.setText("● DESCONHECIDO");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#888888"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_gray);
                        }

                        // ── API SumUp ─────────────────────────────────────────
                        if (status.api_ativa) {
                            txtApiStatus.setText("● ATIVA");
                            txtApiStatus.setTextColor(Color.parseColor("#4CAF50"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_green);
                        } else {
                            txtApiStatus.setText("● INATIVA / TOKEN INVÁLIDO");
                            txtApiStatus.setTextColor(Color.parseColor("#F44336"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_red);
                        }

                        // ── Bateria / Conexão / Firmware ──────────────────────
                        // CORREÇÃO: exibir detalhes para online E idle E offline
                        // (quando há dados disponíveis, mesmo que status=OFFLINE)
                        boolean temDados = (status.bateria != null && !status.bateria.isEmpty())
                                || (status.conexao != null && !status.conexao.isEmpty())
                                || (status.firmware != null && !status.firmware.isEmpty());

                        boolean deveExibir = "online".equals(status.status_leitora)
                                || "idle".equals(status.status_leitora)
                                || ("offline".equals(status.status_leitora) && temDados);

                        if (deveExibir) {
                            layoutLeitoraDetalhes.setVisibility(View.VISIBLE);

                            String batStr = (status.bateria != null && !status.bateria.isEmpty())
                                    ? status.bateria : "--";
                            String conStr = (status.conexao != null && !status.conexao.isEmpty())
                                    ? status.conexao.toUpperCase() : "--";
                            String fwStr  = (status.firmware != null && !status.firmware.isEmpty())
                                    ? status.firmware : "--";

                            txtLeitoraBateria.setText("Bat: " + batStr);
                            txtLeitoraConexao.setText("Rede: " + conStr);
                            txtLeitoraFirmware.setText("FW: " + fwStr);

                            Log.d(TAG, "Detalhes leitora exibidos: bat=" + batStr
                                    + " rede=" + conStr + " fw=" + fwStr);
                        } else {
                            layoutLeitoraDetalhes.setVisibility(View.GONE);
                            Log.d(TAG, "Detalhes leitora ocultados (sem dados ou sem_leitora)");
                        }

                        // ── Mensagem de diagnóstico ───────────────────────────
                        if (status.mensagem != null && !status.mensagem.isEmpty()) {
                            txtLeitoraMensagem.setText(status.mensagem);
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Erro ao processar resposta reader_status: " + e.getMessage(), e);
                    runOnUiThread(() -> {
                        progressLeitora.setVisibility(View.GONE);
                        btnAtualizarLeitora.setEnabled(true);
                        txtLeitoraNome.setText("Leitora: Erro ao processar");
                        txtLeitoraMensagem.setText("Erro interno ao interpretar a resposta da API: " + e.getMessage());
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // Parser do JSON de status
    // ─────────────────────────────────────────────────────────────
    private ReaderStatusResponse parseReaderStatus(String json) {
        ReaderStatusResponse r = new ReaderStatusResponse();
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            r.leitora_nome   = obj.has("leitora_nome")   && !obj.get("leitora_nome").isJsonNull()   ? obj.get("leitora_nome").getAsString()   : "";
            r.reader_id      = obj.has("reader_id")      && !obj.get("reader_id").isJsonNull()      ? obj.get("reader_id").getAsString()       : "";
            r.serial         = obj.has("serial")         && !obj.get("serial").isJsonNull()         ? obj.get("serial").getAsString()          : "";
            r.status_leitora = obj.has("status_leitora") && !obj.get("status_leitora").isJsonNull() ? obj.get("status_leitora").getAsString()  : "offline";
            r.api_ativa      = obj.has("api_ativa")      && !obj.get("api_ativa").isJsonNull()      && obj.get("api_ativa").getAsBoolean();
            r.bateria        = obj.has("bateria")        && !obj.get("bateria").isJsonNull()        ? obj.get("bateria").getAsString()         : "";
            r.conexao        = obj.has("conexao")        && !obj.get("conexao").isJsonNull()        ? obj.get("conexao").getAsString()         : "";
            r.firmware       = obj.has("firmware")       && !obj.get("firmware").isJsonNull()       ? obj.get("firmware").getAsString()        : "";
            r.mensagem       = obj.has("mensagem")       && !obj.get("mensagem").isJsonNull()       ? obj.get("mensagem").getAsString()        : "";

            // Log do debug retornado pelo backend
            if (obj.has("debug") && !obj.get("debug").isJsonNull()) {
                Log.d(TAG, "Backend debug: " + obj.get("debug").toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro parse JSON reader_status: " + e.getMessage() + " | JSON: " + json);
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────────
    // Modelo de resposta
    // ─────────────────────────────────────────────────────────────
    private static class ReaderStatusResponse {
        String leitora_nome   = "";
        String reader_id      = "";
        String serial         = "";
        String status_leitora = "offline";
        boolean api_ativa     = false;
        String bateria        = "";
        String conexao        = "";
        String firmware       = "";
        String mensagem       = "";
    }
}
