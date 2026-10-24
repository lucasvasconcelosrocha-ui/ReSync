# Regra de Publicação e Fluxo de Alterações (ReSync)

Esta regra é permanente e mandatória para todo o desenvolvimento no projeto **ReSync**.

---

## 1. Fluxo Obrigatório de Alterações
Sempre que for solicitada qualquer alteração no código ou recursos do aplicativo:

1. **Implementação:** Realize as modificações necessárias com precisão.
2. **Validação da Compilação:** 
   Execute obrigatoriamente a verificação de compilação Kotlin:
   ```bash
   export HOME="/data/user/0/com.agy/files"
   export GRADLE_USER_HOME="/data/user/0/com.agy/files/.gradle"
   export PATH="/data/user/0/com.agy/files/usr/bin:$PATH"
   ./gradlew compileDebugKotlin --no-daemon
   ```
   *Se houver qualquer erro de compilação, corrija-o antes de prosseguir.*
3. **Publicação Automática (Sem necessidade de o usuário pedir):**
   - Atualize a branch local:
     ```bash
     git pull origin main
     ```
   - Crie o commit com uma mensagem clara e objetiva em português descrevendo a alteração:
     ```bash
     git add -A
     git commit -m "<tipo>: <descrição clara da alteração>"
     ```
   - Envie para o repositório remoto via SSH:
     ```bash
     git push origin main
     ```
4. **Comunicação Final:**
   - Informe ao usuário o resultado do push (hash do commit, branch `main`).
   - Lembre o usuário de que a Action de build e publicação do APK no GitHub Actions leva **cerca de 5 minutos** para concluir.

---

## 2. Bloqueio de Segurança e Arquivos Sensíveis
- **Arquivos Proibidos:** NUNCA adicione ou comite:
  - Arquivos de chave ou certificados (`*.jks`, `*.keystore`, `*.p12`, `resync-release.jks`)
  - Arquivos de propriedades com credenciais (`keystore.properties`, `local.properties`, `secrets.properties`, `*.env`)
  - Tokens ou senhas em texto plano
- **Checagem Pré-Commit:** 
  Antes de commitar, inspecione a saída do `git status`. Se qualquer arquivo sensível ou não intencional aparecer como modificado/staged, **PARE imediatamente**, desfaça o stage e alerte o usuário.

---

## 3. Integridade do CI/CD e Scripts
- **Workflow do GitHub Actions:** NUNCA altere o arquivo [.github/workflows/release.yml](file:///data/user/0/com.agy/files/antigravity/ReSync/.github/workflows/release.yml) sem que o usuário peça explicitamente.
- **Gradle Wrapper:** NUNCA altere o shebang (`#!/usr/bin/env sh`) ou o conteúdo do script `gradlew` local sem pedido explícito.

---

## 4. Condição de Exceção (Sem Publicação)
- Se o usuário especificar na mensagem termos como:
  - *"só testar"* / *"apenas testar"*
  - *"sem publicar"* / *"não suba"* / *"não envie"* / *"não faça push"*
  
  **NÃO realize o commit nem o `git push`**. Faça apenas a alteração e a validação local da compilação.
