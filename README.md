# ReSync

**ReSync** é um gerenciador de backup e restauração profissional, granular, de alta performance e 100% gratuito desenvolvido nativamente para Android (com suporte integral do Android 8.0 até Android 15/16).

---

## 🛡️ Destaques e Princípios Fundamentais

1. **Backup Granular ("Backup Seletivo")**
   - Controle total sobre o que salvar: aplicativos individuais, módulos divididos (Split APKs), pastas específicas, arquivos únicos, mensagens SMS, histórico de chamadas, configurações de rede/DNS e papel de parede.
   - Nenhuma obrigatoriedade de backup completo ou arquivos desnecessários.

2. **Ícones Reais e Otimização Visual**
   - Extração dinâmica e real de ícones através do `PackageManager` oficial.
   - Suporte completo a **Adaptive Icons** (renderização de background + foreground) e Vector Drawables.
   - Cache em memória com `LruCache` para garantir 60/120 FPS em rolagem no Jetpack Compose.

3. **Sincronização com Nuvem 100% Funcional e Gratuita (Google Drive)**
   - Integração real utilizando a **API oficial do Google Drive (REST API v3)** e **OAuth 2.0** oficial do Google (`https://www.googleapis.com/auth/drive.file`).
   - **Totalmente Gratuito:** Sem servidores intermediários pagos, sem assinaturas, sem taxas ocultas e sem dependência de Firebase pago para a funcionalidade básica.
   - **Respeito à Cota da Conta Google:** Usa a própria conta gratuita do usuário. Caso o armazenamento atinja o limite, o app avisa explicitamente: *"Não há espaço suficiente no Google Drive para concluir este backup."*
   - **Upload Resumível Oficial:** Envio segmentado em blocos (1 MB, múltiplos de 256 KB) com cálculo de velocidade em tempo real (MB/s), tempo estimado (ETA), pausa e retomada de uploads interrompidos (`HTTP 308 Resume Incomplete`).
   - **Verificação Contínua de Integridade:** Validação em múltiplos níveis (existência, tamanho exato em bytes, hash MD5 do Google Drive e integridade do container criptográfico `.svb`) antes de exibir **✓ Sincronizado**.
   - **Download e Restauração Direta da Nuvem:** Baixa o container da nuvem com acompanhamento de progresso, validação, descriptografia (AES-256-GCM / PBKDF2), descompressão e restauração seletiva.
   - **Sincronização Automática com WorkManager:** Agendamento periódico inteligente com respeito aos limites do Android (restrições de Somente Wi-Fi, Somente Carregando, Diária ou Semanal).

4. **Container Criptográfico Rodin_Backup (`.svb`)**
   - Cabeçalho padronizado: Magic Bytes `SVB1` (`0x53, 0x56, 0x42, 0x01`).
   - Manifesto estruturado em JSON com metadados dos itens, hashes SHA-256 e permissões originais.
   - Criptografia padrão militar **AES-256-GCM** com Android KeyStore ou derivação de senha via **PBKDF2** (100.000 iterações com salt seguro).
   - Validação de integridade via **SHA-256** antes de marcar qualquer operação como concluída.

