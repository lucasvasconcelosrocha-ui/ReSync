package com.resync.app.cloud.gdrive

import android.content.Context
import com.google.gson.Gson
import com.resync.app.data.model.BackupItemType
import com.resync.app.data.model.BackupManifest
import com.resync.app.engine.ArchiveEngine
import com.resync.app.util.FormatUtils
import java.io.File

/**
 * Inspection and metadata tagging for backups in Google Drive.
 * Records category flags (`has_apps=true`, `count_apps=N`, etc.) in appProperties
 * and evaluates real category contents for remote backups.
 */
object CloudBackupCategoryHelper {

    private val gson = Gson()

    data class CategorySummary(
        val hasApps: Boolean = false,
        val countApps: Int = 0,
        val hasSms: Boolean = false,
        val countSms: Int = 0,
        val hasCalls: Boolean = false,
        val countCalls: Int = 0,
        val hasFolders: Boolean = false,
        val countFolders: Int = 0,
        val hasBluetooth: Boolean = false,
        val countBluetooth: Int = 0,
        val hasDns: Boolean = false,
        val countDns: Int = 0,
        val hasWifi: Boolean = false,
        val countWifi: Int = 0,
        val hasContacts: Boolean = false,
        val countContacts: Int = 0
    ) {
        fun containsCategory(categoryKey: String): Boolean {
            return when (categoryKey.lowercase()) {
                "apps", "aplicativos" -> hasApps
                "sms", "mensagens", "messages" -> hasSms
                "calls", "chamadas" -> hasCalls
                "folders", "pastas", "files" -> hasFolders
                "bluetooth" -> hasBluetooth
                "dns" -> hasDns
                "wifi", "wi-fi" -> hasWifi
                "contacts", "contatos" -> hasContacts
                else -> false
            }
        }

        fun toAppProperties(deviceLabel: String, title: String? = null): Map<String, String> {
            val props = mutableMapOf<String, String>()
            props["deviceLabel"] = deviceLabel.trim()
            if (!title.isNullOrBlank()) {
                props["title"] = title.trim()
            }
            if (hasApps) {
                props["has_apps"] = "true"
                props["count_apps"] = countApps.coerceAtLeast(1).toString()
            }
            if (hasSms) {
                props["has_sms"] = "true"
                props["count_sms"] = countSms.coerceAtLeast(1).toString()
            }
            if (hasCalls) {
                props["has_calls"] = "true"
                props["count_calls"] = countCalls.coerceAtLeast(1).toString()
            }
            if (hasFolders) {
                props["has_folders"] = "true"
                props["count_folders"] = countFolders.coerceAtLeast(1).toString()
            }
            if (hasBluetooth) {
                props["has_bt"] = "true"
                props["count_bt"] = countBluetooth.coerceAtLeast(1).toString()
            }
            if (hasDns) {
                props["has_dns"] = "true"
                props["count_dns"] = countDns.coerceAtLeast(1).toString()
            }
            if (hasWifi) {
                props["has_wifi"] = "true"
                props["count_wifi"] = countWifi.coerceAtLeast(1).toString()
            }
            if (hasContacts) {
                props["has_contacts"] = "true"
                props["count_contacts"] = countContacts.coerceAtLeast(1).toString()
            }
            return props
        }
    }

    /**
     * Inspects a local backup file and generates category appProperties for Google Drive upload.
     */
    suspend fun inspectLocalFile(context: Context, localFile: File, backupTitle: String? = null): Map<String, String> {
        val currentLabel = context.getSharedPreferences("swiftvault_prefs", Context.MODE_PRIVATE)
            .getString("custom_device_label", null) ?: FormatUtils.getDeviceIdentifier(context)

        val summary = extractCategorySummaryFromLocalFile(localFile)
        return summary.toAppProperties(currentLabel, backupTitle ?: localFile.nameWithoutExtension)
    }

