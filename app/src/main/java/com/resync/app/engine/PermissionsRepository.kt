package com.resync.app.engine

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class PermissionsState(
    val isRootGranted: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val hasMediaImages: Boolean = false,
    val hasMediaVideo: Boolean = false,
    val hasMediaAudio: Boolean = false,
    val hasPostNotifications: Boolean = false,
    val hasContacts: Boolean = false,
    val hasCalendar: Boolean = false,
    val hasSms: Boolean = false,
    val hasCalls: Boolean = false,
    val hasBluetoothConnect: Boolean = false,
    val hasWriteSettings: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasBackgroundDataExemption: Boolean = false,
    val persistedSafUrisCount: Int = 0,
    val hasUsageStats: Boolean = false,
    val hasAutostart: Boolean = false
) {
    val areAllRequiredGranted: Boolean
        get() = hasAllFilesAccess

    val areAllRuntimeGranted: Boolean
        get() = hasContacts && hasCalendar && hasSms && hasCalls && hasPostNotifications
}

class PermissionsRepository(private val context: Context) {

    private val _permissionsState = MutableStateFlow(PermissionsState())
    val permissionsState: StateFlow<PermissionsState> = _permissionsState.asStateFlow()

    private fun checkPerm(perm: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Avaliação pura de permissões sem invocar 'su'. Rápida, síncrona e segura para chamadas frequentes.
     */
    fun evaluateSystemPermissions(currentRoot: Boolean = false): PermissionsState {
        return try {
            val hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Environment.isExternalStorageManager()
                } catch (_: Throwable) {
                    false
                }
            } else {
                checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasAudio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPerm(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            }

            val hasContacts = checkPerm(Manifest.permission.READ_CONTACTS)
            val hasCalendar = checkPerm(Manifest.permission.READ_CALENDAR)
            val hasSms = checkPerm(Manifest.permission.READ_SMS)
            val hasCalls = checkPerm(Manifest.permission.READ_CALL_LOG)

            val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                true
            }

            val hasWrite = try {
                Settings.System.canWrite(context)
            } catch (_: Throwable) {
                false
            }

            val powerManager = try {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            } catch (_: Throwable) {
                null
            }
            val isIgnoringBattery = try {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } catch (_: Throwable) {
                false
            }

            val safCount = try {
                context.contentResolver.persistedUriPermissions.size
            } catch (_: Throwable) {
                0
            }

            val appOps = try {
                context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            } catch (_: Throwable) {
                null
            }
            val hasUsage = try {
                if (appOps != null) {
                    @Suppress("DEPRECATION")
                    val mode = appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                    mode == AppOpsManager.MODE_ALLOWED
                } else false
            } catch (_: Throwable) {
                false
            }

            val isXiaomi = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                    Build.BRAND.contains("Xiaomi", ignoreCase = true) ||
                    Build.BRAND.contains("POCO", ignoreCase = true) ||
                    Build.BRAND.contains("Redmi", ignoreCase = true)

            val autostartPrefConfigured = try {
                val prefs = context.getSharedPreferences("resync_permissions_prefs", Context.MODE_PRIVATE)
                prefs.getBoolean("hyperos_autostart_configured", false)
            } catch (_: Throwable) {
                false
            }

            val miuiAutostart = if (isXiaomi && appOps != null) {
                try {
                    val checkOpMethod = appOps.javaClass.getMethod(
                        "checkOpNoThrow",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java
                    )
                    val mode = checkOpMethod.invoke(appOps, 10008, Process.myUid(), context.packageName) as Int
                    mode == AppOpsManager.MODE_ALLOWED
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }

            val hasAutostart = if (isXiaomi) {
                miuiAutostart || (autostartPrefConfigured && (currentRoot || isIgnoringBattery))
            } else {
                isIgnoringBattery || (autostartPrefConfigured && currentRoot)
            }

            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val hasBgDataExemption = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && connMgr != null) {
                connMgr.restrictBackgroundStatus != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            } else {
                true
            }

            PermissionsState(
                isRootGranted = currentRoot,
                hasAllFilesAccess = hasAllFiles,
                hasMediaImages = hasImages,
                hasMediaVideo = hasVideo,
                hasMediaAudio = hasAudio,
                hasPostNotifications = hasNotifications,
                hasContacts = hasContacts,
                hasCalendar = hasCalendar,
                hasSms = hasSms,
                hasCalls = hasCalls,
                hasBluetoothConnect = hasBluetooth,
                hasWriteSettings = hasWrite,
                isIgnoringBatteryOptimizations = isIgnoringBattery,
                hasBackgroundDataExemption = hasBgDataExemption,
                persistedSafUrisCount = safCount,
                hasUsageStats = hasUsage,
                hasAutostart = hasAutostart
            )
        } catch (_: Throwable) {
            PermissionsState(isRootGranted = currentRoot)
        }
    }

    /**
     * Define explicitamente que o Início Automático e Otimização de Bateria HyperOS foram configurados pelo usuário.
     */
    fun setHyperOsAutostartConfigured(configured: Boolean = true) {
        try {
            context.getSharedPreferences("resync_permissions_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hyperos_autostart_configured", configured)
                .apply()
            refreshSystemPermissions()
        } catch (_: Throwable) {}
    }

    /**
     * Atualiza as permissões de sistema imediatamente (usado em callbacks de ActivityResult, onResume e polling leve).
     */
    fun refreshSystemPermissions() {
        val root = runCatching { _permissionsState.value.isRootGranted }.getOrDefault(false)
        _permissionsState.value = evaluateSystemPermissions(currentRoot = root)
    }

    /**
     * Checagem limpa e segura de root com timeout de 5 segundos.
     * Quando isInteractive = false (ex: inicialização do app), realiza checagem segura
     * sem resetar indevidamente o estado para não-concedido.
     */
    suspend fun checkRoot(forceNewShell: Boolean = false, isInteractive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(5000L) {
            try {
                if (forceNewShell) {
                    try {
                        val cached = Shell.getCachedShell()
                        if (cached != null && (!cached.isRoot || !cached.isAlive)) {
                            cached.close()
                        }
                    } catch (_: Throwable) {}
                    try {
                        Shell.getShell()
                    } catch (_: Throwable) {}
                }
                CapabilityManager(context).verifyRootAccess(timeoutMs = 4000L)
            } catch (_: Throwable) {
                false
            }
        } ?: false

        updateRootGranted(result)
        if (result) {
            grantAllPermissionsViaRoot(context)
        }
        result
    }

    /**
     * Concede automaticamente todas as permissões declaradas no manifesto via Root Shell com uid=0 confirmado,
     * incluindo permissões de runtime (com --user explicitado), AppOps com --uid e whitelist de bateria (deviceidle).
     */
    suspend fun grantAllPermissionsViaRoot(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val pkg = context.packageName
            val userId = try {
                android.os.Process.myUserHandle().hashCode()
            } catch (_: Throwable) {
                0
            }

            val commands = mutableListOf<String>()

            // 1. Desbloquear Restricted Settings (essencial para APKs sideload no Android 13–17)
            commands.add("cmd appops set --uid $pkg ACCESS_RESTRICTED_SETTINGS allow")
            commands.add("appops set --uid $pkg ACCESS_RESTRICTED_SETTINGS allow")

            // 2. Storage Access / Acesso a todos os arquivos (CORREÇÃO DO BUG --uid)
            commands.add("cmd appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
            commands.add("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
            commands.add("cmd appops set --user $userId $pkg MANAGE_EXTERNAL_STORAGE allow")
            commands.add("appops set --user $userId $pkg MANAGE_EXTERNAL_STORAGE allow")
            commands.add("cmd appops set --uid $pkg LEGACY_STORAGE allow")
            commands.add("appops set --uid $pkg READ_EXTERNAL_STORAGE allow")
            commands.add("appops set --uid $pkg WRITE_EXTERNAL_STORAGE allow")

            // 3. Battery Optimization (Whitelist do Doze + Background irrestrito)
            commands.add("dumpsys deviceidle whitelist +$pkg")
            commands.add("cmd deviceidle whitelist +$pkg")
            commands.add("cmd appops set --uid $pkg RUN_IN_BACKGROUND allow")
            commands.add("cmd appops set --uid $pkg RUN_ANY_IN_BACKGROUND allow")
            commands.add("cmd appops set --user $userId $pkg RUN_IN_BACKGROUND allow")
            commands.add("cmd appops set --user $userId $pkg RUN_ANY_IN_BACKGROUND allow")

            // 4. Autostart (HyperOS / MIUI + Android geral)
            commands.add("cmd appops set --user $userId $pkg 10008 allow")
            commands.add("appops set --uid $pkg 10008 allow")
            commands.add("cmd appops set --user $userId $pkg AUTO_START allow")
            commands.add("cmd appops set --user $userId $pkg BOOT_COMPLETED allow")

            // 5. Outras permissões especiais
            commands.add("cmd appops set --uid $pkg GET_USAGE_STATS allow")
            commands.add("cmd appops set --uid $pkg REQUEST_INSTALL_PACKAGES allow")
            commands.add("cmd appops set --uid $pkg WRITE_SETTINGS allow")
            commands.add("cmd appops set --uid $pkg SYSTEM_ALERT_WINDOW allow")
            commands.add("cmd appops set --uid $pkg POST_NOTIFICATION allow")

            // 6. Todas as permissões de Runtime (com --user $userId explícito)
            val pm = context.packageManager
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }

            val requested = packageInfo.requestedPermissions?.toSet() ?: emptySet()
            val explicitPerms = setOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

            val permsToGrant = (requested + explicitPerms).filter { perm ->
                requested.contains(perm)
            }

            for (perm in permsToGrant) {
                commands.add("pm grant --user $userId $pkg $perm || pm grant $pkg $perm")
            }

            // Executa tudo via RootUtils com verificação obrigatória de uid=0
            val rootExec = RootUtils.executeRootScript(commands)
            if (!rootExec.isRoot) {
                com.resync.app.util.AppLogger.w("PermissionsRepo", "grantAllPermissionsViaRoot: Falha, uid=0 não confirmado.")
                return@withContext false
            }

            // Aguarda propagação no PackageManager e AppOpsService
            kotlinx.coroutines.delay(250)

            // Registra sucesso nas preferências de autostart
            if (rootExec.isSuccess) {
                try {
                    context.getSharedPreferences("resync_permissions_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("hyperos_autostart_configured", true)
                        .apply()
                } catch (_: Throwable) {}
            }

            // Atualiza o estado em memória e reavalia
            refreshSystemPermissions()
            val updatedState = evaluateSystemPermissions(currentRoot = true)
            _permissionsState.value = updatedState

            com.resync.app.util.AppLogger.i(
                "PermissionsRepo",
                "grantAllPermissionsViaRoot concluído com sucesso: hasAllFilesAccess=${updatedState.hasAllFilesAccess}, hasBattery=${updatedState.isIgnoringBatteryOptimizations}, hasAutostart=${updatedState.hasAutostart}"
            )

            updatedState.hasAllFilesAccess
        } catch (t: Throwable) {
            com.resync.app.util.AppLogger.e("PermissionsRepo", "Falha ao conceder permissoes via root: ${t.message}", t)
            false
        }
    }

    fun updateRootGranted(isGranted: Boolean) {
        try {
            _permissionsState.value = _permissionsState.value.copy(isRootGranted = isGranted)
        } catch (_: Throwable) {}
    }

    init {
        try {
            _permissionsState.value = evaluateSystemPermissions(currentRoot = false)
        } catch (_: Throwable) {
            // Em caso de falha de qualquer API do Android, mantém PermissionsState() padrão e seguro
        }
    }

    companion object {
        @Volatile
        private var instance: PermissionsRepository? = null

        fun getInstance(context: Context): PermissionsRepository {
            return instance ?: synchronized(this) {
                instance ?: PermissionsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

