package com.bartixxx.parked.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bartixxx.parked.R
import com.bartixxx.parked.data.stats.ParkingStats
import java.time.format.TextStyle
import java.util.Locale

/** Local-only summary card shown at the top of the history screen. */
@Composable
fun StatisticsSection(stats: ParkingStats) {
    SectionCard(title = stringResource(R.string.stats_title)) {
        InfoRow(stringResource(R.string.stats_total), stats.totalSpots.toString())
        InfoRow(stringResource(R.string.stats_this_month), stats.spotsThisMonth.toString())
        InfoRow(stringResource(R.string.stats_last_30_days), stats.spotsLast30Days.toString())
        stats.currentParkingMinutes?.let { minutes ->
            InfoRow(stringResource(R.string.stats_current_duration), durationText(minutes))
        }
        val averageMinutes = stats.averageParkingMinutes
        InfoRow(
            stringResource(R.string.stats_avg_duration),
            if (averageMinutes != null) {
                durationText(averageMinutes)
            } else {
                stringResource(R.string.stats_not_enough)
            },
        )
        val longestMinutes = stats.longestParkingMinutes
        InfoRow(
            stringResource(R.string.stats_longest_duration),
            if (longestMinutes != null) {
                durationText(longestMinutes)
            } else {
                stringResource(R.string.stats_not_enough)
            },
        )
        InfoRow(
            stringResource(R.string.stats_busiest_day),
            stats.busiestWeekday?.getDisplayName(TextStyle.FULL, Locale.getDefault())
                ?: stringResource(R.string.no_data),
        )
        Text(
            text = stringResource(R.string.stats_local_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun durationText(minutes: Long): String {
    val safe = minutes.coerceAtLeast(0L)
    val hours = safe / 60
    val rest = safe % 60
    return when {
        hours > 0L && rest > 0L -> stringResource(R.string.stats_duration_hm, hours, rest)
        hours > 0L -> stringResource(R.string.stats_duration_hm, hours, 0L)
        else -> stringResource(R.string.stats_duration_m, safe)
    }
}
