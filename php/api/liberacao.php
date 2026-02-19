<?php
/**
 * API - Controle de Liberação
 * POST /api/liberacao.php?action=iniciada|finalizada
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

$action = $_GET['action'] ?? '';
$input = $_POST;
$checkout_id = $input['checkout_id'] ?? '';

if (empty($checkout_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'checkout_id é obrigatório']);
    exit;
}

$conn = getDBConnection();

if ($action === 'iniciada') {
    // Marcar liberação como iniciada
    $stmt = $conn->prepare("
        UPDATE `order` 
        SET status_liberacao = 'PROCESSING'
        WHERE checkout_id = ?
    ");
    $stmt->execute([$checkout_id]);
    
    http_response_code(200);
    echo json_encode(['success' => true]);
    
} elseif ($action === 'finalizada') {
    // Marcar liberação como finalizada
    $qtd_ml = $input['qtd_ml'] ?? 0;
    
    $stmt = $conn->prepare("SELECT * FROM `order` WHERE checkout_id = ? LIMIT 1");
    $stmt->execute([$checkout_id]);
    $order = $stmt->fetch();
    
    if ($order) {
        $qtd_liberada = $order['qtd_liberada'] + $qtd_ml;
        $status_liberacao = ($qtd_liberada >= $order['quantidade']) ? 'FINISHED' : 'PROCESSING';
        
        $stmt = $conn->prepare("
            UPDATE `order` 
            SET qtd_liberada = ?, status_liberacao = ?
            WHERE id = ?
        ");
        $stmt->execute([$qtd_liberada, $status_liberacao, $order['id']]);
        
        http_response_code(200);
        echo json_encode(['success' => true, 'status' => $status_liberacao]);
    } else {
        http_response_code(404);
        echo json_encode(['error' => 'Pedido não encontrado']);
    }
} else {
    http_response_code(400);
    echo json_encode(['error' => 'Ação inválida']);
}
