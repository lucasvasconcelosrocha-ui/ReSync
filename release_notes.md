# 🚀 ReSync v1.0.42 Release Notes

---

## 🇧🇷 Português

### 🚀 O Que Há de Novo na Versão 1.0.42:

#### 1. 📂 Restauração do Card 'Outros' & Painel Expansível
- **Clique e Painel Interativo:** O card da categoria **Outros** na tela de Armazenamento volta a ser interativo, abrindo o painel expansível inferior de arquivos com botão `X` para fechar.
- **Busca de Arquivos Diversos:** Suporte nativo a arquivos de arquivos compactados e binários (`.zip`, `.rar`, `.7z`, `.tar`, `.gz`, `.apk`, `.bin`, `.iso`, `.img`, etc.) via MediaStore e escaneamento direto.

#### 2. 🧹 Atalho de Limpeza/Armazenamento na TopBar
- **Acesso Rápido com Vassoura:** Novo botão na TopBar da tela inicial com o ícone de limpeza/vassoura, levando diretamente à tela de Armazenamento e métricas de disco.
- **Layout Seguro de TopBar:** Ajuste de espaçamento horizontal (`10.dp`) para total compatibilidade com qualquer largura de display.

#### 3. 📱 Correção da Lista de Aplicativos Instalados
- **Fim da Lista Vazia em Aplicativos:** Corrigida a verificação localizada que causava o falso "Nenhum arquivo encontrado" ao tocar no card Aplicativos.
- **Detalhes Completos dos Apps:** Exibição com ícone real, nome, pacote, tamanho de cache se houver e tamanho total decrescente.

---

## 🇺🇸 English

### 🚀 What's New in Version 1.0.42:

#### 1. 📂 Restored 'Others' Card Click & Expandable File List
- **Interactive Others Card:** Tapping the **Others** card in Storage Details now expands the files panel with full close button `X` support.
- **Comprehensive Scanning:** MediaStore queries and directory fallback for diverse extensions (`.zip`, `.rar`, `.7z`, `.tar`, `.gz`, `.apk`, `.bin`, `.iso`, `.img`, etc.).

#### 2. 🧹 Storage Shortcut in Dashboard TopBar
- **Broom/Cleaning Icon:** Instant navigation to Storage Details from the Dashboard TopBar via the cleaning broom icon.
- **Narrow Screen Spacing:** Safe 10dp icon distribution ensuring zero wrapping or crowding.

#### 3. 📱 Fixed Installed Apps Category List
- **Resolved Empty Apps List:** Fixed locale matching bug where opening Applications showed "No files found".
- **Rich App Item Details:** Displays real app icons, titles, package name, cache badges, and formatted sizes sorted descending.

---

# 🚀 ReSync v1.0.41 Release Notes

---

## 🇧🇷 Português

### 🚀 O Que Há de Novo na Versão 1.0.41:

#### 1. 🛡️ Correção de Estabilidade & Crash de Toast em Thread Secundária
- **Fim do Crash de Toast em IO:** Implementada a utilidade `Context.showSafeToast` que despacha mensagens de Toast para o `Looper.getMainLooper()` quando invocadas a partir de threads de segundo plano (como `Dispatchers.IO`), eliminando o crash fatal ocorrido na sincronização da nuvem.
- **Isolamento de Callbacks de Nuvem:** `refreshActiveAccountData` e rotinas de rede executam seus callbacks em `Dispatchers.Main.immediate`.

#### 2. ⚡ Root Permanente & Concessão Automática de Permissões
- **Persistência de Status de Root:** Corrigida regressão em `PermissionsRepository` que desativava o status de root ao trocar de tela em checagens passivas.
- **Concessão Automática via Root:** Ao confirmar o privilégio de Superusuário, o ReSync agora concede automaticamente todas as permissões declaradas no manifesto, ajusta AppOps essenciais e inclui o app na lista branca do Doze em um único comando de shell de alto desempenho.

#### 3. 📱 Armazenamento Balanceado & Remoção de Cache Redundante
- **Grade 2x2 Equilibrada:** As categorias de armazenamento agora formam 3 pares consistentes (Apps/Imagens, Vídeos/Áudio, Documentos/Sistema) com o card "Outros" em largura total, não clicável, eliminando seções expansíveis desnecessárias.
- **Eliminação do Card de Cache:** Removido o card duplicado da grade para manter o foco no painel dedicado de limpeza de cache.

#### 4. ☁️ Menu FAB Otimizado & Envio de Backups Existentes
- **Scrim Escuro Semi-transparente:** Adicionado fundo escuro (`alpha = 0.65f`) para fechamento do menu FAB com um toque fora.
- **Inclusão do `.index.json`:** Ao enviar backups locais existentes para o Google Drive, o arquivo de índice correspondente é automaticamente enviado junto, garantindo restauração perfeita.

