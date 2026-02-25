<?php
/**
 * API - Status da Leitora de Cartão SumUp
 * POST /api/reader_status.php
 *
 * Retorna o status da leitora SumUp Solo vinculada à TAP (android_id).
 * Usado pelo app Android (ServiceTools.java) para exibir informações
 * de diagnóstico da leitora no painel de ferramentas.
 *
 * Resposta:
 * {
 *   "leitora_nome": "TAP 01 ALMEIDA",
 *   "reader_id": "rdr_XXXX",
 *   "serial": "200300102578",
 *   "status_leitora": "online|offline|sem_leitora",
 *   "api_ativa": true|false,
 *   "bateria": "99.58",
 *   "conexao": "Wi-Fi",
 *   "firmware": "3.3.39.2",
 *   "ultima_atividade": "2026-02-25T00:25:13Z",
 *   "mensagem": "..."
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
$input      = $_POST;
$android_id = trim($input['android_id'] ?? '');

if (empty($android_id)) {
    http_response_code(400);
    echo json_encode(['error' => 'android_id é obrigatório']);
    exit;
}

Logger::info('reader_status.php chamado', ['android_id' => $android_id]);

$conn = getDBConnection();

// ── Buscar TAP e reader_id vinculado ──────────────────────────
$stmt = $conn->prepare("
    SELECT t.id, t.reader_id, t.name as tap_name, t.estabelecimento_id
    FROM tap t
    WHERE t.android_id = ?
    LIMIT 1
");
$stmt->execute([$android_id]);
$tap = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$tap) {
    Logger::warning('reader_status.php: TAP não encontrada', ['android_id' => $android_id]);
    http_response_code(200);
    echo json_encode([
        'leitora_nome'    => 'Não configurada',
        'reader_id'       => null,
        'serial'          => null,
        'status_leitora'  => 'sem_leitora',
        'api_ativa'       => false,
        'bateria'         => null,
        'conexao'         => null,
        'firmware'        => null,
        'ultima_atividade'=> null,
        'mensagem'        => 'TAP não encontrada para este dispositivo.',
    ]);
    exit;
}

$reader_id = $tap['reader_id'] ?? '';

// ── Sem leitora configurada ───────────────────────────────────
if (empty($reader_id)) {
    Logger::info('reader_status.php: TAP sem reader_id', ['tap_id' => $tap['id']]);
    http_response_code(200);
    echo json_encode([
        'leitora_nome'    => 'Não configurada',
        'reader_id'       => null,
        'serial'          => null,
        'status_leitora'  => 'sem_leitora',
        'api_ativa'       => false,
        'bateria'         => null,
        'conexao'         => null,
        'firmware'        => null,
        'ultima_atividade'=> null,
        'mensagem'        => 'Nenhuma leitora de cartão vinculada a esta TAP. Configure o pairing_code no painel administrativo.',
    ]);
    exit;
}

// ── Consultar SumUp Cloud API ─────────────────────────────────
$sumup = new SumUpIntegration();

// Verificar se a API está ativa
$apiAtiva = $sumup->isApiActive();

// Buscar status da leitora
$readerStatus = $sumup->getReaderStatus($reader_id);

// Determinar status simplificado para o app
$statusLeitora = 'offline';
if (!empty($readerStatus['is_ready'])) {
    $statusLeitora = 'online';
} elseif (isset($readerStatus['error'])) {
    $statusLeitora = 'nao_encontrada';
}

// Formatar bateria
$bateria = null;
if ($readerStatus['battery'] !== null) {
    $bateria = number_format((float) $readerStatus['battery'], 1) . '%';
}

// Mensagem de diagnóstico
$mensagem = '';
if ($statusLeitora === 'offline') {
    $rawStatus = $readerStatus['status'] ?? 'UNKNOWN';
    $state     = $readerStatus['state'] ?? '';
    $conn_type = $readerStatus['connection'] ?? '';

    if (!empty($conn_type) && !empty($state)) {
        // Dispositivo conectado mas status OFFLINE na sessão Cloud API
        $mensagem = "Leitora conectada via {$conn_type} (state: {$state}). "
                  . "Se o display mostrar 'Pronto', o pagamento deve funcionar normalmente. "
                  . "Para ativar a sessão Cloud API: Menu → Connections → API → Connect.";
        // Considerar como online se state=IDLE e tem rede
        if (in_array($state, ['IDLE', 'READY', 'PROCESSING'])) {
            $statusLeitora = 'online';
        }
    } else {
        $mensagem = "Leitora OFFLINE. Verifique se o SumUp Solo está ligado e conectado à internet.";
    }
} elseif ($statusLeitora === 'nao_encontrada') {
    $mensagem = "Leitora não encontrada na conta SumUp. Verifique se o reader_id está correto.";
} elseif ($statusLeitora === 'online') {
    $conn_type = $readerStatus['connection'] ?? 'Wi-Fi';
    $mensagem  = "Leitora pronta para transacionar via {$conn_type}.";
}

// Buscar nome da leitora no banco (tabela readers se existir, ou usar o nome da TAP)
$leitoraNome = $tap['tap_name'] ?? 'Leitora';
try {
    $stmtReader = $conn->prepare("SELECT name FROM readers WHERE reader_id = ? LIMIT 1");
    $stmtReader->execute([$reader_id]);
    $readerRow = $stmtReader->fetch(PDO::FETCH_ASSOC);
    if ($readerRow && !empty($readerRow['name'])) {
        $leitoraNome = $readerRow['name'];
    }
} catch (Exception $e) {
    // Tabela readers pode não existir — usar nome da TAP
}

Logger::info('reader_status.php: resposta', [
    'tap_id'         => $tap['id'],
    'reader_id'      => $reader_id,
    'status_leitora' => $statusLeitora,
    'api_ativa'      => $apiAtiva,
    'raw_status'     => $readerStatus['status'] ?? 'N/A',
    'state'          => $readerStatus['state'] ?? 'N/A',
    'connection'     => $readerStatus['connection'] ?? 'N/A',
]);

http_response_code(200);
echo json_encode([
    'leitora_nome'    => $leitoraNome,
    'reader_id'       => $reader_id,
    'serial'          => $readerStatus['reader_serial'] ?? null,
    'status_leitora'  => $statusLeitora,
    'api_ativa'       => $apiAtiva,
    'bateria'         => $bateria,
    'conexao'         => $readerStatus['connection'] ?? null,
    'firmware'        => $readerStatus['firmware'] ?? null,
    'ultima_atividade'=> $readerStatus['last_activity'] ?? null,
    'mensagem'        => $mensagem,
]);
