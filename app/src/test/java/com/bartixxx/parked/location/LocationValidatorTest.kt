package com.bartixxx.parked.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidatorTest {

    private val now = 1_700_000_000_000L

    private fun fix(
        latitude: Double = 52.2297,
        longitude: Double = 21.0122,
        accuracy: Float = 12f,
        time: Long = now - 5_000L,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        timeEpochMs = time,
        provider = "gps",
    )

    @Test
    fun `valid fix is accepted`() {
        assertEquals(
            LocationValidity.VALID,
            LocationValidator.validate(fix(), now, maxAccuracyMeters = 100f, maxAgeMs = 60_000L),
        )
    }

    @Test
    fun `zero zero coordinates are rejected`() {
        assertFalse(LocationValidator.coordinatesValid(0.0, 0.0))
        assertEquals(
            LocationValidity.INVALID_COORDINATES,
            LocationValidator.validate(fix(latitude = 0.0, longitude = 0.0), now, 100f, 60_000L),
        )
    }

    @Test
    fun `coordinates outside valid range are rejected`() {
        assertFalse(LocationValidator.coordinatesValid(91.0, 10.0))
        assertFalse(LocationValidator.coordinatesValid(10.0, 181.0))
        assertFalse(LocationValidator.coordinatesValid(Double.NaN, 10.0))
        assertFalse(LocationValidator.coordinatesValid(10.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `inaccurate fix is rejected`() {
        assertEquals(
            LocationValidity.LOW_ACCURACY,
            LocationValidator.validate(fix(accuracy = 250f), now, 100f, 60_000L),
        )
    }

    @Test
    fun `unknown accuracy is rejected`() {
        assertEquals(
            LocationValidity.UNKNOWN_ACCURACY,
            LocationValidator.validate(fix(accuracy = -1f), now, 100f, 60_000L),
        )
    }

    @Test
    fun `stale fix is rejected`() {
        assertEquals(
            LocationValidity.STALE,
            LocationValidator.validate(fix(time = now - 61_000L), now, 100f, 60_000L),
        )
    }

    @Test
    fun `location from the future beyond tolerance is rejected`() {
        assertEquals(
            LocationValidity.STALE,
            LocationValidator.validate(fix(time = now + 120_000L), now, 100f, 60_000L),
        )
    }

    @Test
    fun `fallback fix allowed to be much older`() {
        val old = fix(time = now - 6 * 3_600_000L)
        assertEquals(
            LocationValidity.VALID,
            LocationValidator.validate(old, now, 100f, 12 * 3_600_000L),
        )
        assertTrue(LocationValidator.coordinatesValid(old.latitude, old.longitude))
    }
}
