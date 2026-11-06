# 🚀 Changelog Oficial — ReSync

Todas as mudanças notáveis deste projeto são documentadas neste arquivo.

---

## [1.0.32] - 2026-11-06

### 🛡️ Correções Críticas de Segurança & Integridade de Pastas
- **Proteção Total da Pasta de Origem (Prevenção de Perda de Dados):**
  - Corrigida rotina de higiene pós-compactação que excluía arquivos de origem em backups de diretórios locais (`/sdcard/...`, `ksu`, `Documents`).
  - Todo o ciclo de vida temporário isolado estritamente em `cacheDir/resync_tmp/<id>` com validação canônica rigorosa antes de qualquer exclusão (`safeDeleteStaging`).
  - Bloqueio definitivo contra exclusão acidental de caminhos protegidos, pastas de origem do usuário ou diretórios externos ao sandbox temporário.
- **Restauração de Pastas Sem Duplicação de Subpasta (`ksu/ksu/...`):**
  - Padronização no mapeamento de arquivos em containers e descompactação de pastas.
  - Implementado `resolveFolderContentRoot` com detecção inteligente de retrocompatibilidade para desembrulhar backups legados que possuíam pasta raiz duplicada.
  - Remoção completa de `find -mindepth 1 -delete` para diretórios de usuário: restauração segura via mesclagem atômica sem perda de arquivos preexistentes não relacionados.
- **Suíte de Testes Automatizados de Pastas:**
  - Adicionado `FolderBackupRestoreTest` com validação de hash SHA-256 da origem pós-backup, bloqueio de deleção externa e garantia de estrutura idêntica na restauração.

---

## [1.0.31] - 2026-11-06

### ✨ Novidades
- **Suporte Universal Sem Root Completo:**
  - Backup e restauração funcionais para qualquer dispositivo Android mesmo sem permissões de superusuário (`Root`).
  - Extração e arquivamento de APKs base, split APKs, dados OBB e mídias públicas do usuário através do userspace nativo.
  - Instalação automatizada de APKs divididos (splits) via API nativa `PackageInstaller.Session`.
  - Fallbacks assistidos para configurações de sistema:
    - **DNS Privado:** Cópia instantânea do hostname para a Área de Transferência e atalho para a tela de configurações de rede.
    - **Wi-Fi Conectado:** Arquivamento das informações da rede Wi-Fi atualmente conectada (`SSID`, `BSSID`, link speed, frequência) em `system/wifi/wifi_connected_network.json`.
    - **Bluetooth:** Relatório descritivo dos dispositivos pareados com atalho para emparelhamento.
  - Restauração de SMS com solicitação temporária de app padrão via `RoleManager.ROLE_SMS` / `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` e reversão automática ao concluir.
- **Modal de Novidades Bento UI In-App:**
  - Diálogo no estilo Dark OLED / Bento UI exibido automaticamente na primeira inicialização da versão 1.0.31.
  - Apresentação visual e categorizada dos 5 pilares fundamentais da versão.
  - Persistência do estado de visualização (`last_seen_changelog_version`) gravada com segurança no toque em *"Entendi / Continuar"*.

### ⚡ Melhorias de Desempenho
- **Google Drive Turbo (Streaming IOStream de Alta Velocidade):**
  - **Dynamic Chunking Inteligente:** Uploads particionados adaptativos de 16 MB em conexões Wi-Fi ultrarrápidas, 8 MB em conexões balanceadas ou sob calor, e 4 MB em dados móveis.
  - **Streaming de 64 KB direto do disco:** Leitura com `RandomAccessFile` através de `StreamingFileRequestBody`, eliminando arrays `ByteArray` pesados na memória e prevenindo 100% de `OutOfMemoryError`.
  - **HTTP/2 Multiplexado e Connection Pool:** Todas as chamadas de API e uploads migrados de `HttpURLConnection` para o singleton `GDriveNetworkClient` com `ConnectionPool` otimizado (10 conexões ociosas persistidas) e `Dispatcher` de alta concorrência (32 requisições máximas, 16 por host).
  - **Cache Persistente de Pastas:** Armazenamento em disco dos IDs de pastas no Google Drive associado ao e-mail da conta Google, reduzindo drasticamente buscas repetidas na nuvem.
  - **Buffer Turbo de Download:** Buffer de recepção ampliado para 128 KB em downloads de containers da nuvem.
- **Otimização de Empacotamento do APK (Redução > 750 KB):**
  - **Conversão de Imagens para WebP:** Otimização do asset `logo_resync` de 356 KB para WebP com qualidade 95 (~35 KB), economizando ~190 KB.
  - **Filtro de Localização:** Inclusão de `resourceConfigurations += listOf("pt", "pt-rBR")` para descartar centenas de strings e traduções não utilizadas de bibliotecas de terceiros no `resources.arsc`.
  - **Exclusão de Dependências Mortas:** Limpeza no empacotamento de arquivos redundantes (`DebugProbesKt.bin`, `version-control-info.textproto`, `DEPENDENCIES`, `INDEX.LIST`, arquivos fonéticos e chaves legado).
- **Eficiência de Leitura Térmica e de Hardware:**
  - Throttling de 3.000 ms com cache em memória para sensores térmicos e leitura direta em userspace via `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` antes de invocar subshells.

### 🐛 Correções
- **Gestão de Sessão LibSU e Compatibilidade com KernelSU (sucompat):**
  - Inicialização Master Shell configurada globalmente com `FLAG_MOUNT_MASTER` e timeout de 15s no companion object `init` e na primeira linha de `ReSyncApplication.onCreate()`.
  - Proteção contra fechamento acidental de sessões root ativas em `PermissionsRepository.checkRoot` e `CapabilityManager.requestRootAccess`, eliminando perda de sessão no KernelSU.
  - Substituição de chamadas diretas `Runtime.getRuntime().exec(arrayOf("su", ...))` por comandos gerenciados via Master Shell persistente do LibSU em `CapabilityManager`, `BackupEngine`, `RestoreEngine` e `StorageExplorerListComponent`.
  - Controle de pausa, retomada e cancelamento de processos (`pkill`) migrado para `Shell.cmd(...).exec()`.
- **Migração Completa para OkHttp no Google Drive:**
  - Eliminação de `HttpURLConnection` em 10 métodos do `DriveFileManager` e nas rotinas `initiateResumableSession` e `queryCurrentServerByteOffset` do `UploadManager`.
- **Linguagem Amigável e Acessível:**
  - Substituição de termos herméticos e siglas por linguagem compreensível em português claro: *"Desbloqueando arquivo de backup..."*, *"Verificando arquivo de backup..."*, *"Instalando aplicativos..."*, *"Pastas do Sistema"*.

### 🔒 Segurança
- **Blindagem contra Command Injection:**
  - Sanitização e escape seguro de caminhos via `shellEscape()` em todas as chamadas de soma de verificação criptográfica SHA-256 e operações de arquivo.
- **Criptografia e Validação Rígida:**
  - Preservação da criptografia autenticada AES-256-GCM via Android KeyStore para todos os containers de dados pessoais e credenciais.
