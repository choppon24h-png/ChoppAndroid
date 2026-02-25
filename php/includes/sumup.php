<?php
/**
 * SumUp Integration - Cloud API
 * Integração com a SumUp Cloud API para pagamentos via leitora Solo
 *
 * Suporta: Débito, Crédito e PIX
 *
 * Referência: https://developer.sumup.com/terminal-payments/cloud-api
 * Affiliate Keys: https://developer.sumup.com/tools/authorization/affiliate-keys/
 *
 * Versão: 2.0.0
 * Compatível com: chopponERP + ChoppAndroid
 */

class SumUpIntegration
{
    private string $token;
    private string $merchantCode;
    private string $affiliateKey;
    private string $affiliateAppId;
    private string $baseUrl = 'https://api.sumup.com';

    public function __construct()
    {
        $conn = getDBConnection();

        // Buscar configurações do banco de dados
        $stmt = $conn->query("SELECT * FROM payment LIMIT 1");
        $cfg  = $stmt->fetch(PDO::FETCH_ASSOC);

        $this->token          = $cfg['token_sumup']      ?? '';
        $this->merchantCode   = $cfg['merchant_code']    ?? (defined('SUMUP_MERCHANT_CODE') ? SUMUP_MERCHANT_CODE : '');
        $this->affiliateKey   = $cfg['affiliate_key']    ?? '';
        $this->affiliateAppId = $cfg['affiliate_app_id'] ?? '';

        // Fallback para constantes do config.php
        if (empty($this->token) && defined('SUMUP_TOKEN')) {
            $this->token = SUMUP_TOKEN;
        }
        if (empty($this->merchantCode) && defined('SUMUP_MERCHANT_CODE')) {
            $this->merchantCode = SUMUP_MERCHANT_CODE;
        }
        if (empty($this->affiliateKey) && defined('SUMUP_AFFILIATE_KEY')) {
            $this->affiliateKey = SUMUP_AFFILIATE_KEY;
        }
        if (empty($this->affiliateAppId) && defined('SUMUP_AFFILIATE_APP_ID')) {
            $this->affiliateAppId = SUMUP_AFFILIATE_APP_ID;
        }

        Logger::debug('SumUpIntegration inicializada', [
            'merchant_code'     => $this->merchantCode,
            'has_token'         => !empty($this->token),
            'has_affiliate_key' => !empty($this->affiliateKey),
            'has_app_id'        => !empty($this->affiliateAppId),
        ]);
    }

    // =========================================================
    // CHECKOUT VIA LEITORA (Cloud API - Débito / Crédito)
    // =========================================================

    /**
     * Cria um checkout para pagamento via leitora SumUp Solo
     *
     * @param array  $order       Dados do pedido: id, valor, descricao
     * @param string $reader_id   ID da leitora (ex: rdr_XXXX)
     * @param string $card_type   Tipo de cartão: 'debit' ou 'credit'
     * @return array|false        Array com checkout_id e response, ou false em caso de erro
     */
    public function createCheckoutCard(array $order, string $reader_id, string $card_type = 'debit')
    {
        if (empty($this->token)) {
            Logger::error('SumUp: token não configurado');
            return false;
        }
        if (empty($this->merchantCode)) {
            Logger::error('SumUp: merchant_code não configurado');
            return false;
        }
        if (empty($reader_id)) {
            Logger::error('SumUp: reader_id vazio');
            return false;
        }
        if (empty($this->affiliateKey)) {
            Logger::error('SumUp: affiliate_key não configurada');
            return false;
        }
        if (empty($this->affiliateAppId)) {
            Logger::error('SumUp: affiliate_app_id não configurado');
            return false;
        }

        // Normalizar tipo de cartão
        $card_type = strtolower(trim($card_type));
        if (!in_array($card_type, ['debit', 'credit'])) {
            $card_type = 'debit';
        }

        // Montar corpo da requisição conforme documentação SumUp Cloud API
        $body = [
            'total_price'            => (float) $order['valor'],
            'currency'               => 'BRL',
            'pay_to_email'           => '',           // Opcional — preenchido automaticamente pela SumUp
            'description'            => $order['descricao'] ?? 'Pagamento ChoppOn',
            'foreign_transaction_id' => 'CHOPPON-' . $order['id'] . '-' . time(),
            'affiliate'              => [
                'key'    => $this->affiliateKey,
                'app_id' => $this->affiliateAppId,
            ],
            'card_type'              => $card_type,  // 'debit' ou 'credit'
        ];

        $url = "{$this->baseUrl}/v0.1/merchants/{$this->merchantCode}/readers/{$reader_id}/checkout";

        Logger::info('SumUp createCheckoutCard - enviando', [
            'url'       => $url,
            'reader_id' => $reader_id,
            'card_type' => $card_type,
            'valor'     => $order['valor'],
            'order_id'  => $order['id'],
        ]);

        $response = $this->httpPost($url, $body);

        Logger::info('SumUp createCheckoutCard - resposta', [
            'http_code'  => $response['http_code'],
            'body_short' => substr($response['body'], 0, 300),
        ]);

        if ($response['http_code'] === 200 || $response['http_code'] === 201) {
            $data = json_decode($response['body'], true);
            $checkout_id = $data['id'] ?? $data['checkout_id'] ?? null;

            if ($checkout_id) {
                return [
                    'checkout_id' => $checkout_id,
                    'response'    => $response['body'],
                    'card_type'   => $card_type,
                    'reader_id'   => $reader_id,
                ];
            }
        }

        // Tratar erros conhecidos
        $error_data = json_decode($response['body'], true);
        $error_msg  = $error_data['message'] ?? $error_data['error_message'] ?? $response['body'];

        Logger::error('SumUp createCheckoutCard - falhou', [
            'http_code' => $response['http_code'],
            'error'     => $error_msg,
            'reader_id' => $reader_id,
            'card_type' => $card_type,
        ]);

        return false;
    }

