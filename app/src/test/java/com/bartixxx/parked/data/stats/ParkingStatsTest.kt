package com.bartixxx.parked.data.stats

import com.bartixxx.parked.data.db.ParkingSpot
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkingStatsTest {

    private fun spot(at: String, note: String = "") = ParkingSpot(
        latitude = 52.0,
        longitude = 21.0,
        savedAtEpochMs = Instant.parse(at).toEpochMilli(),
        fixTimeEpochMs = Instant.parse(at).toEpochMilli(),
        note = note,
    )

    @Test
    fun `empty history has no statistics`() {
        val stats = calculateParkingStats(emptyList(), zoneId = ZoneOffset.UTC)

        assertEquals(0, stats.totalSpots)
        assertNull(stats.averageParkingMinutes)
        assertNull(stats.longestParkingMinutes)
        assertNull(stats.busiestWeekday)
    }

    @Test
    fun `consecutive saves produce parking durations`() {
        val spots = listOf(
            spot("2026-11-01T10:00:00Z"),
            spot("2026-11-01T11:00:00Z"),
            spot("2026-11-01T13:00:00Z"),
            spot("2026-11-01T20:00:00Z"),
        )

        val stats = calculateParkingStats(
            spots = spots,
            nowEpochMs = Instant.parse("2026-11-01T21:00:00Z").toEpochMilli(),
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(4, stats.totalSpots)
        assertEquals(4, stats.spotsThisMonth)
        assertEquals(4, stats.spotsLast30Days)
        assertEquals(200L, stats.averageParkingMinutes)
        assertEquals(420L, stats.longestParkingMinutes)
        assertEquals(60L, stats.currentParkingMinutes)
        assertEquals(DayOfWeek.SUNDAY, stats.busiestWeekday)
    }

    @Test
    fun `very short and very long gaps are ignored`() {
        val spots = listOf(
            spot("2026-11-01T10:00:00Z"),
            spot("2026-11-01T10:05:00Z"),
            spot("2026-11-01T12:00:00Z"),
            spot("2026-11-10T12:00:00Z"),
        )

        val stats = calculateParkingStats(
            spots = spots,
            nowEpochMs = Instant.parse("2026-11-10T13:00:00Z").toEpochMilli(),
            zoneId = ZoneOffset.UTC,
        )

        // Only the 115 minute gap is a plausible parking session.
        assertEquals(115L, stats.averageParkingMinutes)
        assertEquals(115L, stats.longestParkingMinutes)
    }

    @Test
    fun `month and last thirty days are counted separately`() {
        val now = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
        val spots = listOf(
            spot("2026-10-10T12:00:00Z"),
            spot("2026-10-28T12:00:00Z"),
            spot("2026-11-02T12:00:00Z"),
        )

        val stats = calculateParkingStats(spots, now, ZoneOffset.UTC)

        assertEquals(3, stats.totalSpots)
        assertEquals(1, stats.spotsThisMonth)
        assertEquals(2, stats.spotsLast30Days)
    }
}
