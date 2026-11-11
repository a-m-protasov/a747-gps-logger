package io.github.a_m_protasov.a747.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs

/** Tracks and waypoints read from a file made by another program (or exported by this app). */
data class ImportResult(
    val tracks: List<List<LogPoint>> = emptyList(),
    val waypoints: List<LogPoint> = emptyList(),
    /** Names found in the file, keyed by the UTC time of a track's first point or of a waypoint. */
    val names: Map<Long, String> = emptyMap(),
)

sealed class ImportException(message: String) : Exception(message) {
    /** The file is none of the formats below. */
    class UnknownFormat : ImportException("unknown format")
    /** The file has no points with a time (the library is ordered by time). */
    class NoTimedPoints : ImportException("no points with a time")
}

/**
 * Reads GPX, KML (gx:Track and timed placemarks), CSV (this app's columns or common ones) and
 * NMEA (RMC with GGA altitude). Points without a time are skipped. Same rules as the Swift core.
 */
object Importer {
    private val gpxRoot = Regex("<([a-z0-9_]+:)?gpx[\\s>]")
    private val kmlRoot = Regex("<([a-z0-9_]+:)?kml[\\s>]")

    fun parse(data: ByteArray, fileName: String = ""): ImportResult {
        val text = String(data, Charsets.UTF_8).replace("﻿", "")
        val head = text.take(4096).lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val r = when {
            gpxRoot.containsMatchIn(head) -> xml(data, kml = false)
            kmlRoot.containsMatchIn(head) -> xml(data, kml = true)
            ext == "nmea" || head.startsWith("$") || head.contains("\n$") -> nmea(text)
            head.contains(",") -> csv(text)
            else -> throw ImportException.UnknownFormat()
        }
        val out = r.copy(tracks = r.tracks.map(::clean).filter { it.isNotEmpty() }, waypoints = clean(r.waypoints))
        if (out.tracks.isEmpty() && out.waypoints.isEmpty()) throw ImportException.NoTimedPoints()
        return out
    }

    /** Sorted by time, one point per second. */
    fun clean(pts: List<LogPoint>): List<LogPoint> {
        val seen = HashSet<Long>()
        return pts.sortedBy { it.utc }.filter { seen.add(it.utc) }
    }

    // MARK: Time

    /** `2026-05-01T07:12:00Z`, with optional fractional seconds and offset; no offset means UTC. */
    fun time(s0: String): Long? {
        val c = s0.trim()
        fun num(from: Int, len: Int): Int? {
            if (from + len > c.length) return null
            var v = 0
            for (k in from until from + len) { val d = c[k]; if (d !in '0'..'9') return null; v = v * 10 + (d - '0') }
            return v
        }
        val y = num(0, 4) ?: return null
        if (c.getOrNull(4) != '-') return null
        val mo = num(5, 2) ?: return null
        if (c.getOrNull(7) != '-') return null
        val d = num(8, 2) ?: return null
        if (c.getOrNull(10) !in setOf('T', 't', ' ')) return null
        val h = num(11, 2) ?: return null
        if (c.getOrNull(13) != ':') return null
        val mi = num(14, 2) ?: return null
        if (c.getOrNull(16) != ':') return null
        val se = num(17, 2) ?: return null
        var i = 19
        if (i < c.length && (c[i] == '.' || c[i] == ',')) { i++; while (i < c.length && c[i] in '0'..'9') i++ }
        var offset = 0
        if (i < c.length) {
            if (c[i] == 'Z' || c[i] == 'z') i++
            else if (c[i] == '+' || c[i] == '-') {
                val sign = if (c[i] == '-') -1 else 1
                val oh = num(i + 1, 2) ?: return null
                var j = i + 3
                if (j < c.length && c[j] == ':') j++
                val om = num(j, 2) ?: 0
                offset = sign * (oh * 3600 + om * 60)
            }
        }
        return epoch(y, mo, d) * 86400 + (h * 3600 + mi * 60 + se - offset)
    }

