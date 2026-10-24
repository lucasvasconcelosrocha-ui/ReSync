package com.resync.app.engine

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.resync.app.data.database.AppDatabaseHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver listening for real-time Bluetooth ACL connection, disconnection,
 * and bonding state changes to record history directly in SQLite.
 */
class BluetoothEventReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val address = device?.address ?: return
        val name = try {
            device.name ?: device.alias ?: "Dispositivo $address"
        } catch (_: SecurityException) {
            "Dispositivo $address"
        }

        val dbHelper = AppDatabaseHelper.getInstance(context)

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                scope.launch {
                    dbHelper.insertBluetoothEvent(
                        macAddress = address,
                        deviceName = name,
                        eventType = "CONECTADO",
                        details = "Conexão ativa estabelecida"
                    )
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                scope.launch {
                    dbHelper.insertBluetoothEvent(
                        macAddress = address,
                        deviceName = name,
                        eventType = "DESCONECTADO",
                        details = "Conexão encerrada"
                    )
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

                val eventStr = when (bondState) {
                    BluetoothDevice.BOND_BONDED -> "PAREADO"
                    BluetoothDevice.BOND_NONE -> if (prevBondState == BluetoothDevice.BOND_BONDED) "DESPAREADO" else "NÃO PAREADO"
                    BluetoothDevice.BOND_BONDING -> "PAREANDO..."
                    else -> "STATUS ALTERADO"
                }

                scope.launch {
                    dbHelper.insertBluetoothEvent(
                        macAddress = address,
                        deviceName = name,
                        eventType = eventStr,
                        details = "Estado do pareamento alterado"
                    )
                }
            }
        }
    }

    companion object {
        private var isRegistered = false
        private val receiver = BluetoothEventReceiver()

        fun register(context: Context) {
            if (isRegistered) return
            try {
                val filter = IntentFilter().apply {
                    addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                }
                androidx.core.content.ContextCompat.registerReceiver(
                    context.applicationContext,
                    receiver,
                    filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isRegistered = true
                Log.d("BluetoothEventReceiver", "BluetoothEventReceiver registered successfully with RECEIVER_NOT_EXPORTED")
            } catch (e: Exception) {
                Log.w("BluetoothEventReceiver", "Failed to register BluetoothEventReceiver: ${e.message}")
            }
        }
    }
}

