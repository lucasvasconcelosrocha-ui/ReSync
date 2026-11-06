🚀 **O QUE HÁ DE NOVO NESTA VERSÃO (v1.0.32):**

🛡️ **Proteção Total da Pasta de Origem (Prevenção Absoluta de Perda de Dados)**
• **Origem 100% Intacta:** Corrigida rotina de higiene pós-empacotamento que excluía arquivos de origem em backups de diretórios locais (`/sdcard/...`, `ksu`, `Documents`). A origem nunca é modificada ou excluída.
• **Isolamento Estrito em `cacheDir/resync_tmp`:** Todas as operações temporárias de staging e extração residem estritamente dentro do diretório temporário exclusivo do app, com blindagem canônica contra exclusão externa.
• **Validação Canônica `safeDeleteStaging`:** Bloqueio definitivo contra exclusão acidental de caminhos protegidos ou diretórios raiz do usuário.

📂 **Restauração de Pastas Sem Duplicação de Subpasta**
• **Estrutura Idêntica ao Original:** Padronização completa na criação de containers e extração direta sem criação de subpastas aninhadas redundantes (`ksu/ksu/...`).
• **Retrocompatibilidade com Backups Legados:** Detecção automática e desembrulho de arquivos legados que possuíam pasta raiz duplicada.
• **Eliminação de Wipes Destrutivos:** Remoção completa de `find -mindepth 1 -delete` para diretórios de usuário: restauração atômica via mesclagem segura sem apagar arquivos preexistentes não relacionados.

🧪 **Suíte de Testes Automatizados de Pastas**
• Adição de testes unitários automatizados cobrindo preservação integral da origem pós-backup, bloqueio de exclusões fora do sandbox temporário e ausência de subpastas aninhadas na restauração.

🎨 **Nova Interface Bento UI (Dark Minimalista & OLED)**
• **Redesign Visual Completo:** Nova arquitetura visual em todas as telas do aplicativo com fundo Preto OLED profundo (`#05070A`), cards modulares Bento (`#0E121A`), bordas finas (*hairline*) e micro-interações fluidas.
• **Sincronização Global de Cores e Temas:** Padronização completa do `MaterialTheme` em 100% das telas, botões, indicadores e barras de progresso para respeitar dinamicamente a cor de tema escolhida pelo usuário.
• **Foto de Perfil Integrada:** Carregamento automático do avatar real da conta Google logada na barra superior em todo o aplicativo.

⚡ **Motor Universal de Backup & Nuvem Turbo**
• **Detecção Inteligente de Privilégios:** Alternância transparente e automática entre `RootBackupEngine` e `NonRootBackupEngine` conforme os acessos concedidos pelo usuário.
• **Google Drive Turbo:** Upload e download otimizados em blocos de 64 KB com chunks de 4 MB a 16 MB, conexão HTTP/2 unificada, cache persistente de pastas e verificação de integridade SHA-256.
