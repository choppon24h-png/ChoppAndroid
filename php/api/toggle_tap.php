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
 *
 * NOTA: ob_start() é a PRIMEIRA instrução executável do script.
 * Isso garante que qualquer saída acidental dos includes (whitespace,
 * warnings, notices) seja capturada e descartada antes do echo JSON.
 */

// ── Buffer de saída: captura TUDO desde o início ──────────────────────────────
ob_start();

// ── Includes (possíveis fontes de saída acidental) ────────────────────────────
require_once '../includes/config.php';
require_once '../includes/jwt.php';

// ── Descarta qualquer saída gerada pelos includes ─────────────────────────────
ob_clean();

// ── Headers ───────────────────────────────────────────────────────────────────
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

$TAG      = 'TOGGLE_TAP';
$response = ['success' => false, 'error' => 'Erro desconhecido'];
$httpCode = 500;

try {
    // ── Validação JWT ─────────────────────────────────────────────────────────
    $headers    = getallheaders();
    $token      = $headers['token'] ?? $headers['Token'] ?? '';
    $jwtPayload = jwtValidate($token);

    if (!$jwtPayload) {
        Logger::warning($TAG, "JWT inválido ou expirado");
        $response = ['success' => false, 'error' => 'Token inválido ou expirado'];
        $httpCode = 401;
        throw new RuntimeException('jwt_invalid');
    }

    // ── Parâmetros ────────────────────────────────────────────────────────────
    $android_id = trim($_POST['android_id'] ?? '');
    $action     = strtolower(trim($_POST['action'] ?? ''));

    Logger::debug($TAG, "Requisição recebida | android_id=$android_id | action=$action");

    if (empty($android_id) || !in_array($action, ['ativar', 'desativar'])) {
        $response = [
            'success' => false,
            'error'   => 'Parâmetros inválidos. Informe android_id e action (ativar|desativar).'
        ];
        $httpCode = 400;
        throw new RuntimeException('invalid_params');
    }

    // ── Banco de Dados ────────────────────────────────────────────────────────
    $conn = getDBConnection();

    // Busca a TAP pelo android_id
    $stmt = $conn->prepare(
        "SELECT id, nome, status FROM tap WHERE android_id = ? LIMIT 1"
    );
    $stmt->execute([$android_id]);
    $tap = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$tap) {
        Logger::warning($TAG, "TAP não encontrada para android_id=$android_id");
        $response = [
            'success' => false,
            'error'   => 'TAP não encontrada para este dispositivo.'
        ];
        $httpCode = 404;
        throw new RuntimeException('tap_not_found');
    }

    // Define o novo status
    $novoStatus  = ($action === 'desativar') ? 0 : 1;
    $statusLabel = ($novoStatus === 0) ? 'offline' : 'online';

    // Atualiza o status da TAP
    $stmtUpdate = $conn->prepare("UPDATE tap SET status = ? WHERE id = ?");
    $stmtUpdate->execute([$novoStatus, $tap['id']]);

    Logger::info($TAG,
        "TAP '{$tap['nome']}' (id={$tap['id']}) alterada para status=$statusLabel"
    );

    $response = [
        'success'  => true,
        'status'   => $statusLabel,
        'message'  => ($novoStatus === 0)
            ? 'TAP desativada com sucesso. Redirecionando para tela OFFLINE.'
            : 'TAP ativada com sucesso. Retornando ao funcionamento normal.',
        'tap_id'   => (int) $tap['id'],
        'tap_nome' => $tap['nome']
    ];
    $httpCode = 200;

} catch (RuntimeException $e) {
    // Erros controlados — $response e $httpCode já foram definidos acima
    Logger::debug($TAG, "Fluxo encerrado com erro controlado: " . $e->getMessage());
} catch (Throwable $e) {
    // Erros inesperados
    Logger::error($TAG, "Erro inesperado: " . $e->getMessage());
    $response = [
        'success' => false,
        'error'   => 'Erro interno ao processar a solicitação.'
    ];
    $httpCode = 500;
}

// ── ÚNICO PONTO DE SAÍDA ──────────────────────────────────────────────────────
// Descarta qualquer saída acumulada durante o processamento
ob_clean();
http_response_code($httpCode);
$json = json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
Logger::debug($TAG, "Resposta enviada (HTTP $httpCode): $json");
echo $json;
ob_end_flush();
