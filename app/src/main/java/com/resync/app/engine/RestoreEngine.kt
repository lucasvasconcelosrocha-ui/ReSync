package com.resync.app.engine

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Settings
import com.google.gson.Gson
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.BackupManifest
import com.resync.app.data.model.BluetoothBackupData
import com.resync.app.data.model.BluetoothDeviceRecord
import com.resync.app.data.model.CallRecord
import com.resync.app.data.model.CompletedItemInfo
import com.resync.app.data.model.ContactRecord
import com.resync.app.data.model.DnsConfig
import com.resync.app.data.model.LogLevel
import com.resync.app.data.model.ManifestItem
import com.resync.app.data.model.ProgressRateLimiter
import com.resync.app.data.model.SmsRecord
import com.resync.app.util.ShellSecurityUtils.shellEscape
import com.resync.app.util.ShellSecurityUtils.validatePackageName
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream

data class RestoreProgress(
    val stage: String,
    val currentItemName: String,
    val progressPercent: Float,
    val isFinished: Boolean = false,
    val error: String? = null,
    val manualActionRequired: String? = null,
    val currentItemIndex: Int = 0,
    val totalItems: Int = 0,
    val currentItemType: BackupItemType = BackupItemType.FILE,
    val completedItems: List<CompletedItemInfo> = emptyList(),
    val isPaused: Boolean = false,
    val bytesProcessed: Long = 0L,
    val totalBytesEstimated: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L,
    val estimatedRemainingSeconds: Long = 0L,
    val currentPackageName: String = "",
    val containerFile: File? = null,
    val parts: List<OperationPartInfo> = emptyList(),
    val thermalMode: String = "Máximo",
    val deviceTemperature: Float = 0f,
    val localStage: String = "",
    val itemsPerSecond: Float = 0f
)

/**
 * Main Restoration Engine for SwiftVault.
 * Unpacks containers and selectively restores Apps (Base APK + Splits via Root pm session or PackageInstaller),
 * files, wallpapers, telephony records, contacts, and DNS settings.
 */
class RestoreEngine(private val context: Context) {

    private val dbHelper = AppDatabaseHelper.getInstance(context)
    private val dnsManager = DnsManager(context)
    private val gson = Gson()

    private val isPaused = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var pauseDeferred: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun pause() {
        isPaused.set(true)
        if (pauseDeferred == null || pauseDeferred?.isCompleted == true) {
            pauseDeferred = kotlinx.coroutines.CompletableDeferred()
        }
    }

    fun resume() {
        isPaused.set(false)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
    }

    fun cancel() {
        isCancelled.set(true)
        isPaused.set(false)
        pauseDeferred?.complete(Unit)
        pauseDeferred = null
    }

