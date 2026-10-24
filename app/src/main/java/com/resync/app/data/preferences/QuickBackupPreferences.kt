package com.resync.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.quickBackupDataStore: DataStore<Preferences> by preferencesDataStore(name = "quick_backup_selection")

data class QuickBackupSelection(
    // Aplicativos
    val includeApps: Boolean = true,
    val includeBaseApk: Boolean = true,
    val includeAppSplits: Boolean = true,
    val includeAppData: Boolean = true, // Marcado por padrão
    val includeExternalData: Boolean = false,
    val includeObb: Boolean = false,
    val includeAppPermissions: Boolean = true,

    // Sistema e comunicação
    val includeWifiNetwork: Boolean = true,
    val includeDns: Boolean = true,
    val includeSms: Boolean = true,
    val includeCalls: Boolean = false,
    val includeContacts: Boolean = true,
    val includeBluetooth: Boolean = false,
    val includeWallpaper: Boolean = true,
    val includeWallpaperHome: Boolean = true,
    val includeWallpaperLock: Boolean = true,

    // Arquivos e mídia
    val includeCustomFilesAndFolders: Boolean = true,
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = true,
    val includeDocuments: Boolean = true,
    val includeMusic: Boolean = false,
    val includeDownloads: Boolean = false,
    val includeScreenshots: Boolean = true
)

class QuickBackupPreferences(private val context: Context) {

    private object PreferencesKeys {
        val INCLUDE_APPS = booleanPreferencesKey("include_apps")
        val INCLUDE_BASE_APK = booleanPreferencesKey("include_base_apk")
        val INCLUDE_APP_SPLITS = booleanPreferencesKey("include_app_splits")
        val INCLUDE_APP_DATA = booleanPreferencesKey("include_app_data")
        val INCLUDE_EXTERNAL_DATA = booleanPreferencesKey("include_external_data")
        val INCLUDE_OBB = booleanPreferencesKey("include_obb")
        val INCLUDE_APP_PERMISSIONS = booleanPreferencesKey("include_app_permissions")

        val INCLUDE_WIFI_NETWORK = booleanPreferencesKey("include_wifi_network")
        val INCLUDE_DNS = booleanPreferencesKey("include_dns")
        val INCLUDE_SMS = booleanPreferencesKey("include_sms")
        val INCLUDE_CALLS = booleanPreferencesKey("include_calls")
        val INCLUDE_CONTACTS = booleanPreferencesKey("include_contacts")
        val INCLUDE_BLUETOOTH = booleanPreferencesKey("include_bluetooth")
        val INCLUDE_WALLPAPER = booleanPreferencesKey("include_wallpaper")
        val INCLUDE_WALLPAPER_HOME = booleanPreferencesKey("include_wallpaper_home")
        val INCLUDE_WALLPAPER_LOCK = booleanPreferencesKey("include_wallpaper_lock")

        val INCLUDE_CUSTOM_FILES_AND_FOLDERS = booleanPreferencesKey("include_custom_files_and_folders")
        val INCLUDE_PHOTOS = booleanPreferencesKey("include_photos")
        val INCLUDE_VIDEOS = booleanPreferencesKey("include_videos")
        val INCLUDE_DOCUMENTS = booleanPreferencesKey("include_documents")
        val INCLUDE_MUSIC = booleanPreferencesKey("include_music")
        val INCLUDE_DOWNLOADS = booleanPreferencesKey("include_downloads")
        val INCLUDE_SCREENSHOTS = booleanPreferencesKey("include_screenshots")
        val APP_TYPE_FILTER = stringPreferencesKey("app_type_filter")
    }

