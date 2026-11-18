package io.github.bartixxx32.parked.ui

import io.github.bartixxx32.parked.data.db.ParkingSpot
import io.github.bartixxx32.parked.data.db.SpotSource
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

    fun accuracy(spot: ParkingSpot): String =
        spot.accuracyMeters?.let { "${it.toInt()} m" } ?: "brak danych"

    fun source(spot: ParkingSpot): String = when (spot.source) {
        SpotSource.AUTO -> "Automatyczny"
        SpotSource.MANUAL -> "Ręczny"
        SpotSource.FALLBACK -> "Awaryjny"
        else -> spot.source
    }

    fun provider(provider: String?): String = when (provider?.lowercase(Locale.ROOT)) {
        null, "" -> "brak danych"
        "gps" -> "GPS"
        "fused" -> "Fused (Android)"
        "network" -> "Sieć / Wi-Fi"
        "passive" -> "Pasywny"
        else -> provider ?: "brak danych"
    }

    fun providerShort(provider: String?): String? = when (provider?.lowercase(Locale.ROOT)) {
        null, "" -> null
        "gps" -> "GPS"
        "fused" -> "Fused"
        "network" -> "Sieć"
        "passive" -> "Pasywny"
        else -> provider
    }

    fun age(epochMs: Long): String {
        val seconds = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 60 -> "$seconds s temu"
            seconds < 3_600 -> "${seconds / 60} min temu"
            seconds < 86_400 -> "${seconds / 3_600} h temu"
            else -> "${seconds / 86_400} dni temu"
        }
    }
}