    // =========================================================
    // CHECKOUT PIX (API REST SumUp)
    // =========================================================

    /**
     * Cria um checkout PIX via SumUp
     *
     * @param array $order  Dados do pedido: id, valor, descricao
     * @return array|false  Array com checkout_id, pix_code e response, ou false
     */
    public function createCheckoutPix(array $order)
    {
        if (empty($this->token)) {
            Logger::error('SumUp PIX: token não configurado');
            return false;
        }

        $body = [
            'checkout_reference' => 'CHOPPON-PIX-' . $order['id'] . '-' . time(),
            'amount'             => (float) $order['valor'],
            'currency'           => 'BRL',
            'description'        => $order['descricao'] ?? 'Pagamento ChoppOn PIX',
            'pay_to_email'       => '',
        ];

        $url = "{$this->baseUrl}/v0.1/checkouts";

        Logger::info('SumUp createCheckoutPix - enviando', [
            'url'      => $url,
            'valor'    => $order['valor'],
            'order_id' => $order['id'],
        ]);

        $response = $this->httpPost($url, $body);

        Logger::info('SumUp createCheckoutPix - resposta', [
            'http_code'  => $response['http_code'],
            'body_short' => substr($response['body'], 0, 300),
        ]);

        if ($response['http_code'] === 200 || $response['http_code'] === 201) {
            $data        = json_decode($response['body'], true);
            $checkout_id = $data['id'] ?? null;
            $pix_code    = $data['transaction_code'] ?? $data['pix_code'] ?? null;

            if ($checkout_id) {
                return [
                    'checkout_id' => $checkout_id,
                    'pix_code'    => $pix_code ?? $checkout_id,
                    'response'    => $response['body'],
                ];
            }
        }

        Logger::error('SumUp createCheckoutPix - falhou', [
            'http_code' => $response['http_code'],
            'body'      => substr($response['body'], 0, 300),
        ]);

        return false;
    }

    // =========================================================
    // STATUS DA LEITORA
    // =========================================================

    /**
     * Consulta o status de uma leitora SumUp Solo via Cloud API
     *
     * @param string $reader_id  ID da leitora (ex: rdr_XXXX)
     * @return array             Status da leitora
     */
    public function getReaderStatus(string $reader_id): array
    {
        if (empty($this->token) || empty($this->merchantCode) || empty($reader_id)) {
            return [
                'status'  => 'UNKNOWN',
                'state'   => null,
                'battery' => null,
                'wifi'    => null,
                'error'   => 'Configuração incompleta',
            ];
        }

        $url      = "{$this->baseUrl}/v0.1/merchants/{$this->merchantCode}/readers/{$reader_id}";
        $response = $this->httpGet($url);

        if ($response['http_code'] === 200) {
            $data = json_decode($response['body'], true);

            // Extrair status do campo correto (status.data.status ou status.data.state)
            $statusData = $data['status']['data'] ?? [];
            $rawStatus  = strtoupper($statusData['status'] ?? 'UNKNOWN');
            $state      = strtoupper($statusData['state'] ?? '');
            $connType   = $statusData['connection_type'] ?? null;
            $battery    = $statusData['battery_level'] ?? null;
            $firmware   = $statusData['firmware_version'] ?? null;
            $lastAct    = $statusData['last_activity'] ?? null;

            // Lógica de prontidão: IDLE + conexão = pronto para transacionar
            $readyStatuses = ['ONLINE', 'CONNECTED', 'READY', 'READY_TO_TRANSACT'];
            $activeStates  = ['IDLE', 'READY', 'PROCESSING', 'CARD_INSERTED', 'CARD_TAPPED', 'PIN_ENTRY'];
            $hasNetwork    = !empty($connType);

            $isReady = in_array($rawStatus, $readyStatuses)
                    || ($hasNetwork && in_array($state, $activeStates));

            return [
                'status'        => $rawStatus,
                'state'         => $state,
                'is_ready'      => $isReady,
                'battery'       => $battery,
                'connection'    => $connType,
                'firmware'      => $firmware,
                'last_activity' => $lastAct,
                'reader_name'   => $data['name'] ?? null,
                'reader_serial' => $data['device']['identifier'] ?? null,
                'paired_status' => $data['status'] ?? null,
            ];
        }

        return [
            'status'   => 'UNKNOWN',
            'state'    => null,
            'is_ready' => false,
            'error'    => "HTTP {$response['http_code']}",
        ];
    }

