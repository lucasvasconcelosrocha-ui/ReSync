package com.resync.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class RootManagerApp(
    val packageName: String,
    val name: String,
    val launchIntent: Intent?
)

object RootManagers {

    val KNOWN_PACKAGES = listOf(
        "com.topjohnwu.magisk" to "Magisk",
        "io.github.vvb2060.magisk" to "Magisk Alpha",
        "io.github.huskydg.magisk" to "Magisk Delta / Kitsune",
        "me.weishu.kernelsu" to "KernelSU",
        "com.rifsxd.ksunext" to "KernelSU Next",
        "me.bmax.apatch" to "APatch",
        "com.sukisu.ultra" to "KernelSU Ultra",
        "eu.chainfire.supersu" to "SuperSU"
    )

    private val NAME_KEYWORDS = listOf("magisk", "kernelsu", "ksu", "apatch", "supersu")

    fun detectInstalledManagers(context: Context): List<RootManagerApp> {
        val pm = context.packageManager
        val detected = mutableMapOf<String, RootManagerApp>()

        // 1. Detectar por pacotes conhecidos
        for ((pkg, displayName) in KNOWN_PACKAGES) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                val label = pm.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() } ?: displayName
                val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                detected[pkg] = RootManagerApp(pkg, label, intent)
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Throwable) {}
        }

        // 2. Detectar por rótulo (caso o app esteja renomeado ou empacotado diferente, via launcher apps)
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            for (info in resolveInfos) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                if (detected.containsKey(pkg)) continue

                val label = info.loadLabel(pm)?.toString() ?: ""
                val lowerLabel = label.lowercase()
                val isRootKeyword = NAME_KEYWORDS.any { lowerLabel.contains(it) }

                if (isRootKeyword) {
                    val intent = pm.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val finalName = if (label.isNotBlank()) label else "Gerenciador de Root"
                    detected[pkg] = RootManagerApp(pkg, finalName, intent)
                }
            }
        } catch (_: Throwable) {}

        return detected.values.toList()
    }
}

