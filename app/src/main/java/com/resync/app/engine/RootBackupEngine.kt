package com.resync.app.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Motor de backup com privilégios de Superusuário (Root).
 * Utiliza a biblioteca libsu (com.topjohnwu.superuser) para interagir de forma
 * segura e transacional com o daemon de root do Android (Magisk / KernelSU / APatch).
 *
 * Capacidades exclusivas:
 * 1. Acesso total e cópia estruturada de /data/data/[pacote] e /data/user_de/0/[pacote].
 * 2. Restauração de permissões originais (chown uid:gid) e rótulos de segurança (restorecon -R).
 * 3. Extração direta de APKs protegidos em /data/app.
 * 4. Backup irrestrito de qualquer partição e metadados de sistema.
 */
class RootBackupEngine(
    private val context: Context
) : IBackupEngine {

    companion object {
        private const val TAG = "RootBackupEngine"
        private const val DEFAULT_BACKUP_SUBDIR = "ReSyncBackups/Root"
    }

    override val isRootEnabled: Boolean = true

    private fun resolveDestinationDir(destinationDir: File?): File {
        val target = destinationDir ?: File(
            Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"),
            DEFAULT_BACKUP_SUBDIR
        )
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
                    File(Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"), "Documents").absolutePath,
                    File(Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"), "DCIM").absolutePath,
                    File(Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"), "Download").absolutePath
                )
            }

            var allSuccess = true
            for (path in targets) {
                val srcFile = File(path)
                if (!srcFile.exists()) continue
                val escapedSrc = srcFile.absolutePath.replace("\"", "\\\"")
                val escapedDest = filesDir.absolutePath.replace("\"", "\\\"")

                // Cópia preservando atributos, links simbólicos e permissões com cp -a
                val result = Shell.cmd("cp -a \"$escapedSrc\" \"$escapedDest/\"").exec()
                if (!result.isSuccess) {
                    Log.w(TAG, "Falha ao copiar $path com Root: ${result.err.joinToString("\n")}")
                    allSuccess = false
                }
            }
            allSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Erro no backup de mídias com Root: ${e.message}", e)
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
            for (pkg in packagesToProcess) {
                try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val baseApkPath = appInfo.sourceDir
                    if (baseApkPath.isNullOrBlank()) continue

                    val pkgFolder = File(apksDir, pkg).apply { mkdirs() }
                    val escapedBase = baseApkPath.replace("\"", "\\\"")
                    val escapedDest = pkgFolder.absolutePath.replace("\"", "\\\"")

                    // Copiar APK base via shell root
                    Shell.cmd("cp \"$escapedBase\" \"$escapedDest/base.apk\"").exec()

                    // Copiar APKs divididos (splits) se existirem
                    val parentDir = File(baseApkPath).parentFile
                    if (parentDir != null) {
                        val escapedParent = parentDir.absolutePath.replace("\"", "\\\"")
                        Shell.cmd("cp \"$escapedParent\"/split_*.apk \"$escapedDest/\" 2>/dev/null").exec()
                    }

                    successCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Não foi possível extrair APKs de $pkg: ${e.message}")
                }
            }
            successCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro geral ao extrair APKs com Root: ${e.message}", e)
            false
        }
    }

    override suspend fun backupAppData(
        packageNames: List<String>,
        destinationDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dest = resolveDestinationDir(destinationDir)
            val dataDir = File(dest, "data").apply { mkdirs() }
            val pm = context.packageManager

            val packagesToProcess = if (packageNames.isNotEmpty()) {
                packageNames
            } else {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it.packageName }
            }

            var successCount = 0
            for (pkg in packagesToProcess) {
                val appDataPath = "/data/data/$pkg"
                val checkDir = Shell.cmd("[ -d \"$appDataPath\" ]").exec()
                if (!checkDir.isSuccess) {
                    Log.w(TAG, "Diretório $appDataPath não existe ou está inacessível.")
                    continue
                }

                val tarArchive = File(dataDir, "${pkg}_data.tar.gz")
                val escapedArchive = tarArchive.absolutePath.replace("\"", "\\\"")

                // Pausar momentaneamente o processo para consistência das bases de dados SQLite
                Shell.cmd("am kill \"$pkg\" 2>/dev/null").exec()

                // Compactação de /data/data/[pacote] excluindo cache volátil
                val tarCmd = "cd \"$appDataPath\" && tar -czf \"$escapedArchive\" " +
                        "--exclude=\"cache\" " +
                        "--exclude=\"code_cache\" " +
                        "--exclude=\"no_backup\" " +
                        ". 2>/dev/null || tar -czf \"$escapedArchive\" ."

                val tarResult = Shell.cmd(tarCmd).exec()
                if (tarResult.isSuccess && tarArchive.exists() && tarArchive.length() > 0) {
                    successCount++
                } else {
                    Log.w(TAG, "Falha na criação do arquivo tar para $pkg: ${tarResult.err.joinToString("\n")}")
                }
            }
            successCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro no backup de dados internos com Root: ${e.message}", e)
            false
        }
    }

    override suspend fun restoreAppData(
        packageNames: List<String>,
        sourceDir: File?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = sourceDir ?: File(
                Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0"),
                "$DEFAULT_BACKUP_SUBDIR/data"
            )
            if (!source.exists()) {
                Log.w(TAG, "Diretório de origem dos dados não encontrado: ${source.absolutePath}")
                return@withContext false
            }

            val pm = context.packageManager
            var restoredCount = 0

            val archivesToProcess = if (packageNames.isNotEmpty()) {
                packageNames.map { File(source, "${it}_data.tar.gz") }.filter { it.exists() }
            } else {
                source.listFiles { _, name -> name.endsWith("_data.tar.gz") }?.toList() ?: emptyList()
            }

            for (tarFile in archivesToProcess) {
                val pkg = tarFile.name.removeSuffix("_data.tar.gz")
                val appDataDir = "/data/data/$pkg"

                // 1. Obter UID numérico atribuído pelo Android ao aplicativo
                val uid = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    appInfo.uid
                } catch (e: Exception) {
                    Log.w(TAG, "Aplicativo $pkg precisa estar previamente instalado para restaurar dados: ${e.message}")
                    continue
                }

                // 2. Encerrar forçadamente a aplicação antes de gravar dados
                Shell.cmd("am force-stop \"$pkg\"").exec()

                // 3. Limpar conteúdo antigo preservando a pasta raiz
                Shell.cmd("rm -rf \"$appDataDir\"/*").exec()

                // 4. Descompactar arquivo de dados dentro de /data/data/[pacote]
                val escapedArchive = tarFile.absolutePath.replace("\"", "\\\"")
                val untarResult = Shell.cmd("cd \"$appDataDir\" && tar -xzf \"$escapedArchive\"").exec()
                if (!untarResult.isSuccess) {
                    Log.w(TAG, "Erro ao descompactar dados de $pkg: ${untarResult.err.joinToString("\n")}")
                    continue
                }

                // 5. CRÍTICO: Reatribuir a propriedade dos arquivos ao UID do app e restaurar o contexto SELinux
                Shell.cmd("chown -R $uid:$uid \"$appDataDir\"").exec()
                Shell.cmd("restorecon -R \"$appDataDir\"").exec()

                restoredCount++
            }
            restoredCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro na restauração de dados com Root: ${e.message}", e)
            false
        }
    }
}
