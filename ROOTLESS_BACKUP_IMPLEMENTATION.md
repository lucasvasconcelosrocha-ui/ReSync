# Implementação do Motor Universal Sem Root — ReSync 1.0.24

## 1. Arquitetura Modular

Para assegurar independência técnica, estabilidade e facilidade de manutenção, o ReSync 1.0.24 adota uma arquitetura orientada a **Provedores Modulares de Dados** (Data Providers) situados no pacote `com.resync.app.engine.provider`.

```
                    ┌───────────────────────────────┐
                    │      BackupCapabilityDetector │
                    │ (Avaliação Passiva de Recursos)│
                    └───────────────┬───────────────┘
                                    │
                                    ▼
                    ┌───────────────────────────────┐
                    │    BackupPermissionManager    │
                    │(Solicitação Granular por Tipo)│
                    └───────────────┬───────────────┘
                                    │
      ┌─────────────────────────────┴─────────────────────────────┐
      │                                                           │
      ▼                                                           ▼
┌──────────────────────────────┐            ┌───────────────────────────────────┐
│     NormalBackupEngine       │            │         RootBackupEngine          │
│   (APIs Públicas Android)    │            │ (libsu, tar.zst, chown, SELinux)  │
└──────────────┬───────────────┘            └─────────────────┬─────────────────┘
               │                                              │
               ├─► MediaBackupProvider                        │
               ├─► DocumentBackupProvider                     │
               ├─► ContactsBackupProvider                     │
               ├─► CalendarBackupProvider                     │
               ├─► ApplicationBackupProvider                  │
               ├─► SmsBackupProvider                          │
               ├─► CallLogBackupProvider                      │
               ├─► SettingsBackupProvider                     │
               └─► ExternalBackupProvider                     │
                               │                              │
                               └──────────────┬───────────────┘
                                              │
                                              ▼
                               ┌─────────────────────────────┐
                               │   BackupIntegrityValidator  │
                               │  (Streaming SHA-256 Hashes) │
                               └──────────────┬──────────────┘
                                              │
                                              ▼
                               ┌─────────────────────────────┐
                               │   UniversalRestoreManager   │
                               │  (Orquestrador com Dedup)   │
                               └─────────────────────────────┘
```

---

## 2. Detalhamento dos Componentes Implementados

### 2.1 `BackupCapabilityDetector.kt`
- **Função:** Avalia silenciosamente o nível da API Android (13 a 17+), arquitetura da CPU, fabricante, modelo e status de permissões concedidas.
- **Deteção Passiva de Root:** Executa `Shell.isAppGrantedRoot() == true` e checa a existência física de binários `su` sem disparar comandos interativos ou caixas de diálogo do Superusuário.
- **Resultado:** Produz uma `CapabilityMatrix` imutável consumida pelo `MainViewModel` e pelas telas do Compose.

### 2.2 `BackupPermissionManager.kt`
- **Função:** Determina estritamente quais permissões de tempo de execução são exigidas pelas categorias selecionadas para backup ou restauração.
- **Resiliência:** Se o usuário recusar a permissão de uma categoria (por exemplo, `READ_CALENDAR`), o gerenciador não interrompe o fluxo das demais categorias selecionadas (`READ_CONTACTS`, `READ_MEDIA_IMAGES`).
- **Acesso Especial de Armazenamento:** Gerencia a transição para `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` no Android 11+ apenas quando pastas públicas ou arquivos brutos forem selecionados.

### 2.3 `BackupIntegrityValidator.kt`
- **Função:** Cálculo de hash SHA-256 via streaming através de buffer circular de 64 KB (`calculateStreamSha256` e `calculateFileSha256`).
- **Sem Estouro de Memória:** Nenhum arquivo de mídia ou banco de dados é carregado integralmente na heap da JVM para validação criptográfica.
- **Verificação Atômica:** Valida a integridade dos artefatos antes do upload para o Google Drive e imediatamente após o download, antes de autorizar a restauração.

### 2.4 `ContactsBackupProvider.kt`
- **Leitura:** Acessa `ContactsContract` consultando nomes completos, telefones móveis/residenciais, endereços de e-mail, logradouros postais formatados, organizações/empresas, notas e fotos em Base64.
- **Formatos de Saída:**
  1. `contacts.json`: Estrutura JSON completa contendo todos os metadados para restauração atômica.
  2. `contacts.vcf`: Formato padrão vCard 3.0 para interoperabilidade com outros clientes e plataformas.
- **Prevenção de Duplicatas (Deduplicação):** Na restauração, normaliza os números telefônicos através de regex sanitizado e nomes em caixa baixa. Contatos que já existam no dispositivo não são reinseridos.

