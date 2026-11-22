package com.bartixxx.parked.data.stats

import com.bartixxx.parked.data.db.ParkingSpot
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Statistics calculated only from the local parking history. Nothing is sent
 * anywhere, so the numbers are available offline and for legacy databases.
 */
data class ParkingStats(
    val totalSpots: Int = 0,
    val spotsThisMonth: Int = 0,
    val spotsLast30Days: Int = 0,
    /** Average time between consecutive saves, ignoring outliers. */
    val averageParkingMinutes: Long? = null,
    val longestParkingMinutes: Long? = null,
    val currentParkingMinutes: Long? = null,
    val busiestWeekday: DayOfWeek? = null,
)

/**
 * A save marks the start of a parking session, so the time between two
 * consecutive saves is one session. Very short gaps are usually duplicate
 * manual saves and multi-day gaps usually mean the app was not used; both are
 * excluded from the average and the longest session.
 */
fun calculateParkingStats(
    spots: List<ParkingSpot>,
    nowEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): ParkingStats {
    if (spots.isEmpty()) return ParkingStats()

    val sorted = spots.sortedBy { it.savedAtEpochMs }
    val now = Instant.ofEpochMilli(nowEpochMs)
    val currentMonth = YearMonth.from(now.atZone(zoneId))
    val monthStart = currentMonth.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val nextMonthStart = currentMonth.plusMonths(1).atDay(1)
        .atStartOfDay(zoneId).toInstant().toEpochMilli()
    val last30Start = nowEpochMs - THIRTY_DAYS_MS

    val intervals = sorted.zipWithNext { previous, next ->
        next.savedAtEpochMs - previous.savedAtEpochMs
    }.filter { it in MIN_SESSION_MS..MAX_SESSION_MS }

    val latest = sorted.last()
    val currentDuration = (nowEpochMs - latest.savedAtEpochMs)
        .takeIf { it in 0..MAX_SESSION_MS }

    return ParkingStats(
        totalSpots = sorted.size,
        spotsThisMonth = sorted.count {
            it.savedAtEpochMs >= monthStart && it.savedAtEpochMs < nextMonthStart
        },
        spotsLast30Days = sorted.count { it.savedAtEpochMs >= last30Start },
        averageParkingMinutes = intervals
            .takeIf { it.isNotEmpty() }
            ?.let { (it.sum().toDouble() / it.size / 60_000.0).roundToLong() },
        longestParkingMinutes = intervals.maxOrNull()?.div(60_000L),
        currentParkingMinutes = currentDuration?.div(60_000L),
        busiestWeekday = sorted
            .groupingBy { Instant.ofEpochMilli(it.savedAtEpochMs).atZone(zoneId).dayOfWeek }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key,
    )
}

private const val MIN_SESSION_MS = 15L * 60_000L
private const val MAX_SESSION_MS = 7L * 24L * 60L * 60_000L
private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60_000L
