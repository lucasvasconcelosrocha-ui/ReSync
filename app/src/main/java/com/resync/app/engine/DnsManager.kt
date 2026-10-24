package com.resync.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.resync.app.data.model.DnsConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.topjohnwu.superuser.Shell

/**
 * DNS and Network settings inspector.
 * Reads Settings.Global via ContentResolver and privileged Shell.cmd when root is available.
 */
class DnsManager(private val context: Context) {

    /**
     * Inspect active DNS and Private DNS configuration using official APIs and privileged root checks
     */
    fun getDnsConfiguration(): DnsConfig {
        val resolver = context.contentResolver
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Read Private DNS mode from Settings.Global
        var rawMode = try {
            Settings.Global.getString(resolver, "private_dns_mode") ?: "opportunistic"
        } catch (e: Exception) {
            "opportunistic"
        }

        var rawHostname = try {
            Settings.Global.getString(resolver, "private_dns_specifier")
        } catch (e: Exception) {
            null
        }

        val perNetworkDns = mutableMapOf<String, String>()

        // Privileged queries with Shell.cmd if root is granted
        if (Shell.isAppGrantedRoot() == true) {
            val modeCmd = Shell.cmd("settings get global private_dns_mode").exec()
            if (modeCmd.isSuccess && modeCmd.out.isNotEmpty()) {
                val outVal = modeCmd.out.firstOrNull()?.trim()
                if (!outVal.isNullOrBlank() && outVal != "null") {
                    rawMode = outVal
                }
            }

            val specCmd = Shell.cmd("settings get global private_dns_specifier").exec()
            if (specCmd.isSuccess && specCmd.out.isNotEmpty()) {
                val outVal = specCmd.out.firstOrNull()?.trim()
                if (!outVal.isNullOrBlank() && outVal != "null") {
                    rawHostname = outVal
                }
            }

            // Per-network DNS inspection via getprop properties
            val propCmd = Shell.cmd("getprop").exec()
            if (propCmd.isSuccess) {
                for (line in propCmd.out) {
                    val match = Regex("\\[(net\\.dns\\d*|net\\..*?\\.dns\\d*)\\]: \\[(.*?)\\]").find(line)
                    if (match != null) {
                        val (key, value) = match.destructured
                        if (value.isNotBlank()) {
                            perNetworkDns[key] = value
                        }
                    }
                }
            }
        }

        // Query active LinkProperties
        val activeNetwork = connectivityManager?.activeNetwork
        val linkProps: LinkProperties? = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        val netCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val dnsServers = linkProps?.dnsServers?.map { it.hostAddress ?: it.toString() } ?: emptyList()
        val isVpnActive = netCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val isPrivateDnsActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProps?.isPrivateDnsActive == true || rawMode != "off"
        } else {
            rawMode != "off"
        }

        val resolvedHostname = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProps?.privateDnsServerName?.takeIf { it.isNotBlank() } ?: rawHostname
        } else {
            rawHostname
        }

        val modeDisplay = when (rawMode) {
            "off" -> "Desativado"
            "opportunistic" -> "Automático (Oportunístico)"
            "hostname" -> "DNS Privado Personalizado"
            else -> rawMode
        }

        return DnsConfig(
            mode = modeDisplay,
            isActive = isPrivateDnsActive,
            hostname = resolvedHostname,
            dnsServers = dnsServers,
            interfaceName = linkProps?.interfaceName,
            isVpnActive = isVpnActive,
            rawMode = rawMode,
            perNetworkDns = perNetworkDns
        )
    }

    /**
     * Generate standard human-readable text for DNS export
     */
    fun formatDnsBackupSummary(config: DnsConfig): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val dateFormatted = sdf.format(Date(config.capturedAt))

        return buildString {
            appendLine("DNS Backup")
            appendLine("──────────────")
            appendLine("Modo: ${config.mode}")
            appendLine("Status: ${if (config.isActive) "Ativo" else "Inativo"}")
            appendLine("Host: ${config.hostname ?: "Automático / Nenhum"}")
            if (config.dnsServers.isNotEmpty()) {
                appendLine("Servidores DNS: ${config.dnsServers.joinToString(", ")}")
            }
            if (config.interfaceName != null) {
                appendLine("Interface de Rede: ${config.interfaceName}")
            }
            appendLine("VPN: ${if (config.isVpnActive) "Conectada" else "Não"}")
            appendLine("Data: $dateFormatted")
            appendLine("───────────────────────────────────────────")
            appendLine("Nota: No Android 17, a alteração de DNS Privado requer")
            appendLine("interação nas Configurações de Rede do Sistema por segurança.")
        }
    }

    /**
     * Copy DNS hostname to user clipboard for quick paste in Android settings
     */
    fun copyHostToClipboard(hostname: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DNS Hostname", hostname)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Open official Android Network/DNS Settings Activity
     */
    fun createOpenDnsSettingsIntent(): Intent {
        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}

