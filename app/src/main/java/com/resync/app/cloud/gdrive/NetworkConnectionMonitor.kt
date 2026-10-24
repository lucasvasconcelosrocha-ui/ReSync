package com.resync.app.cloud.gdrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Reactive network monitor that observes connectivity state using ConnectivityManager.NetworkCallback.
 * Enables zero-polling suspension and automatic resumption for cloud uploads and background services.
 */
object NetworkConnectionMonitor {
    private const val TAG = "NetworkConnectionMonitor"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Rede conectada: $network")
            _isOnline.value = true
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Rede perdida: $network")
            val isStillConnected = checkCurrentConnectivity()
            _isOnline.value = isStillConnected
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isOnline.value = hasInternet
        }
    }

    fun initialize(context: Context) {
        if (isRegistered) return
        try {
            connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            _isOnline.value = checkCurrentConnectivity()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao registrar NetworkCallback: ${e.message}")
            _isOnline.value = true
        }
    }

    fun checkCurrentConnectivity(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Suspends execution without busy-waiting until the device has a valid internet connection.
     * Returns immediately if connection is already established.
     */
    suspend fun awaitNetworkAvailable() {
        if (checkCurrentConnectivity()) {
            _isOnline.value = true
            return
        }
        _isOnline.first { it }
    }
}

