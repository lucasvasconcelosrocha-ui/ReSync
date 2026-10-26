package com.resync.app

import android.app.Application
import androidx.work.*
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.resync.app.engine.SecurityManager
import com.resync.app.worker.CloudSyncWorker
import com.resync.app.worker.ScheduledBackupWorker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class ReSyncApplication : Application() {

    private val applicationScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Garante canais unificados resync_ops_v3 e limpa canais legados
        com.resync.app.util.HyperFocusNotificationHelper.ensureNotificationChannels(this)

        // Inicializa o repositório singleton de backup para restaurar estado se aplicável
        com.resync.app.engine.BackupRepository.initialize(this)

        // Inicializa o repositório singleton de restauração para persistir estado fixo
        com.resync.app.engine.RestoreRepository.initialize(this)

        // Inicializa o repositório singleton unificado de operações em segundo plano
        com.resync.app.engine.OperationRepository.initialize(this)

        // Inicializa o novo sistema de log central, monitor de rede reativo e controle térmico
        com.resync.app.util.AppLogger.initialize(this)
        com.resync.app.cloud.gdrive.CloudNetworkMonitor.initialize(this)
        com.resync.app.cloud.gdrive.CloudThermalGovernor.initialize(this)

        // Inicializa o monitor reativo de conexão de rede legado via NetworkCallback
        com.resync.app.cloud.gdrive.NetworkConnectionMonitor.initialize(this)

        // Registrar listener de eventos Bluetooth para histórico
        com.resync.app.engine.BluetoothEventReceiver.register(this)

        // 1. Instalar UncaughtExceptionHandler global para gravação de crash.log
        setupUncaughtExceptionHandler()

        // 2. Modo seguro e verificação de crash loop na inicialização
        checkSafeModeAndRecoverQueue()

        // Configure LibSU shell settings as required
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(30)
        )

        // Initialize Database, hardware keys and verify Root status
        applicationScope.launch {
            val db = AppDatabaseHelper.getInstance(this@ReSyncApplication)
            db.insertLog(LogLevel.INFO, "System", "ReSync iniciado no Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

            // Diagnóstico de motivos de saída anteriores do processo (Android 11+ / API 30+)
            logHistoricalProcessExitReasons(db)

            // Verificar se há operações em andamento no Room que foram interrompidas pela morte do processo
            recoverInterruptedOperations(db)

            // Na primeira execução, solicitar isenção de otimização de bateria e aplicar via root
            checkAndApplyBatteryOptimizationExemption(db)

            // Migrar pastas de backup locais legadas (SwiftVaultBackups -> ReSyncBackups)
            try {
                com.resync.app.data.model.BackupPaths.migrateLocalBackupFolders(this@ReSyncApplication)
            } catch (_: Throwable) {}

            val isRoot = Shell.isAppGrantedRoot() == true
            if (isRoot) {
                db.insertLog(LogLevel.INFO, "Root", "Acesso Root (Magisk/KernelSU) verificado e concedido com sucesso.")
            } else {
                db.insertLog(
                    LogLevel.WARN,
                    "Root",
                    "Acesso Root não concedido ou indisponível. Para realizar backup e restauração completa de dados de aplicativos (/data/data), conceda permissão de Superusuário no Magisk/KernelSU."
                )
            }

            try {
                SecurityManager.getOrCreateHardwareKey()
            } catch (e: Exception) {
                db.insertLog(LogLevel.WARN, "Security", "KeyStore de hardware não disponível: ${e.message}")
            }
        }

        // Configuração de StrictMode em builds de depuração para detectar e registrar bloqueios na Thread Principal
        setupStrictMode()

        // Setup background tasks
        setupBackgroundWorkers()
    }

    private fun setupStrictMode() {
        if (BuildConfig.DEBUG) {
            val threadPolicyBuilder = android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                threadPolicyBuilder.penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { violation ->
                    applicationScope.launch {
                        try {
                            val db = AppDatabaseHelper.getInstance(this@ReSyncApplication)
                            db.insertLog(
                                LogLevel.WARN,
                                "StrictMode",
                                "Violação na MainThread detectada: ${violation.javaClass.simpleName} - ${violation.message}"
                            )
                        } catch (_: Throwable) {}
                    }
                }
            }
            android.os.StrictMode.setThreadPolicy(threadPolicyBuilder.build())

            val vmPolicyBuilder = android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
            android.os.StrictMode.setVmPolicy(vmPolicyBuilder.build())
        }
    }

    private suspend fun checkAndApplyBatteryOptimizationExemption(db: AppDatabaseHelper) {
        try {
            val prefs = getSharedPreferences("battery_exemption_prefs", MODE_PRIVATE)
            val alreadyRequested = prefs.getBoolean("battery_exemption_requested", false)

            if (!alreadyRequested) {
                prefs.edit().putBoolean("battery_exemption_requested", true).apply()

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val powerManager = getSystemService(android.os.PowerManager::class.java)
                    if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        try {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            ).apply {
                                data = android.net.Uri.parse("package:$packageName")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            db.insertLog(LogLevel.WARN, "Bateria", "Não foi possível abrir diálogo de isenção de bateria: ${e.message}")
                        }
                    }
                }
            }

            // Sempre reforçar via root com libsu se tiver permissão root
            if (Shell.isAppGrantedRoot() == true) {
                val result = Shell.cmd(
                    "dumpsys deviceidle whitelist +$packageName",
                    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow"
                ).exec()
                if (result.isSuccess) {
                    db.insertLog(LogLevel.INFO, "Bateria", "App incluído na whitelist do Doze e RUN_ANY_IN_BACKGROUND permitido via Root.")
                }
            }
        } catch (t: Throwable) {
            db.insertLog(LogLevel.WARN, "Bateria", "Falha ao configurar isenção de bateria: ${t.message}")
        }
    }

    private suspend fun logHistoricalProcessExitReasons(db: AppDatabaseHelper) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val am = getSystemService(android.app.ActivityManager::class.java)
                val exitReasons = am?.getHistoricalProcessExitReasons(packageName, 0, 5)
                if (!exitReasons.isNullOrEmpty()) {
                    for (exitInfo in exitReasons) {
                        val isAnr = exitInfo.reason == android.app.ApplicationExitInfo.REASON_ANR
                        val reasonStr = when (exitInfo.reason) {
                            android.app.ApplicationExitInfo.REASON_ANR -> "ANR (Application Not Responding)"
                            android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH (Exceção não tratada)"
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH NATIVO (Falha em código C/C++)"
                            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE (Uso excessivo de recursos)"
                            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (Processo encerrou a si mesmo)"
                            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (Sistema matou o app por falta de RAM / LMK)"
                            android.app.ApplicationExitInfo.REASON_OTHER -> "OUTRO MOTIVO"
                            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                            android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (Terminado por sinal OS)"
                            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (Parada forçada pelo usuário)"
                            else -> "CÓDIGO_${exitInfo.reason}"
                        }
                        val desc = exitInfo.description?.let { " - $it" } ?: ""
                        val timestamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(exitInfo.timestamp))

                        // Extrair trace se for ANR
                        var anrTraceDetails = ""
                        if (isAnr) {
                            try {
                                exitInfo.traceInputStream?.use { stream ->
                                    val traceContent = stream.bufferedReader().use { it.readText() }
                                    if (traceContent.isNotBlank()) {
                                        anrTraceDetails = "\n--- TRACE DO ANR ---\n" + traceContent.take(3500)
                                    }
                                }
                            } catch (e: Exception) {
                                anrTraceDetails = "\n(Não foi possível ler trace: ${e.message})"
                            }
                        }

                        val logLevel = if (isAnr) LogLevel.ERROR else LogLevel.INFO
                        db.insertLog(
                            logLevel,
                            "Diagnóstico",
                            "Histórico de saída: Motivo=$reasonStr$desc (PID=${exitInfo.pid}, Importância=${exitInfo.importance}, Data=$timestamp)$anrTraceDetails"
                        )
                    }
                }
            } catch (t: Throwable) {
                db.insertLog(LogLevel.WARN, "Diagnóstico", "Não foi possível consultar histórico de saída: ${t.message}")
            }
        }
    }

    private suspend fun recoverInterruptedOperations(db: AppDatabaseHelper) {
        try {
            val roomDb = com.resync.app.data.room.AppRoomDatabase.getInstance(this)
            val unfinished = roomDb.activeOperationDao().getUnfinishedOperations()
            if (unfinished.isNotEmpty()) {
                for (op in unfinished) {
                    val isServiceAlive = if (op.type == "BACKUP") {
                        com.resync.app.service.BackupForegroundService.isServiceRunning
                    } else {
                        com.resync.app.service.RestoreForegroundService.isServiceRunning
                    }
                    if (!isServiceAlive) {
                        val interruptedOp = op.copy(
                            status = "INTERRUPTED",
                            stage = "Interrompido",
                            error = "A operação foi interrompida antes da conclusão (processo finalizado pelo sistema)."
                        )
                        roomDb.activeOperationDao().insertOrUpdate(interruptedOp)
                        db.insertLog(
                            LogLevel.INFO,
                            "Operações",
                            "Operação ${op.id} (${op.type}) recuperada do Room e marcada como Interrompida (último item: ${op.currentItemName}, ${op.currentItemIndex}/${op.totalItems})."
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            db.insertLog(LogLevel.WARN, "Operações", "Falha ao verificar operações persistentes no Room: ${t.message}")
        }
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val prefs = getSharedPreferences("safe_mode_prefs", MODE_PRIVATE)
                val crashCount = prefs.getInt("consecutive_crashes", 0) + 1
                prefs.edit()
                    .putInt("consecutive_crashes", crashCount)
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()

                val packageInfo = try {
                    packageManager.getPackageInfo(packageName, 0)
                } catch (_: Exception) { null }
                val versionName = packageInfo?.versionName ?: "1.0.1"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode ?: 2L
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong() ?: 2L
                }

                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val nowStr = dateFormat.format(java.util.Date())

                val crashLogContent = buildString {
                    appendLine("=== SWIFTVAULT CRASH LOG ===")
                    appendLine("Data/Hora: $nowStr")
                    appendLine("Versão do App: $versionName (Build $versionCode)")
                    appendLine("Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name} (id: ${thread.id})")
                    appendLine("Exceção: ${throwable.javaClass.name}: ${throwable.message}")
                    appendLine("--- STACKTRACE ---")
                    appendLine(throwable.stackTraceToString())
                    appendLine("============================")
                }

                val crashFile = java.io.File(filesDir, "crash.log")
                crashFile.writeText(crashLogContent)
            } catch (_: Throwable) {
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun checkSafeModeAndRecoverQueue() {
        val prefs = getSharedPreferences("safe_mode_prefs", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val consecutiveCrashes = prefs.getInt("consecutive_crashes", 0)
        val lastCrashTime = prefs.getLong("last_crash_time", 0L)
        val isCrashLoop = consecutiveCrashes >= 2 && (now - lastCrashTime < 60_000L)

        val workManager = try { WorkManager.getInstance(this) } catch (_: Throwable) { null }

        if (isCrashLoop) {
            // MODO SEGURO: Cancela todo o trabalho pendente no WorkManager para interromper crash loop
            try {
                workManager?.cancelAllWork()
            } catch (_: Throwable) {}

            prefs.edit().putInt("consecutive_crashes", 0).apply()

            applicationScope.launch {
                val db = AppDatabaseHelper.getInstance(this@ReSyncApplication)
                db.resetRunningQueueItems(asError = true, errorMessage = "Cancelado pelo Modo Seguro após falhas consecutivas na inicialização.")
                db.insertLog(
                    LogLevel.ERROR,
                    "SafeMode",
                    "Modo Seguro ativado: o app fechou 2+ vezes em menos de 60s. Fila cancelada e trabalhos WorkManager suspensos para permitir inicialização segura."
                )
            }
        } else {
            // Recuperação normal: itens que ficaram 'RUNNING' em processo encerrado voltam para 'PENDING'
            applicationScope.launch {
                val db = AppDatabaseHelper.getInstance(this@ReSyncApplication)
                db.resetRunningQueueItems(asError = false)
            }

            // Reseta contador de falhas se permanecer estável após 60 segundos
            applicationScope.launch {
                kotlinx.coroutines.delay(60_000L)
                prefs.edit().putInt("consecutive_crashes", 0).apply()
            }
        }
    }

    private fun setupBackgroundWorkers() {
        val workManager = WorkManager.getInstance(this)

        // Cancel any legacy or automatic background jobs
        try {
            workManager.cancelUniqueWork("SwiftVaultDailyBackup")
            workManager.cancelUniqueWork("SwiftVaultCloudSync")
            workManager.cancelAllWorkByTag(com.resync.app.worker.BackupSafetyWorker.WORK_TAG)
        } catch (_: Throwable) {}

        // Sincroniza agendamentos: cancela tudo se o usuário não tiver ativado explicitamente
        try {
            com.resync.app.engine.ScheduleManager.getInstance(this)
        } catch (_: Throwable) {}

        // Enfileira verificação periódica de atualização em background via WorkManager
        try {
            com.resync.app.worker.AppUpdateWorker.enqueuePeriodicWork(this)
        } catch (_: Throwable) {}
    }
}

typealias SwiftVaultApplication = ReSyncApplication

