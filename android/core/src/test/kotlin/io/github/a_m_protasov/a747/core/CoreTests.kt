package io.github.a_m_protasov.a747.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val repo = File(System.getProperty("repo"))
private val tz by lazy { TimeZoneDB(File(repo, "shared/timezones.bin").readBytes()) }

/** Minimal JSON field reader for the fixture files (flat values only). */
private fun field(json: String, key: String): String =
    Regex("\"$key\"\\s*:\\s*\"?([^\",}\\n]*)").find(json)!!.groupValues[1].trim()

/** Every implementation must reproduce fixtures/ byte for byte (tools/README.md). */
class FixtureTests {
    private fun check(name: String) {
        val dir = File(repo, "fixtures/$name")
        val meta = File(dir, "meta.json").readText()
        val summary = File(dir, "summary.json").readText()
        val flashId = field(meta, "flash_id").toLong(16)
        val r = LogParser.parse(File(dir, "image.bin").readBytes(), flashId, field(meta, "write_addr").toInt(16), field(meta, "now").toLong())
        assertEquals(field(summary, "flash_size").toInt(), LogFormat.flashSize(flashId))
        assertEquals(field(summary, "points").toInt(), r.points.size)
        assertEquals(field(summary, "waypoints").toInt(), r.waypoints.size)
        assertEquals(field(summary, "corrupt").toInt(), r.corrupt)
        assertEquals(field(summary, "dropped_no_fix").toInt(), r.droppedNoFix)
        assertEquals(field(summary, "duplicates").toInt(), r.duplicates)

        val tracks = Tracks.split(r.points, field(meta, "split_gap_s").toLong())
        val golden = summary.substringAfter("\"tracks\"").split("{").drop(1)
        assertEquals(golden.size, tracks.size)
        val ex = Exporter(tz)
        for ((t, g) in tracks.zip(golden)) {
            val s = Tracks.stats(t)
            assertEquals(field(g, "id"), s.id)
            assertEquals(field(g, "moving_s").toLong(), s.movingS)
            assertTrue(abs(field(g, "distance_m").toDouble() - s.distanceM) <= 0.1)
            assertTrue(abs(field(g, "avg_speed_kmh").toDouble() - s.avgSpeedKmh) <= 0.1)
            assertEquals(field(g, "max_speed_kmh").toDouble(), s.maxSpeedKmh)
            assertEquals(field(g, "ascent_m").toDouble(), s.ascentM)
            assertEquals(field(g, "descent_m").toDouble(), s.descentM)
            assertEquals(field(g, "name"), ex.name(t[0]))
        }
        assertEquals(File(dir, "tracks.gpx").readText(), ex.gpx(tracks))
        assertEquals(File(dir, "waypoints.gpx").readText(), ex.gpxWaypoints(r.waypoints))
        assertEquals(File(dir, "tracks.kml").readText(), ex.kml(tracks, r.waypoints))
        assertEquals(File(dir, "tracks.csv").readText(), ex.csv(tracks))
        assertEquals(File(dir, "waypoints.csv").readText(), ex.csvWaypoints(r.waypoints))
    }

    @Test fun basic() = check("basic")
    @Test fun wrapped() = check("wrapped")

    @Test fun timeZones() {
        assertEquals("Europe/Lisbon", tz.zoneName(38.7071, -9.1355))
        assertEquals("Asia/Tbilisi", tz.zoneName(41.6938, 44.8015))
        assertEquals("Europe/Kaliningrad", tz.zoneName(54.71, 20.51))
    }

    @Test fun flashSize() {
        assertEquals(4 * 1024 * 1024, LogFormat.flashSize(0x1C31161CL))
        assertEquals(8 * 1024 * 1024, LogFormat.flashSize(0))
    }

    @Test fun negativeZero() {
        assertEquals("-0.0", Fmt.fixed(-0.04, 1))
        assertEquals("0.2", Fmt.fixed(0.25, 1))
    }
}

/** A simulated 747A+, replying like the real device (protocol §2). */
class FakeLogger(var flash: ByteArray, var writeAddr: Long, val flashId: Long = 0x1C31161CL) : LoggerTransport {
    var status = 262L
    var dropEvery = 0
    var reads = 0
    val log = ArrayList<String>()
    private val pending = java.io.ByteArrayOutputStream()

