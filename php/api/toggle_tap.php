<?php
/**
 * API - Toggle TAP Status (Ativar / Desativar)
 * POST /api/toggle_tap.php
 *
 * Parâmetros:
 *   android_id  : ID único do dispositivo Android
 *   action      : "desativar" | "ativar"
 *
 * Resposta:
 *   { "success": true, "status": "offline"|"online", "message": "..." }
 */

ob_start();

header('Content-Type: application/json; charset=utf-8');
require_once '../includes/config.php';
require_once '../includes/jwt.php';

$TAG = 'TOGGLE_TAP';

// ── Validação JWT ─────────────────────────────────────────────────────────────
$headers    = getallheaders();
$token      = $headers['token'] ?? $headers['Token'] ?? '';
$jwtPayload = jwtValidate($token);

if (!$jwtPayload) {
    ob_clean();
    http_response_code(401);
    echo json_encode(['success' => false, 'error' => 'Token inválido ou expirado']);
    ob_end_flush();
    exit;
}

// ── Parâmetros ────────────────────────────────────────────────────────────────
$input      = $_POST;
$android_id = trim($input['android_id'] ?? '');
$action     = strtolower(trim($input['action'] ?? ''));

Logger::debug($TAG, "Requisição recebida | android_id=$android_id | action=$action");

if (empty($android_id) || !in_array($action, ['ativar', 'desativar'])) {
    ob_clean();
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'error'   => 'Parâmetros inválidos. Informe android_id e action (ativar|desativar).'
    ]);
    ob_end_flush();
    exit;
}

// ── Banco de Dados ────────────────────────────────────────────────────────────
try {
    $conn = getDBConnection();

    // Busca a TAP pelo android_id
    $stmt = $conn->prepare("SELECT id, nome, status FROM tap WHERE android_id = ? LIMIT 1");
    $stmt->execute([$android_id]);
    $tap = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$tap) {
        Logger::warning($TAG, "TAP não encontrada para android_id=$android_id");
        ob_clean();
        http_response_code(404);
        echo json_encode(['success' => false, 'error' => 'TAP não encontrada para este dispositivo.']);
        ob_end_flush();
        exit;
    }

    // Define o novo status
    $novoStatus = ($action === 'desativar') ? 0 : 1;
    $statusLabel = ($novoStatus === 0) ? 'offline' : 'online';

    // Atualiza o status da TAP
    $stmtUpdate = $conn->prepare("UPDATE tap SET status = ? WHERE id = ?");
    $stmtUpdate->execute([$novoStatus, $tap['id']]);

    Logger::info($TAG, "TAP '{$tap['nome']}' (id={$tap['id']}) alterada para status=$statusLabel");

    $response = [
        'success' => true,
        'status'  => $statusLabel,
        'message' => ($novoStatus === 0)
            ? 'TAP desativada com sucesso. Redirecionando para tela OFFLINE.'
            : 'TAP ativada com sucesso. Retornando ao funcionamento normal.',
        'tap_id'  => $tap['id'],
        'tap_nome'=> $tap['nome']
    ];

} catch (Throwable $e) {
    Logger::error($TAG, "Erro ao alterar status da TAP: " . $e->getMessage());
    $response = [
        'success' => false,
        'error'   => 'Erro interno ao processar a solicitação: ' . $e->getMessage()
    ];
    http_response_code(500);
}

ob_clean();
echo json_encode($response);
ob_end_flush();
