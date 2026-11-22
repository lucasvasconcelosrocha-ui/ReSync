# 🚀 Changelog Oficial — ReSync

Todas as mudanças notáveis deste projeto são documentadas neste arquivo.

---

## [1.0.43] - 2026-11-22

### 🎬 Listagem Real de Arquivos em Vídeos, Áudio e Imagens (Armazenamento)
- **Eliminação do Bug de Categoria e Acentuação:** Implementação do enum `StorageCategoryKey` e resolvedor de chaves insensível a acentuação e idioma, corrigindo a falha em que "Vídeos" e "Áudio" (com acentos) não encontravam correspondência na busca.
- **Busca em Tempo Real no MediaStore:** Consulta completa a `MediaStore.Video`, `MediaStore.Audio` e `MediaStore.Images` por `SIZE DESC`, com suporte a caminhos relativos em caso de Scoped Storage.
- **Varredura Profunda de Fallback:** Varredura em pastas prioritárias (`DCIM`, `Pictures`, `Movies`, `Music`, `Download`, `Recordings`, `WhatsApp Media`, etc.) cobrindo extensões de vídeo, áudio e imagem caso o MediaStore retorne dados incompletos.
- **Feedback Visual na UI:** Indicador discreto de progresso durante a busca assíncrona e garantia de que nenhuma categoria com tamanho > 0 MB exiba "Nenhum arquivo encontrado.".

### 🔐 Auto-Concessão 100% Automática via Root (Zero Diálogos Manuais)
- **Eliminação de Diálogo Precoce de Notificações:** Removida a solicitação de `POST_NOTIFICATIONS` na inicialização do app antes da checagem de Root; caso o dispositivo possua Root, a concessão ocorre silenciosamente via shell `su` sem exibir diálogos na tela.
- **Concessão Abrangente em Lote Único:** Execução automática no exato momento da confirmação de Root e no botão `REQUEST ALL PERMISSIONS`, aplicando:
  - Todas as permissões de manifesto (`pm grant $pkg $perm`)
  - Permissões especiais e AppOps (`MANAGE_EXTERNAL_STORAGE`, `GET_USAGE_STATS`, `REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATION`, `RUN_IN_BACKGROUND`, etc.)
  - Otimização de bateria (`deviceidle whitelist`)
  - Inicialização automática no Xiaomi HyperOS / MIUI (`appops 10008` e `AUTO_START`)
- **Atualização Instantânea da UI:** Reavaliação automática de todos os estados, refletindo badges em verde (`Granted` / `Concedido`) imediatamente e internacionalização 100% via `stringResource(...)`.

### 🏷️ Versão Dinâmica no Modal de Changelog & Status Bar Padding
- **Versão em Tempo Real:** Cabeçalho e controle de exibição do modal de novidades utilizam dinamicamente `BuildConfig.VERSION_NAME` em vez de strings hardcoded.
- **Proteção da Barra de Status:** Adicionado `Modifier.statusBarsPadding()` e padding superior para prevenir qualquer sobreposição com ícones da barra de status ou câmera frontal.

---

## [1.0.42] - 2026-11-22

### 📂 Restauração do Card 'Outros' & Painel Expansível
- **Clique Interativo Restaurado:** Restabelecido o `Modifier.clickable` no card da categoria **Outros / Others** em `StorageDetailsScreen.kt`, alinhado às categorias Documentos, Vídeos, Áudio e Imagens.
- **Lista de Arquivos Expansível:** Ao tocar em Outros, o painel expansível inferior é aberto exibindo os arquivos dessa categoria com opção de fechamento imediato via botão `X`.
- **Escaneamento Otimizado:** Implementada busca em MediaStore para arquivos não-multimídia acima de 512KB e varredura de fallback para extensões comuns (`.zip`, `.rar`, `.7z`, `.tar`, `.gz`, `.apk`, `.bin`, `.iso`, `.img`, `.obb`, `.dat`, `.bak`).

### 🧹 Atalho de Armazenamento na TopBar da Dashboard
- **Ícone de Vassoura/Limpeza:** Adicionado o ícone `Icons.Default.CleaningServices` à direita na TopBar da `DashboardScreen.kt`, fornecendo acesso com 1 toque à tela de Armazenamento.
- **Espaçamento Seguro:** Ajustado o espaçamento entre ícones para `10.dp`, prevenindo quebras de linha ou sobreposições mesmo em aparelhos com telas compactas ou fontes ampliadas.

