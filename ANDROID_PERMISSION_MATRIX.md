# Matriz de Permissões e Capacidades Reais do Android — ReSync 1.0.24

Esta matriz mapeia com precisão cirúrgica o comportamento de cada categoria de dados nas versões suportadas do Android (**Android 13, 14, 15, 16 e 17+**), detalhando as APIs utilizadas, permissões necessárias, capacidades e restrições reais da plataforma.

---

## 1. Matriz Detalhada por Categoria

| Categoria | Versões Android | Permissões Exigidas | API / Mecanismo do Sistema | Nível de Backup | Nível de Restauração | Limitações e Observações Técnicas |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Fotos e Imagens** | **Android 13 a 17+** (API 33+) | `READ_MEDIA_IMAGES` *(ou Photo Picker)* | `MediaStore.Images.Media`, `ContentResolver` | **TOTAL (Full)** | **TOTAL (Full)** | Mídias públicas são salvas com resolução total e metadados EXIF. Imagens ocultas em pastas privadas de outros apps exigem autorização via SAF. |
| **Vídeos** | **Android 13 a 17+** (API 33+) | `READ_MEDIA_VIDEO` | `MediaStore.Video.Media`, streaming `openInputStream` | **TOTAL (Full)** | **TOTAL (Full)** | Transmitidos em blocos de 64 KB para evitar estouro de memória. Restauração insere registros com flag `IS_PENDING` transitória. |
| **Músicas e Áudio** | **Android 13 a 17+** (API 33+) | `READ_MEDIA_AUDIO` | `MediaStore.Audio.Media`, ContentResolver | **TOTAL (Full)** | **TOTAL (Full)** | Preserva tags ID3 (título, artista, álbum, duração). Mídias em cache criptografado de apps de streaming não são acessíveis. |
| **Documentos e Pastas** | **Android 13 a 17+** (API 33+) | `MANAGE_EXTERNAL_STORAGE` ou Permissão SAF persistida | `DocumentsContract`, `DocumentFile`, `ACTION_OPEN_DOCUMENT_TREE` | **TOTAL (Full)** | **TOTAL (Full)** | Diretórios `Android/data` e `Android/obb` são bloqueados pelo sistema sem Root. Pastas públicas (`Documents`, `Download`) e árvores autorizadas possuem acesso irrestrito. |
| **Contatos** | **Android 13 a 17+** (API 33+) | Leitura: `READ_CONTACTS`<br>Escrita: `WRITE_CONTACTS` | `ContactsContract` (RawContacts, Data) | **TOTAL (Full)** | **TOTAL (Full)** | Gera formatos `contacts.json` e vCard 3.0 (`.vcf`). Restauração atômica com deduplicação rigorosa baseada em números de telefone sanitizados e nomes. |
| **Calendário e Eventos** | **Android 13 a 17+** (API 33+) | Leitura: `READ_CALENDAR`<br>Escrita: `WRITE_CALENDAR` | `CalendarContract` (Events, Reminders) | **TOTAL (Full)** | **TOTAL (Full)** | Exporta em `calendar.json` e RFC 5545 iCalendar (`.ics`). Restauração insere eventos e alarmes em calendário gravável do usuário sem duplicatas. |
| **Aplicativos (APKs)** | **Android 13 a 17+** (API 33+) | Nenhuma permissão de runtime (leitura pública de `sourceDir`) | `PackageManager`, `PackageInstaller` Session API | **TOTAL (Full)** | **ASSISTIDA (System Intent / PackageInstaller)** | Extrai APK base e todos os split APKs (`split_*.apk`). Instalação via `PackageInstaller.Session` com suporte a splits; exige confirmação do usuário na tela. Não inclui dados privados (`/data/data`). |
| **Mensagens SMS** | **Android 13 a 17+** (API 33+) | Leitura: `READ_SMS`<br>Escrita: Papel de App SMS Padrão | `Telephony.Sms`, `ContentResolver` | **TOTAL (Full)** | **CONDICIONAL (App SMS Padrão)** | Leitura e backup operam com `READ_SMS` concedida. Gravação de SMS no Android requer que o app seja temporariamente definido como SMS padrão pelo usuário. |
| **Histórico de Chamadas** | **Android 13 a 17+** (API 33+) | Leitura: `READ_CALL_LOG`<br>Escrita: `WRITE_CALL_LOG` | `CallLog.Calls`, `ContentResolver` | **TOTAL (Full)** | **TOTAL (Full)** | Preserva número, data, duração, tipo e nome em cache. Restauração deduplica chamadas idênticas evitando repetição de registros. |
| **Configurações do Sistema**| **Android 13 a 17+** (API 33+) | `WRITE_SETTINGS` (para aplicar ajustes modificáveis) | `Settings.System`, `Settings.Global` | **PARCIAL (Partial)** | **PARCIAL (Partial)** | Configurações públicas (timeout de tela, rotação, resposta tátil) são aplicadas via API. Ajustes manuais ou globais direcionam o usuário às telas do sistema. |
| **Backups Externos de Apps**| **Android 13 a 17+** (API 33+) | Nenhuma (opera via Intent ou SAF) | `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, Storage SAF | **TOTAL (Full)** | **EXPORTAÇÃO LOCAL / DRIVE** | Recebe arquivos compartilhados oficialmente por outros apps (ex: chats exportados do WhatsApp em `.zip` ou `.txt`). Armazena no cofre e sincroniza com o Drive. |
| **Dados Privados de Apps (`/data/data`)** | **Android 13 a 17+** (API 33+) | **Privilégios de Superusuário (Root / uid=0)** | Kernel Linux, libsu, `tar.zst`, `chown`, `restorecon -R` | **ROOT EXCLUSIVO** | **ROOT EXCLUSIVO** | O acesso ao diretório `/data/data/[pacote]` é estritamente isolado pelo kernel e pelo SELinux. Sem Root, o ReSync informa a restrição sem causar falhas ou bloqueios. |

---

## 2. Diferenças Técnicas por Versão do Android

### Android 13 (API 33)
- Introdução das permissões granulares de mídia (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`), descontinuando `READ_EXTERNAL_STORAGE`.
- Introdução de `POST_NOTIFICATIONS` para notificações de progresso em primeiro plano.
- Implementação integralmente suportada pelo ReSync com fallback automático para APIs legadas.

### Android 14 (API 34)
- Acesso parcial a fotos e vídeos (`READ_MEDIA_VISUAL_USER_SELECTED`).
- O ReSync detecta quando o usuário concede acesso apenas a uma seleção restrita de fotos e ajusta a contagem de itens disponíveis na Bento UI de forma honesta.
- Restrições aprimoradas de início de serviços em segundo plano (`FOREGROUND_SERVICE_DATA_SYNC`).

### Android 15 e 16 (API 35 e 36)
- Limitações mais rígidas sobre modificações de arquivos em armazenamento compartilhado não originados pelo próprio aplicativo.
- O ReSync emprega a API `PackageInstaller` moderna com suporte a múltiplos splits de compilações de 16 KB de alinhamento de página de memória (`16KB page-size alignment`).

### Android 17+
- Políticas de isolamento de processos intensificadas.
- O ReSync utiliza exclusivamente canais IPC e Provedores de Conteúdo oficiais, garantindo compatibilidade direta e longevidade sem dependências de hacks de sistema.