    val appTypeFilterFlow: Flow<String> = context.quickBackupDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[PreferencesKeys.APP_TYPE_FILTER] ?: "INSTALLED" }

    suspend fun saveAppTypeFilter(filter: String) {
        context.quickBackupDataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_TYPE_FILTER] = filter
        }
    }

    val selectionFlow: Flow<QuickBackupSelection> = context.quickBackupDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val legacyWallpaper = preferences[PreferencesKeys.INCLUDE_WALLPAPER] ?: true
            QuickBackupSelection(
                includeApps = preferences[PreferencesKeys.INCLUDE_APPS] ?: true,
                includeBaseApk = preferences[PreferencesKeys.INCLUDE_BASE_APK] ?: true,
                includeAppSplits = preferences[PreferencesKeys.INCLUDE_APP_SPLITS] ?: true,
                includeAppData = preferences[PreferencesKeys.INCLUDE_APP_DATA] ?: true,
                includeExternalData = preferences[PreferencesKeys.INCLUDE_EXTERNAL_DATA] ?: false,
                includeObb = preferences[PreferencesKeys.INCLUDE_OBB] ?: false,
                includeAppPermissions = preferences[PreferencesKeys.INCLUDE_APP_PERMISSIONS] ?: true,

                includeWifiNetwork = preferences[PreferencesKeys.INCLUDE_WIFI_NETWORK] ?: true,
                includeDns = preferences[PreferencesKeys.INCLUDE_DNS] ?: true,
                includeSms = preferences[PreferencesKeys.INCLUDE_SMS] ?: true,
                includeCalls = preferences[PreferencesKeys.INCLUDE_CALLS] ?: false,
                includeContacts = preferences[PreferencesKeys.INCLUDE_CONTACTS] ?: true,
                includeBluetooth = preferences[PreferencesKeys.INCLUDE_BLUETOOTH] ?: false,
                includeWallpaper = legacyWallpaper,
                includeWallpaperHome = preferences[PreferencesKeys.INCLUDE_WALLPAPER_HOME] ?: legacyWallpaper,
                includeWallpaperLock = preferences[PreferencesKeys.INCLUDE_WALLPAPER_LOCK] ?: legacyWallpaper,

                includeCustomFilesAndFolders = preferences[PreferencesKeys.INCLUDE_CUSTOM_FILES_AND_FOLDERS] ?: true,
                includePhotos = preferences[PreferencesKeys.INCLUDE_PHOTOS] ?: true,
                includeVideos = preferences[PreferencesKeys.INCLUDE_VIDEOS] ?: true,
                includeDocuments = preferences[PreferencesKeys.INCLUDE_DOCUMENTS] ?: true,
                includeMusic = preferences[PreferencesKeys.INCLUDE_MUSIC] ?: false,
                includeDownloads = preferences[PreferencesKeys.INCLUDE_DOWNLOADS] ?: false,
                includeScreenshots = preferences[PreferencesKeys.INCLUDE_SCREENSHOTS] ?: true
            )
        }

    suspend fun saveSelection(selection: QuickBackupSelection) {
        context.quickBackupDataStore.edit { preferences ->
            preferences[PreferencesKeys.INCLUDE_APPS] = selection.includeApps
            preferences[PreferencesKeys.INCLUDE_BASE_APK] = selection.includeBaseApk
            preferences[PreferencesKeys.INCLUDE_APP_SPLITS] = selection.includeAppSplits
            preferences[PreferencesKeys.INCLUDE_APP_DATA] = selection.includeAppData
            preferences[PreferencesKeys.INCLUDE_EXTERNAL_DATA] = selection.includeExternalData
            preferences[PreferencesKeys.INCLUDE_OBB] = selection.includeObb
            preferences[PreferencesKeys.INCLUDE_APP_PERMISSIONS] = selection.includeAppPermissions

            preferences[PreferencesKeys.INCLUDE_WIFI_NETWORK] = selection.includeWifiNetwork
            preferences[PreferencesKeys.INCLUDE_DNS] = selection.includeDns
            preferences[PreferencesKeys.INCLUDE_SMS] = selection.includeSms
            preferences[PreferencesKeys.INCLUDE_CALLS] = selection.includeCalls
            preferences[PreferencesKeys.INCLUDE_CONTACTS] = selection.includeContacts
            preferences[PreferencesKeys.INCLUDE_BLUETOOTH] = selection.includeBluetooth
            preferences[PreferencesKeys.INCLUDE_WALLPAPER] = selection.includeWallpaperHome || selection.includeWallpaperLock
            preferences[PreferencesKeys.INCLUDE_WALLPAPER_HOME] = selection.includeWallpaperHome
            preferences[PreferencesKeys.INCLUDE_WALLPAPER_LOCK] = selection.includeWallpaperLock

            preferences[PreferencesKeys.INCLUDE_CUSTOM_FILES_AND_FOLDERS] = selection.includeCustomFilesAndFolders
            preferences[PreferencesKeys.INCLUDE_PHOTOS] = selection.includePhotos
            preferences[PreferencesKeys.INCLUDE_VIDEOS] = selection.includeVideos
            preferences[PreferencesKeys.INCLUDE_DOCUMENTS] = selection.includeDocuments
            preferences[PreferencesKeys.INCLUDE_MUSIC] = selection.includeMusic
            preferences[PreferencesKeys.INCLUDE_DOWNLOADS] = selection.includeDownloads
            preferences[PreferencesKeys.INCLUDE_SCREENSHOTS] = selection.includeScreenshots
        }
    }
}