    /** Days since 1970-01-01 (proleptic Gregorian). */
    fun epoch(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return (era * 146097 + doe - 719468).toLong()
    }

    // MARK: GPX and KML

    private class XmlReader(val kml: Boolean) : DefaultHandler() {
        val tracks = ArrayList<List<LogPoint>>()
        val waypoints = ArrayList<LogPoint>()
        val names = HashMap<Long, String>()
        val text = StringBuilder()
        val path = ArrayList<String>()
        var lat: Double? = null; var lon: Double? = null; var ele: Double? = null; var time: Long? = null
        var speed: Double? = null; var hdop: Double? = null; var sat: Int? = null; var pointName: String? = null
        var current = ArrayList<LogPoint>()
        var trackName: String? = null
        var whens = ArrayList<Long?>(); var coords = ArrayList<List<Double>>()
        var placemarkTime: Long? = null; var placemarkName: String? = null; var pointCoord: List<Double>? = null

        fun local(n: String) = n.substringAfterLast(':').lowercase()

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            val n = local(qName.ifEmpty { localName ?: "" })
            path += n
            text.setLength(0)
            if (kml) {
                if (n == "placemark") { placemarkTime = null; placemarkName = null; pointCoord = null; whens = ArrayList(); coords = ArrayList() }
                return
            }
            when (n) {
                "trkpt", "rtept", "wpt" -> {
                    lat = a.getValue("lat")?.trim()?.toDoubleOrNull()
                    lon = a.getValue("lon")?.trim()?.toDoubleOrNull()
                    ele = null; time = null; speed = null; hdop = null; sat = null; pointName = null
                }
                "trk", "rte" -> { current = ArrayList(); trackName = null }
                "trkseg" -> if (current.isNotEmpty() && path.getOrNull(path.size - 2) == "trk") flush()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }

        fun flush() {
            if (current.isNotEmpty()) {
                trackName?.takeIf { it.isNotEmpty() }?.let { names[current.minOf { p -> p.utc }] = it }
                tracks += current
            }
            current = ArrayList()
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val n = local(qName.ifEmpty { localName ?: "" })
            val t = text.toString().trim()
            path.removeAt(path.size - 1)
            val parent = path.lastOrNull() ?: ""
            if (kml) { endKml(n, t, parent); return }
            when (n) {
                "ele" -> ele = t.toDoubleOrNull()
                "time" -> if (parent in setOf("trkpt", "rtept", "wpt")) time = time(t)
                "speed" -> speed = t.toDoubleOrNull()?.let { it * 3.6 }
                "hdop" -> hdop = t.toDoubleOrNull()
                "sat" -> sat = t.toIntOrNull()
                "name" -> if (parent == "wpt") pointName = t else if (parent == "trk" || parent == "rte") trackName = t
                "trkpt", "rtept", "wpt" -> {
                    val la = lat; val lo = lon; val tm = time
                    if (la != null && lo != null && tm != null && abs(la) <= 90 && abs(lo) <= 180) {
                        val p = LogPoint(tm, la, lo, height = ele, speed = speed, hdop = hdop, satsUsed = sat)
                        if (n == "wpt") {
                            waypoints += p
                            pointName?.takeIf { it.isNotEmpty() }?.let { names[tm] = it }
                        } else current += p
                    }
                }
                "trk", "rte" -> flush()
            }
            text.setLength(0)
        }

        fun coord(s: String): List<Double>? =
            s.split(',', ' ').filter { it.isNotEmpty() }.mapNotNull { it.toDoubleOrNull() }.takeIf { it.size >= 2 }

