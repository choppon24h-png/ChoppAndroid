<?php
/**
 * Visualizador de Logs - ChoppOn
 * Acesse via: https://ochoppoficial.com.br/view_logs.php
 * 
 * IMPORTANTE: Proteja este arquivo com senha em produção!
 */

// SEGURANÇA: Senha simples (MUDE ISSO!)
$senha_correta = 'choppon2024';

// Verificar senha
session_start();
$autenticado = isset($_SESSION['log_viewer_auth']) && $_SESSION['log_viewer_auth'] === true;

if (isset($_POST['senha'])) {
    if ($_POST['senha'] === $senha_correta) {
        $_SESSION['log_viewer_auth'] = true;
        $autenticado = true;
    } else {
        $erro = 'Senha incorreta!';
    }
}

if (isset($_GET['logout'])) {
    session_destroy();
    header('Location: view_logs.php');
    exit;
}

// Se não autenticado, mostrar formulário de login
if (!$autenticado) {
    ?>
    <!DOCTYPE html>
    <html lang="pt-BR">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>ChoppOn - Visualizador de Logs</title>
        <style>
            body {
                font-family: Arial, sans-serif;
                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                display: flex;
                justify-content: center;
                align-items: center;
                height: 100vh;
                margin: 0;
            }
            .login-box {
                background: white;
                padding: 40px;
                border-radius: 10px;
                box-shadow: 0 10px 25px rgba(0,0,0,0.2);
                text-align: center;
            }
            .login-box h1 {
                margin: 0 0 20px 0;
                color: #333;
            }
            .login-box input {
                width: 100%;
                padding: 12px;
                margin: 10px 0;
                border: 1px solid #ddd;
                border-radius: 5px;
                box-sizing: border-box;
                font-size: 16px;
            }
            .login-box button {
                width: 100%;
                padding: 12px;
                background: #667eea;
                color: white;
                border: none;
                border-radius: 5px;
                font-size: 16px;
                cursor: pointer;
                margin-top: 10px;
            }
            .login-box button:hover {
                background: #5568d3;
            }
            .error {
                color: red;
                margin: 10px 0;
            }
        </style>
    </head>
    <body>
        <div class="login-box">
            <h1>🍺 ChoppOn Logs</h1>
            <p>Digite a senha para acessar os logs</p>
            <?php if (isset($erro)): ?>
                <p class="error"><?= $erro ?></p>
            <?php endif; ?>
            <form method="POST">
                <input type="password" name="senha" placeholder="Senha" required autofocus>
                <button type="submit">Entrar</button>
            </form>
        </div>
    </body>
    </html>
    <?php
    exit;
}

// Carregar Logger
require_once __DIR__ . '/includes/Logger.php';

// Ações
if (isset($_GET['clear'])) {
    Logger::clear();
    header('Location: view_logs.php');
    exit;
}

if (isset($_GET['download'])) {
    $log_content = Logger::read(999999);
    header('Content-Type: text/plain');
    header('Content-Disposition: attachment; filename="choppon_logs_' . date('Y-m-d_H-i-s') . '.txt"');
    echo $log_content;
    exit;
}

// Obter logs
$lines = isset($_GET['lines']) ? intval($_GET['lines']) : 200;
$log_content = Logger::read($lines);
$log_size = Logger::size();
$log_size_mb = round($log_size / 1024 / 1024, 2);

