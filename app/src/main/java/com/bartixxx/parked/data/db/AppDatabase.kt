package com.bartixxx.parked.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ParkingSpot::class,
        DiagnosticsEvent::class,
        FamilyPhotoEntity::class,
        FamilySentPhotoEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun parkingSpotDao(): ParkingSpotDao
    abstract fun diagnosticsEventDao(): DiagnosticsEventDao
    abstract fun familyPhotoDao(): FamilyPhotoDao
    abstract fun familySentPhotoDao(): FamilySentPhotoDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_sent_photos` (" +
                        "`photoId` TEXT NOT NULL, " +
                        "`spotId` INTEGER NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`sentAtEpochMs` INTEGER NOT NULL, " +
                        "`revokeRequested` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`photoId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_family_sent_photos_spotId` " +
                        "ON `family_sent_photos` (`spotId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_family_sent_photos_revokeRequested` " +
                        "ON `family_sent_photos` (`revokeRequested`)",
                )
            }
        }
    }
}
