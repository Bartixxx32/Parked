package io.github.bartixxx32.parked.location

import android.os.Build
import android.location.Location
import kotlin.math.abs

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeEpochMs: Long,
    val provider: String,
    val isMock: Boolean = false,
    val isLastKnown: Boolean = false,
)

enum class LocationValidity {
    VALID,
    INVALID_COORDINATES,
    UNKNOWN_ACCURACY,
    LOW_ACCURACY,
    STALE,
}

object LocationValidator {

    fun coordinatesValid(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(abs(latitude) < 1e-7 && abs(longitude) < 1e-7)

    fun validate(
        fix: LocationFix,
        nowEpochMs: Long,
        maxAccuracyMeters: Float,
        maxAgeMs: Long,
    ): LocationValidity {
        if (!coordinatesValid(fix.latitude, fix.longitude)) {
            return LocationValidity.INVALID_COORDINATES
        }
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters < 0f) {
            return LocationValidity.UNKNOWN_ACCURACY
        }
        val age = nowEpochMs - fix.timeEpochMs
        if (fix.timeEpochMs <= 0L || age > maxAgeMs || age < -60_000L) {
            return LocationValidity.STALE
        }
        if (fix.accuracyMeters > maxAccuracyMeters) {
            return LocationValidity.LOW_ACCURACY
        }
        return LocationValidity.VALID
    }
}

internal fun Location.toLocationFix(isLastKnown: Boolean): LocationFix {
    val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        isMock
    } else {
        @Suppress("DEPRECATION")
        isFromMockProvider
    }
    return LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else -1f,
        timeEpochMs = if (time > 0L) time else System.currentTimeMillis(),
        provider = provider ?: "unknown",
        isMock = mock,
        isLastKnown = isLastKnown,
    )
}
