package com.bartixxx.parked.export

import com.bartixxx.parked.data.db.ParkingSpot
import com.bartixxx.parked.data.db.SpotSource
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingExporterTest {

    private val spot = ParkingSpot(
        id = 5L,
        latitude = 52.2297,
        longitude = 21.0122,
        accuracyMeters = 12.5f,
        savedAtEpochMs = 1_700_000_000_000L,
        fixTimeEpochMs = 1_699_999_995_000L,
        note = "Poziom -2, \"przy windzie\"\nzapamiętaj",
        source = SpotSource.AUTO,
        deviceName = "Radio",
        deviceAddress = "AA:BB",
    )

    @Test
    fun `json contains escaped note and schema fields`() {
        val json = ParkingExporter.toJson(listOf(spot), now = Instant.ofEpochSecond(1_700_000_100L))

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"count\": 1"))
        assertTrue(json.contains("52.2297"))
        assertTrue(json.contains("21.0122"))
        assertTrue(json.contains("Poziom -2, \\\"przy windzie\\\"\\nzapamiętaj"))
        assertTrue(json.contains("\"accuracyMeters\": 12.5"))
    }

    @Test
    fun `csv has header and properly quotes special characters`() {
        val csv = ParkingExporter.toCsv(listOf(spot))
        val lines = csv.lines()

        assertTrue(lines.first().startsWith("id,saved_at_iso"))
        assertTrue(csv.contains("\"Poziom -2, \"\"przy windzie\"\""))
        assertTrue(csv.contains(",12.5,"))
    }

    @Test
    fun `gpx is well formed xml with waypoint`() {
        val gpx = ParkingExporter.toGpx(listOf(spot), now = Instant.ofEpochSecond(1_700_000_100L))
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))
        val waypoints = document.getElementsByTagName("wpt")

        assertEquals(1, waypoints.length)
        assertTrue(gpx.contains("lat=\"52.2297\""))
        assertTrue(gpx.contains("dokładność: 12.5 m"))
        assertTrue(gpx.contains("&quot;przy windzie&quot;"))
    }

    @Test
    fun `export file name contains format and timestamp`() {
        val payload = ParkingExporter.export(
            spots = listOf(spot),
            format = ExportFormat.JSON,
            now = Instant.parse("2026-11-17T20:15:30Z"),
            zoneId = ZoneId.of("UTC"),
        )

        assertEquals("parked-20261117-201530.json", payload.fileName)
        assertEquals("application/json", payload.mimeType)
    }
}