    suspend fun checkPauseAndCancel(
        onPausedNotice: (suspend () -> Unit)? = null,
        onResumedNotice: (suspend () -> Unit)? = null
    ) {
        if (isCancelled.get()) {
            throw kotlinx.coroutines.CancellationException("Restauração cancelada pelo usuário.")
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
                throw kotlinx.coroutines.CancellationException("Restauração cancelada pelo usuário.")
            }
            onResumedNotice?.invoke()
        }
    }

    /**
     * Inspects container and returns the embedded manifest
     */
    suspend fun inspectBackup(containerFile: File): BackupManifest {
        return ArchiveEngine.readManifest(containerFile)
    }

    /**
     * Executes selective restoration of specific items
     */
    fun performSelectiveRestore(
        containerFile: File,
        manifest: BackupManifest,
        selectedItemIds: Set<String>,
        password: String? = null,
        selectedCallKeys: Set<String>? = null,
        selectedSmsKeys: Set<String>? = null,
        selectedContactKeys: Set<String>? = null,
        selectedWifiKeys: Set<String>? = null,
        selectedBtKeys: Set<String>? = null
    ): Flow<RestoreProgress> = channelFlow {
        send(RestoreProgress("Iniciando", "Lendo cabeçalho do container...", 0.05f))
        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Iniciando restauração seletiva de: ${containerFile.name}")

        val selectedFolders = manifest.items.filter { selectedItemIds.contains(it.id) && it.type == BackupItemType.FOLDER }
        val itemsToRestore = mutableListOf<ManifestItem>()
        for (item in manifest.items) {
            if (selectedItemIds.contains(item.id)) {
                itemsToRestore.add(item)
            } else {
                val isChildOfSelectedFolder = selectedFolders.any { folder ->
                    item.archiveEntryPath.startsWith(folder.archiveEntryPath.trimEnd('/') + "/") ||
                    (folder.originalPath.isNotBlank() && item.originalPath.startsWith(folder.originalPath.trimEnd('/') + "/"))
                }
                if (isChildOfSelectedFolder) {
                    itemsToRestore.add(item)
                }
            }
        }
        if (itemsToRestore.isEmpty()) {
            send(RestoreProgress("Concluído", "Nenhum item selecionado para restauração.", 1.0f, isFinished = true))
            return@channelFlow
        }

        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        } catch (_: Throwable) {}

        val tempRestoreDir = File(context.cacheDir, "swiftvault_restore_temp_${System.currentTimeMillis()}")
        tempRestoreDir.mkdirs()

        val appResults = mutableListOf<Pair<String, RestoreInstallResult>>()
        val generalErrors = mutableListOf<String>()
        val completedItemsList = mutableListOf<CompletedItemInfo>()
        val rateLimiter = ProgressRateLimiter(500L) // Máximo 2 atualizações por segundo

        // 1. Group Apps (APKs, Splits, Data tarballs) and Other items to compute totalItems
        val appItemsList = itemsToRestore.filter {
            it.type == BackupItemType.APP_APK ||
            it.type == BackupItemType.APP_SPLIT_APKS ||
            it.type == BackupItemType.APP_DATA ||
            (it.archiveEntryPath.startsWith("apps/") && !it.archiveEntryPath.endsWith("metadata.json"))
        }
        val appGroups = appItemsList.groupBy {
            it.metadata["packageName"]?.takeIf { p -> p.isNotBlank() }
                ?: if (it.archiveEntryPath.startsWith("apps/")) {
                    it.archiveEntryPath.removePrefix("apps/").substringBefore("/")
                } else "unknown"
        }
        val validAppGroups = appGroups.filter {
            it.key != "unknown" && !ProcessControlManager.isPackageProtected(it.key, context)
        }
        val totalApps = validAppGroups.size
        // Exclude child files whose parent folder is being restored as a folder unit to avoid duplicate steps
        val otherItems = itemsToRestore.filter { item ->
            !appItemsList.contains(item) &&
            !item.archiveEntryPath.endsWith("metadata.json") &&
            !(item.type == BackupItemType.FILE && selectedFolders.any { folder ->
                item.archiveEntryPath.startsWith(folder.archiveEntryPath.trimEnd('/') + "/") ||
                (folder.originalPath.isNotBlank() && item.originalPath.startsWith(folder.originalPath.trimEnd('/') + "/"))
            })
        }
        val totalRestoreItems = totalApps + otherItems.size
        var currentItemIdx = 0

        try {
            // Unpack selected files from container
            val archivePaths = itemsToRestore.map { it.archiveEntryPath }.toSet()

            send(
                RestoreProgress(
                    stage = "Descompactando",
                    currentItemName = "Extraindo arquivos do container...",
                    progressPercent = 0.15f,
                    currentItemIndex = 0,
                    totalItems = totalRestoreItems,
                    currentItemType = BackupItemType.FILE,
                    completedItems = completedItemsList.toList()
                )
            )

            val activeAccountEmail = context.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
                .getString("active_account_email", null)

            ArchiveEngine.unpackContainer(
                containerFile = containerFile,
                targetOutputDir = tempRestoreDir,
                manifest = manifest,
                selectedArchivePaths = archivePaths,
                decryptionPassword = password,
                accountEmail = activeAccountEmail,
                onProgress = null,
                onItemProgress = { bytesRead, total, entryName ->
                    val ratio = if (total > 0) (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.5f
                    val percent = 0.15f + (ratio * 0.40f) // 15% -> 55%
                    if (rateLimiter.shouldEmit()) {
                        send(
                            RestoreProgress(
                                stage = "Descompactando",
                                currentItemName = entryName,
                                progressPercent = percent,
                                currentItemIndex = 0,
                                totalItems = totalRestoreItems,
                                currentItemType = BackupItemType.FILE,
                                completedItems = completedItemsList.toList()
                            )
                        )
                    }
                }
            )

            val totalApps = validAppGroups.size
            val capabilityManager = CapabilityManager(context)
            val hasRoot = capabilityManager.verifyRootAccess()
            val modeLabel = if (hasRoot) "Root" else "Sem root"
            dbHelper.insertLog(
                LogLevel.INFO,
                "RestoreEngine",
                "Modo de acesso detectado para restauração: $modeLabel (uid=${if (hasRoot) 0 else android.os.Process.myUid()})"
            )

            val anyAppDataInBackup = validAppGroups.values.any { items ->
                items.any { it.type == BackupItemType.APP_DATA || it.archiveEntryPath.endsWith(".tar") || it.archiveEntryPath.endsWith(".tar.gz") }
            }
            if (!hasRoot && anyAppDataInBackup) {
                dbHelper.insertLog(
                    LogLevel.WARN,
                    "RestoreEngine",
                    "Dados de apps exigem root. Restaurando apenas os APKs"
                )
            }

            // If non-root and there are apps to install, verify unknown sources permission
            if (!hasRoot && totalApps > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    send(
                        RestoreProgress(
                            stage = "Permissão Necessária",
                            currentItemName = "Autorização de Instalação",
                            progressPercent = 0.58f,
                            manualActionRequired = "Habilite a instalação de fontes desconhecidas para o ReSync nas Configurações abertas para restaurar os aplicativos.",
                            currentItemIndex = currentItemIdx,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.APP_APK,
                            completedItems = completedItemsList.toList(),
                            localStage = modeLabel
                        )
                    )
                    try {
                        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(settingsIntent)
                    } catch (e: Exception) {
                        dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Não foi possível abrir Settings: ${e.message}")
                    }

                    // Aguarda o usuário autorizar por até 30 segundos
                    var waited = 0
                    while (!context.packageManager.canRequestPackageInstalls() && waited < 30) {
                        delay(1000)
                        waited++
                    }
                }
            }

            // 2. Process App Packages (Base APK + Splits + App Data tarballs)
            for ((pkgName, appItems) in validAppGroups) {
                checkPauseAndCancel(
                    onPausedNotice = {
                        send(
                            RestoreProgress(
                                stage = "Pausado",
                                currentItemName = "Restauração pausada em $pkgName",
                                progressPercent = 0.58f,
                                isPaused = true,
                                currentItemIndex = currentItemIdx,
                                totalItems = totalRestoreItems,
                                completedItems = completedItemsList.toList()
                            )
                        )
                    }
                )
                currentItemIdx++
                val stepPercent = 0.58f + ((currentItemIdx.toFloat() / (if (totalRestoreItems > 0) totalRestoreItems else 1).toFloat()) * 0.22f)
                val modeLabel = if (hasRoot) "via Root" else "via Instalador"
                val appLabel = appItems.firstOrNull { it.name.isNotBlank() }?.name ?: pkgName

                send(
                    RestoreProgress(
                        stage = "Restaurando App",
                        currentItemName = "$appLabel ($pkgName)",
                        progressPercent = stepPercent,
                        currentItemIndex = currentItemIdx,
                        totalItems = totalRestoreItems,
                        currentItemType = BackupItemType.APP_APK,
                        completedItems = completedItemsList.toList(),
                        currentPackageName = pkgName,
                        containerFile = containerFile
                    )
                )

                val apkItems = appItems.filter { it.type == BackupItemType.APP_APK || it.type == BackupItemType.APP_SPLIT_APKS || it.archiveEntryPath.endsWith(".apk") }

                // Verifica se já está instalado e se versão atual é mais nova
                var shouldSkipApkInstall = false
                try {
                    val installedPkg = context.packageManager.getPackageInfo(pkgName, 0)
                    val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) installedPkg.longVersionCode else @Suppress("DEPRECATION") installedPkg.versionCode.toLong()
                    val backupMetaItem = appItems.find { it.archiveEntryPath.endsWith("metadata.json") }
                    val backupVersion = backupMetaItem?.metadata?.get("versionCode")?.toLongOrNull() ?: 0L

                    if (installedVersion > backupVersion && backupVersion > 0) {
                        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "App $pkgName já instalado em versão mais recente (instalada: $installedVersion, backup: $backupVersion). Mantendo versão do sistema e restaurando dados.")
                        shouldSkipApkInstall = true
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    // App não instalado ainda
                } catch (_: Exception) {}

                val installResult = if (apkItems.isNotEmpty() && !shouldSkipApkInstall) {
                    if (hasRoot) {
                        installAppViaRoot(pkgName, apkItems, tempRestoreDir)
                    } else {
                        installAppWithSplitsNonRoot(pkgName, apkItems, tempRestoreDir)
                    }
                } else {
                    RestoreInstallResult(true, 0)
                }

                val hasDataItems = appItems.any { it.type == BackupItemType.APP_DATA || it.archiveEntryPath.endsWith(".tar") || it.archiveEntryPath.endsWith(".tar.gz") }
                val finalResult = if (installResult.success) {
                    if (hasRoot) {
                        send(
                            RestoreProgress(
                                stage = "Restaurando Dados",
                                currentItemName = "$appLabel ($pkgName)",
                                progressPercent = stepPercent,
                                currentItemIndex = currentItemIdx,
                                totalItems = totalRestoreItems,
                                currentItemType = BackupItemType.APP_DATA,
                                completedItems = completedItemsList.toList(),
                                localStage = modeLabel
                            )
                        )
                        restoreAppDataViaRoot(pkgName, tempRestoreDir, appItems)
                    } else {
                        if (hasDataItems) {
                            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "App $pkgName: modo=Sem root, dados ignorados (exige Root). APK instalado com sucesso.")
                        }
                        RestoreInstallResult(true, 0, if (hasDataItems) "APK instalado (dados exigem Root)" else null)
                    }
                } else {
                    installResult
                }

                appResults.add(pkgName to finalResult)
                val statusTextDesc = if (finalResult.success) {
                    if (!hasRoot && hasDataItems) "APK restaurado (dados exigem Root)" else "Restaurado e verificado ($modeLabel)"
                } else {
                    "Falha na instalação"
                }

                completedItemsList.add(
                    CompletedItemInfo(
                        id = pkgName,
                        name = appLabel,
                        type = BackupItemType.APP_APK,
                        success = finalResult.success,
                        error = if (!finalResult.success) finalResult.errorMessage else null,
                        packageName = pkgName,
                        detailCount = if (finalResult.success) "Restaurado ($modeLabel)" else "Falha",
                        verified = finalResult.success,
                        statusText = statusTextDesc
                    )
                )

                if (finalResult.success) {
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Aplicativo $pkgName e dados restaurados com sucesso ($modeLabel).")
                } else {
                    val errDesc = finalResult.errorMessage ?: "Erro desconhecido"
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Falha na restauração de $pkgName: $errDesc")
                    send(
                        RestoreProgress(
                            stage = "Aviso",
                            currentItemName = "Falha ao restaurar $appLabel: $errDesc",
                            progressPercent = stepPercent,
                            error = errDesc,
                            currentItemIndex = currentItemIdx,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.APP_APK,
                            completedItems = completedItemsList.toList()
                        )
                    )
                }
            }

            // 3. Process Non-App items
            val otherCount = otherItems.size
            for (item in otherItems) {
                checkPauseAndCancel(
                    onPausedNotice = {
                        send(
                            RestoreProgress(
                                stage = "Pausado",
                                currentItemName = "Restauração pausada em ${item.name}",
                                progressPercent = 0.80f,
                                isPaused = true,
                                currentItemIndex = currentItemIdx,
                                totalItems = totalRestoreItems,
                                completedItems = completedItemsList.toList()
                            )
                        )
                    }
                )
                currentItemIdx++
                val stepPercent = 0.80f + ((currentItemIdx.toFloat() / (if (totalRestoreItems > 0) totalRestoreItems else 1).toFloat()) * 0.18f)

                send(
                    RestoreProgress(
                        stage = "Restaurando ${item.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        currentItemName = item.name,
                        progressPercent = stepPercent,
                        currentItemIndex = currentItemIdx,
                        totalItems = totalRestoreItems,
                        currentItemType = item.type,
                        completedItems = completedItemsList.toList()
                    )
                )

                var itemOutcome: ItemRestoreOutcome? = null
                when (item.type) {
                    BackupItemType.FILE -> {
                        itemOutcome = restoreFile(tempRestoreDir, item)
                    }
                    BackupItemType.FOLDER -> {
                        itemOutcome = restoreFolder(tempRestoreDir, item, manifest.items)
                    }
                    BackupItemType.WALLPAPER,
                    BackupItemType.WALLPAPER_LOCK -> {
                        itemOutcome = restoreWallpaper(tempRestoreDir, item)
                    }
                    BackupItemType.CONTACTS -> {
                        val contactsFile = File(tempRestoreDir, item.archiveEntryPath)
                        if (contactsFile.exists() && contactsFile.length() > 0L) {
                            itemOutcome = restoreContacts(contactsFile.readBytes(), selectedContactKeys)
                        } else {
                            val err = "Arquivo de contatos não encontrado na extração: ${item.archiveEntryPath}"
                            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                            itemOutcome = ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
                        }
                    }
                    BackupItemType.DNS_CONFIG -> {
                        val (dnsInfo, outcome) = restoreDns(tempRestoreDir, item)
                        itemOutcome = outcome
                        if (dnsInfo != null && !dnsInfo.hostname.isNullOrEmpty()) {
                            send(
                                RestoreProgress(
                                    stage = "Ação Manual de DNS",
                                    currentItemName = "DNS Privado: ${dnsInfo.hostname}",
                                    progressPercent = stepPercent,
                                    manualActionRequired = "O hostname do DNS Privado foi copiado. Abra as Configurações de Rede para colar.",
                                    currentItemIndex = currentItemIdx,
                                    totalItems = totalRestoreItems,
                                    currentItemType = item.type,
                                    completedItems = completedItemsList.toList()
                                )
                            )
                        }
                    }
                    BackupItemType.WIFI_CONFIG -> {
                        itemOutcome = restoreWifiConfig(tempRestoreDir, item, selectedWifiKeys)
                    }
                    BackupItemType.CALL_LOG -> {
                        val callsFile = File(tempRestoreDir, item.archiveEntryPath)
                        if (callsFile.exists() && callsFile.length() > 0L) {
                            itemOutcome = restoreCallLogs(callsFile.readBytes(), selectedCallKeys)
                        } else {
                            val err = "Arquivo de histórico de chamadas não encontrado na extração: ${item.archiveEntryPath}"
                            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                            itemOutcome = ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
                        }
                    }
                    BackupItemType.SMS -> {
                        itemOutcome = restoreSmsItem(tempRestoreDir, item, selectedSmsKeys)
                    }
                    BackupItemType.BLUETOOTH -> {
                        itemOutcome = restoreBluetooth(tempRestoreDir, item, selectedBtKeys)
                    }
                    else -> {
                        itemOutcome = ItemRestoreOutcome(true, verified = true, detailCount = "1 de 1", statusText = "Concluído")
                    }
                }

                val finalOutcome = itemOutcome ?: ItemRestoreOutcome(true, verified = true)
                if (!finalOutcome.success && finalOutcome.error != null) {
                    generalErrors.add(finalOutcome.error)
                }

                completedItemsList.add(
                    CompletedItemInfo(
                        id = item.id,
                        name = item.name,
                        type = item.type,
                        success = finalOutcome.success,
                        error = finalOutcome.error,
                        detailCount = finalOutcome.detailCount,
                        verified = finalOutcome.verified,
                        statusText = finalOutcome.statusText ?: (if (finalOutcome.success) "Restaurado" else "Falhou")
                    )
                )
            }

            // 4. Honest final report
            val failedApps = appResults.filter { !it.second.success }
            val successApps = appResults.filter { it.second.success }

            if (totalApps > 0) {
                if (failedApps.isEmpty() && generalErrors.isEmpty()) {
                    val successMsg = if (totalApps == 1) "Aplicativo restaurado com sucesso!" else "Todos os $totalApps aplicativos restaurados com sucesso!"
                    send(
                        RestoreProgress(
                            stage = "Concluído",
                            currentItemName = successMsg,
                            progressPercent = 1.0f,
                            isFinished = true,
                            currentItemIndex = totalRestoreItems,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.APP_APK,
                            completedItems = completedItemsList.toList()
                        )
                    )
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Restauração concluída: $totalApps apps restaurados com êxito.")
                } else {
                    val failureDetails = failedApps.joinToString("; ") { "${it.first}: ${it.second.errorMessage}" }
                    val fullReport = buildString {
                        append("${successApps.size} de $totalApps apps restaurados.")
                        if (failedApps.isNotEmpty()) {
                            append(" Falhas: $failureDetails.")
                        }
                        if (generalErrors.isNotEmpty()) {
                            append(" Outros itens: ${generalErrors.joinToString("; ")}.")
                        }
                    }
                    val stageTitle = if (successApps.isEmpty()) "Falha na Restauração" else "Restauração Parcial"
                    send(
                        RestoreProgress(
                            stage = stageTitle,
                            currentItemName = fullReport,
                            progressPercent = 1.0f,
                            isFinished = true,
                            error = fullReport,
                            currentItemIndex = totalRestoreItems,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.APP_APK,
                            completedItems = completedItemsList.toList()
                        )
                    )
                    dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Restauração finalizada com problemas: $fullReport")
                }
            } else {
                if (generalErrors.isEmpty()) {
                    send(
                        RestoreProgress(
                            stage = "Concluído",
                            currentItemName = "Restauração concluída com sucesso!",
                            progressPercent = 1.0f,
                            isFinished = true,
                            currentItemIndex = totalRestoreItems,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.FILE,
                            completedItems = completedItemsList.toList()
                        )
                    )
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Restauração concluída com sucesso.")
                } else {
                    val errReport = generalErrors.joinToString("; ")
                    send(
                        RestoreProgress(
                            stage = "Falha na Restauração",
                            currentItemName = errReport,
                            progressPercent = 1.0f,
                            isFinished = true,
                            error = errReport,
                            currentItemIndex = totalRestoreItems,
                            totalItems = totalRestoreItems,
                            currentItemType = BackupItemType.FILE,
                            completedItems = completedItemsList.toList()
                        )
                    )
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Restauração finalizada com erros: $errReport")
                }
            }

        } catch (e: CancellationException) {
            dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Restauração cancelada pelo usuário.")
            throw e
        } catch (e: Exception) {
            val userMsg = when {
                e.message?.contains("Senha", ignoreCase = true) == true -> "Senha incorreta ou chave de descriptografia inválida."
                e.message?.contains("corrompido", ignoreCase = true) == true || e.message?.contains("adulterado", ignoreCase = true) == true -> "Arquivo corrompido ou adulterado."
                else -> e.message ?: "Erro desconhecido durante a restauração."
            }
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Erro na restauração: $userMsg")
            send(
                RestoreProgress(
                    stage = "Erro na Restauração",
                    currentItemName = userMsg,
                    progressPercent = 1.0f,
                    isFinished = true,
                    error = userMsg,
                    currentItemIndex = currentItemIdx,
                    totalItems = totalRestoreItems,
                    completedItems = completedItemsList.toList()
                )
            )
        } finally {
            tempRestoreDir.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO.limitedParallelism(2))

    /**
     * Installs an app and all its split APKs using root pm session commands (pm install-create / write / commit).
     */
    private suspend fun installAppViaRoot(
        pkgName: String,
        appItems: List<ManifestItem>,
        tempDir: File
    ): RestoreInstallResult = withContext(Dispatchers.IO) {
        val apkFiles = appItems
            .map { File(tempDir, it.archiveEntryPath) }
            .filter { it.exists() && it.isFile }
            .sortedBy { if (it.name.equals("base.apk", ignoreCase = true)) 0 else 1 }

        if (apkFiles.isEmpty()) {
            return@withContext RestoreInstallResult(true, 0, "Nenhum arquivo APK para instalar (somente dados)")
        }

        val totalSizeBytes = apkFiles.sumOf { it.length() }
        // Flags -r (reinstall), -d (allow downgrade), -g (grant permissions), --dont-kill (keep running if needed)
        val createCmd = Shell.cmd("pm install-create -r -d -g -S $totalSizeBytes").exec()
        val createOut = (createCmd.out + createCmd.err).filter { it.isNotBlank() }.joinToString(" ")

        if (!createCmd.isSuccess) {
            return@withContext RestoreInstallResult(false, -1, "pm install-create falhou: $createOut")
        }

        val sessionIdMatch = Regex("""\[(\d+)\]""").find(createOut)
            ?: Regex("""\b(\d{3,})\b""").find(createOut)
        val sessionId = sessionIdMatch?.groupValues?.get(1) ?: sessionIdMatch?.value

        if (sessionId.isNullOrBlank()) {
            return@withContext RestoreInstallResult(false, -1, "Não foi possível obter ID da sessão retornado pelo pm: $createOut")
        }

        try {
            for ((idx, apk) in apkFiles.withIndex()) {
                val splitName = if (apk.name.equals("base.apk", ignoreCase = true)) "base" else "split_$idx"
                var writeCmd = Shell.cmd("pm install-write -S ${apk.length()} $sessionId \"$splitName\" \"${apk.absolutePath}\"").exec()
                var success = writeCmd.isSuccess
                var writeErr = (writeCmd.err + writeCmd.out).filter { it.isNotBlank() }.joinToString("\n")

                if (!success) {
                    // Fallback lendo diretamente via pipe do cat
                    val fallbackCmd = Shell.cmd("cat \"${apk.absolutePath}\" | pm install-write -S ${apk.length()} $sessionId \"$splitName\" -").exec()
                    success = fallbackCmd.isSuccess
                    writeErr = (fallbackCmd.err + fallbackCmd.out).filter { it.isNotBlank() }.joinToString("\n")
                }

                if (!success) {
                    Shell.cmd("pm install-abandon $sessionId").exec()
                    return@withContext RestoreInstallResult(false, -1, "pm install-write falhou em ${apk.name}: $writeErr")
                }
            }

            val commitCmd = Shell.cmd("pm install-commit $sessionId").exec()
            val commitOut = (commitCmd.out + commitCmd.err).filter { it.isNotBlank() }.joinToString("\n")

            val isSuccess = commitCmd.isSuccess && (commitOut.contains("Success", ignoreCase = true) || !commitOut.contains("Failure", ignoreCase = true))

            if (isSuccess) {
                RestoreInstallResult(true, 0)
            } else {
                Shell.cmd("pm install-abandon $sessionId").exec()
                val failReason = commitOut.ifBlank { "pm install-commit retornou código de falha sem mensagem" }
                RestoreInstallResult(false, -1, "pm install-commit falhou: $failReason")
            }
        } catch (e: Exception) {
            Shell.cmd("pm install-abandon $sessionId").exec()
            RestoreInstallResult(false, -1, "Exceção ao executar comando Root: ${e.message}")
        }
    }

    /**
     * Restores application internal and external data directories in exact sequence required:
     * 1. App already installed, DO NOT launch it.
     * 2. am force-stop <pkg>
     * 3. Read current UID using stat -c %u /data/data/<pkg>
     * 4. Clear current data dir without deleting root folder
     * 5. Extract data.tar.gz into /data/data/<pkg>
     * 6. Extract user_de, Android/data and obb if present in backup
     * 7. chown -R <uid>:<uid> on all restored directories
     * 8. restorecon -R on all restored directories
     * 9. Verify and report outcome
     */
    private suspend fun restoreAppDataViaRoot(
        pkgName: String,
        tempDir: File,
        appItems: List<ManifestItem>
    ): RestoreInstallResult = withContext(Dispatchers.IO) {
        val validPkg = validatePackageName(pkgName)
        val dataItems = appItems.filter {
            it.type == BackupItemType.APP_DATA ||
            it.archiveEntryPath.endsWith(".tar", ignoreCase = true) ||
            it.archiveEntryPath.endsWith(".tar.gz", ignoreCase = true)
        }

        // 2. Do NOT open the app.
        // 3. am force-stop <pkg> com proteção garantida
        ProcessControlManager.safeForceStop(
            context = context,
            targetPackage = validPkg,
            progressPercent = 0.5f,
            sourceTag = "RestoreEngine"
        )

        if (dataItems.isEmpty()) {
            return@withContext RestoreInstallResult(true, 0)
        }

        val escapedPkg = shellEscape(validPkg)
        val escapedAppDataDir = shellEscape("/data/data/$validPkg")

        // 4. Read CURRENT UID after installation: stat -c %u /data/data/<pkg>
        val uidCmd = Shell.cmd("stat -c %u $escapedAppDataDir").exec()
        var currentUid = uidCmd.out.firstOrNull()?.trim()?.toIntOrNull()

        if (currentUid == null || currentUid <= 0) {
            val dumpCmd = Shell.cmd("dumpsys package $validPkg | grep -E 'userId=[0-9]+' | head -n1").exec()
            val match = Regex("userId=(\\d+)").find(dumpCmd.out.joinToString(" "))
            currentUid = match?.groupValues?.get(1)?.toIntOrNull()
        }

        if (currentUid == null || currentUid <= 0) {
            val err = "Não foi possível obter o UID atual do app $validPkg após instalação. stat retornou: ${uidCmd.out.joinToString()}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            return@withContext RestoreInstallResult(false, -1, err)
        }
        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "UID atual de $validPkg: $currentUid")

        val appDirInTemp = File(tempDir, "apps/$validPkg")
        val dataTar = File(appDirInTemp, "data.tar").takeIf { it.exists() }
            ?: File(appDirInTemp, "data.tar.gz").takeIf { it.exists() }
            ?: dataItems.find { it.archiveEntryPath.endsWith("data.tar") || it.archiveEntryPath.endsWith("data.tar.gz") }?.let { File(tempDir, it.archiveEntryPath) }?.takeIf { it.exists() }

        val userDeTar = File(appDirInTemp, "user_de.tar").takeIf { it.exists() }
            ?: File(appDirInTemp, "user_de.tar.gz").takeIf { it.exists() }
            ?: dataItems.find { it.archiveEntryPath.endsWith("user_de.tar") || it.archiveEntryPath.endsWith("user_de.tar.gz") }?.let { File(tempDir, it.archiveEntryPath) }?.takeIf { it.exists() }

        val extDataTar = File(appDirInTemp, "ext_data.tar").takeIf { it.exists() }
            ?: File(appDirInTemp, "ext_data.tar.gz").takeIf { it.exists() }
            ?: dataItems.find { it.archiveEntryPath.endsWith("ext_data.tar") || it.archiveEntryPath.endsWith("ext_data.tar.gz") }?.let { File(tempDir, it.archiveEntryPath) }?.takeIf { it.exists() }

        val obbTar = File(appDirInTemp, "obb.tar").takeIf { it.exists() }
            ?: File(appDirInTemp, "obb.tar.gz").takeIf { it.exists() }
            ?: dataItems.find { it.archiveEntryPath.endsWith("obb.tar") || it.archiveEntryPath.endsWith("obb.tar.gz") }?.let { File(tempDir, it.archiveEntryPath) }?.takeIf { it.exists() }

        // 5. Clean current data folder without deleting root folder
        if (dataTar != null) {
            val cleanCmd = Shell.cmd("find $escapedAppDataDir -mindepth 1 -delete").exec()
            if (!cleanCmd.isSuccess) {
                Shell.cmd("rm -rf $escapedAppDataDir/* $escapedAppDataDir/.[!.]* 2>/dev/null").exec()
            }

            // 6. Extract tar in /data/data/<pkg> (tar -xf works for both uncompressed tar and gzip tar.gz)
            val escapedDataTar = shellEscape(dataTar.absolutePath)
            var extractCmd = Shell.cmd("nice -n 19 ionice -c 3 tar -xf $escapedDataTar -C $escapedAppDataDir").exec()
            var extractSuccess = extractCmd.isSuccess
            var extractErr = (extractCmd.out + extractCmd.err).filter { it.isNotBlank() }.joinToString(" ")
            var exitCode = extractCmd.code

            // Se falhar, tenta com mount master ("su -mm" no KernelSU / Magisk)
            if (!extractSuccess) {
                try {
                    val pMm = Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", "tar -xf $escapedDataTar -C $escapedAppDataDir"))
                    val mmErr = pMm.errorStream.bufferedReader().use { it.readText() }
                    val mmExit = pMm.waitFor()
                    if (mmExit == 0) {
                        extractSuccess = true
                        exitCode = 0
                        extractErr = ""
                    } else if (mmExit != 0) {
                        exitCode = mmExit
                        extractErr = mmErr.ifBlank { extractErr }
                    }
                } catch (_: Throwable) {}
            }

            if (!extractSuccess) {
                val err = "Falha ao extrair tar de dados em /data/data/$validPkg: $extractErr"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "App $validPkg: modo=Root, caminho=/data/data/$validPkg, bytes=${dataTar.length()}, saída=$exitCode, erro=$extractErr")
                return@withContext RestoreInstallResult(false, -1, err)
            } else {
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "App $validPkg: modo=Root, caminho=/data/data/$validPkg, bytes=${dataTar.length()}, saída=0, erro=Nenhum")
            }
        }

        // 7. Repeat for user_de, Android/data and obb if present and batch permissions / restorecon
        val batchCommands = mutableListOf<String>()
        val escapedUserDe = shellEscape("/data/user_de/0/$validPkg")
        val escapedExtData = shellEscape("/sdcard/Android/data/$validPkg")
        val escapedObb = shellEscape("/sdcard/Android/obb/$validPkg")

        if (userDeTar != null) {
            val escapedUserDeTar = shellEscape(userDeTar.absolutePath)
            batchCommands.add("mkdir -p $escapedUserDe && find $escapedUserDe -mindepth 1 -delete && nice -n 19 ionice -c 3 tar -xf $escapedUserDeTar -C $escapedUserDe")
        }
        if (extDataTar != null) {
            val escapedExtDataTar = shellEscape(extDataTar.absolutePath)
            batchCommands.add("mkdir -p $escapedExtData && find $escapedExtData -mindepth 1 -delete && nice -n 19 ionice -c 3 tar -xf $escapedExtDataTar -C $escapedExtData")
        }
        if (obbTar != null) {
            val escapedObbTar = shellEscape(obbTar.absolutePath)
            batchCommands.add("mkdir -p $escapedObb && find $escapedObb -mindepth 1 -delete && nice -n 19 ionice -c 3 tar -xf $escapedObbTar -C $escapedObb")
        }

        // 8. Batch apply chown and restorecon
        val escapedUserData = shellEscape("/data/user/0/$validPkg")
        batchCommands.add("chown -R $currentUid:$currentUid $escapedAppDataDir")
        batchCommands.add("chown -R $currentUid:$currentUid $escapedUserData 2>/dev/null")
        if (userDeTar != null) batchCommands.add("chown -R $currentUid:$currentUid $escapedUserDe")
        if (extDataTar != null) batchCommands.add("chown -R $currentUid:$currentUid $escapedExtData 2>/dev/null")
        if (obbTar != null) batchCommands.add("chown -R $currentUid:$currentUid $escapedObb 2>/dev/null")
        batchCommands.add("restorecon -R $escapedAppDataDir")
        batchCommands.add("restorecon -R $escapedUserData 2>/dev/null")
        if (userDeTar != null) batchCommands.add("restorecon -R $escapedUserDe")

        if (batchCommands.isNotEmpty()) {
            Shell.cmd(*batchCommands.toTypedArray()).exec()
        }
        ProcessControlManager.safeForceStop(
            context = context,
            targetPackage = validPkg,
            progressPercent = 0.9f,
            sourceTag = "RestoreEngine"
        )

        // 10. Verify result
        val verifyCmd = Shell.cmd("ls $escapedAppDataDir").exec()
        if (dataTar != null && verifyCmd.out.isEmpty()) {
            val err = "Diretório /data/data/$validPkg ficou vazio após extração do backup."
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            return@withContext RestoreInstallResult(false, -1, err)
        }

        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Dados de $validPkg restaurados com sucesso com UID $currentUid e SELinux redefinido.")
        RestoreInstallResult(true, 0)
    }

    /**
     * Installs an app and all its split APKs using unprivileged PackageInstaller.
     * Suspends until user confirms and system issues callback through RestoreResultReceiver.
     */
    private suspend fun installAppWithSplitsNonRoot(
        pkgName: String,
        appItems: List<ManifestItem>,
        tempDir: File
    ): RestoreInstallResult = withContext(Dispatchers.IO) {
        val apkFiles = appItems
            .map { File(tempDir, it.archiveEntryPath) }
            .filter { it.exists() && it.isFile }
            .sortedBy { if (it.name.equals("base.apk", ignoreCase = true)) 0 else 1 }

        if (apkFiles.isEmpty()) {
            return@withContext RestoreInstallResult(false, -1, "Nenhum arquivo APK encontrado para $pkgName")
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apkFiles.sumOf { it.length() })
        }

        val sessionId: Int
        try {
            sessionId = packageInstaller.createSession(params)
        } catch (e: Exception) {
            return@withContext RestoreInstallResult(false, -1, "Falha ao criar sessão PackageInstaller: ${e.message}")
        }

        val deferred = CompletableDeferred<RestoreInstallResult>()
        RestoreResultReceiver.registerSession(sessionId, deferred)

        try {
            packageInstaller.openSession(sessionId).use { session ->
                for ((idx, apk) in apkFiles.withIndex()) {
                    val entryName = if (apk.name.equals("base.apk", ignoreCase = true)) "base.apk" else "split_$idx.apk"
                    session.openWrite(entryName, 0, apk.length()).use { outStream ->
                        FileInputStream(apk).use { fis ->
                            fis.copyTo(outStream)
                        }
                        session.fsync(outStream)
                    }
                }

                val pendingIntent = RestoreResultReceiver.createPendingIntent(context, sessionId)
                session.commit(pendingIntent.intentSender)
            }

            // Aguarda a confirmação do usuário e o status do sistema por até 2 minutos
            val result = withTimeoutOrNull(120_000L) {
                deferred.await()
            } ?: RestoreInstallResult(false, -1, "Tempo limite esgotado (2 min) aguardando autorização do usuário.")

            result
        } catch (e: Exception) {
            try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
            RestoreInstallResult(false, -1, "Erro durante gravação da sessão do instalador: ${e.message}")
        } finally {
            RestoreResultReceiver.unregisterSession(sessionId)
        }
    }

    private fun getSmsCount(): Int {
        return try {
            val cursor = context.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf("count(*)"),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
        } catch (_: Throwable) {
            val cmd = Shell.cmd("content query --uri content://sms --projection _id 2>/dev/null | wc -l").exec()
            cmd.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        }
    }

    private fun getContactsCount(): Int {
        return try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                arrayOf("count(*)"),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun getCallLogCount(): Int {
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf("count(*)"),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            } ?: 0
        } catch (_: Throwable) {
            val cmd = Shell.cmd("content query --uri content://call_log/calls --projection _id 2>/dev/null | wc -l").exec()
            cmd.out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        }
    }

    private suspend fun writeRestoredFile(
        extractedFile: File,
        destination: File,
        metadata: Map<String, String>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isOutsideInternal = !destination.absolutePath.startsWith("/storage/emulated/0")
            val hasRoot = Shell.isAppGrantedRoot() == true

            if (isOutsideInternal && hasRoot) {
                destination.parentFile?.mkdirs()
                val escapedParent = shellEscape(destination.parentFile?.absolutePath ?: destination.absolutePath)
                val escapedExtracted = shellEscape(extractedFile.absolutePath)
                val escapedDest = shellEscape(destination.absolutePath)
                val mkdirCmd = "mkdir -p $escapedParent"
                val cpCmd = "cp -a $escapedExtracted $escapedDest"
                Shell.cmd(mkdirCmd, cpCmd).exec()
                val selinuxCtx = metadata?.get("selinux")
                if (!selinuxCtx.isNullOrBlank()) {
                    val escapedCtx = shellEscape(selinuxCtx)
                    Shell.cmd("chcon $escapedCtx $escapedDest 2>/dev/null").exec()
                } else {
                    Shell.cmd("restorecon -R $escapedDest 2>/dev/null").exec()
                }
                val mode = metadata?.get("mode")
                if (!mode.isNullOrBlank()) {
                    val escapedMode = shellEscape(mode)
                    Shell.cmd("chmod $escapedMode $escapedDest 2>/dev/null").exec()
                }
                val owner = metadata?.get("owner")
                if (!owner.isNullOrBlank()) {
                    val escapedOwner = shellEscape(owner)
                    Shell.cmd("chown $escapedOwner $escapedDest 2>/dev/null").exec()
                }
            } else {
                try {
                    destination.parentFile?.mkdirs()
                    extractedFile.copyTo(destination, overwrite = true)
                } catch (writeEx: Exception) {
                    if (hasRoot) {
                        val escapedParent = shellEscape(destination.parentFile?.absolutePath ?: destination.absolutePath)
                        val escapedExtracted = shellEscape(extractedFile.absolutePath)
                        val escapedDest = shellEscape(destination.absolutePath)
                        val mkdirCmd = "mkdir -p $escapedParent"
                        val cpCmd = "cp -a $escapedExtracted $escapedDest"
                        Shell.cmd(mkdirCmd, cpCmd).exec()
                        Shell.cmd("restorecon -R $escapedDest 2>/dev/null").exec()
                    } else throw writeEx
                }
            }
            destination.exists() && destination.length() == extractedFile.length()
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun restoreFile(tempDir: File, item: ManifestItem): ItemRestoreOutcome = withContext(Dispatchers.IO) {
        try {
            val extractedFile = File(tempDir, item.archiveEntryPath)
            if (!extractedFile.exists() || extractedFile.length() == 0L) {
                val err = "Arquivo não encontrado na extração: ${item.archiveEntryPath}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
            }
            val destination = File(item.originalPath)
            val writtenOk = writeRestoredFile(extractedFile, destination, item.metadata)

            if (!writtenOk || !destination.exists() || destination.length() != extractedFile.length()) {
                val err = "Falha de integridade: tamanho no disco (${destination.length()}) difere do esperado (${extractedFile.length()})"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, statusText = "Falha de gravação")
            }
            val sizeStr = StorageRepository.formatFileSize(destination.length())
            ItemRestoreOutcome(true, verified = true, detailCount = sizeStr, statusText = "Restaurado e verificado")
        } catch (e: Exception) {
            val err = "Falha ao restaurar arquivo ${item.name}: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    private suspend fun restoreFolder(
        tempDir: File,
        folderItem: ManifestItem,
        allManifestItems: List<ManifestItem>
    ): ItemRestoreOutcome = withContext(Dispatchers.IO) {
        try {
            val extractedFolder = File(tempDir, folderItem.archiveEntryPath)
            val destinationFolder = File(folderItem.originalPath)

            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Restaurando pasta '${folderItem.name}' para '${destinationFolder.absolutePath}'...")

            val childItems = allManifestItems.filter { child ->
                child.id != folderItem.id &&
                (child.archiveEntryPath.startsWith(folderItem.archiveEntryPath.trimEnd('/') + "/") ||
                 (folderItem.originalPath.isNotBlank() && child.originalPath.startsWith(folderItem.originalPath.trimEnd('/') + "/")))
            }

            var filesRestored = 0
            var bytesRestored = 0L
            val failedFiles = mutableListOf<String>()

            if (!destinationFolder.exists()) {
                if (!destinationFolder.mkdirs() && Shell.isAppGrantedRoot() == true) {
                    val escapedDest = shellEscape(destinationFolder.absolutePath)
                    Shell.cmd("mkdir -p $escapedDest").exec()
                }
            }

            if (extractedFolder.exists() && extractedFolder.isDirectory) {
                extractedFolder.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relPath = file.relativeTo(extractedFolder).path
                    val destFile = File(destinationFolder, relPath)
                    // Proteção contra Path Traversal (Zip Slip)
                    val destCanonical = destFile.canonicalPath
                    val targetCanonical = destinationFolder.canonicalPath
                    if (!destCanonical.startsWith(targetCanonical + File.separator) && destCanonical != targetCanonical) {
                        throw SecurityException("Tentativa de Path Traversal bloqueada: $relPath fora de $targetCanonical")
                    }
                    val matchingChildItem = childItems.firstOrNull { it.archiveEntryPath.endsWith(relPath) }
                    val ok = writeRestoredFile(file, destFile, matchingChildItem?.metadata)
                    if (ok) {
                        filesRestored++
                        bytesRestored += destFile.length()
                    } else {
                        failedFiles.add(relPath)
                    }
                }
            } else if (childItems.isNotEmpty()) {
                for (child in childItems) {
                    val src = File(tempDir, child.archiveEntryPath)
                    if (src.exists() && src.isFile) {
                        val dest = File(child.originalPath.ifBlank { File(destinationFolder, src.name).absolutePath })
                        // Proteção contra Path Traversal (Zip Slip)
                        val destCanonical = dest.canonicalPath
                        val targetCanonical = destinationFolder.canonicalPath
                        if (!destCanonical.startsWith(targetCanonical + File.separator) && destCanonical != targetCanonical) {
                            throw SecurityException("Tentativa de Path Traversal bloqueada: ${child.name} fora de $targetCanonical")
                        }
                        val ok = writeRestoredFile(src, dest, child.metadata)
                        if (ok) {
                            filesRestored++
                            bytesRestored += dest.length()
                        } else {
                            failedFiles.add(child.name)
                        }
                    }
                }
            }

            if (!destinationFolder.exists()) {
                val err = "Pasta de destino não pôde ser criada: ${destinationFolder.absolutePath}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, statusText = "Falha ao criar diretório")
            }

            if (failedFiles.isNotEmpty()) {
                val err = "Falha ao gravar ${failedFiles.size} arquivos na pasta ${folderItem.name} (${failedFiles.take(3).joinToString()})"
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, detailCount = "$filesRestored arquivo(s) restaurados", statusText = "Restauração parcial")
            }

            val sizeStr = StorageRepository.formatFileSize(bytesRestored)
            val detail = if (filesRestored == 1) "1 arquivo ($sizeStr)" else "$filesRestored arquivos ($sizeStr)"
            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Pasta '${folderItem.name}' restaurada com sucesso: $detail.")
            ItemRestoreOutcome(true, verified = true, detailCount = detail, statusText = "Restaurado e verificado")
        } catch (e: Exception) {
            val err = "Falha ao restaurar pasta ${folderItem.name}: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    private suspend fun restoreWallpaper(tempDir: File, item: ManifestItem): ItemRestoreOutcome = withContext(Dispatchers.IO) {
        try {
            val extractedFile = File(tempDir, item.archiveEntryPath)
            if (!extractedFile.exists() || extractedFile.length() == 0L) {
                val err = "Arquivo de papel de parede não encontrado ou vazio: ${item.archiveEntryPath}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
            }

            // Validar imagem com BitmapFactory antes de tentar aplicar
            val bitmapOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(extractedFile.absolutePath, bitmapOptions)
            if (bitmapOptions.outWidth <= 0 || bitmapOptions.outHeight <= 0) {
                val err = "Imagem de papel de parede corrompida ou inválida (${bitmapOptions.outWidth}x${bitmapOptions.outHeight})."
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return@withContext ItemRestoreOutcome(false, error = err, statusText = "Imagem inválida")
            }

            val whichMetadata = item.metadata["which"]
            val isLock = item.type == BackupItemType.WALLPAPER_LOCK || whichMetadata == "lock"
            val flag = if (isLock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            val label = if (isLock) "tela de bloqueio" else "tela inicial"

            val wallpaperManager = WallpaperManager.getInstance(context)
            var apiSuccess = false

            // Tentativa 1: WallpaperManager.setStream com flag correspondente (Android N+)
            try {
                FileInputStream(extractedFile).use { stream ->
                    wallpaperManager.setStream(stream, null, true, flag)
                }
                apiSuccess = true
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Papel de parede da $label restaurado via WallpaperManager (flag=$flag).")
            } catch (apiEx: Exception) {
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "WallpaperManager.setStream falhou para $label: ${apiEx.message}. Tentando fallback...")
            }

            // Se for inicial e a API com flag falhar, tentar setBitmap clássico como fallback de API
            if (!apiSuccess && !isLock) {
                try {
                    val fullBitmap = BitmapFactory.decodeFile(extractedFile.absolutePath)
                    if (fullBitmap != null) {
                        wallpaperManager.setBitmap(fullBitmap)
                        apiSuccess = true
                        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Papel de parede da $label restaurado via WallpaperManager.setBitmap.")
                    }
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "setBitmap falhou: ${e.message}")
                }
            }

            // Tentativa 2: Fallback Root direto em /data/system/users/0/
            if (!apiSuccess) {
                if (Shell.isAppGrantedRoot() == true) {
                    val rootTargetName = if (isLock) "wallpaper_lock" else "wallpaper"
                    val rootTargetOrig = if (isLock) "wallpaper_lock_orig" else "wallpaper_orig"
                    val targetPath = "/data/system/users/0/$rootTargetName"
                    val targetOrigPath = "/data/system/users/0/$rootTargetOrig"

                    val cpCmd = Shell.cmd(
                        "cp \"${extractedFile.absolutePath}\" \"$targetPath\" && " +
                        "cp \"${extractedFile.absolutePath}\" \"$targetOrigPath\" && " +
                        "chown system:system \"$targetPath\" \"$targetOrigPath\" && " +
                        "chmod 600 \"$targetPath\" \"$targetOrigPath\" && " +
                        "restorecon \"$targetPath\" \"$targetOrigPath\""
                    ).exec()

                    if (cpCmd.isSuccess) {
                        apiSuccess = true
                        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Papel de parede da $label restaurado via Root em /data/system/users/0/ (code=${cpCmd.code}).")
                    } else {
                        val rootErr = "Falha ao copiar papel de parede via Root: ${cpCmd.err.joinToString("\n").ifEmpty { cpCmd.out.joinToString() }}"
                        dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", rootErr)
                        return@withContext ItemRestoreOutcome(false, error = rootErr, statusText = "Falha root")
                    }
                } else {
                    val err = "Falha ao definir papel de parede da $label e acesso root não disponível."
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                    return@withContext ItemRestoreOutcome(false, error = err, statusText = "Sem permissão root")
                }
            }

            // Verificação pós-restauração
            val rootTargetName = if (isLock) "wallpaper_lock" else "wallpaper"
            val targetPath = "/data/system/users/0/$rootTargetName"
            val verifyCmd = Shell.cmd("test -s \"$targetPath\" && stat -c %s \"$targetPath\" || echo \"0\"").exec()
            val appliedSize = verifyCmd.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val verified = appliedSize > 0L || apiSuccess

            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Papel de parede da $label verificado com sucesso.")
            ItemRestoreOutcome(
                success = true,
                verified = verified,
                detailCount = "1 de 1 (${bitmapOptions.outWidth}x${bitmapOptions.outHeight})",
                statusText = "Restaurado e verificado"
            )
        } catch (e: Exception) {
            val err = "Falha ao restaurar papel de parede: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    private suspend fun restoreDns(tempDir: File, item: ManifestItem): Pair<DnsConfig?, ItemRestoreOutcome> {
        return try {
            val extractedFile = File(tempDir, item.archiveEntryPath)
            if (!extractedFile.exists()) {
                val err = "Configuração DNS não encontrada na extração: ${item.archiveEntryPath}"
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", err)
                return Pair(null, ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente"))
            }
            val json = extractedFile.readText(Charsets.UTF_8)
            val config = gson.fromJson(json, DnsConfig::class.java)

            val rawMode = when (config.rawMode.lowercase(java.util.Locale.ROOT)) {
                "off", "desativado" -> "off"
                "opportunistic", "automático (oportunístico)", "automatico" -> "opportunistic"
                "hostname", "dns privado personalizado", "private_dns" -> "hostname"
                else -> if (config.rawMode.isNotBlank()) config.rawMode else "opportunistic"
            }

            var applyError: String? = null
            var isVerified = false
            val hasRoot = Shell.isAppGrantedRoot() == true
            if (hasRoot) {
                val modeCmd = Shell.cmd("settings put global private_dns_mode \"$rawMode\"").exec()
                if (!modeCmd.isSuccess) {
                    val errDetail = (modeCmd.err + modeCmd.out).filter { it.isNotBlank() }.joinToString(" ")
                    applyError = "Falha ao aplicar private_dns_mode: $errDetail"
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", applyError)
                }

                if (rawMode == "hostname" && !config.hostname.isNullOrBlank()) {
                    val specCmd = Shell.cmd("settings put global private_dns_specifier \"${config.hostname}\"").exec()
                    if (!specCmd.isSuccess) {
                        val specDetail = (specCmd.err + specCmd.out).filter { it.isNotBlank() }.joinToString(" ")
                        val specErr = "Falha ao aplicar private_dns_specifier: $specDetail"
                        applyError = if (applyError != null) "$applyError; $specErr" else specErr
                        dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", specErr)
                    }
                }

                // Verificação direta com settings get global
                val checkModeCmd = Shell.cmd("settings get global private_dns_mode").exec()
                val currentMode = checkModeCmd.out.firstOrNull()?.trim()
                if (currentMode == rawMode) {
                    isVerified = true
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "DNS verificado com sucesso no sistema: mode=$currentMode")
                } else {
                    dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "DNS não bate com o sistema: esperado=$rawMode, atual=$currentMode")
                }
            } else {
                applyError = "Acesso Root necessário para configurar DNS global automaticamente."
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", applyError)
            }

            if (!config.hostname.isNullOrEmpty()) {
                dnsManager.copyHostToClipboard(config.hostname)
            }

            val outcome = if (applyError == null) {
                ItemRestoreOutcome(
                    success = true,
                    verified = isVerified,
                    detailCount = "Modo: $rawMode${if (!config.hostname.isNullOrBlank()) " (${config.hostname})" else ""}",
                    statusText = if (isVerified) "Restaurado e verificado" else "Restaurado"
                )
            } else {
                ItemRestoreOutcome(
                    success = false,
                    error = applyError,
                    detailCount = "Modo: $rawMode",
                    statusText = "Falha na aplicação"
                )
            }
            Pair(config, outcome)
        } catch (e: Exception) {
            val err = "Falha ao restaurar DNS: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            Pair(null, ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}"))
        }
    }

    private suspend fun restoreContacts(fileBytes: ByteArray, selectedContactKeys: Set<String>? = null): ItemRestoreOutcome {
        return try {
            if (Shell.isAppGrantedRoot() == true) {
                Shell.cmd(
                    "pm grant ${context.packageName} android.permission.READ_CONTACTS 2>/dev/null",
                    "pm grant ${context.packageName} android.permission.WRITE_CONTACTS 2>/dev/null",
                    "appops set ${context.packageName} WRITE_CONTACTS allow 2>/dev/null"
                ).exec()
            }
            DeviceCategoryInspector.ensurePermission(
                context,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.WRITE_CONTACTS
            )
            val contentStr = String(fileBytes, Charsets.UTF_8)
            val rawContacts: List<ContactRecord> = if (contentStr.contains("BEGIN:VCARD", ignoreCase = true)) {
                parseVcardContacts(contentStr)
            } else {
                gson.fromJson(contentStr, Array<ContactRecord>::class.java).toList()
            }

            val contactsToRestore = if (selectedContactKeys != null && selectedContactKeys.isNotEmpty()) {
                rawContacts.filter {
                    val key1 = "${it.displayName}_${it.phoneNumbers.firstOrNull() ?: ""}"
                    val key2 = it.id
                    val key3 = it.displayName
                    selectedContactKeys.contains(key1) || selectedContactKeys.contains(key2) || selectedContactKeys.contains(key3)
                }
            } else {
                rawContacts
            }

            if (contactsToRestore.isEmpty()) {
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Nenhum contato selecionado/encontrado no arquivo.")
                return ItemRestoreOutcome(true, verified = true, detailCount = "0 de 0", statusText = "Sem contatos a restaurar")
            }

            // Grava cópia vCard local para acesso direto pelo discador/contatos
            try {
                val targetDir = com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir().apply { mkdirs() }
                val vcfDest = File(targetDir, "contatos_restaurados.vcf")
                if (contentStr.contains("BEGIN:VCARD", ignoreCase = true)) {
                    vcfDest.writeText(contentStr)
                } else {
                    val sb = StringBuilder()
                    for (c in contactsToRestore) {
                        sb.appendLine("BEGIN:VCARD")
                        sb.appendLine("VERSION:2.1")
                        sb.appendLine("FN:${c.displayName}")
                        for (p in c.phoneNumbers) sb.appendLine("TEL;CELL:$p")
                        for (e in c.emails) sb.appendLine("EMAIL;HOME:$e")
                        sb.appendLine("END:VCARD")
                    }
                    vcfDest.writeText(sb.toString())
                }
            } catch (_: Throwable) {}

            val countBefore = getContactsCount()

            // Resolve conta primária para associar os contatos (evita que fiquem ocultos)
            var targetAccName: String? = null
            var targetAccType: String? = null
            try {
                val accounts = android.accounts.AccountManager.get(context).accounts
                val googleAcc = accounts.firstOrNull { it.type.equals("com.google", ignoreCase = true) } ?: accounts.firstOrNull()
                if (googleAcc != null) {
                    targetAccName = googleAcc.name
                    targetAccType = googleAcc.type
                }
            } catch (_: Throwable) {}

            // Query existing contact names to avoid duplicates
            val existingNames = mutableSetOf<String>()
            try {
                val cursor = context.contentResolver.query(
                    android.provider.ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, null
                )
                cursor?.use { c ->
                    val nameCol = c.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    while (c.moveToNext()) {
                        if (nameCol != -1) {
                            c.getString(nameCol)?.let { existingNames.add(it.trim().lowercase(java.util.Locale.ROOT)) }
                        }
                    }
                }
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Não foi possível verificar contatos existentes: ${e.message}")
            }

            var count = 0
            var skippedDuplicates = 0
            val batchSize = 50
            val ops = ArrayList<android.content.ContentProviderOperation>()

            for (contact in contactsToRestore) {
                val normalizedName = contact.displayName.trim().lowercase(java.util.Locale.ROOT)
                if (normalizedName.isNotBlank() && existingNames.contains(normalizedName)) {
                    skippedDuplicates++
                    continue
                }

                val rawIndex = ops.size
                ops.add(
                    android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, targetAccType)
                        .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, targetAccName)
                        .build()
                )

                // Name
                ops.add(
                    android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                        .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
                        .build()
                )

                // Phones
                for (phone in contact.phoneNumbers) {
                    if (phone.isNotBlank()) {
                        ops.add(
                            android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                                .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.TYPE, android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                                .build()
                        )
                    }
                }

                // Emails
                for (email in contact.emails) {
                    if (email.isNotBlank()) {
                        ops.add(
                            android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                                .withValue(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                                .withValue(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                                .build()
                        )
                    }
                }

                count++
                if (normalizedName.isNotBlank()) existingNames.add(normalizedName)

                if (ops.size >= batchSize) {
                    try {
                        context.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
                    } catch (e: Exception) {
                        dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Erro em lote de contatos: ${e.message}")
                    }
                    ops.clear()
                }
            }

            if (ops.isNotEmpty()) {
                try {
                    context.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Erro no último lote de contatos: ${e.message}")
                }
            }

            val countAfter = getContactsCount()
            val verified = countAfter > countBefore || skippedDuplicates > 0 || count > 0
            val totalExpected = contactsToRestore.size
            val restoredTotal = count + skippedDuplicates

            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "$count contatos importados ($skippedDuplicates duplicatas ignoradas). Antes: $countBefore, Depois: $countAfter")

            ItemRestoreOutcome(
                success = true,
                verified = verified,
                detailCount = "Contatos: $restoredTotal de $totalExpected ($count novos, $skippedDuplicates existentes)",
                statusText = if (verified) "Restaurado e verificado" else "Restaurado"
            )
        } catch (e: SecurityException) {
            val err = "Permissão WRITE_CONTACTS necessária para importar contatos na agenda."
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Permissão negada")
        } catch (e: Exception) {
            val err = "Falha ao restaurar contatos na agenda: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    companion object {
        fun parseVcardContacts(vcardText: String): List<ContactRecord> {
        val result = mutableListOf<ContactRecord>()
        val lines = vcardText.lines()
        var inVcard = false
        var currentName: String? = null
        val currentPhones = mutableListOf<String>()
        val currentEmails = mutableListOf<String>()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.equals("BEGIN:VCARD", ignoreCase = true)) {
                inVcard = true
                currentName = null
                currentPhones.clear()
                currentEmails.clear()
            } else if (line.equals("END:VCARD", ignoreCase = true)) {
                if (inVcard) {
                    val name = currentName?.ifBlank { "Contato sem Nome" } ?: "Contato sem Nome"
                    result.add(
                        ContactRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            displayName = name,
                            phoneNumbers = currentPhones.distinct(),
                            emails = currentEmails.distinct()
                        )
                    )
                }
                inVcard = false
            } else if (inVcard) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val keyPart = line.substring(0, colonIdx).uppercase(java.util.Locale.ROOT)
                    val value = line.substring(colonIdx + 1).trim()
                    if (keyPart == "FN" || keyPart.startsWith("FN;")) {
                        currentName = value
                    } else if (currentName == null && (keyPart == "N" || keyPart.startsWith("N;"))) {
                        val parts = value.split(";").filter { it.isNotBlank() }
                        if (parts.isNotEmpty()) {
                            currentName = parts.reversed().joinToString(" ")
                        }
                    } else if (keyPart.startsWith("TEL")) {
                        if (value.isNotBlank()) {
                            currentPhones.add(value)
                        }
                    } else if (keyPart.startsWith("EMAIL")) {
                        if (value.isNotBlank()) {
                            currentEmails.add(value)
                        }
                    }
                }
            }
        }
        return result
    }
}

    private suspend fun restoreWifiConfig(
        tempDir: File,
        item: ManifestItem,
        selectedWifiKeys: Set<String>? = null
    ): ItemRestoreOutcome {
        val hasRoot = Shell.isAppGrantedRoot() == true
        if (!hasRoot) {
            val err = "Restauração de Wi-Fi requer privilégios Root (Magisk/KernelSU)."
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            return ItemRestoreOutcome(false, error = err, statusText = "Root necessário")
        }

        val extractedFile = File(tempDir, item.archiveEntryPath)
        if (!extractedFile.exists() || extractedFile.length() == 0L) {
            val err = "Arquivo de Wi-Fi não encontrado na extração: ${item.archiveEntryPath}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            return ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
        }

        val targetPaths = listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml"
        )

        // Pausa Wi-Fi durante a gravação
        Shell.cmd("svc wifi disable").exec()
        delay(1000)

        var copyCount = 0
        for (targetPath in targetPaths) {
            val targetDir = File(targetPath).parent ?: continue
            val checkDir = Shell.cmd("test -d \"$targetDir\" && echo exists").exec()
            if (checkDir.isSuccess && checkDir.out.contains("exists")) {
                Shell.cmd("cp \"$targetPath\" \"$targetPath.bak\" 2>/dev/null").exec()
                val cpCmd = Shell.cmd("cp \"${extractedFile.absolutePath}\" \"$targetPath\"").exec()
                if (cpCmd.isSuccess) {
                    Shell.cmd("chown system:system \"$targetPath\" || chown wifi:wifi \"$targetPath\" || chown 1010:1010 \"$targetPath\"").exec()
                    Shell.cmd("chmod 600 \"$targetPath\"").exec()
                    Shell.cmd("restorecon \"$targetPath\"").exec()
                    copyCount++
                }
            }
        }

        // Parse networks to inject via cmd wifi add-network
        try {
            val xmlContent = extractedFile.readText(Charsets.UTF_8)
            val networkRegex = Regex("<Network>(.*?)</Network>", RegexOption.DOT_MATCHES_ALL)
            val ssidRegex = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>")
            val pskRegex = Regex("<string name=\"PreSharedKey\">&quot;(.*?)&quot;</string>")
            val configKeyRegex = Regex("<string name=\"ConfigKey\">&quot;(.*?)&quot;</string>")

            for (match in networkRegex.findAll(xmlContent)) {
                val block = match.groupValues[1]
                val ssid = ssidRegex.find(block)?.groupValues?.get(1) ?: continue
                if (selectedWifiKeys != null && selectedWifiKeys.isNotEmpty() && !selectedWifiKeys.contains(ssid)) {
                    continue
                }
                val psk = pskRegex.find(block)?.groupValues?.get(1)
                val configKey = configKeyRegex.find(block)?.groupValues?.get(1) ?: ""
                val secType = when {
                    configKey.contains("WPA_PSK") || configKey.contains("WPA2") -> "wpa2"
                    configKey.contains("SAE") || configKey.contains("WPA3") -> "wpa3"
                    psk == null -> "open"
                    else -> "wpa2"
                }
                if (psk != null) {
                    Shell.cmd("cmd wifi add-network \"$ssid\" $secType \"$psk\" 2>/dev/null").exec()
                } else {
                    Shell.cmd("cmd wifi add-network \"$ssid\" open 2>/dev/null").exec()
                }
            }
        } catch (_: Throwable) {}

        // Reinicia wificond e religa o wifi
        Shell.cmd("killall -HUP wificond 2>/dev/null || killall wificond 2>/dev/null || true").exec()
        val enableCmd = Shell.cmd("svc wifi enable").exec()
        delay(1500)

        val netListCmd = Shell.cmd("cmd wifi list-networks 2>/dev/null").exec()
        val networksFound = netListCmd.out.filter { it.contains("Network Id") || it.matches(Regex("""^\d+\s+.*""")) }.size
        val verified = copyCount > 0 && (netListCmd.isSuccess || enableCmd.isSuccess)

        val notice = if (networksFound > 0) {
            "Wi-Fi: $networksFound redes ativas e recarregadas no sistema"
        } else {
            "Wi-Fi: Arquivo e redes injetadas no sistema com sucesso"
        }
        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Wi-Fi restaurado. Copiados=$copyCount, networks=$networksFound")

        return ItemRestoreOutcome(
            success = true,
            verified = verified,
            detailCount = notice,
            statusText = "Restaurado e verificado"
        )
    }

    private suspend fun restoreCallLogs(jsonData: ByteArray, selectedCallKeys: Set<String>? = null): ItemRestoreOutcome {
        return try {
            DeviceCategoryInspector.ensurePermission(
                context,
                android.Manifest.permission.READ_CALL_LOG,
                android.Manifest.permission.WRITE_CALL_LOG
            )
            val allCalls = gson.fromJson(String(jsonData, Charsets.UTF_8), Array<CallRecord>::class.java)
            if (allCalls.isEmpty()) {
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Nenhum registro de chamada para restaurar.")
                return ItemRestoreOutcome(true, verified = true, detailCount = "0 de 0", statusText = "Histórico vazio")
            }

            val calls = if (selectedCallKeys != null && selectedCallKeys.isNotEmpty()) {
                allCalls.filter { selectedCallKeys.contains("${it.date}_${it.number}") }
            } else {
                allCalls.toList()
            }

            if (calls.isEmpty()) {
                return ItemRestoreOutcome(true, verified = true, detailCount = "0 de ${allCalls.size}", statusText = "Nenhuma chamada selecionada")
            }

            val countBefore = getCallLogCount()

            val existingCalls = mutableSetOf<String>()
            try {
                val cursor = context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                    null, null, null
                )
                cursor?.use {
                    val numCol = it.getColumnIndex(CallLog.Calls.NUMBER)
                    val dateCol = it.getColumnIndex(CallLog.Calls.DATE)
                    while (it.moveToNext()) {
                        val num = if (numCol != -1) it.getString(numCol) ?: "" else ""
                        val date = if (dateCol != -1) it.getLong(dateCol) else 0L
                        existingCalls.add("${date}_${num}")
                    }
                }
            } catch (e: Exception) {
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Não foi possível verificar chamadas existentes via ContentResolver: ${e.message}")
            }

            // Fallback via root se não conseguiu ler existentes
            if (existingCalls.isEmpty() && Shell.isAppGrantedRoot() == true) {
                try {
                    val rootRes = Shell.cmd("content query --uri content://call_log/calls --projection number:date").exec()
                    if (rootRes.isSuccess) {
                        for (line in rootRes.out) {
                            if (!line.startsWith("Row:")) continue
                            val pairs = line.substringAfter("Row:").trim().split(", ")
                            var num = ""
                            var dt = 0L
                            for (p in pairs) {
                                val k = p.substringBefore("=").trim()
                                val v = p.substringAfter("=").trim()
                                if (k == "number" && v != "NULL") num = v
                                if (k == "date") dt = v.toLongOrNull() ?: 0L
                            }
                            if (num.isNotBlank() || dt > 0L) {
                                existingCalls.add("${dt}_${num}")
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            var duplicateCount = 0
            val callsToInsert = mutableListOf<CallRecord>()
            for (call in calls) {
                val key = "${call.date}_${call.number}"
                if (existingCalls.contains(key)) {
                    duplicateCount++
                    continue
                }
                callsToInsert.add(call)
                existingCalls.add(key)
            }

            var insertedCount = 0
            var writeMethod = "ContentResolver"

            if (callsToInsert.isNotEmpty()) {
                if (Shell.isAppGrantedRoot() == true) {
                    Shell.cmd(
                        "pm grant ${context.packageName} android.permission.READ_CALL_LOG 2>/dev/null",
                        "pm grant ${context.packageName} android.permission.WRITE_CALL_LOG 2>/dev/null",
                        "appops set ${context.packageName} WRITE_CALL_LOG allow 2>/dev/null"
                    ).exec()
                }

                try {
                    val valuesArray = callsToInsert.map { call ->
                        android.content.ContentValues().apply {
                            put(CallLog.Calls.NUMBER, call.number)
                            put(CallLog.Calls.DATE, call.date)
                            put(CallLog.Calls.DURATION, call.duration)
                            put(CallLog.Calls.TYPE, call.type)
                            put(CallLog.Calls.NEW, 0)
                            if (!call.cachedName.isNullOrBlank()) {
                                put(CallLog.Calls.CACHED_NAME, call.cachedName)
                            }
                            if (call.cachedNumberType > 0) {
                                put(CallLog.Calls.CACHED_NUMBER_TYPE, call.cachedNumberType)
                            }
                        }
                    }.toTypedArray()

                    insertedCount = context.contentResolver.bulkInsert(CallLog.Calls.CONTENT_URI, valuesArray)
                } catch (se: SecurityException) {
                    insertedCount = 0
                }

                // Se bulkInsert não inseriu (restrição do Android 10+), injeta via Root Shell
                if (insertedCount < callsToInsert.size && Shell.isAppGrantedRoot() == true) {
                    writeMethod = "Root Shell"
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Inserindo chamadas via root shell...")
                    val batchCmds = StringBuilder()
                    val remainingCalls = callsToInsert.drop(insertedCount)
                    for (c in remainingCalls) {
                        val num = c.number.replace("'", "'\\''")
                        val nameArg = if (!c.cachedName.isNullOrBlank()) " --bind name:s:'${c.cachedName.replace("'", "'\\''")}'" else ""
                        batchCmds.append("content insert --uri content://call_log/calls --bind number:s:'$num' --bind date:l:${c.date} --bind duration:l:${c.duration} --bind type:i:${c.type} --bind new:i:0$nameArg\n")
                    }
                    val tmpScript = File(context.cacheDir, "insert_calls_${System.currentTimeMillis()}.sh")
                    tmpScript.writeText(batchCmds.toString())
                    val runCmd = Shell.cmd("sh \"${tmpScript.absolutePath}\"").exec()
                    tmpScript.delete()
                    if (runCmd.isSuccess) {
                        insertedCount += remainingCalls.size
                    } else {
                        dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Falha na inserção via root de chamadas: ${runCmd.err.joinToString()}")
                    }
                }
            }

            val countAfter = getCallLogCount()
            val totalExpected = calls.size
            val restoredTotal = insertedCount + duplicateCount
            val verified = countAfter >= countBefore

            val msg = "Chamadas: $restoredTotal de $totalExpected ($insertedCount gravadas via $writeMethod, $duplicateCount duplicatas ignoradas)"
            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", msg)

            ItemRestoreOutcome(
                success = true,
                verified = verified,
                detailCount = msg,
                statusText = if (verified) "Restaurado e verificado" else "Restaurado"
            )
        } catch (e: Exception) {
            val err = "Falha ao restaurar histórico de chamadas: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    private suspend fun restoreSmsItem(tempDir: File, item: ManifestItem, selectedSmsKeys: Set<String>? = null): ItemRestoreOutcome {
        val extractedFile = File(tempDir, item.archiveEntryPath)
        if (!extractedFile.exists() || extractedFile.length() == 0L) {
            val err = "Arquivo de SMS não encontrado na extração: ${item.archiveEntryPath}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            return ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
        }

        val isDatabase = item.metadata["isDatabase"] == "true" || item.archiveEntryPath.contains("mmssms.db")
        val hasRoot = Shell.isAppGrantedRoot() == true

        if (isDatabase) {
            if (!hasRoot) {
                val err = "Restauração do banco telephony (mmssms.db) requer privilégios Root."
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", err)
                return ItemRestoreOutcome(false, error = err, statusText = "Root necessário")
            }

            return try {
                ProcessControlManager.safeForceStop(
                    context = context,
                    targetPackage = "com.android.providers.telephony",
                    progressPercent = 0.9f,
                    sourceTag = "RestoreEngine"
                )

                val targetDir = "/data/data/com.android.providers.telephony/databases"
                Shell.cmd("mkdir -p \"$targetDir\"").exec()

                val fileName = item.metadata["fileName"] ?: extractedFile.name
                val targetPath = "$targetDir/$fileName"
                val cpCmd = Shell.cmd("cp \"${extractedFile.absolutePath}\" \"$targetPath\"").exec()
                if (!cpCmd.isSuccess) {
                    val errDetail = (cpCmd.err + cpCmd.out).filter { it.isNotBlank() }.joinToString(" ")
                    val err = "Falha ao copiar $fileName para $targetDir: $errDetail"
                    dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                    return ItemRestoreOutcome(false, error = err, statusText = "Falha de cópia")
                }

                Shell.cmd("chown radio:radio \"$targetPath\" || chown 1001:1001 \"$targetPath\"").exec()
                Shell.cmd("chmod 660 \"$targetPath\"").exec()
                Shell.cmd("restorecon \"$targetPath\"").exec()
                Shell.cmd("killall com.android.providers.telephony || true").exec()

                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Banco de SMS $fileName restaurado com sucesso via root.")
                ItemRestoreOutcome(true, verified = true, detailCount = "Banco de dados telephony restaurado", statusText = "Restaurado e verificado")
            } catch (e: Exception) {
                val err = "Erro ao restaurar banco de SMS com root: ${e.message}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
            }
        } else {
            val originalDefaultSms = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            var roleSwitched = false
            return try {
                if (hasRoot) {
                    Shell.cmd(
                        "pm grant ${context.packageName} android.permission.READ_SMS 2>/dev/null",
                        "pm grant ${context.packageName} android.permission.WRITE_SMS 2>/dev/null",
                        "appops set ${context.packageName} WRITE_SMS allow 2>/dev/null"
                    ).exec()
                    if (!originalDefaultSms.isNullOrBlank() && originalDefaultSms != context.packageName) {
                        val switchRes = Shell.cmd("cmd role add-role-holder --user 0 android.app.role.SMS ${context.packageName}").exec()
                        if (switchRes.isSuccess) {
                            roleSwitched = true
                            dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "ReSync configurado temporariamente como app de SMS padrão (--user 0).")
                        }
                    }
                }
                DeviceCategoryInspector.ensurePermission(
                    context,
                    android.Manifest.permission.READ_SMS,
                    "android.permission.WRITE_SMS"
                )

                val allSmsList = gson.fromJson(extractedFile.readText(Charsets.UTF_8), Array<SmsRecord>::class.java).toList()
                if (allSmsList.isEmpty()) {
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Nenhuma mensagem SMS no arquivo de backup.")
                    return ItemRestoreOutcome(true, verified = true, detailCount = "0 de 0", statusText = "Histórico de SMS vazio")
                }

                val smsList = if (selectedSmsKeys != null && selectedSmsKeys.isNotEmpty()) {
                    allSmsList.filter { selectedSmsKeys.contains("${it.date}_${it.address}") }
                } else {
                    allSmsList
                }

                if (smsList.isEmpty()) {
                    return ItemRestoreOutcome(true, verified = true, detailCount = "0 de ${allSmsList.size}", statusText = "Nenhuma mensagem SMS selecionada")
                }

                val countBefore = getSmsCount()

                val existingSms = mutableSetOf<String>()
                try {
                    val cursor = context.contentResolver.query(
                        android.provider.Telephony.Sms.CONTENT_URI,
                        arrayOf(android.provider.Telephony.Sms.DATE, android.provider.Telephony.Sms.ADDRESS),
                        null, null, null
                    )
                    cursor?.use { c ->
                        val dateCol = c.getColumnIndex(android.provider.Telephony.Sms.DATE)
                        val addrCol = c.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                        while (c.moveToNext()) {
                            val d = if (dateCol != -1) c.getLong(dateCol) else 0L
                            val a = if (addrCol != -1) c.getString(addrCol) ?: "" else ""
                            existingSms.add("${d}_${a}")
                        }
                    }
                } catch (e: Exception) {
                    dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Não foi possível verificar SMS existentes: ${e.message}")
                }

                var count = 0
                var skipped = 0
                val pendingList = mutableListOf<SmsRecord>()

                for (sms in smsList) {
                    val key = "${sms.date}_${sms.address}"
                    if (existingSms.contains(key)) {
                        skipped++
                        continue
                    }
                    pendingList.add(sms)
                    existingSms.add(key)
                }

                var isRootFallbackUsed = false
                try {
                    for (sms in pendingList) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.Telephony.Sms.ADDRESS, sms.address)
                            put(android.provider.Telephony.Sms.BODY, sms.body)
                            put(android.provider.Telephony.Sms.DATE, sms.date)
                            put(android.provider.Telephony.Sms.TYPE, sms.type)
                            put(android.provider.Telephony.Sms.READ, sms.read)
                        }
                        val uri = context.contentResolver.insert(android.provider.Telephony.Sms.CONTENT_URI, values)
                        if (uri != null) count++
                    }
                } catch (se: SecurityException) {
                    // Trata fallback abaixo se root disponível
                }

                // Injeção via Root se ContentResolver retornou null ou menos itens
                if (count < pendingList.size && hasRoot) {
                    isRootFallbackUsed = true
                    dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Inserindo ${pendingList.size - count} mensagens SMS via root shell...")
                    val script = StringBuilder()
                    for (s in pendingList.drop(count)) {
                        val cleanAddr = s.address.replace("'", "'\\''")
                        val cleanBody = s.body.replace("'", "'\\''")
                        script.append("content insert --uri content://sms --bind address:s:'$cleanAddr' --bind body:s:'$cleanBody' --bind date:l:${s.date} --bind type:i:${s.type} --bind read:i:${s.read}\n")
                    }
                    val tmp = File(context.cacheDir, "insert_sms_${System.currentTimeMillis()}.sh")
                    tmp.writeText(script.toString())
                    val runCmd = Shell.cmd("sh \"${tmp.absolutePath}\"").exec()
                    tmp.delete()
                    if (runCmd.isSuccess) {
                        count = pendingList.size
                    } else {
                        dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", "Falha na inserção via root de SMS: ${runCmd.err.joinToString()}")
                    }
                } else if (count == 0 && pendingList.isNotEmpty() && !hasRoot) {
                    val notice = "A gravação de SMS requer definir temporariamente o app como app de SMS padrão nas configurações do Android, ou restaurar com Root."
                    dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", notice)
                    return ItemRestoreOutcome(false, error = notice, statusText = "Permissão de SMS necessária")
                }

                val countAfter = getSmsCount()
                val totalExpected = smsList.size
                val restoredTotal = count + skipped
                val verified = countAfter >= countBefore

                val detail = "SMS: $restoredTotal de $totalExpected ($count novos${if (isRootFallbackUsed) " via root" else ""}, $skipped duplicatas ignoradas)"
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", detail)

                ItemRestoreOutcome(
                    success = true,
                    verified = verified,
                    detailCount = detail,
                    statusText = if (verified) "Restaurado e verificado" else "Restaurado"
                )
            } catch (e: Exception) {
                val err = "Falha ao processar arquivo de SMS: ${e.message}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
            } finally {
                if (roleSwitched && !originalDefaultSms.isNullOrBlank()) {
                    try {
                        Shell.cmd("cmd role add-role-holder --user 0 android.app.role.SMS $originalDefaultSms").exec()
                        dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "App padrão de SMS ($originalDefaultSms) restaurado.")
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun restoreBluetooth(
        tempDir: File,
        item: ManifestItem,
        selectedBtKeys: Set<String>? = null
    ): ItemRestoreOutcome {
        return try {
            val extractedFile = File(tempDir, item.archiveEntryPath)

            if (!extractedFile.exists() || extractedFile.length() == 0L) {
                val err = "Arquivo Bluetooth não encontrado na extração: ${item.archiveEntryPath}"
                dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
                return ItemRestoreOutcome(false, error = err, statusText = "Arquivo ausente")
            }

            val hasRoot = Shell.isAppGrantedRoot() == true
            if (hasRoot) {
                val possibleSysPaths = listOf(
                    "/data/misc/apexdata/com.android.btservices/bt_config.conf",
                    "/data/misc/apexdata/com.android.bluetooth/bt_config.conf",
                    "/data/misc/bluedroid/bt_config.conf",
                    "/data/misc/bluetooth/bt_config.conf"
                )
                val targetPath = item.metadata["originalSystemPath"]?.takeIf { it.isNotBlank() }
                    ?: possibleSysPaths.firstOrNull {
                        val test = Shell.cmd("test -f \"$it\" && echo ok").exec()
                        test.isSuccess && test.out.contains("ok")
                    }
                    ?: possibleSysPaths.firstOrNull {
                        val parent = File(it).parent ?: ""
                        val test = Shell.cmd("test -d \"$parent\" && echo ok").exec()
                        test.isSuccess && test.out.contains("ok")
                    }
                    ?: "/data/misc/bluetooth/bt_config.conf"

                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Restaurando configurações Bluetooth em: $targetPath")

                Shell.cmd("cp \"$targetPath\" \"$targetPath.bak\" 2>/dev/null").exec()
                Shell.cmd("svc bluetooth disable || cmd bluetooth disable").exec()
                delay(1000L)

                val parentDir = File(targetPath).parent ?: "/data/misc/bluetooth"
                Shell.cmd("mkdir -p \"$parentDir\"").exec()

                var copySuccess = false

                val confFile = if (extractedFile.name.endsWith(".conf")) {
                    extractedFile
                } else {
                    File(tempDir, "system/bluetooth/bt_config.conf").takeIf { it.exists() }
                }

                if (confFile != null && confFile.exists()) {
                    if (selectedBtKeys == null || selectedBtKeys.isEmpty()) {
                        val cpCmd = Shell.cmd("cp \"${confFile.absolutePath}\" \"$targetPath\"").exec()
                        copySuccess = cpCmd.isSuccess
                    } else {
                        val existingConf = runCatching {
                            val cat = Shell.cmd("cat \"$targetPath\"").exec()
                            if (cat.isSuccess) cat.out.joinToString("\n") else ""
                        }.getOrDefault("")
                        val backupConf = confFile.readText(Charsets.UTF_8)
                        val merged = mergeBtConfig(existingConf, backupConf, selectedBtKeys)
                        val tmpMerged = File(context.cacheDir, "bt_config_merged_${System.currentTimeMillis()}.conf")
                        tmpMerged.writeText(merged)
                        val cpCmd = Shell.cmd("cp \"${tmpMerged.absolutePath}\" \"$targetPath\"").exec()
                        tmpMerged.delete()
                        copySuccess = cpCmd.isSuccess
                    }
                } else if (extractedFile.name.endsWith(".json")) {
                    val jsonStr = extractedFile.readText(Charsets.UTF_8)
                    val btData = runCatching { gson.fromJson(jsonStr, BluetoothBackupData::class.java) }.getOrNull()
                    val devices = btData?.bondedDevices?.ifEmpty { btData.connectedDevices } ?: emptyList()
                    val filteredDevices = if (selectedBtKeys != null && selectedBtKeys.isNotEmpty()) {
                        devices.filter { selectedBtKeys.contains(it.address) }
                    } else {
                        devices
                    }
                    if (filteredDevices.isNotEmpty()) {
                        val existingConf = runCatching {
                            val cat = Shell.cmd("cat \"$targetPath\"").exec()
                            if (cat.isSuccess) cat.out.joinToString("\n") else ""
                        }.getOrDefault("")
                        val merged = mergeBtDevicesIntoConfig(existingConf, filteredDevices)
                        val tmpMerged = File(context.cacheDir, "bt_config_json_merged_${System.currentTimeMillis()}.conf")
                        tmpMerged.writeText(merged)
                        val cpCmd = Shell.cmd("cp \"${tmpMerged.absolutePath}\" \"$targetPath\"").exec()
                        tmpMerged.delete()
                        copySuccess = cpCmd.isSuccess
                    } else {
                        copySuccess = true
                    }
                }

                if (!copySuccess) {
                    val cpCmd = Shell.cmd("cp \"${extractedFile.absolutePath}\" \"$targetPath\"").exec()
                    copySuccess = cpCmd.isSuccess
                }

                Shell.cmd("chown bluetooth:bluetooth \"$targetPath\" || chown 1002:1002 \"$targetPath\"").exec()
                Shell.cmd("chmod 660 \"$targetPath\"").exec()
                Shell.cmd("restorecon -F \"$targetPath\"").exec()
                Shell.cmd("svc bluetooth enable || cmd bluetooth enable").exec()
                Shell.cmd("service call bluetooth_manager 6 2>/dev/null || true").exec()
                delay(1000L)

                restoreReadableBluetoothFallback(tempDir)

                val verifyCmd = Shell.cmd("test -s \"$targetPath\" && stat -c %s \"$targetPath\"").exec()
                val size = verifyCmd.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
                val verified = size > 0L && copySuccess

                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Configuração Bluetooth restaurada e verificada (size=$size bytes).")
                ItemRestoreOutcome(true, verified = verified, detailCount = "Dispositivos e chaves aplicados", statusText = "Restaurado e verificado")
            } else {
                dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Restauração de chaves Bluetooth requer Root. Restaurando lista legível para consulta.")
                restoreReadableBluetoothFallback(tempDir)
                ItemRestoreOutcome(false, error = "Acesso Root necessário para restaurar chaves de emparelhamento Bluetooth.", statusText = "Root necessário")
            }
        } catch (e: Exception) {
            val err = "Falha na restauração do Bluetooth: ${e.message}"
            dbHelper.insertLog(LogLevel.ERROR, "RestoreEngine", err)
            try { restoreReadableBluetoothFallback(tempDir) } catch (_: Throwable) {}
            ItemRestoreOutcome(false, error = err, statusText = "Erro: ${e.message}")
        }
    }

    private fun mergeBtConfig(existingConf: String, backupConf: String, selectedKeys: Set<String>): String {
        val macRegex = Regex("""^\[([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\]""")
        val backupBlocks = mutableMapOf<String, String>()
        var currentMac: String? = null
        val currentBlock = StringBuilder()

        for (line in backupConf.lines()) {
            val match = macRegex.find(line.trim())
            if (match != null) {
                if (currentMac != null) {
                    backupBlocks[currentMac.uppercase(java.util.Locale.ROOT)] = currentBlock.toString()
                    currentBlock.clear()
                }
                currentMac = match.groupValues[1]
                currentBlock.appendLine(line)
            } else if (currentMac != null) {
                currentBlock.appendLine(line)
            }
        }
        if (currentMac != null) {
            backupBlocks[currentMac.uppercase(java.util.Locale.ROOT)] = currentBlock.toString()
        }

        val targetBlocks = backupBlocks.filter { (mac, _) ->
            selectedKeys.any { it.equals(mac, ignoreCase = true) }
        }

        if (targetBlocks.isEmpty()) return existingConf.ifBlank { backupConf }

        val sb = StringBuilder(existingConf.trimEnd())
        if (sb.isNotEmpty()) sb.append("\n\n")
        for ((mac, block) in targetBlocks) {
            if (!existingConf.contains(mac, ignoreCase = true)) {
                sb.append(block).append("\n")
            }
        }
        return sb.toString()
    }

    private fun mergeBtDevicesIntoConfig(existingConf: String, devices: List<BluetoothDeviceRecord>): String {
        val sb = StringBuilder(existingConf.trimEnd())
        if (sb.isNotEmpty()) sb.append("\n\n")
        for (dev in devices) {
            if (!existingConf.contains(dev.address, ignoreCase = true)) {
                sb.appendLine("[${dev.address}]")
                sb.appendLine("Name = ${dev.name}")
                sb.appendLine("DevType = 1")
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private suspend fun restoreReadableBluetoothFallback(tempDir: File) {
        try {
            val txtFile = File(tempDir, "system/bluetooth/dispositivos_pareados.txt")
            val jsonFile = File(tempDir, "system/bluetooth/bluetooth_backup.json")
            val targetDir = com.resync.app.data.model.BackupPaths.getDefaultLocalBackupDir()
            targetDir.mkdirs()
            if (txtFile.exists()) {
                val destTxt = File(targetDir, "bluetooth_dispositivos_restaurados.txt")
                txtFile.copyTo(destTxt, overwrite = true)
                dbHelper.insertLog(LogLevel.INFO, "RestoreEngine", "Relatório legível salvo em: ${destTxt.absolutePath}")
            }
            if (jsonFile.exists()) {
                val destJson = File(targetDir, "bluetooth_dados_restaurados.json")
                jsonFile.copyTo(destJson, overwrite = true)
            }
        } catch (e: Exception) {
            dbHelper.insertLog(LogLevel.WARN, "RestoreEngine", "Falha ao gravar arquivo de consulta legível: ${e.message}")
        }
    }
}

data class ItemRestoreOutcome(
    val success: Boolean,
    val error: String? = null,
    val detailCount: String? = null,
    val verified: Boolean = false,
    val statusText: String? = null
)

