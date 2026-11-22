package com.bartixxx.parked.timer

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.settings.AppSettings
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Schedules at most one parking reminder. WorkManager survives process death
 * and reboots, so the reminder still arrives when the app is not running.
 * WorkManager timing is inexact under Doze; the notification is a reminder,
 * not an alarm.
 */
class ParkingTimerScheduler(private val context: Context) {

    fun sync(spot: ParkingSpot?, settings: AppSettings, nowEpochMs: Long = System.currentTimeMillis()) {
        if (!settings.parkingTimerEnabled || spot == null) {
            cancel()
            return
        }
        val durationMs = settings.parkingTimerMinutes * 60_000L
        val triggerAt = spot.savedAtEpochMs + durationMs
        if (triggerAt <= nowEpochMs) {
            cancel()
            return
        }

        val warningMs = min(DEFAULT_WARNING_MS, durationMs / 5)
            .coerceAtLeast(MIN_WARNING_MS)
        val reminderAt = (triggerAt - warningMs)
            .coerceAtLeast(spot.savedAtEpochMs + MIN_WARNING_MS)
        val delayMs = (reminderAt - nowEpochMs).coerceAtLeast(0L)

        val request = OneTimeWorkRequestBuilder<ParkingTimerWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putLong(ParkingTimerWorker.KEY_SPOT_ID, spot.id)
                    .putLong(ParkingTimerWorker.KEY_TRIGGER_AT, triggerAt)
                    .build(),
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object {
        const val UNIQUE_NAME = "parked-parking-timer"
        const val TAG = "parking-timer"
        const val DEFAULT_WARNING_MS = 15L * 60_000L
        const val MIN_WARNING_MS = 60_000L
    }
}
