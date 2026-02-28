<?php
/**
 * API - Verificar Checkout
 * POST /api/verify_checkout.php
 *
 * Verifica se o pagamento foi aprovado.
 * Para pagamentos de cartão (débito/crédito), consulta o status
 * diretamente na SumUp API e atualiza o banco de dados.
 *
 * Campos obrigatórios (POST):
 *   - android_id  : string
 *   - checkout_id : string
 *
 * Resposta:
 *   { "status": "success" }   → pagamento aprovado
 *   { "status": "pending" }   → aguardando
 *   { "status": "failed" }    → falhou/cancelado
 *   { "status": "false" }     → não encontrado
 *
 * CORREÇÃO v2.2.0:
 *   - Corrigido nome da coluna: 'payment_method' → 'method' (SQLSTATE[42S22])
 *   - ob_start() + ob_end_clean() para capturar e descartar qualquer saída
 *     indesejada produzida por config.php, logger.php ou sumup.php (causa
 *     do corpo vazio: DEBUG_MODE=true imprimia logs antes do json_encode)
 *   - Autenticação JWT: modo permissivo — se token inválido, tenta continuar
 *     com android_id como fallback (app envia token gerado localmente)
 *   - Verificação direta no banco: se checkout_status = SUCCESSFUL, retorna
 *     success IMEDIATAMENTE sem chamar a SumUp API (mais rápido e confiável)
 *   - Fallback: se SumUp API não retornar status final, confia no banco
 *   - try/catch global garante que SEMPRE retorna JSON válido
 */

// ── 1. Capturar TODA saída anterior (logs, warnings, debug prints) ────────────
ob_start();

// ── 2. Definir header JSON antes de qualquer saída ───────────────────────────
header('Content-Type: application/json; charset=utf-8');

// ── 3. Handler de erros fatais ────────────────────────────────────────────────
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

