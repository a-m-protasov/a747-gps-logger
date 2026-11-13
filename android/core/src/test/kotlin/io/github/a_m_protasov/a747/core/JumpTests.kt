package io.github.a_m_protasov.a747.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Same cases as JumpTests in the Swift core (protocol §4.4, GPS jumps). */
class JumpTests {
    /** A walk north: one point every 3 s, 4 m apart. */
    private fun walk(n: Int, from: Long = 1000, lat: Double = 43.0) =
        (0 until n).map { LogPoint(from + it * 3L, lat + it * 4.0 / 111_195, 20.0) }

    @Test fun coldStartFarOff() {
        val bad = (0 until 5).map { LogPoint(990 + it * 2L, 42.991, 20.0) }  // ~1 km south
        val good = walk(40)
        assertEquals(good, Tracks.dropJumps(bad + good))
        assertEquals(listOf(good), Tracks.split(bad + good))
    }

    @Test fun singleWildFix() {
        val t = walk(60).toMutableList()
        val wild = LogPoint(t[30].utc, 43.05, 20.0)
        t[30] = wild
        assertEquals(t.filter { it != wild }, Tracks.dropJumps(t))
    }

    @Test fun allPiecesShortKeepsTheLongest() {
        val a = walk(5)
        val b = walk(10, from = 1015, lat = 43.01)
        assertEquals(b, Tracks.dropJumps(a + b))
    }

    @Test fun slowTravelAndCleanTracksStay() {
        val t = walk(50)
        assertEquals(t, Tracks.dropJumps(t))
        // 2 km in 10 min is no jump
        val later = walk(5, from = t.last().utc + 600, lat = 43.02)
        assertEquals(t + later, Tracks.dropJumps(t + later))
        assertTrue(Tracks.dropJumps(emptyList()).isEmpty())
    }
}
