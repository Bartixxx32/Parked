package io.github.bartixxx32.parked.data

import io.github.bartixxx32.parked.data.db.DiagnosticLevel
import io.github.bartixxx32.parked.data.db.DiagnosticsEvent
import io.github.bartixxx32.parked.data.db.DiagnosticsEventDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class DiagnosticsRepository(
    private val dao: DiagnosticsEventDao,
    private val scope: CoroutineScope,
) {

    val events: Flow<List<DiagnosticsEvent>> = dao.observeRecent(MAX_EVENTS)

    fun info(tag: String, message: String) = log(DiagnosticLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = log(DiagnosticLevel.WARN, tag, message)

    fun error(tag: String, message: String) = log(DiagnosticLevel.ERROR, tag, message)

    fun log(level: String, tag: String, message: String) {
        val event = DiagnosticsEvent(
            timestampEpochMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message.take(MAX_MESSAGE_LENGTH),
        )
        scope.launch {
            runCatching {
                dao.insert(event)
                dao.trimTo(MAX_EVENTS)
            }
        }
    }

    suspend fun recentAsText(limit: Int = MAX_EVENTS): String {
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
        return dao.recent(limit).joinToString(separator = "\n") { event ->
            val time = formatter.format(java.time.Instant.ofEpochMilli(event.timestampEpochMs))
            "$time ${event.level}/${event.tag}: ${event.message}"
        }
    }

    fun clear() {
        scope.launch { runCatching { dao.clear() } }
    }

    companion object {
        const val MAX_EVENTS = 300
        private const val MAX_MESSAGE_LENGTH = 1000
    }
}
