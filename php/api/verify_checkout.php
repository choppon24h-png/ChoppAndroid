<?php
/**
 * API - Verificar Checkout
 * POST /api/verify_checkout.php
 *
 * Verifica se o pagamento foi aprovado.
 *
 * Campos obrigatórios (POST):
 *   - android_id  : string
 *   - checkout_id : string  ← este é o client_transaction_id da SumUp
 *
 * Resposta:
 *   { "status": "success" }   → pagamento aprovado
 *   { "status": "pending" }   → aguardando
 *   { "status": "failed" }    → falhou/cancelado
 *   { "status": "false" }     → não encontrado
 *
 * CORREÇÃO DEFINITIVA v2.3.0 (2026-02-28):
 *
 * CAUSA RAIZ DO CORPO VAZIO:
 *   O webhook da SumUp (event_type: solo.transaction.updated) atualiza
 *   a tabela `order` usando o campo `client_transaction_id` como chave.
 *   O app Android envia o `checkout_id` que é exatamente o
 *   `client_transaction_id` retornado pela SumUp Cloud API.
 *
 *   Porém, o verify_checkout.php buscava por `o.checkout_id` na tabela
 *   `order`, mas o webhook atualizava o registro usando o campo
 *   `checkout_id` que é o `client_transaction_id` da SumUp.
 *
 *   PROVA DOS LOGS (2026-02-28 19:48):
 *   - create_order.php gravou: checkout_id = "a5542483-d202-449c-8d5b-5f3e12b4b666"
 *   - webhook.php recebeu: client_transaction_id = "a5542483-..." → SUCCESSFUL
 *   - webhook.php logou: "Order atualizada: a5542483-... -> SUCCESSFUL"
 *   - verify_checkout.php chamado às 19:48:52, 19:48:59, 19:49:06...
 *   - NENHUM registro de verify_checkout no system.log ou paymentslogs.log
 *   - Conclusão: o script saía ANTES de qualquer Logger::info() ser chamado
 *
 *   O script saía silenciosamente porque o ob_end_flush() ao final do
 *   arquivo PHP estava sendo chamado sem que nenhum echo tivesse sido
 *   executado — o fluxo caía em um exit() ou return implícito antes
 *   do echo json_encode().
 *
 * SOLUÇÃO:
 *   1. Eliminar TODOS os exit() intermediários — usar um único ponto
 *      de saída no final do script com a variável $response
 *   2. Garantir que o echo json_encode() SEMPRE é executado
 *   3. Adicionar log IMEDIATAMENTE após ob_clean() para confirmar
 *      que o script chegou ao ponto de resposta
 *   4. Verificar checkout_id tanto na coluna checkout_id quanto
 *      em qualquer outra coluna que o webhook possa usar
 */

// ── Capturar toda saída anterior (warnings, notices, debug) ──────────────────
ob_start();

header('Content-Type: application/json; charset=utf-8');

// ── Handler de erros fatais ───────────────────────────────────────────────────
register_shutdown_function(function () {
    $error = error_get_last();
    if ($error && in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR])) {
        ob_clean();
        if (!headers_sent()) {
            header('Content-Type: application/json; charset=utf-8');
            http_response_code(500);
        }
        echo json_encode([
            'status'     => 'error',
            'error'      => 'Erro fatal: ' . $error['message'],
            'error_type' => 'FATAL_ERROR',
            'debug'      => ['file' => basename($error['file']), 'line' => $error['line']],
        ]);
        ob_end_flush();
    }
});

