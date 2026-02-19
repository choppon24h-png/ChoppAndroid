<?php
/**
 * Logger - Classe para registro de logs detalhados
 */
class Logger {
    private static $log_file = __DIR__ . '/../logs/sumup_transactions.log';

    public static function info($message, $details = []) {
        self::log('INFO', $message, $details);
    }

    public static function error($message, $details = []) {
        self::log('ERROR', $message, $details);
    }

    public static function debug($message, $details = []) {
        if (defined('DEBUG_MODE') && DEBUG_MODE) {
            self::log('DEBUG', $message, $details);
        }
    }

    public static function logTransaction($action, $status, $details = []) {
        self::log('TRANSACTION', "[$action] $status", $details);
    }

    private static function log($level, $message, $details = []) {
        $dir = dirname(self::$log_file);
        if (!is_dir($dir)) {
            mkdir($dir, 0777, true);
        }

        $timestamp = date('Y-m-d H:i:s');
        $details_str = !empty($details) ? " | Details: " . json_encode($details) : "";
        $log_entry = "[$timestamp] [$level] $message$details_str" . PHP_EOL;
        
        file_put_contents(self::$log_file, $log_entry, FILE_APPEND);
    }
}