#### 5. 🎨 Polimento Visual & UI de Backup
- **Badge Verde Neon da Versão:** O cabeçalho superior da tela de backup ativo agora exibe o badge `v1.0.41` no mesmo padrão visual da Dashboard.
- **Eliminação de Jargões (`#TIVE` / `0xTIVE_RESYNC`):** Exibição do nome real da operação e identificador de sessão limpo `ID: RS-yyyyMMdd-HHmm`.
- **Botão Concluir em Linha Única:** Botão de ação pós-backup em linha única (`Done` / `Concluir`) e alinhamento visual de 48dp.

---

## 🇺🇸 English

### 🚀 What's New in Version 1.0.41:

#### 1. 🛡️ Stability Fix for Background Thread Toasts
- **Safe Toast Dispatcher:** Created `Context.showSafeToast` to eliminate the fatal `Can't toast on a thread that has not called Looper.prepare()` by ensuring all Toast invocations are safely dispatched to the Main Looper.
- **Main Thread Cloud Callbacks:** Wrapped all completion listeners in `withContext(NonCancellable + Dispatchers.Main.immediate)`.

#### 2. ⚡ Persistent Root & Automatic Permission Granting
- **Persistent Root Verification:** Fixed regression where passive root checks reset the granted status on app switch.
- **Automated Root Grants:** Automatically grants manifest runtime permissions, AppOps settings, and battery whitelist via Root shell upon superuser confirmation.

#### 3. 📱 Balanced Storage Overview & Redundancy Cleanup
- **Balanced 2x2 Bento Grid:** Structured 3 balanced card pairs (Apps/Images, Videos/Audio, Documents/System) with full-width, non-clickable "Others" card.
- **Removed Duplicate Cache Card:** Cleaned up redundant cache tile from the main storage breakdown.

#### 4. ☁️ Enhanced Cloud FAB Menu & Backup Bundle Upload
- **Dark FAB Scrim:** Added semi-transparent dark backdrop dismissable on outside touch with solid opaque FAB action items.
- **Automatic `.index.json` Enqueue:** Enqueues accompanying index metadata file alongside backup archives when uploading to Google Drive.

#### 5. 🎨 UI Refinement & Active Backup Polish
- **Neon Green Version Badge:** Integrated `v1.0.41` badge in the TopBar of `ActiveBackupsScreen` matching Dashboard.
- **Clean Real Identifiers:** Eliminated legacy `#TIVE` / `0xTIVE_RESYNC` placeholders in favor of actual backup titles and formatted session IDs.
- **Single-Line Done Button:** Clean, single-line "Done" / "Concluir" action button with standardized 48dp button heights.

---

## 🚀 Versões Anteriores / Previous Versions:

#### 1. 🛡️ Correção Crítica do Crash ao Iniciar Backup (Zero Backup Crash Fix)
- **Blindagem Completa de StatFs:** Proteção contra falhas `IllegalArgumentException` ao calcular espaço livre, com criação de diretórios (`mkdirs`) e fallback automático para o diretório interno do aplicativo.
- **Início Imediato do Serviço em Primeiro Plano:** Chamada imediata a `ServiceCompat.startForeground` com `FOREGROUND_SERVICE_TYPE_DATA_SYNC` na primeira linha de `onStartCommand`, blindando contra timeouts no Android 14 e 15.
- **Isolamento de Coroutines & Suporte a Não-Root:** Adicionados manipuladores globais `CoroutineExceptionHandler` em serviços e repositórios, além de desvio seguro de chamadas `su` quando o root não estiver concedido.

#### 2. 🔤 Eliminação Total de Mojibake (UTF-8) & Sincronização Perfeita
- **Purificação de Codificação:** Correção de 100% dos caracteres com dupla codificação (`Ã§`, `Ãµ`, `Ã£`, `â€¢`, etc.) nos arquivos de strings e fontes Kotlin.
- **Paridade 1:1 de Traduções:** 1.323 chaves sincronizadas com precisão milimétrica entre Inglês (`values`) e Português do Brasil (`values-pt-rBR`).
- **Resolução de Formatação de Strings:** Correção de placeholders e escape adequado de porcentagem (`%%`) aprovados sem erros no Lint.

---

## 🇺🇸 English

### 🚀 What's New in Version 1.0.40:

