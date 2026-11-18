package io.github.bartixxx32.parked.location

import android.content.Context
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

/**
 * Combines Play Services' fused provider with the platform's own providers.
 *
 * Both are queried in parallel for the whole window. A genuinely good fix ends the wait
 * immediately. Otherwise the best candidate is chosen using a time-aware score, so a
 * slightly less accurate fix captured right at disconnect can beat a very accurate fix
 * captured after the user has already walked away from the car. Fused locations combine
 * GPS, Wi-Fi and cell data and are often better than raw GPS in built-up areas.
 */
class CompositeLocationSource(
    context: Context,
    private val onDecision: (String) -> Unit = {},
) : LocationSource {

    private val platform = PlatformLocationSource(context)
    private val fused = FusedLocationSource(context).takeIf { it.isAvailable }

    override suspend fun getCurrentFix(
        timeoutMs: Long,
        acceptableAccuracyMeters: Float,
    ): LocationFix? = coroutineScope {
        val budget = timeoutMs.coerceAtLeast(1_000L)
        val startedAtWallMs = System.currentTimeMillis()
        val excellentThreshold = minOf(EXCELLENT_ACCURACY_METERS, acceptableAccuracyMeters)
        val playDeferred = fused?.let { source ->
            async {
                Candidate(
                    source = SOURCE_PLAY_FUSED,
                    fix = source.getCurrentFix(budget, acceptableAccuracyMeters),
                )
            }
        }
        val platformDeferred = async {
            Candidate(
                source = SOURCE_PLATFORM,
                fix = platform.getCurrentFix(budget, acceptableAccuracyMeters),
            )
        }

        var playPending = playDeferred != null
        var platformPending = true
        val candidates = mutableListOf<Candidate>()
        var excellent: Candidate? = null

        while (playPending || platformPending) {
            val play = playDeferred
            val candidate = select<Candidate> {
                if (playPending && play != null) play.onAwait { it }
                if (platformPending) platformDeferred.onAwait { it }
            }
            if (candidate.source == SOURCE_PLAY_FUSED) {
                playPending = false
            } else {
                platformPending = false
            }
            val fix = candidate.fix ?: continue
            candidates += candidate
            if (fix.accuracyMeters in 0f..excellentThreshold &&
                fix.timeEpochMs >= startedAtWallMs - EXCELLENT_MAX_AGE_BEFORE_MS &&
                fix.timeEpochMs <= startedAtWallMs + EXCELLENT_MAX_AGE_AFTER_MS
            ) {
                excellent = candidate
                break
            }
        }

        playDeferred?.cancel()
        platformDeferred.cancel()

        val winner = excellent ?: candidates.minByOrNull { candidate ->
            effectiveErrorMeters(candidate.fix!!, startedAtWallMs)
        }
        winner?.let { candidate ->
            val fix = candidate.fix!!
            val offsetSeconds = ((fix.timeEpochMs - startedAtWallMs) / 1000.0).roundToInt()
            onDecision(
                "Wybrano pomiar ${candidate.source} / ${fix.provider}: " +
                    "${fix.accuracyMeters.toInt()} m, ${offsetSeconds} s od startu",
            )
        }
        winner?.fix
    }

    override fun getLastKnownFix(): LocationFix? = platform.getLastKnownFix()

    /**
     * Reported accuracy plus the distance the user may have covered between the start of
     * the measurement and the fix's own timestamp. Works in both directions: a very
     * accurate fix from minutes ago is just as useless as a perfect fix taken 20 s later,
     * when the user is already far from the car. Without this, a cached fused location
     * could win simply because its accuracy number is low.
     */
    private fun effectiveErrorMeters(fix: LocationFix, startedAtWallMs: Long): Double {
        val offsetSeconds = abs(fix.timeEpochMs - startedAtWallMs) / 1000.0
        return fix.accuracyMeters + offsetSeconds * WALKING_DRIFT_METERS_PER_SECOND
    }

    private data class Candidate(
        val source: String,
        val fix: LocationFix?,
    )

    private companion object {
        const val SOURCE_PLAY_FUSED = "fused Play"
        const val SOURCE_PLATFORM = "Android"
        const val EXCELLENT_ACCURACY_METERS = 10f
        const val EXCELLENT_MAX_AGE_BEFORE_MS = 3_000L
        const val EXCELLENT_MAX_AGE_AFTER_MS = 10_000L
        const val WALKING_DRIFT_METERS_PER_SECOND = 1.0
    }
}
