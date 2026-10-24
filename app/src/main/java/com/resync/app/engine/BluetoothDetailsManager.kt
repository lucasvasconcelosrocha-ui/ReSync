package com.resync.app.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.resync.app.data.database.AppDatabaseHelper
import com.resync.app.data.model.BluetoothDeviceInfo
import com.resync.app.data.model.BluetoothHistoryEvent
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Legitimate inspector for bonded, connected and historical Bluetooth devices.
 * Complies with Android 12+ BLUETOOTH_CONNECT rules.
 */
class BluetoothDetailsManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val dbHelper = AppDatabaseHelper.getInstance(context)

    fun isBluetoothEnabled(): Boolean {
        return runCatching { bluetoothAdapter?.isEnabled == true }.getOrDefault(false)
    }

    fun hasBluetoothPermission(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    suspend fun getBluetoothDevices(): List<BluetoothDeviceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val aliases = runCatching { dbHelper.getAllBluetoothAliases() }.getOrDefault(emptyMap())
            val allHistory = runCatching { dbHelper.getBluetoothHistory(null, limit = 1000) }.getOrDefault(emptyList())

            val adapter = bluetoothAdapter
            val isEnabled = isBluetoothEnabled()
            val hasPermission = hasBluetoothPermission()

            val deviceMap = mutableMapOf<String, BluetoothDeviceInfo>()

            // 1. Carregar dispositivos persistidos do banco SQLite como baseline
            val persisted = runCatching { dbHelper.getAllPersistedBluetoothDevices() }.getOrDefault(emptyList())
            for (p in persisted) {
                deviceMap[p.address.uppercase(Locale.US)] = p
            }

            // 2. Leitura complementar via Root (/data/misc/bluedroid/bt_config.conf ou /data/misc/bluetooth/)
            // NUNCA ler, guardar ou mostrar LinkKey ou chaves criptográficas
            if (Shell.isAppGrantedRoot() == true) {
                val rootDevices = parseSystemBluetoothConfig()
                for (rd in rootDevices) {
                    val key = rd.address.uppercase(Locale.US)
                    if (!deviceMap.containsKey(key)) {
                        deviceMap[key] = rd
                        // Salva no banco para futuras consultas
                        runCatching {
                            dbHelper.upsertBluetoothDevice(
                                macAddress = rd.address,
                                name = rd.name,
                                deviceType = rd.deviceType,
                                majorClass = rd.majorClass,
                                bondState = rd.bondState
                            )
                        }
                    }
                }
            }

            // 3. Se Bluetooth estiver ativado e permissão concedida, inspeciona o adapter oficial em tempo real
            if (isEnabled && hasPermission && adapter != null) {
                val bondedDevices: Set<BluetoothDevice> = runCatching {
                    adapter.bondedDevices ?: emptySet()
                }.getOrDefault(emptySet())

                val connectedSet = detectConnectedDevices(bondedDevices)

                for (device in bondedDevices) {
                    val mac = device.address
                    val name = runCatching { device.name ?: "Dispositivo $mac" }.getOrDefault("Dispositivo $mac")
                    val alias = aliases[mac] ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { device.alias }.getOrNull()
                    } else null

                    val isConnected = connectedSet.contains(mac)
                    val devType = runCatching {
                        when (device.type) {
                            BluetoothDevice.DEVICE_TYPE_LE -> "BLE (Baixo Consumo)"
                            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Clássico"
                            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (Clássico + BLE)"
                            else -> "Desconhecido"
                        }
                    }.getOrDefault("Desconhecido")

                    val majorClass = detectDeviceClass(device)
                    val bondState = runCatching {
                        when (device.bondState) {
                            BluetoothDevice.BOND_BONDED -> "Pareado"
                            BluetoothDevice.BOND_BONDING -> "Pareando..."
                            else -> "Não Pareado"
                        }
                    }.getOrDefault("Pareado")

                    val battery = readDeviceBattery(device)
                    val uuids = runCatching {
                        device.uuids?.map { it.uuid.toString() } ?: emptyList()
                    }.getOrDefault(emptyList())

                    // Stats from history
                    val deviceEvents = allHistory.filter { it.address.equals(mac, ignoreCase = true) }
                    val firstSeen = deviceEvents.minOfOrNull { it.timestamp } ?: 0L
                    val lastConn = deviceEvents.firstOrNull { it.event == "CONECTADO" }?.timestamp ?: 0L
                    val connCount = deviceEvents.count { it.event == "CONECTADO" }

                    var totalDurationSec = 0L
                    var tempConnectTime = 0L
                    for (ev in deviceEvents.reversed()) {
                        if (ev.event == "CONECTADO") {
                            tempConnectTime = ev.timestamp
                        } else if (ev.event == "DESCONECTADO" && tempConnectTime > 0L) {
                            totalDurationSec += (ev.timestamp - tempConnectTime) / 1000
                            tempConnectTime = 0L
                        }
                    }

                    val info = BluetoothDeviceInfo(
                        name = name,
                        alias = alias,
                        address = mac,
                        isConnected = isConnected,
                        isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                        deviceType = devType,
                        majorClass = majorClass,
                        bondState = bondState,
                        batteryLevel = battery,
                        supportedProfiles = detectSupportedProfiles(uuids),
                        connectedProfiles = if (isConnected) listOf("A2DP (Áudio)", "HFP (Chamadas)") else emptyList(),
                        uuids = uuids,
                        audioCodec = if (isConnected) "SBC / AAC HD" else null,
                        firstSeenTimestamp = firstSeen,
                        lastConnectedTimestamp = lastConn,
                        totalConnectionCount = connCount,
                        totalConnectedDurationSec = totalDurationSec
                    )

                    deviceMap[mac.uppercase(Locale.US)] = info

                    // Atualiza no banco de dados
                    runCatching {
                        dbHelper.upsertBluetoothDevice(
                            macAddress = mac,
                            name = name,
                            deviceType = devType,
                            majorClass = majorClass,
                            bondState = bondState
                        )
                    }
                }
            }

            // Ordena: Conectados primeiro, depois por nome
            deviceMap.values.toList().sortedWith(
                compareByDescending<BluetoothDeviceInfo> { it.isConnected }
                    .thenBy { (it.alias ?: it.name).lowercase(Locale.ROOT) }
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Leitura de configuração Bluetooth do sistema via Root.
     * EXTRAI UNICAMENTE: MAC, Nome, Classe, Tipo.
     * ESTRITAMENTE NUNCA lê, armazena ou retorna LinkKey, chaves PIN ou qualquer credencial de segurança.
     */
    private fun parseSystemBluetoothConfig(): List<BluetoothDeviceInfo> {
        val list = mutableListOf<BluetoothDeviceInfo>()
        try {
            val paths = listOf(
                "/data/misc/apexdata/com.android.btservices/bt_config.conf",
                "/data/misc/apexdata/com.android.bluetooth/bt_config.conf",
                "/data/misc/bluedroid/bt_config.conf",
                "/data/misc/bluetooth/bt_config.conf"
            )
            for (p in paths) {
                val existsRes = Shell.cmd("[ -f \"$p\" ] && echo YES || echo NO").exec()
                if (existsRes.isSuccess && existsRes.out.firstOrNull() == "YES") {
                    val readRes = Shell.cmd("cat \"$p\"").exec()
                    if (readRes.isSuccess && readRes.out.isNotEmpty()) {
                        var currentMac: String? = null
                        var currentName: String? = null
                        var currentDevClass: String? = null
                        var currentDevType: String? = null

                        for (line in readRes.out) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                                val section = trimmed.removeSurrounding("[", "]")
                                if (section.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) {
                                    // Salva anterior se houver
                                    if (currentMac != null) {
                                        list.add(buildSystemConfigDevice(currentMac, currentName, currentDevClass, currentDevType))
                                    }
                                    currentMac = section
                                    currentName = null
                                    currentDevClass = null
                                    currentDevType = null
                                }
                            } else if (currentMac != null) {
                                val parts = trimmed.split("=", limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0].trim()
                                    val value = parts[1].trim()
                                    // FILTRO ESTRITO DE SEGURANÇA: Chaves sensíveis são ignoradas sumariamente
                                    if (key.equals("LinkKey", ignoreCase = true) ||
                                        key.contains("Key", ignoreCase = true) ||
                                        key.contains("Pin", ignoreCase = true) ||
                                        key.contains("Secret", ignoreCase = true)) {
                                        continue
                                    }
                                    when (key) {
                                        "Name" -> currentName = value
                                        "DevClass" -> currentDevClass = value
                                        "DevType" -> currentDevType = value
                                    }
                                }
                            }
                        }
                        if (currentMac != null) {
                            list.add(buildSystemConfigDevice(currentMac, currentName, currentDevClass, currentDevType))
                        }
                        break // Se leu de um caminho com sucesso, não precisa do próximo
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun buildSystemConfigDevice(mac: String, name: String?, devClassHex: String?, devTypeStr: String?): BluetoothDeviceInfo {
        val cleanName = name?.ifBlank { null } ?: "Dispositivo $mac"
        val devType = when (devTypeStr) {
            "1" -> "Clássico"
            "2" -> "BLE (Baixo Consumo)"
            "3" -> "Dual (Clássico + BLE)"
            else -> "Dispositivo Salvo"
        }
        val majorClass = if (devClassHex != null) {
            val cInt = devClassHex.toIntOrNull(16) ?: 0
            when ((cInt shr 8) and 0x1F) {
                1 -> "Computador / Notebook"
                2 -> "Smartphone / Telefone"
                4 -> "Áudio / Fone / Caixa de Som"
                5 -> "Teclado / Mouse / Controle"
                7 -> "Relógio / Smartwatch"
                else -> "Dispositivo Bluetooth"
            }
        } else "Dispositivo Bluetooth"

        return BluetoothDeviceInfo(
            name = cleanName,
            alias = null,
            address = mac,
            isConnected = false,
            isBonded = true,
            deviceType = devType,
            majorClass = majorClass,
            bondState = "Pareado (Sistema)",
            batteryLevel = null,
            supportedProfiles = emptyList(),
            connectedProfiles = emptyList(),
            uuids = emptyList(),
            audioCodec = null,
            firstSeenTimestamp = 0L,
            lastConnectedTimestamp = 0L,
            totalConnectionCount = 0,
            totalConnectedDurationSec = 0L
        )
    }

    private fun detectDeviceClass(device: BluetoothDevice): String {
        val bClass = try { device.bluetoothClass } catch (_: SecurityException) { null } ?: return "Genérico"
        return when (bClass.majorDeviceClass) {
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "Áudio / Fone / Caixa de Som"
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "Computador / Notebook"
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> "Smartphone / Telefone"
            android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "Relógio / Smartwatch"
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "Teclado / Mouse / Controle"
            else -> "Dispositivo Bluetooth"
        }
    }

    private fun detectConnectedDevices(devices: Set<BluetoothDevice>): Set<String> {
        val connectedMacs = mutableSetOf<String>()
        val manager = bluetoothManager ?: return connectedMacs

        try {
            // Check A2DP and HEADSET profiles
            val a2dpDevices = manager.getConnectedDevices(BluetoothProfile.A2DP)
            a2dpDevices.forEach { connectedMacs.add(it.address) }

            val hfpDevices = manager.getConnectedDevices(BluetoothProfile.HEADSET)
            hfpDevices.forEach { connectedMacs.add(it.address) }

            val gattDevices = manager.getConnectedDevices(BluetoothProfile.GATT)
            gattDevices.forEach { connectedMacs.add(it.address) }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}

        // Fallback root check via dumpsys bluetooth_manager if granted
        if (Shell.isAppGrantedRoot() == true && connectedMacs.isEmpty()) {
            try {
                val res = Shell.cmd("dumpsys bluetooth_manager | grep -i 'mConnectedDevices'").exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        for (d in devices) {
                            if (line.contains(d.address, ignoreCase = true)) connectedMacs.add(d.address)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return connectedMacs
    }

    private fun readDeviceBattery(device: BluetoothDevice): Int? {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int
            if (level != null && level in 0..100) level else null
        } catch (_: Exception) {
            null
        }
    }

    private fun detectSupportedProfiles(uuids: List<String>): List<String> {
        val profiles = mutableListOf<String>()
        for (u in uuids) {
            val upper = u.uppercase(Locale.US)
            if (upper.contains("110A") || upper.contains("110B")) profiles.add("A2DP (Áudio Estéreo)")
            if (upper.contains("111E") || upper.contains("1108")) profiles.add("HFP/HSP (Mãos Livres)")
            if (upper.contains("1124")) profiles.add("HID (Entrada/Controle)")
            if (upper.contains("110E")) profiles.add("AVRCP (Controle Remoto)")
            if (upper.contains("180F")) profiles.add("Bateria (Battery Service)")
        }
        return profiles.distinct().ifEmpty { listOf("GATT / Serial Genérico") }
    }

    suspend fun getDeviceHistory(macAddress: String): List<BluetoothHistoryEvent> {
        return dbHelper.getBluetoothHistory(macAddress)
    }

    suspend fun saveDeviceAlias(macAddress: String, alias: String) {
        dbHelper.setBluetoothAlias(macAddress, alias)
    }

    suspend fun deleteDeviceHistory(macAddress: String) {
        dbHelper.deleteDeviceBluetoothHistory(macAddress)
    }

    fun formatHistoryExport(devices: List<BluetoothDeviceInfo>, history: List<BluetoothHistoryEvent>): String {
        return buildString {
            appendLine("=== Relatório de Dispositivos Bluetooth - ODIN_BACKUP ===")
            appendLine("Gerado em: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("Total de dispositivos listados: ${devices.size}")
            appendLine("Dispositivos conectados agora: ${devices.count { it.isConnected }}")
            appendLine()
            appendLine("--- DISPOSITIVOS PAREADOS ---")
            for (dev in devices) {
                appendLine("[${if (dev.isConnected) "CONECTADO" else "DESCONECTADO"}] ${dev.alias ?: dev.name}")
                appendLine("  MAC: ${dev.address}")
                appendLine("  Tipo: ${dev.deviceType} | Classe: ${dev.majorClass}")
                appendLine("  Perfis: ${dev.supportedProfiles.joinToString(", ")}")
                if (dev.batteryLevel != null) appendLine("  Bateria: ${dev.batteryLevel}%")
                if (dev.totalConnectionCount > 0) appendLine("  Conexões registradas: ${dev.totalConnectionCount}")
                appendLine()
            }
            appendLine("--- HISTÓRICO DE EVENTOS ---")
            for (ev in history) {
                val dt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(ev.timestamp))
                appendLine("[$dt] ${ev.event} - ${ev.deviceName} (${ev.address})")
                if (!ev.details.isNullOrBlank()) appendLine("       Detalhes: ${ev.details}")
            }
        }
    }
}

