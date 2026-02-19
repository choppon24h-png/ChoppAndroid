package com.example.choppontap;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.BleManagerCallbacks;
import no.nordicsemi.android.ble.data.Data;

public class Bluetooth extends BleManager {
    private static final String TAG = "ChoppBleManager";
    private BluetoothGatt bluetoothGatt;
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_CHARACTERISTIC_TX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_CHARACTERISTIC_RX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;

    public final MutableLiveData<String> dadosRecebidos = new MutableLiveData<>();
    public Bluetooth(Context context) {
        super(context);
    }

    @Override
    public boolean isRequiredServiceSupported(BluetoothGatt gatt)
    {
        final BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
        if (service != null) {
            // Tenta encontrar as características de escrita (TX) e leitura (RX)
            txCharacteristic = service.getCharacteristic(NUS_CHARACTERISTIC_TX_UUID);
            rxCharacteristic = service.getCharacteristic(NUS_CHARACTERISTIC_RX_UUID);
        }
        // A conexão só é válida se ambas as características existirem.
        boolean supported = txCharacteristic != null && rxCharacteristic != null;
        if(supported) {
            Log.d(TAG, "Serviços e características do CHOPPE validados com sucesso.");
        } else {
            Log.e(TAG, "Dispositivo não suporta o serviço NUS necessário.");
        }
        return supported;



    }
    @Override
    protected void initialize() {
        // Habilita as notificações para a característica RX (recebimento de dados)
        setNotificationCallback(rxCharacteristic)
                .with((device, data) -> {
                    // Este bloco é executado toda vez que o dispositivo envia dados.
                    String textoRecebido = data.getStringValue(0);
                    Log.d(TAG, "Dados recebidos: " + textoRecebido);
                    // Atualiza o LiveData, que notificará o BluetoothService.
                    if(textoRecebido.contains("VP") || textoRecebido.contains("ML") || textoRecebido.contains("PL") || textoRecebido.contains("OK") || textoRecebido.contains("ERRO"))
                    {
                        dadosRecebidos.postValue(textoRecebido);
                    }
                });

        enableNotifications(rxCharacteristic).enqueue();
        Log.d(TAG, "Notificações habilitadas. Pronto para receber dados.");
    }

    public void enviarComando(@NonNull final String texto) {
        if (txCharacteristic == null || texto.isEmpty()) {
            Log.w("ERRO", "Tentativa de escrita falhou: Característica indisponível ou texto vazio.");
            return;
        }

        // A biblioteca BleManager enfileira as operações para garantir que sejam executadas
        // uma de cada vez, de forma segura.
        writeCharacteristic(txCharacteristic, Data.from(texto))
                .done(device -> Log.d("Enviado", "Comando enviado com sucesso: " + texto))
                .fail((device, status) -> Log.e("ERRO", "Falha ao enviar comando. Status: " + status))
                .enqueue();
    }
    public void limparDadosRecebidos() {
        // Define o valor como null (ou "" se preferir)
        // O postValue é seguro para chamar de qualquer thread
        dadosRecebidos.postValue(null);
    }
}


