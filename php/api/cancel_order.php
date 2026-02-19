<?php
/**
 * API - Cancelar Pedido
 * POST /api/cancel_order.php
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';
require_once '../includes/sumup.php';

$headers = getallheaders();
$token = $headers['token'] ?? $headers['Token'] ?? '';

// Validar token
if (!jwtValidate($token)) {
    http_response_code(401);
    echo json_encode(['error' => 'Token inválido']);
    exit;
}

$input = $_POST;
$checkout_id = $input['checkout_id'] ?? '';

if (empty($checkout_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'checkout_id é obrigatório']);
    exit;
}

$conn = getDBConnection();

// Buscar pedido
$stmt = $conn->prepare("
    SELECT o.*, t.reader_id 
    FROM `order` o
    INNER JOIN tap t ON o.tap_id = t.id
    WHERE o.checkout_id = ?
    LIMIT 1
");
$stmt->execute([$checkout_id]);
$order = $stmt->fetch();

if (!$order) {
    http_response_code(404);
    echo json_encode(['error' => 'Pedido não encontrado']);
    exit;
}

$sumup = new SumUpIntegration();
$cancelled = false;

// Cancelar na SumUp
if ($order['method'] === 'pix') {
    $cancelled = $sumup->cancelPixTransaction($checkout_id);
} else {
    if (!empty($order['reader_id'])) {
        $cancelled = $sumup->cancelCardTransaction($order['reader_id']);
    }
}

// Atualizar status do pedido
$stmt = $conn->prepare("
    UPDATE `order` 
    SET checkout_status = 'CANCELLED', status_liberacao = 'CANCELLED'
    WHERE id = ?
");
$stmt->execute([$order['id']]);

http_response_code(200);
echo json_encode(['success' => true, 'cancelled_at_sumup' => $cancelled]);
