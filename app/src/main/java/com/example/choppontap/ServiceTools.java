package com.example.choppontap;

import android.app.AlertDialog;
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
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
    /**
     * true  = TAP ativa (status=1 no banco)
     * false = TAP desativada (status=0 no banco)
     *
     * O estado inicial é determinado pelo campo `status` retornado pelo
     * verify_tap.php (ou reader_status.php). Enquanto não houver resposta,
     * assume-se que a TAP está ativa para não bloquear o técnico.
     */
    private boolean tapAtiva = true;

    private String android_id;

    // ── Flag: veio da tela OfflineTap (para reativar e voltar ao Home) ────────
    private boolean fromOffline = false;

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

        Log.d(TAG, "ServiceTools iniciado | android_id=" + android_id + " | fromOffline=" + fromOffline);

        loadSystemInfo();
        loadReaderStatus();
        sincronizarEstadoTap();

        // ── Listeners ─────────────────────────────────────────────────────────
        btnCalibrarPulsos.setOnClickListener(v ->
                startActivity(new Intent(this, CalibrarPulsos.class)));

        btnTempoAbertura.setOnClickListener(v ->
                startActivity(new Intent(this, ModificarTimeout.class)));

        btnSairTools.setOnClickListener(v -> {
            Log.d(TAG, "Botão Sair clicado | fromOffline=" + fromOffline);
            finish();
        });

        btnAtualizarLeitora.setOnClickListener(v -> {
            Log.d(TAG, "Botão Atualizar Leitora clicado");
            loadReaderStatus();
        });

        // ── Botão Toggle TAP ──────────────────────────────────────────────────
        btnToggleTap.setOnClickListener(v -> confirmarToggleTap());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sincroniza o estado visual do botão com o status real da TAP no servidor
    // ─────────────────────────────────────────────────────────────────────────
    private void sincronizarEstadoTap() {
        Log.d(TAG, "Sincronizando estado da TAP com o servidor...");

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);

        new ApiHelper().sendPost(body, "verify_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Falha ao sincronizar estado da TAP: " + e.getMessage());
                // Mantém estado padrão (ativa) sem alterar o botão
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    Log.d(TAG, "verify_tap resposta: " + json);

                    // O campo "status" no JSON indica se a TAP está ativa (1) ou não (0)
                    boolean ativa = true;
                    try {
                        // Descarta prefixo indesejado (whitespace/warnings PHP)
                        String jsonLimpo = json;
                        int idx = json.indexOf('{');
                        if (idx > 0) jsonLimpo = json.substring(idx);

                        com.google.gson.JsonReader reader =
                                new com.google.gson.JsonReader(new java.io.StringReader(jsonLimpo));
                        reader.setLenient(true);
                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        if (obj.has("status")) {
                            int status = obj.get("status").getAsInt();
                            ativa = (status == 1);
                        }
                    } catch (Exception ignored) {
                        Log.w(TAG, "Não foi possível parsear status da TAP: " + ignored.getMessage());
                    }

                    final boolean estadoFinal = ativa;
                    runOnUiThread(() -> {
                        tapAtiva = estadoFinal;
                        atualizarBotaoToggle();
                        Log.i(TAG, "Estado da TAP sincronizado: " + (tapAtiva ? "ATIVA" : "DESATIVADA"));
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Atualiza a aparência do botão conforme o estado atual
    // ─────────────────────────────────────────────────────────────────────────
    private void atualizarBotaoToggle() {
        if (tapAtiva) {
            // TAP ativa → botão vermelho "Desativar TAP"
            btnToggleTap.setText("Desativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")));
            btnToggleTap.setIconResource(android.R.drawable.ic_lock_power_off);
        } else {
            // TAP desativada → botão verde "Ativar TAP"
            btnToggleTap.setText("Ativar TAP");
            btnToggleTap.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")));
            btnToggleTap.setIconResource(android.R.drawable.ic_media_play);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Diálogo de confirmação antes de desativar/ativar
    // ─────────────────────────────────────────────────────────────────────────
    private void confirmarToggleTap() {
        String acao   = tapAtiva ? "desativar" : "ativar";
        String titulo = tapAtiva ? "Desativar esta TAP?" : "Ativar esta TAP?";
        String msg    = tapAtiva
                ? "A torneira ficará OFFLINE e os clientes serão redirecionados para o próximo TAP.\n\nDeseja continuar?"
                : "A torneira voltará a funcionar normalmente.\n\nDeseja ativar?";

        new AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setPositiveButton("Confirmar", (dialog, which) -> executarToggleTap(acao))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Executa a chamada à API toggle_tap.php
    // ─────────────────────────────────────────────────────────────────────────
    private void executarToggleTap(String acao) {
        Log.d(TAG, "Executando toggle TAP | acao=" + acao);

        // Feedback visual: desabilita botão e mostra progresso
        btnToggleTap.setEnabled(false);
        progressToggle.setVisibility(View.VISIBLE);

        Map<String, String> body = new HashMap<>();
        body.put("android_id", android_id);
        body.put("action", acao);

        new ApiHelper().sendPost(body, "toggle_tap.php", new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Falha de rede ao executar toggle TAP: " + e.getMessage());
                runOnUiThread(() -> {
                    btnToggleTap.setEnabled(true);
                    progressToggle.setVisibility(View.GONE);
                    Toast.makeText(ServiceTools.this,
                            "Erro de conexão. Verifique a internet e tente novamente.",
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody rb = response.body()) {
                    String json = rb != null ? rb.string() : "{}";
                    Log.d(TAG, "toggle_tap resposta (HTTP " + response.code() + "): " + json);

                    boolean sucesso = false;
                    String  novoStatus = "";
                    String  mensagem   = "";

                    // Extrai o JSON puro: descarta qualquer caractere antes do '{'
                    // (whitespace, BOM, warnings PHP que escaparam do ob_clean)
                    String jsonLimpo = json;
                    int braceIdx = json.indexOf('{');
                    if (braceIdx > 0) {
                        Log.w(TAG, "JSON com prefixo indesejado (" + braceIdx + " chars). Descartando: ["
                                + json.substring(0, braceIdx) + "]");
                        jsonLimpo = json.substring(braceIdx);
                    }

                    try {
                        com.google.gson.JsonReader reader =
                                new com.google.gson.JsonReader(new java.io.StringReader(jsonLimpo));
                        reader.setLenient(true); // tolera JSON levemente malformado
                        com.google.gson.JsonObject obj =
                                com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                        sucesso    = obj.has("success") && obj.get("success").getAsBoolean();
                        novoStatus = obj.has("status")  ? obj.get("status").getAsString()  : "";
                        mensagem   = obj.has("message") ? obj.get("message").getAsString() : "";
                        Log.d(TAG, "JSON parseado OK | success=" + sucesso + " status=" + novoStatus);
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao parsear resposta toggle_tap: " + e.getMessage()
                                + " | JSON bruto: [" + json + "]");
                    }

                    final boolean ok        = sucesso;
                    final String  statusFin = novoStatus;
                    final String  msgFin    = mensagem;

                    runOnUiThread(() -> {
                        btnToggleTap.setEnabled(true);
                        progressToggle.setVisibility(View.GONE);

                        if (ok) {
                            tapAtiva = "online".equals(statusFin);
                            atualizarBotaoToggle();

                            Log.i(TAG, "Toggle TAP bem-sucedido. Novo status: " + statusFin);

                            if (!tapAtiva) {
                                // TAP foi DESATIVADA → ir para tela OFFLINE
                                Log.i(TAG, "TAP desativada. Navegando para OfflineTap...");
                                Intent intent = new Intent(ServiceTools.this, OfflineTap.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                finishAffinity(); // Fecha toda a pilha (ServiceTools + AcessoMaster)
                            } else {
                                // TAP foi REATIVADA → voltar ao Home
                                Log.i(TAG, "TAP reativada. Voltando ao Home...");
                                Toast.makeText(ServiceTools.this,
                                        "TAP ativada! Voltando ao funcionamento normal.",
                                        Toast.LENGTH_SHORT).show();
                                Intent intent = new Intent(ServiceTools.this, Home.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                finishAffinity();
                            }
                        } else {
                            // Falha na API
                            String erroMsg = msgFin.isEmpty()
                                    ? "Não foi possível alterar o status da TAP."
                                    : msgFin;
                            Toast.makeText(ServiceTools.this, erroMsg, Toast.LENGTH_LONG).show();
                            Log.w(TAG, "Toggle TAP falhou: " + erroMsg);
                        }
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Informações do sistema (IMEI, Wi-Fi, Bluetooth)
    // ─────────────────────────────────────────────────────────────────────────
    private void loadSystemInfo() {
        txtInfoImei.setText("IMEI/ID: " + android_id);

        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info.getSSID();
        if (ssid != null && !ssid.equals("<unknown ssid>")) {
            txtInfoWifi.setText("Wi-Fi: Conectado a " + ssid.replace("\"", ""));
        } else {
            txtInfoWifi.setText("Wi-Fi: Desconectado");
        }

        txtInfoBluetooth.setText("Bluetooth: Ativo / Pronto");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status da Leitora de Cartão SumUp
    // ─────────────────────────────────────────────────────────────────────────
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
                    txtLeitoraMensagem.setText(
                            "Falha de rede ao consultar a API. Verifique a conexão do tablet.");
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

                        // Nome / Serial
                        String nome = (status.leitora_nome != null && !status.leitora_nome.isEmpty())
                                ? status.leitora_nome : "Não configurada";
                        String serialInfo = (status.serial != null && !status.serial.isEmpty())
                                ? "\nSerial: " + status.serial : "";
                        txtLeitoraNome.setText("Leitora: " + nome + serialInfo);

                        // Status
                        switch (status.status_leitora) {
                            case "online":
                                txtLeitoraStatus.setText("● ONLINE");
                                txtLeitoraStatus.setTextColor(Color.parseColor("#4CAF50"));
                                viewStatusDot.setBackgroundResource(R.drawable.circle_green);
                                break;
                            case "idle":
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

                        // API SumUp
                        if (status.api_ativa) {
                            txtApiStatus.setText("● ATIVA");
                            txtApiStatus.setTextColor(Color.parseColor("#4CAF50"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_green);
                        } else {
                            txtApiStatus.setText("● INATIVA / TOKEN INVÁLIDO");
                            txtApiStatus.setTextColor(Color.parseColor("#F44336"));
                            viewApiDot.setBackgroundResource(R.drawable.circle_red);
                        }

                        // Bateria / Conexão / Firmware
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
                        } else {
                            layoutLeitoraDetalhes.setVisibility(View.GONE);
                        }

                        // Mensagem de diagnóstico
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
                        txtLeitoraMensagem.setText(
                                "Erro interno ao interpretar a resposta da API: " + e.getMessage());
                    });
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parser do JSON de status
    // ─────────────────────────────────────────────────────────────────────────
    private ReaderStatusResponse parseReaderStatus(String json) {
        ReaderStatusResponse r = new ReaderStatusResponse();
        try {
            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            r.leitora_nome   = obj.has("leitora_nome")   && !obj.get("leitora_nome").isJsonNull()
                    ? obj.get("leitora_nome").getAsString()   : "";
            r.reader_id      = obj.has("reader_id")      && !obj.get("reader_id").isJsonNull()
                    ? obj.get("reader_id").getAsString()       : "";
            r.serial         = obj.has("serial")         && !obj.get("serial").isJsonNull()
                    ? obj.get("serial").getAsString()          : "";
            r.status_leitora = obj.has("status_leitora") && !obj.get("status_leitora").isJsonNull()
                    ? obj.get("status_leitora").getAsString()  : "offline";
            r.api_ativa      = obj.has("api_ativa")      && !obj.get("api_ativa").isJsonNull()
                    && obj.get("api_ativa").getAsBoolean();
            r.bateria        = obj.has("bateria")        && !obj.get("bateria").isJsonNull()
                    ? obj.get("bateria").getAsString()         : "";
            r.conexao        = obj.has("conexao")        && !obj.get("conexao").isJsonNull()
                    ? obj.get("conexao").getAsString()         : "";
            r.firmware       = obj.has("firmware")       && !obj.get("firmware").isJsonNull()
                    ? obj.get("firmware").getAsString()        : "";
            r.mensagem       = obj.has("mensagem")       && !obj.get("mensagem").isJsonNull()
                    ? obj.get("mensagem").getAsString()        : "";

            if (obj.has("debug") && !obj.get("debug").isJsonNull()) {
                Log.d(TAG, "Backend debug: " + obj.get("debug").toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro parse JSON reader_status: " + e.getMessage() + " | JSON: " + json);
        }
        return r;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Modelo de resposta
    // ─────────────────────────────────────────────────────────────────────────
    private static class ReaderStatusResponse {
        String  leitora_nome   = "";
        String  reader_id      = "";
        String  serial         = "";
        String  status_leitora = "offline";
        boolean api_ativa      = false;
        String  bateria        = "";
        String  conexao        = "";
        String  firmware       = "";
        String  mensagem       = "";
    }
}
