package io.github.a_m_protasov.a747.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One logged position (docs/protocol.md §3.3). */
data class LogPoint(
    var utc: Long,
    val lat: Double,
    val lon: Double,
    val height: Double? = null,
    val speed: Double? = null,
    val hdop: Double? = null,
    val satsUsed: Int? = null,
    val valid: Int? = null,
    val rcr: Int? = null,
)

class ParseResult {
    val points = ArrayList<LogPoint>()
    val waypoints = ArrayList<LogPoint>()
    var corrupt = 0
    var droppedNoFix = 0
    var droppedBadTime = 0
    var duplicates = 0
}

/** The log format contains satellite data (SID) or lacks UTC/LAT/LON. */
class UnsupportedFormatException(val mask: Long) :
    Exception("Unsupported log format %08X".format(mask))

object LogFormat {
    const val SECTOR = 0x10000
    const val HEADER = 0x200
    const val ROLLOVER = 1024L * 7 * 86400
    /** 2008-01-01T00:00:00Z */
    const val ROLLOVER_FLOOR = 1_199_145_600L
    const val RCR_BUTTON = 0x8
    const val VALID_NO_FIX = 0x0001

    /** bit to size in bytes */
    val FIELD_SIZES = listOf(
        0 to 4, 1 to 2, 2 to 8, 3 to 8, 4 to 4, 5 to 4, 6 to 4, 7 to 2, 8 to 4, 9 to 2,
        10 to 2, 11 to 2, 12 to 2, 17 to 2, 18 to 2, 19 to 8,
    )
    private const val SID_BITS = (1L shl 13) or (1L shl 14) or (1L shl 15) or (1L shl 16)

    /** Real flash size from the JEDEC ID (§2.4). */
    fun flashSize(flashId: Long): Int {
        val code = ((flashId shr 8) and 0xFF).toInt()
        return if (code in 0x10..0x18) 1 shl code else 8 * 1024 * 1024
    }

    fun recordSize(mask: Long): Int {
        if (mask and SID_BITS != 0L) throw UnsupportedFormatException(mask)
        if (mask and 0b1101L != 0b1101L) throw UnsupportedFormatException(mask)
        return 2 + FIELD_SIZES.filter { mask and (1L shl it.first) != 0L }.sumOf { it.second }
    }

    /** (sector start, data end) in chronological order (§4.1). */
    fun sectorOrder(f: Int, w: Int): List<Pair<Int, Int>> {
        val n = f / SECTOR
        if (w <= f) {
            val last = maxOf(0, (w + SECTOR - 1) / SECTOR - 1)
            return (0..last).map { it * SECTOR to minOf((it + 1) * SECTOR, w) }
        }
        val wm = w % f
        val newest = wm / SECTOR
        val order = (0 until n - 1).map { (newest + 1 + it) % n } + newest
        return order.map { s -> s * SECTOR to (if (s == newest) wm else (s + 1) * SECTOR) }
    }

    /** Adds 1024-week periods until the time is plausible (§4.2). Null when in the future. */
    fun fixTime(t0: Long, now: Long): Long? {
        var t = t0
        while (t < ROLLOVER_FLOOR) t += ROLLOVER
        return if (t > now + 86400) null else t
    }
}

object LogParser {
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
    fun u32(b: ByteArray, o: Int): Long = (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    private fun buf(b: ByteArray, o: Int) = ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN)

    fun isSeparator(b: ByteArray, i: Int): Boolean {
        for (k in 0 until 7) if (u8(b, i + k) != 0xAA) return false
        for (k in 12 until 16) if (u8(b, i + k) != 0xBB) return false
        return true
    }

    private fun parseRecord(b: ByteArray, i: Int, end: Int, mask: Long, size: Int): LogPoint? {
        if (i + size > end || u8(b, i + size - 2) != 0x2A) return null
        var crc = 0
        for (k in 0 until size - 2) crc = crc xor u8(b, i + k)
        if (crc != u8(b, i + size - 1)) return null
        var utc = 0L; var lat = 0.0; var lon = 0.0
        var height: Double? = null; var speed: Double? = null; var hdop: Double? = null
        var sats: Int? = null; var valid: Int? = null; var rcr: Int? = null
        var o = i
        for ((bit, sz) in LogFormat.FIELD_SIZES) {
            if (mask and (1L shl bit) == 0L) continue
            when (bit) {
                0 -> utc = u32(b, o)
                1 -> valid = u16(b, o)
                2 -> lat = buf(b, o).double
                3 -> lon = buf(b, o).double
                4 -> height = buf(b, o).float.toDouble()
                5 -> speed = buf(b, o).float.toDouble()
                10 -> hdop = u16(b, o) / 100.0
                12 -> sats = u8(b, o + 1)
                17 -> rcr = u16(b, o)
            }
            o += sz
        }
        return LogPoint(utc, lat, lon, height, speed, hdop, sats, valid, rcr)
    }

    /** Parses a raw image (§4.1–4.3). */
    fun parse(full: ByteArray, flashId: Long, writeAddr: Int, now: Long): ParseResult {
        val f = LogFormat.flashSize(flashId)
        val image = if (full.size > f) full.copyOf(f) else full
        val res = ParseResult()
        val raw = ArrayList<LogPoint>()
        for ((start, sectorEnd) in LogFormat.sectorOrder(f, writeAddr)) {
            val end = minOf(sectorEnd, image.size)
            if (start + LogFormat.HEADER > end) continue
            var mask = u32(image, start + 2)
            if (mask == 0xFFFFFFFFL) continue
            var size = LogFormat.recordSize(mask)
            var i = start + LogFormat.HEADER
            var resyncing = false
            while (i + 4 <= end) {
                if (u32(image, i) == 0xFFFFFFFFL) break
                if (i + 16 <= end && isSeparator(image, i)) {
                    if (u8(image, i + 7) == 0x02) {
                        mask = u32(image, i + 8)
                        size = LogFormat.recordSize(mask)
                    }
                    i += 16
                    resyncing = false
                    continue
                }
                if (i + size > end) break
                val p = parseRecord(image, i, end, mask, size)
                if (p == null) {
                    if (!resyncing) { res.corrupt++; resyncing = true }
                    i++
                    continue
                }
                resyncing = false
                raw.add(p)
                i += size
            }
        }
        data class Key(val t: Long, val lat: Double, val lon: Double)
        val seenT = HashSet<Key>()
        val seenW = HashSet<Key>()
        for (p in raw) {
            if (p.valid == LogFormat.VALID_NO_FIX || (p.lat == 0.0 && p.lon == 0.0)) {
                res.droppedNoFix++; continue
            }
            val t = LogFormat.fixTime(p.utc, now)
            if (t == null) { res.droppedBadTime++; continue }
            p.utc = t
            val key = Key(p.utc, p.lat, p.lon)
            if ((p.rcr ?: 0) and LogFormat.RCR_BUTTON != 0) {
                if (!seenW.add(key)) { res.duplicates++; continue }
                res.waypoints.add(p)
            } else {
                if (!seenT.add(key)) { res.duplicates++; continue }
                res.points.add(p)
            }
        }
        res.points.sortBy { it.utc } // stable
        res.waypoints.sortBy { it.utc }
        return res
    }
}
