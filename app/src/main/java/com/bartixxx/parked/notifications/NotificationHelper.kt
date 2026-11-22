package com.bartixxx.parked.notifications

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
import com.bartixxx.parked.MainActivity
import com.bartixxx.parked.R
import com.bartixxx.parked.monitor.BluetoothMonitorService

/**
 * Parked does not post event notifications about saves or failures. Apart from the
 * Android-required foreground monitoring notification, the only notification is an
 * optional parking reminder the user explicitly enables in Settings.
 */
object NotificationHelper {

    const val MONITOR_NOTIFICATION_ID = 1001
    const val TIMER_NOTIFICATION_ID = 1003
    private const val CHANNEL_MONITOR = "parked_monitor"
    private const val CHANNEL_TIMER = "parked_timer"
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

        val timer = NotificationChannel(
            CHANNEL_TIMER,
            context.getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.timer_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(timer)

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

    /**
     * One reminder per parking session. Silently does nothing when the user has
     * not granted the notification permission; the timer remains opt-in.
     */
    fun showParkingTimer(context: Context, minutesLeft: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val text = if (minutesLeft > 0) {
            context.getString(R.string.timer_notification_text, minutesLeft)
        } else {
            context.getString(R.string.timer_notification_expired)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_parked)
            .setContentTitle(context.getString(R.string.timer_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(TIMER_NOTIFICATION_ID, notification)
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
