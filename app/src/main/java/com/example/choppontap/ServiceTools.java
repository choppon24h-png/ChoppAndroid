package com.example.choppontap;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class ServiceTools extends AppCompatActivity {
    private TextView txtInfoImei, txtInfoBluetooth, txtInfoWifi;
    private Button btnCalibrarPulsos, btnTempoAbertura, btnSairTools;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_tools);

        txtInfoImei = findViewById(R.id.txtInfoImei);
        txtInfoBluetooth = findViewById(R.id.txtInfoBluetooth);
        txtInfoWifi = findViewById(R.id.txtInfoWifi);
        
        btnCalibrarPulsos = findViewById(R.id.btnCalibrarPulsos);
        btnTempoAbertura = findViewById(R.id.btnTempoAbertura);
        btnSairTools = findViewById(R.id.btnSairTools);

        loadSystemInfo();

        btnCalibrarPulsos.setOnClickListener(v -> startActivity(new Intent(this, CalibrarPulsos.class)));
        btnTempoAbertura.setOnClickListener(v -> startActivity(new Intent(this, ModificarTimeout.class)));
        btnSairTools.setOnClickListener(v -> finish());
    }

    private void loadSystemInfo() {
        // IMEI (Android ID como referência)
        String android_id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        txtInfoImei.setText("IMEI/ID: " + android_id);

        // Wi-Fi Info
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info.getSSID();
        if (ssid != null && !ssid.equals("<unknown ssid>")) {
            txtInfoWifi.setText("Wi-Fi: Conectado a " + ssid.replace("\"", ""));
        } else {
            txtInfoWifi.setText("Wi-Fi: Desconectado");
        }

        // Bluetooth Info (Exemplo simples, pode ser expandido com BluetoothService)
        txtInfoBluetooth.setText("Bluetooth: Ativo / Pronto");
    }
}
