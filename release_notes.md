# 🚀 ReSync v1.0.39 Release Notes

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