#### 1. 🛡️ Backup Launch Crash Fix & Service Hardening
- **StatFs Shielding:** Full protection against `IllegalArgumentException` during disk space calculation with directory verification and resilient fallback to internal app storage.
- **Immediate Foreground Service Startup:** First-line `ServiceCompat.startForeground` invocation with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` conforming strictly to Android 14 & 15 requirements.
- **Coroutine Fault Tolerance & Non-Root Fallback:** Global `CoroutineExceptionHandler` attached to service and repository scopes; graceful bypass of shell root commands when superuser access is unavailable.

#### 2. 🔤 Total Mojibake (UTF-8) Elimination & Parity
- **Encoding Cleanup:** 100% resolution of double-encoded UTF-8 characters (`Ã§`, `Ãµ`, `â€¢`, etc.) across all string bundles and Kotlin source files.
- **1:1 Strict Parity:** 1,323 keys synchronized between English default and Portuguese (Brazil).
- **Format Specifier Lint Cleanliness:** Proper percent escaping (`%%`) and clean Lint validation with zero formatting issues.

---

## [v1.0.39] Release Notes

---

## 🇧🇷 Português

### 🚀 O Que Há de Novo na Versão 1.0.39:

#### 1. 🛡️ Fim Definitivo dos Crashes ao Abrir Abas (Zero Tab Crash Fix)
- **Causa Raiz Eliminada:** Extirpado o erro fatal `ClassCastException: ContextImpl cannot be cast to Activity` que fechava o aplicativo ao abrir determinadas abas ou modais.
- **Navegação 100% Estável:** Substituição de todos os casts diretos de Activity pela extensão segura `Context.findActivity()`.

#### 2. 🌐 Purificação e Tradução Integral para o Inglês (100% Localization)
- **Varredura Completa:** Todas as telas, gráficos (Donut, Linhas, Barras), diálogos e notificações tiveram textos hardcoded em português substituídos por chaves localizadas.
- **Sincronização 1:1:** 1.307+ chaves sincronizadas com exatidão entre Inglês padrão (`values/strings.xml`) e Português do Brasil (`values-pt-rBR/strings.xml`).

#### 3. 🧹 Limpeza de Chaves de Bibliotecas & Estabilidade XML
- **Remoção de Conflitos:** Limpeza de chaves de bibliotecas externas (`common_google_play_services`, `androidx_startup`, etc.) dos arquivos XML do projeto, prevenindo quebras de compilação e merge.
- **Auditoria de Placeholders:** Formatação estrita com especificadores posicionais (`%1$s`, `%1$d`) e apóstrofos devidamente escapados.

---

## 🇺🇸 English

### 🚀 What's New in Version 1.0.39:

#### 1. 🛡️ Zero Tab Crash & Navigation Shielding
- **Root Cause Fixed:** Permanently resolved fatal `ClassCastException: ContextImpl cannot be cast to Activity` when opening navigation tabs, bottom sheets, and dialogs.
- **Safe Context Resolution:** All direct Activity casts replaced with safe null-tolerant `Context.findActivity()` extension across all screens.

#### 2. 🌐 Complete English Localization & Parity (100% Localization)
- **Hardcoded Strings Sweep:** Replaced 100% of hardcoded Portuguese text across screens, dialogs, charts, and background services with localized string resources.
- **1:1 Strict Parity:** 1,307+ translation keys synchronized between English default (`values/strings.xml`) and Portuguese (`values-pt-rBR/strings.xml`).

#### 3. 🧹 Library Key Cleanup & XML Stability
- **Merge Conflict Resolution:** Stripped conflicting third-party library keys (`common_google_play_services`, `androidx_startup`, `m3c_*`) from app XML resources.
- **Anti-Crash Verification:** All placeholders strictly validated with positional specifiers (`%1$s`, `%1$d`) and properly escaped apostrophes.

---

## [v1.0.38] Release Notes

---

## 🇧🇷 Português

### 🚀 O Que Há de Novo na Versão 1.0.38:

#### 1. 🌐 Inglês como Idioma Padrão de Fábrica & Localização 100% Completa
- **Inglês como Padrão:** O aplicativo agora inicia obrigatoriamente em Inglês em novas instalações, com suporte completo a Português (Brasil) e Padrão do Sistema.
- **Reatividade Instantânea em Compose:** Suporte a troca dinâmica de idioma em tempo real via `LocaleHelper.currentLanguageFlow` e `CompositionLocalProvider` sem necessidade de reiniciar o aplicativo.
- **Sincronização 1:1 de Recursos:** 311 chaves XML sincronizadas entre `values`, `values-en`, `values-pt` e `values-pt-rBR`.

#### 2. 🛡️ Correção Crítica no Fluxo de Restauração Individual de Aplicativos
- **Fim da Falsa Conclusão Instantânea:** Resolvido o problema em que a tela de restauração individual abria diretamente em "Concluída" sem executar nada devido a estados persistidos de execuções anteriores.
- **Nova Máquina de Estados `IndividualRestoreUiState`:** Fluxo estrito (`Idle` ➔ `SelectingBackup` ➔ `Running` ➔ `Completed` / `Error`).
- **Validação de Backup:** Se o aplicativo não possuir nenhum ponto de backup local ou remoto, o botão Restabelecer exibe um aviso claro e impede o início de restaurações fantasmas.

#### 3. ✨ Animação Futurista de Conclusão (`FuturisticCompletionOrb`) & Barra Inferior Inteligente
- **Orb Neon com Canvas:** Anéis orbitais duplos com rotação desacoplada em degradê neon (`#00FF88` ➔ `#00E5FF`), pulso de radar ao tocar, feedback háptico e animação progressiva do checkmark com `PathMeasure`.
- **Barra de Ações Contextual Pós-Backup:** Substituição dos botões de Pausar/Abortar por ações diretas ("Done / Back to Home" e "View Backups") com badge de verificação SHA-256.

