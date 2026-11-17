package io.github.bartixxx32.parked.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ParkingSpot::class, DiagnosticsEvent::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun parkingSpotDao(): ParkingSpotDao
    abstract fun diagnosticsEventDao(): DiagnosticsEventDao
}
