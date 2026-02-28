<?php
/**
 * API - Verificar Checkout (Versão de Diagnóstico Senior)
 */

// ✅ Ativar exibição de erros para capturar o motivo da resposta vazia
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

header('Content-Type: application/json');

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    // Fallback para getallheaders em servidores Nginx
    if (!function_exists('getallheaders')) {
        function getallheaders() {
            $headers = [];
            foreach ($_SERVER as $name => $value) {
                if (substr($name, 0, 5) == 'HTTP_') {
                    $headers[str_replace(' ', '-', ucwords(strtolower(str_replace('_', ' ', substr($name, 5)))))] = $value;
                }
            }
            return $headers;
        }
    }

    $headers = getallheaders();
    $token = $headers['token'] ?? $headers['Token'] ?? '';

    if (!jwtValidate($token)) {
        http_response_code(401);
        echo json_encode(['error' => 'Token expirado ou invalido', 'status' => 'failed']);
        exit;
    }

    $android_id = $_POST['android_id'] ?? '';
    $checkout_id = $_POST['checkout_id'] ?? '';

    if (empty($checkout_id)) {
        throw new Exception("checkout_id ausente na requisicao");
    }

    $conn = getDBConnection();
    
    // 1. Verificar status local no banco primeiro (Cache)
    $stmt = $conn->prepare("SELECT checkout_status FROM `order` WHERE checkout_id = ? LIMIT 1");
    $stmt->execute([$checkout_id]);
    $order = $stmt->fetch();

    if ($order && $order['checkout_status'] === 'SUCCESSFUL') {
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
        exit;
    }

    // 2. Consultar SumUp em tempo real
    $sumup = new SumUpIntegration();
    $sumupStatus = $sumup->getCheckoutStatus($checkout_id);

    // 3. Atualizar banco se aprovado
    if ($sumupStatus === 'SUCCESSFUL') {
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'SUCCESSFUL' WHERE checkout_id = ?");
        $stmt->execute([$checkout_id]);
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
    } else {
        echo json_encode([
            'status' => 'pending', 
            'checkout_status' => $sumupStatus,
            'debug_id' => $checkout_id
        ]);
    }

} catch (Exception $e) {
    // ✅ Captura o erro que antes causava a "Resposta Vazia"
    http_response_code(500);
    echo json_encode([
        'error' => $e->getMessage(),
        'file' => basename($e->getFile()),
        'line' => $e->getLine(),
        'status' => 'error'
    ]);
}
