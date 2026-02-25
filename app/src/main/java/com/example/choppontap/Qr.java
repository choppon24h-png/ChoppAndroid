package com.example.choppontap;

/**
 * Modelo de resposta do endpoint create_order.php
 *
 * PIX:
 *   { "checkout_id": "...", "qr_code": "<base64>" }
 *
 * Cartão (débito/crédito):
 *   {
 *     "checkout_id": "...",
 *     "card_type": "debit|credit",
 *     "reader_name": "TAP 01 ALMEIDA",
 *     "reader_serial": "200300102578",
 *     "reader_id": "rdr_XXXX"
 *   }
 */
public class Qr {
    /** Base64 da imagem do QR Code (apenas PIX) */
    public String qr_code;

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
