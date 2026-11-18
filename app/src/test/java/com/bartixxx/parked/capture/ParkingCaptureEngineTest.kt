package com.bartixxx.parked.capture

import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.db.SpotSource
import com.bartixxx.parked.data.settings.AppSettings
import com.bartixxx.parked.location.LocationFix
import com.bartixxx.parked.location.LocationSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingCaptureEngineTest {

    private val now = 1_700_000_000_000L
    private val settings = AppSettings(
        gpsTimeoutSeconds = 20,
        maxAccuracyMeters = 100,
        allowFallbackLastKnown = true,
        fallbackMaxAgeHours = 12,
    )

    private class FakeLocationSource(
        var current: LocationFix? = null,
        var lastKnown: LocationFix? = null,
    ) : LocationSource {
        var currentRequests = 0

        override suspend fun getCurrentFix(
            timeoutMs: Long,
            acceptableAccuracyMeters: Float,
        ): LocationFix? {
            currentRequests++
            return current
        }

        override fun getLastKnownFix(): LocationFix? = lastKnown
    }

    private class RecordingSink(private val resultId: Long = 1L) : ParkingSink {
        val inserted = mutableListOf<ParkingSpot>()

        override suspend fun insertIfNew(spot: ParkingSpot): Long {
            inserted += spot
            return resultId
        }
    }

    private fun freshFix(
        accuracy: Float = 8f,
        latitude: Double = 52.2297,
        longitude: Double = 21.0122,
        time: Long = now - 3_000L,
    ) = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy,
        timeEpochMs = time,
        provider = "gps",
    )

    private fun engine(source: FakeLocationSource, sink: RecordingSink, clock: () -> Long = { now }) =
        ParkingCaptureEngine(locationSource = source, sink = sink, clock = clock)

    @Test
    fun `fresh accurate fix is saved for the automatic cycle`() = runBlocking {
        val source = FakeLocationSource(current = freshFix())
        val sink = RecordingSink(resultId = 42L)
        val outcome = engine(source, sink).capture(
            CaptureRequest(
                trigger = CaptureTrigger.AUTO,
                cycleId = 7L,
                deviceAddress = "AA:BB",
                deviceName = "Radio",
            ),
            settings,
        )

        val saved = outcome as CaptureOutcome.Saved
        assertEquals(42L, saved.spot.id)
        assertFalse(saved.usedFallback)
        assertEquals(SpotSource.AUTO, saved.spot.source)
        assertEquals(7L, saved.spot.cycleId)
        assertEquals("Radio", saved.spot.deviceName)
        assertEquals(8f, saved.spot.accuracyMeters)
        assertEquals(1, sink.inserted.size)
    }

    @Test
    fun `no gps and no last known location fails without writing`() = runBlocking {
        val source = FakeLocationSource()
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 1L), settings)

        assertEquals(CaptureFailure.NO_LOCATION, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
        assertEquals(2, source.currentRequests)
    }

    @Test
    fun `inaccurate current fix uses emergency last known point`() = runBlocking {
        val source = FakeLocationSource(
            current = freshFix(accuracy = 500f),
            lastKnown = freshFix(accuracy = 18f, time = now - 3_600_000L),
        )
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 3L), settings)

        val saved = outcome as CaptureOutcome.Saved
        assertTrue(saved.usedFallback)
        assertTrue(saved.spot.isFallback)
        assertEquals(SpotSource.FALLBACK, saved.spot.source)
        assertEquals(18f, saved.spot.accuracyMeters)
    }

    @Test
    fun `inaccurate fix without fallback is rejected`() = runBlocking {
        val source = FakeLocationSource(current = freshFix(accuracy = 500f))
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 3L), settings)

        assertEquals(CaptureFailure.LOW_ACCURACY, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `fallback older than configured limit is rejected`() = runBlocking {
        val source = FakeLocationSource(
            lastKnown = freshFix(accuracy = 20f, time = now - 13 * 3_600_000L),
        )
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 3L), settings)

        assertEquals(CaptureFailure.STALE_LOCATION, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `fallback can be disabled`() = runBlocking {
        val source = FakeLocationSource(
            current = freshFix(accuracy = 500f),
            lastKnown = freshFix(accuracy = 10f, time = now - 60_000L),
        )
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(
            CaptureRequest(CaptureTrigger.AUTO, 4L),
            settings.copy(allowFallbackLastKnown = false),
        )

        assertEquals(CaptureFailure.LOW_ACCURACY, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `zero zero gps fix is never stored`() = runBlocking {
        val source = FakeLocationSource(
            current = freshFix(latitude = 0.0, longitude = 0.0),
            lastKnown = freshFix(latitude = 0.0, longitude = 0.0),
        )
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 5L), settings)

        assertEquals(CaptureFailure.INVALID_COORDINATES, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `duplicate cycle write is reported as duplicate`() = runBlocking {
        val source = FakeLocationSource(current = freshFix())
        val sink = RecordingSink(resultId = -1L)

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO, 9L), settings)

        assertEquals(CaptureOutcome.Duplicate, outcome)
    }

    @Test
    fun `manual save has no cycle id and is marked manual`() = runBlocking {
        val source = FakeLocationSource(current = freshFix())
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(
            CaptureRequest(trigger = CaptureTrigger.MANUAL),
            settings,
        )

        val saved = outcome as CaptureOutcome.Saved
        assertNull(saved.spot.cycleId)
        assertEquals(SpotSource.MANUAL, saved.spot.source)
        assertNull(saved.spot.deviceAddress)
    }

    @Test
    fun `automatic save without cycle id is rejected defensively`() = runBlocking {
        val source = FakeLocationSource(current = freshFix())
        val sink = RecordingSink()

        val outcome = engine(source, sink).capture(CaptureRequest(CaptureTrigger.AUTO), settings)

        assertEquals(CaptureFailure.INTERNAL_ERROR, (outcome as CaptureOutcome.Failed).reason)
        assertTrue(sink.inserted.isEmpty())
    }

    @Test
    fun `fix acquired at disconnect is saved later without moving it`() = runBlocking {
        val fix = freshFix(
            accuracy = 6f,
            latitude = 52.2297123,
            longitude = 21.0122987,
        )
        val source = FakeLocationSource(current = fix)
        val sink = RecordingSink(resultId = 11L)
        var clockNow = now
        val engine = ParkingCaptureEngine(
            locationSource = source,
            sink = sink,
            clock = { clockNow },
        )

        val acquisition = engine.acquire(settings) as AcquisitionOutcome.Acquired
        assertEquals(1, source.currentRequests)
        assertTrue(sink.inserted.isEmpty())
        assertEquals(fix.timeEpochMs, acquisition.fix.timeEpochMs)
        assertEquals(fix.latitude, acquisition.fix.latitude, 0.0)
        assertEquals(fix.longitude, acquisition.fix.longitude, 0.0)

        clockNow = now + 15_000L
        val outcome = engine.save(
            CaptureRequest(
                trigger = CaptureTrigger.AUTO,
                cycleId = 12L,
                deviceAddress = "AA:BB",
                deviceName = "Radio",
            ),
            acquisition.fix,
        ) as CaptureOutcome.Saved

        assertEquals(11L, outcome.spot.id)
        assertEquals(fix.latitude, outcome.spot.latitude, 0.0)
        assertEquals(fix.longitude, outcome.spot.longitude, 0.0)
        assertEquals(now + 15_000L, outcome.spot.savedAtEpochMs)
        assertEquals(fix.timeEpochMs, outcome.spot.fixTimeEpochMs)
        assertEquals(6f, outcome.spot.accuracyMeters)
        assertEquals(SpotSource.AUTO, outcome.spot.source)
    }
}
