<?php
/**
 * Configurações do Sistema Chopp On Tap
 * Versão 3.0.2 - Correção DEFINITIVA de detecção de URL
 */

// Modo Debug (ative para ver logs detalhados)
define('DEBUG_MODE', true);

// Configurações do Banco de Dados
define('DB_HOST', 'localhost');
define('DB_NAME', 'inlaud99_choppontap');
define('DB_USER', 'inlaud99_admin');
define('DB_PASS', 'Admin259087@');
define('DB_CHARSET', 'utf8mb4');

// Configurações do Sistema
define('SITE_NAME', 'Chopp On Tap');

// Detectar URL automaticamente (VERSÃO CORRIGIDA)
// CORREÇÃO: Simplificando a detecção de URL para evitar problemas de caminho.
// A função original é muito complexa e pode falhar em ambientes de sandbox ou subdiretórios.
	function detectSiteURL() {
	    // Detectar protocolo
	    $protocol = isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off' ? 'https://' : 'http://';
	    
	    // Detectar host
	    $host = $_SERVER['HTTP_HOST'] ?? 'localhost';
	    
	    // Detectar caminho base (diretório onde o index.php está)
	    $base_dir = str_replace('\\', '/', dirname($_SERVER['SCRIPT_NAME'] ?? ''));
	    
	    // Limpar barras e garantir que não termine com barra, a menos que seja a raiz
	    $base_dir = rtrim($base_dir, '/');
	    
	    // Se o script estiver em um subdiretório, o $base_dir será o caminho.
	    // Se estiver na raiz, $base_dir será vazio ou '/'.
	    if ($base_dir === '.' || $base_dir === '/') {
	        $base_dir = '';
	    }
	    
	    // O sistema parece estar em um subdiretório. Vamos tentar detectar o caminho
	    // relativo ao arquivo de configuração.
	    // Como config.php está em includes/, o caminho base é o diretório pai.
	    $path_parts = explode('/', $_SERVER['SCRIPT_NAME'] ?? '');
	    // Remove o nome do script (ex: index.php)
	    array_pop($path_parts); 
	    // Remove o diretório atual (ex: includes)
	    array_pop($path_parts); 
	    $base_path = implode('/', $path_parts);
	    
	    // Limpar barras
	    $base_path = rtrim($base_path, '/');
	    if ($base_path === '.' || $base_path === '/') {
	        $base_path = '';
	    }
	    
	    return $protocol . $host . $base_path;
	}

define('SITE_URL', detectSiteURL());

define('SYSTEM_VERSION', 'v3.0.2');

// Configurações de Sessão
define('SESSION_TIMEOUT', 7200); // 2 horas em segundos

// Configurações SumUp
define('SUMUP_TOKEN', 'sup_sk_8vNpSEJPVudqJrWPdUlomuE3EfVofw1bL');
define('SUMUP_MERCHANT_CODE', 'MCTSYDUE');
define('SUMUP_CHECKOUT_URL', 'https://api.sumup.com/v0.1/checkouts/');
define('SUMUP_MERCHANT_URL', 'https://api.sumup.com/v0.1/merchants/');

// Configurações JWT
define('JWT_SECRET', 'teaste');
define('JWT_ALGORITHM', 'HS256');

// Timezone
date_default_timezone_set('America/Sao_Paulo');

// ========================================
// CONFIGURAÇÕES TELEGRAM CRON
// ========================================
// Chave de segurança para execução do CRON via HTTP
// Gere uma chave aleatória forte (ex: openssl rand -hex 32)
// Use esta chave ao configurar o CRON: telegram_cron.php?key=SUA_CHAVE
define('TELEGRAM_CRON_KEY', 'choppon_telegram_2026_secure_key_' . md5(DB_NAME . DB_PASS));

// Habilitar/desabilitar notificações automáticas
define('TELEGRAM_NOTIFICATIONS_ENABLED', true);

// Intervalo mínimo entre notificações do mesmo tipo (em horas)
define('TELEGRAM_MIN_INTERVAL_HOURS', 6);

// Iniciar sessão
if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

// Incluir sistema de logs
require_once __DIR__ . '/logger.php';