    // =========================================================
    // VERIFICAR STATUS DO CHECKOUT (polling)
    // =========================================================

    /**
     * Verifica o status de um checkout SumUp
     *
     * @param string $checkout_id  ID do checkout
     * @return string              Status: SUCCESSFUL, PENDING, FAILED, CANCELLED
     */
    public function getCheckoutStatus(string $checkout_id): string
    {
        if (empty($this->token) || empty($checkout_id)) {
            return 'UNKNOWN';
        }

        $url      = "{$this->baseUrl}/v0.1/checkouts/{$checkout_id}";
        $response = $this->httpGet($url);

        if ($response['http_code'] === 200) {
            $data   = json_decode($response['body'], true);
            $status = strtoupper($data['status'] ?? 'PENDING');
            return $status;
        }

        return 'UNKNOWN';
    }

    // =========================================================
    // CANCELAR CHECKOUT
    // =========================================================

    /**
     * Cancela um checkout pendente
     *
     * @param string $checkout_id  ID do checkout
     * @return bool
     */
    public function cancelCheckout(string $checkout_id): bool
    {
        if (empty($this->token) || empty($checkout_id)) {
            return false;
        }

        $url      = "{$this->baseUrl}/v0.1/checkouts/{$checkout_id}";
        $response = $this->httpDelete($url);

        return in_array($response['http_code'], [200, 204]);
    }

    // =========================================================
    // VERIFICAR TOKEN / API ATIVA
    // =========================================================

    /**
     * Verifica se o token SumUp está válido
     *
     * @return bool
     */
    public function isApiActive(): bool
    {
        if (empty($this->token)) {
            return false;
        }

        $url      = "{$this->baseUrl}/v0.1/me";
        $response = $this->httpGet($url);

        return $response['http_code'] === 200;
    }

    /**
     * Retorna o merchant_code da conta autenticada
     *
     * @return string|null
     */
    public function getMerchantCode(): ?string
    {
        if (empty($this->token)) {
            return null;
        }

        $url      = "{$this->baseUrl}/v0.1/me";
        $response = $this->httpGet($url);

        if ($response['http_code'] === 200) {
            $data = json_decode($response['body'], true);
            return $data['merchant_profile']['merchant_code'] ?? null;
        }

        return null;
    }

    // =========================================================
    // HTTP HELPERS (cURL)
    // =========================================================

    private function httpPost(string $url, array $body): array
    {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => json_encode($body),
            CURLOPT_TIMEOUT        => 30,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $this->token,
                'Content-Type: application/json',
                'Accept: application/json',
            ],
            CURLOPT_SSL_VERIFYPEER => true,
        ]);

        $responseBody = curl_exec($ch);
        $httpCode     = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlError    = curl_error($ch);
        curl_close($ch);

        if ($curlError) {
            Logger::error('SumUp cURL POST error', ['url' => $url, 'error' => $curlError]);
        }

        return ['http_code' => $httpCode, 'body' => $responseBody ?: ''];
    }

    private function httpGet(string $url): array
    {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPGET        => true,
            CURLOPT_TIMEOUT        => 15,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $this->token,
                'Accept: application/json',
            ],
            CURLOPT_SSL_VERIFYPEER => true,
        ]);

        $responseBody = curl_exec($ch);
        $httpCode     = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlError    = curl_error($ch);
        curl_close($ch);

        if ($curlError) {
            Logger::error('SumUp cURL GET error', ['url' => $url, 'error' => $curlError]);
        }

        return ['http_code' => $httpCode, 'body' => $responseBody ?: ''];
    }

    private function httpDelete(string $url): array
    {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CUSTOMREQUEST  => 'DELETE',
            CURLOPT_TIMEOUT        => 15,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $this->token,
                'Accept: application/json',
            ],
            CURLOPT_SSL_VERIFYPEER => true,
        ]);

        $responseBody = curl_exec($ch);
        $httpCode     = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        return ['http_code' => $httpCode, 'body' => $responseBody ?: ''];
    }
}

// =========================================================
// FUNÇÃO AUXILIAR: Gerar QR Code em Base64
// =========================================================

if (!function_exists('generateQRCode')) {
    /**
     * Gera um QR Code em Base64 a partir de um texto/URL
     * Usa a API QR Server (sem dependência externa)
     *
     * @param string $text  Texto ou URL para o QR Code
     * @return string       Base64 da imagem PNG
     */
    function generateQRCode(string $text): string
    {
        $url = 'https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=' . urlencode($text);

        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 10,
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_SSL_VERIFYPEER => true,
        ]);

        $imageData = curl_exec($ch);
        curl_close($ch);

        if ($imageData && strlen($imageData) > 100) {
            return base64_encode($imageData);
        }

        Logger::warning('generateQRCode: falhou ao gerar QR Code', ['text_len' => strlen($text)]);
        return '';
    }
}
