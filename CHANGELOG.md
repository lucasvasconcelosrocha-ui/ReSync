# 🚀 Changelog Oficial — ReSync

Todas as mudanças notáveis deste projeto serão documentadas neste arquivo.

---

## [1.0.30] - 2026-11-06

### ⚡ Destaques da Versão (Os 5 Pilares)

#### 1. 🚀 Google Drive Turbo (Streaming IOStream sem OOM)
- **Dynamic Chunking inteligente:** Adaptação automática do tamanho dos blocos de upload para 16 MB em conexões Wi-Fi de alta velocidade, 8 MB em conexões balanceadas ou com aquecimento moderado, e 4 MB em dados móveis.
- **Streaming contínuo de 64 KB:** Substituição de alocações pesadas de `ByteArray` por `StreamingFileRequestBody`, lendo diretamente do disco via `RandomAccessFile` em blocos de 64 KB. Elimina 100% dos riscos de `OutOfMemoryError` mesmo ao transmitir backups de dezenas de gigabytes.
- **Conexões HTTP/2 multiplexadas & Connection Pool:** Centralização de todas as transferências de nuvem no singleton `GDriveNetworkClient`, com `ConnectionPool` otimizado (10 conexões ociosas mantidas por 5 min) e `Dispatcher` configurado (maxRequests = 32, maxRequestsPerHost = 16), eliminando re-handshakes TLS custosos.
- **Cache persistente de pastas e resolução em paralelo:** Cache persistente dos IDs de diretórios (`ReSync`, subpastas de backups rápidos, arquivos e pastas) indexado pelo e-mail da conta Google via `SharedPreferences`, associado à resolução concorrente em segundo plano com `coroutineScope { async { ... } }`.
- **Buffer de download turbo de 128 KB:** Aumento do buffer de recepção de stream para o arquivo temporário `.part`, reduzindo interrupções de I/O em discos UFS rápidos.

#### 2. 🛡️ Modo Universal Sem Root (User-Space Seamless)
- **Seleção e restauração completas sem bloqueios:** Quando o dispositivo opera sem acesso de superusuário (`isRootGranted == false`), a tela de aplicativos mantém APKs base, split APKs, recursos extras e dados OBB 100% operacionais e selecionáveis.
- **Badge clara de proteção:** Pastas internas do sistema restritas ao sandbox (`/data/data` e `/data/user_de`) são desativadas com a badge elegante `"Requer acesso avançado"`.
- **Instalação nativa via PackageInstaller:** Restauração automatizada de múltiplos split APKs em aparelhos sem root utilizando a API oficial do Android (`PackageInstaller.Session`).
- **Limpeza de cache orientada:** Limpeza de diretórios acessíveis do usuário e atalho com botão direto `"Config"` para abrir os Detalhes do Aplicativo nas Configurações do Sistema (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Restauração de DNS privado assistida:** Captura amigável de `SecurityException`, cópia instantânea do hostname para a Área de Transferência e abertura da tela nativa de configurações de rede do Android.
- **Relatório informativo de Wi-Fi e Bluetooth:** Exportação clara e transparente dos dispositivos pareados conhecidos e do SSID conectado atual, esclarecendo as proteções de chaves criptográficas do Android Keystore/WPA3.
- **Restauração de SMS com alternância de app padrão:** Suporte à requisição temporária da função padrão de mensagens (`RoleManager.ROLE_SMS` / `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT`), permitindo a escrita integral do histórico no banco `Telephony` e oferecendo restauração do app de mensagens original ao finalizar.

#### 3. 💬 Linguagem Clara, Natural e Amigável
- **Humanização de etapas e notificações:** Remoção de jargões técnicos herméticos como "Descriptografando container...", "Verificando integridade...", "Container", "Checksum" e "Split APKs".
- **Comunicação natural:** Substituição por frases claras em português: *"Verificando arquivo de backup..."*, *"Desbloqueando arquivo de backup..."*, *"Instalando aplicativos..."*, *"Componentes do app"*, *"Arquivos adicionais e mídia"*, *"Pastas do Sistema"* e *"Redes salvas"*.

#### 4. ⚙️ Gestão de Processos e Root Otimizada
- **Sessão global persistente com LibSU Master Shell:** Configuração em `ReSyncApplication.onCreate()` via `Shell.setDefaultBuilder()` com a flag `FLAG_MOUNT_MASTER` e timeout seguro de 15 segundos. Evita concorrência e conflitos de sucompat entre módulos.
- **Blindagem contra Command Injection:** Escape seguro de caminhos via `ShellSecurityUtils.shellEscape()` em comandos de soma criptográfica e verificações de arquivos.
- **Otimização de leitura de hardware e térmica:** Throttling in-memory de 3000 ms com cache de arquivos de zonas térmicas válidas e priorização de leitura direta em userspace de `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` antes de invocar subshells root.

#### 5. 📦 Otimização de Empacotamento e Redução de Tamanho
- **Filtro de recursos linguísticos:** Configuração de `resourceConfigurations += listOf("pt", "pt-rBR")` no `build.gradle.kts`, eliminando centenas de tabelas de tradução não utilizadas do arquivo `resources.arsc`.
- **Exclusão de arquivos mortos do APK:** Remoção de metadados desnecessários no bloco de empacotamento (`DebugProbesKt.bin`, `version-control-info.textproto`, `DEPENDENCIES`, `INDEX.LIST`, regras de dialetos fonéticos do Commons Codec, etc.).
- **Limpeza de drawables órfãos:** Exclusão de imagens e vetores legados sem referências no projeto.
- **Modal de novidades in-app:** Exibição automática no padrão Bento UI ao abrir a versão 1.0.30, com controle de visualização persistido no `SharedPreferences` (`last_seen_changelog_version`).
