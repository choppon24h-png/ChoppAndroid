package com.example.choppontap;

/**
 * Modelo de resposta do endpoint verify_checkout.php
 *
 * Respostas possíveis:
 *   { "status": "success",  "checkout_status": "SUCCESSFUL" }
 *   { "status": "pending",  "checkout_status": "PENDING"    }
 *   { "status": "failed",   "checkout_status": "FAILED"     }
 *   { "status": "false",    "checkout_status": "NOT_FOUND"  }
 */
public class CheckoutResponse {
    /** Status simplificado: "success" | "pending" | "failed" | "false" */
    public String status;

    /**
     * Status detalhado da SumUp:
     * SUCCESSFUL | PENDING | FAILED | CANCELLED | EXPIRED | NOT_FOUND | UNKNOWN
     */
    public String checkout_status;

    /** ID do checkout (opcional, para debug) */
    public String checkout_id;

    /** Mensagem de debug (opcional) */
    public String debug;
}
