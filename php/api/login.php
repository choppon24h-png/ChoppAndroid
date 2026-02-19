<?php
/**
 * API - Login
 * POST /api/login.php
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';

$input = $_POST;

$email = $input['email'] ?? '';
$password = $input['password'] ?? '';

if (empty($email) || empty($password)) {
    http_response_code(400);
    echo json_encode(['error' => 'Email e senha são obrigatórios']);
    exit;
}

$conn = getDBConnection();
$stmt = $conn->prepare("SELECT * FROM users WHERE email = ? LIMIT 1");
$stmt->execute([$email]);
$user = $stmt->fetch();

if ($user && password_verify($password, $user['password'])) {
    // Buscar estabelecimentos do usuário
    $stmt = $conn->prepare("
        SELECT estabelecimento_id 
        FROM user_estabelecimento 
        WHERE user_id = ? AND status = 1
    ");
    $stmt->execute([$user['id']]);
    $estabelecimentos = $stmt->fetchAll(PDO::FETCH_COLUMN);
    
    $token = jwtEncode([
        'user' => [
            'id' => $user['id'],
            'name' => $user['name'],
            'email' => $user['email'],
            'type' => $user['type'],
            'estabelecimentos' => $estabelecimentos
        ]
    ]);
    
    http_response_code(200);
    echo json_encode([
        'token' => $token,
        'user' => [
            'id' => $user['id'],
            'name' => $user['name'],
            'email' => $user['email'],
            'type' => $user['type']
        ],
        'isAdmin' => $user['type'] == 1
    ]);
} else {
    http_response_code(401);
    echo json_encode(['user' => false, 'error' => 'Credenciais inválidas']);
}
