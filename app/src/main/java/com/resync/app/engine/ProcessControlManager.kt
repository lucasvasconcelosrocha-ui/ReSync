package com.resync.app.engine

import android.content.Context
import android.util.Log
import com.resync.app.BuildConfig
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.LogLevel
import com.topjohnwu.superuser.Shell

/**
 * Centralized, guarded process control manager.
 * Prevents catastrophic self-termination or terminating critical system/root components
 * (such as Magisk, KernelSU, Termux, SystemUI, or the active root shell session).
 */
object ProcessControlManager {

    private const val TAG = "ProcessControl"

    // Core packages that must NEVER be killed or force-stopped
    private val PROTECTED_PACKAGES = setOf(
        "com.resync.app",
        BuildConfig.APPLICATION_ID,
        "com.termux",
        "com.termux.api",
        "com.termux.boot",
        "com.termux.window",
        "com.android.systemui",
        "android",
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.inputmethod.latin",
        "com.android.inputmethod.latin",
        "com.topjohnwu.magisk",
        "io.github.vvb2060.magisk",
        "io.github.huskydg.magisk",
        "me.weishu.kernelsu",
        "com.rifsxd.ksunext",
        "me.bmax.apatch",
        "com.sukisu.ultra",
        "eu.chainfire.supersu"
    )

    /**
     * Determines whether a package is protected against force-stop/kill.
     */
    fun isPackageProtected(packageName: String, context: Context? = null): Boolean {
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return true

        // 1. Check exact match or application package name
        val myPkg = context?.packageName ?: BuildConfig.APPLICATION_ID
        if (cleanPkg.equals(myPkg, ignoreCase = true) || cleanPkg.equals("com.resync.app", ignoreCase = true)) {
            return true
        }

        // 2. Check static protected set
        if (PROTECTED_PACKAGES.any { it.equals(cleanPkg, ignoreCase = true) }) {
            return true
        }

        // 3. Dynamic root managers detection
        if (context != null) {
            val rootManagers = RootManagers.detectInstalledManagers(context)
            if (rootManagers.any { it.packageName.equals(cleanPkg, ignoreCase = true) }) {
                return true
            }
        }

        return false
    }

    /**
     * Safely executes `am force-stop <pkg>` only if the package is NOT protected.
     * Logs progress percentage, target package, and result.
     *
     * @return true if force-stop was executed, false if skipped or failed.
     */
    suspend fun safeForceStop(
        context: Context,
        targetPackage: String,
        progressPercent: Float,
        sourceTag: String = "BackupEngine"
    ): Boolean {
        val dbHelper = AppDatabaseHelper.getInstance(context)
        val pctFormatted = (progressPercent * 100).toInt().coerceIn(0, 100)

        if (isPackageProtected(targetPackage, context)) {
            val skipMsg = "IGNORADO am force-stop para pacote protegido: '$targetPackage' em $pctFormatted% de progresso."
            Log.w(TAG, skipMsg)
            dbHelper.insertLog(LogLevel.WARN, sourceTag, skipMsg)
            return false
        }

        val logBeforeMsg = "Solicitando am force-stop para '$targetPackage' em $pctFormatted% de progresso."
        Log.i(TAG, logBeforeMsg)
        dbHelper.insertLog(LogLevel.INFO, sourceTag, logBeforeMsg)

        return try {
            val cmd = Shell.cmd("am force-stop $targetPackage").exec()
            val outcomeMsg = "am force-stop '$targetPackage' em $pctFormatted%: exitCode=${cmd.code}, isSuccess=${cmd.isSuccess}"
            Log.d(TAG, outcomeMsg)
            dbHelper.insertLog(
                if (cmd.isSuccess) LogLevel.INFO else LogLevel.WARN,
                sourceTag,
                outcomeMsg
            )
            cmd.isSuccess
        } catch (e: Exception) {
            val errMsg = "Erro ao executar am force-stop '$targetPackage' em $pctFormatted%: ${e.message}"
            Log.e(TAG, errMsg, e)
            dbHelper.insertLog(LogLevel.ERROR, sourceTag, errMsg)
            false
        }
    }
}

