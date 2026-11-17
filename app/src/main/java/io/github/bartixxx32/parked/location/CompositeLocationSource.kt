package io.github.bartixxx32.parked.location

import android.content.Context
import android.os.SystemClock

class CompositeLocationSource(context: Context) : LocationSource {

    private val platform = PlatformLocationSource(context)
    private val fused = FusedLocationSource(context).takeIf { it.isAvailable }

    override suspend fun getCurrentFix(
        timeoutMs: Long,
        acceptableAccuracyMeters: Float,
    ): LocationFix? {
        val startedAt = SystemClock.elapsedRealtime()
        var fusedFix: LocationFix? = null

        if (fused != null) {
            val fusedBudget = (timeoutMs / 2).coerceAtLeast(1_000L)
            fusedFix = fused.getCurrentFix(fusedBudget, acceptableAccuracyMeters)
            if (fusedFix != null && fusedFix.accuracyMeters in 0f..acceptableAccuracyMeters) {
                return fusedFix
            }
        }

        val remaining = timeoutMs - (SystemClock.elapsedRealtime() - startedAt)
        val platformFix = platform.getCurrentFix(
            remaining.coerceAtLeast(1_000L),
            acceptableAccuracyMeters,
        )
        return when {
            platformFix == null -> fusedFix
            fusedFix == null -> platformFix
            platformFix.accuracyMeters <= fusedFix.accuracyMeters -> platformFix
            else -> fusedFix
        }
    }

    override fun getLastKnownFix(): LocationFix? = platform.getLastKnownFix()
}
