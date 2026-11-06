package com.resync.app.engine.provider

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.resync.app.engine.BackupIntegrityValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Provedor Universal de Backup e Restauração de Aplicativos (APKs e Split APKs).
 *
 * MODO SEM ROOT:
 * - Extrai APKs base e split APKs originais através dos caminhos públicos do PackageManager.
 * - Restaura utilizando a API oficial PackageInstaller (com suporte a split APKs) ou Intent de Instalação.
 * - AVISO CRÍTICO: Não extrai /data/data (dados privados) no modo sem Root.
 */
class ApplicationBackupProvider(private val context: Context) {

    companion object {
        private const val TAG = "AppBackupProvider"
        private const val BUFFER_SIZE = 64 * 1024
    }

    data class AppExportMetadata(
        val packageName: String,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val baseApkName: String,
        val splitApkNames: List<String>,
        val sha256: String,
        val isRootDataIncluded: Boolean = false
    )

    private val gson = Gson()

    /**
     * Extrai os APKs de uma lista de pacotes selecionados para o diretório de destino.
     */
    suspend fun extractApks(
        packageNames: List<String>,
        outputDir: File,
        onProgress: ((current: Int, total: Int, pkg: String) -> Unit)? = null
    ): List<AppExportMetadata> = withContext(Dispatchers.IO) {
        val result = mutableListOf<AppExportMetadata>()
        val pm = context.packageManager
        outputDir.mkdirs()

        val buffer = ByteArray(BUFFER_SIZE)

        packageNames.forEachIndexed { index, pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val baseApkPath = appInfo.sourceDir ?: return@forEachIndexed
                val baseFile = File(baseApkPath)
                if (!baseFile.exists() || !baseFile.canRead()) return@forEachIndexed

                val pkgDir = File(outputDir, pkg).apply { mkdirs() }
                val targetBase = File(pkgDir, "base.apk")

                FileInputStream(baseFile).use { input ->
                    FileOutputStream(targetBase).use { output ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                    }
                }

                val splitNames = mutableListOf<String>()
                // Extrair split APKs
                val splitSourceDirs = appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs
                if (splitSourceDirs != null) {
                    for (splitPath in splitSourceDirs) {
                        val splitFile = File(splitPath)
                        if (splitFile.exists() && splitFile.canRead()) {
                            val targetSplit = File(pkgDir, splitFile.name)
                            FileInputStream(splitFile).use { input ->
                                FileOutputStream(targetSplit).use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } != -1) {
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                            splitNames.add(splitFile.name)
                        }
                    }
                }

                val baseSha = BackupIntegrityValidator.calculateFileSha256(targetBase) ?: ""
                val packageInfo = try { pm.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
                val vName = packageInfo?.versionName ?: "1.0"
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode ?: 1L
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong() ?: 1L
                }
                val label = pm.getApplicationLabel(appInfo).toString()

                val meta = AppExportMetadata(
                    packageName = pkg,
                    appName = label,
                    versionName = vName,
                    versionCode = vCode,
                    baseApkName = "base.apk",
                    splitApkNames = splitNames,
                    sha256 = baseSha,
                    isRootDataIncluded = false
                )
                result.add(meta)

                onProgress?.invoke(index + 1, packageNames.size, pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao extrair APK de $pkg: ${e.message}")
            }
        }

        // Grava metadata
        val metaFile = File(outputDir, "apps_manifest.json")
        metaFile.writeText(gson.toJson(result))

        result
    }

    /**
     * Inicia sessão do PackageInstaller oficial do Android para instalar APK e Split APKs.
     */
    fun createInstallSession(pkgDir: File): Boolean {
        try {
            val baseApk = File(pkgDir, "base.apk")
            if (!baseApk.exists()) return false

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            val buffer = ByteArray(BUFFER_SIZE)

            // Escrever base.apk
            baseApk.inputStream().use { input ->
                session.openWrite("base.apk", 0, baseApk.length()).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    session.fsync(output)
                }
            }

            // Escrever split APKs
            pkgDir.listFiles()?.forEach { split ->
                if (split.isFile && split.name.startsWith("split_") && split.name.endsWith(".apk")) {
                    split.inputStream().use { input ->
                        session.openWrite(split.name, 0, split.length()).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            session.fsync(output)
                        }
                    }
                }
            }

            val intent = Intent(context, com.resync.app.MainActivity::class.java).apply {
                action = "com.resync.app.INSTALL_COMPLETE"
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao criar sessão de instalação: ${e.message}", e)
            return false
        }
    }

    /**
     * Cria intent ACTION_VIEW para instalação direta pelo instalador de pacotes do sistema.
     */
    fun createInstallIntent(apkFile: File): Intent? {
        if (!apkFile.exists()) return null
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao criar intent de instalação: ${e.message}")
            null
        }
    }
}
