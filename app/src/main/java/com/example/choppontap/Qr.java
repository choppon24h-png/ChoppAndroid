package com.example.choppontap;

/**
 * Modelo de resposta do endpoint create_order.php
 *
 * PIX:
 *   {
 *     "success": true,
 *     "checkout_id": "...",
 *     "qr_code": "<base64>",
 *     "pix_code": "000201265800..."
 *   }
 *
 * Cartão (débito/crédito):
 *   {
 *     "checkout_id": "...",
 *     "card_type": "debit|credit",
 *     "reader_name": "TAP 01 ALMEIDA",
 *     "reader_serial": "200300102578",
 *     "reader_id": "rdr_XXXX"
 *   }
 *
 * ALTERAÇÃO v3.1.0:
 *   Adicionado campo 'pix_code' para receber o código EMV "copia e cola"
 *   retornado pela API v3.0.0 (chopponERP). Sem este campo, o Gson descartava
 *   silenciosamente o valor retornado pelo servidor.
 */
public class Qr {
    /** Base64 da imagem do QR Code (apenas PIX) */
    public String qr_code;

    /**
     * Código EMV "copia e cola" do PIX (apenas PIX).
     * Formato: string começando com "000201..." (padrão EMV Co).
     * Retornado pela API v3.0.0+ em create_order.php.
     */
    public String pix_code;

    /** ID do checkout SumUp */
    public String checkout_id;

    /** Tipo de cartão: "debit" ou "credit" (apenas cartão) */
    public String card_type;

    /** Nome da leitora SumUp vinculada (apenas cartão) */
    public String reader_name;

    /** Serial/identificador físico da leitora (apenas cartão) */
    public String reader_serial;

    /** ID lógico da leitora na SumUp (apenas cartão) */
    public String reader_id;
}
