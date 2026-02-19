<?php
/**
 * RetryHandler - Implementação de Exponential Backoff para operações críticas
 */
require_once 'logger.php';

class RetryHandler {
    /**
     * Executa uma função com lógica de retentativa
     * 
     * @param callable $function A função a ser executada
     * @param array $args Argumentos para a função
     * @param int $maxAttempts Máximo de tentativas
     * @param int $initialDelay Delay inicial em segundos
     * @return mixed Resultado da função ou false em falha total
     */
    public static function execute(callable $function, array $args = [], $maxAttempts = 3, $initialDelay = 1) {
        $attempt = 0;
        $delay = $initialDelay;

        while ($attempt < $maxAttempts) {
            try {
                $attempt++;
                Logger::info("Executando tentativa $attempt de $maxAttempts");
                
                $result = call_user_func_array($function, $args);
                
                if ($result !== false) {
                    if ($attempt > 1) {
                        Logger::info("Sucesso obtido na tentativa $attempt");
                    }
                    return $result;
                }
            } catch (Exception $e) {
                Logger::error("Erro na tentativa $attempt: " . $e.getMessage());
            }

            if ($attempt < $maxAttempts) {
                Logger::info("Falha na tentativa $attempt. Aguardando {$delay}s antes de tentar novamente...");
                sleep($delay);
                $delay *= 2; // Exponential backoff
            }
        }

        Logger::error("Máximo de tentativas ($maxAttempts) atingido sem sucesso.");
        return false;
    }
}
