package com.bartixxx.parked.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilySentPhotoDao {

    @Query("SELECT * FROM family_sent_photos WHERE spotId = :spotId")
    suspend fun forSpot(spotId: Long): List<FamilySentPhotoEntity>

    @Query(
        "SELECT * FROM family_sent_photos " +
            "WHERE groupId = :groupId AND revokeRequested = 0",
    )
    fun observeActiveForGroup(groupId: String): Flow<List<FamilySentPhotoEntity>>

    @Query(
        "SELECT COUNT(*) FROM family_sent_photos " +
            "WHERE spotId = :spotId AND revokeRequested = 0",
    )
    suspend fun activeCountForSpot(spotId: Long): Int

    @Query(
        "SELECT * FROM family_sent_photos " +
            "WHERE groupId = :groupId AND revokeRequested = 1",
    )
    suspend fun pendingRevocations(groupId: String): List<FamilySentPhotoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: FamilySentPhotoEntity)

    @Query("UPDATE family_sent_photos SET revokeRequested = 1 WHERE spotId = :spotId")
    suspend fun markRevocationRequested(spotId: Long)

    @Query("UPDATE family_sent_photos SET revokeRequested = 1")
    suspend fun markAllRevocationRequested()

    @Query("DELETE FROM family_sent_photos WHERE photoId = :photoId")
    suspend fun delete(photoId: String)

    @Query("DELETE FROM family_sent_photos WHERE spotId = :spotId")
    suspend fun deleteForSpot(spotId: Long)

    @Query("DELETE FROM family_sent_photos WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)

    @Query("DELETE FROM family_sent_photos WHERE groupId = :groupId AND sentAtEpochMs < :cutoff")
    suspend fun deleteOlderThan(groupId: String, cutoff: Long)
}
