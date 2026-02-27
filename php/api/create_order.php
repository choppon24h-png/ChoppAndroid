<?php
/**
 * API - Criar Pedido
 * POST /api/create_order.php
 *
 * Suporta: pix, debit (débito), credit (crédito)
 * Integração: SumUp Cloud API para leitora Solo
 *
 * Campos obrigatórios (POST):
 *   - valor          : float  (ex: 1.00)
 *   - descricao      : string (ex: "Chopp 300ml")
 *   - android_id     : string (ID do dispositivo Android)
 *   - payment_method : string ("pix" | "debit" | "credit")
 *   - quantidade     : int    (ex: 1)
 *   - cpf            : string (CPF do cliente)
 *
 * Resposta PIX:
 *   { "checkout_id": "...", "qr_code": "<base64>" }
 *
 * Resposta Cartão (débito/crédito):
 *   { "checkout_id": "...", "card_type": "debit|credit", "reader_name": "...", "reader_serial": "..." }
 *
 * Resposta Erro:
 *   { "error": "Mensagem de erro", "error_type": "TIPO" }
 *
 * CORREÇÃO v2.2.0:
 *   - Proteção global try/catch: NUNCA retorna corpo vazio
 *   - SUMUP_AFFILIATE_KEY e SUMUP_AFFILIATE_APP_ID agora em config.php
 *   - Mensagens de erro detalhadas com error_type para o Android
 *   - PIX: generateQRCode chamada corretamente como função global
 */

// Proteção global: garantir Content-Type JSON antes de qualquer coisa
header('Content-Type: application/json');

