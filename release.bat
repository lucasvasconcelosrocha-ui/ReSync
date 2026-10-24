@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo             ReSync - Release Automatizada
echo ===================================================

:: 1. Verificar GitHub CLI
where gh >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERRO] O GitHub CLI (gh) nao esta instalado ou nao esta no PATH.
    echo Para instalar no Windows, execute:
    echo     winget install --id GitHub.cli
    echo Depois autentique com sua conta do GitHub:
    echo     gh auth login
    exit /b 1
)

gh auth status >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERRO] O GitHub CLI nao esta autenticado.
    echo Execute o comando abaixo no terminal para se conectar ao GitHub:
    echo     gh auth login
    exit /b 1
)

:: 2. Carregar e Atualizar version.properties
set VERSION_PROP=version.properties
if not exist "%VERSION_PROP%" (
    echo versionCode=2 > "%VERSION_PROP%"
    echo versionName=1.0.1 >> "%VERSION_PROP%"
)

for /f "tokens=1,2 delims==" %%a in (%VERSION_PROP%) do (
    if "%%a"=="versionCode" set V_CODE=%%b
    if "%%a"=="versionName" set V_NAME=%%b
)

:: Se argumentos forem passados: release.bat [versionName] [versionCode] [notes]
set CUSTOM_NAME=%~1
set CUSTOM_CODE=%~2
set NOTES=%~3

if not "%CUSTOM_CODE%"=="" (
    set /a NEW_CODE=%CUSTOM_CODE%
) else (
    set /a NEW_CODE=%V_CODE%+1
)

if not "%CUSTOM_NAME%"=="" (
    set NEW_NAME=%CUSTOM_NAME%
) else (
    for /f "tokens=1,2,3 delims=." %%x in ("%V_NAME%") do (
        set MAJOR=%%x
        set MINOR=%%y
        set /a PATCH=%%z+1
        set NEW_NAME=!MAJOR!.!MINOR!.!PATCH!
    )
)

if "%NOTES%"=="" (
    set NOTES=ReSync v!NEW_NAME! - Atualizacao de estabilidade, desempenho do backup em streaming e correcoes de interface.
)

echo [*] Atualizando versao: v!V_NAME! (build !V_CODE!) -^> v!NEW_NAME! (build !NEW_CODE!)
(
    echo versionCode=!NEW_CODE!
    echo versionName=!NEW_NAME!
) > "%VERSION_PROP%"

:: 3. Compilar assembleRelease
echo [*] Compilando APK assinado assembleRelease...
call gradlew.bat assembleRelease
if %ERRORLEVEL% neq 0 (
    echo [ERRO] Falha na compilacao assembleRelease.
    exit /b 1
)

set APK_SRC=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_SRC%" (
    echo [ERRO] APK de release nao encontrado em %APK_SRC%.
    exit /b 1
)

:: 4. Gerar ReSync-v<versionName>.apk e SHA-256
set APK_DEST=ReSync-v!NEW_NAME!.apk
set SHA_DEST=ReSync-v!NEW_NAME!.apk.sha256

copy /y "%APK_SRC%" "%APK_DEST%" >nul
echo [*] APK gerado: %APK_DEST%

powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%APK_DEST%').Hash.ToLower() + '  %APK_DEST%'" > "%SHA_DEST%"
set /p SHA_VAL=<"%SHA_DEST%"
echo [*] SHA-256: !SHA_VAL!

:: 5. Criar Tag e Release no GitHub (apenas APK e SHA-256, sem push do codigo-fonte)
echo [*] Publicando release no GitHub (lucasvasconcelosrocha-ui/SwiftVault)...
gh release create "v!NEW_NAME!" "%APK_DEST%" "%SHA_DEST%" --repo lucasvasconcelosrocha-ui/SwiftVault --title "ReSync v!NEW_NAME!" --notes "!NOTES!"
if %ERRORLEVEL% neq 0 (
    echo [ERRO] Falha ao criar release no GitHub.
    exit /b 1
)

echo ===================================================
echo [SUCESSO] Release v!NEW_NAME! publicada com sucesso!
echo Link: https://github.com/lucasvasconcelosrocha-ui/SwiftVault/releases/tag/v!NEW_NAME!
echo ===================================================
