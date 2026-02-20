package com.example.choppontap;

public class CheckoutResponse {
    public String checkout_id;
    public String status;
    // Campo adicional retornado quando o pagamento ainda está pendente
    public String checkout_status;
    // Campo de debug retornado quando o checkout_id não está no banco
    public String debug;
}
