package com.bartixxx.parked.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bartixxx.parked.ParkedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts monitoring after a reboot or an app update, but only when the user enabled it
 * and the required runtime permissions are still granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? ParkedApp ?: return@launch
                val settings = app.container.settingsRepository.current()
                if (!settings.monitoringEnabled || settings.deviceAddress.isNullOrBlank()) return@launch

                val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
                val hasLocation = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasBluetooth || !hasLocation) {
                    app.container.diagnosticsRepository.warn(
                        "Boot",
                        "Nie wznowiono monitorowania po restarcie — brak uprawnień",
                    )
                    return@launch
                }

                if (!BluetoothMonitorService.isRunning) {
                    BluetoothMonitorService.start(context)
                    app.container.diagnosticsRepository.info(
                        "Boot",
                        "Wznowiono monitorowanie po zdarzeniu $action",
                    )
                }
            } catch (error: Exception) {
                runCatching {
                    (context.applicationContext as ParkedApp).container.diagnosticsRepository.error(
                        "Boot",
                        "Nie udało się wznowić monitorowania: ${error.message}",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
