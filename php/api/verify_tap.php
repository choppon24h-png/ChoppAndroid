<?php
/**
 * API - Verificar TAP
 * POST /api/verify_tap.php
 *
 * Retorna informações da bebida e TAP para o app Android.
 * O campo "cartao" indica se a TAP possui leitora de cartão vinculada —
 * quando true, o app habilita os botões de débito e crédito.
 *
 * Campos POST:
 *   - android_id : string (obrigatório)
 *
 * Resposta:
 * {
 *   "image":   "https://ochoppoficial.com.br/uploads/...",
 *   "preco":   8.50,
 *   "bebida":  "Chopp Pilsen",
 *   "volume":  10000,
 *   "cartao":  true,
 *   "esp32_mac": "AA:BB:CC:DD:EE:FF"
 * }
 */

header('Content-Type: application/json');
require_once '../includes/config.php';
require_once '../includes/jwt.php';

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

$conn = getDBConnection();

// ── Buscar TAP com dados da bebida ────────────────────────────
$stmt = $conn->prepare("
    SELECT
        t.id,
        t.reader_id,
        t.esp32_mac,
        t.volume,
        t.volume_consumido,
        b.name  AS bebida_name,
        b.value AS bebida_preco,
        b.image AS bebida_image
    FROM tap t
    INNER JOIN bebidas b ON t.bebida_id = b.id
    WHERE t.android_id = ?
    LIMIT 1
");
$stmt->execute([$android_id]);
$tap = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$tap) {
    Logger::warning('verify_tap: TAP não encontrada', ['android_id' => $android_id]);
    http_response_code(404);
    echo json_encode(['error' => 'TAP não encontrada']);
    exit;
}

// ── Atualizar last_call ───────────────────────────────────────
$stmtUpdate = $conn->prepare("UPDATE tap SET last_call = NOW() WHERE id = ?");
$stmtUpdate->execute([$tap['id']]);

// ── Calcular volume disponível ────────────────────────────────
$volume_atual = max(0, (int)$tap['volume'] - (int)$tap['volume_consumido']);

// ── cartao: true se a TAP tem reader_id configurado ──────────
// Isso habilita os botões de débito e crédito no app Android
$cartao = !empty($tap['reader_id']);

// ── Montar URL da imagem ──────────────────────────────────────
$image_url = '';
if (!empty($tap['bebida_image'])) {
    $image_url = rtrim(SITE_URL, '/') . '/' . ltrim($tap['bebida_image'], '/');
}

Logger::info('verify_tap: TAP encontrada', [
    'tap_id'    => $tap['id'],
    'android_id'=> $android_id,
    'bebida'    => $tap['bebida_name'],
    'cartao'    => $cartao,
    'reader_id' => $tap['reader_id'] ?? 'nenhum',
]);

http_response_code(200);
echo json_encode([
    'image'     => $image_url,
    'preco'     => (float) $tap['bebida_preco'],
    'bebida'    => $tap['bebida_name'],
    'volume'    => $volume_atual,
    'cartao'    => $cartao,
    'esp32_mac' => $tap['esp32_mac'] ?? null,
]);