        fun endKml(n: String, t: String, parent: String) {
            when (n) {
                "when" -> if (parent == "track") whens += time(t) else placemarkTime = time(t)
                "coord" -> coords += coord(t) ?: emptyList()
                "name" -> if (parent == "placemark") placemarkName = t
                "coordinates" -> if (parent == "point") pointCoord = coord(t)
                "track" -> {
                    val pts = ArrayList<LogPoint>()
                    for ((w, c) in whens.zip(coords)) {
                        if (w == null || c.size < 2) continue
                        pts += LogPoint(w, c[1], c[0], height = c.getOrNull(2))
                    }
                    if (pts.isNotEmpty()) {
                        placemarkName?.takeIf { it.isNotEmpty() }?.let { names[pts.minOf { p -> p.utc }] = it }
                        tracks += pts
                    }
                    whens = ArrayList(); coords = ArrayList()
                }
                "placemark" -> {
                    val c = pointCoord; val tm = placemarkTime
                    if (c != null && tm != null) {
                        waypoints += LogPoint(tm, c[1], c[0], height = c.getOrNull(2))
                        placemarkName?.takeIf { it.isNotEmpty() }?.let { names[tm] = it }
                    }
                }
            }
            text.setLength(0)
        }
    }

    private fun xml(data: ByteArray, kml: Boolean): ImportResult {
        val reader = XmlReader(kml)
        val f = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        try {
            f.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), reader)
        } catch (e: Exception) {
            if (reader.tracks.isEmpty() && reader.waypoints.isEmpty() && reader.current.isEmpty()) throw ImportException.UnknownFormat()
        }
        reader.flush()
        return ImportResult(reader.tracks, reader.waypoints, reader.names)
    }

    // MARK: CSV

    fun fields(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ } else quoted = false
                } else cur.append(ch)
            } else if (ch == '"') quoted = true
            else if (ch == ',') { out += cur.toString(); cur.setLength(0) }
            else cur.append(ch)
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }
    }

    /** Splits CSV text into records; quoted fields may contain line breaks. */
    fun records(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in text) {
            if (ch == '"') quoted = !quoted
            if ((ch == '\n' || ch == '\r') && !quoted) {
                if (cur.isNotBlank()) out += cur.toString()
                cur.setLength(0)
            } else cur.append(ch)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    private fun csv(text: String): ImportResult {
        val rows = records(text)
        val header = rows.firstOrNull()?.let(::fields)?.map { it.lowercase() } ?: return ImportResult()
        fun col(vararg names: String) = header.indexOfFirst { it in names }.takeIf { it >= 0 }
        val ti = col("time_utc", "time", "timestamp", "date_time", "datetime", "utc") ?: return ImportResult()
        val la = col("lat", "latitude") ?: return ImportResult()
        val lo = col("lon", "lng", "long", "longitude") ?: return ImportResult()
        val ei = col("ele_m", "ele", "elevation", "alt", "altitude", "height")
        val si = col("speed_kmh"); val hi = col("hdop"); val ni = col("sats_used", "sats", "satellites")
        val track = col("track", "track_name", "segment")
        // this app's waypoints file: a "name" column and no track column
        val wptName = if (track == null) col("name") else null
        val wpts = ArrayList<LogPoint>()
        val names = HashMap<Long, String>()
        val groups = LinkedHashMap<String, ArrayList<LogPoint>>()
        for (row in rows.drop(1)) {
            val f = fields(row)
            fun v(i: Int?) = i?.let { f.getOrNull(it) }?.takeIf { it.isNotEmpty() }
            val tm = v(ti)?.let(::time) ?: continue
            val lat = v(la)?.toDoubleOrNull() ?: continue
            val lon = v(lo)?.toDoubleOrNull() ?: continue
            if (abs(lat) > 90 || abs(lon) > 180) continue
            val p = LogPoint(tm, lat, lon, height = v(ei)?.toDoubleOrNull(), speed = v(si)?.toDoubleOrNull(),
                hdop = v(hi)?.toDoubleOrNull(), satsUsed = v(ni)?.toIntOrNull())
            if (wptName != null) {
                wpts += p
                v(wptName)?.let { names[tm] = it }
            } else groups.getOrPut(v(track) ?: "") { ArrayList() } += p
        }
        for ((key, pts) in groups) if (key.isNotEmpty()) names[pts.minOf { it.utc }] = key
        return ImportResult(groups.values.toList(), wpts, names)
    }

    // MARK: NMEA

    /** `ddmm.mmmm` with hemisphere. */
    private fun nmeaDeg(v: String, hemi: String): Double? {
        val x = v.toDoubleOrNull() ?: return null
        val deg = (x / 100).toLong().toDouble()
        val d = deg + (x - deg * 100) / 60
        return if (hemi == "S" || hemi == "W") -d else d
    }

    private fun nmea(text: String): ImportResult {
        val lines = text.lines()
        val alt = HashMap<String, Double>() // GGA altitude by hhmmss
        for (line in lines) {
            val f = Nmea.validate(line)?.split(',') ?: continue
            if (f[0].endsWith("GGA") && f.size > 9) f[9].toDoubleOrNull()?.let { alt[f[1].take(6)] = it }
        }
        val pts = ArrayList<LogPoint>()
        for (line in lines) {
            val f = Nmea.validate(line)?.split(',') ?: continue
            if (!f[0].endsWith("RMC") || f.size <= 9 || f[2] != "A" || f[1].length < 6 || f[9].length != 6) continue
            val lat = nmeaDeg(f[3], f[4]) ?: continue
            val lon = nmeaDeg(f[5], f[6]) ?: continue
            val hh = f[1].substring(0, 2).toIntOrNull() ?: continue
            val mm = f[1].substring(2, 4).toIntOrNull() ?: continue
            val ss = f[1].substring(4, 6).toIntOrNull() ?: continue
            val dd = f[9].substring(0, 2).toIntOrNull() ?: continue
            val mo = f[9].substring(2, 4).toIntOrNull() ?: continue
            val yy = f[9].substring(4, 6).toIntOrNull() ?: continue
            val utc = epoch(2000 + yy, mo, dd) * 86400 + hh * 3600 + mm * 60 + ss
            pts += LogPoint(utc, lat, lon, height = alt[f[1].take(6)], speed = f[7].toDoubleOrNull()?.let { it * 1.852 })
        }
        return ImportResult(if (pts.isEmpty()) emptyList() else listOf(pts))
    }
}

