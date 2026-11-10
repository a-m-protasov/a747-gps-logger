package io.github.a_m_protasov.a747.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackStats(
    val id: String,
    val startUtc: Long,
    val endUtc: Long,
    val points: Int,
    val distanceM: Double,
    val durationS: Long,
    val movingS: Long,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val ascentM: Double,
    val descentM: Double,
)

object Tracks {
    const val EARTH_RADIUS = 6_371_008.8

    /** §4.4 */
    fun split(points: List<LogPoint>, gapSeconds: Long = 1800): List<List<LogPoint>> {
        val tracks = ArrayList<ArrayList<LogPoint>>()
        for (p in points) {
            val last = tracks.lastOrNull()?.lastOrNull()
            if (last != null && p.utc - last.utc <= gapSeconds) tracks.last().add(p) else tracks.add(arrayListOf(p))
        }
        return tracks
    }

    fun haversine(a: LogPoint, b: LogPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val s1 = sin(dp / 2)
        val s2 = sin(dl / 2)
        val h = s1 * s1 + cos(p1) * cos(p2) * s2 * s2
        return 2 * EARTH_RADIUS * asin(min(1.0, sqrt(h)))
    }

    /** Rounds the exact binary value, ties to even (protocol §7). */
    fun round(x: Double, decimals: Int): Double =
        BigDecimal(x).setScale(decimals, RoundingMode.HALF_EVEN).toDouble()

    /** §4.5 */
    fun stats(t: List<LogPoint>): TrackStats {
        var dist = 0.0
        var moving = 0.0
        var segMax = 0.0
        for (k in 1 until t.size) {
            val a = t[k - 1]
            val b = t[k]
            val d = haversine(a, b)
            val dt = (b.utc - a.utc).toDouble()
            dist += d
            if (dt > 0 && d / dt * 3.6 > 1) moving += dt
            if (dt >= 2) segMax = maxOf(segMax, d / dt * 3.6)
        }
        val speeds = t.mapNotNull { it.speed }
        var asc = 0.0
        var desc = 0.0
        var ref: Double? = null
        for (h in t.mapNotNull { it.height }) {
            val r = ref
            if (r == null) { ref = h; continue }
            if (abs(h - r) > 3) {
                if (h > r) asc += h - r else desc += r - h
                ref = h
            }
        }
        return TrackStats(
            id = "${t[0].utc}-${t.size}",
            startUtc = t[0].utc,
            endUtc = t.last().utc,
            points = t.size,
            distanceM = round(dist, 1),
            durationS = t.last().utc - t[0].utc,
            movingS = BigDecimal(moving).setScale(0, RoundingMode.HALF_EVEN).toLong(),
            avgSpeedKmh = if (moving > 0) round(dist / moving * 3.6, 1) else 0.0,
            maxSpeedKmh = round(speeds.maxOrNull() ?: segMax, 1),
            ascentM = round(asc, 1),
            descentM = round(desc, 1),
        )
    }
}
