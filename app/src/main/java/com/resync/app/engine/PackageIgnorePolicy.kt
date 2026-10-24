package com.resync.app.engine

/**
 * Centralized policy for identifying internal system packages,
 * runtime resource overlays (RRO), and packages whose application data
 * should be ignored during backup without counting as a failure.
 */
object PackageIgnorePolicy {

    /**
     * Exact package names that never contain relevant user backup data
     * and should never be saved or reported as failure.
     */
    val ALWAYS_IGNORE_DATA_PACKAGES: Set<String> = setOf(
        "com.android.sdksandbox",
        "com.google.android.adservices.api",
        "com.google.android.ext.adservices.api",
        "com.android.adservices.api"
    )

    /**
     * Package name prefixes that represent internal system services or sandboxes
     * without user-facing data.
     */
    val DATA_IGNORE_PREFIXES: List<String> = listOf(
        "com.google.android.adservices",
        "com.google.android.ext.adservices",
        "com.android.adservices",
        "android.auto_generated_rro_",
        "com.android.overlay"
    )

    /**
     * Content providers and system modules that, when empty (0 bytes / < 100 bytes),
     * must be ignored silently instead of failing.
     */
    val IGNORE_EMPTY_DATA_PREFIXES: List<String> = listOf(
        "com.android.providers."
    )

    /**
     * Checks if a package is a Runtime Resource Overlay (RRO).
     */
    fun isOverlayPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains(".overlay") ||
                lower.contains(".rro") ||
                lower.startsWith("android.auto_generated_rro_") ||
                lower.contains(".auto_generated_rro_")
    }

    /**
     * Identifies internal system packages (SDK Sandbox, AdServices, Overlays, etc.)
     * that should be hidden by default in UI selection lists and excluded from "Select All".
     */
    fun isInternalSystemPackage(packageName: String, isOverlay: Boolean = false): Boolean {
        if (isOverlay) return true
        val lower = packageName.lowercase()
        if (lower == "com.android.sdksandbox" || lower.contains(".sdksandbox")) return true
        if (DATA_IGNORE_PREFIXES.any { lower.startsWith(it) }) return true
        if (isOverlayPackage(lower)) return true
        return false
    }

    /**
     * Checks if data backup should be skipped/ignored unconditionally for this package.
     */
    fun isAlwaysIgnoredForData(packageName: String, isOverlay: Boolean = false): Boolean {
        if (isOverlay) return true
        val lower = packageName.lowercase()
        if (ALWAYS_IGNORE_DATA_PACKAGES.contains(lower)) return true
        if (lower.contains(".sdksandbox")) return true
        if (DATA_IGNORE_PREFIXES.any { lower.startsWith(it) }) return true
        if (isOverlayPackage(lower)) return true
        return false
    }

    /**
     * Determines whether an empty or inaccessible data directory (0 bytes / < 100 bytes)
     * should be treated as "Ignorado (sem dados)" rather than "Falha".
     *
     * Rule:
     * - Packages in the always-ignored list: true
     * - Packages starting with IGNORE_EMPTY_DATA_PREFIXES (e.g. com.android.providers.*): true
     * - System packages in general: true
     * - User applications: false (maintains failure)
     */
    fun shouldIgnoreIfEmptyData(packageName: String, isSystemApp: Boolean): Boolean {
        if (isAlwaysIgnoredForData(packageName)) return true
        val lower = packageName.lowercase()
        if (IGNORE_EMPTY_DATA_PREFIXES.any { lower.startsWith(it) }) return true
        if (isSystemApp) return true
        return false
    }
}
