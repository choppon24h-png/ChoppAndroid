package com.example.choppontap;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
 *
 * Sistema de ATUALIZAÇÃO DE APK (v3.2.0):
 *   - checkAppUpdate(Context)  → consulta version.json no servidor
 *   - downloadNewVersion(...)  → baixa o APK via DownloadManager
 *   - installApk(File)         → instala via FileProvider (Android 7+)
 *   Não interfere em nenhuma lógica existente.
 *   Acionado apenas pelo botão btnAtualizarApp ou chamada manual.
 */
public class ServiceTools extends AppCompatActivity {

    private static final String TAG = "SERVICE_TOOLS";

    // ── URL do endpoint de versão (alterar para o servidor real) ─────────────
    private static final String VERSION_URL =
            "https://ochoppoficial.com.br/app/version.json";

    // ── Nome do arquivo APK salvo no Downloads ────────────────────────────────
    private static final String APK_FILE_NAME = "choppontap-update.apk";

    // ── Card de sistema ────────────────────────────────────────────────────
    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;

    // ── Card BLE (ESP32) ────────────────────────────────────────────────────
    private TextView txtBleMac, txtBleStatus, txtBleNome, txtBleAndroidId;
    private MaterialButton btnCopiarMac, btnCopiarAndroidId;

    // ── Card de leitora ───────────────────────────────────────────────────────
    private TextView txtLeitoraNome, txtLeitoraStatus, txtApiStatus, txtLeitoraMensagem;
    private TextView txtLeitoraBateria, txtLeitoraConexao, txtLeitoraFirmware;
    private LinearLayout layoutLeitoraDetalhes;
    private View viewStatusDot, viewApiDot;
    private ProgressBar progressLeitora;

    // ── Botões ────────────────────────────────────────────────────────────────
    private MaterialButton btnCalibrarPulsos, btnTempoAbertura, btnSairTools;
    private MaterialButton btnAtualizarLeitora, btnToggleTap;
    private MaterialButton btnAtualizarApp;          // NOVO — botão de atualização
    private ProgressBar progressToggle;
    private ProgressBar progressUpdate;              // NOVO — progresso do download

    // ── Estado da TAP ─────────────────────────────────────────────────────────
    private boolean tapAtiva = true;
    private String android_id;
    private boolean fromOffline = false;

    // ── Download em andamento ─────────────────────────────────────────────────
    private long mDownloadId = -1;
    private BroadcastReceiver mDownloadReceiver;

    // ── Bluetooth Service ─────────────────────────────────────────────────────
    private BluetoothService mBluetoothService;
    private boolean mIsServiceBound = false;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBluetoothService = ((BluetoothService.LocalBinder) service).getService();
            mIsServiceBound = true;
            Log.i(TAG, "BluetoothService vinculado ao ServiceTools");
            runOnUiThread(() -> {
                boolean conectado = mBluetoothService.connected();
                txtInfoBluetooth.setText("Bluetooth: " + (conectado ? "Conectado ao ESP32" : "Desconectado"));
                // Atualiza o card BLE com o status de conexão real
                atualizarStatusBle(conectado);
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

        // NOVO — botão e progresso de atualização do app
        btnAtualizarApp  = findViewById(R.id.btnAtualizarApp);
        progressUpdate   = findViewById(R.id.progressUpdate);

        // ── Card BLE (ESP32) ────────────────────────────────────────────────────
        txtBleMac       = findViewById(R.id.txtBleMac);
        txtBleStatus    = findViewById(R.id.txtBleStatus);
        txtBleNome      = findViewById(R.id.txtBleNome);
        txtBleAndroidId = findViewById(R.id.txtBleAndroidId);
        btnCopiarMac    = findViewById(R.id.btnCopiarMac);
        btnCopiarAndroidId = findViewById(R.id.btnCopiarAndroidId);

        android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // ── Inicialização ────────────────────────────────────────────────────
        loadSystemInfo();
        loadBleInfo();       // ← Card BLE: MAC, status e nome do ESP32
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

        // NOVO — listener do botão de atualização do app
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setOnClickListener(v -> checkAppUpdate(this));
        }
    }

    // =========================================================================
    // SISTEMA DE ATUALIZAÇÃO DE APK — v3.2.0
    // Todos os métodos abaixo são novos e não alteram nenhuma lógica existente.
    // =========================================================================