### 📱 Correção da Lista de Aplicativos Instalados
- **Eliminação do Bug de Categoria:** Corrigida a validação de categoria que causava exibição indevida de *"Nenhum arquivo encontrado"* ao abrir o card Aplicativos em português e inglês.
- **Listagem Completa & Ordenação:** População completa dos aplicativos instalados (`installedApps`) ordenados por tamanho decrescente, exibindo ícone real (`RealAppIcon`), nome do app, pacote, badge de cache formatado e tamanho total.

---

## [1.0.41] - 2026-11-22

### 🚨 Correção de Estabilidade & Crash de Toast em Thread Secundária
- **Implementação do `Context.showSafeToast`:** Eliminação completa do crash fatal `NullPointerException: Can't toast on a thread that has not called Looper.prepare()` no Android 17 (API 37), despachando a exibição via `Handler(Looper.getMainLooper())` sempre que invocado fora da Main Thread.
- **Isolamento de Callbacks de Nuvem:** `MainViewModel.refreshActiveAccountData` e `refreshLegacyGoogleDriveData` agora invocam lambdas de conclusão (`onComplete`) estritamente em `withContext(NonCancellable + Dispatchers.Main.immediate)`.
- **Substituição Abrangente:** Todas as chamadas de `Toast.makeText` em telas e componentes com operações assíncronas (`CloudSyncCenterScreen`, `StorageDetailsScreen`, `ActiveBackupsScreen`, `AccountScreen`, `GoogleDriveAccountsBottomSheet`) foram migradas para `showSafeToast`.

### ⚡ Root Permanente & Concessão Automática de Permissões
- **Eliminação do Falso 'Permissão Pendente':** Corrigido em `PermissionsRepository.kt` o comportamento onde checagens passivas de root (`!isInteractive`) resetavam indevidamente `isRootGranted = false` ao retornar ao aplicativo.
- **Validação Multinível em `CapabilityManager.verifyRootAccess`:** Checagem robusta de `Shell.isAppGrantedRoot()`, verificação de integridade da shell ativa em cache, teste com `Shell.cmd("id")` e fallback resiliente via `ProcessBuilder("su", "-c", "id")`.
- **Concessão Automática (`grantAllPermissionsViaRoot`):** Concede dinamicamente todas as permissões de runtime do manifesto via `pm grant`, além de habilitar `MANAGE_EXTERNAL_STORAGE`, `GET_USAGE_STATS`, `REQUEST_INSTALL_PACKAGES`, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `RUN_IN_BACKGROUND` e inclusão na whitelist do Doze (`dumpsys deviceidle whitelist`).

### 📱 Armazenamento Otimizado & Grade Balanceada
- **Card 'Outros' Otimizado:** Card transformado em elemento puramente informativo, não clicável, em largura total (`fillMaxWidth()`) abaixo das 3 linhas de categorias, preservando 100% da identidade Bento UI OLED.
- **Remoção do Card 'Cache':** Eliminada a redundância do card de Cache na grade geral (pois a tela já dispõe de painel e navegação dedicados para limpeza de cache).
- **Grade Balanceada (3 Pares de 2):** Linha 1 (Apps / Imagens), Linha 2 (Vídeos / Áudio), Linha 3 (Documentos / Sistema).

### ☁️ Menu FAB & Envio de Backups Existentes
- **Scrim Escuro Semi-transparente:** Adicionado fundo escuro (`Color.Black.copy(alpha = 0.65f)`) com clique externo para dispensar o menu FAB.
- **Botões FAB Sólidos Opacos:** Estilizados com container sólido `Color(0xFF161F2E)`, borda de 1dp `Color(0xFF263244)` e elevação de 6dp.
- **Validações Pré-Ação:** Bloqueio e aviso prévio caso nenhuma conta Google esteja conectada e verificação de pastas vazias via `DocumentFile`.
- **Inclusão Automática do `.index.json`:** Ao enviar backups locais existentes para o Google Drive, o arquivo de índice `.index.json` correspondente é automaticamente adicionado à fila de envio.

