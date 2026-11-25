package com.resync.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Metadata descriptor for a device label (Etiquetas 2.0).
 * Persisted as `label_manifest.json` in Google Drive with instant local cache.
 */
data class LabelManifest(
    @SerializedName("labelId") val labelId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("deviceModel") val deviceModel: String,
    @SerializedName("deviceCodename") val deviceCodename: String,
    @SerializedName("androidVersion") val androidVersion: String,
    @SerializedName("rootActive") val rootActive: Boolean,
    @SerializedName("isLocked") val isLocked: Boolean = false,
    @SerializedName("retentionCount") val retentionCount: Int = 2,
    @SerializedName("cryptoKeyFingerprint") val cryptoKeyFingerprint: String = "",
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("totalBytes") val totalBytes: Long = 0L,
    @SerializedName("packageCount") val packageCount: Int = 0,
    @SerializedName("partitions") val partitions: Map<String, PartitionPointer> = emptyMap()
) {
    companion object {
        const val KEY_APPS = "apps"
        const val KEY_MESSAGES = "messages"
        const val KEY_CALLS = "calls"
        const val KEY_FOLDERS = "folders"
        const val KEY_BLUETOOTH = "bluetooth"
        const val KEY_DNS = "dns"
        const val KEY_WIFI = "wifi"
        const val KEY_CONTACTS = "contacts"

        val FIXED_KEYS = listOf(
            KEY_APPS, KEY_MESSAGES, KEY_CALLS, KEY_FOLDERS,
            KEY_BLUETOOTH, KEY_DNS, KEY_WIFI, KEY_CONTACTS
        )

        fun createDefault(
            labelId: String,
            displayName: String? = null,
            rootActive: Boolean = false,
            cryptoKeyFingerprint: String = ""
        ): LabelManifest {
            val model = android.os.Build.MODEL ?: "Android Device"
            val codename = android.os.Build.DEVICE ?: android.os.Build.PRODUCT ?: "unknown"
            val androidVer = android.os.Build.VERSION.RELEASE ?: "14"
            val friendlyName = displayName?.takeIf { it.isNotBlank() } ?: model
            val emptyPartitions = FIXED_KEYS.associateWith { PartitionPointer() }
            return LabelManifest(
                labelId = labelId,
                displayName = friendlyName,
                deviceModel = model,
                deviceCodename = codename,
                androidVersion = androidVer,
                rootActive = rootActive,
                isLocked = false,
                retentionCount = 2,
                cryptoKeyFingerprint = cryptoKeyFingerprint,
                updatedAt = System.currentTimeMillis(),
                totalBytes = 0L,
                packageCount = 0,
                partitions = emptyPartitions
            )
        }
    }
}

data class PartitionPointer(
    @SerializedName("itemCount") val itemCount: Int = 0,
    @SerializedName("sizeBytes") val sizeBytes: Long = 0L,
    @SerializedName("updatedAt") val updatedAt: Long = 0L,
    @SerializedName("sourceFileId") val sourceFileId: String = "",
    @SerializedName("sourceIndexFileId") val sourceIndexFileId: String = "",
    @SerializedName("sourceFileName") val sourceFileName: String = ""
)

enum class LabelDeleteScope {
    CLOUD_ONLY,
    DEVICE_ONLY,
    BOTH
}

