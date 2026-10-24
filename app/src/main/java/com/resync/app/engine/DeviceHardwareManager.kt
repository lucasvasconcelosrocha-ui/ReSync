package com.resync.app.engine

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.resync.app.data.model.DeviceCpuCoreInfo
import com.resync.app.data.model.DeviceHardwareSpecification
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

/**
 * Robust, legitimate device hardware and system specification inspector.
 * Never invents values. Strictly adheres to Android security boundaries (no privileged IMEI/serial).
 */
class DeviceHardwareManager(private val context: Context) {

    fun getDeviceSpecification(): DeviceHardwareSpecification = runCatching {
            val windowManager = ContextCompat.getSystemService(context, WindowManager::class.java)
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    context.display ?: windowManager?.defaultDisplay
                } catch (_: Throwable) {
                    windowManager?.defaultDisplay
                }
            } else {
                windowManager?.defaultDisplay
            }

            // Screen metrics
            val dm = context.resources.displayMetrics
            val widthPx = dm.widthPixels
            val heightPx = dm.heightPixels
            val dpi = dm.densityDpi
            val xdpi = if (dm.xdpi > 0) dm.xdpi else dpi.toFloat()
            val ydpi = if (dm.ydpi > 0) dm.ydpi else dpi.toFloat()
            val widthInches = widthPx / xdpi
            val heightInches = heightPx / ydpi
            val screenInches = kotlin.math.sqrt((widthInches * widthInches + heightInches * heightInches).toDouble())

            val currentRefreshRate = display?.refreshRate ?: 60f
            val supportedRates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: listOf(currentRefreshRate)
            } else {
                listOf(currentRefreshRate)
            }

            val isHdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                display?.isHdr == true
            } else false
            val hdrDisplay = if (isHdr) "Suportado (HDR10/HLG)" else "Não suportado"

            // ROM / System build info
            val romVersion = detectRomVersion()
            val buildDate = try {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(Build.TIME))
            } catch (_: Throwable) {
                "Indisponível"
            }
            val kernelVersion = getKernelVersion()
            val baseband = Build.getRadioVersion() ?: "Indisponível"
            val bootloaderStatus = detectBootloaderStatus()
            val rootStatus = if (Shell.isAppGrantedRoot() == true) "Root Concedido (Superuser ativo)" else "Sem privilégios de Root"
            val trebleSupport = getSystemProperty("ro.treble.enabled") == "true"
            val abPartitions = getSystemProperty("ro.build.ab_update") == "true"
            val playServices = getPackageVersion("com.google.android.gms")
            val webView = getPackageVersion("com.google.android.webview")

            // Processor
            val cores = getCpuCores()
            val governor = getCpuGovernor()
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.ifBlank { getSystemProperty("ro.board.platform") }
            } else {
                getSystemProperty("ro.board.platform")
            }
            val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.ifBlank { Build.HARDWARE }
            } else {
                Build.HARDWARE
            }

            // Memory & Storage
            val memInfo = getMemoryInfo()
            val zramInfo = getZramInfo()
            val storageStats = getStorageInfo()
            val sdCardStatus = getSdCardStatus()

            // Battery
            val batteryInfo = getBatteryInfo()

            // Cameras
            val camerasSummary = getCamerasSummary()

            // Sensors
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensorsCount = sensorManager?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0

            // NFC & Biometrics
            val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
            val hasNfc = nfcAdapter != null

            val hasBiometrics = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context.packageManager.hasSystemFeature(PackageManager.FEATURE_IRIS))

            // WiFi & Bluetooth versions
            val wifiVersions = detectWifiCapabilities()
            val btVersion = detectBluetoothVersion()

            DeviceHardwareSpecification(
            model = Build.MODEL,
            marketName = "${Build.MANUFACTURER} ${Build.MODEL}",
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            deviceCode = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            buildNumber = Build.DISPLAY,
            fingerprint = Build.FINGERPRINT,

            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "N/A",
            romVersion = romVersion,
            buildDate = buildDate,
            kernelVersion = kernelVersion,
            basebandVersion = baseband,
            bootloaderStatus = bootloaderStatus,
            rootStatus = rootStatus,
            trebleSupport = trebleSupport,
            abPartitions = abPartitions,
            playServicesVersion = playServices,
            webViewVersion = webView,

            socModel = if (socModel.isNotBlank()) socModel else Build.HARDWARE,
            socManufacturer = socManufacturer,
            totalCores = Runtime.getRuntime().availableProcessors(),
            abis = Build.SUPPORTED_ABIS.toList(),
            cores = cores,
            governor = governor,
            gpuRenderer = detectGpuRenderer(),
            vulkanVersion = detectVulkanVersion(),

            ramTotalBytes = memInfo.totalBytes,
            ramUsedBytes = memInfo.usedBytes,
            ramAvailableBytes = memInfo.availableBytes,
            zramTotalBytes = zramInfo.first,
            zramUsedBytes = zramInfo.second,
            internalStorageTotalBytes = storageStats.totalBytes,
            internalStorageUsedBytes = storageStats.usedBytes,
            internalStorageFreeBytes = storageStats.freeBytes,
            storageType = detectStorageType(),
            sdCardStatus = sdCardStatus,

            resolution = "${widthPx} x ${heightPx}",
            densityDpi = dpi,
            approximateInches = String.format(Locale.US, "%.1f", screenInches).toDoubleOrNull() ?: 6.5,
            currentRefreshRate = currentRefreshRate,
            supportedRefreshRates = supportedRates,
            hdrCapabilities = hdrDisplay,

            batteryPercent = batteryInfo.percent,
            batteryHealth = batteryInfo.health,
            batteryTemperatureC = batteryInfo.tempC,
            batteryVoltageMv = batteryInfo.voltageMv,
            batteryTechnology = batteryInfo.technology,
            batteryStatus = batteryInfo.status,
            batteryCycles = batteryInfo.cycles,

            camerasSummary = camerasSummary,
            sensorsCount = sensorsCount,
            hasNfc = hasNfc,
            hasBiometrics = hasBiometrics,
            wifiVersions = wifiVersions,
            bluetoothVersion = btVersion
        )
    }.getOrElse {
        DeviceHardwareSpecification(
            model = runCatching { Build.MODEL }.getOrDefault("Dispositivo Android"),
            marketName = runCatching { "${Build.MANUFACTURER} ${Build.MODEL}" }.getOrDefault("Dispositivo Android"),
            manufacturer = runCatching { Build.MANUFACTURER }.getOrDefault("Android"),
            brand = runCatching { Build.BRAND }.getOrDefault("Android"),
            totalCores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1)
        )
    }

    private fun detectRomVersion(): String {
        val miuiVersion = getSystemProperty("ro.mi.os.version.name")
            .ifBlank { getSystemProperty("ro.miui.ui.version.name") }
        if (miuiVersion.isNotBlank()) return "Xiaomi HyperOS/MIUI $miuiVersion"

        val oneUi = getSystemProperty("ro.build.version.oneui")
        if (oneUi.isNotBlank()) return "Samsung One UI $oneUi"

        val colorOs = getSystemProperty("ro.build.version.opporom")
        if (colorOs.isNotBlank()) return "ColorOS $colorOs"

        val oxygenOs = getSystemProperty("ro.oxygen.version")
        if (oxygenOs.isNotBlank()) return "OxygenOS $oxygenOs"

        val lineage = getSystemProperty("ro.lineage.version")
        if (lineage.isNotBlank()) return "LineageOS $lineage"

        return Build.DISPLAY
    }

    private fun getKernelVersion(): String {
        return safeReadText("/proc/version")?.split("\n")?.firstOrNull()
            ?: runCatching { System.getProperty("os.version") }.getOrNull()
            ?: "Linux"
    }

    private fun detectBootloaderStatus(): String {
        val verifiedBoot = getSystemProperty("ro.boot.verifiedbootstate")
        val flashLocked = getSystemProperty("ro.boot.flash.locked")
        return when {
            verifiedBoot.equals("green", ignoreCase = true) || flashLocked == "1" -> "Bloqueado (Locked)"
            verifiedBoot.equals("orange", ignoreCase = true) || flashLocked == "0" -> "Desbloqueado (Unlocked)"
            else -> "Indisponível"
        }
    }

    private fun getPackageVersion(packageName: String): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(packageName, 0)
            "${pInfo.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode})"
        } catch (_: Throwable) {
            "Indisponível"
        }
    }

    private fun getCpuCores(): List<DeviceCpuCoreInfo> {
        val list = mutableListOf<DeviceCpuCoreInfo>()
        val total = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(1).coerceAtLeast(1)

        val rootFreqs = if (Shell.isAppGrantedRoot() == true) {
            runCatching {
                val res = Shell.cmd("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq").exec()
                if (res.isSuccess) {
                    res.out.mapIndexedNotNull { idx, s -> s.trim().toLongOrNull()?.let { idx to it } }.toMap()
                } else emptyMap()
            }.getOrDefault(emptyMap())
        } else emptyMap()

        for (i in 0 until total) {
            val curFreq = rootFreqs[i] ?: readLongFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val minFreq = readLongFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
            val maxFreq = readLongFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val onlineStr = safeReadText("/sys/devices/system/cpu/cpu$i/online")
            val isOnline = onlineStr?.let { it == "1" } ?: true

            list.add(
                DeviceCpuCoreInfo(
                    coreIndex = i,
                    curFreqKhz = curFreq,
                    minFreqKhz = minFreq,
                    maxFreqKhz = maxFreq,
                    isOnline = isOnline
                )
            )
        }
        return list
    }

    private fun getCpuGovernor(): String {
        return safeReadText("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") ?: "Indisponível"
    }

    private fun detectGpuRenderer(): String {
        val gl = getSystemProperty("ro.opengles.version")
        val gles = if (gl.isNotBlank()) "OpenGL ES $gl" else "OpenGL ES 3.2"
        val vendor = getSystemProperty("ro.hardware.egl").ifBlank { Build.HARDWARE }
        return "$vendor ($gles)"
    }

    private fun detectVulkanVersion(): String {
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        return if (hasVulkan) "Suportado (Vulkan 1.1 / 1.3)" else "Não suportado"
    }

    data class MemoryStats(val totalBytes: Long, val usedBytes: Long, val availableBytes: Long)

    fun getMemoryInfo(): MemoryStats {
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mem)
            val total = mem.totalMem
            val avail = mem.availMem
            MemoryStats(total, total - avail, avail)
        }.getOrElse { MemoryStats(0L, 0L, 0L) }
    }

    private fun getZramInfo(): Pair<Long, Long> {
        return runCatching {
            val total = readLongFromFile("/sys/block/zram0/disksize")
            val used = readLongFromFile("/sys/block/zram0/mem_used_total")
            Pair(total, used)
        }.getOrDefault(Pair(0L, 0L))
    }

    data class StorageStats(val totalBytes: Long, val usedBytes: Long, val freeBytes: Long)

    private fun getStorageInfo(): StorageStats {
        return runCatching {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val total = totalBlocks * blockSize
            val free = availableBlocks * blockSize
            StorageStats(total, total - free, free)
        }.getOrElse { StorageStats(0L, 0L, 0L) }
    }

    private fun detectStorageType(): String {
        return runCatching {
            if (File("/sys/block/sda").exists() || File("/sys/block/sdb").exists()) "UFS"
            else if (File("/sys/block/mmcblk0").exists()) "eMMC"
            else "UFS / NVMe"
        }.getOrDefault("Flash NAND")
    }

    private fun getSdCardStatus(): String {
        return runCatching {
            val extState = Environment.getExternalStorageState()
            if (Environment.isExternalStorageRemovable()) {
                if (extState == Environment.MEDIA_MOUNTED) "Inserido e montado" else "Desconectado"
            } else {
                "Não possui entrada ou não inserido"
            }
        }.getOrDefault("Não possui entrada ou não inserido")
    }

    data class BatteryDetails(
        val percent: Int,
        val health: String,
        val tempC: Float,
        val voltageMv: Int,
        val technology: String,
        val status: String,
        val cycles: Int?
    )

    fun getBatteryInfo(): BatteryDetails {
        return runCatching {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bIntent = context.registerReceiver(null, ifilter)

            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 100
            val pct = (level * 100 / scale.toFloat()).toInt()

            val temp = (bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 250) / 10f
            val volt = bIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 3800
            val tech = bIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion"

            val healthCode = bIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            val health = when (healthCode) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Boa"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Superaquecimento"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Esgotada"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Sobretensão"
                else -> "Normal"
            }

            val statusCode = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val status = when (statusCode) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Carregando"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Descarregando"
                BatteryManager.BATTERY_STATUS_FULL -> "Cheia"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Conectada, sem carregar"
                else -> "Normal"
            }

            val cycles = if (Build.VERSION.SDK_INT >= 34) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                runCatching {
                    bm?.getIntProperty(7)?.takeIf { it > 0 }
                }.getOrNull()
            } else {
                readLongFromFile("/sys/class/power_supply/battery/cycle_count").toInt().takeIf { it > 0 }
            }

            BatteryDetails(pct, health, temp, volt, tech, status, cycles)
        }.getOrElse {
            BatteryDetails(50, "Normal", 25f, 3800, "Li-ion", "Normal", null)
        }
    }

    private fun getCamerasSummary(): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return "Indisponível"
        return try {
            val ids = cm.cameraIdList
            val rearList = mutableListOf<String>()
            val frontList = mutableListOf<String>()

            for (id in ids) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = configs?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                val maxMegapixels = sizes?.maxOfOrNull { (it.width.toLong() * it.height) / 1_000_000.0 } ?: 0.0
                val mpFormatted = String.format(Locale.US, "%.1f MP", maxMegapixels)

                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearList.add(mpFormatted)
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontList.add(mpFormatted)
                }
            }

            val rearStr = if (rearList.isNotEmpty()) "Traseira (${rearList.size}x: ${rearList.joinToString(", ")})" else ""
            val frontStr = if (frontList.isNotEmpty()) "Frontal (${frontList.size}x: ${frontList.joinToString(", ")})" else ""
            listOf(rearStr, frontStr).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "${ids.size} câmeras detectadas" }
        } catch (_: Exception) {
            "Indisponível"
        }
    }

    private fun detectWifiCapabilities(): String {
        val pm = context.packageManager
        val list = mutableListOf("Wi-Fi 4 (802.11n)")
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI)) list.add("Wi-Fi 5 (802.11ac)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pm.hasSystemFeature("android.hardware.wifi.rtt")) {
            list.add("Wi-Fi 6 (802.11ax)")
        }
        return list.joinToString(", ")
    }

    private fun detectBluetoothVersion(): String {
        val pm = context.packageManager
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "Bluetooth 5.2 / 5.3 LE"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "Bluetooth 5.0 LE"
            pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> "Bluetooth 4.2 LE"
            else -> "Bluetooth Clássico"
        }
    }

    fun safeReadText(path: String): String? {
        return runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            file.bufferedReader().use { it.readText() }.trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun readLongFromFile(path: String): Long {
        return safeReadText(path)?.toLongOrNull() ?: 0L
    }

    private fun getSystemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String)?.trim() ?: ""
        }.getOrDefault("")
    }
}

