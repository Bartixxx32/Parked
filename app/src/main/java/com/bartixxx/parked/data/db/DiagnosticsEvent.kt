package com.bartixxx.parked.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object DiagnosticLevel {
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

@Entity(
    tableName = "diagnostics_events",
    indices = [Index(value = ["timestampEpochMs"])],
)
data class DiagnosticsEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampEpochMs: Long,
    val level: String,
    val tag: String,
    val message: String,
)
