package com.bartixxx.parked.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bartixxx.parked.ParkedApp
import com.bartixxx.parked.util.PermissionUtils
import java.util.concurrent.TimeUnit

/**
 * Best-effort watchdog. Android may forbid starting a foreground service from the
 * background; the failure is recorded in diagnostics instead of posting a notification.
 * OEM autostart/battery settings remain the only reliable remedy (see Settings screen).
 */
class MonitorWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ParkedApp ?: return Result.success()
        val container = app.container
        val settings = container.settingsRepository.current()

        if (!settings.monitoringEnabled || settings.deviceAddress.isNullOrBlank()) {
            return Result.success()
        }
        if (BluetoothMonitorService.isRunning) {
            return Result.success()
        }
        if (!container.bluetoothRepository.hasBluetoothPermission() ||
            !PermissionUtils.hasAnyLocation(applicationContext)
        ) {
            container.diagnosticsRepository.warn(
                "Watchdog",
                "Monitoring is not running, but permissions are missing — open the app",
            )
            return Result.success()
        }

        return try {
            BluetoothMonitorService.start(applicationContext)
            container.diagnosticsRepository.info("Watchdog", "Monitoring service restarted")
            Result.success()
        } catch (error: Exception) {
            container.diagnosticsRepository.error(
                "Watchdog",
                "The system blocked the service restart: ${error.message}",
            )
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "parked-monitor-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWatchdogWorker>(
                15,
                TimeUnit.MINUTES,
            )
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
