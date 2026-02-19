package com.example.choppontap;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Classe para gerenciar o indicador visual de status de conexão Bluetooth
 * 
 * Responsabilidades:
 * - Exibir status de conexão (Conectado, Desconectado, Erro)
 * - Mudar cores baseado no status
 * - Mostrar/esconder alerta em vermelho
 */
public class BluetoothStatusIndicator {
    
    private LinearLayout statusContainer;
    private View statusIndicator;
    private TextView statusText;
    private ImageView bluetoothIcon;
    private Context context;
    
    // Estados possíveis
    public static final int STATUS_CONNECTED = 0;
    public static final int STATUS_DISCONNECTED = 1;
    public static final int STATUS_CONNECTING = 2;
    public static final int STATUS_ERROR = 3;
    
    private int currentStatus = STATUS_DISCONNECTED;
    
    /**
     * Construtor
     * @param container LinearLayout que contém todos os elementos
     */
    public BluetoothStatusIndicator(LinearLayout container) {
        this.statusContainer = container;
        this.context = container.getContext();
        
        // Encontrar os elementos do layout
        this.statusIndicator = container.findViewById(R.id.bluetooth_status_indicator);
        this.statusText = container.findViewById(R.id.bluetooth_status_text);
        this.bluetoothIcon = container.findViewById(R.id.bluetooth_icon);
    }
    
    /**
     * Atualizar o status da conexão Bluetooth
     */
    public void setStatus(int status, String message) {
        this.currentStatus = status;
        
        switch (status) {
            case STATUS_CONNECTED:
                updateConnected(message);
                break;
            case STATUS_DISCONNECTED:
                updateDisconnected(message);
                break;
            case STATUS_CONNECTING:
                updateConnecting(message);
                break;
            case STATUS_ERROR:
                updateError(message);
                break;
        }
    }
    
    /**
     * Status: Conectado (Verde)
     */
    private void updateConnected(String message) {
        setIndicatorColor(Color.parseColor("#4CAF50")); // Verde
        statusText.setText(message != null ? message : "✓ Conectado ao Chopp");
        statusText.setTextColor(Color.parseColor("#4CAF50"));
        setIconColor(Color.parseColor("#4CAF50"));
        statusContainer.setBackgroundColor(Color.parseColor("#E8F5E9")); // Verde claro
    }
    
    /**
     * Status: Desconectado (Cinza)
     */
    private void updateDisconnected(String message) {
        setIndicatorColor(Color.parseColor("#9E9E9E")); // Cinza
        statusText.setText(message != null ? message : "Desconectado");
        statusText.setTextColor(Color.parseColor("#9E9E9E"));
        setIconColor(Color.parseColor("#9E9E9E"));
        statusContainer.setBackgroundColor(Color.parseColor("#F5F5F5")); // Cinza claro
    }
    
    /**
     * Status: Conectando (Amarelo/Laranja)
     */
    private void updateConnecting(String message) {
        setIndicatorColor(Color.parseColor("#FF9800")); // Laranja
        statusText.setText(message != null ? message : "⏳ Conectando...");
        statusText.setTextColor(Color.parseColor("#FF9800"));
        setIconColor(Color.parseColor("#FF9800"));
        statusContainer.setBackgroundColor(Color.parseColor("#FFF3E0")); // Laranja claro
    }
    
    /**
     * Status: Erro (Vermelho) - ALERTA CRÍTICO
     */
    private void updateError(String message) {
        setIndicatorColor(Color.parseColor("#F44336")); // Vermelho
        statusText.setText(message != null ? message : "🔴 CONEXÃO BLUETOOTH PERDIDA");
        statusText.setTextColor(Color.parseColor("#F44336"));
        setIconColor(Color.parseColor("#F44336"));
        statusContainer.setBackgroundColor(Color.parseColor("#FFEBEE")); // Vermelho claro
        
        // Animar o alerta (piscar)
        animateErrorAlert();
    }
    
    /**
     * Animar o alerta em vermelho (piscar)
     */
    private void animateErrorAlert() {
        statusContainer.setAlpha(1.0f);
        statusContainer.animate()
            .alpha(0.7f)
            .setDuration(500)
            .withEndAction(() -> {
                statusContainer.animate()
                    .alpha(1.0f)
                    .setDuration(500)
                    .start();
            })
            .start();
    }
    
    /**
     * Definir a cor do indicador (círculo)
     */
    private void setIndicatorColor(int color) {
        if (statusIndicator != null) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(color);
            statusIndicator.setBackground(drawable);
        }
    }
    
    /**
     * Definir a cor do ícone Bluetooth
     */
    private void setIconColor(int color) {
        if (bluetoothIcon != null) {
            bluetoothIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }
    
    /**
     * Obter o status atual
     */
    public int getCurrentStatus() {
        return currentStatus;
    }
    
    /**
     * Verificar se está conectado
     */
    public boolean isConnected() {
        return currentStatus == STATUS_CONNECTED;
    }
    
    /**
     * Mostrar/esconder o indicador
     */
    public void setVisible(boolean visible) {
        statusContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