?>
<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ChoppOn - Visualizador de Logs</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Courier New', monospace;
            background: #1e1e1e;
            color: #d4d4d4;
            padding: 20px;
        }
        
        .header {
            background: #2d2d30;
            padding: 20px;
            border-radius: 8px;
            margin-bottom: 20px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            flex-wrap: wrap;
            gap: 10px;
        }
        
        .header h1 {
            color: #4ec9b0;
            font-size: 24px;
        }
        
        .header-info {
            color: #858585;
            font-size: 14px;
        }
        
        .controls {
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
        }
        
        .btn {
            padding: 10px 20px;
            border: none;
            border-radius: 5px;
            cursor: pointer;
            font-size: 14px;
            text-decoration: none;
            display: inline-block;
            transition: all 0.3s;
        }
        
        .btn-primary {
            background: #0e639c;
            color: white;
        }
        
        .btn-primary:hover {
            background: #1177bb;
        }
        
        .btn-success {
            background: #14a44d;
            color: white;
        }
        
        .btn-success:hover {
            background: #198754;
        }
        
        .btn-danger {
            background: #dc4c64;
            color: white;
        }
        
        .btn-danger:hover {
            background: #c82333;
        }
        
        .btn-warning {
            background: #e4a11b;
            color: white;
        }
        
        .btn-warning:hover {
            background: #dc9b0d;
        }
        
        .log-container {
            background: #1e1e1e;
            border: 1px solid #3e3e42;
            border-radius: 8px;
            padding: 20px;
            max-height: 70vh;
            overflow-y: auto;
            font-size: 13px;
            line-height: 1.6;
        }
        
        .log-container pre {
            margin: 0;
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        
        .log-line {
            margin-bottom: 10px;
            padding: 10px;
            border-left: 3px solid #3e3e42;
        }
        
        .log-info {
            border-left-color: #4ec9b0;
        }
        
        .log-error {
            border-left-color: #f48771;
            background: rgba(244, 135, 113, 0.1);
        }
        
        .log-warning {
            border-left-color: #e4a11b;
            background: rgba(228, 161, 27, 0.1);
        }
        
        .log-debug {
            border-left-color: #858585;
        }
        
        .timestamp {
            color: #858585;
        }
        
        .level {
            font-weight: bold;
            padding: 2px 6px;
            border-radius: 3px;
            margin: 0 5px;
        }
        
        .level-info {
            color: #4ec9b0;
        }
        
        .level-error {
            color: #f48771;
        }
        
        .level-warning {
            color: #e4a11b;
        }
        
        .level-debug {
            color: #858585;
        }
        
        .empty-log {
            text-align: center;
            padding: 40px;
            color: #858585;
        }
        
        ::-webkit-scrollbar {
            width: 10px;
        }
        
        ::-webkit-scrollbar-track {
            background: #1e1e1e;
        }
        
        ::-webkit-scrollbar-thumb {
            background: #3e3e42;
            border-radius: 5px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: #555;
        }
        
        .filter-bar {
            background: #2d2d30;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
            display: flex;
            gap: 10px;
            align-items: center;
            flex-wrap: wrap;
        }
        
        .filter-bar label {
            color: #d4d4d4;
        }
        
        .filter-bar select {
            padding: 8px 12px;
            background: #3e3e42;
            color: #d4d4d4;
            border: 1px solid #555;
            border-radius: 5px;
            cursor: pointer;
        }
    </style>
</head>
<body>
    <div class="header">
        <div>
            <h1>🍺 ChoppOn - Visualizador de Logs</h1>
            <div class="header-info">
                Tamanho: <?= $log_size_mb ?> MB | 
                Última atualização: <?= date('d/m/Y H:i:s') ?>
            </div>
        </div>
        <div class="controls">
            <a href="?lines=<?= $lines ?>" class="btn btn-primary">🔄 Atualizar</a>
            <a href="?download" class="btn btn-success">📥 Download</a>
            <a href="?clear" class="btn btn-danger" onclick="return confirm('Tem certeza que deseja limpar todos os logs?')">🗑️ Limpar</a>
            <a href="?logout" class="btn btn-warning">🚪 Sair</a>
        </div>
    </div>
    
    <div class="filter-bar">
        <label for="lines">Mostrar últimas:</label>
        <select id="lines" onchange="window.location.href='?lines='+this.value">
            <option value="50" <?= $lines == 50 ? 'selected' : '' ?>>50 linhas</option>
            <option value="100" <?= $lines == 100 ? 'selected' : '' ?>>100 linhas</option>
            <option value="200" <?= $lines == 200 ? 'selected' : '' ?>>200 linhas</option>
            <option value="500" <?= $lines == 500 ? 'selected' : '' ?>>500 linhas</option>
            <option value="1000" <?= $lines == 1000 ? 'selected' : '' ?>>1000 linhas</option>
        </select>
    </div>
    
    <div class="log-container">
        <?php if (empty(trim($log_content)) || $log_content === 'Nenhum log encontrado.'): ?>
            <div class="empty-log">
                <h2>📭 Nenhum log encontrado</h2>
                <p>Os logs aparecerão aqui quando o sistema começar a registrar eventos.</p>
            </div>
        <?php else: ?>
            <pre><?= htmlspecialchars($log_content) ?></pre>
        <?php endif; ?>
    </div>
    
    <script>
        // Auto-refresh a cada 10 segundos
        setTimeout(function() {
            window.location.reload();
        }, 10000);
    </script>
</body>
</html>
