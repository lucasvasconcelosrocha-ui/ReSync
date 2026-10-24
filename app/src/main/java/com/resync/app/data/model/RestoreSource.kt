package com.resync.app.data.model

enum class RestoreSource(
    val key: String,
    val title: String,
    val tag: String
) {
    LOCAL("local", "Do dispositivo", "Do dispositivo"),
    DRIVE("drive", "Google Drive", "Do Google Drive");

    companion object {
        fun fromKey(key: String?): RestoreSource {
            return when (key?.lowercase()) {
                "drive", "cloud", "googledrive" -> DRIVE
                else -> LOCAL
            }
        }
    }
}
