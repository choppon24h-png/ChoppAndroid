<?php
$page_title = 'TAPs';
$current_page = 'taps';

require_once '../includes/config.php';
require_once '../includes/auth.php';
require_once '../includes/sumup.php';

$conn = getDBConnection();
$success = '';
$error = '';

// Processar ações (apenas Admin Geral pode criar/editar/deletar TAPs)
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $action = $_POST['action'] ?? '';
    
    // Verificar permissão para ações de criação/edição/exclusão
    if (in_array($action, ['create', 'update', 'delete']) && !isAdminGeral()) {
        $error = 'Apenas o Administrador Geral pode gerenciar TAPs.';
        $action = '';
    }
    
    if ($action === 'create' && isAdminGeral()) {
        $bebida_id = $_POST['bebida_id'];
        $estabelecimento_id = isAdminGeral() ? $_POST['estabelecimento_id'] : getEstabelecimentoId();
        $volume = numberToFloat($_POST['volume']);
        $volume_critico = numberToFloat($_POST['volume_critico']);
        $android_id = sanitize($_POST['android_id']);
        $senha = password_hash($_POST['senha'], PASSWORD_DEFAULT);
        $vencimento = $_POST['vencimento'];
        $pairing_code = sanitize($_POST['pairing_code'] ?? '');
        
        $reader_id = '';
        if (!empty($pairing_code)) {
            $sumup = new SumUpIntegration();
            $reader_id = $sumup->addReader($pairing_code);
        }
        
        $stmt = $conn->prepare("
            INSERT INTO tap (bebida_id, estabelecimento_id, volume, volume_consumido, volume_critico, android_id, senha, vencimento, pairing_code, reader_id)
            VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
        ");
        
        if ($stmt->execute([$bebida_id, $estabelecimento_id, $volume, $volume_critico, $android_id, $senha, $vencimento, $pairing_code, $reader_id])) {
            $success = 'TAP cadastrada com sucesso!';
        } else {
            $error = 'Erro ao cadastrar TAP.';
        }
    }
    
    if ($action === 'update') {
        $id = $_POST['id'];
        $bebida_id = $_POST['bebida_id'];
        $volume = numberToFloat($_POST['volume']);
        $volume_critico = numberToFloat($_POST['volume_critico']);
        $vencimento = $_POST['vencimento'];
        $status = isset($_POST['status']) ? 1 : 0;
        $pairing_code = sanitize($_POST['pairing_code'] ?? '');
        
        // Verificar se pairing_code mudou
        $stmt = $conn->prepare("SELECT pairing_code, reader_id FROM tap WHERE id = ?");
        $stmt->execute([$id]);
        $tap_atual = $stmt->fetch();
        
        $reader_id = $tap_atual['reader_id'];
        if (!empty($pairing_code) && $pairing_code !== $tap_atual['pairing_code']) {
            $sumup = new SumUpIntegration();
            $new_reader_id = $sumup->addReader($pairing_code);
            if ($new_reader_id) {
                $reader_id = $new_reader_id;
            }
        }
        
        $update_fields = "bebida_id = ?, volume = ?, volume_critico = ?, vencimento = ?, status = ?, pairing_code = ?, reader_id = ?";
        $params = [$bebida_id, $volume, $volume_critico, $vencimento, $status, $pairing_code, $reader_id];
        
        if (!empty($_POST['senha'])) {
            $senha = password_hash($_POST['senha'], PASSWORD_DEFAULT);
            $update_fields .= ", senha = ?";
            $params[] = $senha;
        }
        
        $stmt = $conn->prepare("
            UPDATE tap 
            SET $update_fields
            WHERE id = ?
        ");
        $params[] = $id;
        
        if ($stmt->execute($params)) {
            $success = 'TAP atualizada com sucesso!';
        } else {
            $error = 'Erro ao atualizar TAP.';
        }
    }
    
    if ($action === 'delete') {
        $id = $_POST['id'];
        $stmt = $conn->prepare("DELETE FROM tap WHERE id = ?");
        if ($stmt->execute([$id])) {
            $success = 'TAP excluída com sucesso!';
        } else {
            $error = 'Erro ao excluir TAP.';
        }
    }
}

// Listar TAPs
if (isAdminGeral()) {
    $stmt = $conn->query("
        SELECT t.*, b.name as bebida_name, e.name as estabelecimento_name,
               (t.volume - t.volume_consumido) as volume_atual
        FROM tap t
        INNER JOIN bebidas b ON t.bebida_id = b.id
        INNER JOIN estabelecimentos e ON t.estabelecimento_id = e.id
        ORDER BY t.created_at DESC
    ");
} else {
    $estabelecimento_id = getEstabelecimentoId();
    $stmt = $conn->prepare("
        SELECT t.*, b.name as bebida_name, e.name as estabelecimento_name,
               (t.volume - t.volume_consumido) as volume_atual
        FROM tap t
        INNER JOIN bebidas b ON t.bebida_id = b.id
        INNER JOIN estabelecimentos e ON t.estabelecimento_id = e.id
        WHERE t.estabelecimento_id = ?
        ORDER BY t.created_at DESC
    ");
    $stmt->execute([$estabelecimento_id]);
}
$taps = $stmt->fetchAll();

// Listar estabelecimentos e bebidas para o formulário
$estabelecimentos = [];
$bebidas = [];
if (isAdminGeral()) {
    $stmt = $conn->query("SELECT * FROM estabelecimentos WHERE status = 1 ORDER BY name");
    $estabelecimentos = $stmt->fetchAll();
    
    $stmt = $conn->query("SELECT * FROM bebidas ORDER BY name");
    $bebidas = $stmt->fetchAll();
} else {
    $estabelecimento_id = getEstabelecimentoId();
    $stmt = $conn->prepare("SELECT * FROM bebidas WHERE estabelecimento_id = ? ORDER BY name");
    $stmt->execute([$estabelecimento_id]);
    $bebidas = $stmt->fetchAll();
}

require_once '../includes/header.php';

?>

<div class="page-header">
    <h1>TAPs</h1>
    <button class="btn btn-primary" onclick="openModal('modalTap')">+ Nova TAP</button>
</div>

<?php if ($success): ?>
    <div class="alert alert-success"><?php echo $success; ?></div>
<?php endif; ?>

<?php if ($error): ?>
    <div class="alert alert-danger"><?php echo $error; ?></div>
<?php endif; ?>

<div class="card">
    <div class="card-body">
        <div class="table-responsive">
            <table>
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Bebida</th>
                        <?php if (isAdminGeral()): ?>
                        <th>Estabelecimento</th>
                        <?php endif; ?>
                        <th>Ultimo Sinal</th>
                        <th>Volume Total</th>
                        <th>Volume Consumido</th>
                        <th>Volume Atual</th>
                        <th>Vencimento</th>
                        <th>Status</th>
                        <th>Ações</th>
                    </tr>
                </thead>
                <tbody>
                    <?php if (empty($taps)): ?>
                    <tr>
                        <td colspan="<?php echo isAdminGeral() ? '10' : '9'; ?>" class="text-center">
                            Nenhuma TAP cadastrada
                        </td>
                    </tr>
                    <?php else: ?>
                        <?php foreach ($taps as $tap): ?>
                        <tr>
                            <td><?php echo $tap['id']; ?></td>
                            <td><?php echo $tap['bebida_name']; ?></td>
                            <?php if (isAdminGeral()): ?>
                            <td><?php echo $tap['estabelecimento_name']; ?></td>
                            <?php endif; ?>
                            <td><?php echo $tap['last_call'] ? date("d/m/Y H:i:s",strtotime($tap['last_call'])) : ''; ?></td>
                            <td><?php echo number_format($tap['volume'], 2, ',', '.'); ?>L</td>
                            <td><?php echo number_format($tap['volume_consumido'], 2, ',', '.'); ?>L</td>
                            <td>
                                <span class="badge badge-<?php echo getTapStatusClass($tap); ?>">
                                    <?php echo number_format($tap['volume_atual'], 2, ',', '.'); ?>L
                                </span>
                            </td>
                            <td><?php echo formatDateBR($tap['vencimento']); ?></td>
                            <td>
                                <span class="badge badge-<?php echo $tap['status'] ? 'success' : 'danger'; ?>">
                                    <?php echo $tap['status'] ? 'Ativa' : 'Inativa'; ?>
                                </span>
                            </td>
                            <td>
                                <div class="action-buttons">
                                    <button class="btn btn-sm btn-primary" onclick='editTap(<?php echo json_encode($tap); ?>)'>Editar</button>
                                    <form method="POST" style="display: inline;" onsubmit="return confirmDelete('Deseja excluir esta TAP?')">
                                        <input type="hidden" name="action" value="delete">
                                        <input type="hidden" name="id" value="<?php echo $tap['id']; ?>">
                                        <button type="submit" class="btn btn-sm btn-danger">Excluir</button>
                                    </form>
                                </div>
                            </td>
                        </tr>
                        <?php endforeach; ?>
                    <?php endif; ?>
                </tbody>
            </table>
        </div>
    </div>
</div>

<!-- Modal TAP -->
<div id="modalTap" class="modal">
    <div class="modal-content">
        <div class="modal-header">
            <h3 id="modalTitle">Nova TAP</h3>
            <button class="modal-close" onclick="closeModal('modalTap')">&times;</button>
        </div>
        <form method="POST" id="formTap">
            <div class="modal-body">
                <input type="hidden" name="action" id="formAction" value="create">
                <input type="hidden" name="id" id="tapId">
                
                <?php if (isAdminGeral()): ?>
                <div class="form-group">
                    <label for="estabelecimento_id">Estabelecimento *</label>
                    <select name="estabelecimento_id" id="estabelecimento_id" class="form-control" required>
                        <option value="">Selecione</option>
                        <?php foreach ($estabelecimentos as $estab): ?>
                        <option value="<?php echo $estab['id']; ?>"><?php echo $estab['name']; ?></option>
                        <?php endforeach; ?>
                    </select>
                </div>
                <?php endif; ?>
                
                <div class="form-group">
                    <label for="bebida_id">Bebida *</label>
                    <select name="bebida_id" id="bebida_id" class="form-control" required>
                        <option value="">Selecione</option>
                        <?php foreach ($bebidas as $bebida): ?>
                        <option value="<?php echo $bebida['id']; ?>"><?php echo $bebida['name']; ?></option>
                        <?php endforeach; ?>
                    </select>
                </div>
                
                <div class="form-group">
                    <label for="android_id">Android ID *</label>
                    <input type="text" name="android_id" id="android_id" class="form-control" required>
                </div>
                
                <div class="form-group">
                    <label for="senha">Senha *</label>
                    <input type="password" name="senha" id="senha" class="form-control" required>
                    <small id="senhaHelp" style="color: var(--gray-600);">Deixe em branco para manter a senha atual (apenas edição)</small>
                </div>
                
                <div class="row">
                    <div class="col-md-6">
                        <div class="form-group">
                            <label for="volume">Volume Total (L) *</label>
                            <input type="number" step="0.01" name="volume" id="volume" class="form-control" required>
                        </div>
                    </div>
                    <div class="col-md-6">
                        <div class="form-group">
                            <label for="volume_critico">Volume Crítico (L) *</label>
                            <input type="number" step="0.01" name="volume_critico" id="volume_critico" class="form-control" required>
                        </div>
                    </div>
                </div>
                
                <div class="form-group">
                    <label for="vencimento">Data de Vencimento *</label>
                    <input type="date" name="vencimento" id="vencimento" class="form-control" required>
                </div>
                
                <div class="form-group">
                    <label for="pairing_code">Código de Pareamento SumUp</label>
                    <input type="text" name="pairing_code" id="pairing_code" class="form-control" placeholder="Opcional">
                    <small style="color: var(--gray-600);">Deixe em branco se não usar leitora de cartão</small>
                </div>
                
                <div class="form-group" id="statusGroup" style="display: none;">
                    <label class="checkbox-label">
                        <input type="checkbox" name="status" id="status" value="1" checked>
                        <span>TAP Ativa</span>
                    </label>
                </div>
            </div>
            <div class="modal-footer">
                <button type="button" class="btn btn-secondary" onclick="closeModal('modalTap')">Cancelar</button>
                <button type="submit" class="btn btn-primary">Salvar</button>
            </div>
        </form>
    </div>
</div>

<?php
$extra_js = <<<'JS'
<script>
function editTap(tap) {
    document.getElementById('modalTitle').textContent = 'Editar TAP';
    document.getElementById('formAction').value = 'update';
    document.getElementById('tapId').value = tap.id;
    document.getElementById('bebida_id').value = tap.bebida_id;
    document.getElementById('android_id').value = tap.android_id;
    document.getElementById('android_id').readOnly = true;
    document.getElementById('volume').value = tap.volume;
    document.getElementById('volume_critico').value = tap.volume_critico;
    document.getElementById('vencimento').value = tap.vencimento;
    document.getElementById('pairing_code').value = tap.pairing_code || '';
    document.getElementById('status').checked = tap.status == 1;
    document.getElementById('statusGroup').style.display = 'block';
    document.getElementById('senha').required = false;
    document.getElementById('senhaHelp').style.display = 'block';
    
    openModal('modalTap');
}

// Reset form ao abrir modal para nova TAP
document.querySelector('[onclick="openModal(\'modalTap\')"]').addEventListener('click', function() {
    document.getElementById('modalTitle').textContent = 'Nova TAP';
    document.getElementById('formAction').value = 'create';
    document.getElementById('formTap').reset();
    document.getElementById('android_id').readOnly = false;
    document.getElementById('statusGroup').style.display = 'none';
    document.getElementById('senha').required = true;
    document.getElementById('senhaHelp').style.display = 'none';
});
</script>
JS;

require_once '../includes/footer.php';
?>
