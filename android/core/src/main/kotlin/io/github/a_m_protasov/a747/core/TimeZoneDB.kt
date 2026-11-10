package io.github.a_m_protasov.a747.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** Offline time zone lookup over shared/timezones.bin (protocol §4.6). */
class TimeZoneDB(data: ByteArray) {
    private class Zone(val name: String, val x0: Long, val y0: Long, val x1: Long, val y1: Long, val rings: List<IntArray>)

    private val zones: List<Zone>
    private val cache = ConcurrentHashMap<String, ZoneId>()

    init {
        require(data.size > 8 && String(data, 0, 4, Charsets.US_ASCII) == "A7TZ") { "bad time zone data" }
        fun u8(o: Int) = data[o].toInt() and 0xFF
        fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
        fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val count = u16(6)
        var o = 8
        val list = ArrayList<Zone>(count)
        repeat(count) {
            val n = u8(o)
            val name = String(data, o + 1, n, Charsets.US_ASCII)
            o += 1 + n
            val x0 = i32(o).toLong(); val y0 = i32(o + 4).toLong(); val x1 = i32(o + 8).toLong(); val y1 = i32(o + 12).toLong()
            o += 16
            val rc = i32(o); o += 4
            val rings = ArrayList<IntArray>(rc)
            repeat(rc) {
                val pc = i32(o); o += 4
                val r = IntArray(2 * pc) { k -> i32(o + 4 * k) }
                o += 8 * pc
                rings.add(r)
            }
            list.add(Zone(name, x0, y0, x1, y1, rings))
        }
        zones = list
    }

    private fun inside(x: Long, y: Long, rings: List<IntArray>): Boolean {
        var inside = false
        for (r in rings) {
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = r[2 * i].toLong(); val yi = r[2 * i + 1].toLong()
                val xj = r[2 * j].toLong(); val yj = r[2 * j + 1].toLong()
                if ((yi > y) != (yj > y)) {
                    val lhs = (x - xi) * (yj - yi)
                    val rhs = (xj - xi) * (y - yi)
                    if (if (yj > yi) lhs < rhs else lhs > rhs) inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    /** Python's round(): half to even, of the exact product. */
    private fun scaled(v: Double): Long = BigDecimal(v * 100_000).setScale(0, RoundingMode.HALF_EVEN).toLong()

    fun zoneName(lat: Double, lon: Double): String {
        val x = scaled(lon)
        val y = scaled(lat)
        for (z in zones) {
            if (x < z.x0 || x > z.x1 || y < z.y0 || y > z.y1) continue
            if (inside(x, y, z.rings)) return z.name
        }
        return "UTC"
    }

    fun zone(lat: Double, lon: Double): ZoneId {
        val name = zoneName(lat, lon)
        return cache.getOrPut(name) { runCatching { ZoneId.of(name) }.getOrDefault(ZoneId.of("UTC")) }
    }
}
