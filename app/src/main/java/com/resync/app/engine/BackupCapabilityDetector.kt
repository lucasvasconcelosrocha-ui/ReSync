package com.resync.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.resync.app.data.model.BackupItemType
import com.topjohnwu.superuser.Shell

/**
 * Detector Universal de Capacidades e Níveis de Execução (Root vs Sem Root).
 * Avalia de forma segura, passiva e transparente os recursos suportados pelo dispositivo
 * de acordo com o nível da API do Android (13 a 17+), permissões concedidas e nível de privilégio.
 *
 * REGRA ABSOLUTA: A inicialização e avaliação passiva NUNCA disparam pop-up de Superusuário.
 */
class BackupCapabilityDetector(private val context: Context) {

    enum class CapabilityStatus {
        AVAILABLE,
        PERMISSION_REQUIRED,
        PARTIALLY_AVAILABLE,
        ROOT_ONLY,
        UNSUPPORTED
    }

    enum class BackupLevel {
        FULL,
        PARTIAL,
        MANUAL_EXPORT,
        ROOT_ONLY,
        NONE
    }

    enum class RestoreLevel {
        FULL,
        PARTIAL,
        SYSTEM_INTENT,
        ROOT_ONLY,
        NONE
    }

    data class CategoryCapability(
        val itemType: BackupItemType,
        val displayName: String,
        val status: CapabilityStatus,
        val apiUsed: String,
        val permissionNeeded: String,
        val backupLevel: BackupLevel,
        val restoreLevel: RestoreLevel,
        val limitationsDescription: String
    )

    data class CapabilityMatrix(
        val androidVersion: String,
        val apiLevel: Int,
        val manufacturer: String,
        val model: String,
        val supportedAbis: List<String>,
        val isRootActive: Boolean,
        val hasSuBinary: Boolean,
        val rootManagerInstalled: String?,
        val categories: Map<BackupItemType, CategoryCapability>
    ) {
        val totalAvailableCount: Int
            get() = categories.values.count { it.status == CapabilityStatus.AVAILABLE || it.status == CapabilityStatus.PARTIALLY_AVAILABLE }

        val rootOnlyCount: Int
            get() = categories.values.count { it.status == CapabilityStatus.ROOT_ONLY }
    }

    private val capabilityManager = CapabilityManager(context)

