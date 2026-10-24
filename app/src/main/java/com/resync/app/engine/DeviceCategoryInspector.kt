package com.resync.app.engine

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import com.resync.app.data.database.AppDatabaseHelper
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.zip.ZipFile

data class SmsConversationItem(
    val id: Long,
    val address: String,
    val contactName: String?,
    val messageCount: Int,
    val snippet: String,
    val date: Long,
    val isSelected: Boolean = true
) {
    val stableId: String get() = "sms_${id}_${address}"
}

data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int, // 1: Incoming, 2: Outgoing, 3: Missed, 5: Rejected, 6: Blocked
    val date: Long,
    val duration: Long,
    val isSelected: Boolean = true
) {
    val stableId: String get() = if (id > 0L) "call_${id}_${date}" else "call_${number}_${date}_${duration}"
}

data class ContactItem(
    val id: Long,
    val name: String,
    val phone: String,
    val photoUri: String? = null,
    val isSelected: Boolean = true
) {
    val cleanPhone: String get() = phone.replace(Regex("[^0-9+]"), "")
    val hasRealName: Boolean get() {
        val n = name.trim()
        if (n.isBlank() || n.equals("Contato", ignoreCase = true)) return false
        val cleanName = n.replace(Regex("[^0-9]"), "")
        val cleanP = phone.replace(Regex("[^0-9]"), "")
        if (cleanName.isNotBlank() && (cleanName == cleanP || n == phone)) return false
        return n.any { it.isLetter() }
    }
    val initials: String get() {
        if (!hasRealName) return ""
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }
    val stableId: String get() = "contact_${id}_${cleanPhone}"
}

data class WifiSavedNetwork(
    val ssid: String,
    val keyMgmt: String,
    val isConnected: Boolean = false,
    val isHidden: Boolean = false,
    val isSelected: Boolean = true
) {
    val stableId: String get() = "wifi_${ssid}"
}

data class BluetoothPairedDevice(
    val name: String,
    val address: String,
    val type: String,
    val isConnected: Boolean = false,
    val isSelected: Boolean = true,
    val deviceCategory: String = "other" // "headphones", "watch", "speaker", "car", "computer", "phone", "other"
) {
    val stableId: String get() = "bt_${address}"
}

data class DnsCategoryInfo(
    val mode: String, // "off", "opportunistic", "hostname"
    val hostname: String?,
    val isPrivateDnsActive: Boolean
)

data class WallpaperPreviewItem(
    val flag: Int,
    val label: String,
    val bitmap: Bitmap?,
    val isSelected: Boolean = true
)

object DeviceCategoryInspector {

