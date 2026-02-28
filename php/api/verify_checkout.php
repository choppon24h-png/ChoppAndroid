<?php
/**
 * API - Verificar Checkout (Versão Estabilizada)
 */

// Desativar saída de erros HTML para não quebrar o JSON do Android
ini_set('display_errors', 0);
header('Content-Type: application/json');

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    // Capturar Token
    $headers = getallheaders();
    $token = $headers['token'] ?? $headers['Token'] ?? '';

    // Se o token falhar, retornamos erro limpo
    if (!jwtValidate($token)) {
        http_response_code(401);
        echo json_encode(['status' => 'failed', 'error' => 'Sessao expirada']);
        exit;
    }

    $checkout_id = $_POST['checkout_id'] ?? '';
    if (empty($checkout_id)) {
        echo json_encode(['status' => 'false', 'error' => 'ID ausente']);
        exit;
    }

    $conn = getDBConnection();
    
    // ✅ PASSO 1: Verificação prioritária no Banco de Dados (alimentado pelo Webhook)
    // Isso é o mais rápido e evita timeouts de rede externa
    $stmt = $conn->prepare("SELECT checkout_status FROM `order` WHERE checkout_id = ? LIMIT 1");
    $stmt->execute([$checkout_id]);
    $order = $stmt->fetch();

    if ($order && $order['checkout_status'] === 'SUCCESSFUL') {
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
        exit;
    }

    // ✅ PASSO 2: Consulta em tempo real na SumUp (apenas se não estiver aprovado no banco)
    $sumup = new SumUpIntegration();
    
    // Usamos um bloco interno para que se a SumUp falhar, o script não morra
    try {
        $sumupStatus = $sumup->getCheckoutStatus($checkout_id);
    } catch (Exception $e) {
        $sumupStatus = 'PENDING'; // Fallback se a API externa falhar
    }

    if ($sumupStatus === 'SUCCESSFUL') {
        // Atualiza o banco preventivamente caso o webhook ainda não tenha chegado
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'SUCCESSFUL' WHERE checkout_id = ?");
        $stmt->execute([$checkout_id]);
        
        echo json_encode(['status' => 'success', 'checkout_status' => 'SUCCESSFUL']);
    } else {
        // Se ainda não aprovou, retorna pendente de forma limpa
        echo json_encode([
            'status' => 'pending', 
            'checkout_status' => $sumupStatus ?: 'PENDING'
        ]);
    }

} catch (Exception $e) {
    // ✅ GARANTIA: Sempre retorna um JSON válido
    echo json_encode([
        'status' => 'error',
        'error' => 'Falha técnica no processamento'
    ]);
}
