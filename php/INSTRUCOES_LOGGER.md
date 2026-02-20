# 📋 INSTRUÇÕES DE INSTALAÇÃO - SISTEMA DE LOGS

## 🎯 ARQUIVOS CRIADOS

Criei 3 arquivos para você:

1. **Logger.php** - Classe de logging
2. **view_logs.php** - Visualizador web de logs
3. **logs_htaccess.txt** - Arquivo de proteção da pasta logs

---

## 📂 ESTRUTURA DE PASTAS NO HOSTGATOR

```
ochoppoficial.com.br/
├── api/
│   ├── create_order.php
│   ├── verify_tap.php
│   └── ...
├── includes/
│   ├── Logger.php          ← NOVO (colocar aqui)
│   ├── sumup.php
│   ├── config.php
│   └── ...
├── logs/                    ← NOVA PASTA (criar)
│   ├── .htaccess           ← NOVO (protege pasta)
│   └── app.log             ← Será criado automaticamente
├── view_logs.php           ← NOVO (colocar na raiz)
└── ...
```

---

## 🚀 PASSO A PASSO DE INSTALAÇÃO

### **1. Criar Pasta `/logs`**

No gerenciador de arquivos do Hostgator:

1. Acesse a raiz do site: `/ochoppoficial.com.br/`
2. Clique em **"Nova Pasta"**
3. Nome: `logs`
4. Permissões: `755`

---

### **2. Fazer Upload do Logger.php**

1. Acesse a pasta `/includes/`
2. Faça upload do arquivo **Logger.php**
3. Verifique se está em: `/ochoppoficial.com.br/includes/Logger.php`

---

### **3. Proteger Pasta de Logs**

1. Acesse a pasta `/logs/` que você criou
2. Crie um arquivo chamado `.htaccess`
3. Conteúdo do arquivo:
```
Deny from all
```
4. Salve

**OU** faça upload do arquivo `logs_htaccess.txt` e renomeie para `.htaccess`

---

### **4. Fazer Upload do view_logs.php**

1. Acesse a **raiz** do site: `/ochoppoficial.com.br/`
2. Faça upload do arquivo **view_logs.php**
3. Verifique se está em: `/ochoppoficial.com.br/view_logs.php`

---

### **5. Configurar Senha do Visualizador**

Edite o arquivo `view_logs.php` (linha 11):

```php
$senha_correta = 'choppon2024';  // ← MUDE ESTA SENHA!
```

**Troque para uma senha forte!**

---

## ✅ VERIFICAÇÃO

### **Testar se Logger está funcionando**

Acesse no navegador:
```
https://ochoppoficial.com.br/view_logs.php
```

**O que vai acontecer:**
1. Vai pedir senha (use a senha que você configurou)
2. Se tudo estiver correto, vai mostrar a tela de logs
3. Inicialmente estará vazio (normal)

---

## 🧪 TESTAR LOGGING

### **Criar arquivo de teste**

Crie um arquivo `test_logger.php` na raiz:

```php
<?php
require_once __DIR__ . '/includes/Logger.php';

Logger::info("Teste de logging", [
    'teste' => 'funcionando',
    'timestamp' => date('Y-m-d H:i:s')
]);

echo "Log criado! Acesse view_logs.php para ver.";
?>
```

Acesse: `https://ochoppoficial.com.br/test_logger.php`

Depois acesse: `https://ochoppoficial.com.br/view_logs.php`

**Deve aparecer o log de teste!**

---

## 📊 COMO USAR O VISUALIZADOR

### **Funcionalidades:**

1. **🔄 Atualizar** - Recarrega os logs
2. **📥 Download** - Baixa arquivo de log completo
3. **🗑️ Limpar** - Apaga todos os logs
4. **🚪 Sair** - Faz logout

### **Filtros:**

- Escolha quantas linhas quer ver (50, 100, 200, 500, 1000)
- Auto-refresh a cada 10 segundos

---

## 🎯 INTEGRAÇÃO COM create_order.php E sumup.php

**Os arquivos que você enviou JÁ USAM o Logger!**

Exemplos de uso que já estão nos arquivos:

```php
// Em create_order.php
Logger::info("Create Order - TAP Data", [
    'tap_id' => $tap['id'],
    'reader_id' => $tap['reader_id']
]);

Logger::error("Create Order - Failed", [
    'error' => 'mensagem de erro'
]);

// Em sumup.php
Logger::info("SumUp Request", [
    'url' => $url,
    'method' => $method,
    'data' => $data
]);

Logger::info("SumUp Response", [
    'status' => $status,
    'response' => $response
]);
```