### 🎨 Polimento Visual Bento OLED & UI de Backup
- **Badge Verde Neon da Versão:** Adicionado badge `v1.0.41` com estilo Bento neon no cabeçalho de `ActiveBackupsScreen`, alinhado à `DashboardScreen`.
- **Eliminação de Jargões (`#TIVE` / `0xTIVE_RESYNC`):** Substituídos pelo título real da rotina de backup em execução e formato padronizado `ID: RS-yyyyMMdd-HHmm`.
- **Ajuste de Rodapé Pós-Backup:** Botão primário verde ajustado para linha única `"Concluir"` (PT) / `"Done"` (EN) (`btn_done`) e botões com altura padronizada de 48dp, espaçamento de 12dp e `navigationBarsPadding`.

### 🛡️ Correção Crítica do Crash ao Iniciar Backup (Zero Backup Crash Fix)
- **Blindagem do `StatFs`:** Protegidas todas as invocações de `android.os.StatFs` em `BackupEngine.kt` e `RestoreEngine.kt` com criação preventiva de diretórios (`mkdirs()`), tratamento seguro em bloco `try/catch` e fallback resiliente para `context.filesDir.absolutePath`, evitando `IllegalArgumentException: Invalid path`.
- **Invocação Imediata de `startForeground`:** Implementada chamada com `ServiceCompat.startForeground` e tipo `FOREGROUND_SERVICE_TYPE_DATA_SYNC` na primeira linha absoluta de `onStartCommand` em `BackupForegroundService.kt` e `RestoreForegroundService.kt`, garantindo conformidade rigorosa com Android 14 e 15 (API 35).
- **Proteção para Dispositivos Sem Root:** Tratamento seguro para `isRootActive == false` em cópias de APK base e splits, impedindo chamadas `Shell.cmd` desnecessárias ou falhas críticas em arquivos inacessíveis.
- **Prevenção de Quedas de Coroutines:** Adicionado `CoroutineExceptionHandler` a todos os escopos assíncronos (`serviceScope` em serviços em primeiro plano e `repositoryScope` no `BackupRepository`), além de captura abrangente com log via `AppLogger.e` e emissão de estado de erro para a interface em vez de crash da JVM.
- **Resiliência em `UploadManager` & `ActiveBackupsScreen`:** Tratamento para `CloneNotSupportedException` no clone de digest MD5 e proteção contra `NaN` e divisão por zero em cálculos de progresso e pesos do Jetpack Compose, além de cópias defensivas `.toList()` em iterações de logs.

### 🔤 Eliminação Total de Mojibake (UTF-8) & Sincronização Perfeita de Idiomas
- **Correção de 100% da Dupla Codificação:** Eliminadas todas as ocorrências de caracteres corrompidos por dupla codificação UTF-8 (`Ã§`, `Ãµ`, `Ã£`, `Ãª`, `Ãº`, `Ã¡`, `Ã©`, `â€¢`, `â€”`, etc.) em arquivos de recursos XML e classes Kotlin.
- **Paridade Estrita de Chaves (1.323 Chaves):** Sincronização perfeita 1:1 entre `values/strings.xml`, `values-en/strings.xml`, `values-pt/strings.xml` e `values-pt-rBR/strings.xml`.
- **Auditoria Rigorosa do Lint:** Resolução de todos os alertas de `StringFormatInvalid` com escape adequado de `%` (`%%`) e adição da permissão `android.permission.VIBRATE` no `AndroidManifest.xml`.

---

## [1.0.39] - 2026-11-20

### 🛡️ Fim Definitivo dos Crashes ao Abrir Abas (Zero Tab Crash Fix)
- **Eliminação do `ClassCastException` Fatal:** Corrigido o crash imediato que ocorria ao abrir abas e diálogos (como Account, Settings, BottomSheets de Google Drive), causado pela substituição indevida do `LocalContext` no Compose por `createConfigurationContext` (`ContextImpl`).
- **Extensão Segura `Context.findActivity()`:** Implementada função `tailrec fun Context.findActivity(): Activity?` em `LocaleHelper.kt` e substituídos 100% dos casts diretos inseguros (`as Activity` / `as ComponentActivity`) em todas as telas e componentes.
- **Isolamento de Contexto:** Configuração de idioma aplicada de forma transparente via `attachBaseContext` na `MainActivity` e `ReSyncApplication`, preservando o `LocalContext` como a própria Activity real.

