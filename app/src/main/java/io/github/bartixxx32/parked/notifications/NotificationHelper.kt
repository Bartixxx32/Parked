package io.github.bartixxx32.parked.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.bartixxx32.parked.MainActivity
import io.github.bartixxx32.parked.R
import io.github.bartixxx32.parked.capture.CaptureFailure
import io.github.bartixxx32.parked.capture.accuracyLabel
import io.github.bartixxx32.parked.capture.userMessage
import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.monitor.BluetoothMonitorService

object NotificationHelper {

    const val MONITOR_NOTIFICATION_ID = 1001
    private const val EVENT_NOTIFICATION_ID = 1002
    private const val CHANNEL_MONITOR = "parked_monitor"
    private const val CHANNEL_EVENTS = "parked_events"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            "Monitorowanie Bluetooth",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Stały status monitorowania radia samochodowego"
            setShowBadge(false)
            setSound(null, null)
        }
        val events = NotificationChannel(
            CHANNEL_EVENTS,
            "Zapisane miejsca",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Powiadomienia o zapisaniu miejsca parkingowego"
        }
        manager.createNotificationChannels(listOf(monitor, events))
    }

    fun monitorNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_parked)
            .setContentTitle("Parked")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Zatrzymaj", stopMonitoringIntent(context))
            .build()

    fun updateMonitorNotification(context: Context, text: String) {
        runCatching {
            NotificationManagerCompat.from(context).notify(
                MONITOR_NOTIFICATION_ID,
                monitorNotification(context, text),
            )
        }
    }

    fun showSaved(context: Context, spot: ParkingSpot, usedFallback: Boolean) {
        if (!canNotify(context)) return
        val details = buildString {
            if (usedFallback) {
                append("Punkt awaryjny z ostatniej znanej lokalizacji. ")
            }
            append("Dokładność: ").append(spot.accuracyLabel()).append('.')
            if (spot.isMock) append(" Lokalizacja testowa (mock).")
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_parked)
            .setContentTitle("Zapisano miejsce parkingowe")
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(EVENT_NOTIFICATION_ID, notification)
        }
    }

    fun showFailure(context: Context, reason: CaptureFailure) {
        showEvent(
            context = context,
            title = "Nie udało się zapisać miejsca",
            message = reason.userMessage(),
        )
    }

    fun showServiceProblem(context: Context, message: String) {
        showEvent(
            context = context,
            title = "Parked wymaga uwagi",
            message = message,
            ongoing = false,
        )
    }

    private fun showEvent(
        context: Context,
        title: String,
        message: String,
        ongoing: Boolean = false,
    ) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_parked)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(EVENT_NOTIFICATION_ID + 1, notification)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopMonitoringIntent(context: Context): PendingIntent {
        val intent = Intent(context, BluetoothMonitorService::class.java)
            .setAction(BluetoothMonitorService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canNotify(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
