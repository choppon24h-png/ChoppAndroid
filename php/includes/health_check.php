<?php
/**
 * HealthCheck - Classe para monitoramento de saúde do sistema SumUp
 */
require_once 'logger.php';
require_once 'sumup.php';

class HealthCheck {
    private $sumup;

    public function __construct() {
        $this->sumup = new SumUpIntegration();
    }

    /**
     * Verifica se um leitor específico está online e pareado
     */
    public function checkReaderStatus($reader_id) {
        try {
            Logger::info("Iniciando health check para leitor: $reader_id");
            $status = $this->sumup->getReaderStatus($reader_id);
            
            if ($status && $status['status'] === 'online') {
                Logger::info("Leitor $reader_id está ONLINE");
                return true;
            }
            
            Logger::error("Leitor $reader_id está OFFLINE ou indisponível");
            return false;
        } catch (Exception $e) {
            Logger::error("Exceção no health check do leitor: " . $e.getMessage());
            return false;
        }
    }

    /**
     * Verifica se a API da SumUp está respondendo
     */
    public function checkSumUpAPI() {
        try {
            return $this->sumup->testConnectivity();
        } catch (Exception $e) {
            Logger::error("API SumUp inacessível: " . $e.getMessage());
            return false;
        }
    }
}