    /**
     * Ponto de entrada público para verificação de atualização.
     * Pode ser chamado por qualquer Activity via:
     *   ServiceTools.checkAppUpdate(context)  — chamada estática
     * ou internamente pelo botão btnAtualizarApp.
     *
     * @param context Context da Activity chamadora
     */
    public static void checkAppUpdate(Context context) {
        Log.d(TAG, "checkAppUpdate() iniciado");

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(VERSION_URL)
                .addHeader("Cache-Control", "no-cache")
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "checkAppUpdate - falha de rede: " + e.getMessage());
                if (context instanceof AppCompatActivity) {
                    ((AppCompatActivity) context).runOnUiThread(() ->
                            Toast.makeText(context,
                                    "Não foi possível verificar atualizações.\nVerifique a conexão.",
                                    Toast.LENGTH_LONG).show());
                }
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws IOException {
                try (okhttp3.ResponseBody rb = response.body()) {
                    if (!response.isSuccessful() || rb == null) {
                        Log.e(TAG, "checkAppUpdate - resposta inválida HTTP " + response.code());
                        return;
                    }

                    String json = rb.string();
                    Log.d(TAG, "checkAppUpdate - version.json: " + json);

                    // Parsear o JSON de versão
                    VersionInfo info = parseVersionJson(json);
                    if (info == null) {
                        Log.e(TAG, "checkAppUpdate - falha ao parsear version.json");
                        return;
                    }

                    // Obter versionCode atual do app instalado
                    int currentVersionCode = getCurrentVersionCode(context);
                    Log.d(TAG, "checkAppUpdate - versão atual: " + currentVersionCode
                            + " | versão servidor: " + info.versionCode);

                    if (context instanceof AppCompatActivity) {
                        AppCompatActivity activity = (AppCompatActivity) context;
                        activity.runOnUiThread(() ->
                                handleUpdateResult(activity, info, currentVersionCode));
                    }
                }
            }
        });
    }

    /**
     * Avalia o resultado da verificação e exibe o diálogo adequado.
     */
    private static void handleUpdateResult(AppCompatActivity activity,
                                           VersionInfo info,
                                           int currentVersionCode) {
        if (info.versionCode > currentVersionCode) {
            // Nova versão disponível
            String titulo   = "Nova atualização disponível";
            String mensagem = "Versão " + info.versionName + " disponível.\n\n"
                    + "Versão atual: " + getCurrentVersionName(activity) + "\n\n"
                    + (info.changelog != null && !info.changelog.isEmpty()
                    ? "O que há de novo:\n" + info.changelog : "");

            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(titulo)
                    .setMessage(mensagem)
                    .setPositiveButton("Atualizar agora", (dialog, which) -> {
                        if (activity instanceof ServiceTools) {
                            ((ServiceTools) activity).downloadNewVersion(info.apkUrl);
                        }
                    })
                    .setNegativeButton("Agora não", null);

            if (info.force) {
                // Atualização obrigatória: remove o botão "Agora não"
                builder.setCancelable(false)
                        .setNegativeButton(null, null);
            }

            builder.show();

        } else {
            // App já está atualizado
            Toast.makeText(activity,
                    "O aplicativo já está na versão mais recente (" + getCurrentVersionName(activity) + ").",
                    Toast.LENGTH_LONG).show();
            Log.d(TAG, "checkAppUpdate - app já está atualizado");
        }
    }

    /**
     * Baixa o APK usando DownloadManager e registra um BroadcastReceiver
     * para iniciar a instalação quando o download concluir.
     *
     * @param apkUrl URL completa do APK no servidor
     */
    private void downloadNewVersion(String apkUrl) {
        Log.d(TAG, "downloadNewVersion() url=" + apkUrl);

        // Exibe progresso
        if (progressUpdate != null) {
            progressUpdate.setVisibility(View.VISIBLE);
        }
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setEnabled(false);
            btnAtualizarApp.setText("Baixando...");
        }

        Toast.makeText(this, "Iniciando download da atualização...", Toast.LENGTH_SHORT).show();

        // Remove APK anterior se existir
        File apkFile = getApkFile();
        if (apkFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            apkFile.delete();
        }

        // Configura o DownloadManager
        DownloadManager.Request dmRequest = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("ChoppON — Atualização")
                .setDescription("Baixando versão mais recente do app...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Log.e(TAG, "downloadNewVersion - DownloadManager indisponível");
            resetUpdateButton();
            return;
        }

        mDownloadId = dm.enqueue(dmRequest);
        Log.d(TAG, "downloadNewVersion - download enfileirado id=" + mDownloadId);

        // Registra receiver para capturar conclusão do download
        mDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (completedId != mDownloadId) return;

                Log.d(TAG, "downloadNewVersion - download concluído id=" + completedId);
                unregisterReceiver(mDownloadReceiver);
                mDownloadReceiver = null;

                // Verifica se o download foi bem-sucedido
                DownloadManager.Query query = new DownloadManager.Query()
                        .setFilterById(mDownloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int status = (statusIdx >= 0) ? cursor.getInt(statusIdx) : -1;
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        runOnUiThread(() -> {
                            resetUpdateButton();
                            installApk(getApkFile());
                        });
                    } else {
                        Log.e(TAG, "downloadNewVersion - download falhou status=" + status);
                        runOnUiThread(() -> {
                            resetUpdateButton();
                            Toast.makeText(ServiceTools.this,
                                    "Falha no download. Tente novamente.",
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                }
            }
        };

        registerReceiver(mDownloadReceiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    /**
     * Inicia a instalação do APK baixado usando FileProvider (Android 7+).
     *
     * @param apkFile Arquivo APK a ser instalado
     */
    private void installApk(File apkFile) {
        Log.d(TAG, "installApk() path=" + apkFile.getAbsolutePath());

        if (!apkFile.exists()) {
            Log.e(TAG, "installApk - arquivo não encontrado: " + apkFile.getAbsolutePath());
            Toast.makeText(this, "Arquivo de atualização não encontrado.", Toast.LENGTH_LONG).show();
            return;
        }

        // Verifica permissão de instalação de fontes desconhecidas (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Log.w(TAG, "installApk - permissão de instalar APK não concedida, abrindo configurações");
                Toast.makeText(this,
                        "Permita a instalação de apps de fontes desconhecidas nas configurações.",
                        Toast.LENGTH_LONG).show();
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + getPackageName()));
                startActivity(settingsIntent);
                return;
            }
        }

        Uri apkUri;
        try {
            apkUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    apkFile);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "installApk - FileProvider falhou: " + e.getMessage());
            Toast.makeText(this, "Erro ao preparar instalação do APK.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent installIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Log.d(TAG, "installApk - iniciando instalação via Intent");
        startActivity(installIntent);
    }

    // ── Helpers privados ──────────────────────────────────────────────────────

    /** Retorna o File do APK no diretório Downloads público. */
    private static File getApkFile() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                APK_FILE_NAME);
    }

    /** Obtém o versionCode atual do app instalado. */
    private static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            } else {
                //noinspection deprecation
                return pi.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "getCurrentVersionCode - " + e.getMessage());
            return 0;
        }
    }

    /** Obtém o versionName atual do app instalado. */
    private static String getCurrentVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "desconhecida";
        }
    }

    /** Parseia o JSON de versão retornado pelo servidor. */
    private static VersionInfo parseVersionJson(String json) {
        try {
            // Limpa possíveis caracteres antes do JSON
            int idx = json.indexOf('{');
            if (idx > 0) json = json.substring(idx);

            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            VersionInfo info = new VersionInfo();
            info.versionCode = obj.has("versionCode") ? obj.get("versionCode").getAsInt()    : 0;
            info.versionName = obj.has("versionName") ? obj.get("versionName").getAsString() : "";
            info.apkUrl      = obj.has("apkUrl")      ? obj.get("apkUrl").getAsString()      : "";
            info.force       = obj.has("force")       && obj.get("force").getAsBoolean();
            info.changelog   = obj.has("changelog")   ? obj.get("changelog").getAsString()   : "";
            return info;
        } catch (Exception e) {
            Log.e(TAG, "parseVersionJson - erro: " + e.getMessage());
            return null;
        }
    }

    /** Restaura o estado do botão de atualização. */
    private void resetUpdateButton() {
        if (progressUpdate != null) progressUpdate.setVisibility(View.GONE);
        if (btnAtualizarApp != null) {
            btnAtualizarApp.setEnabled(true);
            btnAtualizarApp.setText("Verificar Atualização");
        }
    }

    /** Modelo de dados do version.json. */
    private static class VersionInfo {
        int     versionCode = 0;
        String  versionName = "";
        String  apkUrl      = "";
        boolean force       = false;
        String  changelog   = "";
    }

    // =========================================================================
    // LÓGICA EXISTENTE — não alterada
    // =========================================================================

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

                    final boolean ok          = sucesso;
                    final String  statusFinal = novoStatus;

                    runOnUiThread(() -> {
                        btnToggleTap.setEnabled(true);
                        progressToggle.setVisibility(View.GONE);

                        if (ok) {
                            tapAtiva = "online".equals(statusFinal);
                            atualizarBotaoToggle();

                            if (!tapAtiva) {
                                desconectarBluetooth();
                                Log.i(TAG, "TAP desativada → BT desconectado → navegando para OfflineTap");
                                Intent intent = new Intent(ServiceTools.this, OfflineTap.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();
                            } else {
                                reconectarBluetooth();
                                Log.i(TAG, "TAP ativada → BT reconectando → navegando para Home");
                                Intent intent = new Intent(ServiceTools.this, Home.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finishAffinity();
                            }
                        } else {
                            Toast.makeText(ServiceTools.this,
                                    "Falha ao alterar status da TAP", Toast.LENGTH_SHORT).show();
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
            mBluetoothService.scanLeDevice(true);
        } else {
            Log.w(TAG, "BluetoothService não vinculado — Home fará a reconexão no onCreate()");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Card BLE (ESP32) — MAC e informações do dispositivo
    // ─────────────────────────────────────────────────────────────────────────────
    /**
     * Carrega as informações do dispositivo BLE (ESP32) salvas no SharedPreferences.
     * O MAC é gravado em "tap_config" → "esp32_mac" pelo BluetoothService ao conectar.
     * O nome do dispositivo é o prefixo "CHOPP_" seguido do identificador do ESP32.
     * Exibe o Android ID do tablet para facilitar o cadastro na API web.
     */
    private void loadBleInfo() {
        String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                .getString("esp32_mac", null);

        if (txtBleMac == null) return; // card não presente no layout

        if (mac != null && !mac.isEmpty()) {
            txtBleMac.setText(mac);
            txtBleNome.setText("Nome esperado: CHOPP_" + mac.replace(":", "").substring(6));
        } else {
            txtBleMac.setText("Não encontrado");
            txtBleNome.setText("Aguardando conexão BLE...");
        }

        // Android ID do tablet (necessário para cadastrar o TAP na API web)
        if (txtBleAndroidId != null) {
            txtBleAndroidId.setText(android_id);
        }

        // Status inicial (será atualizado quando o BluetoothService conectar)
        if (txtBleStatus != null) {
            txtBleStatus.setText("● AGUARDANDO...");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#888888"));
        }

        // Botão copiar MAC
        if (btnCopiarMac != null) {
            btnCopiarMac.setOnClickListener(v -> {
                String macAtual = txtBleMac.getText().toString();
                if (!macAtual.isEmpty() && !macAtual.equals("Não encontrado")) {
                    copiarParaClipboard("MAC ESP32", macAtual);
                    Toast.makeText(this, "MAC copiado: " + macAtual, Toast.LENGTH_SHORT).show();
                    Log.i(TAG, "MAC BLE copiado: " + macAtual);
                } else {
                    Toast.makeText(this, "MAC não disponível. Conecte o BLE primeiro.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Botão copiar Android ID
        if (btnCopiarAndroidId != null) {
            btnCopiarAndroidId.setOnClickListener(v -> {
                copiarParaClipboard("Android ID", android_id);
                Toast.makeText(this, "Android ID copiado!", Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Android ID copiado: " + android_id);
            });
        }
    }

    /**
     * Atualiza o indicador de status BLE no card após o BluetoothService conectar.
     * Chamado pelo onServiceConnected quando o status real do GATT é conhecido.
     */
    private void atualizarStatusBle(boolean conectado) {
        if (txtBleStatus == null) return;
        if (conectado) {
            txtBleStatus.setText("● CONECTADO");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
            // Atualiza o MAC se ainda não estava salvo (conexão feita antes de abrir ServiceTools)
            if (mBluetoothService != null && mBluetoothService.connected()) {
                String mac = getSharedPreferences("tap_config", Context.MODE_PRIVATE)
                        .getString("esp32_mac", null);
                if (mac != null && txtBleMac != null) {
                    txtBleMac.setText(mac);
                    if (txtBleNome != null)
                        txtBleNome.setText("Nome esperado: CHOPP_" + mac.replace(":", "").substring(6));
                }
            }
        } else {
            txtBleStatus.setText("● DESCONECTADO");
            txtBleStatus.setTextColor(android.graphics.Color.parseColor("#F44336"));
        }
    }

    /** Copia texto para a área de transferência. */
    private void copiarParaClipboard(String label, String texto) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, texto));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Informações do sistema
    // ─────────────────────────────────────────────────────────────────────────────
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
    // Ciclo de vida — desvincula o serviço e o receiver ao destruir a Activity
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIsServiceBound) {
            unbindService(mServiceConnection);
            mIsServiceBound = false;
        }
        // Desregistra o receiver de download se ainda estiver ativo
        if (mDownloadReceiver != null) {
            try {
                unregisterReceiver(mDownloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver já foi desregistrado
            }
            mDownloadReceiver = null;
        }
    }
}
