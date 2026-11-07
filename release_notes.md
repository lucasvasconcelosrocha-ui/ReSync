🚀 **O QUE HÁ DE NOVO NESTA VERSÃO (v1.0.33):**

🛡️ **Proteção Total da Pasta de Origem (Prevenção Absoluta de Perda de Dados)**
• **Origem 100% Intacta:** Blindagem canônica reforçada contra exclusão pós-empacotamento de qualquer arquivo residente em `/storage/`, `/sdcard/`, `/data/media/` ou caminhos selecionados pelo usuário.
• **Isolamento Estrito em `cacheDir/resync_tmp`:** Todas as operações temporárias de staging e extração residem estritamente dentro do diretório temporário exclusivo do app, com validação canônica antes de qualquer limpeza.
• **Validação Canônica `safeDeleteStaging`:** Bloqueio definitivo contra exclusão acidental de caminhos protegidos, origens selecionadas ou diretórios externos ao sandbox temporário.

📂 **Restauração Completa e Unificada de Pastas**
• **Resolução Simultânea de Raiz e Subpastas:** Resolução de arquivos soltos na raiz da pasta e de todas as suas subpastas (resolvendo o problema de arquivos soltos ignorados quando coexistiam com subpastas em backups legados e novos).
• **Progresso Dinâmico Arquivo a Arquivo:** Exibição em tempo real do arquivo sendo restaurado e contador de progresso na interface.
• **Gravação Bufferizada Sem Root:** Gravação via `FileInputStream` e `FileOutputStream` com buffers de 64 KB e criação automática de diretórios-pai (`parentFile?.mkdirs()`).
• **Fallback Root Seguro:** Execução via shell `cp -f` aguardando conclusão síncrona, validação de exit code (`isSuccess`) e verificação de integridade e tamanho do arquivo gravado.

🔄 **Correção de Gestão de Estado na Interface (UI)**
• **Reinicialização de Estado Residual:** Implementação de `resetRestoreState()` no `RestoreRepository` e `MainViewModel` para limpar SharedPreferences e `StateFlow` ao clicar em "RESTAURAR" ou inspecionar um backup.
• **Correção da Flag `isFinished`:** Eliminação da transição prematura de 1s para o resumo de restauração por estado residual de operações anteriores.
• **Botão "Concluir e Voltar":** Adição de botão explícito de fechamento na tela de resultado da restauração com limpeza garantida de estado.

🧪 **Suíte de Testes Automatizados**
• Adição de testes unitários em `FolderBackupRestoreTest` cobrindo restauração simultânea de arquivos soltos na raiz e subpastas em backups legados, gravação bufferizada e preservação de integridade de hashes SHA-256.

🎨 **Interface Bento UI (Dark Minimalista & OLED)**
• Fundo Preto OLED profundo (`#05070A`), cards modulares Bento (`#0E121A`), bordas finas e sincronização com `MaterialTheme`.