5. **Transparência e Compatibilidade com Android 17**
   - Tratamento explícito das restrições de segurança do Android 17 (Sandbox, Scoped Storage, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`).
   - `CapabilityManager` transparente:
     - **Sem Root:** Executa todas as operações permitidas pelas APIs públicas e oficiais.
     - **Shizuku:** Integração via IPC Binder para comandos privilegiados quando disponível.
     - **Root:** Integração via `libsu` oficial mediante autorização explícita do usuário.
   - Notificações honestas e diretas ao usuário quando uma operação for restrita pelo sistema operacional.

6. **Identidade Visual Premium**
   - Tema escuro profundo com efeitos glassmorphism e bordas sutis.
   - Cores de destaque personalizáveis: **Azul Neon**, **Roxo Cyber**, **Verde Esmeralda**, **Ciano Elétrico**, **Laranja Sunset** e **Vermelho Carmim**.
   - Gráficos interativos em Canvas:
     - Donut Chart de uso de armazenamento com toque interativo.
     - Gráfico em curva suave (Cubic Bezier) de histórico de backups com seletor de períodos (7d, 30d, 90d, 1 ano).
     - Gráfico multi-barras de atividade na nuvem (Upload, Download, Falhas).
     - Barra de distribuição e filtro rápido de status de aplicativos.

---

## ☁️ Arquitetura da Sincronização com Nuvem (Google Drive)

O subsistema de sincronização em nuvem é 100% desacoplado da interface gráfica, localizado em `com.swiftvault.backup.cloud.gdrive`:

```
CloudStorageManager
│
└── GoogleDriveProvider
    │
    ├── OAuthManager             // Autenticação OAuth 2.0 oficial (AccountManager / Token Info / Refresh)
    ├── DriveFileManager        // API v3: Verificação de cota, criação da hierarquia de pastas e listagem
    ├── UploadManager           // Upload Resumível em chunks, MB/s real, ETA, pausa/retomada e cancelamento
    ├── DownloadManager         // Streaming de download direto da API v3 com progresso em tempo real
    ├── IntegrityManager        // Validação de existência, tamanho, MD5 checksum e manifesto .svb
    ├── SyncManager             // Agendamento em segundo plano via WorkManager (Wi-Fi, carregador, periodicidade)
    └── CloudBackupRepository   // Repositório de orquestração de upload, download, restauração e exclusão
```

### Estrutura de Pastas Remota no Google Drive
Ao autenticar e sincronizar, o aplicativo cria automaticamente no Google Drive do usuário:

```
Rodin_Backup/
│
├── Backups/
│   ├── Aplicativos/
│   ├── Arquivos/
│   ├── Sistema/
│   ├── DNS/
│   ├── SMS/
│   └── Chamadas/
│
└── Metadata/
```

---

## 📁 Estrutura do Projeto

```
RodinBackup/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/swiftvault/backup/
│       │   │   ├── SwiftVaultApplication.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/
│       │   │   │   ├── model/BackupModels.kt
│       │   │   │   └── database/AppDatabaseHelper.kt
│       │   │   ├── engine/
│       │   │   │   ├── SecurityManager.kt
│       │   │   │   ├── ArchiveEngine.kt
│       │   │   │   ├── CapabilityManager.kt
│       │   │   │   ├── IconCacheManager.kt
│       │   │   │   ├── DnsManager.kt
│       │   │   │   ├── BackupEngine.kt
│       │   │   │   └── RestoreEngine.kt
│       │   │   ├── cloud/
│       │   │   │   ├── CloudStorageProvider.kt
│       │   │   │   ├── CloudStorageManager.kt
│       │   │   │   ├── gdrive/
│       │   │   │   │   ├── OAuthManager.kt
│       │   │   │   │   ├── DriveFileManager.kt
│       │   │   │   │   ├── UploadManager.kt
│       │   │   │   │   ├── DownloadManager.kt
│       │   │   │   │   ├── IntegrityManager.kt
│       │   │   │   │   ├── SyncManager.kt
│       │   │   │   │   └── CloudBackupRepository.kt
│       │   │   │   └── providers/
│       │   │   │       ├── GoogleDriveProvider.kt
│       │   │   │       └── GenericCloudProviders.kt
│       │   │   ├── service/
│       │   │   │   └── BackupForegroundService.kt
│       │   │   ├── worker/
│       │   │   │   ├── ScheduledBackupWorker.kt
│       │   │   │   └── CloudSyncWorker.kt
│       │   │   └── ui/
│       │   │       ├── theme/Theme.kt
│       │   │       ├── components/
│       │   │       │   ├── cards/DashboardCards.kt
│       │   │       │   └── charts/
│       │   │       │       ├── StorageDonutChart.kt
│       │   │       │       ├── BackupHistoryLineChart.kt
│       │   │       │       ├── CloudActivityChart.kt
│       │   │       │       └── AppStatusDistributionChart.kt
│       │   │       ├── screens/
│       │   │       │   ├── DashboardScreen.kt
│       │   │       │   ├── AppsListScreen.kt
│       │   │       │   ├── AppDetailScreen.kt
│       │   │       │   ├── FileExplorerScreen.kt
│       │   │       │   ├── DnsSettingsScreen.kt
│       │   │       │   ├── SystemSettingsBackupScreen.kt
│       │   │       │   ├── BackupWizardScreen.kt
│       │   │       │   ├── CloudSyncCenterScreen.kt
│       │   │       │   ├── BackupRestoreScreen.kt
│       │   │       │   └── LogsScreen.kt
│       │   │       └── navigation/Navigation.kt
│       │   └── res/
│       └── test/
│           └── java/com/swiftvault/backup/
│               ├── BackupAndSecurityEngineTest.kt
│               ├── CloudDiagnosticAndSyncTest.kt
│               └── GoogleDriveRealSyncTest.kt
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew.bat
```

---

## 🚀 Como Compilar e Executar

### Pré-requisitos
- JDK 17 ou JDK 21
- Android SDK Platform 35 / 36 / 37 (Android 17 Baklava ready)

### Compilação e Testes Unitários
```bash
# Executar suíte de testes unitários automatizados
./gradlew testDebugUnitTest

# Gerar APK de Depuração Oficial
./gradlew assembleDebug
```

O binário final gerado estará disponível em:
`app/build/outputs/apk/debug/app-debug.apk`
ou para versão Release assinada:
`app/build/outputs/apk/release/app-release.apk`

