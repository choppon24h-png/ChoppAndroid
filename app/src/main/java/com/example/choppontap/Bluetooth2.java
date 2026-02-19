package com.example.choppontap;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.data.Data;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;


public class Bluetooth2 extends BleManager {
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice esp32Device;
    private static final String ESP32_MAC_ADDRESS = "94:54:C5:E8:72:12"; // Replace with your ESP32's MAC
    private static final UUID SPP_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"); // Standard SPP UUID
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;
    private ScanCallback scanCallback;
    private TextView txtQtd;
    private TextView txtVz;
    private TextView txtMl;
    public Boolean started = false;
    private TextView txtQtdLocal;
    protected static final UUID CHARACTERISTIC_UPDATE_NOTIFICATION_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    protected static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    protected static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    protected static final UUID serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");

    private boolean notificationEnabled = false;
    private Integer mlsLiberados = 0;
    private String command;
    private BluetoothGattService gattService;
    private Context localContext;
    public Bluetooth2(Context context)
    {
        super(context);
        localContext = context;
    }
    @Override
    public boolean isRequiredServiceSupported(BluetoothGatt gatt)
    {
        bluetoothGatt = gatt;
        BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
        Log.d("B","B");
        return true;
    }




    public boolean connected() {
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);
        if (!connectedGattDevices.isEmpty()) {
            return true;
        }
        return false;
    }


    private void checkDevice(Context context, BluetoothGattCallback gattCallback) {
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

        if(!devices.isEmpty()) {
            connectDevice(context,gattCallback);
        }

    }
    public void connectDevice(Context context, BluetoothGattCallback gattCallback )
    {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }

        if(bluetoothLeScanner != null)
        {
            ScanCallback scanCallbackStop = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {

                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    // Process a batch of scan results
                }

                @Override
                public void onScanFailed(int errorCode) {
                    // Handle scan failure
                }
            };
            bluetoothLeScanner.stopScan(scanCallbackStop);
        }
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for(BluetoothDevice device : devices){
            if(bluetoothGatt == null && device.getAlias() != null && device.getAlias().equals("CHOPPE")){
                bluetoothGatt = device.connectGatt(context, true, gattCallback);

            }
        }

    }

    public void liberarLiquido(Context context, Integer qtd_ml , String checkout_id, String android_id, TextView txtQtd1, TextView txtVz1, TextView txtMl1, Button btnLiberar,Integer liberado)
    {
        txtQtd = txtQtd1;
        txtVz = txtVz1;
        txtMl = txtMl1;


        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {

            close();

            final Integer mlsSolicitado = qtd_ml;
            String command = "$ML:"+qtd_ml.toString();
            byte[] messageBytes = command.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "SB");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String receivedData = new String(data2);
                        runOnCallbackThread(new Runnable() {
                            @Override
                            public void run() {
                                if(receivedData.contains("VZ")){
                                   // txtVz.setText(receivedData);
                                }
                                if(receivedData.contains("QP")){
                                    txtQtd.setText(receivedData);
                                }
                                if(receivedData.contains("VP")){
                                    String vp = receivedData.replace("VP:","");
                                    Double mlsFloat = Double.valueOf(vp);
                                    Integer mls = (int) Math.round(mlsFloat);
                                    mlsLiberados += mls;
                                    if(liberado > 0)
                                    {
                                        mls += liberado;
                                    }
                                    txtVz.setText(mls.toString()+"ML");
                                    if(mls != mlsSolicitado){
                                        btnLiberar.setVisibility(TextView.VISIBLE);
                                        Log.d("Diferente","Diferente");
                                    }else{
                                        btnLiberar.setVisibility(TextView.GONE);
                                    }
                                }
                                if(receivedData.contains("ML")){
                                    sendRequest(mlsLiberados,checkout_id,android_id);
                                    txtMl.setText(receivedData);
                                    close();
                                }
                            }
                        });
                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();

}
    }


    public void liberacaoContinua(TextView txtVolumeLiberado,String android_id)
    {
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {

            close();

            String command = "$LB:";
            byte[] messageBytes = command.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String receivedData = new String(data2);
                        Log.d("LB",receivedData);
                        String vp = receivedData.replace("VP:","");
                        Double mlsFloat = Double.valueOf(vp);
                        Integer mls = (int) Math.round(mlsFloat);
                        sendRequest(mls,android_id);
                        runOnCallbackThread(new Runnable() {
                            @Override
                            public void run() {
                                txtVolumeLiberado.setText(receivedData);
                            }
                        });
                        if(receivedData.contains("ML")){
                            close();
                        }
                    }
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();

        }
    }

    public void liberarLiquidoTeste(Context context, Integer qtd_ml, TextView txtVolumeLiberado, TextView txtQtd, EditText novaQtd)
    {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {
            close();
            String command = "$ML:"+qtd_ml.toString();
            byte[] messageBytes = command.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "SB");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String receivedData = new String(data2);
                        runOnCallbackThread(new Runnable() {
                            @Override
                            public void run() {
                                if(receivedData.contains("VP")){
                                    String vp = receivedData.replace("VP:","");
                                    Double mlsFloat = Double.valueOf(vp);
                                    Integer mls = (int) Math.round(mlsFloat);

                                    mlsLiberados = mls;
                                    txtVolumeLiberado.setText(mls.toString()+"ML");
                                }
                                if(receivedData.contains("ML")){
                                    String qtdAtual = txtQtd.getText().toString();
                                    String vp = receivedData.replace("ML:","");
                                    String liberado = txtVolumeLiberado.getText().toString();

                                    String pulsos = qtdAtual.replace("PL:","");
                                    Float pulsosInt = Float.parseFloat(pulsos.replace("\n",""));

                                    String liberadoF = liberado.replace("ML","");
                                    Float liberadoInt = Float.parseFloat(liberadoF.replace("\n",""));


                                    Float mlsFloat = ((pulsosInt)/(liberadoInt/10));
                                    Integer mls = (int) Math.round(mlsFloat);

                                    mlsLiberados = mls;
                                    if(liberadoInt != 100.0)
                                    {
                                        runOnCallbackThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                txtVolumeLiberado.setText(vp+"ML");
                                                close();
                                            }
                                        });
                                    }
                                }
                            }
                        });
                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();

        }
    }

    public void changePulsos(Context context, Integer pulsoPorLitro,TextView qtdAtual){

        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);
        txtQtdLocal = qtdAtual;
        if(!connectedGattDevices.isEmpty()) {

            close();
            String command = "$PL:"+pulsoPorLitro.toString();
            byte[] messageBytes = command.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "SB");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String data1 = new String(data2);
                        Log.d("HEHE",data1);
                        Log.d("AQUI","AQUI");
                        getPulsos(context,txtQtdLocal);
                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();
        }

    }

    public void getPulsos(Context context, TextView txtQtd){
        txtQtdLocal = txtQtd;


        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {

            byte[] messageBytes = "$PL:0".getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "SB");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String data1 = new String(data2);
                        Log.d("HEHE",data1);
                        if(data1.contains("ML")){
                            close();
                        }
                        if(data1.contains("PL")){
                            runOnCallbackThread(new Runnable() {
                                @Override
                                public void run() {
                                    txtQtdLocal.setText(data1.replace("\n",""));
                                    close();
                                }
                            });

                        }
                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();
        }


    }
    public void getTimeout(TextView txtTimeoutAtual){
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {
            String commando = "$TO:";
            byte[] messageBytes = commando.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "STO");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String data1 = new String(data2);
                        Log.d("HEHE",data1);
                        runOnCallbackThread(new Runnable() {
                            @Override
                            public void run() {
                                txtTimeoutAtual.setText(data1);
                            }
                        });

                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();
        }
    }
    public void setTimeout(String value){
        Integer ms = Integer.parseInt(value)*1000;
        BluetoothManager manager = (BluetoothManager) localContext.getSystemService(Context.BLUETOOTH_SERVICE);
        List<BluetoothDevice> connectedGattDevices = manager.getConnectedDevices(BluetoothProfile.GATT);

        if(!connectedGattDevices.isEmpty()) {
            String commando = "$TO:"+ms.toString();
            byte[] messageBytes = commando.getBytes();
            connect(connectedGattDevices.get(0)).done(device -> {
                BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);
                Log.d("a", "STO");
                writeCharacteristic(rx, messageBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                        .enqueue();
                BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                setNotificationCallback(tx).with(new DataReceivedCallback() {
                    @Override
                    public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                        byte[] data2 = data.getValue();
                        String data1 = new String(data2);
                        Log.d("HEHE",data1);

                    }
                }).then(()->{
                    Log.d("THEN","S");
                });

                enableNotifications(tx).enqueue();


                readCharacteristic(tx).enqueue();
            }).enqueue();
        }
    }


    public void sendCommand(BluetoothGatt gatt,Context context,String command)
    {

        BluetoothGattService gattService = getUartService(gatt);
        if(gattService == null)
        {
            return;
        }
        List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();
        if(!started){
            started = true;
            BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
            BluetoothGattCharacteristic rx = gattService.getCharacteristic(RX);

            byte[] messageBytes = command.getBytes();
            rx.setValue(messageBytes);
            gatt.writeCharacteristic(rx);

            Log.d("tamanho",String.valueOf(gattCharacteristics.size()));

            gatt.setCharacteristicNotification(tx, true);
            gatt.readCharacteristic(tx);


        }


    }
    public BluetoothGattService getUartService(BluetoothGatt gatt)
    {
        List<BluetoothGattService> gattServices = gatt.getServices();
        if(gattServices == null)
        {
            return null;
        }
        for (BluetoothGattService gattService : gattServices) {
            gattService.getCharacteristics();

            if(!gattService.getUuid().equals(UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")) &&
                    !gattService.getUuid().equals(UUID.fromString("00001801-0000-1000-8000-00805f9b34fb"))){
                return gattService;
            }
        }
        return null;
    }

    public void scan(Context context,Button btnCalibrar){

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            // Request to enable Bluetooth
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                if(device.getAlias() == null || (device.getAlias() != null && !device.getAlias().equals("CHOPPE")))
                {
                    return;
                }
                BluetoothManager bluetoothManager = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
                List<BluetoothDevice> devices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);

                if(devices.isEmpty()) {
                    connect(device).done(device2 -> {
                        BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                        BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                        setNotificationCallback(tx).with(new DataReceivedCallback() {
                            @Override
                            public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                                byte[] data2 = data.getValue();
                                String receivedData = new String(data2);
                                String id = receivedData.replace("ID:","").replace("\\n","");
                                Log.d("ID",id);
                                runOnCallbackThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if(id.replace("\n","").equals("0589af9a")) {
                                            btnCalibrar.setVisibility(TextView.VISIBLE);
                                        }
                                    }
                                });
                            }
                        }).then(()->{
                            Log.d("THEN","S");
                        });

                        enableNotifications(tx).enqueue();
                        readCharacteristic(tx).enqueue();
                    }).enqueue();
                }
                else{
                    BluetoothGattService gattService = bluetoothGatt.getService(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"));
                    BluetoothGattCharacteristic tx = gattService.getCharacteristic(TX);
                    setNotificationCallback(tx).with(new DataReceivedCallback() {
                        @Override
                        public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                            byte[] data2 = data.getValue();
                            String receivedData = new String(data2);
                            String id = receivedData.replace("ID:","").replace("\n","");
                            Log.d("ID",id);
                            runOnCallbackThread(new Runnable() {
                                @Override
                                public void run() {
                                    if(id.replace("\n","").equals("0589af9a")) {
                                        btnCalibrar.setVisibility(TextView.VISIBLE);
                                    }
                                }
                            });
                        }
                    }).then(()->{
                        Log.d("THEN","S");
                    });

                    enableNotifications(tx).enqueue();
                    readCharacteristic(tx).enqueue();
                }

            }
            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                // Process a batch of scan results
            }

            @Override
            public void onScanFailed(int errorCode) {
                // Handle scan failure
            }
        };
        bluetoothLeScanner.startScan(scanCallback);
    }

    private void sendRequest(Integer qtd_ml,String checkout_id, String android_id)
    {
        Map<String,String> body = new HashMap<>();

        body.put("android_id",android_id);
        body.put("qtd_ml",qtd_ml.toString());
        body.put("checkout_id",checkout_id);

        Callback callback = new Callback() {
            @Override public void onFailure(Call call, IOException e) {

            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {

                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    Log.d("Response",responseBody.string());

                }catch (Exception e){

                }
            }
        };

        ApiHelper apiHelper = new ApiHelper();
        apiHelper.sendPost(body,"liberacao.php",callback);
    }
    private void sendRequest(Integer qtd_ml, String android_id)
    {
        Map<String,String> body = new HashMap<>();

        body.put("android_id",android_id);
        body.put("qtd_ml",qtd_ml.toString());
        Callback callback = new Callback() {
            @Override public void onFailure(Call call, IOException e) {

            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {

                    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
                    Log.d("Response",responseBody.string());

                }catch (Exception e){

                }
            }
        };

        ApiHelper apiHelper = new ApiHelper();
        apiHelper.sendPost(body,"liquido_liberado.php",callback);
    }

}