    /**
     * Deteção de root passiva e não intrusiva.
     * Retorna true apenas se libsu já tiver autorização concedida sem executar "su" interativo.
     */
    fun isRootGrantedPassively(): Boolean {
        return try {
            Shell.isAppGrantedRoot() == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkPerm(perm: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Avalia e gera a Matriz Real de Capacidades do dispositivo atual.
     * @param isRootExplicitlyGranted Se o usuário já concedeu privilégio root explicitamente na sessão.
     */
    fun evaluateCapabilities(isRootExplicitlyGranted: Boolean = false): CapabilityMatrix {
        val rootActive = isRootExplicitlyGranted || isRootGrantedPassively()
        val hasSu = capabilityManager.hasSuBinary()
        val rootManager = capabilityManager.getInstalledRootManagerPackage()

        val apiLevel = Build.VERSION.SDK_INT
        val androidVersion = Build.VERSION.RELEASE ?: "Unknown"
        val manufacturer = Build.MANUFACTURER ?: "Generic"
        val model = Build.MODEL ?: "Device"
        val abis = Build.SUPPORTED_ABIS.toList()

        val hasAllFiles = if (apiLevel >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasImagesPerm = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.READ_MEDIA_IMAGES) || hasAllFiles
        } else {
            hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasVideoPerm = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.READ_MEDIA_VIDEO) || hasAllFiles
        } else {
            hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasAudioPerm = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            checkPerm(Manifest.permission.READ_MEDIA_AUDIO) || hasAllFiles
        } else {
            hasAllFiles || checkPerm(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasContactsRead = checkPerm(Manifest.permission.READ_CONTACTS)
        val hasContactsWrite = checkPerm(Manifest.permission.WRITE_CONTACTS)

        val hasCalendarRead = checkPerm(Manifest.permission.READ_CALENDAR)
        val hasCalendarWrite = checkPerm(Manifest.permission.WRITE_CALENDAR)

        val hasSmsPerm = checkPerm(Manifest.permission.READ_SMS)
        val hasCallsPerm = checkPerm(Manifest.permission.READ_CALL_LOG)

        val persistedSafCount = try {
            context.contentResolver.persistedUriPermissions.size
        } catch (_: Throwable) {
            0
        }

        val categories = mutableMapOf<BackupItemType, CategoryCapability>()

        // 1. Fotos e Imagens
        categories[BackupItemType.MEDIA_IMAGES] = CategoryCapability(
            itemType = BackupItemType.MEDIA_IMAGES,
            displayName = "Fotos e Imagens",
            status = if (hasImagesPerm) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "MediaStore.Images.Media / Storage Access Framework",
            permissionNeeded = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) "READ_MEDIA_IMAGES" else "READ_EXTERNAL_STORAGE",
            backupLevel = BackupLevel.FULL,
            restoreLevel = BackupLevel.FULL.let { RestoreLevel.FULL },
            limitationsDescription = "Preserva EXIF, resolução e metadados. Mídias ocultas ou privadas de outros apps requerem seleção manual via SAF."
        )

        // 2. Vídeos
        categories[BackupItemType.MEDIA_VIDEOS] = CategoryCapability(
            itemType = BackupItemType.MEDIA_VIDEOS,
            displayName = "Vídeos",
            status = if (hasVideoPerm) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "MediaStore.Video.Media / Streaming SAF",
            permissionNeeded = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) "READ_MEDIA_VIDEO" else "READ_EXTERNAL_STORAGE",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.FULL,
            limitationsDescription = "Transmitido via streaming contínuo sem estouro de RAM. Vídeos em pastas privadas requerem autorização SAF."
        )

        // 3. Músicas e Áudio
        categories[BackupItemType.MEDIA_AUDIO] = CategoryCapability(
            itemType = BackupItemType.MEDIA_AUDIO,
            displayName = "Músicas e Áudios",
            status = if (hasAudioPerm) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "MediaStore.Audio.Media / ContentResolver",
            permissionNeeded = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) "READ_MEDIA_AUDIO" else "READ_EXTERNAL_STORAGE",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.FULL,
            limitationsDescription = "Preserva tags ID3 (artista, álbum, duração). Arquivos de cache de streaming de terceiros não são acessíveis."
        )

        // 4. Documentos e Pastas
        categories[BackupItemType.FOLDER] = CategoryCapability(
            itemType = BackupItemType.FOLDER,
            displayName = "Documentos e Pastas",
            status = if (hasAllFiles || persistedSafCount > 0) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "DocumentsContract / DocumentFile / ACTION_OPEN_DOCUMENT_TREE",
            permissionNeeded = "MANAGE_EXTERNAL_STORAGE ou Permissão de Pasta SAF",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.FULL,
            limitationsDescription = "Pastas do sistema Android/data e Android/obb são restritas no Android 11+ sem root."
        )

        // 5. Contatos
        categories[BackupItemType.CONTACTS] = CategoryCapability(
            itemType = BackupItemType.CONTACTS,
            displayName = "Contatos",
            status = if (hasContactsRead) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "ContactsContract (RawContacts & Data)",
            permissionNeeded = "READ_CONTACTS (escrita requer WRITE_CONTACTS)",
            backupLevel = BackupLevel.FULL,
            restoreLevel = if (hasContactsWrite) RestoreLevel.FULL else RestoreLevel.PARTIAL,
            limitationsDescription = "Exporta em formatos vCard (.vcf) e JSON estruturado com prevenção rigorosa de duplicatas."
        )

        // 6. Calendário e Eventos
        categories[BackupItemType.CALENDAR] = CategoryCapability(
            itemType = BackupItemType.CALENDAR,
            displayName = "Calendário e Eventos",
            status = if (hasCalendarRead) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "CalendarContract (Events & Reminders)",
            permissionNeeded = "READ_CALENDAR (escrita requer WRITE_CALENDAR)",
            backupLevel = BackupLevel.FULL,
            restoreLevel = if (hasCalendarWrite) RestoreLevel.FULL else RestoreLevel.PARTIAL,
            limitationsDescription = "Exporta formato iCalendar (.ics) e JSON estruturado. Restauração insere em calendário gravável do usuário."
        )

        // 7. Aplicativos (APKs e Split APKs)
        categories[BackupItemType.APP_APK] = CategoryCapability(
            itemType = BackupItemType.APP_APK,
            displayName = "Aplicativos (APKs)",
            status = CapabilityStatus.AVAILABLE,
            apiUsed = "PackageManager (getApplicationInfo, splitPublicSourceDirs)",
            permissionNeeded = "Nenhuma permissão especial necessária para leitura de pacotes públicos",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.SYSTEM_INTENT,
            limitationsDescription = "Extrai APK base e split APKs completos. Sem Root NÃO inclui dados internos (/data/data). Reinstalação requer confirmação do usuário."
        )

        // 8. Mensagens SMS
        categories[BackupItemType.SMS] = CategoryCapability(
            itemType = BackupItemType.SMS,
            displayName = "Mensagens SMS",
            status = if (hasSmsPerm) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "Telephony.Sms / ContentResolver",
            permissionNeeded = "READ_SMS",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.PARTIAL,
            limitationsDescription = "Leitura e backup completos em JSON estruturado. A gravação no Android exige papel de app SMS padrão."
        )

        // 9. Histórico de Chamadas
        categories[BackupItemType.CALL_LOG] = CategoryCapability(
            itemType = BackupItemType.CALL_LOG,
            displayName = "Histórico de Chamadas",
            status = if (hasCallsPerm) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
            apiUsed = "CallLog.Calls / ContentResolver",
            permissionNeeded = "READ_CALL_LOG & WRITE_CALL_LOG",
            backupLevel = BackupLevel.FULL,
            restoreLevel = RestoreLevel.FULL,
            limitationsDescription = "Preserva número, data, duração e tipo de chamada com deduplicação automática na restauração."
        )

        // 10. Configurações do Sistema
        categories[BackupItemType.SYSTEM_SETTINGS] = CategoryCapability(
            itemType = BackupItemType.SYSTEM_SETTINGS,
            displayName = "Configurações do Sistema",
            status = CapabilityStatus.PARTIALLY_AVAILABLE,
            apiUsed = "Settings.System / Settings.Global",
            permissionNeeded = "WRITE_SETTINGS (para configurações permitidas)",
            backupLevel = BackupLevel.PARTIAL,
            restoreLevel = RestoreLevel.PARTIAL,
            limitationsDescription = "Configurações públicas (ex: toque, brilho automático, timeout) são salvas. Configurações de segurança exigem ajuste manual."
        )

        // 11. Backups Externos (WhatsApp, Signal, exportações de terceiros)
        categories[BackupItemType.EXTERNAL_BACKUP] = CategoryCapability(
            itemType = BackupItemType.EXTERNAL_BACKUP,
            displayName = "Backups Externos de Apps",
            status = CapabilityStatus.AVAILABLE,
            apiUsed = "ACTION_SEND / ACTION_SEND_MULTIPLE / SAF Seletor",
            permissionNeeded = "Nenhuma (opera via Intent compartilhado ou SAF)",
            backupLevel = BackupLevel.MANUAL_EXPORT,
            restoreLevel = RestoreLevel.PARTIAL,
            limitationsDescription = "Importa pacotes de exportação oficiais gerados por apps de terceiros e sincroniza com o Google Drive de forma segura."
        )

        // 12. Dados Privados de Apps (/data/data) - Apenas Root
        categories[BackupItemType.APP_DATA] = CategoryCapability(
            itemType = BackupItemType.APP_DATA,
            displayName = "Dados de Apps (/data/data)",
            status = if (rootActive) CapabilityStatus.AVAILABLE else CapabilityStatus.ROOT_ONLY,
            apiUsed = "libsu / Magisk / KernelSU (tar.zst + chown/restorecon)",
            permissionNeeded = "Privilégios de Superusuário (Root)",
            backupLevel = if (rootActive) BackupLevel.FULL else BackupLevel.ROOT_ONLY,
            restoreLevel = if (rootActive) RestoreLevel.FULL else RestoreLevel.ROOT_ONLY,
            limitationsDescription = if (rootActive) {
                "Acesso total a bancos de dados SQLite, SharedPreferences e arquivos privados com restauração atômica de UID/GID e SELinux."
            } else {
                "Bloqueado pelas políticas de segurança do kernel Linux e SELinux. Requer dispositivo rooteado com autorização concedida."
            }
        )

        return CapabilityMatrix(
            androidVersion = androidVersion,
            apiLevel = apiLevel,
            manufacturer = manufacturer,
            model = model,
            supportedAbis = abis,
            isRootActive = rootActive,
            hasSuBinary = hasSu,
            rootManagerInstalled = rootManager,
            categories = categories
        )
    }
}
