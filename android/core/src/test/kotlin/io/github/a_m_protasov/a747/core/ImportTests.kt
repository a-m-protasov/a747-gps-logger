package io.github.a_m_protasov.a747.core

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Same cases as ImportTests in the Swift core. */
class ImportTests {
    private val repo = File(System.getProperty("repo"))
    private val tz by lazy { TimeZoneDB(File(repo, "shared/timezones.bin").readBytes()) }
    private fun file(path: String) = File(repo, path).readBytes()

    /** This app's own exports come back as the same tracks and waypoints. */
    @Test fun roundTrip() {
        val ex = Exporter(tz)
        for (name in listOf("basic", "wrapped")) {
            for (f in listOf("tracks.gpx", "tracks.csv")) {
                val gold = String(file("fixtures/$name/$f"))
                val r = Importer.parse(file("fixtures/$name/$f"), f)
                assertEquals(gold, if (f.endsWith("gpx")) ex.gpx(r.tracks) else ex.csv(r.tracks), "$name/$f")
                assertTrue(r.names.values.all { n -> r.tracks.any { ex.name(it[0]) == n } })
            }
            for (f in listOf("waypoints.gpx", "waypoints.csv")) {
                val gold = String(file("fixtures/$name/$f"))
                val r = Importer.parse(file("fixtures/$name/$f"), f)
                assertEquals(gold, if (f.endsWith("gpx")) ex.gpxWaypoints(r.waypoints) else ex.csvWaypoints(r.waypoints), "$name/$f")
            }
        }
    }

    @Test fun otherGpx() {
        val r = Importer.parse(file("fixtures/import/other.gpx"))
        assertEquals(listOf(2, 1), r.tracks.map { it.size })
        assertEquals(LogPoint(1780300800, 44.8, 20.45, height = 117.5), r.tracks[0][0])
        assertEquals(18.0, r.tracks[0][1].speed)
        assertEquals(1780304400, r.tracks[1][0].utc)
        assertEquals(listOf(1780308000L), r.waypoints.map { it.utc })
        assertEquals(mapOf(1780300800L to "Morning ride", 1780304400L to "Morning ride", 1780308000L to "Café & Co"), r.names)
    }

    @Test fun otherKml() {
        val r = Importer.parse(file("fixtures/import/other.kml"))
        assertEquals(listOf(listOf(LogPoint(1780394400, 44.80, 20.45, height = 120.0),
            LogPoint(1780394410, 44.81, 20.46, height = 121.0))), r.tracks)
        assertEquals(listOf(LogPoint(1780394700, 44.82, 20.47, height = 0.0)), r.waypoints)
        assertEquals(mapOf(1780394400L to "Walk", 1780394700L to "Bench"), r.names)
    }

    @Test fun otherNmea() {
        val r = Importer.parse(file("fixtures/import/other.nmea"), "other.nmea")
        assertEquals(1, r.tracks.size)
        val t = r.tracks[0]
        assertEquals(2, t.size)
        assertEquals(1780394400, t[0].utc); assertEquals(44.8, t[0].lat); assertEquals(20.45, t[0].lon); assertEquals(115.2, t[0].height)
        assertTrue(abs(t[0].speed!! - 18.52) < 1e-9)
        assertTrue(abs(t[1].lat + 44.80016666) < 1e-6 && abs(t[1].lon + 20.45016666) < 1e-6)
    }

    @Test fun otherCsv() {
        val r = Importer.parse(file("fixtures/import/other.csv"))
        assertEquals(listOf(listOf(LogPoint(1780470000, 44.80, 20.45, height = 100.0), LogPoint(1780470030, 44.81, 20.46))), r.tracks)
        assertTrue(r.names.isEmpty())
    }

    @Test fun errors() {
        assertFailsWith<ImportException.UnknownFormat> { Importer.parse("hello".toByteArray()) }
        assertFailsWith<ImportException.NoTimedPoints> {
            Importer.parse("<kml><Placemark><Point><coordinates>1,2</coordinates></Point></Placemark></kml>".toByteArray())
        }
    }

    @Test fun times() {
        assertEquals(0L, Importer.time("1970-01-01T00:00:00Z"))
        assertEquals(951_845_400L, Importer.time("2000-02-29T12:00:00-05:30"))
        assertEquals(1780300800L, Importer.time("2026-06-01 08:00:00"))
        assertNull(Importer.time("01.06.2026"))
    }

    @Test fun pointsFile() {
        val t = listOf(LogPoint(10, 1.5, -2.25, height = 3.0, speed = 4.5, hdop = 0.9, satsUsed = 7), LogPoint(11, 1.6, -2.26))
        val w = listOf(LogPoint(12, 5.0, 6.0))
        val (dt, dw) = PointsFile.decode(PointsFile.encode(t, w) + "T,13,1.")
        assertEquals(t, dt)
        assertEquals(w, dw)
    }
}
