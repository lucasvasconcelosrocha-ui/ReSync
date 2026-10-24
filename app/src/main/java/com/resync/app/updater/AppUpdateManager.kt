package com.resync.app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.resync.app.BuildConfig
import com.resync.app.engine.SecurityManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

class AppUpdateManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("resync_update_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val TAG = "AppUpdateManager"
        const val GITHUB_OWNER = "lucasvasconcelosrocha-ui"
        const val GITHUB_REPO = "ReSync"
        const val VERSION_JSON_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/version.json"
        const val GITHUB_API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        private const val PREF_AUTO_CHECK = "auto_check_updates"
        private const val PREF_LAST_CHECK_TIME = "last_check_timestamp"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var cachedReleaseInfo: AppReleaseInfo? = null

    fun isAutoCheckEnabled(): Boolean {
        return prefs.getBoolean(PREF_AUTO_CHECK, true)
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_CHECK, enabled).apply()
    }

    fun getInstalledVersionCode(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            }
        } catch (_: Exception) {
            BuildConfig.VERSION_CODE
        }
    }

    fun getInstalledVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }
    }

    /**
     * Compares remote version tag (e.g. "1.0.10" or "v1.0.10") with current installed version numerically.
     * Guaranteed: 1.0.10 > 1.0.9, 2.0.0 > 1.9.9, etc.
     */
    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remoteClean = remoteTag.trim().removePrefix("v").removePrefix("V")
        val currentClean = currentVersion.trim().removePrefix("v").removePrefix("V")

        val remoteParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Checks whether an update is available from GitHub releases.
     * Enforces 6-hour interval for automatic checks; forceManual bypasses the timer.
     * Silent failure: network errors and missing releases do not throw or freeze UI.
     */
    suspend fun checkForUpdate(forceManual: Boolean = false): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(PREF_LAST_CHECK_TIME, 0L)

        if (!forceManual && (now - lastCheck) < CHECK_INTERVAL_MS && cachedReleaseInfo == null) {
            return@withContext Result.success(null)
        }

        try {
            var releaseInfo = fetchFromGitHubApiFallback()
            if (releaseInfo == null) {
                releaseInfo = fetchVersionJsonDirect()
            }

            if (releaseInfo == null) {
                Log.d(TAG, "Nenhuma release encontrada no GitHub no momento.")
                return@withContext Result.success(null)
            }

            cachedReleaseInfo = releaseInfo
            prefs.edit().putLong(PREF_LAST_CHECK_TIME, now).apply()

            val isNewer = isNewerVersion(releaseInfo.versionName, getInstalledVersionName()) ||
                    releaseInfo.versionCode > getInstalledVersionCode()

            if (isNewer) {
                Log.i(TAG, "Nova atualização detectada: ${releaseInfo.versionName} (build ${releaseInfo.versionCode}) vs instalado (${getInstalledVersionName()})")
                Result.success(releaseInfo)
            } else {
                Log.d(TAG, "Aplicativo atualizado. Versão instalada: ${getInstalledVersionName()}, Remota: ${releaseInfo.versionName}")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha discreta ao checar atualizações no GitHub: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Directly fetches version.json from the latest release asset download URL
     */
    private fun fetchVersionJsonDirect(): AppReleaseInfo? {
        val request = Request.Builder()
            .url(VERSION_JSON_URL)
            .header("User-Agent", "ReSync-App/${getInstalledVersionName()}")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val body = response.body?.string() ?: return null
                parseVersionJson(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Query GitHub Releases API: GET /repos/{owner}/{repo}/releases/latest
     * Finds the asset ending with .apk (regardless of file name) and companion .sha256
     */
    private fun fetchFromGitHubApiFallback(): AppReleaseInfo? {
        val request = Request.Builder()
            .url(GITHUB_API_LATEST_RELEASE)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ReSync-App/${getInstalledVersionName()}")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "GitHub API latest release retornou código HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val rootJson = JsonParser.parseString(body).asJsonObject

                val assets = rootJson.getAsJsonArray("assets") ?: return null
                var apkUrl: String? = null
                var apkName = "ReSync.apk"
                var apkSize = 0L
                var sha256Url: String? = null
                var sha256Expected = ""
                var versionJsonUrl: String? = null

                for (item in assets) {
                    val assetObj = item.asJsonObject
                    val name = assetObj.get("name")?.asString ?: ""
                    val downloadUrl = assetObj.get("browser_download_url")?.asString ?: ""

                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = downloadUrl
                        apkName = name
                        apkSize = assetObj.get("size")?.asLong ?: 0L
                    } else if (name.endsWith(".sha256", ignoreCase = true)) {
                        sha256Url = downloadUrl
                    } else if (name.equals("version.json", ignoreCase = true)) {
                        versionJsonUrl = downloadUrl
                    }
                }

                // If companion .sha256 asset is available, read the expected checksum
                if (!sha256Url.isNullOrBlank()) {
                    try {
                        val shaReq = Request.Builder().url(sha256Url).get().build()
                        httpClient.newCall(shaReq).execute().use { shaRes ->
                            if (shaRes.isSuccessful) {
                                val text = shaRes.body?.string()?.trim() ?: ""
                                sha256Expected = text.split(Regex("\\s+")).firstOrNull() ?: text
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (!versionJsonUrl.isNullOrBlank()) {
                    val subReq = Request.Builder().url(versionJsonUrl).get().build()
                    httpClient.newCall(subReq).execute().use { subRes ->
                        if (subRes.isSuccessful) {
                            val subBody = subRes.body?.string()
                            if (!subBody.isNullOrBlank()) {
                                return parseVersionJson(subBody, apkUrl)
                            }
                        }
                    }
                }

                val tagName = rootJson.get("tag_name")?.asString ?: "v1.0.0"
                val releaseNotes = rootJson.get("body")?.asString ?: ""
                val syntheticVersionCode = extractVersionCodeFromTag(tagName)

                if (!apkUrl.isNullOrBlank()) {
                    AppReleaseInfo(
                        versionCode = syntheticVersionCode,
                        versionName = tagName.removePrefix("v").removePrefix("V"),
                        tag = tagName,
                        apkName = apkName,
                        apkSize = apkSize,
                        sha256 = sha256Expected,
                        releaseNotes = releaseNotes,
                        apkUrl = apkUrl
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Exceção na consulta à API do GitHub: ${e.message}")
            null
        }
    }

    /**
     * Parses version.json content and ensures a complete AppReleaseInfo object
     */
    fun parseVersionJson(jsonString: String, overrideApkUrl: String? = null): AppReleaseInfo? {
        return try {
            val obj = JsonParser.parseString(jsonString).asJsonObject
            val versionCode = obj.get("versionCode")?.asInt ?: 0
            val versionName = obj.get("versionName")?.asString ?: "1.0.0"
            val tag = obj.get("tag")?.asString ?: "v$versionName"
            val apkName = obj.get("apkName")?.asString ?: "ReSync-$tag.apk"
            val apkSize = obj.get("apkSize")?.asLong ?: 0L
            val sha256 = obj.get("sha256")?.asString ?: ""
            val releaseNotes = obj.get("releaseNotes")?.asString ?: ""

            val resolvedApkUrl = overrideApkUrl
                ?: obj.get("apkUrl")?.asString
                ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tag/$apkName"

            AppReleaseInfo(
                versionCode = versionCode,
                versionName = versionName,
                tag = tag,
                apkName = apkName,
                apkSize = apkSize,
                sha256 = sha256,
                releaseNotes = releaseNotes,
                apkUrl = resolvedApkUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao processar JSON de versão: ${e.message}")
            null
        }
    }

    private fun extractVersionCodeFromTag(tag: String): Int {
        val clean = tag.removePrefix("v").removePrefix("V").split(".")
        return try {
            val patch = clean.getOrNull(2)?.toIntOrNull()
            if (patch != null && patch > 0) {
                patch
            } else {
                val major = clean.getOrNull(0)?.toIntOrNull() ?: 1
                val minor = clean.getOrNull(1)?.toIntOrNull() ?: 0
                val p = patch ?: 0
                (major * 10000) + (minor * 100) + p
            }
        } catch (_: Exception) {
            1
        }
    }

    /**
     * Downloads APK update with progress notification and resume capability
     */
    suspend fun downloadUpdate(
        releaseInfo: AppReleaseInfo,
        onProgress: (progress: Float, speedMbPerSec: Double, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, releaseInfo.apkName)
        val partFile = File(updatesDir, "${releaseInfo.apkName}.part")

        try {
            var downloadedBytes = if (partFile.exists()) partFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(releaseInfo.apkUrl)
                .header("User-Agent", "ReSync-App/${getInstalledVersionName()}")

            if (downloadedBytes > 0) {
                requestBuilder.header("Range", "bytes=$downloadedBytes-")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 416) {
                // If range request failed with code other than Requested Range Not Satisfiable, start fresh
                partFile.delete()
                downloadedBytes = 0L
                val freshResponse = httpClient.newCall(
                    Request.Builder().url(releaseInfo.apkUrl).build()
                ).execute()

                if (!freshResponse.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Falha no download da atualização: HTTP ${freshResponse.code}")
                    )
                }
                processDownloadStream(freshResponse, partFile, downloadedBytes, releaseInfo.apkSize, onProgress)
            } else if (response.code == 416) {
                // Already downloaded completely to partFile
            } else {
                processDownloadStream(response, partFile, downloadedBytes, releaseInfo.apkSize, onProgress)
            }

            // Rename .part to final .apk
            if (targetFile.exists()) targetFile.delete()
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante o download do APK: ${e.message}", e)
            try { partFile.delete() } catch (_: Exception) {}
            try { targetFile.delete() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private fun processDownloadStream(
        response: okhttp3.Response,
        partFile: File,
        initialBytes: Long,
        expectedTotalSize: Long,
        onProgress: (progress: Float, speedMbPerSec: Double, downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val body = response.body ?: throw IllegalStateException("Resposta HTTP sem corpo de dados.")
        val contentLength = body.contentLength()
        val totalBytes = if (contentLength > 0) initialBytes + contentLength else expectedTotalSize

        var currentDownloaded = initialBytes
        var lastTime = System.currentTimeMillis()
        var bytesSinceLastTime = 0L

        FileOutputStream(partFile, initialBytes > 0).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    currentDownloaded += read
                    bytesSinceLastTime += read

                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastTime
                    if (deltaMs >= 500) {
                        val speedMb = (bytesSinceLastTime.toDouble() / (1024.0 * 1024.0)) / (deltaMs.toDouble() / 1000.0)
                        val progress = if (totalBytes > 0) (currentDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                        onProgress(progress, speedMb, currentDownloaded, totalBytes)
                        lastTime = now
                        bytesSinceLastTime = 0L
                    }
                }
            }
        }
        val finalProgress = if (totalBytes > 0) 1.0f else 1.0f
        onProgress(finalProgress, 0.0, currentDownloaded, totalBytes)
    }

    /**
     * Validates SHA-256 and Package Signature before allowing installation.
     * Deletes the APK immediately if any validation fails.
     */
    fun verifyDownloadedApk(apkFile: File, expectedSha256: String?): Result<Boolean> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.failure(IllegalStateException("Arquivo do instalador inexistente ou vazio."))
        }

        // 1. SHA-256 Verification
        if (!expectedSha256.isNullOrBlank()) {
            val calculatedSha = try {
                FileInputStream(apkFile).use { SecurityManager.computeSha256(it) }
            } catch (e: Exception) {
                apkFile.delete()
                return Result.failure(IllegalStateException("Falha ao calcular hash SHA-256 do arquivo baixado: ${e.message}"))
            }

            if (!calculatedSha.equals(expectedSha256.trim(), ignoreCase = true)) {
                apkFile.delete()
                Log.e(TAG, "Hash divergente! Calculado: $calculatedSha vs Esperado: $expectedSha256")
                return Result.failure(
                    SecurityException("O hash SHA-256 do arquivo baixado não confere com o da release. O arquivo foi excluído por segurança.")
                )
            }
            Log.i(TAG, "Hash SHA-256 verificado com sucesso: $calculatedSha")
        }

        // 2. Package Integrity & Application ID Check
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        if (archiveInfo == null) {
            apkFile.delete()
            return Result.failure(
                SecurityException("O arquivo baixado não é um pacote APK Android válido ou está corrompido.")
            )
        }

        if (archiveInfo.packageName != context.packageName) {
            apkFile.delete()
            return Result.failure(
                SecurityException("Identificador do pacote incompatível (${archiveInfo.packageName} != ${context.packageName}). O arquivo foi excluído.")
            )
        }

        // 3. Signature Comparison against Installed App
        val installedSignatures = getInstalledSignatures()
        val apkSignatures = extractSignaturesFromArchiveInfo(archiveInfo)

        if (installedSignatures.isEmpty() || apkSignatures.isEmpty()) {
            Log.w(TAG, "Não foi possível extrair certificados para conferência estrita de assinatura pré-instalação.")
        } else {
            val matching = apkSignatures.any { apkSig ->
                installedSignatures.any { instSig -> apkSig.contentEquals(instSig) }
            }

            if (!matching) {
                apkFile.delete()
                return Result.failure(
                    SecurityException("Assinatura do APK incompatível com a versão instalada. O Android recusaria a instalação. O arquivo foi excluído por segurança.")
                )
            }
            Log.i(TAG, "Assinatura do APK compatível e verificada com sucesso.")
        }

        return Result.success(true)
    }

    private fun getInstalledSignatures(): List<ByteArray> {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = pInfo.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                val pInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pInfo.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao obter assinaturas do app instalado: ${e.message}")
            emptyList()
        }
    }

    private fun extractSignaturesFromArchiveInfo(archiveInfo: android.content.pm.PackageInfo): List<ByteArray> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = archiveInfo.signingInfo ?: return emptyList()
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners.map { it.toByteArray() }
                } else {
                    signingInfo.signingCertificateHistory.map { it.toByteArray() }
                }
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.map { it.toByteArray() } ?: emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Installs the downloaded update using Root (silent) or Non-root (PackageInstaller/Intent).
     * Cleans up the APK file after installation.
     */
    suspend fun installApk(apkFile: File, preferRoot: Boolean = true): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!apkFile.exists()) {
            return@withContext Result.failure(IllegalStateException("Arquivo do instalador não encontrado."))
        }

        // Try root installation if requested and shell is available
        if (preferRoot && Shell.isAppGrantedRoot() == true) {
            try {
                Log.i(TAG, "Iniciando instalação silenciosa via Root (pm install -r)...")
                val installCommand = "pm install -r \"${apkFile.absolutePath}\""
                val installResult = Shell.cmd(installCommand).exec()

                if (installResult.isSuccess) {
                    Log.i(TAG, "Instalação silenciosa concluída com sucesso! Reabrindo o app...")
                    // Restart app automatically
                    Shell.cmd("am start -n ${context.packageName}/${context.packageName}.MainActivity").exec()
                    // Cleanup APK
                    try { apkFile.delete() } catch (_: Exception) {}
                    return@withContext Result.success(true)
                } else {
                    val errOutput = installResult.out.joinToString("\n")
                    Log.w(TAG, "Falha na instalação via Root: $errOutput. Tentando fluxo padrão do sistema...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro no comando Root: ${e.message}. Recorrendo ao fluxo padrão.")
            }
        }

        // Non-root installation via system package installer
        try {
            // Check unknown sources permission on Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    return@withContext Result.failure(
                        UnknownSourcesPermissionRequiredException(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    )
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar instalador do sistema: ${e.message}", e)
            try { apkFile.delete() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Cleans up all files in cache/updates directory
     */
    fun cleanUpdatesCache() {
        try {
            val dir = File(context.cacheDir, "updates")
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }
}

class UnknownSourcesPermissionRequiredException(val intent: Intent) :
    Exception("É necessário autorizar a instalação de aplicativos desta fonte nas configurações do Android.")

