package com.example.choppontap;

public class Logs {

    public Logs(Integer id,String tipo_log, String log, String created_at, Boolean enviado){
        this.id = id;
        this.tipo_log = tipo_log;
        this.log = log;
        this.created_at = created_at;
        this.enviado = enviado;
    }
    public Integer id;
    public String tipo_log;
    public String log;
    public String created_at;
    public Boolean enviado;

}
