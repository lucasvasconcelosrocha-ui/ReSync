package com.resync.app.cloud.gdrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.resync.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class NetworkStatusInfo(
    val isOnline: Boolean,
    val networkType: String, // "Wi-Fi", "Dados Móveis", "Ethernet", "VPN", "Desconectado"
    val isValidated: Boolean,
    val isMetered: Boolean
)

/**
 * High-precision, zero-polling reactive network monitor using ConnectivityManager.
 * Detects online status, network changes (Wi-Fi <-> Cellular), and validated capabilities.
 * Guarantees zero artificial restrictions on mobile or metered data.
 */
object CloudNetworkMonitor {

    private const val TAG = "CloudNetworkMonitor"

    private val _networkStatus = MutableStateFlow(
        NetworkStatusInfo(
            isOnline = true,
            networkType = "Wi-Fi",
            isValidated = true,
            isMetered = false
        )
    )
    val networkStatus: StateFlow<NetworkStatusInfo> = _networkStatus.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateStatus("onAvailable")
        }

        override fun onLost(network: Network) {
            updateStatus("onLost")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateStatus("onCapabilitiesChanged", capabilities)
        }
    }

    fun initialize(context: Context) {
        if (isRegistered) return
        val app = context.applicationContext
        connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cm = connectivityManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, networkCallback)
            }
            isRegistered = true
            updateStatus("initialization")
            AppLogger.i(TAG, "CloudNetworkMonitor registrado com sucesso via defaultNetworkCallback.")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Falha ao registrar defaultNetworkCallback: ${e.message}")
        }
    }

    private fun updateStatus(reason: String, givenCaps: NetworkCapabilities? = null) {
        val cm = connectivityManager ?: return
        val status = queryCurrentStatus(cm, givenCaps)
        val old = _networkStatus.value
        _networkStatus.value = status

        if (old.isOnline != status.isOnline || old.networkType != status.networkType) {
            AppLogger.i(TAG, "Rede alterada ($reason): Conectado=${status.isOnline}, Tipo=${status.networkType}, Validada=${status.isValidated}, Limitada=${status.isMetered}")
        }
    }

    fun queryCurrentStatus(cm: ConnectivityManager? = connectivityManager, givenCaps: NetworkCapabilities? = null): NetworkStatusInfo {
        if (cm == null) {
            return NetworkStatusInfo(true, "Desconhecido", true, false)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork
                if (activeNetwork == null) {
                    return NetworkStatusInfo(false, "Desconectado", false, false)
                }
                val caps = givenCaps ?: cm.getNetworkCapabilities(activeNetwork)
                if (caps == null) {
                    return NetworkStatusInfo(false, "Desconectado", false, false)
                }

                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                val netType = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados Móveis"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "Outra"
                }

                NetworkStatusInfo(
                    isOnline = hasInternet,
                    networkType = netType,
                    isValidated = isValidated,
                    isMetered = isMetered
                )
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                val connected = info != null && info.isConnected
                val type = info?.typeName ?: "Desconectado"
                NetworkStatusInfo(connected, type, connected, false)
            }
        } catch (_: Throwable) {
            NetworkStatusInfo(true, "Wi-Fi", true, false)
        }
    }

    fun isConnected(): Boolean = _networkStatus.value.isOnline

    fun getActiveNetworkType(): String = _networkStatus.value.networkType

    /**
     * Suspends execution without CPU polling until valid internet connection is detected.
     */
    suspend fun awaitNetworkAvailable() {
        if (isConnected()) return
        AppLogger.i(TAG, "Aguardando conexão de rede reativa via NetworkCallback...")
        _networkStatus.first { it.isOnline }
        AppLogger.i(TAG, "Conexão de rede restabelecida (${_networkStatus.value.networkType}). Retomando transferência...")
    }
}
