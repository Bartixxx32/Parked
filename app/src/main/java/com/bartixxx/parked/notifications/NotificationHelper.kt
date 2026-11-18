package com.bartixxx.parked.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bartixxx.parked.MainActivity
import com.bartixxx.parked.R
import com.bartixxx.parked.monitor.BluetoothMonitorService

/**
 * Parked does not post optional event notifications (saved/failure alerts). The only
 * notification is the one Android requires for the foreground monitoring service; it is
 * silent, low priority and shows the current trigger status.
 */
object NotificationHelper {

    const val MONITOR_NOTIFICATION_ID = 1001
    private const val CHANNEL_MONITOR = "parked_monitor"
    private const val LEGACY_CHANNEL_EVENTS = "parked_events"
    private const val LEGACY_EVENT_NOTIFICATION_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(monitor)

        // Remove the old "saved location" channel and any notification it left behind.
        // Event notifications are no longer used in Parked.
        runCatching {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_EVENTS)
            manager.cancel(LEGACY_EVENT_NOTIFICATION_ID)
            manager.cancel(LEGACY_EVENT_NOTIFICATION_ID + 1)
        }
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
            .addAction(0, context.getString(R.string.notification_stop), stopMonitoringIntent(context))
            .build()

    fun updateMonitorNotification(context: Context, text: String) {
        runCatching {
            NotificationManagerCompat.from(context).notify(
                MONITOR_NOTIFICATION_ID,
                monitorNotification(context, text),
            )
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
}
