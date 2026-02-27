<?php
/**
 * API - Status da Leitora de Cartão SumUp
 * POST /api/reader_status.php
 *
 * Retorna o status da leitora SumUp Solo vinculada à TAP (android_id).
 * Usado pelo app Android (ServiceTools.java) para exibir:
 *   - Nome e serial da leitora
 *   - Status online/offline/idle
 *   - API SumUp ativa/inativa
 *   - Bateria, tipo de conexão, firmware, última atividade
 *
 * CORREÇÃO v2.2.0:
 *   - Proteção global try/catch: NUNCA retorna corpo vazio
 *   - SUMUP_AFFILIATE_KEY e SUMUP_AFFILIATE_APP_ID agora em config.php
 *   - Exibe bateria/conexão mesmo quando status=OFFLINE mas state=IDLE
 *   - status_leitora="idle" quando state=IDLE + conexão ativa
 *
 * Campos POST:
 *   - android_id : string (obrigatório)
 *
 * Resposta:
 * {
 *   "leitora_nome":     "TAP 01 ALMEIDA",
 *   "reader_id":        "rdr_XXXX",
 *   "serial":           "200300102578",
 *   "status_leitora":   "online|idle|offline|sem_leitora|nao_encontrada",
 *   "api_ativa":        true|false,
 *   "bateria":          "99.6%",
 *   "conexao":          "Wi-Fi",
 *   "firmware":         "3.3.39.2",
 *   "ultima_atividade": "2026-02-25T00:25:13Z",
 *   "mensagem":         "...",
 *   "debug":            { ... }
 * }
 */

// Proteção global: garantir Content-Type JSON antes de qualquer coisa
header('Content-Type: application/json');

// Handler de erros fatais: captura die(), fatal errors e retorna JSON
register_shutdown_function(function () {
    $error = error_get_last();
    if ($error && in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR])) {
        if (!headers_sent()) {
            header('Content-Type: application/json');
            http_response_code(500);
        }
        echo json_encode([
            'leitora_nome'   => 'Erro interno',
            'reader_id'      => null,
            'serial'         => null,
            'status_leitora' => 'erro',
            'api_ativa'      => false,
            'bateria'        => null,
            'conexao'        => null,
            'firmware'       => null,
            'ultima_atividade' => null,
            'mensagem'       => 'Erro interno no servidor: ' . $error['message'],
            'debug'          => ['fatal_error' => $error],
        ]);
    }
});