#### 4. 💻 Overhaul no Console de Logs & Badge Neon no Dashboard
- **Layout de Largura Total:** Logs monoespaçados ocupando 100% da largura útil da tela (`11.sp` / `lineHeight = 15.sp`), eliminando a contenção de 3 colunas espremidas.
- **Filtro de Ruído:** Logs de diagnóstico internos (`[DIAGNÓSTICO]`) filtrados do console principal.
- **Correção da TopBar:** Ajuste de padding superior para visualização perfeita dos botões de voltar e minimizar.
- **Badge Neon Dinâmico:** Novo badge neon verde (`Color(0xFF00FF88)`) no Dashboard exibindo a versão real do app (`v1.0.38`).

#### 5. 💬 Comunidade Oficial no Telegram (`t.me/resync_backup`)
- **Modal de Boas-Vindas:** Apresentação da comunidade no primeiro acesso com opções "Entrar no Telegram" e "Agora não".
- **Card Permanente nas Configurações:** Atalho direto e elegante no estilo Bento OLED para acessar o grupo oficial a qualquer momento.

---

## 🇺🇸 English

### 🚀 What's New in Version 1.0.38:

#### 1. 🌐 Factory Default English & Full 100% Localization
- **English Default:** ReSync now starts in English out of the box for fresh installs, with full support for Portuguese (Brazil) and System Default.
- **Instant Compose Switching:** Real-time language switching powered by `LocaleHelper.currentLanguageFlow` and `CompositionLocalProvider` without requiring an app restart.
- **Strict 1:1 XML Parity:** 311 synchronized XML keys across `values`, `values-en`, `values-pt`, and `values-pt-rBR`.

#### 2. 🛡️ Critical Fix for Individual App Restore Flow
- **Eliminated False Instant Completion:** Fixed an issue where the individual app restore screen would immediately display "Completed" due to persisted repository states.
- **Dedicated `IndividualRestoreUiState` Machine:** Strict lifecycle flow (`Idle` ➔ `SelectingBackup` ➔ `Running` ➔ `Completed` / `Error`).
- **Backup Verification:** If an application has no local or cloud backups, the Restore button informs the user and prevents invalid restore attempts.

#### 3. ✨ Futuristic Completion Animation (`FuturisticCompletionOrb`) & Smart Bottom Bar
- **Canvas Orbital Neon Orb:** Dual rotating orbit rings in electric neon gradients (`#00FF88` ➔ `#00E5FF`), interactive tap radar wave, haptic feedback, and progressive path checkmark animation.
- **Contextual Post-Completion Bar:** Replaces Pause/Abort buttons with direct actions ("Done / Back to Home" and "View Backups") alongside a SHA-256 Verified neon badge.

#### 4. 💻 Backup Console Overhaul & Live Neon Dashboard Badge
- **Full-Width Monospace Console:** Monospace logs now occupy 100% width with clean line breaks (`11.sp` / `lineHeight = 15.sp`), replacing cramped 3-column rows.
- **Diagnostic Noise Filter:** Internal diagnostic entries (`[DIAGNÓSTICO]`) are hidden from the primary user console.
- **TopBar Padding Fix:** Resolved header clipping on devices with camera cutouts or status bars.
- **Live Neon Badge:** Dynamic neon green version indicator (`Color(0xFF00FF88)`) next to the ReSync header reading `v1.0.38`.

#### 5. 💬 Official Telegram Community Integration (`t.me/resync_backup`)
- **Welcome Modal:** Introductory modal on first launch offering direct access to the Telegram community group.
- **Permanent Bento Settings Card:** Dedicated community card in Settings for easy access anytime.
