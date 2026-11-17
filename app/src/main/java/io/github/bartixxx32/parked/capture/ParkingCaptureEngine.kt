package io.github.bartixxx32.parked.capture

import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.data.db.SpotSource
import io.github.bartixxx32.parked.data.settings.AppSettings
import io.github.bartixxx32.parked.location.LocationFix
import io.github.bartixxx32.parked.location.LocationSource
import io.github.bartixxx32.parked.location.LocationValidity
import io.github.bartixxx32.parked.location.LocationValidator

enum class CaptureTrigger {
    AUTO,
    MANUAL,
}

data class CaptureRequest(
    val trigger: CaptureTrigger,
    val cycleId: Long? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
)

enum class CaptureFailure {
    NO_LOCATION,
    INVALID_COORDINATES,
    LOW_ACCURACY,
    STALE_LOCATION,
    STORAGE_ERROR,
    INTERNAL_ERROR,
}

sealed interface CaptureOutcome {
    data class Saved(
        val spot: ParkingSpot,
        val usedFallback: Boolean,
    ) : CaptureOutcome

    data object Duplicate : CaptureOutcome

    data class Failed(
        val reason: CaptureFailure,
        val detail: String? = null,
    ) : CaptureOutcome
}

interface ParkingSink {
    suspend fun insertIfNew(spot: ParkingSpot): Long
}

/**
 * Acquires and validates a fix, then writes it to the database.
 *
 * Two fresh-location attempts are made. If they fail and the user allows it, the best
 * last-known fix is stored as an explicitly marked emergency/fallback point. Invalid
 * coordinates (including 0,0), missing accuracy, excessive inaccuracy and stale fixes are
 * never stored.
 */
class ParkingCaptureEngine(
    private val locationSource: LocationSource,
    private val sink: ParkingSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun capture(
        request: CaptureRequest,
        settings: AppSettings,
    ): CaptureOutcome {
        if (request.trigger == CaptureTrigger.AUTO && request.cycleId == null) {
            return CaptureOutcome.Failed(CaptureFailure.INTERNAL_ERROR, "Brak identyfikatora cyklu")
        }

        val maxAccuracy = settings.maxAccuracyMeters.toFloat()
        val timeoutMs = settings.gpsTimeoutSeconds * 1000L
        val attemptTimeout = (timeoutMs / ATTEMPTS).coerceAtLeast(1_000L)

        var chosen: LocationFix? = null
        var lastRejectedValidity: LocationValidity? = null

        for (attempt in 0 until ATTEMPTS) {
            if (chosen != null) break
            val fix = locationSource.getCurrentFix(attemptTimeout, maxAccuracy) ?: continue
            val verdict = LocationValidator.validate(
                fix = fix,
                nowEpochMs = clock(),
                maxAccuracyMeters = maxAccuracy,
                maxAgeMs = MAX_CURRENT_FIX_AGE_MS,
            )
            if (verdict == LocationValidity.VALID) {
                chosen = fix
            } else {
                lastRejectedValidity = verdict
            }
        }

        var usedFallback = false
        var fallbackValidity: LocationValidity? = null

        if (chosen == null && settings.allowFallbackLastKnown) {
            val lastKnown = locationSource.getLastKnownFix()
            if (lastKnown != null) {
                val verdict = LocationValidator.validate(
                    fix = lastKnown,
                    nowEpochMs = clock(),
                    maxAccuracyMeters = maxAccuracy,
                    maxAgeMs = settings.fallbackMaxAgeHours * 3_600_000L,
                )
                if (verdict == LocationValidity.VALID) {
                    chosen = lastKnown
                    usedFallback = true
                } else {
                    fallbackValidity = verdict
                }
            }
        }

        val fix = chosen ?: return CaptureOutcome.Failed(
            reason = when {
                lastRejectedValidity == LocationValidity.LOW_ACCURACY ||
                    fallbackValidity == LocationValidity.LOW_ACCURACY -> CaptureFailure.LOW_ACCURACY

                lastRejectedValidity == LocationValidity.STALE ||
                    fallbackValidity == LocationValidity.STALE -> CaptureFailure.STALE_LOCATION

                lastRejectedValidity == LocationValidity.INVALID_COORDINATES ||
                    fallbackValidity == LocationValidity.INVALID_COORDINATES ->
                    CaptureFailure.INVALID_COORDINATES

                else -> CaptureFailure.NO_LOCATION
            },
            detail = describe(lastRejectedValidity ?: fallbackValidity),
        )

        val now = clock()
        val source = when {
            usedFallback -> SpotSource.FALLBACK
            request.trigger == CaptureTrigger.AUTO -> SpotSource.AUTO
            else -> SpotSource.MANUAL
        }
        val spot = ParkingSpot(
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            savedAtEpochMs = now,
            fixTimeEpochMs = fix.timeEpochMs,
            source = source,
            isFallback = usedFallback,
            isMock = fix.isMock,
            provider = fix.provider,
            deviceAddress = request.deviceAddress,
            deviceName = request.deviceName,
            cycleId = if (request.trigger == CaptureTrigger.AUTO) request.cycleId else null,
        )

        val id = try {
            sink.insertIfNew(spot)
        } catch (error: Exception) {
            return CaptureOutcome.Failed(CaptureFailure.STORAGE_ERROR, error.message)
        }
        if (id < 0L) {
            return CaptureOutcome.Duplicate
        }
        return CaptureOutcome.Saved(spot.copy(id = id), usedFallback)
    }

    private fun describe(validity: LocationValidity?): String? = when (validity) {
        LocationValidity.INVALID_COORDINATES -> "Nieprawidłowe współrzędne"
        LocationValidity.UNKNOWN_ACCURACY -> "Brak informacji o dokładności"
        LocationValidity.LOW_ACCURACY -> "Zbyt niska dokładność GPS"
        LocationValidity.STALE -> "Lokalizacja jest zbyt stara"
        LocationValidity.VALID, null -> null
    }

    companion object {
        private const val ATTEMPTS = 2
        private const val MAX_CURRENT_FIX_AGE_MS = 2 * 60_000L
    }
}