    private fun reply(body: String) = pending.write(Nmea.sentence(body))

    override suspend fun write(data: ByteArray) = synchronized(this) { handle(data) }

    private fun handle(data: ByteArray) {
        val body = Nmea.validate(String(data, Charsets.US_ASCII)) ?: return
        log.add(body)
        pending.write("\$GPGGA,120000.000,3842.426,N,00908.130,W,1,07,1.2,117.0,M,,,,0000*3A\r\n".toByteArray())
        reply("GPGGA,120000.000,3842.426,N,00908.130,W,1,07,1.2,117.0,M,,,,0000")
        val f = body.split(",")
        when {
            f[0] == "PMTK605" -> reply("PMTK705,AXN_1.30-B_1.3_C01,0003,TSI_747A+,1.0")
            f[0] == "PMTK182" && f[1] == "2" -> {
                val n = f[2].toInt()
                val v = when (n) { 2 -> 0x0002143FL; 3 -> 30L; 4 -> 100L; 5 -> 0L; 6 -> 2L; 7 -> status; 8 -> writeAddr; 9 -> flashId; 10 -> 1234L; else -> 0L }
                val text = when (n) { in 3..7 -> v.toString(); 8, 10 -> "%08X".format(v); else -> "%X".format(v) }
                reply("PMTK182,3,$n,$text")
            }
            f[0] == "PMTK182" && f[1] == "7" -> {
                reads++
                if (dropEvery > 0 && reads % dropEvery == 0) return
                val addr = f[2].toInt(16); val size = f[3].toInt(16)
                val real = LogFormat.flashSize(flashId)
                val hex = (0 until size).joinToString("") { "%02X".format(flash[(addr + it) % real]) }
                reply("PMTK182,8,${f[2]},$hex")
                reply("PMTK001,182,7,3")
            }
            f[0] == "PMTK182" && f[1] == "6" -> { flash.fill(0xFF.toByte()); writeAddr = 0x200; status = 260; reply("PMTK001,182,6,3") }
            f[0] == "PMTK182" && f[1] == "4" -> { status = status or 2; reply("PMTK001,182,4,3") }
            else -> reply("PMTK001,${f[0].drop(4)},1")
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray = synchronized(this) {
        val all = pending.toByteArray()
        val n = minOf(64, all.size)
        pending.reset()
        pending.write(all, n, all.size - n)
        all.copyOf(n)
    }

    override suspend fun close() {}
}

class ClientTests {
    private val basic = File(repo, "fixtures/basic/image.bin").readBytes()
    private fun flash(image: ByteArray) = ByteArray(4 * 1024 * 1024) { 0xFF.toByte() }.also { image.copyInto(it) }

    @Test fun fullDownload() = runBlocking {
        val fake = FakeLogger(flash(basic), basic.size.toLong()).apply { dropEvery = 7 }
        val client = LoggerClient(fake, replyTimeoutMs = 150)
        val r = Downloader.run(client, null) {}
        assertEquals("TSI_747A+", r.info.model)
        assertContentEquals(basic, r.state.image)
        assertEquals(7, client.live?.satellites)
    }

    @Test fun incrementalDownload() = runBlocking {
        val half = 0x9000 + 0x123
        val fake = FakeLogger(flash(basic.copyOf(half)), half.toLong())
        val client = LoggerClient(fake, replyTimeoutMs = 150)
        val first = Downloader.run(client, null) {}.state
        fake.flash = flash(basic); fake.writeAddr = basic.size.toLong()
        val second = Downloader.run(client, first) {}
        assertContentEquals(basic, second.state.image)
        assertEquals(DownloadPlan.Incremental(0x9000, basic.size), second.plan)
    }

    @Test fun eraseVerifies() = runBlocking {
        val fake = FakeLogger(flash(basic), basic.size.toLong())
        val info = LoggerClient(fake, replyTimeoutMs = 150).eraseAndEnable()
        assertEquals(0x200L, info.writeAddr)
        assertTrue(info.loggingOn)
        assertTrue("PMTK182,6,1" in fake.log && "PMTK182,4" in fake.log)
    }

    @Test fun sentences() {
        assertEquals("\$PMTK182,2,8*33\r\n", String(Nmea.sentence("PMTK182,2,8")))
        assertEquals(null, Nmea.validate("\$PMTK182,2,8*34"))
    }
}