// Conexão com o Banco de Dados
function getDBConnection() {
    static $conn = null;
    
    if ($conn === null) {
        try {
            $dsn = "mysql:host=" . DB_HOST . ";dbname=" . DB_NAME . ";charset=" . DB_CHARSET;
            $options = [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES => false,
            ];
            $conn = new PDO($dsn, DB_USER, DB_PASS, $options);
        } catch (PDOException $e) {
            Logger::error("Erro de conexão com banco de dados", ['error' => $e->getMessage()]);
            die("Erro de conexão com o banco de dados. Verifique as configurações.");
        }
    }
    
    return $conn;
}

// Função para verificar se usuário está logado
function isLoggedIn() {
    return isset($_SESSION['user_id']) && !empty($_SESSION['user_id']);
}

// Função para verificar se é admin geral
function isAdminGeral() {
    return isset($_SESSION['user_type']) && $_SESSION['user_type'] == 1;
}

// Função para redirecionar
function redirect($url) {
    // Se a URL não começar com http, adicionar SITE_URL
    if (strpos($url, 'http') !== 0) {
        $url = SITE_URL . '/' . ltrim($url, '/');
    }
    header("Location: " . $url);
    exit();
}

// Função para sanitizar input
function sanitize($data) {
    return htmlspecialchars(strip_tags(trim($data)), ENT_QUOTES, 'UTF-8');
}

// Função para formatar valor monetário
function formatMoney($value) {
    return 'R$ ' . number_format($value, 2, ',', '.');
}

// Função para formatar data brasileira
function formatDateBR($date) {
    if (empty($date)) return '';
    $timestamp = strtotime($date);
    return date('d/m/Y', $timestamp);
}

// Função para formatar data e hora brasileira
function formatDateTimeBR($datetime) {
    if (empty($datetime)) return '';
    $timestamp = strtotime($datetime);
    return date('d/m/Y H:i:s', $timestamp);
}

// Função para converter número BR para float
function numberToFloat($number) {
    $number = str_replace('.', '', $number);
    $number = str_replace(',', '.', $number);
    return floatval($number);
}

// Função para obter tipo de usuário
function getUserType($type) {
    switch ($type) {
        case 1:
            return 'Admin. Geral';
        case 2:
            return 'Admin. Estabelecimento';
        case 3:
            return 'Gerente Estabelecimento';
        case 4:
            return 'Operador';
        default:
            return 'Tipo inválido';
    }
}

// Função para obter método de pagamento
function getPaymentMethod($method) {
    switch ($method) {
        case 'pix':
            return 'PIX';
        case 'credit':
            return 'Crédito';
        case 'debit':
            return 'Débito';
        default:
            return $method;
    }
}

// Função para obter classe CSS do status do pedido
function getOrderStatusClass($status) {
    switch ($status) {
        case 'SUCCESSFUL':
            return 'success';
        case 'PENDING':
            return 'warning';
        case 'CANCELLED':
        case 'FAILED':
            return 'danger';
        default:
            return 'secondary';
    }
}

// Função para obter classe CSS do status da TAP
function getTapStatusClass($tap) {
    if (!$tap['status']) {
        return 'secondary';
    }
    $volume_atual = $tap['volume'] - $tap['volume_consumido'];
    if ($volume_atual > $tap['volume_critico']) {
        return 'success';
    }
    if ($volume_atual == 0) {
        return 'danger';
    }
    return 'warning';
}

// Função para calcular tempo de sessão
function getSessionTime() {
    if (isset($_SESSION['login_time'])) {
        $diff = time() - $_SESSION['login_time'];
        $hours = floor($diff / 3600);
        $minutes = floor(($diff % 3600) / 60);
        return sprintf('%02d:%02d', $hours, $minutes);
    }
    return '00:00';
}

// Log da URL detectada (apenas em debug)
if (DEBUG_MODE) {
    Logger::debug("URL detectada", [
        'SITE_URL' => SITE_URL,
        'HTTP_HOST' => $_SERVER['HTTP_HOST'] ?? 'N/A',
        'SCRIPT_NAME' => $_SERVER['SCRIPT_NAME'] ?? 'N/A',
        'REQUEST_URI' => $_SERVER['REQUEST_URI'] ?? 'N/A'
    ]);
}
?>
