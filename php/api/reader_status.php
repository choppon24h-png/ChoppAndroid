<?php
/**
 * API - Status da Leitora de Cartão SumUp
 * POST /api/reader_status.php
 *
 * Retorna o status da leitora SumUp Solo vinculada à TAP (android_id).
 * Usado pelo app Android (ServiceTools.java) para exibir:
 *   - Nome e serial da leitora
 *   - Status online/offline
 *   - API SumUp ativa/inativa
 *   - Bateria, tipo de conexão, firmware, última atividade
 *
 * Campos POST:
 *   - android_id : string (obrigatório)
 *
 * Resposta:
 * {
 *   "leitora_nome":     "TAP 01 ALMEIDA",
 *   "reader_id":        "rdr_XXXX",
 *   "serial":           "200300102578",
 *   "status_leitora":   "online|offline|sem_leitora|nao_encontrada",
 *   "api_ativa":        true|false,
 *   "bateria":          "99.6%",
 *   "conexao":          "Wi-Fi",
 *   "firmware":         "3.3.39.2",
 *   "ultima_atividade": "2026-02-25T00:25:13Z",
 *   "mensagem":         "..."
 * }
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

// ── Parâmetros ────────────────────────────────────────────────
$android_id = trim($_POST['android_id'] ?? '');

if (empty($android_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'android_id é obrigatório']);
    exit;
}

Logger::info('reader_status chamado', ['android_id' => $android_id]);

$conn = getDBConnection();

// ── Buscar TAP e reader_id vinculado ──────────────────────────
$stmt = $conn->prepare("
    SELECT t.id, t.reader_id, t.name AS tap_name
    FROM tap t
    WHERE t.android_id = ?
    LIMIT 1
");
$stmt->execute([$android_id]);
$tap = $stmt->fetch(PDO::FETCH_ASSOC);

// ── TAP não encontrada ────────────────────────────────────────
if (!$tap) {
    Logger::warning('reader_status: TAP não encontrada', ['android_id' => $android_id]);
    http_response_code(200);
    echo json_encode([
        'leitora_nome'     => 'Não configurada',
        'reader_id'        => null,
        'serial'           => null,
        'status_leitora'   => 'sem_leitora',
        'api_ativa'        => false,
        'bateria'          => null,
        'conexao'          => null,
        'firmware'         => null,
        'ultima_atividade' => null,
        'mensagem'         => 'TAP não encontrada para este dispositivo.',
    ]);
    exit;
}

$reader_id = $tap['reader_id'] ?? '';

// ── TAP sem leitora configurada ───────────────────────────────
if (empty($reader_id)) {
    Logger::info('reader_status: TAP sem reader_id', ['tap_id' => $tap['id']]);
    http_response_code(200);
    echo json_encode([
        'leitora_nome'     => 'Não configurada',
        'reader_id'        => null,
        'serial'           => null,
        'status_leitora'   => 'sem_leitora',
        'api_ativa'        => false,
        'bateria'          => null,
        'conexao'          => null,
        'firmware'         => null,
        'ultima_atividade' => null,
        'mensagem'         => 'Nenhuma leitora de cartão vinculada a esta TAP. Configure o pairing_code no painel administrativo.',
    ]);
    exit;
}

// ── Consultar SumUp Cloud API ─────────────────────────────────
$sumup = new SumUpIntegration();

// Verificar se a API está ativa (token válido)
$apiAtiva = $sumup->isApiActive();

// Buscar status completo da leitora
$rs = $sumup->getReaderStatus($reader_id);

// ── Determinar status simplificado ───────────────────────────
// is_ready = true quando: status ONLINE/CONNECTED/READY ou state=IDLE+conexão ativa
$statusLeitora = 'offline';
if (!empty($rs['error']) && strpos($rs['error'], '404') !== false) {
    $statusLeitora = 'nao_encontrada';
} elseif (!empty($rs['is_ready'])) {
    $statusLeitora = 'online';
}

// ── Formatar bateria ──────────────────────────────────────────
$bateria = null;
if ($rs['battery'] !== null && $rs['battery'] !== '') {
    $bateria = number_format((float) $rs['battery'], 1) . '%';
}

// ── Montar mensagem de diagnóstico ────────────────────────────
$mensagem = '';
if ($statusLeitora === 'online') {
    $conn_type = $rs['connection'] ?? 'Wi-Fi';
    $mensagem  = "Leitora pronta para transacionar via {$conn_type}.";
} elseif ($statusLeitora === 'offline') {
    $state    = $rs['state'] ?? '';
    $connType = $rs['connection'] ?? '';
    if (!empty($connType) && !empty($state)) {
        $mensagem = "Dispositivo conectado via {$connType} (state: {$state}). "
                  . "Se o display mostrar 'Pronto', o pagamento funcionará normalmente.";
    } else {
        $mensagem = "Leitora OFFLINE. Verifique se o SumUp Solo está ligado e conectado à internet.";
    }
} elseif ($statusLeitora === 'nao_encontrada') {
    $mensagem = "Leitora não encontrada na conta SumUp. Verifique se o reader_id está correto.";
}

// ── Nome da leitora: usar o retornado pela SumUp ou o nome da TAP ─
$leitoraNome = $rs['reader_name'] ?? $tap['tap_name'] ?? 'Leitora';

Logger::info('reader_status: resposta enviada', [
    'tap_id'         => $tap['id'],
    'reader_id'      => $reader_id,
    'status_leitora' => $statusLeitora,
    'api_ativa'      => $apiAtiva,
    'raw_status'     => $rs['status'] ?? 'N/A',
    'state'          => $rs['state'] ?? 'N/A',
    'connection'     => $rs['connection'] ?? 'N/A',
    'battery'        => $bateria,
]);

http_response_code(200);
echo json_encode([
    'leitora_nome'     => $leitoraNome,
    'reader_id'        => $reader_id,
    'serial'           => $rs['reader_serial'] ?? null,
    'status_leitora'   => $statusLeitora,
    'api_ativa'        => $apiAtiva,
    'bateria'          => $bateria,
    'conexao'          => $rs['connection'] ?? null,
    'firmware'         => $rs['firmware'] ?? null,
    'ultima_atividade' => $rs['last_activity'] ?? null,
    'mensagem'         => $mensagem,
]);
