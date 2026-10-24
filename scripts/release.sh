#!/usr/bin/env bash
set -euo pipefail

echo "==================================================="
echo "            ReSync - Release Automatizada"
echo "==================================================="

# 1. Verificar GitHub CLI
if ! command -v gh &> /dev/null; then
    echo "[ERRO] O GitHub CLI (gh) não está instalado ou não está no PATH."
    echo "Para instalar no Linux/macOS:"
    echo "    sudo apt install gh   # Ubuntu/Debian"
    echo "    brew install gh       # macOS"
    echo "Depois autentique com sua conta do GitHub:"
    echo "    gh auth login"
    exit 1
fi

if ! gh auth status &> /dev/null; then
    echo "[ERRO] O GitHub CLI não está autenticado."
    echo "Execute o comando abaixo para se autenticar no GitHub:"
    echo "    gh auth login"
    exit 1
fi

# 2. Carregar e Atualizar version.properties
VERSION_PROP="version.properties"
if [ ! -f "$VERSION_PROP" ]; then
    echo "versionCode=2" > "$VERSION_PROP"
    echo "versionName=1.0.1" >> "$VERSION_PROP"
fi

V_CODE=$(grep "^versionCode=" "$VERSION_PROP" | cut -d'=' -f2)
V_NAME=$(grep "^versionName=" "$VERSION_PROP" | cut -d'=' -f2)

CUSTOM_NAME="${1:-}"
CUSTOM_CODE="${2:-}"
NOTES="${3:-}"

if [ -n "$CUSTOM_CODE" ]; then
    NEW_CODE="$CUSTOM_CODE"
else
    NEW_CODE=$((V_CODE + 1))
fi

if [ -n "$CUSTOM_NAME" ]; then
    NEW_NAME="$CUSTOM_NAME"
else
    MAJOR=$(echo "$V_NAME" | cut -d'.' -f1)
    MINOR=$(echo "$V_NAME" | cut -d'.' -f2)
    PATCH=$(echo "$V_NAME" | cut -d'.' -f3)
    NEW_PATCH=$((PATCH + 1))
    NEW_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"
fi

if [ -z "$NOTES" ]; then
    NOTES="ReSync v${NEW_NAME} - Atualização de estabilidade, desempenho do backup em streaming e correções de interface."
fi

echo "[*] Atualizando versão: v${V_NAME} (build ${V_CODE}) -> v${NEW_NAME} (build ${NEW_CODE})"
cat <<EOF > "$VERSION_PROP"
versionCode=${NEW_CODE}
versionName=${NEW_NAME}
EOF

# 3. Compilar assembleRelease
echo "[*] Compilando APK assinado assembleRelease..."
./gradlew assembleRelease

APK_SRC="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "[ERRO] APK de release não encontrado em ${APK_SRC}."
    exit 1
fi

# 4. Gerar ReSync-v<versionName>.apk e SHA-256
APK_DEST="ReSync-v${NEW_NAME}.apk"
SHA_DEST="ReSync-v${NEW_NAME}.apk.sha256"

cp -f "$APK_SRC" "$APK_DEST"
echo "[*] APK gerado: ${APK_DEST}"

if command -v sha256sum &> /dev/null; then
    sha256sum "$APK_DEST" > "$SHA_DEST"
elif command -v shasum &> /dev/null; then
    shasum -a 256 "$APK_DEST" > "$SHA_DEST"
fi

echo "[*] SHA-256 gerado em ${SHA_DEST}"

# 5. Criar Tag e Release no GitHub (apenas APK e SHA-256, sem push de código-fonte)
echo "[*] Publicando release no GitHub (lucasvasconcelosrocha-ui/SwiftVault)..."
gh release create "v${NEW_NAME}" "$APK_DEST" "$SHA_DEST" \
    --repo lucasvasconcelosrocha-ui/SwiftVault \
    --title "ReSync v${NEW_NAME}" \
    --notes "$NOTES"

echo "==================================================="
echo "[SUCESSO] Release v${NEW_NAME} publicada com sucesso!"
echo "Link: https://github.com/lucasvasconcelosrocha-ui/SwiftVault/releases/tag/v${NEW_NAME}"
echo "==================================================="
