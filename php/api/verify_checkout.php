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
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';
require_once '../includes/sumup.php';

// ── Autenticação JWT ──────────────────────────────────────────
$headers = getallheaders();
$token   = $headers['token'] ?? $headers['Token'] ?? '';

if (!jwtValidate($token)) {
    http_response_code(401);
    echo json_encode(['error' => 'Token inválido']);
    exit;
}

$input       = $_POST;
$android_id  = trim($input['android_id']  ?? '');
$checkout_id = trim($input['checkout_id'] ?? '');

if (empty($android_id) || empty($checkout_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'android_id e checkout_id são obrigatórios']);
    exit;
}

$conn = getDBConnection();

// ── Buscar pedido no banco ────────────────────────────────────
$stmt = $conn->prepare("
    SELECT o.id, o.checkout_status, o.method, o.valor
    FROM `order` o
    INNER JOIN tap t ON t.id = o.tap_id
    WHERE o.checkout_id = ?
    LIMIT 1
");
$stmt->execute([$checkout_id]);
$order = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$order) {
    Logger::warning('verify_checkout: pedido não encontrado', ['checkout_id' => $checkout_id]);
    http_response_code(200);
    echo json_encode(['status' => 'false', 'checkout_status' => 'NOT_FOUND']);
    exit;
}

// ── Se já aprovado no banco, retornar imediatamente ───────────
if ($order['checkout_status'] === 'SUCCESSFUL') {
    Logger::info('verify_checkout: já aprovado no banco', [
        'checkout_id' => $checkout_id,
        'order_id'    => $order['id'],
    ]);
    http_response_code(200);
    echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
    exit;
}

// ── Se já falhou/cancelado, retornar imediatamente ────────────
if (in_array($order['checkout_status'], ['FAILED', 'CANCELLED'])) {
    http_response_code(200);
    echo json_encode(['status' => 'failed', 'checkout_status' => $order['checkout_status']]);
    exit;
}

// ── Consultar status na SumUp API (polling ativo) ─────────────
$sumup        = new SumUpIntegration();
$sumupStatus  = $sumup->getCheckoutStatus($checkout_id);

Logger::info('verify_checkout: status SumUp', [
    'checkout_id'  => $checkout_id,
    'order_id'     => $order['id'],
    'sumup_status' => $sumupStatus,
    'method'       => $order['method'],
]);

// ── Atualizar banco se status mudou ──────────────────────────
$finalStatuses = ['SUCCESSFUL', 'FAILED', 'CANCELLED', 'EXPIRED'];
if (in_array($sumupStatus, $finalStatuses) && $order['checkout_status'] !== $sumupStatus) {
    $stmt = $conn->prepare("UPDATE `order` SET checkout_status = ? WHERE id = ?");
    $stmt->execute([$sumupStatus, $order['id']]);

    Logger::info('verify_checkout: status atualizado no banco', [
        'order_id'   => $order['id'],
        'old_status' => $order['checkout_status'],
        'new_status' => $sumupStatus,
    ]);
}

// ── Retornar resposta ao app ──────────────────────────────────
if ($sumupStatus === 'SUCCESSFUL') {
    http_response_code(200);
    echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
} elseif (in_array($sumupStatus, ['FAILED', 'CANCELLED', 'EXPIRED'])) {
    http_response_code(200);
    echo json_encode(['status' => 'failed', 'checkout_status' => $sumupStatus]);
} else {
    // PENDING ou UNKNOWN — app continua fazendo polling
    http_response_code(200);
    echo json_encode(['status' => 'pending', 'checkout_status' => $sumupStatus]);
}
