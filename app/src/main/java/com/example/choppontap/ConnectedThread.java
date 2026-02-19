package com.example.choppontap;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mmHandler;
    private byte[] mmBuffer;

    // Use as constantes do BluetoothService para manter tudo centralizado
    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mmHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            // Se falhar aqui, a conexão é inútil. Notifique o Handler.
            mmHandler.obtainMessage(BluetoothService.MESSAGE_CONNECTION_LOST).sendToTarget();
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    public void run() {
        mmBuffer = new byte[1024];
        int numBytes;

        while (true) {
            try {
                numBytes = mmInStream.read(mmBuffer);
                // Envia os dados lidos de volta para o Service
                Message readMsg = mmHandler.obtainMessage(
                        BluetoothService.MESSAGE_READ, numBytes, -1, mmBuffer);
                readMsg.sendToTarget();
            } catch (IOException e) {
                // Conexão perdida! Notifica o Service.
                mmHandler.obtainMessage(BluetoothService.MESSAGE_CONNECTION_LOST).sendToTarget();
                break;
            }
        }
    }

    public void write(byte[] bytes) {
        try {
            mmOutStream.write(bytes);
            // Notifica o Service sobre o que foi escrito
            Message writtenMsg = mmHandler.obtainMessage(
                    BluetoothService.MESSAGE_WRITE, -1, -1, bytes);
            writtenMsg.sendToTarget();
        } catch (IOException e) {
            // Erro ao escrever, pode indicar que a conexão foi perdida.
        }
    }

    public void cancel() {

    }
}