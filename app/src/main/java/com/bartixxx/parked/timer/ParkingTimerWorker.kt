package com.bartixxx.parked.timer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bartixxx.parked.ParkedApp
import com.bartixxx.parked.notifications.NotificationHelper

/**
 * Posts the single, opt-in parking reminder. It verifies that the spot is still
 * the latest one so a deleted or replaced spot never produces a stale alert.
 */
class ParkingTimerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? ParkedApp)?.container
            ?: return Result.success()
        if (!container.settingsRepository.current().parkingTimerEnabled) {
            return Result.success()
        }

        val spotId = inputData.getLong(KEY_SPOT_ID, -1L)
        if (spotId <= 0L) return Result.success()
        val latest = container.parkingRepository.latestSpot()
        if (latest?.id != spotId) return Result.success()

        val triggerAt = inputData.getLong(KEY_TRIGGER_AT, 0L)
        val now = System.currentTimeMillis()
        // Do not surface a stale reminder after the phone was off for a long time.
        if (triggerAt > 0L && now - triggerAt > MAX_LATE_MS) return Result.success()
        val minutesLeft = if (triggerAt > 0L) {
            ((triggerAt - now) / 60_000L).toInt()
        } else {
            0
        }
        NotificationHelper.showParkingTimer(applicationContext, minutesLeft)
        return Result.success()
    }

    companion object {
        const val KEY_SPOT_ID = "spot_id"
        const val KEY_TRIGGER_AT = "trigger_at"
        private const val MAX_LATE_MS = 6L * 60L * 60_000L
    }
}
