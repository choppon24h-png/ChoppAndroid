<?php
/**
 * API - Cancelar Pedido
 * POST /api/cancel_order.php
 *
 * Cancela um checkout pendente na SumUp e marca o pedido como CANCELLED.
 *
 * Campos obrigatórios (POST):
 *   - android_id  : string
 *   - checkout_id : string
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
$checkout_id = trim($input['checkout_id'] ?? '');

if (empty($checkout_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'checkout_id é obrigatório']);
    exit;
}

$conn = getDBConnection();

// ── Buscar pedido ─────────────────────────────────────────────
$stmt = $conn->prepare("
    SELECT o.id, o.method, o.checkout_status, t.reader_id
    FROM `order` o
    INNER JOIN tap t ON o.tap_id = t.id
    WHERE o.checkout_id = ?
    LIMIT 1
");
$stmt->execute([$checkout_id]);
$order = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$order) {
    http_response_code(404);
    echo json_encode(['error' => 'Pedido não encontrado']);
    exit;
}

Logger::info('cancel_order: cancelando pedido', [
    'checkout_id' => $checkout_id,
    'order_id'    => $order['id'],
    'method'      => $order['method'],
]);

// ── Cancelar na SumUp ─────────────────────────────────────────
$sumup     = new SumUpIntegration();
$cancelled = false;

// Para todos os tipos de pagamento, cancelar o checkout pelo checkout_id
if (!in_array($order['checkout_status'], ['SUCCESSFUL', 'CANCELLED', 'FAILED'])) {
    $cancelled = $sumup->cancelCheckout($checkout_id);
    Logger::info('cancel_order: resultado cancelamento SumUp', [
        'checkout_id' => $checkout_id,
        'cancelled'   => $cancelled,
    ]);
}

// ── Atualizar banco ───────────────────────────────────────────
$stmt = $conn->prepare("
    UPDATE `order`
    SET checkout_status = 'CANCELLED'
    WHERE id = ?
");
$stmt->execute([$order['id']]);

http_response_code(200);
echo json_encode([
    'success'           => true,
    'cancelled_at_sumup'=> $cancelled,
    'order_id'          => $order['id'],
]);
