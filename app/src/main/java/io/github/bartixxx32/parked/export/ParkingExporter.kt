package io.github.bartixxx32.parked.export

import io.github.bartixxx32.parked.data.db.ParkingSpot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    GPX("gpx", "application/gpx+xml"),
}

data class ExportPayload(
    val fileName: String,
    val mimeType: String,
    val content: String,
)

/**
 * Pure, dependency-free exporters. Keeping them free of Android classes makes them
 * directly unit-testable on the JVM.
 */
object ParkingExporter {

    fun export(
        spots: List<ParkingSpot>,
        format: ExportFormat,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ExportPayload {
        val timestamp = FILE_TIMESTAMP.format(now.atZone(zoneId))
        val baseName = "parked-$timestamp"
        val content = when (format) {
            ExportFormat.JSON -> toJson(spots, now)
            ExportFormat.CSV -> toCsv(spots)
            ExportFormat.GPX -> toGpx(spots, now)
        }
        return ExportPayload(
            fileName = "$baseName.${format.extension}",
            mimeType = format.mimeType,
            content = content,
        )
    }

    fun toJson(spots: List<ParkingSpot>, now: Instant = Instant.now()): String = buildString {
        append("{\n")
        append("  \"app\": \"Parked\",\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"exportedAt\": ").append(jsonString(now.toString())).append(",\n")
        append("  \"count\": ").append(spots.size).append(",\n")
        append("  \"spots\": [\n")
        spots.forEachIndexed { index, spot ->
            append("    {\n")
            append("      \"id\": ").append(spot.id).append(",\n")
            append("      \"savedAt\": ").append(jsonString(Instant.ofEpochMilli(spot.savedAtEpochMs).toString())).append(",\n")
            append("      \"savedAtEpochMs\": ").append(spot.savedAtEpochMs).append(",\n")
            append("      \"fixTime\": ").append(jsonString(Instant.ofEpochMilli(spot.fixTimeEpochMs).toString())).append(",\n")
            append("      \"latitude\": ").append(spot.latitude).append(",\n")
            append("      \"longitude\": ").append(spot.longitude).append(",\n")
            append("      \"accuracyMeters\": ").append(spot.accuracyMeters ?: "null").append(",\n")
            append("      \"source\": ").append(jsonString(spot.source)).append(",\n")
            append("      \"isFallback\": ").append(spot.isFallback).append(",\n")
            append("      \"isMock\": ").append(spot.isMock).append(",\n")
            append("      \"provider\": ").append(spot.provider?.let(::jsonString) ?: "null").append(",\n")
            append("      \"deviceName\": ").append(spot.deviceName?.let(::jsonString) ?: "null").append(",\n")
            append("      \"deviceAddress\": ").append(spot.deviceAddress?.let(::jsonString) ?: "null").append(",\n")
            append("      \"note\": ").append(jsonString(spot.note)).append(",\n")
            append("      \"hasPhoto\": ").append(spot.photoPath != null).append("\n")
            append("    }")
            if (index != spots.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    fun toCsv(spots: List<ParkingSpot>): String = buildString {
        append(
            "id,saved_at_iso,saved_at_epoch_ms,fix_time_iso,latitude,longitude,accuracy_m," +
                "source,is_fallback,is_mock,provider,device_name,device_address,note,has_photo\n",
        )
        spots.forEach { spot ->
            append(spot.id).append(',')
            append(csv(Instant.ofEpochMilli(spot.savedAtEpochMs).toString())).append(',')
            append(spot.savedAtEpochMs).append(',')
            append(csv(Instant.ofEpochMilli(spot.fixTimeEpochMs).toString())).append(',')
            append(spot.latitude).append(',')
            append(spot.longitude).append(',')
            append(spot.accuracyMeters?.toString() ?: "").append(',')
            append(csv(spot.source)).append(',')
            append(spot.isFallback).append(',')
            append(spot.isMock).append(',')
            append(csv(spot.provider ?: "")).append(',')
            append(csv(spot.deviceName ?: "")).append(',')
            append(csv(spot.deviceAddress ?: "")).append(',')
            append(csv(spot.note)).append(',')
            append(spot.photoPath != null).append('\n')
        }
    }

    fun toGpx(spots: List<ParkingSpot>, now: Instant = Instant.now()): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append(
            "<gpx version=\"1.1\" creator=\"Parked\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 " +
                "http://www.topografix.com/GPX/1/1/gpx.xsd\">\n",
        )
        append("  <metadata>\n")
        append("    <name>Parked — miejsca parkingowe</name>\n")
        append("    <time>").append(now.toString()).append("</time>\n")
        append("  </metadata>\n")
        spots.forEach { spot ->
            append("  <wpt lat=\"").append(spot.latitude).append("\" lon=\"")
                .append(spot.longitude).append("\">\n")
            append("    <time>").append(Instant.ofEpochMilli(spot.fixTimeEpochMs).toString())
                .append("</time>\n")
            val name = spot.note.ifBlank {
                "Parked " + DATE_TIME_FORMAT.format(
                    Instant.ofEpochMilli(spot.savedAtEpochMs).atZone(ZoneId.systemDefault()),
                )
            }
            append("    <name>").append(xml(name)).append("</name>\n")
            append("    <desc>")
                .append(
                    xml(
                        buildString {
                            append("Źródło: ").append(spot.source)
                            spot.accuracyMeters?.let { append("; dokładność: ").append(it).append(" m") }
                            if (spot.isFallback) append("; punkt awaryjny (ostatnia znana lokalizacja)")
                        },
                    ),
                )
                .append("</desc>\n")
            append("    <sym>Parking</sym>\n")
            append("    <type>parking</type>\n")
            append("  </wpt>\n")
        }
        append("</gpx>\n")
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun csv(value: String): String {
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + value.replace("\"", "\"\"") + "\""
        }
        return value
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
