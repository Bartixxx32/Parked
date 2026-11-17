package io.github.bartixxx32.parked.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ParkingSpotDao {

    @Query("SELECT * FROM parking_spots ORDER BY savedAtEpochMs DESC")
    fun observeAll(): Flow<List<ParkingSpot>>

    @Query("SELECT * FROM parking_spots ORDER BY savedAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<ParkingSpot?>

    @Query("SELECT * FROM parking_spots ORDER BY savedAtEpochMs DESC LIMIT 1")
    suspend fun latest(): ParkingSpot?

    @Query("SELECT * FROM parking_spots ORDER BY savedAtEpochMs DESC")
    suspend fun all(): List<ParkingSpot>

    @Query("SELECT * FROM parking_spots WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ParkingSpot?

    @Query("SELECT * FROM parking_spots WHERE cycleId = :cycleId LIMIT 1")
    suspend fun byCycleId(cycleId: Long): ParkingSpot?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(spot: ParkingSpot): Long

    @Query("UPDATE parking_spots SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Query("UPDATE parking_spots SET photoPath = :photoPath WHERE id = :id")
    suspend fun updatePhotoPath(id: Long, photoPath: String?)

    @Query("DELETE FROM parking_spots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM parking_spots")
    suspend fun deleteAll()

    @Query("SELECT MAX(cycleId) FROM parking_spots")
    suspend fun maxCycleId(): Long?

    @Query("SELECT COUNT(*) FROM parking_spots")
    suspend fun count(): Int
}