    suspend fun getSmsConversations(context: Context): List<SmsConversationItem> = withContext(Dispatchers.IO) {
        val conversations = mutableMapOf<String, MutableList<Pair<Long, String>>>()
        val latestDates = mutableMapOf<String, Long>()

        // 1. ContentResolver
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 1000"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex(Telephony.Sms._ID)
                val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndex(Telephony.Sms.DATE)

                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                    val address = if (addrCol >= 0) c.getString(addrCol) ?: "Desconhecido" else "Desconhecido"
                    val body = if (bodyCol >= 0) c.getString(bodyCol) ?: "" else ""
                    val date = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis()

                    val list = conversations.getOrPut(address) { mutableListOf() }
                    list.add(Pair(id, body))
                    if (!latestDates.containsKey(address)) {
                        latestDates[address] = date
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback via Root Shell se vazio e houver Root
        if (conversations.isEmpty() && Shell.isAppGrantedRoot() == true) {
            try {
                val res = Shell.cmd("content query --uri content://sms --projection _id:address:body:date").exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        if (!line.startsWith("Row:")) continue
                        val pairs = line.substringAfter("Row:").trim().split(", ")
                        var id = 0L
                        var address = "Desconhecido"
                        var body = ""
                        var date = System.currentTimeMillis()
                        for (p in pairs) {
                            val k = p.substringBefore("=").trim()
                            val v = p.substringAfter("=").trim()
                            when (k) {
                                "_id" -> id = v.toLongOrNull() ?: 0L
                                "address" -> if (v != "NULL") address = v
                                "body" -> if (v != "NULL") body = v
                                "date" -> date = v.toLongOrNull() ?: System.currentTimeMillis()
                            }
                        }
                        val list = conversations.getOrPut(address) { mutableListOf() }
                        list.add(Pair(id, body))
                        if (!latestDates.containsKey(address)) {
                            latestDates[address] = date
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        conversations.map { (addr, msgs) ->
            SmsConversationItem(
                id = msgs.firstOrNull()?.first ?: 0L,
                address = addr,
                contactName = null,
                messageCount = msgs.size,
                snippet = msgs.firstOrNull()?.second?.take(100) ?: "",
                date = latestDates[addr] ?: System.currentTimeMillis()
            )
        }.sortedByDescending { it.date }
    }

    suspend fun getCallLogs(context: Context): List<CallLogEntry> = withContext(Dispatchers.IO) {
        val list = mutableListOf<CallLogEntry>()
        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 500"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndex(CallLog.Calls._ID)
                val numCol = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                val durCol = c.getColumnIndex(CallLog.Calls.DURATION)

                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                    val number = if (numCol >= 0) c.getString(numCol) ?: "" else ""
                    val name = if (nameCol >= 0) c.getString(nameCol) else null
                    val type = if (typeCol >= 0) c.getInt(typeCol) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis()
                    val dur = if (durCol >= 0) c.getLong(durCol) else 0L

                    list.add(CallLogEntry(id, number, name, type, date, dur))
                }
            }
        } catch (_: Throwable) {}

        // Fallback Root se vazio
        if (list.isEmpty() && Shell.isAppGrantedRoot() == true) {
            try {
                val res = Shell.cmd("content query --uri content://call_log/calls --projection _id:number:name:type:date:duration").exec()
                if (res.isSuccess) {
                    for (line in res.out) {
                        if (!line.startsWith("Row:")) continue
                        val pairs = line.substringAfter("Row:").trim().split(", ")
                        var id = 0L
                        var number = ""
                        var name: String? = null
                        var type = 1
                        var date = System.currentTimeMillis()
                        var dur = 0L
                        for (p in pairs) {
                            val k = p.substringBefore("=").trim()
                            val v = p.substringAfter("=").trim()
                            when (k) {
                                "_id" -> id = v.toLongOrNull() ?: 0L
                                "number" -> if (v != "NULL") number = v
                                "name" -> if (v != "NULL") name = v
                                "type" -> type = v.toIntOrNull() ?: 1
                                "date" -> date = v.toLongOrNull() ?: System.currentTimeMillis()
                                "duration" -> dur = v.toLongOrNull() ?: 0L
                            }
                        }
                        if (number.isNotBlank() || id > 0L) {
                            list.add(CallLogEntry(id, number, name, type, date, dur))
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        list
    }

    suspend fun getContacts(context: Context): List<ContactItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<ContactItem>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                val seenIds = mutableSetOf<Long>()
                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) c.getString(nameCol) ?: "Contato" else "Contato"
                    val phone = if (numCol >= 0) c.getString(numCol) ?: "" else ""
                    val photo = if (photoCol >= 0) c.getString(photoCol) else null

                    if (seenIds.add(id)) {
                        list.add(ContactItem(id, name, phone, photo))
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback Root se vazio
        if (list.isEmpty() && Shell.isAppGrantedRoot() == true) {
            try {
                val res = Shell.cmd("content query --uri content://com.android.contacts/data/phones --projection contact_id:display_name:data1").exec()
                if (res.isSuccess) {
                    val seen = mutableSetOf<Long>()
                    for (line in res.out) {
                        if (!line.startsWith("Row:")) continue
                        val pairs = line.substringAfter("Row:").trim().split(", ")
                        var id = 0L
                        var name = "Contato"
                        var phone = ""
                        for (p in pairs) {
                            val k = p.substringBefore("=").trim()
                            val v = p.substringAfter("=").trim()
                            when (k) {
                                "contact_id" -> id = v.toLongOrNull() ?: 0L
                                "display_name" -> if (v != "NULL") name = v
                                "data1" -> if (v != "NULL") phone = v
                            }
                        }
                        if (id > 0L && seen.add(id)) {
                            list.add(ContactItem(id, name, phone))
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Deduplicar contatos (mesmo número em várias contas ou duplicados do SIM/WhatsApp)
        val seenPhoneKeys = mutableMapOf<String, ContactItem>()
        val seenNameKeys = mutableMapOf<String, ContactItem>()

        for (item in list) {
            val phoneDigits = item.cleanPhone.filter { it.isDigit() }
            val key = if (phoneDigits.length >= 8) phoneDigits.takeLast(8) else phoneDigits
            if (key.isNotBlank()) {
                val existing = seenPhoneKeys[key]
                if (existing == null) {
                    seenPhoneKeys[key] = item
                } else {
                    // Preferir registro que possua nome real e foto
                    val preferNew = (!existing.hasRealName && item.hasRealName) ||
                            (existing.photoUri == null && item.photoUri != null) ||
                            (existing.name.length < item.name.length && item.hasRealName)
                    if (preferNew) {
                        seenPhoneKeys[key] = item
                    }
                }
            } else {
                val nameKey = item.name.trim().lowercase()
                if (nameKey.isNotBlank()) {
                    val existing = seenNameKeys[nameKey]
                    if (existing == null || (existing.photoUri == null && item.photoUri != null)) {
                        seenNameKeys[nameKey] = item
                    }
                }
            }
        }

        val allUnique = (seenPhoneKeys.values + seenNameKeys.values).distinctBy { it.id }
        allUnique.sortedWith(compareBy({ !it.hasRealName }, { it.name.lowercase() }))
    }

    suspend fun getSavedWifiNetworks(context: Context): List<WifiSavedNetwork> = withContext(Dispatchers.IO) {
        val networks = mutableListOf<WifiSavedNetwork>()

        // Detecção da rede conectada atualmente
        val activeSsid = getCurrentlyConnectedWifiSsid(context)

        // 1. Root inspection de WifiConfigStore.xml
        try {
            val res = Shell.cmd("cat /data/misc/wifi/WifiConfigStore.xml 2>/dev/null || cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml 2>/dev/null").exec()
            if (res.isSuccess && res.out.isNotEmpty()) {
                val fullText = res.out.joinToString("\n")
                val configKeyRegex = Regex("<string name=\"ConfigKey\">&quot;(.*?)&quot;(.*?)</string>")
                val ssidRegex = Regex("<string name=\"SSID\">&quot;(.*?)&quot;</string>")

                val matches = configKeyRegex.findAll(fullText).toList()
                if (matches.isNotEmpty()) {
                    for (m in matches) {
                        val ssid = m.groupValues[1]
                        val security = m.groupValues[2].trim()
                        if (ssid.isNotBlank()) {
                            val isConn = activeSsid != null && (activeSsid == ssid || activeSsid.trim('"') == ssid)
                            networks.add(WifiSavedNetwork(ssid, if (security.isNotBlank()) security else "WPA2_PSK", isConnected = isConn))
                        }
                    }
                } else {
                    val ssidMatches = ssidRegex.findAll(fullText).toList()
                    for (m in ssidMatches) {
                        val ssid = m.groupValues[1]
                        if (ssid.isNotBlank() && networks.none { it.ssid == ssid }) {
                            val isConn = activeSsid != null && (activeSsid == ssid || activeSsid.trim('"') == ssid)
                            networks.add(WifiSavedNetwork(ssid, "WPA2_PSK", isConnected = isConn))
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Fallback: WifiManager se disponível
        if (networks.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val configured = wifiManager?.configuredNetworks
                if (!configured.isNullOrEmpty()) {
                    for (c in configured) {
                        val ssid = c.SSID?.trim('"', ' ') ?: ""
                        if (ssid.isNotBlank()) {
                            val isConn = activeSsid != null && (activeSsid == ssid || activeSsid.trim('"') == ssid)
                            networks.add(WifiSavedNetwork(ssid, "WPA_PSK", isConnected = isConn))
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Se houver rede conectada que não apareceu no arquivo xml, adicionar
        if (!activeSsid.isNullOrBlank() && networks.none { it.ssid == activeSsid.trim('"') }) {
            networks.add(0, WifiSavedNetwork(activeSsid.trim('"'), "WPA2_PSK", isConnected = true))
        }

        // Ordenar colocando a conectada no topo
        networks.distinctBy { it.ssid }.sortedWith(compareByDescending<WifiSavedNetwork> { it.isConnected }.thenBy { it.ssid })
    }

    fun getCurrentlyConnectedWifiSsid(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val info: WifiInfo? = wifiManager?.connectionInfo
                info?.ssid?.trim('"', ' ')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getWallpapers(context: Context): List<WallpaperPreviewItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<WallpaperPreviewItem>()
        try {
            val wm = WallpaperManager.getInstance(context)

            // Tela Inicial (FLAG_SYSTEM)
            var homeBitmap: Bitmap? = null
            try {
                val pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)
                if (pfd != null) {
                    pfd.use {
                        homeBitmap = BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                    }
                }
            } catch (_: Throwable) {}

            if (homeBitmap == null) {
                try {
                    val drawable = wm.drawable
                    if (drawable is BitmapDrawable) {
                        homeBitmap = drawable.bitmap
                    }
                } catch (_: Throwable) {}
            }

            // Fallback Root para Tela Inicial: /data/system/users/0/wallpaper
            if (homeBitmap == null && Shell.isAppGrantedRoot() == true) {
                try {
                    val cacheFile = File(context.cacheDir, "temp_home_wp.png")
                    val cmd = Shell.cmd("cp /data/system/users/0/wallpaper \"${cacheFile.absolutePath}\" && chmod 666 \"${cacheFile.absolutePath}\"").exec()
                    if (cmd.isSuccess && cacheFile.exists() && cacheFile.length() > 0L) {
                        homeBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                        cacheFile.delete()
                    }
                } catch (_: Throwable) {}
            }

            result.add(WallpaperPreviewItem(WallpaperManager.FLAG_SYSTEM, "Tela Inicial", homeBitmap, isSelected = true))

            // Tela de Bloqueio (FLAG_LOCK)
            var lockBitmap: Bitmap? = null
            try {
                val pfdLock = wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)
                if (pfdLock != null) {
                    pfdLock.use {
                        lockBitmap = BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
                    }
                }
            } catch (_: Throwable) {}

            // Fallback Root para Tela de Bloqueio: /data/system/users/0/wallpaper_lock
            if (lockBitmap == null && Shell.isAppGrantedRoot() == true) {
                try {
                    val cacheFile = File(context.cacheDir, "temp_lock_wp.png")
                    val cmd = Shell.cmd("cp /data/system/users/0/wallpaper_lock \"${cacheFile.absolutePath}\" && chmod 666 \"${cacheFile.absolutePath}\"").exec()
                    if (cmd.isSuccess && cacheFile.exists() && cacheFile.length() > 0L) {
                        lockBitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                        cacheFile.delete()
                    }
                } catch (_: Throwable) {}
            }

            // Se a tela de bloqueio usa o mesmo da inicial
            if (lockBitmap == null) {
                lockBitmap = homeBitmap
            }

            result.add(WallpaperPreviewItem(WallpaperManager.FLAG_LOCK, "Tela de Bloqueio", lockBitmap, isSelected = true))
        } catch (_: Throwable) {}

        result
    }

    suspend fun applyWallpapers(
        context: Context,
        homeBitmap: Bitmap?,
        lockBitmap: Bitmap?,
        applyHome: Boolean,
        applyLock: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val wm = WallpaperManager.getInstance(context)
        var ok = true
        if (applyHome && homeBitmap != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setBitmap(homeBitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                } else {
                    wm.setBitmap(homeBitmap)
                }
            } catch (_: Throwable) {
                ok = false
            }
        }
        if (applyLock && lockBitmap != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    wm.setBitmap(lockBitmap, null, true, WallpaperManager.FLAG_LOCK)
                }
            } catch (_: Throwable) {
                ok = false
            }
        }
        ok
    }

    fun extractWallpaperThumbnails(archiveFile: File): Pair<Bitmap?, Bitmap?> {
        var homeBmp: Bitmap? = null
        var lockBmp: Bitmap? = null
        try {
            ZipFile(archiveFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name.contains("home_wallpaper", ignoreCase = true) || (e.name.contains("wallpaper", ignoreCase = true) && !e.name.contains("lock", ignoreCase = true))) {
                        zip.getInputStream(e).use { stream ->
                            homeBmp = BitmapFactory.decodeStream(stream)
                        }
                    } else if (e.name.contains("lock_wallpaper", ignoreCase = true) || e.name.contains("wallpaper_lock", ignoreCase = true)) {
                        zip.getInputStream(e).use { stream ->
                            lockBmp = BitmapFactory.decodeStream(stream)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return Pair(homeBmp, lockBmp ?: homeBmp)
    }

    fun classifyBluetoothDevice(name: String, devClass: Int?, btDevice: BluetoothDevice?): Pair<String, String> {
        val lower = name.lowercase(Locale.ROOT)
        val btClass = try { btDevice?.bluetoothClass } catch (_: Throwable) { null }
        val major = btClass?.majorDeviceClass ?: ((devClass ?: 0) and 0x1F00)
        val device = btClass?.deviceClass ?: (devClass ?: 0)

        if (major == BluetoothClass.Device.Major.WEARABLE || lower.contains("watch") || lower.contains("band") || lower.contains("relogio") || lower.contains("fit")) {
            return Pair("Relógio / Wearable", "watch")
        }
        if (device == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
            device == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
            lower.contains("buds") || lower.contains("pod") || lower.contains("ear") || lower.contains("headphone") || lower.contains("fone") || lower.contains("wh-") || lower.contains("wf-")) {
            return Pair("Fone de ouvido", "headphones")
        }
        if (device == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER ||
            lower.contains("speaker") || lower.contains("caixa") || lower.contains("sound") || lower.contains("jbl") || lower.contains("boombox") || lower.contains("echo")) {
            return Pair("Caixa de som", "speaker")
        }
        if (device == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
            device == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
            lower.contains("car") || lower.contains("carro") || lower.contains("sync") || lower.contains("automotivo") || lower.contains("multimidia")) {
            return Pair("Carro / Automotivo", "car")
        }
        if (major == BluetoothClass.Device.Major.COMPUTER || lower.contains("pc") || lower.contains("macbook") || lower.contains("laptop") || lower.contains("desktop")) {
            return Pair("Computador", "computer")
        }
        if (major == BluetoothClass.Device.Major.PHONE || lower.contains("phone") || lower.contains("galaxy") || lower.contains("iphone") || lower.contains("celular")) {
            return Pair("Smartphone", "phone")
        }
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            return Pair("Áudio / Som", "speaker")
        }
        if (major == BluetoothClass.Device.Major.PERIPHERAL || lower.contains("mouse") || lower.contains("teclado") || lower.contains("keyboard") || lower.contains("gamepad") || lower.contains("controller")) {
            return Pair("Periférico / Controle", "other")
        }
        return Pair("Dispositivo Pareado", "other")
    }

    @SuppressLint("MissingPermission")
    suspend fun getBluetoothDevices(context: Context): List<BluetoothPairedDevice> = withContext(Dispatchers.IO) {
        val map = LinkedHashMap<String, BluetoothPairedDevice>()
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

            // 1. Inspeciona bonded devices via API oficial
            val bonded = try { adapter?.bondedDevices } catch (_: Throwable) { null }
            if (!bonded.isNullOrEmpty()) {
                val connectedProfilesDevices = mutableSetOf<String>()
                try {
                    val a2dp = bluetoothManager?.getConnectedDevices(BluetoothProfile.A2DP)
                    a2dp?.forEach { connectedProfilesDevices.add(it.address.uppercase(Locale.US)) }
                } catch (_: Throwable) {}
                try {
                    val headset = bluetoothManager?.getConnectedDevices(BluetoothProfile.HEADSET)
                    headset?.forEach { connectedProfilesDevices.add(it.address.uppercase(Locale.US)) }
                } catch (_: Throwable) {}
                try {
                    val gatt = bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
                    gatt?.forEach { connectedProfilesDevices.add(it.address.uppercase(Locale.US)) }
                } catch (_: Throwable) {}

                for (d in bonded) {
                    val mac = d.address ?: continue
                    val macUpper = mac.uppercase(Locale.US)
                    val name = try { d.name ?: "Dispositivo $mac" } catch (_: Throwable) { "Dispositivo $mac" }

                    var isConnected = connectedProfilesDevices.contains(macUpper)
                    if (!isConnected) {
                        try {
                            val isConnectedMethod = d.javaClass.getMethod("isConnected")
                            isConnected = isConnectedMethod.invoke(d) as? Boolean ?: false
                        } catch (_: Throwable) {}
                    }

                    val (typeName, categoryKey) = classifyBluetoothDevice(name, null, d)

                    map[macUpper] = BluetoothPairedDevice(
                        name = name,
                        address = mac,
                        type = typeName,
                        isConnected = isConnected,
                        isSelected = true,
                        deviceCategory = categoryKey
                    )
                }
            }
        } catch (_: Throwable) {}

        // 2. Alternativa com Root se a lista estiver vazia ou Bluetooth desligado (lê bt_config.conf)
        try {
            if (Shell.isAppGrantedRoot() == true) {
                val configPaths = listOf(
                    "/data/misc/apexdata/com.android.btservices/bt_config.conf",
                    "/data/misc/apexdata/com.android.bluetooth/bt_config.conf",
                    "/data/misc/bluedroid/bt_config.conf",
                    "/data/misc/bluetooth/bt_config.conf"
                )
                for (p in configPaths) {
                    val check = Shell.cmd("[ -f \"$p\" ] && echo YES || echo NO").exec()
                    if (check.isSuccess && check.out.firstOrNull() == "YES") {
                        val catRes = Shell.cmd("cat \"$p\"").exec()
                        if (catRes.isSuccess && catRes.out.isNotEmpty()) {
                            var curMac: String? = null
                            var curName: String? = null
                            var curDevClass: Int? = null

                            fun flushCur() {
                                val m = curMac ?: return
                                val mUpper = m.uppercase(Locale.US)
                                if (!map.containsKey(mUpper)) {
                                    val nm = curName ?: "Dispositivo $m"
                                    val (tName, catKey) = classifyBluetoothDevice(nm, curDevClass, null)
                                    map[mUpper] = BluetoothPairedDevice(
                                        name = nm,
                                        address = m,
                                        type = tName,
                                        isConnected = false,
                                        isSelected = true,
                                        deviceCategory = catKey
                                    )
                                }
                            }

                            for (line in catRes.out) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                                    val section = trimmed.removeSurrounding("[", "]")
                                    if (section.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}"))) {
                                        flushCur()
                                        curMac = section
                                        curName = null
                                        curDevClass = null
                                    }
                                } else if (curMac != null) {
                                    val parts = trimmed.split("=", limit = 2)
                                    if (parts.size == 2) {
                                        val key = parts[0].trim()
                                        val value = parts[1].trim()
                                        // Filtro de segurança: jamais lê chaves criptográficas
                                        if (!key.equals("LinkKey", ignoreCase = true) &&
                                            !key.contains("Key", ignoreCase = true) &&
                                            !key.contains("Pin", ignoreCase = true) &&
                                            !key.contains("Secret", ignoreCase = true)) {
                                            when (key) {
                                                "Name" -> curName = value
                                                "DevClass" -> curDevClass = value.toIntOrNull() ?: value.toIntOrNull(16)
                                            }
                                        }
                                    }
                                }
                            }
                            flushCur()
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3. Fallback complementar: dispositivos persistidos no banco de dados local
        try {
            val persisted = AppDatabaseHelper.getInstance(context).getAllPersistedBluetoothDevices()
            for (p in persisted) {
                val macUpper = p.address.uppercase(Locale.US)
                if (!map.containsKey(macUpper)) {
                    val (tName, catKey) = classifyBluetoothDevice(p.name, null, null)
                    map[macUpper] = BluetoothPairedDevice(
                        name = p.name,
                        address = p.address,
                        type = tName,
                        isConnected = false,
                        isSelected = true,
                        deviceCategory = catKey
                    )
                }
            }
        } catch (_: Throwable) {}

        map.values.sortedWith(
            compareByDescending<BluetoothPairedDevice> { it.isConnected }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    fun getDnsInfo(context: Context): DnsCategoryInfo {
        return try {
            val resolver = context.contentResolver
            val mode = Settings.Global.getString(resolver, "private_dns_mode") ?: "off"
            val specifier = Settings.Global.getString(resolver, "private_dns_specifier")
            val isCustomActive = mode == "hostname" && !specifier.isNullOrBlank()
            DnsCategoryInfo(
                mode = mode,
                hostname = specifier,
                isPrivateDnsActive = mode != "off"
            )
        } catch (_: Throwable) {
            DnsCategoryInfo("off", null, false)
        }
    }
}