### 🌐 Purificação Total em Inglês & Sincronização Perfeita (100% English Localization)
- **Varredura Completa de Strings Hardcoded:** 100% dos textos remanescentes em português em composables (`Text`, `title`, `label`, `placeholder`, `contentDescription`), diálogos, gráficos (`StorageDonutChart`, `CloudActivityChart`, `BackupHistoryLineChart`, `AppStatusDistributionChart`), notificações e ViewModels foram externalizados e traduzidos com precisão para o Inglês.
- **Sincronização Estrita (1.307+ Chaves):** Paridade exata 1:1 entre `values/strings.xml` (Inglês padrão), `values-en/strings.xml`, `values-pt/strings.xml` e `values-pt-rBR/strings.xml` com zero chaves faltantes.
- **Formatação Segura:** Todos os apóstrofos (`\'`), e-comerciais (`&amp;`) e especificadores de formato posicional (`%1$s`, %1$d) auditados com zero risco de `IllegalFormatConversionException`.

### 🧹 Limpeza de Chaves de Bibliotecas de Terceiros
- **Remoção de 52 Chaves Externas Conflitantes:** Removidas chaves internas de bibliotecas (`androidx_startup`, `common_google_play_services_*`, `m3c_*`, `default_error_message`, etc.) dos arquivos XML de strings, permitindo que o Gradle e o Android utilizem as traduções oficiais sem divergências de merge.
- **Otimização de Builds:** Adicionada diretiva `resourceConfigurations += listOf("en", "pt-rBR", "pt")` no `app/build.gradle.kts`.

---

## [1.0.38] - 2026-11-20

### 🌐 Inglês como Idioma Padrão de Fábrica & Localização 100% Completa
- **Inglês como Idioma Padrão:** O aplicativo agora inicia obrigatoriamente em Inglês (`Locale.ENGLISH`) em novas instalações ou quando nenhuma preferência foi definida.
- **Reatividade Instantânea em Compose:** Suporte a troca dinâmica de idioma em tempo real via `LocaleHelper.currentLanguageFlow` e `CompositionLocalProvider` em `MainActivity.kt`, atualizando 100% das telas sem reiniciar o aplicativo.
- **Sincronização Estrita de Recursos XML:** 311 chaves XML sincronizadas 1:1 entre `values/strings.xml` (Inglês padrão), `values-en/strings.xml`, `values-pt/strings.xml` e `values-pt-rBR/strings.xml`.
- **Configuração de Idiomas por App no Android 13+:** `locales_config.xml` configurado com `en`, `en-US`, `pt`, `pt-BR`.

### 🛡️ Correção Crítica na Restauração Individual de Aplicativos (Bugfix)
- **Eliminação da Falsa Conclusão Instantânea:** Corrigido problema crítico onde a tela de restauração individual abria diretamente em estado "Concluída!" sem executar a restauração devido a estados persistidos de execuções anteriores em `swiftvault_restore_repository_prefs`.
- **Nova Máquina de Estados Dedicada (`IndividualRestoreUiState`):** Implementada no `MainViewModel` com transições estritas (`Idle` ➔ `SelectingBackup` ➔ `Running` ➔ `Completed` / `Error`), desacoplando a UI do estado global persistido do repositório.
- **Verificação Prévia de Backups:** Verificação obrigatória de pontos de restauração antes de abrir o fluxo; se não houver backups, exibe aviso informativo e bloqueia operações inválidas.

### ✨ Animação Futurista de Conclusão (`FuturisticCompletionOrb`) & Barra Inferior Inteligente
- **Orb Neon Interativo com Canvas:** Anéis orbitais duplos desacoplados com gradientes neon (`#00FF88` ➔ `#00E5FF`), pulso de radar sensível ao toque, feedback háptico (`HapticFeedbackType.LongPress`) e animação progressiva de traçado do checkmark com `PathMeasure`.
- **Barra de Ações Contextual Pós-Backup:** Quando a operação termina, os botões de Pausar/Abortar são substituídos automaticamente por "Done / Back to Home" (primário) e "View Backups" (secundário), além de badge neon de integridade SHA-256.

