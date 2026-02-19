<?php
/**
 * API - Criar Pedido
 * POST /api/create_order.php
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';
require_once '../includes/sumup.php';
require_once '../includes/email_sender.php';

$headers = getallheaders();
$token = $headers['token'] ?? $headers['Token'] ?? '';

// Validar token
if (!jwtValidate($token)) {
    http_response_code(401);
    echo json_encode(['error' => 'Token inválido']);
    exit;
}

$input = $_POST;

// Validar campos obrigatórios
$required_fields = ['valor', 'descricao', 'android_id', 'payment_method', 'quantidade', 'cpf'];
foreach ($required_fields as $field) {
    if (empty($input[$field])) {
        http_response_code(400);
        echo json_encode(['error' => "$field é obrigatório"]);
        exit;
    }
}

$conn = getDBConnection();

// Buscar TAP
$stmt = $conn->prepare("SELECT * FROM tap WHERE android_id = ? LIMIT 1");
$stmt->execute([$input['android_id']]);
$tap = $stmt->fetch();

if (!$tap) {
    http_response_code(404);
    echo json_encode(['error' => 'TAP não encontrada']);
    exit;
}

// Criar pedido
$stmt = $conn->prepare("
    INSERT INTO `order` (tap_id, bebida_id, estabelecimento_id, method, valor, descricao, quantidade, cpf, checkout_status)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
");

$stmt->execute([
    $tap['id'],
    $tap['bebida_id'],
    $tap['estabelecimento_id'],
    $input['payment_method'],
    $input['valor'],
    $input['descricao'],
    $input['quantidade'],
    $input['cpf']
]);

$order_id = $conn->lastInsertId();

// --- Alerta de Venda por E-mail ---
$stmt_estab = $conn->prepare("
    SELECT e.name as estabelecimento_nome, b.name as bebida_nome, ec.email_alerta, ec.notificar_vendas
    FROM estabelecimentos e
    INNER JOIN bebidas b ON b.id = ?
    LEFT JOIN email_config ec ON e.id = ec.estabelecimento_id AND ec.status = 1
    WHERE e.id = ?
");
$stmt_estab->execute([$tap['bebida_id'], $tap['estabelecimento_id']]);
$estab_data = $stmt_estab->fetch(PDO::FETCH_ASSOC);

if ($estab_data && $estab_data['notificar_vendas'] && !empty($estab_data['email_alerta'])) {
    $order_info = [
        'estabelecimento_nome' => $estab_data['estabelecimento_nome'],
        'bebida_nome' => $estab_data['bebida_nome'],
        'method' => $input['payment_method'],
        'valor' => $input['valor'],
        'quantidade' => $input['quantidade'],
        'cpf' => $input['cpf']
    ];
    
    $email_sender = new EmailSender($conn);
    $subject = "Nova Venda Registrada - {$estab_data['estabelecimento_nome']}";
    $body = EmailSender::formatVendaBody($order_info);
    
    $email_sender->sendAlert($tap['estabelecimento_id'], $subject, $body, 'venda');
}
// --- Fim Alerta de Venda por E-mail ---

// Processar pagamento via SumUp
$sumup = new SumUpIntegration();

$order_data = [
    'id' => $order_id,
    'valor' => $input['valor'],
    'descricao' => $input['descricao']
];

if ($input['payment_method'] === 'pix') {
    // Criar checkout PIX
    $result = $sumup->createCheckoutPix($order_data);
    
    if ($result) {
        // Atualizar pedido com dados do checkout
        $stmt = $conn->prepare("
            UPDATE `order` 
            SET checkout_id = ?, pix_code = ?, response = ?
            WHERE id = ?
        ");
        $stmt->execute([$result['checkout_id'], $result['pix_code'], $result['response'], $order_id]);
        
        // Gerar QR Code
        $qr_code_base64 = generateQRCode($result['pix_code']);
        
        http_response_code(200);
        echo json_encode([
            'checkout_id' => $result['checkout_id'],
            'qr_code' => $qr_code_base64
        ]);
    } else {
        http_response_code(500);
        echo json_encode(['error' => 'Erro ao criar checkout PIX']);
    }
} else {
    // Criar checkout para cartão
    if (empty($tap['reader_id'])) {
        http_response_code(400);
        echo json_encode(['error' => 'TAP não possui leitora de cartão configurada']);
        exit;
    }
    
    $result = $sumup->createCheckoutCard($order_data, $tap['reader_id'], $input['payment_method']);
    
    if ($result) {
        // Atualizar pedido com dados do checkout
        $stmt = $conn->prepare("
            UPDATE `order` 
            SET checkout_id = ?, response = ?, checkout_status = 'PENDING'
            WHERE id = ?
        ");
        $stmt->execute([$result['checkout_id'], $result['response'], $order_id]);
        
        http_response_code(200);
        echo json_encode([
            'checkout_id' => $result['checkout_id']
        ]);
    } else {
        // Marcar como falhou
        $stmt = $conn->prepare("UPDATE `order` SET checkout_status = 'FAILED' WHERE id = ?");
        $stmt->execute([$order_id]);
        
        http_response_code(500);
        echo json_encode(['error' => 'Erro ao criar checkout de cartão']);
    }
}
