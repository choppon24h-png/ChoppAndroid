<?php
/**
 * API - Verificar Checkout
 * POST /api/verify_checkout.php
 * Verifica se o pagamento foi aprovado
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';

$headers = getallheaders();
$token = $headers['token'] ?? $headers['Token'] ?? '';

// Validar token
if (!jwtValidate($token)) {
    http_response_code(401);
    echo json_encode(['error' => 'Token inválido']);
    exit;
}

$input = $_POST;

$android_id = $input['android_id'] ?? '';
$checkout_id = $input['checkout_id'] ?? '';

if (empty($android_id) || empty($checkout_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'android_id e checkout_id são obrigatórios']);
    exit;
}

$conn = getDBConnection();
$stmt = $conn->prepare("
    SELECT * FROM `order` 
    WHERE checkout_id = ? AND checkout_status = 'SUCCESSFUL'
    LIMIT 1
");
$stmt->execute([$checkout_id]);
$order = $stmt->fetch();

if ($order) {
    http_response_code(200);
    echo json_encode(['status' => 'success']);
} else {
    http_response_code(200);
    echo json_encode(['status' => 'false']);
}
