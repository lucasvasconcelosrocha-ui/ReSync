package com.resync.app.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Environment
import android.provider.CallLog
import android.provider.Telephony
import android.util.Base64
import com.google.gson.Gson
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import com.resync.app.util.FormatUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class BackupProgress(
    val stage: String,
    val currentItemName: String,
    val progressPercent: Float,
    val bytesProcessed: Long = 0L,
    val totalBytesEstimated: Long = 0L,
    val isFinished: Boolean = false,
    val error: String? = null,
    val currentItemIndex: Int = 0,
    val totalItems: Int = 0,
    val currentItemType: BackupItemType = BackupItemType.FILE,
    val completedItems: List<CompletedItemInfo> = emptyList(),
    val isPaused: Boolean = false,
    val uploadSpeedBytesPerSec: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L,
    val compressionSpeedBytesPerSec: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L,
    val speedBytesPerSec: Long = uploadSpeedBytesPerSec,
    val compressBytesPerSec: Long = compressionSpeedBytesPerSec,
    val currentPackageName: String = "",
    val containerFile: java.io.File? = null,
    val parts: List<OperationPartInfo> = emptyList(),
    val thermalMode: String = "Máximo",
    val deviceTemperature: Float = 0f,
    val localStage: String = "",
    val itemsPerSecond: Float = 0f
)

/**
 * Sliding-window rolling average speed calculator to prevent wild fluctuations in real-time UI.
 */
class RollingSpeedCalculator(private val windowSizeMs: Long = 10000L) {
    private val samples = java.util.ArrayDeque<Pair<Long, Long>>()
    private var startTimeMs: Long = 0L

    @Synchronized
    fun update(cumulativeBytes: Long): Long {
        val now = System.currentTimeMillis()
        if (startTimeMs == 0L) startTimeMs = now
        samples.addLast(Pair(now, cumulativeBytes))
        while (samples.size > 1 && (now - samples.first.first) > windowSizeMs) {
            samples.removeFirst()
        }
        if (samples.size < 2) return 0L
        val oldest = samples.first
        val timeDeltaMs = now - oldest.first
        if ((now - startTimeMs) < 3000L || timeDeltaMs < 1000L) return 0L
        val bytesDelta = (cumulativeBytes - oldest.second).coerceAtLeast(0L)
        return (bytesDelta * 1000L) / timeDeltaMs
    }

    @Synchronized
    fun reset() {
        samples.clear()
        startTimeMs = 0L
    }
}

/**
 * Main Backup Orchestration Engine.
 * Executes granular, selective backups with real post-backup SHA-256 integrity validation.
 */
