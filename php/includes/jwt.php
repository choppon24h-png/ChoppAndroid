<?php
/**
 * Funções JWT com Expiração e Refresh Token
 * Versão 2.0 - Melhorias de Segurança
 */

/**
 * Codifica JWT com expiração
 * @param array $payload Dados do usuário
 * @param int $expiration Tempo de expiração em segundos (padrão: 1 hora)
 * @param string $secret Chave secreta
 * @return string Token JWT
 */
function jwtEncode($payload, $expiration = 3600, $secret = JWT_SECRET) {
    $now = time();
    
    // Adicionar claims padrão
    $payload['iat'] = $now; // Issued at
    $payload['exp'] = $now + $expiration; // Expiration
    $payload['jti'] = bin2hex(random_bytes(16)); // JWT ID (único)
    
    $header = json_encode(['typ' => 'JWT', 'alg' => 'HS256']);
    
    $base64UrlHeader = base64UrlEncode($header);
    $base64UrlPayload = base64UrlEncode(json_encode($payload));
    
    $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, $secret, true);
    $base64UrlSignature = base64UrlEncode($signature);
    
    return $base64UrlHeader . "." . $base64UrlPayload . "." . $base64UrlSignature;
}

/**
 * Gera refresh token
 * @param int $user_id ID do usuário
 * @param string $secret Chave secreta
 * @return string Refresh token
 */
function jwtGenerateRefreshToken($user_id, $secret = JWT_SECRET) {
    $payload = [
        'user_id' => $user_id,
        'type' => 'refresh',
        'iat' => time(),
        'exp' => time() + (30 * 24 * 3600), // 30 dias
        'jti' => bin2hex(random_bytes(16))
    ];
    
    return jwtEncode($payload, 30 * 24 * 3600, $secret);
}

/**
 * Decodifica JWT
 * @param string $jwt Token JWT
 * @param string $secret Chave secreta
 * @return object|false Payload decodificado ou false
 */
function jwtDecode($jwt, $secret = JWT_SECRET) {
    $tokenParts = explode('.', $jwt);
    
    if (count($tokenParts) !== 3) {
        return false;
    }
    
    $header = base64UrlDecode($tokenParts[0]);
    $payload = base64UrlDecode($tokenParts[1]);
    $signatureProvided = $tokenParts[2];
    
    // Verificar assinatura
    $base64UrlHeader = $tokenParts[0];
    $base64UrlPayload = $tokenParts[1];
    $signature = hash_hmac('sha256', $base64UrlHeader . "." . $base64UrlPayload, $secret, true);
    $base64UrlSignature = base64UrlEncode($signature);
    
    if ($base64UrlSignature !== $signatureProvided) {
        return false;
    }
    
    $decoded = json_decode($payload);
    
    // Verificar expiração
    if (isset($decoded->exp) && $decoded->exp < time()) {
        return false;
    }
    
    return $decoded;
}

/**
 * Valida JWT
 * @param string $jwt Token JWT
 * @param string $secret Chave secreta
 * @return bool True se válido
 */
function jwtValidate($jwt, $secret = JWT_SECRET) {
    $decoded = jwtDecode($jwt, $secret);
    
    if ($decoded === false) {
        return false;
    }
    
    // Verificar se é refresh token (não deve ser aceito como token de acesso)
    if (isset($decoded->type) && $decoded->type === 'refresh') {
        return false;
    }
    
    return true;
}

/**
 * Valida refresh token
 * @param string $jwt Refresh token
 * @param string $secret Chave secreta
 * @return object|false Payload decodificado ou false
 */
function jwtValidateRefreshToken($jwt, $secret = JWT_SECRET) {
    $decoded = jwtDecode($jwt, $secret);
    
    if ($decoded === false) {
        return false;
    }
    
    // Verificar se é refresh token
    if (!isset($decoded->type) || $decoded->type !== 'refresh') {
        return false;
    }
    
    return $decoded;
}

/**
 * Extrai dados do usuário do token
 * @param string $jwt Token JWT
 * @param string $secret Chave secreta
 * @return object|false Dados do usuário ou false
 */
function jwtGetUser($jwt, $secret = JWT_SECRET) {
    $decoded = jwtDecode($jwt, $secret);
    
    if ($decoded === false) {
        return false;
    }
    
    return $decoded->user ?? false;
}

/**
 * Verifica se token está próximo de expirar (menos de 5 minutos)
 * @param string $jwt Token JWT
 * @param string $secret Chave secreta
 * @return bool True se está próximo de expirar
 */
function jwtIsExpiringSoon($jwt, $secret = JWT_SECRET) {
    $decoded = jwtDecode($jwt, $secret);
    
    if ($decoded === false) {
        return false;
    }
    
    if (!isset($decoded->exp)) {
        return false;
    }
    
    return ($decoded->exp - time()) < 300; // 5 minutos
}

/**
 * Base64 URL Encode
 * @param string $data Dados para codificar
 * @return string Dados codificados
 */
function base64UrlEncode($data) {
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

/**
 * Base64 URL Decode
 * @param string $data Dados para decodificar
 * @return string Dados decodificados
 */
function base64UrlDecode($data) {
    return base64_decode(strtr($data, '-_', '+/'));
}

/**
 * Adiciona token à blacklist (para logout)
 * @param string $jti JWT ID
 * @param int $exp Timestamp de expiração
 */
function jwtBlacklist($jti, $exp) {
    $conn = getDBConnection();
    
    try {
        // Criar tabela se não existir
        $conn->exec("
            CREATE TABLE IF NOT EXISTS jwt_blacklist (
                jti VARCHAR(32) PRIMARY KEY,
                expires_at INT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_expires (expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        ");
        
        // Adicionar à blacklist
        $stmt = $conn->prepare("INSERT INTO jwt_blacklist (jti, expires_at) VALUES (?, ?)");
        $stmt->execute([$jti, $exp]);
        
        // Limpar tokens expirados
        $conn->exec("DELETE FROM jwt_blacklist WHERE expires_at < " . time());
        
    } catch (PDOException $e) {
        Logger::error("Erro ao adicionar token à blacklist", ['error' => $e->getMessage()]);
    }
}

/**
 * Verifica se token está na blacklist
 * @param string $jti JWT ID
 * @return bool True se está na blacklist
 */
function jwtIsBlacklisted($jti) {
    $conn = getDBConnection();
    
    try {
        $stmt = $conn->prepare("SELECT COUNT(*) FROM jwt_blacklist WHERE jti = ? AND expires_at > ?");
        $stmt->execute([$jti, time()]);
        
        return $stmt->fetchColumn() > 0;
        
    } catch (PDOException $e) {
        Logger::error("Erro ao verificar blacklist", ['error' => $e->getMessage()]);
        return false;
    }
}