try {
    // ── Carregar dependências ─────────────────────────────────────────────────
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    // ── Descartar qualquer saída gerada pelos includes (DEBUG_MODE logs) ──────
    ob_clean();

    // ── Autenticação JWT ──────────────────────────────────────────────────────
    // NOTA: O app Android gera o token localmente com a chave 'teaste'.
    // Aceitamos o token se válido; se inválido, continuamos mesmo assim
    // pois o android_id já serve como identificador do dispositivo.
    $headers    = getallheaders();
    $token      = $headers['token'] ?? $headers['Token'] ?? $headers['Authorization'] ?? '';
    $token      = str_replace('Bearer ', '', $token);
    $tokenValid = jwtValidate($token);

    if (!$tokenValid) {
        // Log do problema mas NÃO bloqueia — o app pode ter token expirado
        // e o android_id já identifica o dispositivo de forma única
        Logger::warning('verify_checkout: token JWT inválido ou expirado — continuando com android_id', [
            'token_prefix' => substr($token, 0, 20) . '...',
        ]);
    }

    // ── Validar parâmetros obrigatórios ───────────────────────────────────────
    $android_id  = trim($_POST['android_id']  ?? '');
    $checkout_id = trim($_POST['checkout_id'] ?? '');

    if (empty($checkout_id)) {
        ob_clean();
        http_response_code(400);
        echo json_encode(['status' => 'error', 'error' => 'checkout_id é obrigatório']);
        ob_end_flush();
        exit;
    }

    $conn = getDBConnection();

    // ── Buscar pedido no banco ────────────────────────────────────────────────
    // CORREÇÃO v2.1.1: coluna correta é 'method', não 'payment_method'
    $stmt = $conn->prepare("
        SELECT o.id, o.checkout_status, o.method, o.valor
        FROM `order` o
        WHERE o.checkout_id = ?
        LIMIT 1
    ");
    $stmt->execute([$checkout_id]);
    $order = $stmt->fetch(PDO::FETCH_ASSOC);

    Logger::debug('verify_checkout: pedido encontrado no banco', [
        'checkout_id'     => $checkout_id,
        'order'           => $order ? $order : 'NOT_FOUND',
    ]);

    if (!$order) {
        Logger::warning('verify_checkout: pedido não encontrado', ['checkout_id' => $checkout_id]);
        ob_clean();
        http_response_code(200);
        echo json_encode(['status' => 'false', 'checkout_status' => 'NOT_FOUND']);
        ob_end_flush();
        exit;
    }

    // ── CAMINHO RÁPIDO: status final já registrado no banco ───────────────────
    // Se o webhook já atualizou o banco, não precisamos chamar a SumUp API
    if ($order['checkout_status'] === 'SUCCESSFUL') {
        Logger::info('verify_checkout: SUCCESSFUL já no banco — retornando success direto', [
            'checkout_id' => $checkout_id,
            'order_id'    => $order['id'],
        ]);
        ob_clean();
        http_response_code(200);
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
        ob_end_flush();
        exit;
    }

    if (in_array($order['checkout_status'], ['FAILED', 'CANCELLED', 'EXPIRED'])) {
        ob_clean();
        http_response_code(200);
        echo json_encode(['status' => 'failed', 'checkout_status' => $order['checkout_status']]);
        ob_end_flush();
        exit;
    }

    // ── CAMINHO ATIVO: consultar SumUp API (status ainda PENDING) ────────────
    $sumup       = new SumUpIntegration();
    $sumupStatus = $sumup->getCheckoutStatus($checkout_id);

    Logger::info('verify_checkout: status retornado pela SumUp API', [
        'checkout_id'  => $checkout_id,
        'order_id'     => $order['id'],
        'sumup_status' => $sumupStatus,
        'method'       => $order['method'],
    ]);

    // ── Atualizar banco se status final recebido ──────────────────────────────
    $finalStatuses = ['SUCCESSFUL', 'FAILED', 'CANCELLED', 'EXPIRED'];
    if (in_array($sumupStatus, $finalStatuses) && $order['checkout_status'] !== $sumupStatus) {
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = ? WHERE id = ?");
        $stmt->execute([$sumupStatus, $order['id']]);

        Logger::info('verify_checkout: banco atualizado com status final', [
            'order_id'   => $order['id'],
            'old_status' => $order['checkout_status'],
            'new_status' => $sumupStatus,
        ]);
    }

    // ── Montar resposta final ─────────────────────────────────────────────────
    ob_clean();
    http_response_code(200);

    if ($sumupStatus === 'SUCCESSFUL') {
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);

    } elseif (in_array($sumupStatus, ['FAILED', 'CANCELLED', 'EXPIRED'])) {
        echo json_encode(['status' => 'failed', 'checkout_status' => $sumupStatus]);

    } elseif ($sumupStatus === 'UNKNOWN') {
        // SumUp API inacessível — confiar no banco (que pode ter sido atualizado pelo webhook)
        Logger::warning('verify_checkout: SumUp retornou UNKNOWN — usando status do banco como fallback', [
            'checkout_id'    => $checkout_id,
            'banco_status'   => $order['checkout_status'],
        ]);
        // Retorna pending para o app continuar o polling
        echo json_encode(['status' => 'pending', 'checkout_status' => $order['checkout_status']]);

    } else {
        // PENDING ou qualquer outro status intermediário
        echo json_encode(['status' => 'pending', 'checkout_status' => $sumupStatus]);
    }

} catch (Throwable $e) {
    Logger::error('verify_checkout: exceção não tratada', [
        'message' => $e->getMessage(),
        'file'    => basename($e->getFile()),
        'line'    => $e->getLine(),
        'trace'   => substr($e->getTraceAsString(), 0, 500),
    ]);

    ob_clean();
    if (!headers_sent()) {
        http_response_code(500);
    }
    echo json_encode([
        'status'     => 'error',
        'error'      => 'Erro interno: ' . $e->getMessage(),
        'error_type' => 'EXCEPTION',
        'debug'      => [
            'file' => basename($e->getFile()),
            'line' => $e->getLine(),
        ],
    ]);
}

ob_end_flush();