class BackupEngine(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val dnsManager = DnsManager(context)
    private val gson = Gson()

    private val isPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var pauseDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private var activeStagingDir: File? = null

    private val controlScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    val cpuGovernor = CpuGovernor(context)

    fun pause() {
        isPaused.set(true)
        if (pauseDeferred == null || pauseDeferred?.isCompleted == true) {
            pauseDeferred = kotlinx.coroutines.CompletableDeferred()
        }
        controlScope.launch {
            try {
                // Executar em processo / shell isolado para não disputar o lock do Shell compartilhado do backup
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -STOP tar; pkill -STOP gzip; pkill -STOP zip")).waitFor()
            } catch (_: Throwable) {
                try {
                    com.topjohnwu.superuser.Shell.Builder.create().build().use { standaloneShell ->
                        standaloneShell.newJob().add("pkill -STOP tar; pkill -STOP gzip; pkill -STOP zip").exec()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    fun resume() {
        isPaused.set(false)
        cpuGovernor.overrideThermalProtection() // Retomada manual força continuação (ignora proteção térmica por 10 min)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
        controlScope.launch {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -CONT tar; pkill -CONT gzip; pkill -CONT zip")).waitFor()
            } catch (_: Throwable) {
                try {
                    com.topjohnwu.superuser.Shell.Builder.create().build().use { standaloneShell ->
                        standaloneShell.newJob().add("pkill -CONT tar; pkill -CONT gzip; pkill -CONT zip").exec()
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    fun cancel() {
        isCancelled.set(true)
        isPaused.set(false)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
        controlScope.launch {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 tar; pkill -9 gzip; pkill -9 zip; pkill -9 cp")).waitFor()
            } catch (_: Throwable) {
                try {
                    com.topjohnwu.superuser.Shell.Builder.create().build().use { standaloneShell ->
                        standaloneShell.newJob().add("pkill -9 tar; pkill -9 gzip; pkill -9 zip; pkill -9 cp").exec()
                    }
                } catch (_: Throwable) {}
            }
            try {
                activeStagingDir?.deleteRecursively()
            } catch (_: Throwable) {}
        }
    }

    suspend fun checkPauseAndCancel(
        onPausedNotice: (suspend () -> Unit)? = null,
        onResumedNotice: (suspend () -> Unit)? = null
    ) {
        if (isCancelled.get()) {
            throw CancellationException("Backup cancelado pelo usuário.")
        }
        if (isPaused.get()) {
            onPausedNotice?.invoke()
            while (isPaused.get() && !isCancelled.get()) {
                val d = pauseDeferred
                if (d != null) {
                    kotlinx.coroutines.withTimeoutOrNull(500L) { d.await() }
                } else {
                    kotlinx.coroutines.delay(500L)
                }
            }
            if (isCancelled.get()) {
                throw CancellationException("Backup cancelado pelo usuário.")
            }
            onResumedNotice?.invoke()
        }
    }

    /**
     * Executes a selective backup session
     */
    fun hasRootOrManageExternalStorage(): Boolean {
        val hasManage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            true
        }
        val hasRoot = com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
        return hasManage || hasRoot
    }

    /**
     * Executes a selective backup session
     */
    fun performSelectiveBackup(
        backupTitle: String,
        selectedApps: List<Pair<AppInfo, AppSelectionOptions>> = emptyList(),
        selectedFilesAndFolders: List<String> = emptyList(),
        includeSms: Boolean = false,
        includeCalls: Boolean = false,
        includeContacts: Boolean = false,
        includeDns: Boolean = false,
        includeWallpaper: Boolean = false,
        includeWallpaperHome: Boolean = false,
        includeWallpaperLock: Boolean = false,
        includeWifi: Boolean = false,
        includeBluetooth: Boolean = false,
        filter: BackupFilter? = null,
        encryptionPassword: String? = null,
        destinationDir: File? = null,
        destinationLabel: String? = null,
        destinationType: BackupDestinationType = BackupDestinationType.SOMENTE_INTERNA
    ): Flow<BackupProgress> = channelFlow<BackupProgress> {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {}

        val backupId = com.resync.app.data.model.BackupPaths.BACKUP_FILE_PREFIX + System.currentTimeMillis()
        isPaused.set(false)
        isCancelled.set(false)

        val completedItemsList = mutableListOf<CompletedItemInfo>()
        val rateLimiter = ProgressRateLimiter(500L) // Máximo 2 atualizações de progresso por segundo
        var latestRollingSpeed = 0L
        cpuGovernor.startStage("Iniciando")
        cpuGovernor.startPeriodicCpuLogging(10000L) { latestRollingSpeed } // Log a cada 10s: CPU, MB/s e Temperatura

        // Resolver flags de wallpaper garantindo compatibilidade com includeWallpaper legado
        val shouldBackupHome = includeWallpaperHome || (includeWallpaper && !includeWallpaperLock)
        val shouldBackupLock = includeWallpaperLock || (includeWallpaper && !includeWallpaperHome)

        // Excluir expressamente o próprio app (com.resync.app) da lista de apps a copiar
        val safeSelectedApps = selectedApps.filter { (app, _) ->
            !ProcessControlManager.isPackageProtected(app.packageName, context)
        }

        // 1. Pré-calcular total fixo de bytes e lotes/itens UMA VEZ antes de começar, e manter rigorosamente fixo
        var precalcItems = 0
        var precalcBytes = 0L

        for ((app, options) in safeSelectedApps) {
            var appItemCount = 0
            var appBytes = 0L
            if (options.includeBaseApk) {
                val baseLen = File(app.sourceDir).takeIf { it.exists() }?.length() ?: 30_000_000L
                appBytes += baseLen
                appItemCount++
            }
            if (options.includeSplits) {
                app.splitSourceDirs.forEach { splitPath ->
                    val splitLen = File(splitPath).takeIf { it.exists() }?.length() ?: 10_000_000L
                    appBytes += splitLen
                    appItemCount++
                }
            }
            if (options.includeData) {
                val dLen = app.sizeBytes.takeIf { it > 0 } ?: 15_000_000L
                appBytes += dLen
                appItemCount++
            }
            if (options.includeExternalData) {
                val edLen = 10_000_000L
                appBytes += edLen
                appItemCount++
            }
            if (options.includeObb) {
                val obbLen = 20_000_000L
                appBytes += obbLen
                appItemCount++
            }
            // Metadados + ícone (2 entradas)
            appItemCount += 2
            appBytes += 25_000L

            precalcItems += appItemCount.coerceAtLeast(1)
            precalcBytes += appBytes.coerceAtLeast(1024L)
        }

        for (fp in selectedFilesAndFolders) {
            val f = File(fp)
            if (f.exists()) {
                if (f.isFile) {
                    if (matchesFilter(f, filter)) {
                        precalcItems++
                        precalcBytes += f.length()
                    }
                } else if (f.isDirectory) {
                    try {
                        f.walkTopDown().filter { it.isFile && matchesFilter(it, filter) }.forEach { child ->
                            precalcItems++
                            precalcBytes += child.length()
                        }
                    } catch (_: Throwable) {
                        precalcItems += 10
                        precalcBytes += 50_000_000L
                    }
                }
            }
        }

        if (includeSms) { precalcItems++; precalcBytes += 5_000_000L }
        if (includeCalls) { precalcItems++; precalcBytes += 2_000_000L }
        if (includeContacts) { precalcItems++; precalcBytes += 3_000_000L }
        if (includeDns) { precalcItems++; precalcBytes += 50_000L }
        if (includeWifi) { precalcItems++; precalcBytes += 50_000L }
        if (shouldBackupHome) { precalcItems++; precalcBytes += 4_000_000L }
        if (shouldBackupLock) { precalcItems++; precalcBytes += 4_000_000L }
        if (includeBluetooth) { precalcItems++; precalcBytes += 50_000L }

        val fixedTotalBytes = precalcBytes.coerceAtLeast(10_000_000L)
        val fixedTotalItems = precalcItems.coerceAtLeast(1)

        val progressEstimator = BackupProgressEstimator(
            totalBytes = fixedTotalBytes,
            totalItems = fixedTotalItems,
            windowSeconds = 30.0
        )

        var latestEmittedProgress: BackupProgress? = null
        suspend fun emitProgress(p: BackupProgress) {
            latestEmittedProgress = p
            send(p)
        }

        val etaCountdownTickerJob = launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000L)
                if (!isPaused.get() && !isCancelled.get()) {
                    val currentEta = progressEstimator.tickCountdown()
                    val curr = latestEmittedProgress
                    if (curr != null && !curr.isFinished && curr.estimatedRemainingSeconds > 0L && curr.estimatedRemainingSeconds != currentEta) {
                        val ticked = curr.copy(
                            estimatedRemainingSeconds = currentEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                        latestEmittedProgress = ticked
                        send(ticked)
                    }
                }
            }
        }

        val initialProgress = BackupProgress(
            stage = "Iniciando",
            currentItemName = "Verificando permissões e espaço de armazenamento...",
            progressPercent = 0.02f,
            bytesProcessed = 0L,
            totalBytesEstimated = fixedTotalBytes,
            currentItemIndex = 0,
            totalItems = fixedTotalItems,
            estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING
        )
        emitProgress(initialProgress)
        dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Iniciando backup: $backupTitle")

        // 0. Verify broad file access permissions if saving to internal storage root
        var effectiveVaultDir: File? = destinationDir
        if (effectiveVaultDir == null && !hasRootOrManageExternalStorage()) {
            val fallbackBase = context.getExternalFilesDir(null) ?: context.filesDir
            val fallbackVault = File(fallbackBase, com.resync.app.data.model.BackupPaths.BACKUP_DIR_NAME)
            effectiveVaultDir = fallbackVault
        }

        // Staging directory for atomic generation and hash validation
        val stagingDir = File(context.cacheDir, "staging_$backupId")
        stagingDir.mkdirs()
        try { File(stagingDir, ".nomedia").createNewFile() } catch (_: Throwable) {}
        activeStagingDir = stagingDir
        val stagingFile = File(stagingDir, "$backupId${com.resync.app.data.model.BackupPaths.BACKUP_FILE_EXTENSION}")

        // Criar .nomedia em ReSyncBackups na memória interna e no diretório de destino
        try {
            val defaultVaultDir = com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir()
            defaultVaultDir.mkdirs()
            File(defaultVaultDir, ".nomedia").createNewFile()
            effectiveVaultDir?.let {
                it.mkdirs()
                File(it, ".nomedia").createNewFile()
            }
        } catch (_: Throwable) {}

        var createdFinalFile: File? = null
        var createdFinalDir: File? = null

        try {
            val packEntries = mutableListOf<ArchiveEngine.PackEntry>()
            val manifestItems = mutableListOf<ManifestItem>()

            var totalEstimatedSize = 0L

            // 1. Process Selected Apps (Base APK + Splits + App Data tar.gz + Metadata JSON)
            cpuGovernor.startStage("Processamento de Aplicativos")
            val totalApps = safeSelectedApps.size
            for ((index, pair) in safeSelectedApps.withIndex()) {
                val currentItemIdx = ((index + 1).toFloat() / totalApps.coerceAtLeast(1).toFloat() * (safeSelectedApps.size * 3)).toInt().coerceIn(1, fixedTotalItems)
                cpuGovernor.throttleIfNeeded(
                    onThermalWait = { temp ->
                        emitProgress(BackupProgress("Aguardando o aparelho esfriar", "Temperatura em %.1f °C (> 42 °C). Resfriando dispositivo...".format(java.util.Locale.US, temp), 0.05f, isPaused = true, completedItems = completedItemsList.toList(), bytesProcessed = 0L, totalBytesEstimated = fixedTotalBytes, currentItemIndex = currentItemIdx, totalItems = fixedTotalItems, estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING))
                    },
                    onThermalResume = {
                        emitProgress(BackupProgress("Retomando", "Dispositivo resfriado. Continuando backup...", 0.05f, isPaused = false, completedItems = completedItemsList.toList(), bytesProcessed = 0L, totalBytesEstimated = fixedTotalBytes, currentItemIndex = currentItemIdx, totalItems = fixedTotalItems, estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING))
                    },
                    isCancelled = { isCancelled.get() }
                )
                checkPauseAndCancel(
                    onPausedNotice = {
                        emitProgress(BackupProgress("Pausado", "Backup pausado em ${pair.first.appName}", 0.05f, isPaused = true, completedItems = completedItemsList.toList(), bytesProcessed = 0L, totalBytesEstimated = fixedTotalBytes, currentItemIndex = currentItemIdx, totalItems = fixedTotalItems, estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING))
                    }
                )
                val (app, options) = pair
                try {
                    val stepFraction = if (totalApps > 0) (index.toFloat() / totalApps.toFloat()) else 0f
                    val appProgressPercent = 0.05f + (stepFraction * 0.30f)
                    val estimatedProcessedSoFar = (fixedTotalBytes * appProgressPercent).toLong().coerceIn(0L, fixedTotalBytes)
                    val eta = progressEstimator.update(estimatedProcessedSoFar, currentItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Aplicativos",
                            currentItemName = "${app.appName} (${app.packageName})",
                            progressPercent = appProgressPercent,
                            bytesProcessed = estimatedProcessedSoFar,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemType = BackupItemType.APP_APK,
                            currentItemIndex = currentItemIdx,
                            totalItems = fixedTotalItems,
                            currentPackageName = app.packageName,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = eta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )

                val appFileEntries = mutableListOf<AppBackupFileEntry>()

                // A) am force-stop <pkg> com proteção garantida antes de copiar dados
                ProcessControlManager.safeForceStop(
                    context = context,
                    targetPackage = app.packageName,
                    progressPercent = appProgressPercent,
                    sourceTag = "BackupEngine"
                )

                // Retrieve all actual APK paths (base and all splits) via pm path
                val pmPathCmd = com.topjohnwu.superuser.Shell.cmd("pm path ${app.packageName}").exec()
                val realApkPaths = if (pmPathCmd.isSuccess && pmPathCmd.out.isNotEmpty()) {
                    pmPathCmd.out.filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
                } else {
                    (listOf(app.sourceDir) + app.splitSourceDirs).distinct().filter { it.isNotBlank() }
                }

                val realBaseApkPath = realApkPaths.find { it.endsWith("base.apk", ignoreCase = true) } ?: realApkPaths.firstOrNull() ?: app.sourceDir
                val realSplitApkPaths = realApkPaths.filter { it != realBaseApkPath }

                // Base APK
                if (options.includeBaseApk && realBaseApkPath.isNotBlank()) {
                    var baseApk = File(realBaseApkPath)
                    if (!baseApk.canRead()) {
                        val stagedBase = File(stagingDir, "${app.packageName}_base.apk")
                        com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 cp \"${baseApk.absolutePath}\" \"${stagedBase.absolutePath}\" && chmod 644 \"${stagedBase.absolutePath}\"").exec()
                        if (stagedBase.exists() && stagedBase.length() > 0) {
                            baseApk = stagedBase
                        }
                    }

                    if (baseApk.exists() && baseApk.length() > 0) {
                        val baseSha256 = SecurityManager.computeFileSha256(baseApk)
                        val entryName = "apps/${app.packageName}/base.apk"
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.APP_APK,
                            name = "${app.appName} (Base APK)",
                            originalPath = realBaseApkPath,
                            archiveEntryPath = entryName,
                            sizeBytes = baseApk.length(),
                            sha256Checksum = baseSha256,
                            compressionMethod = "STORED",
                            metadata = mapOf(
                                "packageName" to app.packageName,
                                "versionName" to app.versionName,
                                "versionCode" to app.versionCode.toString(),
                                "minSdk" to app.minSdkVersion.toString(),
                                "targetSdk" to app.targetSdkVersion.toString()
                            )
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = baseApk, manifestItem = item, isStoreOnly = true))
                        manifestItems.add(item)
                        totalEstimatedSize += baseApk.length()
                        appFileEntries.add(AppBackupFileEntry("base.apk", entryName, baseApk.length(), baseSha256, "base_apk"))
                    } else {
                        val warnMsg = "APK base de ${app.packageName} não pôde ser lido."
                        dbHelper.insertLog(LogLevel.WARN, "BackupEngine", warnMsg)
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "${app.appName} (Base APK)",
                                type = BackupItemType.APP_APK,
                                success = false,
                                error = warnMsg,
                                stage = "Coleta de APK",
                                causeException = "Arquivo APK base inacessível em $realBaseApkPath",
                                suggestedFix = "Verifique se o aplicativo foi desinstalado recentemente ou conceda acesso root."
                            )
                        )
                    }
                }

                // Split APKs
                if (options.includeSplits && realSplitApkPaths.isNotEmpty()) {
                    for ((splitIdx, splitPath) in realSplitApkPaths.withIndex()) {
                        var splitFile = File(splitPath)
                        if (!splitFile.canRead()) {
                            val stagedSplit = File(stagingDir, "${app.packageName}_split_$splitIdx.apk")
                            com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 cp \"${splitFile.absolutePath}\" \"${stagedSplit.absolutePath}\" && chmod 644 \"${stagedSplit.absolutePath}\"").exec()
                            if (stagedSplit.exists() && stagedSplit.length() > 0) {
                                splitFile = stagedSplit
                            }
                        }

                        if (splitFile.exists() && splitFile.length() > 0) {
                            val splitSha256 = SecurityManager.computeFileSha256(splitFile)
                            val entryName = "apps/${app.packageName}/splits/${splitFile.name}"
                            val item = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.APP_SPLIT_APKS,
                                name = "${app.appName} (${splitFile.name})",
                                originalPath = splitPath,
                                archiveEntryPath = entryName,
                                sizeBytes = splitFile.length(),
                                sha256Checksum = splitSha256,
                                compressionMethod = "STORED",
                                metadata = mapOf("packageName" to app.packageName, "isSplit" to "true")
                            )
                            packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = splitFile, manifestItem = item, isStoreOnly = true))
                            manifestItems.add(item)
                            totalEstimatedSize += splitFile.length()
                            appFileEntries.add(AppBackupFileEntry(splitFile.name, entryName, splitFile.length(), splitSha256, "split_apk"))
                        }
                    }
                }

                // Internal Data: /data/data/<pkg> (excluding cache and code_cache)
                if (options.includeData || options.includeInternalData) {
                    emitProgress(
                        BackupProgress(
                            stage = "Aplicativos",
                            currentItemName = "Gerando backup de dados de ${app.appName}...",
                            progressPercent = appProgressPercent,
                            bytesProcessed = estimatedProcessedSoFar.coerceAtMost(fixedTotalBytes),
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = currentItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = eta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )

                    if (PackageIgnorePolicy.isAlwaysIgnoredForData(app.packageName)) {
                        dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Dados de ${app.packageName} ignorados por política de componentes internos/sandbox.")
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "${app.appName} (Dados)",
                                type = BackupItemType.APP_DATA,
                                success = true,
                                isIgnored = true,
                                stage = "Compactação de Dados",
                                statusText = "Ignorado (sem dados)",
                                packageName = app.packageName
                            )
                        )
                    } else {
                        val dataTarFile = File(stagingDir, "${app.packageName}_data.tar")
                        try {
                            val tarCmd = com.topjohnwu.superuser.Shell.cmd(
                                "nice -n 19 ionice -c 3 tar -cf \"${dataTarFile.absolutePath}\" --exclude \"cache\" --exclude \"code_cache\" --exclude \"./cache\" --exclude \"./code_cache\" -C \"/data/data/${app.packageName}\" ."
                            ).exec()
                            val tarOutErr = (tarCmd.out + tarCmd.err).filter { it.isNotBlank() }.joinToString(" ")

                            if (dataTarFile.exists() && dataTarFile.length() >= 100L) {
                                val dataSha256 = SecurityManager.computeFileSha256(dataTarFile)
                                val dataEntryName = "apps/${app.packageName}/data.tar"
                                val dataItem = ManifestItem(
                                    id = UUID.randomUUID().toString(),
                                    type = BackupItemType.APP_DATA,
                                    name = "${app.appName} (Dados Internos)",
                                    originalPath = "/data/data/${app.packageName}",
                                    archiveEntryPath = dataEntryName,
                                    sizeBytes = dataTarFile.length(),
                                    sha256Checksum = dataSha256,
                                    compressionMethod = "STORED",
                                    metadata = mapOf("packageName" to app.packageName, "dataType" to "internal_data")
                                )
                                packEntries.add(ArchiveEngine.PackEntry(dataEntryName, sourceFile = dataTarFile, manifestItem = dataItem, isStoreOnly = true))
                                manifestItems.add(dataItem)
                                totalEstimatedSize += dataTarFile.length()
                                appFileEntries.add(AppBackupFileEntry("data.tar", dataEntryName, dataTarFile.length(), dataSha256, "internal_data"))
                                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Dados de ${app.packageName} arquivados com sucesso (tar uncompressed): ${dataTarFile.length()} bytes.")
                            } else {
                                if (PackageIgnorePolicy.shouldIgnoreIfEmptyData(app.packageName, app.isSystemApp)) {
                                    val infoMsg = "Dados de ${app.appName} vazios ou inacessíveis em componente do sistema (${if (dataTarFile.exists()) dataTarFile.length() else 0} bytes). Ignorado conforme política."
                                    dbHelper.insertLog(LogLevel.INFO, "BackupEngine", infoMsg)
                                    completedItemsList.add(
                                        CompletedItemInfo(
                                            name = "${app.appName} (Dados)",
                                            type = BackupItemType.APP_DATA,
                                            success = true,
                                            isIgnored = true,
                                            stage = "Compactação de Dados",
                                            statusText = "Ignorado (sem dados)",
                                            packageName = app.packageName
                                        )
                                    )
                                } else {
                                    val warnMsg = "Dados de ${app.appName} vazios ou inacessíveis (${if (dataTarFile.exists()) dataTarFile.length() else 0} bytes). ${tarOutErr.take(120)}"
                                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", warnMsg)
                                    completedItemsList.add(
                                        CompletedItemInfo(
                                            name = "${app.appName} (Dados)",
                                            type = BackupItemType.APP_DATA,
                                            success = false,
                                            isIgnored = false,
                                            error = warnMsg,
                                            stage = "Compactação de Dados",
                                            causeException = tarOutErr.ifBlank { "Falha ao executar comando tar em /data/data/${app.packageName}" },
                                            suggestedFix = "Permissão negada ao ler /data/data. Conceda acesso root via Magisk/KernelSU."
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (PackageIgnorePolicy.shouldIgnoreIfEmptyData(app.packageName, app.isSystemApp)) {
                                val infoMsg = "Exceção ao gerar tar de dados de componente do sistema ${app.appName}: ${e.message}. Ignorado conforme política."
                                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", infoMsg)
                                completedItemsList.add(
                                    CompletedItemInfo(
                                        name = "${app.appName} (Dados)",
                                        type = BackupItemType.APP_DATA,
                                        success = true,
                                        isIgnored = true,
                                        stage = "Compactação de Dados",
                                        statusText = "Ignorado (sem dados)",
                                        packageName = app.packageName
                                    )
                                )
                            } else {
                                val warnMsg = "Exceção ao gerar tar de dados de ${app.appName}: ${e.message}"
                                dbHelper.insertLog(LogLevel.WARN, "BackupEngine", warnMsg)
                                completedItemsList.add(
                                    CompletedItemInfo(
                                        name = "${app.appName} (Dados)",
                                        type = BackupItemType.APP_DATA,
                                        success = false,
                                        isIgnored = false,
                                        error = warnMsg,
                                        stage = "Compactação de Dados",
                                        causeException = "${e.javaClass.simpleName}: ${e.message}",
                                        suggestedFix = "Conceda acesso root ou verifique se o aplicativo não possui restrições do sistema."
                                    )
                                )
                            }
                        }
                    }
                }

                // Device Protected Data: /data/user_de/0/<pkg>
                if (options.includeUserDe) {
                    val checkDe = com.topjohnwu.superuser.Shell.cmd("[ -d \"/data/user_de/0/${app.packageName}\" ] && [ \"$(ls -A /data/user_de/0/${app.packageName} 2>/dev/null)\" ]").exec()
                    if (checkDe.isSuccess) {
                        val userDeTarFile = File(stagingDir, "${app.packageName}_user_de.tar")
                        val deCmd = com.topjohnwu.superuser.Shell.cmd(
                            "nice -n 19 ionice -c 3 tar -cf \"${userDeTarFile.absolutePath}\" --exclude \"cache\" --exclude \"code_cache\" -C \"/data/user_de/0/${app.packageName}\" ."
                        ).exec()
                        if (userDeTarFile.exists() && userDeTarFile.length() >= 100L) {
                            val deSha256 = SecurityManager.computeFileSha256(userDeTarFile)
                            val deEntryName = "apps/${app.packageName}/user_de.tar"
                            val deItem = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.APP_DATA,
                                name = "${app.appName} (Dados Criptografia do Dispositivo)",
                                originalPath = "/data/user_de/0/${app.packageName}",
                                archiveEntryPath = deEntryName,
                                sizeBytes = userDeTarFile.length(),
                                sha256Checksum = deSha256,
                                compressionMethod = "STORED",
                                metadata = mapOf("packageName" to app.packageName, "dataType" to "user_de")
                            )
                            packEntries.add(ArchiveEngine.PackEntry(deEntryName, sourceFile = userDeTarFile, manifestItem = deItem, isStoreOnly = true))
                            manifestItems.add(deItem)
                            totalEstimatedSize += userDeTarFile.length()
                            appFileEntries.add(AppBackupFileEntry("user_de.tar", deEntryName, userDeTarFile.length(), deSha256, "user_de"))
                        }
                    }
                }

                // External Data: /sdcard/Android/data/<pkg>
                if (options.includeExternalData) {
                    val checkExt = com.topjohnwu.superuser.Shell.cmd("[ -d \"/sdcard/Android/data/${app.packageName}\" ] && [ \"$(ls -A /sdcard/Android/data/${app.packageName} 2>/dev/null)\" ]").exec()
                    if (checkExt.isSuccess) {
                        val extTarFile = File(stagingDir, "${app.packageName}_ext_data.tar")
                        val extCmd = com.topjohnwu.superuser.Shell.cmd(
                            "nice -n 19 ionice -c 3 tar -cf \"${extTarFile.absolutePath}\" --exclude \"cache\" -C \"/sdcard/Android/data/${app.packageName}\" ."
                        ).exec()
                        if (extTarFile.exists() && extTarFile.length() >= 100L) {
                            val extSha256 = SecurityManager.computeFileSha256(extTarFile)
                            val extEntryName = "apps/${app.packageName}/ext_data.tar"
                            val extItem = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.APP_DATA,
                                name = "${app.appName} (Dados Externos)",
                                originalPath = "/sdcard/Android/data/${app.packageName}",
                                archiveEntryPath = extEntryName,
                                sizeBytes = extTarFile.length(),
                                sha256Checksum = extSha256,
                                compressionMethod = "STORED",
                                metadata = mapOf("packageName" to app.packageName, "dataType" to "external_data")
                            )
                            packEntries.add(ArchiveEngine.PackEntry(extEntryName, sourceFile = extTarFile, manifestItem = extItem, isStoreOnly = true))
                            manifestItems.add(extItem)
                            totalEstimatedSize += extTarFile.length()
                            appFileEntries.add(AppBackupFileEntry("ext_data.tar", extEntryName, extTarFile.length(), extSha256, "external_data"))
                        }
                    }
                }

                // OBB Data: /sdcard/Android/obb/<pkg>
                if (options.includeObb) {
                    val checkObb = com.topjohnwu.superuser.Shell.cmd("[ -d \"/sdcard/Android/obb/${app.packageName}\" ] && [ \"$(ls -A /sdcard/Android/obb/${app.packageName} 2>/dev/null)\" ]").exec()
                    if (checkObb.isSuccess) {
                        val obbTarFile = File(stagingDir, "${app.packageName}_obb.tar")
                        val obbCmd = com.topjohnwu.superuser.Shell.cmd(
                            "nice -n 19 ionice -c 3 tar -cf \"${obbTarFile.absolutePath}\" -C \"/sdcard/Android/obb/${app.packageName}\" ."
                        ).exec()
                        if (obbTarFile.exists() && obbTarFile.length() >= 100L) {
                            val obbSha256 = SecurityManager.computeFileSha256(obbTarFile)
                            val obbEntryName = "apps/${app.packageName}/obb.tar"
                            val obbItem = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.APP_DATA,
                                name = "${app.appName} (OBB)",
                                originalPath = "/sdcard/Android/obb/${app.packageName}",
                                archiveEntryPath = obbEntryName,
                                sizeBytes = obbTarFile.length(),
                                sha256Checksum = obbSha256,
                                compressionMethod = "STORED",
                                metadata = mapOf("packageName" to app.packageName, "dataType" to "obb")
                            )
                            packEntries.add(ArchiveEngine.PackEntry(obbEntryName, sourceFile = obbTarFile, manifestItem = obbItem, isStoreOnly = true))
                            manifestItems.add(obbItem)
                            totalEstimatedSize += obbTarFile.length()
                            appFileEntries.add(AppBackupFileEntry("obb.tar", obbEntryName, obbTarFile.length(), obbSha256, "obb"))
                        }
                    }
                }

                // Metadata JSON per app
                val appMetadata = AppBackupMetadata(
                    packageName = app.packageName,
                    appName = app.appName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    backupTimestamp = System.currentTimeMillis(),
                    files = appFileEntries
                )
                val metaJsonBytes = gson.toJson(appMetadata).toByteArray(Charsets.UTF_8)
                val metaEntryName = "apps/${app.packageName}/metadata.json"
                val metaItem = ManifestItem(
                    id = UUID.randomUUID().toString(),
                    type = BackupItemType.FILE,
                    name = "${app.appName} (Metadados)",
                    originalPath = "",
                    archiveEntryPath = metaEntryName,
                    sizeBytes = metaJsonBytes.size.toLong(),
                    sha256Checksum = SecurityManager.computeSha256(metaJsonBytes),
                    compressionMethod = "DEFLATE",
                    metadata = mapOf("packageName" to app.packageName, "isMetadata" to "true")
                )
                packEntries.add(ArchiveEngine.PackEntry(metaEntryName, rawData = metaJsonBytes, manifestItem = metaItem))
                manifestItems.add(metaItem)
                totalEstimatedSize += metaJsonBytes.size

                // App Icon PNG (96x96 / 128x128) - Salvo para restauração visual autônoma mesmo se o app for desinstalado
                val iconBytes = IconCacheManager.getInstance(context).extractAppIconPngBytes(app.packageName)
                if (iconBytes != null && iconBytes.isNotEmpty()) {
                    val iconEntryName = "apps/${app.packageName}/icon.png"
                    val iconItem = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.FILE,
                        name = "${app.appName} (Ícone)",
                        originalPath = "",
                        archiveEntryPath = iconEntryName,
                        sizeBytes = iconBytes.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(iconBytes),
                        compressionMethod = "STORED",
                        metadata = mapOf("packageName" to app.packageName, "isIcon" to "true")
                    )
                    packEntries.add(ArchiveEngine.PackEntry(iconEntryName, rawData = iconBytes, manifestItem = iconItem, isStoreOnly = true))
                    manifestItems.add(iconItem)
                    totalEstimatedSize += iconBytes.size
                }

                    completedItemsList.add(
                        CompletedItemInfo(
                            name = app.appName,
                            type = BackupItemType.APP_APK,
                            success = true,
                            stage = "Aplicativo",
                            packageName = app.packageName
                        )
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val errMsg = "Falha ao processar aplicativo ${app.appName} (${app.packageName}): ${t.message}"
                    try { dbHelper.insertLog(LogLevel.WARN, "BackupEngine", errMsg) } catch (_: Throwable) {}
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = app.appName,
                            type = BackupItemType.APP_APK,
                            success = false,
                            error = errMsg,
                            stage = "Aplicativo",
                            packageName = app.packageName
                        )
                    )
                }
            }

            // 2. Process Files & Folders
            cpuGovernor.startStage("Coleta de Arquivos e Pastas")
            for (filePath in selectedFilesAndFolders) {
                cpuGovernor.throttleIfNeeded(
                    onThermalWait = { temp ->
                        emitProgress(BackupProgress("Aguardando o aparelho esfriar", "Temperatura em %.1f °C (> 42 °C). Resfriando dispositivo...".format(java.util.Locale.US, temp), 0.20f, isPaused = true, completedItems = completedItemsList.toList(), bytesProcessed = (fixedTotalBytes * 0.20f).toLong(), totalBytesEstimated = fixedTotalBytes, currentItemIndex = (fixedTotalItems * 0.20f).toInt().coerceIn(1, fixedTotalItems), totalItems = fixedTotalItems, estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING))
                    },
                    onThermalResume = {
                        emitProgress(BackupProgress("Retomando", "Dispositivo resfriado. Continuando backup...", 0.20f, isPaused = false, completedItems = completedItemsList.toList(), bytesProcessed = (fixedTotalBytes * 0.20f).toLong(), totalBytesEstimated = fixedTotalBytes, currentItemIndex = (fixedTotalItems * 0.20f).toInt().coerceIn(1, fixedTotalItems), totalItems = fixedTotalItems, estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING))
                    },
                    isCancelled = { isCancelled.get() }
                )
                try {
                    val file = File(filePath)
                    if (file.exists()) {
                        if (file.isDirectory) {
                            var folderTotalBytes = 0L
                            var folderFileCount = 0
                            collectFilesRecursively(file, filter, packEntries, manifestItems) { sizeAdded ->
                                folderTotalBytes += sizeAdded
                                folderFileCount++
                                totalEstimatedSize += sizeAdded
                            }
                            val folderItem = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.FOLDER,
                                name = file.name,
                                originalPath = file.absolutePath,
                                archiveEntryPath = "files/${file.name}",
                                sizeBytes = folderTotalBytes,
                                sha256Checksum = "",
                                compressionMethod = "STORED",
                                metadata = mapOf(
                                    "fileCount" to folderFileCount.toString(),
                                    "lastModified" to file.lastModified().toString()
                                )
                            )
                            manifestItems.add(folderItem)
                        } else {
                            collectFilesRecursively(file, filter, packEntries, manifestItems) { sizeAdded ->
                                totalEstimatedSize += sizeAdded
                            }
                        }
                    } else {
                        dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Caminho de arquivo não encontrado: $filePath")
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    val warnMsg = "Falha ao processar arquivo $filePath: ${t.message}"
                    try { dbHelper.insertLog(LogLevel.WARN, "BackupEngine", warnMsg) } catch (_: Throwable) {}
                }
            }

            // Check free space on internal storage before proceeding
            val internalRoot = android.os.Environment.getExternalStorageDirectory()
            val freeBytes = try {
                android.os.StatFs(internalRoot.absolutePath).availableBytes
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            val requiredMargin = totalEstimatedSize + (100L * 1024 * 1024) // 100 MB margin
            if (freeBytes < requiredMargin) {
                val spaceErr = "Espaço insuficiente no armazenamento interno. Necessário: ${(requiredMargin / (1024 * 1024))} MB. Disponível: ${(freeBytes / (1024 * 1024))} MB."
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", spaceErr)
                emitProgress(BackupProgress("Erro", spaceErr, 1.0f, 0, 0, isFinished = true, error = spaceErr))
                stagingDir.deleteRecursively()
                return@channelFlow
            }

            // 3. Process SMS
            if (includeSms) {
                try {
                    val smsProgressPercent = 0.22f
                    val smsBytes = (fixedTotalBytes * smsProgressPercent).toLong()
                    val smsItemIdx = (fixedTotalItems * smsProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val smsEta = progressEstimator.update(smsBytes, smsItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Mensagens",
                            currentItemName = "Exportando mensagens SMS e bancos de telefonia...",
                            progressPercent = smsProgressPercent,
                            bytesProcessed = smsBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = smsItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = smsEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val smsList = readSmsMessages()
                    var smsCount = 0
                    if (smsList.isNotEmpty()) {
                        val smsJson = gson.toJson(smsList).toByteArray(Charsets.UTF_8)
                        val entryName = "system/telephony/sms_backup.json"
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.SMS,
                            name = "Mensagens SMS (${smsList.size})",
                            originalPath = "content://sms",
                            archiveEntryPath = entryName,
                            sizeBytes = smsJson.size.toLong(),
                            sha256Checksum = SecurityManager.computeSha256(smsJson),
                            compressionMethod = "DEFLATE",
                            metadata = mapOf("format" to "json", "count" to smsList.size.toString())
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = smsJson, manifestItem = item))
                        manifestItems.add(item)
                        totalEstimatedSize += smsJson.size
                        smsCount += smsList.size
                    }

                    // If root is available, also backup telephony SQLite databases (mmssms.db, -wal, -shm)
                    var dbCount = 0
                    if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                        val telephonyDbEntries = backupTelephonyDatabases(stagingDir)
                        for ((entry, item) in telephonyDbEntries) {
                            packEntries.add(entry)
                            manifestItems.add(item)
                            totalEstimatedSize += item.sizeBytes
                            dbCount++
                        }
                    }

                    if (smsCount > 0 || dbCount > 0) {
                        val smsLabel = if (smsCount == 1) "1 msg" else "$smsCount msgs"
                        val dbLabel = if (dbCount == 1) "1 banco" else "$dbCount bancos"
                        completedItemsList.add(CompletedItemInfo(name = "Mensagens SMS ($smsLabel, $dbLabel)", type = BackupItemType.SMS, success = true, stage = "Coleta de SMS"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Mensagens SMS",
                                type = BackupItemType.SMS,
                                success = false,
                                error = "Nenhuma mensagem encontrada ou permissão não concedida",
                                stage = "Coleta de SMS",
                                causeException = "Sem permissão READ_SMS ou banco vazio",
                                suggestedFix = "Conceda permissão de leitura de SMS nas configurações do app ou ative root."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de SMS: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Mensagens SMS",
                            type = BackupItemType.SMS,
                            success = false,
                            error = err,
                            stage = "Coleta de SMS",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Conceda permissão de leitura de SMS nas configurações do app ou ative root."
                        )
                    )
                }
            }

            // 4. Process Call Logs
            if (includeCalls) {
                try {
                    val callsProgressPercent = 0.25f
                    val callsBytes = (fixedTotalBytes * callsProgressPercent).toLong()
                    val callsItemIdx = (fixedTotalItems * callsProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val callsEta = progressEstimator.update(callsBytes, callsItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Chamadas",
                            currentItemName = "Exportando histórico de chamadas...",
                            progressPercent = callsProgressPercent,
                            bytesProcessed = callsBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = callsItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = callsEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val callsList = readCallLogs()
                    if (callsList.isNotEmpty()) {
                        val callsJson = gson.toJson(callsList).toByteArray(Charsets.UTF_8)
                        val entryName = "system/telephony/calls_backup.json"
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.CALL_LOG,
                            name = "Registro de Chamadas (${callsList.size})",
                            originalPath = "content://call_log/calls",
                            archiveEntryPath = entryName,
                            sizeBytes = callsJson.size.toLong(),
                            sha256Checksum = SecurityManager.computeSha256(callsJson),
                            compressionMethod = "DEFLATE",
                            metadata = mapOf("count" to callsList.size.toString(), "format" to "json")
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = callsJson, manifestItem = item))
                        manifestItems.add(item)
                        totalEstimatedSize += callsJson.size
                        completedItemsList.add(CompletedItemInfo(name = "Registro de Chamadas (${callsList.size})", type = BackupItemType.CALL_LOG, success = true, stage = "Coleta de Chamadas"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Registro de Chamadas",
                                type = BackupItemType.CALL_LOG,
                                success = false,
                                error = "Nenhuma chamada encontrada ou permissão não concedida",
                                stage = "Coleta de Chamadas",
                                causeException = "Sem permissão READ_CALL_LOG ou histórico vazio",
                                suggestedFix = "Conceda permissão de registro de chamadas nas configurações do aplicativo."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Chamadas: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Registro de Chamadas",
                            type = BackupItemType.CALL_LOG,
                            success = false,
                            error = err,
                            stage = "Coleta de Chamadas",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Conceda permissão de registro de chamadas nas configurações do aplicativo."
                        )
                    )
                }
            }

            // 5. Process Contacts
            if (includeContacts) {
                try {
                    val contactsProgressPercent = 0.28f
                    val contactsBytes = (fixedTotalBytes * contactsProgressPercent).toLong()
                    val contactsItemIdx = (fixedTotalItems * contactsProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val contactsEta = progressEstimator.update(contactsBytes, contactsItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Contatos",
                            currentItemName = "Exportando catálogo de contatos para VCF...",
                            progressPercent = contactsProgressPercent,
                            bytesProcessed = contactsBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = contactsItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = contactsEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val contactsList = readContactsList()
                    if (contactsList.isNotEmpty()) {
                        val vcfBytes = exportContactsToVcf(contactsList)
                        val entryName = "system/contacts/contacts_backup.vcf"
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.CONTACTS,
                            name = "Contatos (${contactsList.size})",
                            originalPath = "content://contacts",
                            archiveEntryPath = entryName,
                            sizeBytes = vcfBytes.size.toLong(),
                            sha256Checksum = SecurityManager.computeSha256(vcfBytes),
                            compressionMethod = "DEFLATE",
                            metadata = mapOf("format" to "vcf", "count" to contactsList.size.toString())
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = vcfBytes, manifestItem = item))
                        manifestItems.add(item)
                        totalEstimatedSize += vcfBytes.size
                        completedItemsList.add(CompletedItemInfo(name = "Contatos (${contactsList.size})", type = BackupItemType.CONTACTS, success = true, stage = "Coleta de Contatos"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Contatos",
                                type = BackupItemType.CONTACTS,
                                success = false,
                                error = "Nenhum contato encontrado ou permissão não concedida",
                                stage = "Coleta de Contatos",
                                causeException = "Sem permissão READ_CONTACTS ou catálogo vazio",
                                suggestedFix = "Conceda permissão de contatos nas configurações do aplicativo."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Contatos: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Contatos",
                            type = BackupItemType.CONTACTS,
                            success = false,
                            error = err,
                            stage = "Coleta de Contatos",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Conceda permissão de contatos nas configurações do aplicativo."
                        )
                    )
                }
            }

            // 6. Process DNS
            if (includeDns) {
                try {
                    val dnsProgressPercent = 0.32f
                    val dnsBytes = (fixedTotalBytes * dnsProgressPercent).toLong()
                    val dnsItemIdx = (fixedTotalItems * dnsProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val dnsEta = progressEstimator.update(dnsBytes, dnsItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "DNS & Rede",
                            currentItemName = "Capturando configuração de DNS...",
                            progressPercent = dnsProgressPercent,
                            bytesProcessed = dnsBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = dnsItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = dnsEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val dnsConfig = dnsManager.getDnsConfiguration()
                    val dnsJson = gson.toJson(dnsConfig).toByteArray(Charsets.UTF_8)
                    val entryName = "system/dns_network.json"
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.DNS_CONFIG,
                        name = "Configuração DNS (${dnsConfig.mode})",
                        originalPath = "Settings.Global.private_dns",
                        archiveEntryPath = entryName,
                        sizeBytes = dnsJson.size.toLong(),
                        sha256Checksum = SecurityManager.computeSha256(dnsJson),
                        compressionMethod = "DEFLATE",
                        metadata = mapOf("mode" to dnsConfig.mode, "host" to (dnsConfig.hostname ?: ""))
                    )
                    packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = dnsJson, manifestItem = item))
                    manifestItems.add(item)
                    totalEstimatedSize += dnsJson.size
                    completedItemsList.add(CompletedItemInfo(name = "Configuração DNS (${dnsConfig.mode})", type = BackupItemType.DNS_CONFIG, success = true, stage = "Captura de DNS"))
                } catch (e: Throwable) {
                    val err = "Falha no backup de DNS: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Configuração DNS",
                            type = BackupItemType.DNS_CONFIG,
                            success = false,
                            error = err,
                            stage = "Captura de DNS",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Verifique configurações de rede e permissão WRITE_SECURE_SETTINGS via ADB ou root."
                        )
                    )
                }
            }

            // 6b. Process Wi-Fi
            if (includeWifi) {
                try {
                    val wifiProgressPercent = 0.33f
                    val wifiBytes = (fixedTotalBytes * wifiProgressPercent).toLong()
                    val wifiItemIdx = (fixedTotalItems * wifiProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val wifiEta = progressEstimator.update(wifiBytes, wifiItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Wi-Fi",
                            currentItemName = "Capturando redes Wi-Fi salvas...",
                            progressPercent = wifiProgressPercent,
                            bytesProcessed = wifiBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = wifiItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = wifiEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val (wifiEntry, wifiItem) = backupWifiConfig(stagingDir)
                    if (wifiEntry != null && wifiItem != null) {
                        packEntries.add(wifiEntry)
                        manifestItems.add(wifiItem)
                        totalEstimatedSize += wifiItem.sizeBytes
                        completedItemsList.add(CompletedItemInfo(name = wifiItem.name, type = BackupItemType.WIFI_CONFIG, success = true, stage = "Captura de Wi-Fi"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Configuração Wi-Fi",
                                type = BackupItemType.WIFI_CONFIG,
                                success = false,
                                error = "Requer Root ou arquivo Wi-Fi inacessível",
                                stage = "Captura de Wi-Fi",
                                causeException = "Sem permissão root para ler /data/misc/wifi/WifiConfigStore.xml",
                                suggestedFix = "Requer autorização Root para acessar WifiConfigStore.xml no Android 10+."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Wi-Fi: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Configuração Wi-Fi",
                            type = BackupItemType.WIFI_CONFIG,
                            success = false,
                            error = err,
                            stage = "Captura de Wi-Fi",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Requer autorização Root para acessar WifiConfigStore.xml no Android 10+."
                        )
                    )
                }
            }

            // 7. Process Wallpaper (Tela Inicial e Tela de Bloqueio separadamente)
            var homeWallpaperBytes: ByteArray? = null
            var homeArchiveEntryPath: String? = null

            // 7a. Papel de parede da Tela Inicial
            if (shouldBackupHome) {
                try {
                    val wpProgressPercent = 0.35f
                    val wpBytes = (fixedTotalBytes * wpProgressPercent).toLong()
                    val wpItemIdx = (fixedTotalItems * wpProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val wpEta = progressEstimator.update(wpBytes, wpItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Sistema",
                            currentItemName = "Capturando Papel de Parede da Tela Inicial...",
                            progressPercent = wpProgressPercent,
                            bytesProcessed = wpBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = wpItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = wpEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val homeResult = captureWallpaperBytesByFlag(WallpaperManager.FLAG_SYSTEM)
                    val bytes = homeResult.bytes
                    if (bytes != null) {
                        homeWallpaperBytes = bytes
                        val entryName = "system/wallpaper_home.png"
                        homeArchiveEntryPath = entryName

                        val metadata = mutableMapOf<String, String>("which" to "system")
                        if (homeResult.liveWallpaperService != null) {
                            metadata["liveWallpaperComponent"] = homeResult.liveWallpaperService
                            dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Papel de parede da Tela Inicial é animado (${homeResult.liveWallpaperService}). Referência e snapshot estático salvos.")
                        }

                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.WALLPAPER,
                            name = "Papel de Parede da Tela Inicial",
                            originalPath = "WallpaperManager (FLAG_SYSTEM, source=${homeResult.source})",
                            archiveEntryPath = entryName,
                            sizeBytes = bytes.size.toLong(),
                            sha256Checksum = SecurityManager.computeSha256(bytes),
                            compressionMethod = "STORED",
                            metadata = metadata
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = bytes, manifestItem = item, isStoreOnly = true))
                        manifestItems.add(item)
                        totalEstimatedSize += bytes.size
                        completedItemsList.add(CompletedItemInfo(name = "Papel de Parede da Tela Inicial", type = BackupItemType.WALLPAPER, success = true, stage = "Captura de Papel de Parede"))
                    } else if (homeResult.liveWallpaperService != null) {
                        // Papel de parede live sem bitmap extraível
                        val dummyBytes = ByteArray(0)
                        val entryName = "system/wallpaper_home_live.json"
                        val metadata = mapOf("which" to "system", "liveWallpaperComponent" to homeResult.liveWallpaperService)
                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.WALLPAPER,
                            name = "Papel de Parede da Tela Inicial (Animado)",
                            originalPath = "WallpaperManager (Live: ${homeResult.liveWallpaperService})",
                            archiveEntryPath = entryName,
                            sizeBytes = 0L,
                            sha256Checksum = SecurityManager.computeSha256(dummyBytes),
                            compressionMethod = "STORED",
                            metadata = metadata
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = dummyBytes, manifestItem = item, isStoreOnly = true))
                        manifestItems.add(item)
                        completedItemsList.add(CompletedItemInfo(name = "Papel de Parede da Tela Inicial (Animado)", type = BackupItemType.WALLPAPER, success = true, stage = "Captura de Papel de Parede"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Papel de Parede da Tela Inicial",
                                type = BackupItemType.WALLPAPER,
                                success = false,
                                error = "Papel de parede inicial indisponível ou sem permissão",
                                stage = "Captura de Papel de Parede",
                                causeException = "WallpaperManager retornou arquivo nulo ou sem permissão de leitura",
                                suggestedFix = "Defina um papel de parede estático ou conceda permissão ao ReSync."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Papel de Parede da Tela Inicial: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Papel de Parede da Tela Inicial",
                            type = BackupItemType.WALLPAPER,
                            success = false,
                            error = err,
                            stage = "Captura de Papel de Parede",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Defina um papel de parede estático ou conceda permissão."
                        )
                    )
                }
            }

            // 7b. Papel de parede da Tela de Bloqueio
            if (shouldBackupLock) {
                try {
                    val lockProgressPercent = 0.36f
                    val lockBytes = (fixedTotalBytes * lockProgressPercent).toLong()
                    val lockItemIdx = (fixedTotalItems * lockProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val lockEta = progressEstimator.update(lockBytes, lockItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Sistema",
                            currentItemName = "Capturando Papel de Parede da Tela de Bloqueio...",
                            progressPercent = lockProgressPercent,
                            bytesProcessed = lockBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = lockItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = lockEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val lockResult = captureWallpaperBytesByFlag(WallpaperManager.FLAG_LOCK)

                    if (lockResult.isSharedWithHome) {
                        dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "A tela de bloqueio usa o mesmo papel de parede da tela inicial.")
                        // Se home já foi capturada ou se podemos capturá-la agora para reusar o arquivo
                        var sharedEntry = homeArchiveEntryPath
                        var sharedBytes = homeWallpaperBytes
                        if (sharedEntry == null || sharedBytes == null) {
                            val homeCapture = captureWallpaperBytesByFlag(WallpaperManager.FLAG_SYSTEM)
                            if (homeCapture.bytes != null) {
                                sharedBytes = homeCapture.bytes
                                sharedEntry = "system/wallpaper_home.png"
                                homeWallpaperBytes = sharedBytes
                                homeArchiveEntryPath = sharedEntry
                            }
                        }

                        if (sharedEntry != null && sharedBytes != null) {
                            val metadata = mutableMapOf<String, String>(
                                "which" to "lock",
                                "sameAsHome" to "true",
                                "note" to "A tela de bloqueio usa o mesmo papel de parede da tela inicial"
                            )
                            if (lockResult.liveWallpaperService != null) {
                                metadata["liveWallpaperComponent"] = lockResult.liveWallpaperService
                            }

                            val item = ManifestItem(
                                id = UUID.randomUUID().toString(),
                                type = BackupItemType.WALLPAPER_LOCK,
                                name = "Papel de Parede da Tela de Bloqueio",
                                originalPath = "WallpaperManager (FLAG_LOCK, compartilhado com inicial)",
                                archiveEntryPath = sharedEntry,
                                sizeBytes = sharedBytes.size.toLong(),
                                sha256Checksum = SecurityManager.computeSha256(sharedBytes),
                                compressionMethod = "STORED",
                                metadata = metadata
                            )
                            if (!shouldBackupHome) {
                                packEntries.add(ArchiveEngine.PackEntry(sharedEntry, rawData = sharedBytes, manifestItem = item, isStoreOnly = true))
                                totalEstimatedSize += sharedBytes.size
                            }
                            manifestItems.add(item)
                            completedItemsList.add(CompletedItemInfo(
                                name = "Papel de Parede da Tela de Bloqueio",
                                type = BackupItemType.WALLPAPER_LOCK,
                                success = true,
                                stage = "Captura de Papel de Parede"
                            ))
                        } else {
                            completedItemsList.add(
                                CompletedItemInfo(
                                    name = "Papel de Parede da Tela de Bloqueio",
                                    type = BackupItemType.WALLPAPER_LOCK,
                                    success = false,
                                    error = "Papel de parede compartilhado não pôde ser lido da tela inicial",
                                    stage = "Captura de Papel de Parede",
                                    causeException = "Tela inicial vazia",
                                    suggestedFix = "Defina um papel de parede na tela de bloqueio ou na tela inicial."
                                )
                            )
                        }
                    } else if (lockResult.bytes != null) {
                        val bytes = lockResult.bytes
                        val entryName = "system/wallpaper_lock.png"
                        val metadata = mutableMapOf<String, String>("which" to "lock")
                        if (lockResult.liveWallpaperService != null) {
                            metadata["liveWallpaperComponent"] = lockResult.liveWallpaperService
                            dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Papel de parede da Tela de Bloqueio é animado (${lockResult.liveWallpaperService}). Referência salva.")
                        }

                        val item = ManifestItem(
                            id = UUID.randomUUID().toString(),
                            type = BackupItemType.WALLPAPER_LOCK,
                            name = "Papel de Parede da Tela de Bloqueio",
                            originalPath = "WallpaperManager (FLAG_LOCK, source=${lockResult.source})",
                            archiveEntryPath = entryName,
                            sizeBytes = bytes.size.toLong(),
                            sha256Checksum = SecurityManager.computeSha256(bytes),
                            compressionMethod = "STORED",
                            metadata = metadata
                        )
                        packEntries.add(ArchiveEngine.PackEntry(entryName, rawData = bytes, manifestItem = item, isStoreOnly = true))
                        manifestItems.add(item)
                        totalEstimatedSize += bytes.size
                        completedItemsList.add(CompletedItemInfo(name = "Papel de Parede da Tela de Bloqueio", type = BackupItemType.WALLPAPER_LOCK, success = true, stage = "Captura de Papel de Parede"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Papel de Parede da Tela de Bloqueio",
                                type = BackupItemType.WALLPAPER_LOCK,
                                success = false,
                                error = "Papel de parede de bloqueio indisponível",
                                stage = "Captura de Papel de Parede",
                                causeException = "Arquivo de bloqueio não encontrado e não compartilhado",
                                suggestedFix = "Defina um papel de parede específico na tela de bloqueio."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Papel de Parede da Tela de Bloqueio: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Papel de Parede da Tela de Bloqueio",
                            type = BackupItemType.WALLPAPER_LOCK,
                            success = false,
                            error = err,
                            stage = "Captura de Papel de Parede",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Defina um papel de parede na tela de bloqueio."
                        )
                    )
                }
            }

            // 7b. Process Bluetooth
            if (includeBluetooth) {
                try {
                    val btProgressPercent = 0.37f
                    val btBytes = (fixedTotalBytes * btProgressPercent).toLong()
                    val btItemIdx = (fixedTotalItems * btProgressPercent).toInt().coerceIn(1, fixedTotalItems)
                    val btEta = progressEstimator.update(btBytes, btItemIdx)
                    emitProgress(
                        BackupProgress(
                            stage = "Bluetooth",
                            currentItemName = "Capturando dispositivos Bluetooth pareados e chaves...",
                            progressPercent = btProgressPercent,
                            bytesProcessed = btBytes,
                            totalBytesEstimated = fixedTotalBytes,
                            currentItemIndex = btItemIdx,
                            totalItems = fixedTotalItems,
                            completedItems = completedItemsList.toList(),
                            estimatedRemainingSeconds = btEta,
                            compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                        )
                    )
                    val btEntries = backupBluetooth(stagingDir)
                    var btSuccess = false
                    for ((entry, item) in btEntries) {
                        packEntries.add(entry)
                        manifestItems.add(item)
                        totalEstimatedSize += item.sizeBytes
                        btSuccess = true
                    }
                    if (btSuccess) {
                        completedItemsList.add(CompletedItemInfo(name = "Bluetooth Pareado & Configurações", type = BackupItemType.BLUETOOTH, success = true, stage = "Captura de Bluetooth"))
                    } else {
                        completedItemsList.add(
                            CompletedItemInfo(
                                name = "Bluetooth Pareado",
                                type = BackupItemType.BLUETOOTH,
                                success = false,
                                error = "Nenhum dispositivo encontrado ou sem permissão",
                                stage = "Captura de Bluetooth",
                                causeException = "Sem permissão BLUETOOTH_CONNECT ou Bluetooth desligado",
                                suggestedFix = "Conceda permissões de dispositivos por perto (BLUETOOTH_CONNECT) ou ative o Bluetooth."
                            )
                        )
                    }
                } catch (e: Throwable) {
                    val err = "Falha no backup de Bluetooth: ${e.message}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", err)
                    completedItemsList.add(
                        CompletedItemInfo(
                            name = "Bluetooth Pareado",
                            type = BackupItemType.BLUETOOTH,
                            success = false,
                            error = err,
                            stage = "Captura de Bluetooth",
                            causeException = "${e.javaClass.simpleName}: ${e.message}",
                            suggestedFix = "Conceda permissões de dispositivos por perto (BLUETOOTH_CONNECT) ou ative o Bluetooth."
                        )
                    )
                }
            }

            // 8. Assemble Manifest
            val manifest = BackupManifest(
                formatIdentifier = "SwiftVault",
                manifestVersion = 2,
                vaultVersion = "2.0.0",
                backupId = backupId,
                title = backupTitle,
                deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidSdk = android.os.Build.VERSION.SDK_INT,
                androidRelease = android.os.Build.VERSION.RELEASE,
                rootManager = CapabilityManager(context).getInstalledRootManagerPackage(),
                createdAt = System.currentTimeMillis(),
                isEncrypted = !encryptionPassword.isNullOrEmpty(),
                encryptionAlgorithm = if (!encryptionPassword.isNullOrEmpty()) "AES-256-GCM-CHUNKED" else "NONE",
                compressionAlgorithm = "ZIP-MIXED",
                totalSizeBytes = totalEstimatedSize,
                items = manifestItems,
                sha256PayloadHash = ""
            )

            // 9. Stream Archive & Compress directly into Staging Zip
            cpuGovernor.startStage("Compactação e Gravação do Container")
            emitProgress(
                BackupProgress(
                    stage = "Comprimindo",
                    currentItemName = "Empacotando e gravando container .zip...",
                    progressPercent = 0.40f,
                    bytesProcessed = (fixedTotalBytes * 0.40f).toLong(),
                    totalBytesEstimated = fixedTotalBytes,
                    currentItemIndex = (fixedTotalItems * 0.40f).toInt().coerceIn(1, fixedTotalItems),
                    totalItems = fixedTotalItems,
                    completedItems = completedItemsList.toList(),
                    estimatedRemainingSeconds = progressEstimator.update((fixedTotalBytes * 0.40f).toLong(), (fixedTotalItems * 0.40f).toInt().coerceIn(1, fixedTotalItems)),
                    compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                )
            )

            var lastPackedIndex = 0
            var lastRecordedPercent = 0.40f
            var lastRecordedName = "Empacotando container..."

            ArchiveEngine.createZipContainer(
                outputFile = stagingFile,
                manifest = manifest,
                entries = packEntries,
                encryptionPassword = encryptionPassword,
                onItemProgress = { written, total, currentItemName, itemIndex, totalItems, itemType ->
                    val curEntry = packEntries.getOrNull(itemIndex - 1)
                    val curPkg = curEntry?.manifestItem?.metadata?.get("packageName") ?: ""
                    if (itemIndex > lastPackedIndex && lastPackedIndex > 0) {
                        val prevEntry = packEntries.getOrNull(lastPackedIndex - 1)
                        if (prevEntry != null && completedItemsList.none { it.name == prevEntry.manifestItem.name }) {
                            completedItemsList.add(
                                CompletedItemInfo(
                                    name = prevEntry.manifestItem.name,
                                    type = prevEntry.manifestItem.type,
                                    success = true,
                                    stage = "Compactação",
                                    packageName = prevEntry.manifestItem.metadata["packageName"] ?: "",
                                    iconPath = prevEntry.manifestItem.originalPath
                                )
                            )
                        }
                    }
                    lastPackedIndex = itemIndex

                    val ratio = if (total > 0) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                    val percent = 0.40f + (ratio * 0.45f) // 40% -> 85%
                    lastRecordedPercent = percent
                    lastRecordedName = currentItemName

                    val mappedBytes = (fixedTotalBytes * percent).toLong().coerceIn(0L, fixedTotalBytes)
                    val mappedItemIndex = (percent * fixedTotalItems).toInt().coerceIn(1, fixedTotalItems)
                    val etaSec = progressEstimator.update(mappedBytes, mappedItemIndex)

                    if (rateLimiter.shouldEmit()) {
                        emitProgress(
                            BackupProgress(
                                stage = "Gravando container",
                                currentItemName = currentItemName,
                                progressPercent = percent,
                                bytesProcessed = mappedBytes,
                                totalBytesEstimated = fixedTotalBytes,
                                currentItemIndex = mappedItemIndex,
                                totalItems = fixedTotalItems,
                                currentItemType = itemType,
                                currentPackageName = curPkg,
                                completedItems = completedItemsList.toList(),
                                isPaused = isPaused.get(),
                                uploadSpeedBytesPerSec = 0L,
                                downloadSpeedBytesPerSec = 0L,
                                compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes(),
                                estimatedRemainingSeconds = etaSec
                            )
                        )
                    }
                },
                checkPauseAndCancel = {
                    val pausedBytes = (fixedTotalBytes * lastRecordedPercent).toLong()
                    val pausedItems = (lastRecordedPercent * fixedTotalItems).toInt().coerceIn(1, fixedTotalItems)
                    cpuGovernor.throttleIfNeeded(
                        onThermalWait = { temp ->
                            emitProgress(
                                BackupProgress(
                                    stage = "Aguardando o aparelho esfriar",
                                    currentItemName = "Temperatura em %.1f °C (> 42 °C). Aguardando resfriamento...".format(java.util.Locale.US, temp),
                                    progressPercent = lastRecordedPercent,
                                    bytesProcessed = pausedBytes,
                                    totalBytesEstimated = fixedTotalBytes,
                                    currentItemIndex = pausedItems,
                                    totalItems = fixedTotalItems,
                                    completedItems = completedItemsList.toList(),
                                    isPaused = true,
                                    estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING
                                )
                            )
                        },
                        onThermalResume = {
                            emitProgress(
                                BackupProgress(
                                    stage = "Gravando container",
                                    currentItemName = lastRecordedName,
                                    progressPercent = lastRecordedPercent,
                                    bytesProcessed = pausedBytes,
                                    totalBytesEstimated = fixedTotalBytes,
                                    currentItemIndex = pausedItems,
                                    totalItems = fixedTotalItems,
                                    completedItems = completedItemsList.toList(),
                                    isPaused = false,
                                    estimatedRemainingSeconds = progressEstimator.update(pausedBytes, pausedItems),
                                    compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                                )
                            )
                        },
                        isCancelled = { isCancelled.get() }
                    )
                    checkPauseAndCancel(
                        onPausedNotice = {
                            emitProgress(
                                BackupProgress(
                                    stage = "Pausado",
                                    currentItemName = lastRecordedName,
                                    progressPercent = lastRecordedPercent,
                                    bytesProcessed = pausedBytes,
                                    totalBytesEstimated = fixedTotalBytes,
                                    currentItemIndex = pausedItems,
                                    totalItems = fixedTotalItems,
                                    completedItems = completedItemsList.toList(),
                                    isPaused = true,
                                    estimatedRemainingSeconds = FormatUtils.ESTIMATE_CALCULATING
                                )
                            )
                        },
                        onResumedNotice = {
                            emitProgress(
                                BackupProgress(
                                    stage = "Gravando container",
                                    currentItemName = lastRecordedName,
                                    progressPercent = lastRecordedPercent,
                                    bytesProcessed = pausedBytes,
                                    totalBytesEstimated = fixedTotalBytes,
                                    currentItemIndex = pausedItems,
                                    totalItems = fixedTotalItems,
                                    completedItems = completedItemsList.toList(),
                                    isPaused = false,
                                    estimatedRemainingSeconds = progressEstimator.update(pausedBytes, pausedItems),
                                    compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                                )
                            )
                        }
                    )
                }
            )

            val lastEntry = packEntries.lastOrNull()
            if (lastEntry != null && completedItemsList.none { it.name == lastEntry.manifestItem.name }) {
                completedItemsList.add(CompletedItemInfo(name = lastEntry.manifestItem.name, type = lastEntry.manifestItem.type, success = true, stage = "Compactação"))
            }

            // 10. RIGOROUS INTEGRITY VALIDATION ON STAGED FILE
            cpuGovernor.startStage("Validação de Integridade (Hash)")
            val verifyBytes = (fixedTotalBytes * 0.88f).toLong()
            val verifyItems = (fixedTotalItems * 0.88f).toInt().coerceIn(1, fixedTotalItems)
            emitProgress(
                BackupProgress(
                    stage = "Verificando",
                    currentItemName = "Executando verificação de integridade SHA-256 do arquivo...",
                    progressPercent = 0.88f,
                    bytesProcessed = verifyBytes,
                    totalBytesEstimated = fixedTotalBytes,
                    currentItemIndex = verifyItems,
                    totalItems = fixedTotalItems,
                    completedItems = completedItemsList.toList(),
                    estimatedRemainingSeconds = FormatUtils.ESTIMATE_FINALIZING,
                    compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                )
            )
            val stagingSha256 = SecurityManager.computeFileSha256(stagingFile)

            // Test readability of the container manifest
            val readBackManifest = try {
                ArchiveEngine.readManifest(stagingFile)
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Falha na verificação de integridade do container: ${e.message}")
                emitProgress(BackupProgress("Erro", "Container corrompido durante a gravação: ${e.message}", 1.0f, totalEstimatedSize, totalEstimatedSize, isFinished = true, error = e.message, completedItems = completedItemsList.toList()))
                return@channelFlow
            }

            if (readBackManifest.items.size != manifestItems.size) {
                stagingDir.deleteRecursively()
                val countErr = "Inconsistência no número de itens validados: esperado ${manifestItems.size}, encontrado ${readBackManifest.items.size}"
                dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", countErr)
                emitProgress(BackupProgress("Erro", countErr, 1.0f, totalEstimatedSize, totalEstimatedSize, isFinished = true, error = countErr, completedItems = completedItemsList.toList()))
                return@channelFlow
            }

            cpuGovernor.startStage("Finalização e Armazenamento")
            if (destinationType == BackupDestinationType.SOMENTE_DRIVE) {
                // SOMENTE_DRIVE: Mover para cache/staging dedicado para o upload, NÃO gravar em SwiftVaultBackups
                val driveBytes = (fixedTotalBytes * 0.95f).toLong()
                val driveItems = (fixedTotalItems * 0.95f).toInt().coerceIn(1, fixedTotalItems)
                emitProgress(
                    BackupProgress(
                        stage = "Finalizando",
                        currentItemName = "Preparando container em staging temporário para envio ao Google Drive...",
                        progressPercent = 0.95f,
                        bytesProcessed = driveBytes,
                        totalBytesEstimated = fixedTotalBytes,
                        currentItemIndex = driveItems,
                        totalItems = fixedTotalItems,
                        completedItems = completedItemsList.toList(),
                        estimatedRemainingSeconds = FormatUtils.ESTIMATE_FINALIZING,
                        compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                    )
                )
                
                val driveStagingDir = File(context.cacheDir, "staging_drive").apply { mkdirs() }
                val driveStagingFile = File(driveStagingDir, "$backupId${com.resync.app.data.model.BackupPaths.BACKUP_FILE_EXTENSION}")
                stagingFile.copyTo(driveStagingFile, overwrite = true)
                
                // Limpar staging inicial
                stagingDir.deleteRecursively()

                val stagingFinalSha256 = SecurityManager.computeFileSha256(driveStagingFile)

                // Gerar índice sidecar comprimido (svb_XXXX.index.json) e salvar no Room
                try {
                    val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(context)
                    val indexDao = roomDb.backupIndexDao()
                    val sidecarFile = File(driveStagingDir, "$backupId.index.json")
                    val jsonBytes = com.google.gson.Gson().toJson(readBackManifest).toByteArray(Charsets.UTF_8)
                    java.io.FileOutputStream(sidecarFile).use { fos ->
                        java.util.zip.GZIPOutputStream(fos).use { gzip ->
                            gzip.write(jsonBytes)
                        }
                    }
                    val baos = java.io.ByteArrayOutputStream()
                    java.util.zip.GZIPOutputStream(baos).use { it.write(jsonBytes) }
                    val compressedBase64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val indexEntity = com.resync.app.data.room.BackupIndexCacheEntity(
                        backupId = backupId,
                        backupTitle = backupTitle,
                        filePath = driveStagingFile.absolutePath,
                        driveFileId = null,
                        sizeBytes = driveStagingFile.length(),
                        manifestGzipBase64 = compressedBase64,
                        itemCount = readBackManifest.items.size,
                        cachedAt = System.currentTimeMillis()
                    )
                    indexDao.insertOrUpdate(indexEntity)
                    dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Índice sidecar comprimido salvo no Room e em staging: ${sidecarFile.name}")
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Aviso ao salvar sidecar em staging: ${e.message}")
                }

                // Persistir registro inicial no banco apontando para o arquivo temporário de staging
                val record = BackupRecord(
                    id = backupId,
                    title = backupTitle,
                    filePath = driveStagingFile.absolutePath,
                    sizeBytes = driveStagingFile.length(),
                    checksumSha256 = stagingFinalSha256,
                    isEncrypted = !encryptionPassword.isNullOrEmpty(),
                    timestamp = System.currentTimeMillis(),
                    itemCount = manifestItems.size,
                    status = BackupStatus.COMPLETED,
                    includedAppsCount = selectedApps.size,
                    includedFilesCount = manifestItems.count { it.type == BackupItemType.FILE },
                    includedDns = includeDns,
                    destination = destinationLabel ?: "Google Drive",
                    syncStatus = SyncStatus.UPLOADING
                )
                dbHelper.insertBackupRecord(record)
                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Backup SOMENTE_DRIVE gerado em staging temporário: ${driveStagingFile.name} (${driveStagingFile.length() / (1024 * 1024)} MB). Nenhuma cópia gravada em SwiftVaultBackups.")
                cpuGovernor.endProfiling()

                val failedCount = completedItemsList.count { !it.success }
                val finishWarning = if (failedCount > 0) "$failedCount item(ns) apresentaram avisos ou falha." else null

                emitProgress(
                    BackupProgress(
                        stage = if (failedCount > 0) "Concluído com avisos" else "Concluído",
                        currentItemName = "Container preparado em staging temporário para envio (${driveStagingFile.name})",
                        progressPercent = 1.0f,
                        bytesProcessed = fixedTotalBytes,
                        totalBytesEstimated = fixedTotalBytes,
                        isFinished = true,
                        error = finishWarning,
                        currentItemIndex = fixedTotalItems,
                        totalItems = fixedTotalItems,
                        completedItems = completedItemsList.toList(),
                        estimatedRemainingSeconds = 0L
                    )
                )
            } else {
                // SOMENTE_INTERNA ou INTERNA_E_DRIVE: grava na memória interna
                // 11. Create Destination Subfolder on Internal Storage Root ONLY after validation
                val finalBytes = (fixedTotalBytes * 0.95f).toLong()
                val finalItems = (fixedTotalItems * 0.95f).toInt().coerceIn(1, fixedTotalItems)
                emitProgress(
                    BackupProgress(
                        stage = "Finalizando",
                        currentItemName = "Criando pasta e movendo container validado para o armazenamento...",
                        progressPercent = 0.95f,
                        bytesProcessed = finalBytes,
                        totalBytesEstimated = fixedTotalBytes,
                        currentItemIndex = finalItems,
                        totalItems = fixedTotalItems,
                        completedItems = completedItemsList.toList(),
                        estimatedRemainingSeconds = FormatUtils.ESTIMATE_FINALIZING,
                        compressionSpeedBytesPerSec = progressEstimator.getEffectiveSpeedBytes()
                    )
                )

                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                val deviceSanitized = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}".replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val subfolderName = "${dateFormat.format(Date())}_$deviceSanitized"
                var finalVaultDir = effectiveVaultDir ?: destinationDir ?: File(getOrCreateDefaultVaultDir(), subfolderName)

                if (!finalVaultDir.exists()) {
                    val created = finalVaultDir.mkdirs()
                    if (!created && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                        com.topjohnwu.superuser.Shell.cmd("mkdir -p \"${finalVaultDir.absolutePath}\" && chmod 777 \"${finalVaultDir.absolutePath}\"").exec()
                    }
                }

                if (!finalVaultDir.exists() || !finalVaultDir.canWrite()) {
                    val fallbackBase = context.getExternalFilesDir(null) ?: context.filesDir
                    val fallbackDir = File(fallbackBase, "${com.resync.app.data.model.BackupPaths.BACKUP_DIR_NAME}/$subfolderName")
                    fallbackDir.mkdirs()
                    if (fallbackDir.exists()) {
                        dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Armazenamento padrão inacessível. Salvando no diretório do app: ${fallbackDir.absolutePath}")
                        finalVaultDir = fallbackDir
                    }
                }

                if (!finalVaultDir.exists()) {
                    stagingDir.deleteRecursively()
                    val dirErr = "Não foi possível criar o diretório final: ${finalVaultDir.absolutePath}"
                    dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", dirErr)
                    emitProgress(BackupProgress("Erro", dirErr, 1.0f, 0, 0, isFinished = true, error = dirErr, completedItems = completedItemsList.toList()))
                    return@channelFlow
                }

                val finalOutputFile = File(finalVaultDir, "$backupId${com.resync.app.data.model.BackupPaths.BACKUP_FILE_EXTENSION}")
                createdFinalDir = finalVaultDir
                createdFinalFile = finalOutputFile

                stagingFile.copyTo(finalOutputFile, overwrite = true)

                // Re-verify SHA-256 of copied file to guarantee exact disk copy
                val copiedSha256 = SecurityManager.computeFileSha256(finalOutputFile)
                if (copiedSha256 != stagingSha256) {
                    finalOutputFile.delete()
                    if (finalVaultDir.listFiles().isNullOrEmpty()) finalVaultDir.delete()
                    stagingDir.deleteRecursively()
                    val copyErr = "Divergência de hash SHA-256 após cópia para ${finalOutputFile.absolutePath}."
                    dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", copyErr)
                    emitProgress(BackupProgress("Erro", copyErr, 1.0f, 0, 0, isFinished = true, error = copyErr, completedItems = completedItemsList.toList()))
                    return@channelFlow
                }

                // Clean staging
                stagingDir.deleteRecursively()

                // Gerar índice sidecar svb_XXXX.index.json na memória interna e salvar no Room
                try {
                    val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(context)
                    val indexDao = roomDb.backupIndexDao()
                    val sidecarFile = File(finalVaultDir, "$backupId.index.json")
                    val jsonBytes = com.google.gson.Gson().toJson(readBackManifest).toByteArray(Charsets.UTF_8)
                    java.io.FileOutputStream(sidecarFile).use { fos ->
                        java.util.zip.GZIPOutputStream(fos).use { gzip ->
                            gzip.write(jsonBytes)
                        }
                    }
                    val baos = java.io.ByteArrayOutputStream()
                    java.util.zip.GZIPOutputStream(baos).use { it.write(jsonBytes) }
                    val compressedBase64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                    val indexEntity = com.resync.app.data.room.BackupIndexCacheEntity(
                        backupId = backupId,
                        backupTitle = backupTitle,
                        filePath = finalOutputFile.absolutePath,
                        driveFileId = null,
                        sizeBytes = finalOutputFile.length(),
                        manifestGzipBase64 = compressedBase64,
                        itemCount = readBackManifest.items.size,
                        cachedAt = System.currentTimeMillis()
                    )
                    indexDao.insertOrUpdate(indexEntity)
                    dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Índice sidecar comprimido gerado na memória interna e cache Room: ${sidecarFile.name}")
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Aviso ao salvar sidecar local: ${e.message}")
                }

                // 12. Persist Record to Database
                val record = BackupRecord(
                    id = backupId,
                    title = backupTitle,
                    filePath = finalOutputFile.absolutePath,
                    sizeBytes = finalOutputFile.length(),
                    checksumSha256 = copiedSha256,
                    isEncrypted = !encryptionPassword.isNullOrEmpty(),
                    timestamp = System.currentTimeMillis(),
                    itemCount = manifestItems.size,
                    status = BackupStatus.COMPLETED,
                    includedAppsCount = selectedApps.size,
                    includedFilesCount = manifestItems.count { it.type == BackupItemType.FILE },
                    includedDns = includeDns,
                    destination = destinationLabel ?: finalVaultDir.name
                )
                dbHelper.insertBackupRecord(record)
                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Backup concluído com sucesso e validado: ${finalOutputFile.name} (${finalOutputFile.length() / (1024 * 1024)} MB)")
                cpuGovernor.endProfiling()

                // Mark successful so finally won't delete it
                createdFinalFile = null
                createdFinalDir = null

                val failedCount = completedItemsList.count { !it.success && !it.isIgnored }
                val ignoredCount = completedItemsList.count { it.isIgnored }
                val finishWarning = if (failedCount > 0) {
                    "$failedCount item(ns) apresentaram falhas."
                } else if (ignoredCount > 0) {
                    "$ignoredCount item(ns) foram ignorados."
                } else null

                if (failedCount > 0) {
                    val errorReportText = buildString {
                        appendLine("Relatório de Falhas no Backup: ${finalOutputFile.name}")
                        completedItemsList.filter { !it.success && !it.isIgnored }.forEach { fail ->
                            appendLine("Item: ${fail.name}")
                            appendLine("Etapa: ${fail.stage}")
                            appendLine("Erro: ${fail.errorMessage}")
                            appendLine("Causa: ${fail.causeException}")
                            appendLine("Sugestão: ${fail.suggestedFix}")
                            appendLine("---")
                        }
                    }
                    dbHelper.insertLog(LogLevel.WARN, "BackupReport_${backupId}", finishWarning ?: "Falhas no backup", errorReportText)
                }

                val finalStage = if (failedCount > 0) {
                    "Finalizado com falhas"
                } else if (ignoredCount > 0) {
                    "Finalizado com avisos"
                } else {
                    "Concluído"
                }

                val finalItemName = if (failedCount > 0) {
                    "Backup finalizado com $failedCount falha(s) (${finalOutputFile.name})"
                } else if (ignoredCount > 0) {
                    "Backup finalizado com $ignoredCount item(ns) ignorado(s) (${finalOutputFile.name})"
                } else {
                    "Backup verificado com sucesso! (${finalOutputFile.name})"
                }

                emitProgress(
                    BackupProgress(
                        stage = finalStage,
                        currentItemName = finalItemName,
                        progressPercent = 1.0f,
                        bytesProcessed = fixedTotalBytes,
                        totalBytesEstimated = fixedTotalBytes,
                        isFinished = true,
                        error = if (failedCount > 0) finishWarning else null,
                        currentItemIndex = fixedTotalItems,
                        totalItems = fixedTotalItems,
                        completedItems = completedItemsList.toList(),
                        estimatedRemainingSeconds = 0L
                    )
                )
            }

        } catch (e: CancellationException) {
            stagingDir.deleteRecursively()
            createdFinalFile?.delete()
            createdFinalDir?.let { if (it.listFiles().isNullOrEmpty()) it.delete() }
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Operação de backup cancelada: ${e.message}")
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            createdFinalFile?.delete()
            createdFinalDir?.let { if (it.listFiles().isNullOrEmpty()) it.delete() }
            dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Erro crítico durante backup: ${e.message}")
            emitProgress(BackupProgress("Erro", e.message ?: "Falha ao gravar container de backup.", 1.0f, 0, 0, isFinished = true, error = e.message, completedItems = completedItemsList.toList()))
        } finally {
            etaCountdownTickerJob.cancel()
            progressEstimator.logCompletion()
            cpuGovernor.stopPeriodicCpuLogging()
            try {
                if (stagingDir.exists()) stagingDir.deleteRecursively()
            } catch (_: Throwable) {}
            activeStagingDir = null
        }
    }.flowOn(Dispatchers.IO.limitedParallelism(cpuGovernor.getOptimalThreadCount()))

    private fun collectFilesRecursively(
        target: File,
        filter: BackupFilter?,
        packEntries: MutableList<ArchiveEngine.PackEntry>,
        manifestItems: MutableList<ManifestItem>,
        baseDir: File? = null,
        onSizeAdded: (Long) -> Unit
    ) {
        val effectiveBase = baseDir ?: (if (target.isDirectory) target else target.parentFile ?: target)
        if (target.isFile) {
            if (matchesFilter(target, filter)) {
                val relPath = try {
                    target.relativeTo(effectiveBase).path.replace('\\', '/')
                } catch (_: Exception) {
                    target.name
                }
                val entryName = if (effectiveBase == target.parentFile) "files/" + target.name else "files/" + effectiveBase.name + "/" + relPath
                val item = ManifestItem(
                    id = UUID.randomUUID().toString(),
                    type = BackupItemType.FILE,
                    name = target.name,
                    originalPath = target.absolutePath,
                    archiveEntryPath = entryName,
                    sizeBytes = target.length(),
                    sha256Checksum = ""
                )
                packEntries.add(ArchiveEngine.PackEntry(entryName, sourceFile = target, manifestItem = item))
                manifestItems.add(item)
                onSizeAdded(target.length())
                if (filter?.isIncremental == true) {
                    try {
                        val prefs = context.getSharedPreferences("incremental_backup_index", android.content.Context.MODE_PRIVATE)
                        val key = target.absolutePath.hashCode().toString()
                        prefs.edit().putString(key, "${target.length()}:${target.lastModified()}").apply()
                    } catch (_: Throwable) {}
                }
            }
        } else if (target.isDirectory) {
            var children = target.listFiles()
            if ((children == null || children.isEmpty()) && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                try {
                    val out = com.topjohnwu.superuser.Shell.cmd("ls -1 \"${target.absolutePath}\"").exec().out
                    val rootFiles = out.mapNotNull { name ->
                        val child = File(target, name)
                        if (child.exists()) child else null
                    }
                    if (rootFiles.isNotEmpty()) {
                        children = rootFiles.toTypedArray()
                    }
                } catch (_: Exception) {}
            }
            if (children != null) {
                for (child in children) {
                    collectFilesRecursively(child, filter, packEntries, manifestItems, effectiveBase, onSizeAdded)
                }
            }
        }
    }

    private fun matchesFilter(file: File, filter: BackupFilter?): Boolean {
        if (filter == null) return true

        // Exclusions
        val fileNameLower = file.name.lowercase(Locale.ROOT)
        for (pattern in filter.excludedPatterns) {
            val cleanPattern = pattern.replace("*", "").lowercase(Locale.ROOT)
            if (cleanPattern.isNotEmpty() && fileNameLower.contains(cleanPattern)) {
                return false
            }
        }

        // Min Size
        if (filter.minSizeBytes > 0 && file.length() < filter.minSizeBytes) {
            return false
        }

        // Max Age Days
        if (filter.maxAgeDays > 0) {
            val ageMs = System.currentTimeMillis() - file.lastModified()
            val maxAgeMs = filter.maxAgeDays * 24L * 60 * 60 * 1000
            if (ageMs > maxAgeMs) return false
        }

        // Extensions
        if (filter.includedExtensions.isNotEmpty()) {
            val ext = "." + file.extension.lowercase(Locale.ROOT)
            if (!filter.includedExtensions.any { it.equals(ext, ignoreCase = true) }) {
                return false
            }
        }

        // Incremental check (pular o que não mudou por tamanho + data)
        if (filter.isIncremental) {
            val prefs = context.getSharedPreferences("incremental_backup_index", android.content.Context.MODE_PRIVATE)
            val key = file.absolutePath.hashCode().toString()
            val recorded = prefs.getString(key, null)
            if (recorded != null) {
                val parts = recorded.split(":")
                if (parts.size == 2) {
                    val recordedLen = parts[0].toLongOrNull()
                    val recordedMod = parts[1].toLongOrNull()
                    if (recordedLen == file.length() && recordedMod == file.lastModified()) {
                        return false // Inalterado por tamanho e data
                    }
                }
            }
        }

        return true
    }

    private suspend fun readSmsMessages(): List<SmsRecord> {
        val list = mutableListOf<SmsRecord>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                null, null, "${Telephony.Sms.DATE} DESC LIMIT 5000"
            )
            cursor?.use {
                val addrCol = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeCol = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readCol = it.getColumnIndexOrThrow(Telephony.Sms.READ)

                while (it.moveToNext()) {
                    list.add(
                        SmsRecord(
                            address = it.getString(addrCol) ?: "",
                            body = it.getString(bodyCol) ?: "",
                            date = it.getLong(dateCol),
                            type = it.getInt(typeCol),
                            read = it.getInt(readCol)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Não foi possível ler mensagens SMS: ${e.message}")
        }
        return list
    }

    private suspend fun readCallLogs(): List<CallRecord> {
        val list = mutableListOf<CallRecord>()
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.CACHED_NUMBER_TYPE
                ),
                null, null, "${CallLog.Calls.DATE} DESC LIMIT 5000"
            )
            cursor?.use {
                val numCol = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateCol = it.getColumnIndex(CallLog.Calls.DATE)
                val durCol = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeCol = it.getColumnIndex(CallLog.Calls.TYPE)
                val nameCol = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numTypeCol = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)

                while (it.moveToNext()) {
                    val number = if (numCol != -1) it.getString(numCol) ?: "" else ""
                    val date = if (dateCol != -1) it.getLong(dateCol) else 0L
                    val dur = if (durCol != -1) it.getLong(durCol) else 0L
                    val type = if (typeCol != -1) it.getInt(typeCol) else 1
                    val cachedName = if (nameCol != -1) it.getString(nameCol) else null
                    val numType = if (numTypeCol != -1) it.getInt(numTypeCol) else 0

                    list.add(
                        CallRecord(
                            number = number,
                            date = date,
                            duration = dur,
                            type = type,
                            cachedName = cachedName,
                            cachedNumberType = numType
                        )
                    )
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Não foi possível ler histórico de chamadas via ContentResolver: ${e.message}")
        }

        // Fallback via Root Shell se a lista estiver vazia e Root estiver disponível
        if (list.isEmpty() && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
            try {
                val rootRes = com.topjohnwu.superuser.Shell.cmd("content query --uri content://call_log/calls --projection number:date:duration:type:name:numbertype").exec()
                if (rootRes.isSuccess) {
                    for (line in rootRes.out) {
                        if (!line.startsWith("Row:")) continue
                        val pairs = line.substringAfter("Row:").trim().split(", ")
                        var number = ""
                        var date = 0L
                        var duration = 0L
                        var type = 1
                        var name: String? = null
                        var numType = 0
                        for (p in pairs) {
                            val k = p.substringBefore("=").trim()
                            val v = p.substringAfter("=").trim()
                            when (k) {
                                "number" -> if (v != "NULL") number = v
                                "date" -> date = v.toLongOrNull() ?: 0L
                                "duration" -> duration = v.toLongOrNull() ?: 0L
                                "type" -> type = v.toIntOrNull() ?: 1
                                "name" -> if (v != "NULL") name = v
                                "numbertype" -> numType = v.toIntOrNull() ?: 0
                            }
                        }
                        if (number.isNotBlank() || date > 0L) {
                            list.add(
                                CallRecord(
                                    number = number,
                                    date = date,
                                    duration = duration,
                                    type = type,
                                    cachedName = name,
                                    cachedNumberType = numType
                                )
                            )
                        }
                    }
                }
            } catch (e2: Exception) {
                dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Falha na leitura de chamadas via Root: ${e2.message}")
            }
        }

        return list
    }

    private data class WallpaperCaptureResult(
        val bytes: ByteArray?,
        val liveWallpaperService: String? = null,
        val isSharedWithHome: Boolean = false,
        val source: String = "unknown"
    )

    private fun captureLiveWallpaperInfo(which: Int): String? {
        return try {
            val wallpaperManager = WallpaperManager.getInstance(context)
            // No Android N+, wallpaperInfo pode representar o papel animado ativo
            val info = wallpaperManager.wallpaperInfo
            if (info != null) {
                "${info.packageName}/${info.serviceName}"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readWallpaperViaRoot(filename: String): ByteArray? {
        return try {
            val rootPath = "/data/system/users/0/$filename"
            val tempFile = File(context.cacheDir, "wp_temp_${System.currentTimeMillis()}_$filename")
            val copyRes = Shell.cmd("cp \"$rootPath\" \"${tempFile.absolutePath}\" && chmod 644 \"${tempFile.absolutePath}\"").exec()
            if (copyRes.isSuccess && tempFile.exists() && tempFile.length() > 0) {
                val bytes = tempFile.readBytes()
                tempFile.delete()
                bytes
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun captureWallpaperBytesByFlag(which: Int): WallpaperCaptureResult {
        val isLock = (which == WallpaperManager.FLAG_LOCK)
        val wallpaperManager = WallpaperManager.getInstance(context)

        // 1. Verificar se é Live Wallpaper
        val liveInfo = captureLiveWallpaperInfo(which)
        if (liveInfo != null) {
            dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Papel de parede (${if (isLock) "bloqueio" else "inicial"}) é animado: $liveInfo")
        }

        // 2. Se for Lock screen, verificar se existe arquivo exclusivo de lock no sistema via Root ou API
        if (isLock) {
            // Verificar no sistema de arquivos se wallpaper_lock existe
            val lockExists = Shell.cmd("[ -f /data/system/users/0/wallpaper_lock ] || [ -f /data/system/users/0/wallpaper_lock_orig ]").exec().isSuccess
            // Se não existe wallpaper_lock, a tela de bloqueio usa o mesmo papel da tela inicial
            if (!lockExists) {
                val pfdLock = try { wallpaperManager.getWallpaperFile(WallpaperManager.FLAG_LOCK) } catch (e: Exception) { null }
                if (pfdLock == null) {
                    return WallpaperCaptureResult(
                        bytes = null,
                        liveWallpaperService = liveInfo,
                        isSharedWithHome = true,
                        source = "shared_home"
                    )
                } else {
                    try { pfdLock.close() } catch (ignored: Exception) {}
                }
            }
        }

        // 3. Tentar getWallpaperFile (formato original, sem recompressão)
        try {
            val pfd = wallpaperManager.getWallpaperFile(which)
            if (pfd != null) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        return WallpaperCaptureResult(bytes = bytes, liveWallpaperService = liveInfo, source = "WallpaperManager.getWallpaperFile")
                    }
                }
            }
        } catch (e: Exception) {
            // Sem permissão de sistema ou não suportado pela ROM
        }

        // 4. Fallback Root direto de /data/system/users/0/
        if (isLock) {
            val lockOrig = readWallpaperViaRoot("wallpaper_lock_orig") ?: readWallpaperViaRoot("wallpaper_lock")
            if (lockOrig != null) {
                return WallpaperCaptureResult(bytes = lockOrig, liveWallpaperService = liveInfo, source = "root_data_system")
            }
        } else {
            val homeOrig = readWallpaperViaRoot("wallpaper_orig") ?: readWallpaperViaRoot("wallpaper")
            if (homeOrig != null) {
                return WallpaperCaptureResult(bytes = homeOrig, liveWallpaperService = liveInfo, source = "root_data_system")
            }
        }

        // 5. Fallback via Drawable / Bitmap (lossless PNG 100%)
        try {
            val drawable = if (isLock) {
                // Tenta drawable com flag se suportado, senão drawable padrão
                try {
                    val getFastDrawableMethod = wallpaperManager.javaClass.getMethod("getFastDrawable", Int::class.javaPrimitiveType)
                    getFastDrawableMethod.invoke(wallpaperManager, which) as? android.graphics.drawable.Drawable
                } catch (e: Exception) {
                    null
                }
            } else {
                wallpaperManager.drawable
            }

            if (drawable != null) {
                val bitmap = if (drawable is BitmapDrawable) {
                    drawable.bitmap
                } else {
                    IconCacheManager.getInstance(context).drawableToBitmap(drawable)
                }
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                val bytes = stream.toByteArray()
                return WallpaperCaptureResult(bytes = bytes, liveWallpaperService = liveInfo, source = "drawable_bitmap")
            }
        } catch (e: Exception) {
            // Falha na extração de drawable
        }

        // Se lock não conseguiu nada mas é lock, assumir que compartilha com a home
        if (isLock) {
            return WallpaperCaptureResult(
                bytes = null,
                liveWallpaperService = liveInfo,
                isSharedWithHome = true,
                source = "shared_home"
            )
        }

        return WallpaperCaptureResult(bytes = null, liveWallpaperService = liveInfo, source = "failed")
    }

    fun getContactsCount(): Int {
        return try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Contacts._ID),
                null, null, null
            )
            cursor?.use { it.count } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    suspend fun readContactsList(): List<ContactRecord> {
        val list = mutableListOf<ContactRecord>()
        try {
            val resolver = context.contentResolver
            val cursor = resolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.Contacts._ID,
                    android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                    android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                null, null, null
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
                val nameCol = c.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneCol = c.getColumnIndex(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)

                while (c.moveToNext()) {
                    val id = if (idCol != -1) c.getString(idCol) ?: "" else ""
                    val name = if (nameCol != -1) c.getString(nameCol) ?: "Sem Nome" else "Sem Nome"
                    val hasPhone = if (hasPhoneCol != -1) c.getInt(hasPhoneCol) > 0 else false

                    val phones = mutableListOf<String>()
                    if (hasPhone && id.isNotEmpty()) {
                        val pCursor = resolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        pCursor?.use { pc ->
                            val numCol = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (pc.moveToNext()) {
                                if (numCol != -1) {
                                    pc.getString(numCol)?.let { phones.add(it) }
                                }
                            }
                        }
                    }

                    val emails = mutableListOf<String>()
                    if (id.isNotEmpty()) {
                        val eCursor = resolver.query(
                            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS),
                            "${android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                            arrayOf(id),
                            null
                        )
                        eCursor?.use { ec ->
                            val emailCol = ec.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS)
                            while (ec.moveToNext()) {
                                if (emailCol != -1) {
                                    ec.getString(emailCol)?.let { emails.add(it) }
                                }
                            }
                        }
                    }

                    list.add(ContactRecord(id = id, displayName = name, phoneNumbers = phones, emails = emails))
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Acesso aos contatos restrito ou erro: ${e.message}")
        }
        return list
    }

    fun exportContactsToVcf(contacts: List<ContactRecord>): ByteArray {
        val sb = StringBuilder()
        for (contact in contacts) {
            sb.appendLine("BEGIN:VCARD")
            sb.appendLine("VERSION:3.0")
            sb.appendLine("FN:${contact.displayName}")
            val nameParts = contact.displayName.trim().split(" ", limit = 2)
            val family = if (nameParts.size > 1) nameParts[1] else ""
            val given = nameParts[0]
            sb.appendLine("N:$family;$given;;;")
            for (phone in contact.phoneNumbers) {
                if (phone.isNotBlank()) {
                    sb.appendLine("TEL;TYPE=CELL:$phone")
                }
            }
            for (email in contact.emails) {
                if (email.isNotBlank()) {
                    sb.appendLine("EMAIL;TYPE=INTERNET:$email")
                }
            }
            sb.appendLine("END:VCARD")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private suspend fun backupTelephonyDatabases(stagingDir: File): List<Pair<ArchiveEngine.PackEntry, ManifestItem>> {
        val results = mutableListOf<Pair<ArchiveEngine.PackEntry, ManifestItem>>()
        try {
            val dbDir = "/data/data/com.android.providers.telephony/databases"
            val listCmd = com.topjohnwu.superuser.Shell.cmd("ls $dbDir/mmssms.db*").exec()
            if (!listCmd.isSuccess || listCmd.out.isEmpty()) {
                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Bancos mmssms.db não encontrados em $dbDir")
                return results
            }

            for (remotePath in listCmd.out) {
                val path = remotePath.trim()
                if (path.isBlank() || path.contains("No such file", ignoreCase = true)) continue
                val fileName = File(path).name
                val stagedFile = File(stagingDir, "telephony_$fileName")
                val cpCmd = com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 cp \"$path\" \"${stagedFile.absolutePath}\" && chmod 644 \"${stagedFile.absolutePath}\"").exec()
                if (cpCmd.isSuccess && stagedFile.exists() && stagedFile.length() > 0L) {
                    val entryName = "system/telephony/databases/$fileName"
                    val checksum = SecurityManager.computeFileSha256(stagedFile)
                    val item = ManifestItem(
                        id = UUID.randomUUID().toString(),
                        type = BackupItemType.SMS,
                        name = "Banco de Mensagens ($fileName)",
                        originalPath = path,
                        archiveEntryPath = entryName,
                        sizeBytes = stagedFile.length(),
                        sha256Checksum = checksum,
                        compressionMethod = "STORED",
                        metadata = mapOf("isDatabase" to "true", "fileName" to fileName)
                    )
                    results.add(Pair(ArchiveEngine.PackEntry(entryName, sourceFile = stagedFile, manifestItem = item, isStoreOnly = true), item))
                    dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Banco de SMS copiado: $path (${stagedFile.length()} bytes)")
                }
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Falha ao copiar banco telephony: ${e.message}")
        }
        return results
    }

    private suspend fun backupWifiConfig(stagingDir: File): Pair<ArchiveEngine.PackEntry?, ManifestItem?> {
        val hasRoot = com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true
        if (!hasRoot) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Backup de Wi-Fi ignorado: requer privilégios Root.")
            return Pair(null, null)
        }

        val candidatePaths = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/wpa_supplicant.conf"
        )

        var foundSourcePath: String? = null
        for (candidate in candidatePaths) {
            val checkCmd = com.topjohnwu.superuser.Shell.cmd("test -f \"$candidate\" && echo exists").exec()
            if (checkCmd.isSuccess && checkCmd.out.contains("exists")) {
                foundSourcePath = candidate
                break
            }
        }

        if (foundSourcePath == null) {
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Nenhum arquivo de configuração Wi-Fi encontrado nos caminhos padrão do sistema.")
            return Pair(null, null)
        }

        val sourceFile = File(foundSourcePath)
        val stagedFile = File(stagingDir, "wifi_${sourceFile.name}")
        val cpCmd = com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 cp \"$foundSourcePath\" \"${stagedFile.absolutePath}\" && chmod 644 \"${stagedFile.absolutePath}\"").exec()
        if (!cpCmd.isSuccess || !stagedFile.exists() || stagedFile.length() == 0L) {
            val errDetail = cpCmd.err.joinToString().ifEmpty { "código de saída ${cpCmd.code}" }
            dbHelper.insertLog(LogLevel.ERROR, "BackupEngine", "Falha ao copiar arquivo Wi-Fi ($foundSourcePath): $errDetail")
            return Pair(null, null)
        }

        val entryName = "system/wifi/${sourceFile.name}"
        val checksum = SecurityManager.computeFileSha256(stagedFile)

        val item = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.WIFI_CONFIG,
            name = "Configuração Wi-Fi (${sourceFile.name})",
            originalPath = foundSourcePath,
            archiveEntryPath = entryName,
            sizeBytes = stagedFile.length(),
            sha256Checksum = checksum,
            compressionMethod = "STORED",
            metadata = mapOf(
                "sourcePath" to foundSourcePath,
                "androidSdk" to android.os.Build.VERSION.SDK_INT.toString(),
                "fileName" to sourceFile.name
            )
        )

        val packEntry = ArchiveEngine.PackEntry(entryName, sourceFile = stagedFile, manifestItem = item, isStoreOnly = true)
        dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Configuração Wi-Fi capturada com sucesso de: $foundSourcePath (${stagedFile.length()} bytes)")
        return Pair(packEntry, item)
    }

    private suspend fun backupBluetooth(stagingDir: File): List<Pair<ArchiveEngine.PackEntry, ManifestItem>> {
        val results = mutableListOf<Pair<ArchiveEngine.PackEntry, ManifestItem>>()
        val bondedRecords = mutableListOf<BluetoothDeviceRecord>()
        val connectedRecords = mutableListOf<BluetoothDeviceRecord>()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter ?: try {
            @Suppress("DEPRECATION")
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }

        val hasBtConnectPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // a) Query bonded devices via BluetoothAdapter.bondedDevices
        if (bluetoothAdapter != null && hasBtConnectPerm) {
            try {
                @Suppress("MissingPermission")
                val bonded = bluetoothAdapter.bondedDevices ?: emptySet()
                for (dev in bonded) {
                    val name = try { dev.name ?: "Desconhecido" } catch (_: SecurityException) { "Sem permissão" }
                    val address = try { dev.address ?: "00:00:00:00:00:00" } catch (_: SecurityException) { "00:00:00:00:00:00" }
                    val devClass = try {
                        val c = dev.bluetoothClass
                        if (c != null) "0x${Integer.toHexString(c.deviceClass)} (${c.majorDeviceClass})" else "Desconhecido"
                    } catch (_: Throwable) { "Desconhecido" }

                    val bondState = when (dev.bondState) {
                        android.bluetooth.BluetoothDevice.BOND_BONDED -> "BONDED"
                        android.bluetooth.BluetoothDevice.BOND_BONDING -> "BONDING"
                        else -> "NONE"
                    }

                    // b) Query connected state across A2DP, HEADSET, and GATT profiles
                    val connectedProfiles = mutableListOf<String>()
                    var isConnected = false
                    try {
                        if (bluetoothAdapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            connectedProfiles.add("A2DP (Áudio)")
                            isConnected = true
                        }
                    } catch (_: Throwable) {}
                    try {
                        if (bluetoothAdapter.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            connectedProfiles.add("HEADSET (Voz)")
                            isConnected = true
                        }
                    } catch (_: Throwable) {}
                    try {
                        if (bluetoothManager != null) {
                            val gattDevices = bluetoothManager.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
                            if (gattDevices.any { it.address.equals(address, ignoreCase = true) }) {
                                connectedProfiles.add("GATT")
                                isConnected = true
                            }
                        }
                    } catch (_: Throwable) {}

                    val record = BluetoothDeviceRecord(
                        name = name,
                        address = address,
                        deviceClass = devClass,
                        bondState = bondState,
                        isConnected = isConnected,
                        connectedProfiles = connectedProfiles
                    )
                    bondedRecords.add(record)
                    if (isConnected) {
                        connectedRecords.add(record)
                    }
                }
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.WARN, "BackupEngine", "Erro ao ler dispositivos Bluetooth pareados: ${e.message}")
            }
        } else {
            val msg = if (bluetoothAdapter == null) "BluetoothAdapter indisponível no dispositivo." else "Permissão BLUETOOTH_CONNECT ausente no Android 12+."
            dbHelper.insertLog(LogLevel.WARN, "BackupEngine", msg)
        }

        // c) With root: copy configuration file (bt_config.conf), checking known paths
        var configFileStaged: File? = null
        var configFoundPath: String? = null
        var configStatusMsg = "indisponível nesta versão"

        if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
            val candidatePaths = listOf(
                "/data/misc/bluedroid/bt_config.conf",
                "/data/misc/bluetooth/bt_config.conf",
                "/data/misc/bluetooth/bt_config.xml",
                "/data/misc/bluedroid/bt_config.xml"
            )

            for (cand in candidatePaths) {
                val checkCmd = com.topjohnwu.superuser.Shell.cmd("test -f \"$cand\" && echo exists").exec()
                if (checkCmd.isSuccess && checkCmd.out.contains("exists")) {
                    configFoundPath = cand
                    break
                }
            }

            if (configFoundPath != null) {
                val origFile = File(configFoundPath)
                val targetStaged = File(stagingDir, "bt_${origFile.name}")
                val cpCmd = com.topjohnwu.superuser.Shell.cmd("nice -n 19 ionice -c 3 cp \"$configFoundPath\" \"${targetStaged.absolutePath}\" && chmod 644 \"${targetStaged.absolutePath}\"").exec()
                if (cpCmd.isSuccess && targetStaged.exists() && targetStaged.length() > 0L) {
                    configFileStaged = targetStaged
                    configStatusMsg = "Capturado com sucesso de: $configFoundPath"
                    dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "bt_config.conf copiado com sucesso de $configFoundPath (${targetStaged.length()} bytes)")
                } else {
                    configStatusMsg = "Falha ao ler arquivo em $configFoundPath: ${cpCmd.err.joinToString()}"
                    dbHelper.insertLog(LogLevel.WARN, "BackupEngine", configStatusMsg)
                }
            } else {
                configStatusMsg = "indisponível nesta versão"
                dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "bt_config.conf não encontrado nos caminhos padrão (indisponível nesta versão)")
            }
        } else {
            configStatusMsg = "indisponível nesta versão (requer root)"
            dbHelper.insertLog(LogLevel.INFO, "BackupEngine", "Root não autorizado para acessar arquivos de sistema do Bluetooth")
        }

        // d) Save everything in .svb as JSON, human-readable report and original config file
        val backupData = BluetoothBackupData(
            backupTimestamp = System.currentTimeMillis(),
            totalBonded = bondedRecords.size,
            totalConnected = connectedRecords.size,
            configFilePresent = configFileStaged != null,
            configStatusMessage = configStatusMsg,
            bondedDevices = bondedRecords,
            connectedDevices = connectedRecords
        )

        // 1. JSON backup
        val jsonBytes = gson.toJson(backupData).toByteArray(Charsets.UTF_8)
        val jsonEntryName = "system/bluetooth/bluetooth_backup.json"
        val jsonItem = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.BLUETOOTH,
            name = "Dispositivos Bluetooth (${bondedRecords.size} pareados)",
            originalPath = "BluetoothAdapter.bondedDevices",
            archiveEntryPath = jsonEntryName,
            sizeBytes = jsonBytes.size.toLong(),
            sha256Checksum = SecurityManager.computeSha256(jsonBytes),
            compressionMethod = "DEFLATE",
            metadata = mapOf(
                "totalBonded" to bondedRecords.size.toString(),
                "totalConnected" to connectedRecords.size.toString(),
                "configStatus" to configStatusMsg,
                "configFilePresent" to (configFileStaged != null).toString(),
                "isBluetoothJson" to "true"
            )
        )
        results.add(Pair(ArchiveEngine.PackEntry(jsonEntryName, rawData = jsonBytes, manifestItem = jsonItem), jsonItem))

        // 2. Human-readable text report
        val txtBuilder = java.lang.StringBuilder()
        txtBuilder.appendLine("==================================================")
        txtBuilder.appendLine("SWIFTVAULT - RELATÓRIO DE DISPOSITIVOS BLUETOOTH")
        txtBuilder.appendLine("==================================================")
        txtBuilder.appendLine("Data do Backup: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
        txtBuilder.appendLine("Dispositivos Pareados: ${bondedRecords.size}")
        txtBuilder.appendLine("Dispositivos Conectados: ${connectedRecords.size}")
        txtBuilder.appendLine("Arquivo de Chaves Original: $configStatusMsg")
        txtBuilder.appendLine("--------------------------------------------------")
        if (bondedRecords.isEmpty()) {
            txtBuilder.appendLine("Nenhum dispositivo emparelhado encontrado.")
        } else {
            bondedRecords.forEachIndexed { i, dev ->
                txtBuilder.appendLine("${i + 1}. Nome: ${dev.name}")
                txtBuilder.appendLine("   Endereço MAC: ${dev.address}")
                txtBuilder.appendLine("   Classe: ${dev.deviceClass}")
                txtBuilder.appendLine("   Estado: ${dev.bondState}")
                txtBuilder.appendLine("   Conectado: ${if (dev.isConnected) "SIM (${dev.connectedProfiles.joinToString()})" else "NÃO"}")
                txtBuilder.appendLine("--------------------------------------------------")
            }
        }
        val txtBytes = txtBuilder.toString().toByteArray(Charsets.UTF_8)
        val txtEntryName = "system/bluetooth/dispositivos_pareados.txt"
        val txtItem = ManifestItem(
            id = UUID.randomUUID().toString(),
            type = BackupItemType.BLUETOOTH,
            name = "Relatório Legível de Bluetooth",
            originalPath = "system/bluetooth/dispositivos_pareados.txt",
            archiveEntryPath = txtEntryName,
            sizeBytes = txtBytes.size.toLong(),
            sha256Checksum = SecurityManager.computeSha256(txtBytes),
            compressionMethod = "DEFLATE",
            metadata = mapOf("format" to "txt")
        )
        results.add(Pair(ArchiveEngine.PackEntry(txtEntryName, rawData = txtBytes, manifestItem = txtItem), txtItem))

        // 3. Original configuration file if found via root
        if (configFileStaged != null && configFoundPath != null) {
            val fileName = configFileStaged.name.removePrefix("bt_")
            val confEntryName = "system/bluetooth/$fileName"
            val confChecksum = SecurityManager.computeFileSha256(configFileStaged)
            val confItem = ManifestItem(
                id = UUID.randomUUID().toString(),
                type = BackupItemType.BLUETOOTH,
                name = "Arquivo de Configuração Bluetooth ($fileName)",
                originalPath = configFoundPath,
                archiveEntryPath = confEntryName,
                sizeBytes = configFileStaged.length(),
                sha256Checksum = confChecksum,
                compressionMethod = "STORED",
                metadata = mapOf(
                    "isConfigFile" to "true",
                    "originalSystemPath" to configFoundPath,
                    "fileName" to fileName
                )
            )
            results.add(Pair(ArchiveEngine.PackEntry(confEntryName, sourceFile = configFileStaged, manifestItem = confItem, isStoreOnly = true), confItem))
        }

        return results
    }

    fun getOrCreateDefaultVaultDir(): File {
        val rootDir = com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir()
        if (!rootDir.exists()) {
            val created = rootDir.mkdirs()
            if (!created && com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                com.topjohnwu.superuser.Shell.cmd("mkdir -p \"${rootDir.absolutePath}\"").exec()
            }
        }
        return rootDir
    }
}

