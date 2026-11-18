package com.bartixxx.parked.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SpotSource {
    const val AUTO = "AUTO"
    const val MANUAL = "MANUAL"
    const val FALLBACK = "FALLBACK"
}

/**
 * A saved parking location.
 *
 * @param cycleId identifies one Bluetooth connect -> disconnect cycle for automatic saves.
 *                A unique index makes automatic saves idempotent even across process restarts.
 * @param fixTimeEpochMs time reported by the location provider (can be older than save time).
 */
@Entity(
    tableName = "parking_spots",
    indices = [
        Index(value = ["cycleId"], unique = true),
        Index(value = ["savedAtEpochMs"]),
    ],
)
data class ParkingSpot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val savedAtEpochMs: Long,
    val fixTimeEpochMs: Long,
    val note: String = "",
    val photoPath: String? = null,
    val source: String = SpotSource.AUTO,
    val isFallback: Boolean = false,
    val isMock: Boolean = false,
    val provider: String? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val cycleId: Long? = null,
)

val ParkingSpot.fixAgeMs: Long
    get() = (savedAtEpochMs - fixTimeEpochMs).coerceAtLeast(0L)
