package com.bartixxx.parked.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsEventDao {

    @Query("SELECT * FROM diagnostics_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticsEvent>>

    @Query("SELECT * FROM diagnostics_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticsEvent>

    @Insert
    suspend fun insert(event: DiagnosticsEvent): Long

    @Query("DELETE FROM diagnostics_events")
    suspend fun clear()

    @Query(
        "DELETE FROM diagnostics_events WHERE id NOT IN " +
            "(SELECT id FROM diagnostics_events ORDER BY timestampEpochMs DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}
