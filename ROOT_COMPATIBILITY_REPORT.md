# Relatório de Compatibilidade com Root — ReSync 1.0.24

## 1. Princípio Fundamental de Coexistência

A arquitetura universal do **ReSync 1.0.24** foi desenhada para que os modos **Sem Root** e **Com Root** operem de maneira harmoniosa, compartilhando a mesma infraestrutura de catálogo, manifesto, validação criptográfica e sincronização com o Google Drive, mantendo isolamento rigoroso em suas camadas de acesso ao sistema de arquivos.

---

## 2. Compatibilidade com Gerenciadores de Superusuário

O ReSync foi validado e oferece suporte de primeira classe aos seguintes ambientes de privilégios elevados no Android:

| Gerenciador | Suporte | Mecanismo de Interação | Observações |
| :--- | :--- | :--- | :--- |
| **KernelSU Next** (`com.rifsxd.ksunext`) | **NATIVO ✓** | Kernel-level hooks, `su -mm` (Mount Master), libsu | Compatibilidade completa com namespaces isolados do kernel Linux 5.10 / 6.1+. |
| **KernelSU Padrão** (`me.weishu.kernelsu`) | **NATIVO ✓** | Kernel-level hooks, `ksud`, libsu | Totalmente suportado para backups atômicos de `/data/data`. |
| **Magisk** (`com.topjohnwu.magisk`) | **NATIVO ✓** | Daemon MagiskSU, `su -c`, libsu | Suporte clássico e moderno (Magisk v26, v27, v28). |
| **APatch** (`me.bmax.apatch`) | **NATIVO ✓** | KernelPatch & SuperKey | Reconhecimento automático e execução privilegiada sem atritos. |

---

## 3. Garantia de Deteção Não Intrusiva (Zero Pop-ups Automáticos)

Para respeitar as diretrizes de usabilidade e privacidade:
1. **Inicialização Limpa:** O método `CapabilityManager.verifyRootAccess` e a rotina `BackupCapabilityDetector.isRootGrantedPassively` inspecionam o estado já concedido pelo `libsu` (`Shell.isAppGrantedRoot() == true`).
2. **Sem Disparo Acidental:** Nenhuma chamada que force o surgimento do pop-up do Magisk ou KernelSU é emitida no arranque da aplicação.
3. **Elevação Sob Demanda:** O diálogo de concessão só é disparado se o usuário clicar ativamente no botão "Solicitar Acesso Root" ou tentar realizar um backup/restauração da categoria restrita "Dados de Aplicativos (/data/data)".

---

## 4. Recursos Privilegiados Preservados

Quando o usuário autoriza privilégios de Root:
1. **Acesso Total a Dados Internos (`/data/data/[pacote]` e `/data/user_de/0/[pacote]`):**
   - Compactação de alto desempenho em tempo real via algoritmo **Zstandard** (`tar.zst`) ou **gzip**.
   - Preservação dos bancos de dados SQLite com flush preventivo (`WAL checkpoint` e sync de arquivos).
2. **Preservação e Restauração de Atributos de Segurança:**
   - Extração e persistência do UID e GID originais de cada pacote (`stat -c "%u %g"`).
   - Na restauração privilegiada, o ReSync reaplica automaticamente o proprietário correto (`chown -R uid:gid /data/data/[pacote]`) e os contextos SELinux oficiais (`restorecon -R /data/data/[pacote]`).
3. **Isolamento de Vaults:**
   - Backups privilegiados e comuns recebem flags identificadoras no `manifest.json` (`isRootDataIncluded = true/false`).
   - Um dispositivo sem root pode restaurar perfeitamente a parte de mídia, documentos, APKs e contatos de um backup criado em dispositivo com root, ignorando pacificamente a pasta interna de `/data/data` e informando o usuário com total clareza.