    private suspend fun extractCategorySummaryFromLocalFile(localFile: File): CategorySummary {
        // 1. Try reading from companion sidecar index file (.index.json)
        val baseName = localFile.nameWithoutExtension
        val sidecar = File(localFile.parentFile, "$baseName.index.json")
        if (sidecar.exists() && sidecar.length() > 0L) {
            try {
                val bytes = sidecar.readBytes()
                val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
                val jsonStr = if (isGzip) {
                    java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val manifest = gson.fromJson(jsonStr, BackupManifest::class.java)
                if (manifest?.items != null && manifest.items.isNotEmpty()) {
                    return fromManifest(manifest)
                }
            } catch (_: Throwable) {}
        }

        // 2. Try inspecting manifest directly via ArchiveEngine
        try {
            val manifest = ArchiveEngine.readManifest(localFile)
            if (manifest.items.isNotEmpty()) {
                return fromManifest(manifest)
            }
        } catch (_: Throwable) {}

        // 3. Fallback based on file name heuristics
        return fromFilename(localFile.name)
    }

    private fun fromManifest(manifest: BackupManifest): CategorySummary {
        val items = manifest.items
        val appPackages = items.filter { it.type in listOf(BackupItemType.APP_APK, BackupItemType.APP_SPLIT_APKS, BackupItemType.APP_DATA) }
            .mapNotNull { it.metadata["packageName"] ?: it.archiveEntryPath.substringAfter("apps/").substringBefore("/").takeIf { p -> p.isNotBlank() } }
            .distinct()
        val appCount = if (appPackages.isNotEmpty()) appPackages.size else items.count { it.type == BackupItemType.APP_APK }

        val smsCount = items.count { it.type == BackupItemType.SMS }
        val callsCount = items.count { it.type == BackupItemType.CALL_LOG }
        val foldersCount = items.count { it.type == BackupItemType.FOLDER || it.type == BackupItemType.FILE }
        val btCount = items.count { it.type == BackupItemType.BLUETOOTH }
        val dnsCount = items.count { it.type == BackupItemType.DNS_CONFIG }
        val wifiCount = items.count { it.type == BackupItemType.WIFI_CONFIG }
        val contactsCount = items.count { it.type == BackupItemType.CONTACTS }

        return CategorySummary(
            hasApps = appCount > 0,
            countApps = appCount,
            hasSms = smsCount > 0,
            countSms = smsCount,
            hasCalls = callsCount > 0,
            countCalls = callsCount,
            hasFolders = foldersCount > 0,
            countFolders = foldersCount,
            hasBluetooth = btCount > 0,
            countBluetooth = btCount,
            hasDns = dnsCount > 0,
            countDns = dnsCount,
            hasWifi = wifiCount > 0,
            countWifi = wifiCount,
            hasContacts = contactsCount > 0,
            countContacts = contactsCount
        )
    }

    private fun fromFilename(fileName: String): CategorySummary {
        val nameLower = fileName.lowercase()
        val isFullPackage = nameLower.endsWith(".resync") || nameLower.endsWith(".svb") ||
                nameLower.contains("backup") || nameLower.contains("completo") ||
                nameLower.contains("rapido") || nameLower.contains("quick")

        val isApp = nameLower.endsWith(".apk") || nameLower.contains("app")
        val isSms = nameLower.contains("sms") || nameLower.contains("message") || nameLower.contains("mensag")
        val isCall = nameLower.contains("call") || nameLower.contains("chamada")
        val isFolder = nameLower.contains("folder") || nameLower.contains("pasta") || nameLower.contains("file")
        val isBt = nameLower.contains("bluetooth")
        val isDns = nameLower.contains("dns")
        val isWifi = nameLower.contains("wifi") || nameLower.contains("wi-fi")
        val isContacts = nameLower.contains("contact") || nameLower.contains("contato")

        val hasAnySpecific = isApp || isSms || isCall || isFolder || isBt || isDns || isWifi || isContacts

        if (isFullPackage && !hasAnySpecific) {
            // Full comprehensive package contains all 8 categories
            return CategorySummary(
                hasApps = true, countApps = 1,
                hasSms = true, countSms = 1,
                hasCalls = true, countCalls = 1,
                hasFolders = true, countFolders = 1,
                hasBluetooth = true, countBluetooth = 1,
                hasDns = true, countDns = 1,
                hasWifi = true, countWifi = 1,
                hasContacts = true, countContacts = 1
            )
        }

        return CategorySummary(
            hasApps = isApp || (isFullPackage && !hasAnySpecific),
            countApps = if (isApp || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasSms = isSms || (isFullPackage && !hasAnySpecific),
            countSms = if (isSms || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasCalls = isCall || (isFullPackage && !hasAnySpecific),
            countCalls = if (isCall || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasFolders = isFolder || (isFullPackage && !hasAnySpecific),
            countFolders = if (isFolder || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasBluetooth = isBt || (isFullPackage && !hasAnySpecific),
            countBluetooth = if (isBt || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasDns = isDns || (isFullPackage && !hasAnySpecific),
            countDns = if (isDns || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasWifi = isWifi || (isFullPackage && !hasAnySpecific),
            countWifi = if (isWifi || (isFullPackage && !hasAnySpecific)) 1 else 0,
            hasContacts = isContacts || (isFullPackage && !hasAnySpecific),
            countContacts = if (isContacts || (isFullPackage && !hasAnySpecific)) 1 else 0
        )
    }

    /**
     * Inspects a remote file retrieved from Google Drive.
     * Uses `appProperties` if present; falls back to full package or filename analysis.
     */
    fun inspectRemoteFile(file: DriveFileMetadata): CategorySummary {
        val props = file.appProperties
        val hasPropApps = props["has_apps"]?.toBooleanStrictOrNull()
        val hasPropSms = props["has_sms"]?.toBooleanStrictOrNull()
        val hasPropCalls = props["has_calls"]?.toBooleanStrictOrNull()
        val hasPropFolders = props["has_folders"]?.toBooleanStrictOrNull()
        val hasPropBt = props["has_bt"]?.toBooleanStrictOrNull()
        val hasPropDns = props["has_dns"]?.toBooleanStrictOrNull()
        val hasPropWifi = props["has_wifi"]?.toBooleanStrictOrNull()
        val hasPropContacts = props["has_contacts"]?.toBooleanStrictOrNull()

        val anyPropPresent = hasPropApps != null || hasPropSms != null || hasPropCalls != null ||
                hasPropFolders != null || hasPropBt != null || hasPropDns != null ||
                hasPropWifi != null || hasPropContacts != null

        if (anyPropPresent) {
            val countApps = props["count_apps"]?.toIntOrNull() ?: if (hasPropApps == true) 1 else 0
            val countSms = props["count_sms"]?.toIntOrNull() ?: if (hasPropSms == true) 1 else 0
            val countCalls = props["count_calls"]?.toIntOrNull() ?: if (hasPropCalls == true) 1 else 0
            val countFolders = props["count_folders"]?.toIntOrNull() ?: if (hasPropFolders == true) 1 else 0
            val countBt = props["count_bt"]?.toIntOrNull() ?: if (hasPropBt == true) 1 else 0
            val countDns = props["count_dns"]?.toIntOrNull() ?: if (hasPropDns == true) 1 else 0
            val countWifi = props["count_wifi"]?.toIntOrNull() ?: if (hasPropWifi == true) 1 else 0
            val countContacts = props["count_contacts"]?.toIntOrNull() ?: if (hasPropContacts == true) 1 else 0

            return CategorySummary(
                hasApps = hasPropApps == true || countApps > 0,
                countApps = countApps,
                hasSms = hasPropSms == true || countSms > 0,
                countSms = countSms,
                hasCalls = hasPropCalls == true || countCalls > 0,
                countCalls = countCalls,
                hasFolders = hasPropFolders == true || countFolders > 0,
                countFolders = countFolders,
                hasBluetooth = hasPropBt == true || countBt > 0,
                countBluetooth = countBt,
                hasDns = hasPropDns == true || countDns > 0,
                countDns = countDns,
                hasWifi = hasPropWifi == true || countWifi > 0,
                countWifi = countWifi,
                hasContacts = hasPropContacts == true || countContacts > 0,
                countContacts = countContacts
            )
        }

        // Fallback to filename & heuristics for older backups
        return fromFilename(file.name)
    }
}
