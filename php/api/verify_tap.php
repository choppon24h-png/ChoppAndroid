<?php
/**
 * API - Verificar TAP
 * POST /api/verify_tap.php
 * Retorna informações da bebida e TAP para o app Android
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

if (empty($android_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'android_id é obrigatório']);
    exit;
}

$conn = getDBConnection();
$stmt = $conn->prepare("
    SELECT t.*, b.name as bebida_name, b.value, b.image,
           (t.volume - t.volume_consumido) as volume_atual
    FROM tap t
    INNER JOIN bebidas b ON t.bebida_id = b.id
    WHERE t.android_id = ?
    LIMIT 1
");
$stmt->execute([$android_id]);
$tap = $stmt->fetch();

if ($tap) {
    $image_url = SITE_URL . '/' . $tap['image'];
    $stmt = $conn->prepare("
    UPDATE tap SET last_call = now() WHERE id = ?
    ");
    $stmt->execute([$tap['id']]);
    
    http_response_code(200);
    echo json_encode([
        'image' => $image_url,
        'preco' => $tap['value'],
        'bebida' => $tap['bebida_name'],
        'volume' => $tap['volume_atual'],
        'cartao' => !empty($tap['reader_id'])
    ]);
} else {
    http_response_code(404);
    echo json_encode(['error' => 'TAP não encontrada']);
}
