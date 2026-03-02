package com.example.choppontap;

public class Tap {
    public String image;
    public String bebida;
    public String volume;
    public Float preco;
    public Boolean cartao;
    public String esp32_mac;  // MAC address do ESP32 vinculado
    public Integer tap_status; // FIX: 1=ativa, 0=desativada (OFFLINE) — retornado pelo verify_tap.php v1.1

    public String toString(){
        return image;
    }
}