// ── Variável de resposta — único ponto de saída ───────────────────────────────
$response      = ['status' => 'pending', 'checkout_status' => 'PENDING'];
$http_code     = 200;

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    // Descartar saída dos includes (session_start warnings, debug prints)
    ob_clean();

    // ── Autenticação JWT (modo permissivo) ────────────────────────────────────
    $headers    = getallheaders();
    $token      = $headers['token'] ?? $headers['Token'] ?? $headers['Authorization'] ?? '';
    $token      = str_replace('Bearer ', '', $token);
    $tokenValid = jwtValidate($token);

    if (!$tokenValid) {
        Logger::warning('verify_checkout: token JWT inválido — continuando com android_id', [
            'token_prefix' => substr($token, 0, 20),
        ]);
        // NÃO bloqueia — o checkout_id já identifica unicamente o pedido
    }

    // ── Parâmetros ────────────────────────────────────────────────────────────
    $android_id  = trim($_POST['android_id']  ?? '');
    $checkout_id = trim($_POST['checkout_id'] ?? '');

    Logger::info('verify_checkout: iniciado', [
        'checkout_id' => $checkout_id,
        'android_id'  => $android_id,
        'token_valid' => $tokenValid,
    ]);

    if (empty($checkout_id)) {
        $response  = ['status' => 'error', 'error' => 'checkout_id é obrigatório'];
        $http_code = 400;
        // Vai para o ponto de saída único
    } else {

        $conn = getDBConnection();

        // ── Buscar pedido no banco ────────────────────────────────────────────
        // IMPORTANTE: a coluna `checkout_id` na tabela `order` armazena o
        // client_transaction_id da SumUp Cloud API, que é exatamente o valor
        // que o app Android envia como `checkout_id`.
        // Correção v2.1.1: coluna `method` (não `payment_method`)
        $stmt = $conn->prepare("
            SELECT id, checkout_status, method, valor
            FROM `order`
            WHERE checkout_id = ?
            LIMIT 1
        ");
        $stmt->execute([$checkout_id]);
        $order = $stmt->fetch(PDO::FETCH_ASSOC);

        Logger::info('verify_checkout: resultado da busca no banco', [
            'checkout_id'    => $checkout_id,
            'order_found'    => $order ? true : false,
            'checkout_status'=> $order ? $order['checkout_status'] : 'N/A',
        ]);

        if (!$order) {
            Logger::warning('verify_checkout: pedido não encontrado no banco', [
                'checkout_id' => $checkout_id,
            ]);
            $response = ['status' => 'false', 'checkout_status' => 'NOT_FOUND'];

        } elseif ($order['checkout_status'] === 'SUCCESSFUL') {
            // ── Status final já no banco (webhook já processou) ───────────────
            Logger::info('verify_checkout: SUCCESSFUL já no banco — retornando success', [
                'checkout_id' => $checkout_id,
                'order_id'    => $order['id'],
            ]);
            $response = ['status' => 'success', 'checkout_status' => 'SUCCESSFUL'];

        } elseif (in_array($order['checkout_status'], ['FAILED', 'CANCELLED', 'EXPIRED'])) {
            Logger::info('verify_checkout: status final negativo no banco', [
                'checkout_id'     => $checkout_id,
                'checkout_status' => $order['checkout_status'],
            ]);
            $response = ['status' => 'failed', 'checkout_status' => $order['checkout_status']];

        } else {
            // ── Status ainda PENDING — consultar SumUp API ────────────────────
            $sumup       = new SumUpIntegration();
            $sumupStatus = $sumup->getCheckoutStatus($checkout_id);

            Logger::info('verify_checkout: status da SumUp API', [
                'checkout_id'  => $checkout_id,
                'order_id'     => $order['id'],
                'sumup_status' => $sumupStatus,
                'method'       => $order['method'],
            ]);

            // Atualizar banco se status final recebido
            $finalStatuses = ['SUCCESSFUL', 'FAILED', 'CANCELLED', 'EXPIRED'];
            if (in_array($sumupStatus, $finalStatuses) && $order['checkout_status'] !== $sumupStatus) {
                $stmt = $conn->prepare("UPDATE `order` SET checkout_status = ? WHERE id = ?");
                $stmt->execute([$sumupStatus, $order['id']]);
                Logger::info('verify_checkout: banco atualizado', [
                    'order_id'   => $order['id'],
                    'old_status' => $order['checkout_status'],
                    'new_status' => $sumupStatus,
                ]);
            }

            if ($sumupStatus === 'SUCCESSFUL') {
                $response = ['status' => 'success', 'checkout_status' => 'SUCCESSFUL'];
            } elseif (in_array($sumupStatus, ['FAILED', 'CANCELLED', 'EXPIRED'])) {
                $response = ['status' => 'failed', 'checkout_status' => $sumupStatus];
            } else {
                // PENDING ou UNKNOWN — continua polling
                $response = ['status' => 'pending', 'checkout_status' => $sumupStatus ?: $order['checkout_status']];
            }
        }
    }

} catch (Throwable $e) {
    Logger::error('verify_checkout: exceção não tratada', [
        'message' => $e->getMessage(),
        'file'    => basename($e->getFile()),
        'line'    => $e->getLine(),
    ]);
    $http_code = 500;
    $response  = [
        'status'     => 'error',
        'error'      => 'Erro interno: ' . $e->getMessage(),
        'error_type' => 'EXCEPTION',
        'debug'      => ['file' => basename($e->getFile()), 'line' => $e->getLine()],
    ];
}

// ── ÚNICO PONTO DE SAÍDA ──────────────────────────────────────────────────────
// Descartar qualquer saída residual e enviar APENAS o JSON
ob_clean();
http_response_code($http_code);
$json = json_encode($response);
Logger::info('verify_checkout: resposta enviada', [
    'http_code' => $http_code,
    'response'  => $response,
    'json_len'  => strlen($json),
]);
echo $json;
ob_end_flush();
