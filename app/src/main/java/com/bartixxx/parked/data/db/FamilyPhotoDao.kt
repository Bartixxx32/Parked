package com.bartixxx.parked.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FamilyPhotoDao {

    @Query("SELECT * FROM family_photos WHERE groupId = :groupId ORDER BY savedAtEpochMs DESC")
    fun observeForGroup(groupId: String): Flow<List<FamilyPhotoEntity>>

    @Query("SELECT * FROM family_photos WHERE groupId = :groupId ORDER BY savedAtEpochMs DESC")
    suspend fun allForGroup(groupId: String): List<FamilyPhotoEntity>

    @Query("SELECT * FROM family_photos WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): FamilyPhotoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: FamilyPhotoEntity)

    @Query("DELETE FROM family_photos WHERE groupId = :groupId")
    suspend fun deleteForGroup(groupId: String)
}
