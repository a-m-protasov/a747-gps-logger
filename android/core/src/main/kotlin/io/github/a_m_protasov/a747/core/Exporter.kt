package io.github.a_m_protasov.a747.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and time formatting shared by exports (protocol §7). */
object Fmt {
    /** Like C printf: exact binary value, ties to even, keeps the sign of negative values. */
    fun fixed(x: Double, decimals: Int): String {
        val s = BigDecimal(x).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
        val negative = x < 0 || (x == 0.0 && 1.0 / x < 0)
        return if (negative && !s.startsWith("-")) "-$s" else s
    }

    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

    fun iso(t: Long): String = iso.format(Instant.ofEpochSecond(t))
    fun localName(t: Long, zone: ZoneId): String = local.withZone(zone).format(Instant.ofEpochSecond(t))
}

class Exporter(private val tz: TimeZoneDB) {
    fun name(p: LogPoint): String = Fmt.localName(p.utc, tz.zone(p.lat, p.lon))

    private fun coord(p: LogPoint) = "lat=\"${Fmt.fixed(p.lat, 7)}\" lon=\"${Fmt.fixed(p.lon, 7)}\""
    private fun ele(p: LogPoint) = p.height?.let { "<ele>${Fmt.fixed(it, 1)}</ele>" } ?: ""

    fun gpx(tracks: List<List<LogPoint>>): String = buildString {
        append(GPX_HEAD)
        for (t in tracks) {
            append("<trk><name>${name(t[0])}</name>\n<trkseg>\n")
            for (p in t) {
                append("<trkpt ${coord(p)}>${ele(p)}<time>${Fmt.iso(p.utc)}</time>")
                p.satsUsed?.let { append("<sat>$it</sat>") }
                p.hdop?.let { append("<hdop>${Fmt.fixed(it, 2)}</hdop>") }
                append("</trkpt>\n")
            }
            append("</trkseg>\n</trk>\n")
        }
        append("</gpx>\n")
    }

    fun gpxWaypoints(wpts: List<LogPoint>): String = buildString {
        append(GPX_HEAD)
        for (p in wpts) append("<wpt ${coord(p)}>${ele(p)}<time>${Fmt.iso(p.utc)}</time><name>${name(p)}</name></wpt>\n")
        append("</gpx>\n")
    }

    private fun kmlCoord(p: LogPoint): String {
        val base = "${Fmt.fixed(p.lon, 7)},${Fmt.fixed(p.lat, 7)}"
        return p.height?.let { "$base,${Fmt.fixed(it, 1)}" } ?: base
    }

    fun kml(tracks: List<List<LogPoint>>, wpts: List<LogPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        for (t in tracks) {
            append("<Placemark><name>${name(t[0])}</name><LineString><coordinates>")
            append(t.joinToString(" ") { kmlCoord(it) })
            append("</coordinates></LineString></Placemark>\n")
        }
        for (p in wpts) append("<Placemark><name>${name(p)}</name><Point><coordinates>${kmlCoord(p)}</coordinates></Point></Placemark>\n")
        append("</Document>\n</kml>\n")
    }

    private fun opt(v: Double?, d: Int) = v?.let { Fmt.fixed(it, d) } ?: ""

    fun csv(tracks: List<List<LogPoint>>): String = buildString {
        append("track,time_utc,lat,lon,ele_m,speed_kmh,hdop,sats_used\n")
        for (t in tracks) {
            val n = name(t[0])
            for (p in t) {
                append("$n,${Fmt.iso(p.utc)},${Fmt.fixed(p.lat, 7)},${Fmt.fixed(p.lon, 7)},")
                append("${opt(p.height, 1)},${opt(p.speed, 1)},${opt(p.hdop, 2)},${p.satsUsed ?: ""}\n")
            }
        }
    }

    fun csvWaypoints(wpts: List<LogPoint>): String = buildString {
        append("name,time_utc,lat,lon,ele_m\n")
        for (p in wpts) append("${name(p)},${Fmt.iso(p.utc)},${Fmt.fixed(p.lat, 7)},${Fmt.fixed(p.lon, 7)},${opt(p.height, 1)}\n")
    }

    companion object {
        const val GPX_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<gpx version=\"1.1\" creator=\"A747 GPS Logger\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
    }
}
