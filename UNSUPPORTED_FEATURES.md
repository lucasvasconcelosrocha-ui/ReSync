# Relatório de Restrições e Recursos Não Suportados Sem Root — ReSync 1.0.24

## 1. Princípio de Transparência Técnica

A arquitetura do **ReSync 1.0.24** rejeita terminantemente simulações de funcionalidades, botões decorativos ou promessas enganosas. O Android impõe barreiras de segurança rigorosas no kernel do Linux e no framework do sistema operacional.

Este documento detalha com precisão técnica quais operações **NÃO PODEM** ser realizadas em um dispositivo operando no **Modo Sem Root**, apresentando a justificativa técnica e a solução adotada pelo ReSync.

---

## 2. Tabela de Restrições de Segurança do Android

| Recurso Restrito | Limitação Técnica do Android | Como o ReSync Trata Sem Root |
| :--- | :--- | :--- |
| **Bancos de Dados e Contas Internas (`/data/data/[pacote]`)** | **Isolamento de Sandbox do Linux (UID/GID):** Cada aplicativo roda sob um usuário Linux dedicado. O kernel bloqueia qualquer tentativa de leitura ou escrita de processos que não pertençam ao mesmo UID ou ao Superusuário (`uid=0`). | O ReSync **NÃO promete** e **NÃO finge** fazer cópia desses dados. Na interface, o item é claramente identificado como *"Requer Root"*. Se o usuário tentar executar sem root, o sistema informa a limitação educadamente e continua o backup dos outros dados acessíveis. |
| **Pastas de Sistema `Android/data` e `Android/obb`** | **Restrição de Scoped Storage (Android 11+ / API 30+):** O framework do Android impede que seletores do Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) concedam acesso às subpastas `Android/data` e `Android/obb` na memória interna compartilhada. | O aplicativo não tenta burlar as diretrizes do sistema com brechas instáveis. Avisa o usuário que essas duas pastas específicas são protegidas pelo Android 11+ e foca nas pastas autorizadas (`Documents`, `Download`, `Pictures`, `DCIM`, etc.). |
| **Restauração Silenciosa de APKs** | **Políticas de Instalação de Aplicativos:** Apenas a Play Store, a loja oficial do fabricante ou o Superusuário possuem a permissão de sistema privilegiada `INSTALL_PACKAGES` para instalar aplicativos sem confirmação visual. | O ReSync utiliza a API oficial `PackageInstaller.Session` com suporte a Split APKs e envia um Intent do sistema para que o usuário confirme a instalação com um único toque na tela. |
| **Escrita Direta de Mensagens SMS** | **Política de App SMS Padrão:** Desde o Android 4.4 (KitKat), apenas o aplicativo atualmente configurado como padrão pelo usuário possui autorização de escrita na tabela `Telephony.Sms`. | A leitura e o backup em arquivo estruturado funcionam normalmente com `READ_SMS`. Para a restauração em lote, o ReSync orienta o usuário sobre os passos oficiais exigidos pelo Android. |
| **Chaves Criptográficas de Terceiros (Keystore)** | **Hardware Security Module (HSM) / TEE / StrongBox:** Chaves privadas armazenadas no Keystore do Android são vinculadas ao hardware físico e ao UID do aplicativo que as criou. Elas não podem ser exportadas nem mesmo com root em versões modernas do Android. | O ReSync não tenta copiar material de chaves privadas inacessíveis, prevenindo a corrupção de credenciais de biometria e carteiras digitais. |
| **Configurações Globais Seguras (`Settings.Secure`, `Settings.Global`)** | **Permissão `WRITE_SECURE_SETTINGS`:** Reservada exclusivamente para aplicativos de sistema assinados pela fabricante ou concedida via comando manual ADB. | O ReSync separa claramente as configurações públicas modificáveis (`Settings.System`) das opções de leitura apenas informativas, oferecendo atalhos rápidos para que o usuário ajuste manualmente as opções do sistema se desejar. |

---

## 3. Comparativo Prático: O que Esperar em Cada Modo

### No Modo Sem Root (Padrão)
- **O que é 100% Salvo e Restaurado:**
  - Todas as fotos, capturas de tela e imagens (`MediaStore.Images`).
  - Todos os vídeos e gravações (`MediaStore.Video`).
  - Todas as músicas, áudios e podcasts (`MediaStore.Audio`).
  - Todos os documentos e pastas autorizadas via SAF (`Documents`, `Download`, etc.).
  - Todos os contatos com nomes, telefones, e-mails, endereços, notas e fotos (vCard 3.0 e JSON com deduplicação).
  - Todos os eventos de calendário com horários, recorrências e lembretes (iCalendar e JSON).
  - Todos os arquivos instaladores APK (base e splits completos) para reinstalação de apps.
  - Histórico de chamadas completo com deduplicação.
  - Mensagens SMS salvas em arquivo JSON seguro.
  - Configurações de sistema públicas autorizadas.
  - Backups compartilhados por outros aplicativos (WhatsApp, Signal, exportações manuais).
  - Sincronização integral de alta velocidade com o Google Drive.

### No Modo Com Root (Recurso Avançado Opcional)
- Tudo o que o modo Sem Root realiza, **acrescido de:**
  - Cópia e restauração dos dados internos, bancos SQLite e preferências de aplicativos em `/data/data`.
  - Restauração atômica de UID/GID e contextos de segurança SELinux (`restorecon -R`).
  - Leitura irrestrita de partições de sistema e dados de rede avançados.