---

## 🔍 TESTANDO PAGAMENTO COM CARTÃO

### **Fluxo completo:**

1. Abra o app ChoppOn
2. Selecione quantidade
3. Clique em "Pagar com Cartão"
4. **IMEDIATAMENTE** acesse: `https://ochoppoficial.com.br/view_logs.php`
5. Você verá TODOS os logs detalhados:
   - ✅ Dados da TAP
   - ✅ reader_id usado
   - ✅ Requisição enviada para SumUp
   - ✅ Resposta da SumUp
   - ✅ Erros (se houver)

---

## 📝 EXEMPLO DE LOG

```
[2026-02-20 15:30:45] [INFO] [IP: 192.168.1.100] [URI: /api/create_order.php] Create Order - TAP Data
{
    "tap_id": "123",
    "reader_id": "rdr_1JHCGHNM3095NBKJP2CMDWJTXC",
    "android_id": "abc123",
    "estabelecimento_id": "1"
}
----------------------------------------------------------------------------------------------------

[2026-02-20 15:30:45] [INFO] [IP: 192.168.1.100] [URI: /api/create_order.php] SumUp Request
{
    "url": "https://api.sumup.com/v0.1/merchants/MCTSYDUE/readers/rdr_1JHCGHNM3095NBKJP2CMDWJTXC/checkout",
    "method": "POST",
    "data": {
        "total_amount": {
            "value": 1550,
            "currency": "BRL",
            "minor_unit": 2
        },
        "installments": 1,
        "description": "Chopp Brahma 500ml",
        "card_type": "credit"
    }
}
----------------------------------------------------------------------------------------------------

[2026-02-20 15:30:46] [INFO] [IP: 192.168.1.100] [URI: /api/create_order.php] SumUp Response
{
    "status": 201,
    "response": "{\"id\":\"abc123\",\"status\":\"pending\"}",
    "curl_error": ""
}
----------------------------------------------------------------------------------------------------
```

---

## 🔒 SEGURANÇA

### **Proteções implementadas:**

1. ✅ Pasta `/logs/` protegida por `.htaccess`
2. ✅ Visualizador protegido por senha
3. ✅ Logs não acessíveis via URL direta
4. ✅ Rotação automática de logs (10MB)

### **Recomendações:**

- ⚠️ **MUDE A SENHA** do view_logs.php
- ⚠️ Não compartilhe a URL do visualizador
- ⚠️ Limpe logs periodicamente

---

## 📱 PERMISSÕES DE ARQUIVOS

```
/includes/Logger.php     → 644
/logs/                   → 755
/logs/.htaccess          → 644
/logs/app.log            → 644 (criado automaticamente)
/view_logs.php           → 644
```

---

## ❓ TROUBLESHOOTING

### **Erro: "Class 'Logger' not found"**

**Solução:**
- Verifique se `Logger.php` está em `/includes/`
- Verifique se o caminho está correto nos arquivos PHP

---

### **Erro: "Permission denied" ao criar log**

**Solução:**
```bash
chmod 755 /home/seu_usuario/public_html/logs/
chmod 644 /home/seu_usuario/public_html/logs/app.log
```

---

### **Logs não aparecem no visualizador**

**Solução:**
1. Verifique se a pasta `/logs/` existe
2. Verifique se `Logger.php` está carregado
3. Teste com `test_logger.php`
4. Verifique permissões

---

### **view_logs.php retorna erro 500**

**Solução:**
- Verifique se o caminho do `require_once` está correto
- Linha 54: `require_once __DIR__ . '/includes/Logger.php';`
- Se sua estrutura for diferente, ajuste o caminho

---

## 🎯 PRÓXIMOS PASSOS

1. ✅ Instalar arquivos conforme instruções acima
2. ✅ Testar com `test_logger.php`
3. ✅ Fazer teste de pagamento com cartão no app
4. ✅ Acessar `view_logs.php` para ver logs detalhados
5. ✅ Enviar screenshot dos logs para análise

---

## 📞 SUPORTE

Se tiver qualquer dúvida ou erro, me envie:
1. Screenshot do erro
2. Conteúdo do log (se houver)
3. Estrutura de pastas do seu servidor

---

**Boa sorte! 🍺**