### 💻 Overhaul no Console de Logs & Badge Neon no Dashboard
- **Layout de Logs em Largura Total:** Migração das linhas espremidas em 3 colunas para formato monoespaçado de largura total com cabeçalho de timestamp/tag e mensagem com quebra natural de linha (`11.sp` / `lineHeight = 15.sp`).
- **Filtro de Ruído Diagnóstico:** Logs internos do tipo `[DIAGNÓSTICO]` e `preload` são filtrados do console primário.
- **Ajuste da TopBar:** Aplicação correta de padding superior (`16.dp` e `innerPadding`), eliminando sobreposição da barra de status e botões cortados.
- **Badge Neon Dinâmico:** Novo badge neon verde (`Color(0xFF00FF88)`) ao lado do logo ReSync no Dashboard indicando `v1.0.38` via `BuildConfig.VERSION_NAME`.

### 💬 Comunidade Oficial no Telegram (`t.me/resync_backup`)
- **Modal de Boas-Vindas no Primeiro Acesso:** Modal no estilo Bento OLED convidando novos usuários a ingressarem no grupo oficial do Telegram, com controle via SharedPreferences (`has_seen_telegram_welcome_modal_v1`).
- **Card Permanente nas Configurações:** Novo card estético nas Configurações permitindo acesso direto à comunidade a qualquer momento.

---

## [1.0.37] - 2026-11-17

### 🌐 Suporte Completo ao Idioma Inglês (Full English Language Support)
- **Internacionalização Integral (100% i18n):**
  - Tradução completa e profissional de 100% das telas, menus, diagnósticos, diálogos e notificações em segundo plano para Inglês (English) e Português (Brasil).
  - Blindagem total de chaves XML com correspondência exata 1:1 entre `values/strings.xml` e `values-en/strings.xml`, preservando especificadores de formato e termos técnicos essenciais (Root, Shizuku, APK, SHA-256, AES-256-GCM, Google Drive, HTTP/2).
- **Seletor Rápido de Idioma nas Configurações:**
  - Novo card interativo de seleção de idioma na tela de Configurações no padrão estético Bento UI.
  - Suporte a 3 modos de seleção: Padrão do Sistema (System default), Português (Brasil) e English (US / International).
  - Recriação instantânea da Activity e aplicação imediata sem necessidade de reiniciar o aplicativo.
- **Integração com Android 13+ (Per-App Language Preferences):**
  - Configuração nativa de `android:localeConfig="@xml/locales_config"` permitindo alternar o idioma tanto pelo app quanto pelo painel de Idiomas por App das configurações do sistema operacional Android 13+.
  - `LocaleHelper` com sincronização síncrona via `LocaleManager.setApplicationLocales`.

### ⚡ Google Drive Turbo & Blindagem de Conexão
- Streaming HTTP/2 direto via OkHttp sem SDKs pesados legados.
- Buffers de alta velocidade (256 KB) e upload em chunks dinâmicos com integridade MD5 em passagem única.
- Aquisição resiliente de `WifiLock` de baixa latência e `WakeLock` com liberação segura garantida.
- Inicialização imediata de `ForegroundService` com tolerância zero a timeouts e congelamentos ANR.

---

## [1.0.34] - 2026-11-07

### ⚡ Google Drive Turbo 2.0 (Velocidade Máxima Upload & Download)
- **Upload com Streaming Otimizado & Flush Terminal Único:**
  - Buffer de leitura de streaming aumentado para **256 KB** (`262_144` bytes) em `StreamingFileRequestBody`.
  - Removida a chamada `sink.flush()` de dentro do loop `while` de leitura, eliminando a fragmentação de quadros HTTP/2 e pacotes TLS. Chamada única terminal ao final da transmissão do chunk.
  - Throttle de progresso implementado com janela de 200 ms (`SystemClock.elapsedRealtime()`), eliminando milhares de recomposições e callbacks desnecessários por chunk.
- **MD5 em Passagem Única (Single-Pass) & Chunks de até 32 MB:**
  - Eliminação da releitura de disco pós-chunk: o hash MD5 agora é calculado inline em passagem única (`chunkDigest.update()`) dentro do próprio loop de envio, sincronizando o digest ao confirmar o status HTTP 308.
  - Elevação do tamanho máximo do chunk dinâmico para **32 MB** em Wi-Fi de alta velocidade sem restrição térmica (16 MB em Wi-Fi moderado e 4 MB em dados móveis/restrição térmica via `CloudThermalGovernor`).
