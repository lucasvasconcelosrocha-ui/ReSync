package com.resync.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resync.app.data.model.BackupItemType
import com.resync.app.engine.IconCacheManager
import com.resync.app.ui.theme.DarkSurfaceVariant
import java.io.File

/**
 * Universal real app icon composable.
 * Uses IconCacheManager and native Android PackageManager.
 * Guarantees that the device's real application icon is always displayed.
 * Supports desaturation + discrete cloud badge for uninstalled apps.
 */
@Composable
fun RealAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    shapeRadius: Dp = 10.dp,
    containerFile: File? = null,
    remoteIconFetcher: (suspend (String) -> ByteArray?)? = null,
    password: String? = null,
    accountEmail: String? = null
) {
    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }

    val iconState = produceState<IconCacheManager.AppIconResult?>(
        initialValue = null,
        key1 = packageName,
        key2 = containerFile?.path
    ) {
        value = iconCache.getAppIconWithStatus(
            packageName = packageName,
            containerFile = containerFile,
            remoteIconFetcher = remoteIconFetcher,
            password = password,
            accountEmail = accountEmail
        )
    }

    val result = iconState.value
    val bitmap = result?.bitmap
    val isInstalled = result?.isInstalled ?: true

    Box(
        modifier = modifier.size(size)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(shapeRadius))
                .background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                val colorFilter = if (!isInstalled) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.45f) })
                } else null

                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    colorFilter = colorFilter,
                    alpha = if (!isInstalled) 0.85f else 1.0f,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(shapeRadius))
                )
            } else if (result != null) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = if (!isInstalled) Color.White.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.70f),
                    modifier = Modifier.size(size * 0.52f)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(size * 0.45f),
                    strokeWidth = 2.dp,
                    color = Color.White.copy(alpha = 0.25f)
                )
            }
        }

        // Discrete badge for uninstalled cloud/backup apps
        if (result != null && !isInstalled) {
            val badgeSize = (size * 0.42f).coerceAtLeast(14.dp)
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF38BDF8), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Na nuvem / Não instalado",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(badgeSize * 0.65f)
                )
            }
        }
    }
}

/**
 * Universal item icon & thumbnail composable.
 * Supports:
 * - Real application icon from PackageManager or backup container
 * - Image & video real thumbnail from MediaStore or local file
 * - Category vector icon fallback when thumbnail/app icon is unavailable
 */
@Composable
fun ItemThumbnailIcon(
    name: String,
    packageName: String = "",
    type: BackupItemType = BackupItemType.FILE,
    containerFile: File? = null,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shapeRadius: Dp = 10.dp,
    tintFallback: Color = Color.Unspecified
) {
    val context = LocalContext.current
    val iconCache = remember { IconCacheManager.getInstance(context) }

    val iconState = produceState<ImageBitmap?>(
        initialValue = null,
        key1 = name,
        key2 = packageName,
        key3 = containerFile?.path
    ) {
        value = iconCache.getItemIcon(name, packageName, type, containerFile)
    }

    val isApps = type == BackupItemType.APP_APK ||
            type == BackupItemType.APP_SPLIT_APKS ||
            type == BackupItemType.APP_DATA ||
            name.endsWith(".apk", ignoreCase = true) ||
            name.contains("(Base APK)", ignoreCase = true) ||
            name.contains("(Splits)", ignoreCase = true)

    val lowerName = name.lowercase().trim()
    val isImage = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp") || lowerName.endsWith(".bmp") || lowerName.endsWith(".gif") || lowerName.endsWith(".heic")
    val isVideo = lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") ||
            lowerName.endsWith(".3gp") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi")
    val isAudio = lowerName.endsWith(".mp3") || lowerName.endsWith(".flac") || lowerName.endsWith(".wav") ||
            lowerName.endsWith(".m4a") || lowerName.endsWith(".aac") || lowerName.endsWith(".ogg") || lowerName.endsWith(".wma")
    val isPdf = lowerName.endsWith(".pdf")
    val isZip = lowerName.endsWith(".zip") || lowerName.endsWith(".7z") || lowerName.endsWith(".rar") ||
            lowerName.endsWith(".tar") || lowerName.endsWith(".gz") || lowerName.endsWith(".resync") || lowerName.endsWith(".svb") || lowerName.endsWith(".bz2")
    val isApk = lowerName.endsWith(".apk")
    val isDoc = lowerName.endsWith(".doc") || lowerName.endsWith(".docx") || lowerName.endsWith(".txt") ||
            lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt") ||
            lowerName.endsWith(".pptx") || lowerName.endsWith(".csv") || lowerName.endsWith(".json") ||
            lowerName.endsWith(".xml") || lowerName.endsWith(".html") || lowerName.endsWith(".log")
    val isFolder = type == BackupItemType.FOLDER || (!name.contains(".") && !isApps && type == BackupItemType.FILE && !isImage && !isVideo && !isAudio)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(shapeRadius))
            .background(DarkSurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = iconState.value
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(shapeRadius))
            )
        } else {
            val (fallbackIcon, fallbackColor) = when {
                isApps || isApk -> Icons.Default.Android to (if (tintFallback != Color.Unspecified) tintFallback else Color(0xFF4CAF50))
                isImage -> Icons.Default.Image to Color(0xFF00C853)
                isVideo -> Icons.Default.Videocam to Color(0xFFFF5252)
                isAudio -> Icons.Default.MusicNote to Color(0xFFFF9100)
                isPdf -> Icons.Default.PictureAsPdf to Color(0xFFE53935)
                isZip -> Icons.Default.FolderZip to Color(0xFFFFB300)
                isDoc -> Icons.Default.Description to Color(0xFF29B6F6)
                isFolder -> Icons.Default.Folder to Color(0xFFFFAB00)
                type == BackupItemType.CONTACTS -> Icons.Default.Person to Color(0xFF00E5FF)
                type == BackupItemType.SMS -> Icons.Default.Message to Color(0xFFFFD600)
                type == BackupItemType.CALL_LOG -> Icons.Default.Phone to Color(0xFF76FF03)
                type == BackupItemType.WIFI_CONFIG -> Icons.Default.Wifi to Color(0xFF2979FF)
                type == BackupItemType.DNS_CONFIG -> Icons.Default.Dns to Color(0xFFAA00FF)
                type == BackupItemType.WALLPAPER -> Icons.Default.Wallpaper to Color(0xFFFF4081)
                type == BackupItemType.WALLPAPER_LOCK -> Icons.Default.Lock to Color(0xFFFF6D00)
                type == BackupItemType.BLUETOOTH -> Icons.Default.Bluetooth to Color(0xFF00B0FF)
                else -> Icons.Default.InsertDriveFile to (if (tintFallback != Color.Unspecified) tintFallback else Color.White.copy(alpha = 0.7f))
            }

            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = fallbackColor,
                modifier = Modifier.size(size * 0.52f)
            )
        }
    }
}