/**
 * The library's own file for tracks recorded on a phone or imported: one track per file
 * (rows `T`) or waypoints (rows `W`), appendable while recording.
 */
object PointsFile {
    const val HEADER = "kind,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n"

    fun row(kind: Char, p: LogPoint): String {
        fun o(v: Double?, d: Int) = v?.let { Fmt.fixed(it, d) } ?: ""
        return "$kind,${p.utc},${Fmt.fixed(p.lat, 7)},${Fmt.fixed(p.lon, 7)},${o(p.height, 1)},${o(p.speed, 1)}," +
            "${o(p.hdop, 2)},${p.satsUsed ?: ""}\n"
    }

    fun encode(track: List<LogPoint> = emptyList(), waypoints: List<LogPoint> = emptyList()): String =
        HEADER + track.joinToString("") { row('T', it) } + waypoints.joinToString("") { row('W', it) }

    /** A truncated last line (the app was killed while writing) is skipped. */
    fun decode(text: String): Pair<List<LogPoint>, List<LogPoint>> {
        val t = ArrayList<LogPoint>()
        val w = ArrayList<LogPoint>()
        for (line in text.lines().drop(1)) {
            val f = line.split(',')
            if (f.size < 8) continue
            val utc = f[1].toLongOrNull() ?: continue
            val lat = f[2].toDoubleOrNull() ?: continue
            val lon = f[3].toDoubleOrNull() ?: continue
            val p = LogPoint(utc, lat, lon, height = f[4].toDoubleOrNull(), speed = f[5].toDoubleOrNull(),
                hdop = f[6].toDoubleOrNull(), satsUsed = f[7].toIntOrNull())
            if (f[0] == "W") w += p else t += p
        }
        return Importer.clean(t) to Importer.clean(w)
    }
}