- **Download Paralelo 6x & Redução de Contenção SQLite:**
  - Aumento das conexões simultâneas de download em estado térmico normal de 4 para **6 fluxos paralelos**.
  - Buffer de leitura de download ampliado para **256 KB**.
  - Separação entre a frequência de atualização da UI (250 ms) e a frequência de persistência no Room (`cloudTransferDao.updateProgress`), limitada a cada 1500 ms com `AtomicLong` compartilhado, extinguindo a disputa de I/O de disco entre as corrotinas de download e o SQLite.
- **Migração Completa do `BackupIndexManager` para HTTP/2:**
  - Substituição de todas as chamadas legadas `HttpURLConnection` nos métodos `downloadRemoteIndexBytes`, `fetchRemoteRangeBytes`, `findEntryInRemoteCentralDirectory` e `fetchDriveFileMetadata` por chamadas `okhttp3.Request` executadas via pool compartilhado `GDriveNetworkClient.client`.
  - Configuração de alta vazão no OkHttp Dispatcher: `maxRequests = 64` e `maxRequestsPerHost = 32`.

### 🛡️ Shell Hardening & Blindagem no Explorer
- **Sanitização Universal com `ShellSecurityUtils.shellEscape`:**
  - Uniformização de toda e qualquer interpolação de caminho de arquivos e diretórios enviada para execução root (`su -c`) em `FileExplorerScreen.kt`, `StorageExplorerListComponent.kt` e `StorageRepository.kt`.
  - Prevenção absoluta contra falhas em diretórios com espaços, parênteses, apóstrofos ou metacaracteres shell.

### 🧹 Higiene de Disco & Prevenção de Falsos-Positivos
- **Limpeza Preventiva em `resync_tmp` antes do cálculo `StatFs`:**
  - Varredura e expurgo automático de arquivos residuais e órfãos (`*.part`, `*.tmp`, `staging_*`, `restore_*`) criados há mais de 1 hora na pasta `cacheDir/resync_tmp` em `BackupEngine` e `RestoreEngine` antes da verificação de espaço disponível via `StatFs`.

### 📦 Refinamento Geral, Remoção de Código Morto & Assets
- **Expurgo de Motores e Provedores Órfãos:**
  - Remoção definitiva de `UniversalRestoreManager`, `BackupSafetyWorker`, `IBackupEngine`, `RootBackupEngine`, `NormalBackupEngine`, `BackupEngineFactory` e provedores legados desacoplados.
- **Deduplicação de Imagens PNG:**
  - Exclusão de cópias redundantes de ícones em `drawable-nodpi`.
- **Limpeza de Textos Residuais:**
  - Atualização de textos e logs legados com o nome "SwiftVault" para "ReSync", preservando chaves criptográficas e banco Room retrocompatíveis.

---

## [1.0.33] - 2026-11-07

### 🛡️ Correções Finais de Integridade de Backup e Restauração
- **Blindagem Contra Exclusão da Origem (Defesa em Profundidade):**
  - Checagem canônica explícita impedindo exclusão de quaisquer arquivos residentes em `/storage/`, `/sdcard/`, `/data/media/` ou caminhos selecionados pelo usuário.
  - Eliminação de qualquer deleção de arquivos de entrada pós-empacotamento.
- **Restauração Completa e Unificada de Pastas:**
  - Resolução simultânea de arquivos soltos na raiz da pasta e de todas as suas subpastas (resolvendo o problema de arquivos como `Download/arquivo.mp3` ignorados quando coexistiam com subpastas).
  - Emissão dinâmica e granular de progresso arquivo a arquivo na tela de restauração.
  - Gravação de streams bufferizados de 64 KB com criação automática de diretórios-pai no modo Sem Root.
  - Execução segura via `cp -f` no modo Root aguardando término (`waitFor`/`exec`) e validação de tamanho de arquivo.
- **Reinicialização de Estado Residual na UI:**
  - Implementação de `resetRestoreState()` no `RestoreRepository` e `MainViewModel` para limpar SharedPreferences e `StateFlow`.
  - Correção da flag `isFinished` para prevenir que telas de restaurações anteriores apareçam prematuramente.
  - Adição do botão "Concluir e Voltar" na tela de resumo da restauração.
- **Testes Unitários:**
  - Novos testes automatizados cobrindo recuperação unificada de arquivos soltos e subpastas de backups legados e gravação via streams.

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