// Handler de erros fatais
register_shutdown_function(function () {
    $error = error_get_last();
    if ($error && in_array($error['type'], [E_ERROR, E_PARSE, E_CORE_ERROR, E_COMPILE_ERROR])) {
        if (!headers_sent()) {
            header('Content-Type: application/json');
            http_response_code(500);
        }
        echo json_encode([
            'error'      => 'Erro interno no servidor: ' . $error['message'],
            'error_type' => 'FATAL_ERROR',
            'debug'      => [
                'file' => basename($error['file']),
                'line' => $error['line'],
            ],
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
        echo json_encode(['error' => 'Token inválido', 'error_type' => 'AUTH_ERROR']);
        exit;
    }

    $input = $_POST;

    // ── Validar campos obrigatórios ───────────────────────────────
    $required_fields = ['valor', 'descricao', 'android_id', 'payment_method', 'quantidade', 'cpf'];
    foreach ($required_fields as $field) {
        if (!isset($input[$field]) || $input[$field] === '') {
            http_response_code(400);
            echo json_encode([
                'error'      => "$field é obrigatório",
                'error_type' => 'MISSING_FIELD',
            ]);
            exit;
        }
    }

    // Normalizar payment_method
    $payment_method = strtolower(trim($input['payment_method']));
    $allowed_methods = ['pix', 'debit', 'credit'];
    if (!in_array($payment_method, $allowed_methods)) {
        http_response_code(400);
        echo json_encode([
            'error'      => "payment_method inválido. Use: pix, debit ou credit",
            'error_type' => 'INVALID_METHOD',
        ]);
        exit;
    }

    $conn = getDBConnection();

    // ── Buscar TAP ────────────────────────────────────────────────
    $stmt = $conn->prepare("SELECT * FROM tap WHERE android_id = ? LIMIT 1");
    $stmt->execute([$input['android_id']]);
    $tap = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$tap) {
        http_response_code(404);
        echo json_encode([
            'error'      => 'TAP não encontrada',
            'error_type' => 'TAP_NOT_FOUND',
        ]);
        exit;
    }

    // ── Criar pedido no banco ─────────────────────────────────────
    $stmt = $conn->prepare("
        INSERT INTO `order`
            (tap_id, bebida_id, estabelecimento_id, method, valor, descricao, quantidade, cpf, checkout_status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
    ");

    $stmt->execute([
        $tap['id'],
        $tap['bebida_id'],
        $tap['estabelecimento_id'],
        $payment_method,
        $input['valor'],
        $input['descricao'],
        $input['quantidade'],
        $input['cpf'],
    ]);

    $order_id = $conn->lastInsertId();

    Logger::info('create_order: pedido criado', [
        'order_id'       => $order_id,
        'payment_method' => $payment_method,
        'valor'          => $input['valor'],
        'android_id'     => $input['android_id'],
        'tap_id'         => $tap['id'],
    ]);

    // ── Alerta de Venda por E-mail (opcional, não bloqueia) ───────
    try {
        if (file_exists('../includes/email_sender.php')) {
            require_once '../includes/email_sender.php';

            $stmt_estab = $conn->prepare("
                SELECT e.name as estabelecimento_nome, b.name as bebida_nome, ec.email_alerta, ec.notificar_vendas
                FROM estabelecimentos e
                INNER JOIN bebidas b ON b.id = ?
                LEFT JOIN email_config ec ON e.id = ec.estabelecimento_id AND ec.status = 1
                WHERE e.id = ?
            ");
            $stmt_estab->execute([$tap['bebida_id'], $tap['estabelecimento_id']]);
            $estab_data = $stmt_estab->fetch(PDO::FETCH_ASSOC);

            if ($estab_data && !empty($estab_data['notificar_vendas']) && !empty($estab_data['email_alerta'])) {
                $order_info = [
                    'estabelecimento_nome' => $estab_data['estabelecimento_nome'],
                    'bebida_nome'          => $estab_data['bebida_nome'],
                    'method'               => $payment_method,
                    'valor'                => $input['valor'],
                    'quantidade'           => $input['quantidade'],
                    'cpf'                  => $input['cpf'],
                ];
                $email_sender = new EmailSender($conn);
                $subject      = "Nova Venda Registrada - {$estab_data['estabelecimento_nome']}";
                $body_email   = EmailSender::formatVendaBody($order_info);
                $email_sender->sendAlert($tap['estabelecimento_id'], $subject, $body_email, 'venda');
            }
        }
    } catch (Exception $e) {
        Logger::warning('create_order: falha ao enviar e-mail de alerta', ['error' => $e->getMessage()]);
    }

    // ── Processar pagamento via SumUp ─────────────────────────────
    $sumup = new SumUpIntegration();

    $order_data = [
        'id'        => $order_id,
        'valor'     => $input['valor'],
        'descricao' => $input['descricao'],
    ];

    // ── PIX ───────────────────────────────────────────────────────
    if ($payment_method === 'pix') {
        $result = $sumup->createCheckoutPix($order_data);

        if ($result) {
            $stmt = $conn->prepare("
                UPDATE `order`
                SET checkout_id = ?, pix_code = ?, response = ?
                WHERE id = ?
            ");
            $stmt->execute([$result['checkout_id'], $result['pix_code'], $result['response'], $order_id]);

            // Gerar QR Code — generateQRCode é função global definida no sumup.php
            $qr_code_base64 = '';
            if (!empty($result['pix_code'])) {
                $qr_code_base64 = generateQRCode($result['pix_code']);
            }

            Logger::info('create_order: PIX criado', [
                'order_id'       => $order_id,
                'checkout_id'    => $result['checkout_id'],
                'pix_code_len'   => strlen($result['pix_code'] ?? ''),
                'qr_code_len'    => strlen($qr_code_base64),
                'qr_code_ok'     => !empty($qr_code_base64),
            ]);

            http_response_code(200);
            echo json_encode([
                'checkout_id' => $result['checkout_id'],
                'qr_code'     => $qr_code_base64,
                'pix_code'    => $result['pix_code'], // código copia-e-cola como fallback
            ]);
        } else {
            $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?");
            $stmt->execute([$order_id]);

            Logger::error('create_order: falha ao criar checkout PIX', ['order_id' => $order_id]);
            http_response_code(500);
            echo json_encode([
                'error'      => 'Erro ao criar checkout PIX. Verifique a configuração SumUp (token e pay_to_email).',
                'error_type' => 'PIX_CHECKOUT_FAILED',
            ]);
        }
        exit;
    }

    // ── DÉBITO / CRÉDITO (Cloud API - SumUp Solo) ─────────────────
    if (empty($tap['reader_id'])) {
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?");
        $stmt->execute([$order_id]);

        Logger::error('create_order: TAP sem reader_id configurado', [
            'order_id' => $order_id,
            'tap_id'   => $tap['id'],
        ]);

        http_response_code(400);
        echo json_encode([
            'error'      => 'Esta TAP não possui leitora de cartão configurada. Configure o pairing_code no painel administrativo.',
            'error_type' => 'NO_READER_CONFIGURED',
            'tap_id'     => $tap['id'],
        ]);
        exit;
    }

    // Verificar se affiliate_key e app_id estão configurados
    if (!defined('SUMUP_AFFILIATE_KEY') || empty(SUMUP_AFFILIATE_KEY)) {
        http_response_code(500);
        echo json_encode([
            'error'      => 'Affiliate Key não configurada. Acesse o painel > Pagamentos > Affiliate Key.',
            'error_type' => 'AFFILIATE_KEY_MISSING',
        ]);
        exit;
    }
    if (!defined('SUMUP_AFFILIATE_APP_ID') || empty(SUMUP_AFFILIATE_APP_ID)) {
        http_response_code(500);
        echo json_encode([
            'error'      => 'Affiliate App ID não configurado. Acesse o painel > Pagamentos > Affiliate App ID.',
            'error_type' => 'AFFILIATE_APP_ID_MISSING',
        ]);
        exit;
    }

    // Buscar nome e serial da leitora para retornar ao app
    $reader_name   = null;
    $reader_serial = null;
    try {
        // Tentar tabela readers primeiro
        $stmtReader = $conn->prepare("SELECT name, serial FROM readers WHERE reader_id = ? LIMIT 1");
        $stmtReader->execute([$tap['reader_id']]);
        $readerRow = $stmtReader->fetch(PDO::FETCH_ASSOC);
        if ($readerRow) {
            $reader_name   = $readerRow['name'] ?? null;
            $reader_serial = $readerRow['serial'] ?? null;
        }
    } catch (Exception $e) {
        // Tabela readers pode não existir — buscar via API
        Logger::debug('create_order: tabela readers não disponível, buscando via API', []);
    }

    // Se não encontrou no banco, buscar via SumUp API
    if (empty($reader_name)) {
        try {
            $readerStatus = $sumup->getReaderStatus($tap['reader_id']);
            $reader_name   = $readerStatus['reader_name'] ?? null;
            $reader_serial = $readerStatus['reader_serial'] ?? null;
        } catch (Exception $e) {
            Logger::warning('create_order: falha ao buscar reader via API', ['error' => $e->getMessage()]);
        }
    }

    // Enviar checkout para a leitora via Cloud API
    $result = $sumup->createCheckoutCard($order_data, $tap['reader_id'], $payment_method);

    if ($result) {
        $stmt = $conn->prepare("
            UPDATE `order`
            SET checkout_id = ?, response = ?, checkout_status = 'PENDING'
            WHERE id = ?
        ");
        $stmt->execute([$result['checkout_id'], $result['response'], $order_id]);

        Logger::info('create_order: checkout cartão criado', [
            'order_id'    => $order_id,
            'checkout_id' => $result['checkout_id'],
            'card_type'   => $payment_method,
            'reader_id'   => $tap['reader_id'],
        ]);

        http_response_code(200);
        echo json_encode([
            'checkout_id'   => $result['checkout_id'],
            'card_type'     => $payment_method,
            'reader_name'   => $reader_name,
            'reader_serial' => $reader_serial,
            'reader_id'     => $tap['reader_id'],
        ]);
    } else {
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?");
        $stmt->execute([$order_id]);

        Logger::error('create_order: falha ao criar checkout cartão', [
            'order_id'   => $order_id,
            'card_type'  => $payment_method,
            'reader_id'  => $tap['reader_id'],
        ]);

        http_response_code(500);
        echo json_encode([
            'error'      => 'Erro ao criar checkout de cartão. Verifique se a leitora está online e as configurações SumUp.',
            'error_type' => 'CARD_CHECKOUT_FAILED',
            'card_type'  => $payment_method,
            'reader_id'  => $tap['reader_id'],
        ]);
    }

} catch (Throwable $e) {
    // Captura qualquer exceção ou erro não tratado
    if (isset($conn) && isset($order_id)) {
        try {
            $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?")->execute([$order_id]);
        } catch (Exception $dbEx) { /* ignorar */ }
    }

    Logger::error('create_order: exceção não tratada', [
        'message' => $e->getMessage(),
        'file'    => $e->getFile(),
        'line'    => $e->getLine(),
    ]);

    if (!headers_sent()) {
        http_response_code(500);
    }
    echo json_encode([
        'error'      => 'Erro interno no servidor: ' . $e->getMessage(),
        'error_type' => 'EXCEPTION',
        'debug'      => [
            'file' => basename($e->getFile()),
            'line' => $e->getLine(),
        ],
    ]);
}
