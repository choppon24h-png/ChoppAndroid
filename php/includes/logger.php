<?php
/**
 * Classe Logger para Hostgator
 * Salva logs em arquivo dentro da pasta /logs
 */

class Logger {
    private static $log_dir = __DIR__ . '/../logs/';
    private static $log_file = 'app.log';
    private static $max_file_size = 10485760; // 10MB
    
    /**
     * Inicializa o Logger
     */
    private static function init() {
        // Criar pasta de logs se não existir
        if (!file_exists(self::$log_dir)) {
            mkdir(self::$log_dir, 0755, true);
        }
        
        // Criar arquivo .htaccess para proteger pasta de logs
        $htaccess = self::$log_dir . '.htaccess';
        if (!file_exists($htaccess)) {
            file_put_contents($htaccess, "Deny from all\n");
        }
        
        // Rotacionar arquivo se estiver muito grande
        $log_path = self::$log_dir . self::$log_file;
        if (file_exists($log_path) && filesize($log_path) > self::$max_file_size) {
            $backup = self::$log_dir . 'app_' . date('Y-m-d_H-i-s') . '.log';
            rename($log_path, $backup);
        }
    }
    
    /**
     * Loga mensagem de INFO
     */
    public static function info($message, $context = []) {
        self::log('INFO', $message, $context);
    }
    
    /**
     * Loga mensagem de ERROR
     */
    public static function error($message, $context = []) {
        self::log('ERROR', $message, $context);
    }
    
    /**
     * Loga mensagem de WARNING
     */
    public static function warning($message, $context = []) {
        self::log('WARNING', $message, $context);
    }
    
    /**
     * Loga mensagem de DEBUG
     */
    public static function debug($message, $context = []) {
        self::log('DEBUG', $message, $context);
    }
    
    /**
     * Método principal de logging
     */
    private static function log($level, $message, $context = []) {
        self::init();
        
        $timestamp = date('Y-m-d H:i:s');
        $ip = self::getClientIP();
        $request_uri = isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : 'CLI';
        
        // Formatar contexto
        $context_str = '';
        if (!empty($context)) {
            $context_str = "\n" . json_encode($context, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        }
        
        // Formatar linha de log
        $log_line = sprintf(
            "[%s] [%s] [IP: %s] [URI: %s] %s%s\n%s\n",
            $timestamp,
            $level,
            $ip,
            $request_uri,
            $message,
            $context_str,
            str_repeat('-', 100)
        );
        
        // Escrever no arquivo
        $log_path = self::$log_dir . self::$log_file;
        file_put_contents($log_path, $log_line, FILE_APPEND | LOCK_EX);
        
        // Também enviar para error_log do PHP (se disponível)
        error_log("[$level] $message");
    }
    
    /**
     * Obtém IP do cliente
     */
    private static function getClientIP() {
        $ip = 'UNKNOWN';
        
        if (isset($_SERVER['HTTP_CLIENT_IP'])) {
            $ip = $_SERVER['HTTP_CLIENT_IP'];
        } elseif (isset($_SERVER['HTTP_X_FORWARDED_FOR'])) {
            $ip = $_SERVER['HTTP_X_FORWARDED_FOR'];
        } elseif (isset($_SERVER['HTTP_X_FORWARDED'])) {
            $ip = $_SERVER['HTTP_X_FORWARDED'];
        } elseif (isset($_SERVER['HTTP_FORWARDED_FOR'])) {
            $ip = $_SERVER['HTTP_FORWARDED_FOR'];
        } elseif (isset($_SERVER['HTTP_FORWARDED'])) {
            $ip = $_SERVER['HTTP_FORWARDED'];
        } elseif (isset($_SERVER['REMOTE_ADDR'])) {
            $ip = $_SERVER['REMOTE_ADDR'];
        }
        
        return $ip;
    }
    
    /**
     * Limpa todos os logs
     */
    public static function clear() {
        self::init();
        $log_path = self::$log_dir . self::$log_file;
        if (file_exists($log_path)) {
            unlink($log_path);
        }
    }
    
    /**
     * Retorna conteúdo do log
     */
    public static function read($lines = 100) {
        self::init();
        $log_path = self::$log_dir . self::$log_file;
        
        if (!file_exists($log_path)) {
            return "Nenhum log encontrado.";
        }
        
        // Ler últimas N linhas
        $file = new SplFileObject($log_path, 'r');
        $file->seek(PHP_INT_MAX);
        $total_lines = $file->key();
        
        $start_line = max(0, $total_lines - $lines);
        
        $file->seek($start_line);
        $content = '';
        while (!$file->eof()) {
            $content .= $file->current();
            $file->next();
        }
        
        return $content;
    }
    
    /**
     * Retorna tamanho do arquivo de log
     */
    public static function size() {
        self::init();
        $log_path = self::$log_dir . self::$log_file;
        
        if (!file_exists($log_path)) {
            return 0;
        }
        
        return filesize($log_path);
    }
}
