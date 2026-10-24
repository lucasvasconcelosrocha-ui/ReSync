package com.resync.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Motor de backup em modo padrão / Sem Root (Non-Root Engine).
 * Opera estritamente dentro da sandbox de segurança do sistema operacional Android.
 *
 * Princípios de arquitetura:
 * 1. Mídias e Documentos: Acessados via APIs públicas de armazenamento e permissões padrão (Scoped Storage).
 * 2. APKs: Copiados diretamente dos caminhos públicos de instalação fornecidos pelo PackageManager.
 * 3. Dados de Aplicativos (/data/data): Bloqueados pelo kernel do Linux e SELinux para apps de terceiros.
 *    As funções de backup/restauro de dados retornam 'false' e registram exceções controladas,
 *    garantindo que o fluxo não entre em colapso e informando a interface adequadamente.
 */
class NormalBackupEngine(
    private val context: Context,
    private val throwOnRestrictedOperations: Boolean = false
) : IBackupEngine {

    companion object {
        private const val TAG = "NormalBackupEngine"
        private const val DEFAULT_BACKUP_SUBDIR = "ReSyncBackups/Normal"
    }

    override val isRootEnabled: Boolean = false

    private fun resolveDestinationDir(destinationDir: File?): File {
        val baseDir = try {
            context.getExternalFilesDir(null) ?: Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        } catch (_: Throwable) {
            Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
        }
        val target = destinationDir ?: File(baseDir, DEFAULT_BACKUP_SUBDIR)
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    override suspend fun backupMediaAndFiles(
        sourcePaths: List<String>,
        destinationDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dest = resolveDestinationDir(destinationDir)
            val filesDir = File(dest, "files").apply { mkdirs() }

            val targets = if (sourcePaths.isNotEmpty()) {
                sourcePaths
            } else {
                listOf(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                ).map { it.absolutePath }
            }

            var copiedFiles = 0
            for (path in targets) {
                val src = File(path)
                if (!src.exists() || !src.canRead()) continue

                if (src.isDirectory) {
                    val targetSubDir = File(filesDir, src.name).apply { mkdirs() }
                    src.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile && file.canRead()) {
                            val relPath = file.relativeTo(src).path
                            val outFile = File(targetSubDir, relPath)
                            outFile.parentFile?.mkdirs()
                            file.inputStream().use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            copiedFiles++
                        }
                    }
                } else if (src.isFile) {
                    val outFile = File(filesDir, src.name)
                    src.inputStream().use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    copiedFiles++
                }
            }
            copiedFiles > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao efetuar backup de mídias no modo Normal: ${e.message}", e)
            false
        }
    }

    override suspend fun extractInstalledApks(
        packageNames: List<String>,
        destinationDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dest = resolveDestinationDir(destinationDir)
            val apksDir = File(dest, "apks").apply { mkdirs() }
            val pm = context.packageManager

            val packagesToProcess = if (packageNames.isNotEmpty()) {
                packageNames
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it.packageName }
            }

            var successCount = 0
            val buffer = ByteArray(64 * 1024)

            for (pkg in packagesToProcess) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val baseApkPath = appInfo.sourceDir ?: continue
                    val baseFile = File(baseApkPath)
                    if (!baseFile.exists() || !baseFile.canRead()) continue

                    val pkgFolder = File(apksDir, pkg).apply { mkdirs() }
                    val targetBase = File(pkgFolder, "base.apk")

                    FileInputStream(baseFile).use { input ->
                        FileOutputStream(targetBase).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } > 0) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    // Extrair splits acessíveis
                    baseFile.parentFile?.listFiles()?.forEach { splitFile ->
                        if (splitFile.isFile && splitFile.name.startsWith("split_") && splitFile.name.endsWith(".apk") && splitFile.canRead()) {
                            val targetSplit = File(pkgFolder, splitFile.name)
                            FileInputStream(splitFile).use { input ->
                                FileOutputStream(targetSplit).use { output ->
                                    var read: Int
                                    while (input.read(buffer).also { read = it } > 0) {
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        }
                    }

                    successCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Falha na leitura do APK de $pkg sem root: ${e.message}")
                }
            }
            successCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral ao extrair APKs no modo Normal: ${e.message}", e)
            false
        }
    }

    /**
     * No Android, o acesso a /data/data/[pacote] é estritamente proibido sem permissões de Superusuário.
     * Retorna false de forma segura e controlada, ou dispara UnsupportedOperationException se configurado.
     */
    override suspend fun backupAppData(
        packageNames: List<String>,
        destinationDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        val message = "Operação restrita: O backup de dados internos (/data/data) exige privilégios de Superusuário (Root). " +
                "O dispositivo está operando em modo Normal/Sem Root."
        Log.w(TAG, message)

        if (throwOnRestrictedOperations) {
            throw UnsupportedOperationException(message)
        }
        false
    }

    /**
     * A gravação de arquivos dentro do diretório sandbox de outros aplicativos em /data/data
     * é terminantemente bloqueada pelo kernel sem privilégios de Root.
     */
    override suspend fun restoreAppData(
        packageNames: List<String>,
        sourceDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        val message = "Operação restrita: A restauração de dados internos e preferências de aplicativos exige Root. " +
                "O dispositivo está operando em modo Normal/Sem Root."
        Log.w(TAG, message)

        if (throwOnRestrictedOperations) {
            throw UnsupportedOperationException(message)
        }
        false
    }
}
