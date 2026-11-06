package com.resync.app.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.resync.app.data.model.BackupItemType

/**
 * Gerenciador inteligente de permissões de backup.
 * Garante que apenas as permissões estritamente necessárias para as categorias selecionadas sejam requeridas.
 * Se uma permissão for negada pelo usuário, o aplicativo não trava e não bloqueia as demais categorias.
 */
class BackupPermissionManager(private val context: Context) {

    data class PermissionRequirement(
        val itemType: BackupItemType,
        val runtimePermissions: List<String>,
        val requiresSpecialStorage: Boolean = false,
        val requiresWriteSettings: Boolean = false,
        val isRootExclusive: Boolean = false
    )

    /**
     * Mapeia as categorias selecionadas para as permissões do sistema Android necessárias.
     */
    fun getRequiredPermissionsForTypes(types: Collection<BackupItemType>): List<String> {
        val permissions = mutableSetOf<String>()
        val apiLevel = Build.VERSION.SDK_INT

        for (type in types) {
            when (type) {
                BackupItemType.MEDIA_IMAGES -> {
                    if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                    } else {
                        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                BackupItemType.MEDIA_VIDEOS -> {
                    if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                    } else {
                        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                BackupItemType.MEDIA_AUDIO -> {
                    if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                    } else {
                        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }
                BackupItemType.CONTACTS -> {
                    permissions.add(Manifest.permission.READ_CONTACTS)
                }
                BackupItemType.CALENDAR -> {
                    permissions.add(Manifest.permission.READ_CALENDAR)
                }
                BackupItemType.SMS -> {
                    permissions.add(Manifest.permission.READ_SMS)
                }
                BackupItemType.CALL_LOG -> {
                    permissions.add(Manifest.permission.READ_CALL_LOG)
                }
                BackupItemType.BLUETOOTH -> {
                    if (apiLevel >= Build.VERSION_CODES.S) {
                        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
                else -> {
                    // Sem permissões de tempo de execução adicionais
                }
            }
        }

        // Filtra permissões que já foram concedidas
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Mapeia as permissões necessárias para restauração das categorias selecionadas.
     */
    fun getRequiredRestorePermissions(types: Collection<BackupItemType>): List<String> {
        val permissions = mutableSetOf<String>()
        val apiLevel = Build.VERSION.SDK_INT

        for (type in types) {
            when (type) {
                BackupItemType.CONTACTS -> {
                    permissions.add(Manifest.permission.WRITE_CONTACTS)
                }
                BackupItemType.CALENDAR -> {
                    permissions.add(Manifest.permission.WRITE_CALENDAR)
                }
                BackupItemType.CALL_LOG -> {
                    permissions.add(Manifest.permission.WRITE_CALL_LOG)
                }
                BackupItemType.MEDIA_IMAGES,
                BackupItemType.MEDIA_VIDEOS,
                BackupItemType.MEDIA_AUDIO,
                BackupItemType.FOLDER -> {
                    if (apiLevel < Build.VERSION_CODES.R) {
                        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                else -> {}
            }
        }

        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Verifica se o acesso especial All Files Access (MANAGE_EXTERNAL_STORAGE) é necessário e está ausente.
     */
    fun needsAllFilesAccess(types: Collection<BackupItemType>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val requiresFullStorage = types.contains(BackupItemType.FOLDER) || types.contains(BackupItemType.FILE)
        return requiresFullStorage && !Environment.isExternalStorageManager()
    }

    /**
     * Cria a Intent do sistema para abrir a tela de autorização de Todos os Arquivos.
     */
    fun createAllFilesAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * Cria Intent para abrir as Configurações de Aplicativo do sistema.
     */
    fun createAppSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Avalia se uma categoria específica pode ser processada com as permissões atuais.
     */
    fun canProcessCategory(type: BackupItemType, isRootGranted: Boolean): Boolean {
        if (type == BackupItemType.APP_DATA) {
            return isRootGranted
        }
        val missing = getRequiredPermissionsForTypes(listOf(type))
        return missing.isEmpty()
    }
}
