package com.bartixxx.parked.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ParkingSpot::class, DiagnosticsEvent::class, FamilyPhotoEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun parkingSpotDao(): ParkingSpotDao
    abstract fun diagnosticsEventDao(): DiagnosticsEventDao
    abstract fun familyPhotoDao(): FamilyPhotoDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_photos` (" +
                        "`id` TEXT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`senderDeviceId` TEXT NOT NULL, " +
                        "`senderLabel` TEXT NOT NULL, " +
                        "`savedAtEpochMs` INTEGER NOT NULL, " +
                        "`receivedAtEpochMs` INTEGER NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_family_photos_groupId` " +
                        "ON `family_photos` (`groupId`)",
                )
            }
        }
    }
}