try {
    require_once '../includes/config.php';
    require_once '../includes/jwt.php';
    require_once '../includes/sumup.php';

    // ── Autenticação JWT ──────────────────────────────────────────
    $headers = getallheaders();
    $token   = $headers['token'] ?? $headers['Token'] ?? '';

    if (!jwtValidate($token)) {
        http_response_code(401);
        echo json_encode([
            'leitora_nome'   => 'Não autorizado',
            'reader_id'      => null,
            'serial'         => null,
            'status_leitora' => 'erro',
            'api_ativa'      => false,
            'bateria'        => null,
            'conexao'        => null,
            'firmware'       => null,
            'ultima_atividade' => null,
            'mensagem'       => 'Token JWT inválido ou expirado.',
        ]);
        exit;
    }

    // ── Parâmetros ────────────────────────────────────────────────
    $android_id = trim($_POST['android_id'] ?? '');

    if (empty($android_id)) {
        http_response_code(400);
        echo json_encode([
            'leitora_nome'   => 'Parâmetro ausente',
            'reader_id'      => null,
            'serial'         => null,
            'status_leitora' => 'erro',
            'api_ativa'      => false,
            'bateria'        => null,
            'conexao'        => null,
            'firmware'       => null,
            'ultima_atividade' => null,
            'mensagem'       => 'android_id é obrigatório.',
        ]);
        exit;
    }

    // ── Buscar TAP no banco ───────────────────────────────────────
    $conn = getDBConnection();
    $stmt = $conn->prepare("SELECT * FROM tap WHERE android_id = ? LIMIT 1");
    $stmt->execute([$android_id]);
    $tap = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$tap) {
        Logger::warning('reader_status: TAP não encontrada', ['android_id' => $android_id]);
        http_response_code(200);
        echo json_encode([
            'leitora_nome'     => 'Não configurada',
            'reader_id'        => null,
            'serial'           => null,
            'status_leitora'   => 'nao_encontrada',
            'api_ativa'        => false,
            'bateria'          => null,
            'conexao'          => null,
            'firmware'         => null,
            'ultima_atividade' => null,
            'mensagem'         => 'TAP não encontrada para este dispositivo. Verifique o cadastro no painel.',
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
    // Estados possíveis:
    //   online  = status ONLINE/CONNECTED/READY (sessão Cloud API ativa)
    //   idle    = state=IDLE + conexão ativa (dispositivo pronto, tela "Pronto")
    //   offline = sem conexão ou sem estado ativo
    $statusLeitora = 'offline';

    if (!empty($rs['error']) && strpos($rs['error'], '404') !== false) {
        $statusLeitora = 'nao_encontrada';
    } elseif (!empty($rs['is_ready'])) {
        $rawStatus = $rs['status'] ?? '';
        $readyStatuses = ['ONLINE', 'CONNECTED', 'READY', 'READY_TO_TRANSACT'];
        if (in_array($rawStatus, $readyStatuses)) {
            $statusLeitora = 'online';
        } else {
            // state=IDLE + conexão = idle (pronto para transacionar)
            $statusLeitora = 'idle';
        }
    } elseif (!empty($rs['state']) || !empty($rs['connection'])) {
        // Tem dados mas não está pronto — offline com informações disponíveis
        $statusLeitora = 'offline';
    }

    // ── Formatar bateria ──────────────────────────────────────────
    $bateria = null;
    if (isset($rs['battery']) && $rs['battery'] !== null && $rs['battery'] !== '') {
        $bateria = number_format((float) $rs['battery'], 1) . '%';
    }

    // ── Montar mensagem de diagnóstico ────────────────────────────
    $state    = $rs['state'] ?? '';
    $connType = $rs['connection'] ?? '';

    if ($statusLeitora === 'online') {
        $mensagem = "Leitora pronta para transacionar via {$connType}.";
    } elseif ($statusLeitora === 'idle') {
        $mensagem = "Dispositivo pronto (state: {$state}, rede: {$connType}). Pagamento habilitado.";
    } elseif ($statusLeitora === 'offline') {
        if (!empty($connType) && !empty($state)) {
            $mensagem = "Dispositivo conectado via {$connType} (state: {$state}). "
                      . "Se o display mostrar 'Pronto', o pagamento funcionará normalmente.";
        } elseif (!$apiAtiva) {
            $mensagem = "API SumUp inativa. Verifique se o token está correto no painel de Pagamentos.";
        } else {
            $mensagem = "Leitora OFFLINE. Verifique se o SumUp Solo está ligado e conectado à internet.";
        }
    } elseif ($statusLeitora === 'nao_encontrada') {
        $mensagem = "Leitora não encontrada na conta SumUp. Verifique se o reader_id está correto.";
    } else {
        $mensagem = "Status desconhecido. Tente atualizar.";
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
        'is_ready'       => $rs['is_ready'] ?? false,
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
        // Debug adicional para diagnóstico
        'debug' => [
            'raw_status'   => $rs['status'] ?? null,
            'state'        => $rs['state'] ?? null,
            'is_ready'     => $rs['is_ready'] ?? false,
            'battery_temp' => $rs['battery_temp'] ?? null,
            'tap_id'       => $tap['id'],
            'affiliate_key_set'    => defined('SUMUP_AFFILIATE_KEY') && !empty(SUMUP_AFFILIATE_KEY),
            'affiliate_app_id_set' => defined('SUMUP_AFFILIATE_APP_ID') && !empty(SUMUP_AFFILIATE_APP_ID),
        ],
    ]);

} catch (Throwable $e) {
    // Captura qualquer exceção ou erro não tratado
    Logger::error('reader_status: exceção não tratada', [
        'message' => $e->getMessage(),
        'file'    => $e->getFile(),
        'line'    => $e->getLine(),
    ]);
    if (!headers_sent()) {
        http_response_code(500);
    }
    echo json_encode([
        'leitora_nome'     => 'Erro interno',
        'reader_id'        => null,
        'serial'           => null,
        'status_leitora'   => 'erro',
        'api_ativa'        => false,
        'bateria'          => null,
        'conexao'          => null,
        'firmware'         => null,
        'ultima_atividade' => null,
        'mensagem'         => 'Erro interno: ' . $e->getMessage(),
        'debug'            => [
            'exception' => $e->getMessage(),
            'file'      => basename($e->getFile()),
            'line'      => $e->getLine(),
        ],
    ]);
}
