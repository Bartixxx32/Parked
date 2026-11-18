package com.bartixxx.parked.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bartixxx.parked.R
import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.db.SpotSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun time(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    @Composable
    fun accuracy(spot: ParkingSpot): String =
        spot.accuracyMeters?.let { "${it.toInt()} m" } ?: stringResource(R.string.no_data)

    @Composable
    fun source(spot: ParkingSpot): String = when (spot.source) {
        SpotSource.AUTO -> stringResource(R.string.source_auto)
        SpotSource.MANUAL -> stringResource(R.string.source_manual)
        SpotSource.FALLBACK -> stringResource(R.string.source_fallback)
        else -> spot.source
    }

    @Composable
    fun provider(provider: String?): String = when (provider?.lowercase(Locale.ROOT)) {
        null, "" -> stringResource(R.string.no_data)
        "gps" -> "GPS"
        "fused" -> "Fused (Android)"
        "network" -> stringResource(R.string.provider_network)
        "passive" -> stringResource(R.string.provider_passive)
        "manual" -> stringResource(R.string.source_manual)
        "unknown" -> stringResource(R.string.provider_unknown)
        else -> provider ?: stringResource(R.string.no_data)
    }

    @Composable
    fun providerShort(provider: String?): String? = when (provider?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "gps" -> "GPS"
        "fused" -> "Fused"
        "network" -> stringResource(R.string.provider_network_short)
        "passive" -> stringResource(R.string.provider_passive)
        "manual" -> stringResource(R.string.source_manual)
        "unknown" -> null
        else -> provider
    }

    @Composable
    fun age(epochMs: Long): String {
        val seconds = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60 -> stringResource(R.string.age_seconds, seconds)
            seconds < 3_600 -> stringResource(R.string.age_minutes, seconds / 60)
            seconds < 86_400 -> stringResource(R.string.age_hours, seconds / 3_600)
            else -> {
                val days = seconds / 86_400
                pluralStringResource(R.plurals.age_days, days.toInt(), days)
            }
        }
    }
}
