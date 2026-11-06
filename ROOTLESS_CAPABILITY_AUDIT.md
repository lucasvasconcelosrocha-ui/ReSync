# Auditoria de Capacidades do Modo Sem Root — ReSync 1.0.24

## 1. Visão Geral da Auditoria

O **ReSync 1.0.24** foi submetido a uma auditoria arquitetural completa com o objetivo primordial de transformar o aplicativo em uma **solução universal de backup e restauração**, operando tanto em dispositivos com privilégios de **Superusuário (Root)** quanto em dispositivos **Sem Root (Non-Root)**.

### Regra Inegociável Estabelecida:
> **O ROOT DEVE SER ESTRITAMENTE OPCIONAL.**
> O usuário nunca é obrigado a instalar Magisk, KernelSU, ADB, Shizuku ou autorizar privilégios elevados para utilizar o aplicativo. O app inicia silenciosamente sem emitir caixas de diálogo ou solicitações de superusuário na tela.

---

## 2. Diagnóstico da Arquitetura Anterior

Na versão inicial do ReSync, várias operações de checagem de privilégios (`CapabilityManager.kt` e `PermissionsRepository.kt`) invocavam a criação ou consulta de processos `su` síncronos na inicialização. Em aparelhos rooteados, isso causava a aparição imediata de pop-ups de concessão de Superusuário. Em dispositivos sem root, gerava logs de erro de processo e limitava a confiança do usuário.

### Mudanças Implementadas:
1. **Deteção Silenciosa e Passiva (`isRootGrantedPassively`):**
   - A inicialização do aplicativo verifica apenas se o processo do ReSync já possui autorização concedida em cache pelo `libsu` (`Shell.isAppGrantedRoot() == true`).
   - A presença de binários `su` ou aplicativos gerenciadores (Magisk, KernelSU, KernelSU Next, APatch) é avaliada através de inspeção passiva do sistema de arquivos e `PackageManager`.
   - Nenhuma chamada a `su -c` ou `Shell.getShell()` é executada no arranque.
2. **Solicitação Explícita de Elevação:**
   - O prompt interativo de root só é disparado se o usuário clicar expressamente no botão "Solicitar Acesso Root" ou tentar executar uma rotina exclusiva de dados internos (`/data/data`).
3. **Modo Padrão Contínuo:**
   - Em caso de ausência de root, recusa pelo usuário ou falha de timeout, o aplicativo opera 100% de suas funções acessíveis em modo **Sem Root**, com UI adaptativa Bento Dark.

---

## 3. Mapeamento das Capacidades do Sistema Operacional Android

| Categoria de Dados | API Pública Oficial | Nível de Acesso Sem Root | Requer Root? | Comportamento no ReSync |
| :--- | :--- | :--- | :--- | :--- |
| **Fotos e Imagens** | `MediaStore.Images` / SAF | Leitura e Escrita Completas | Não | Backup via streaming de 64 KB com EXIF preservado e restauração direta no MediaStore. |
| **Vídeos** | `MediaStore.Video` / SAF | Leitura e Escrita Completas | Não | Streaming de blocos sem carregamento total na RAM; metadados e duração preservados. |
| **Músicas e Áudios** | `MediaStore.Audio` / SAF | Leitura e Escrita Completas | Não | Indexação ID3, metadados de artista/álbum e restauração na pasta de Músicas do sistema. |
| **Documentos e Pastas** | `DocumentsContract` / `DocumentFile` | Leitura e Escrita via SAF | Não | Suporte a pastas públicas (`Documents`, `Download`) e árvores persistidas via `ACTION_OPEN_DOCUMENT_TREE`. |
| **Contatos** | `ContactsContract` | Leitura e Escrita | Não | Exportação simultânea para vCard (.vcf) e JSON estruturado com prevenção automática de duplicatas. |
| **Calendário e Eventos**| `CalendarContract` | Leitura e Escrita | Não | Exportação RFC 5545 iCalendar (.ics) e JSON estruturado com injeção em calendário gravável. |
| **Aplicativos (APKs)** | `PackageManager` | Leitura de Pacotes Públicos | Não | Extração do APK base e todos os split APKs. Restauração via `PackageInstaller` oficial com confirmação do usuário. |
| **Dados Privados (/data/data)** | Sandbox Linux / SELinux | Bloqueado pelo Kernel | **Sim** | Operação honestamente classificada como exclusiva de Root. Sem Root, o app explica o motivo sem travar. |
| **Mensagens SMS** | `Telephony.Sms` | Leitura via Permissão | Não (Leitura) / Sim* (Escrita) | Leitura completa em JSON estruturado; restauração respeita políticas de app SMS padrão do Android. |
| **Histórico de Chamadas** | `CallLog.Calls` | Leitura e Escrita | Não | Backup completo em JSON; restauração direta na base de chamadas com prevenção de duplicatas. |
| **Configurações Sistema**| `Settings.System` / `Global` | Leitura Parcial / Escrita Permitida | Não | Separação transparente entre preferências auto-restauráveis (`WRITE_SETTINGS`) e manuais. |
| **Backups Externos** | `ACTION_SEND` / SAF | Compartilhamento Oficial | Não | Recepção segura de pacotes exportados por apps de terceiros (WhatsApp, Signal, Telegram) e envio ao Drive. |

---

## 4. Princípios de Honestidade Técnica e Integridade

1. **Zero Falsos Positivos:** O ReSync não exibe status "Concluído" se um item foi apenas baixado do Google Drive ou descompactado. A restauração só é confirmada após a reinserção bem-sucedida no provedor de conteúdo do Android.
2. **Zero Falsas Promessas:** O aplicativo informa expressamente que salvar APKs sem root restaura o aplicativo limpo, mas não inclui credenciais de login, sessões ou bancos de dados internos de terceiros (`/data/data`).
3. **Resiliência a Negação de Permissão:** Nenhuma categoria bloqueia as demais. Se o usuário negar permissão de SMS ou Chamadas, o backup de Fotos, Mídias, Documentos e Contatos continua executando normalmente.