### 2.5 `CalendarBackupProvider.kt`
- **Leitura:** Consulta `CalendarContract.Events` e `CalendarContract.Reminders`, recuperando título, descrição, local, início (`DTSTART`), término (`DTEND`), dia inteiro (`ALL_DAY`), fuso horário (`EVENT_TIMEZONE`), regras de recorrência (`RRULE`) e minutos de antecedência dos alarmes configurados.
- **Formatos de Saída:**
  1. `calendar.json`: Metadados completos do modelo `CalendarRecord`.
  2. `calendar.ics`: Padrão internacional RFC 5545 iCalendar (`BEGIN:VCALENDAR ... BEGIN:VEVENT`).
- **Restauração Segura:** Identifica o primeiro calendário com permissão de escrita (`CAL_ACCESS_CONTRIBUTOR` ou superior) do usuário e realiza a inserção dos eventos e seus respectivos lembretes (`Reminders`), ignorando eventos que possuam mesma chave primária composta (título, início e fim).

### 2.6 `MediaBackupProvider.kt`
- **Mídias Suportadas:** Fotos (`MediaStore.Images`), Vídeos (`MediaStore.Video`) e Músicas/Áudios (`MediaStore.Audio`).
- **Streaming de 64 KB:** Transfere dados entre o `ContentResolver.openInputStream()` e os arquivos de container sem alocar buffers pesados.
- **Metadados Preservados:** Nome de exibição, tipo MIME, data de modificação original, largura, altura, duração e caminhos relativos.
- **Restauração no MediaStore:** Reinserção via `ContentResolver.insert()` com suporte a flags modernas do Android 10+ (`IS_PENDING = 1` durante a cópia e `0` após a finalização).

### 2.7 `DocumentBackupProvider.kt`
- **Pastas Locais e Árvores SAF:** Suporta varredura recursiva de diretórios públicos do armazenamento compartilhado (`Documents`, `Download`) e URIs persistidas via `ACTION_OPEN_DOCUMENT_TREE` (`DocumentFile.fromTreeUri`).
- **Preservação de Estrutura:** Mantém os nomes relativos de subdiretórios e restaura os arquivos dentro de `Documents/ReSyncRestored/`.

### 2.8 `ApplicationBackupProvider.kt`
- **Leitura de Pacotes:** Utiliza `PackageManager.getApplicationInfo` para localizar os arquivos `base.apk` e coleções de split APKs (`split_*.apk`).
- **Manifesto de Apps:** Registra versão (`versionName`), código de compilação (`longVersionCode`), tamanho e checksum SHA-256 de cada pacote exportado.
- **Restauração via PackageInstaller:** Cria uma sessão oficial de instalação (`PackageInstaller.SessionParams.MODE_FULL_INSTALL`), transfere os streams do APK base e de todos os splits, realizando o commit através de um `PendingIntent`. Fornece fallback via `ACTION_VIEW` com `FileProvider`.

### 2.9 `SmsBackupProvider.kt` e `CallLogBackupProvider.kt`
- **SMS:** Exporta mensagens autorizadas em `sms_messages.json`. Na restauração, previne duplicações checando número, timestamp e hash do corpo.
- **Chamadas:** Extrai histórico completo em `call_logs.json`. Restaura registros via `CallLog.Calls.CONTENT_URI` evitando duplicidades pela tupla (número, data, duração).

### 2.10 `SettingsBackupProvider.kt`
- **Classificação:**
  - `RESTORABLE`: Parâmetros do `Settings.System` (timeout de tela, rotação automática, resposta tátil, sons de toque) que podem ser gravados programaticamente quando `WRITE_SETTINGS` é concedida.
  - `MANUAL_ONLY`: Configurações de leitura permitida que exigem intervenção manual do usuário no sistema.

### 2.11 `ExternalBackupProvider.kt`
- **Importação de Backups de Terceiros:** Recebe arquivos compartilhados via `ACTION_SEND` e `ACTION_SEND_MULTIPLE` (como históricos de conversa exportados do WhatsApp em `.zip`/`.txt`), armazena no cofre protegido `ReSyncVault/ExternalImports/` com hash SHA-256 e sincroniza diretamente com o Google Drive.

### 2.12 `UniversalRestoreManager.kt`
- **Orquestração:** Lê o `manifest.json`, valida checksums dos arquivos, verifica permissões e despacha a restauração para os provedores específicos.
- **Relatório Transparente:** Emite um sumário discriminando individualmente: Itens Restaurados com Sucesso, Itens que Exigem Ação Manual, e Itens Restritos por Segurança.
