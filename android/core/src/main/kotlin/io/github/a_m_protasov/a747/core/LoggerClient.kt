package io.github.a_m_protasov.a747.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/** NMEA/PMTK sentences (protocol §2). */
object Nmea {
    fun checksum(body: String): Int = body.toByteArray(Charsets.US_ASCII).fold(0) { a, b -> a xor (b.toInt() and 0xFF) }

    /** `$<body>*XX\r\n` */
    fun sentence(body: String): ByteArray = "$%s*%02X\r\n".format(body, checksum(body)).toByteArray(Charsets.US_ASCII)

    /** The body (without `$` and checksum) if the line is a valid sentence. */
    fun validate(line0: String): String? {
        val line = line0.trim()
        if (!line.startsWith("$")) return null
        val star = line.lastIndexOf('*')
        if (star < 0) return null
        val body = line.substring(1, star)
        val cs = line.substring(star + 1)
        if (cs.length != 2) return null
        return if (cs.toIntOrNull(16) == checksum(body)) body else null
    }
}

/** Live receiver status from GGA. */
data class LiveStatus(val hasFix: Boolean, val satellites: Int, val hdop: Double?) {
    companion object {
        fun parseGga(body: String): LiveStatus? {
            val f = body.split(",")
            if (f.size <= 8 || !f[0].endsWith("GGA")) return null
            return LiveStatus((f[6].toIntOrNull() ?: 0) > 0, f[7].toIntOrNull() ?: 0, f[8].toDoubleOrNull())
        }
    }
}

/** A byte stream to the logger: USB bulk endpoints or Bluetooth SPP. */
interface LoggerTransport {
    suspend fun write(data: ByteArray)
    /** Whatever arrived within [timeoutMs] (may be empty). */
    suspend fun read(timeoutMs: Long): ByteArray
    suspend fun close()
}

sealed class LoggerException(message: String) : Exception(message) {
    class NoReply(val command: String) : LoggerException("No reply to $command")
    class BadReply(val reply: String) : LoggerException("Unexpected reply: $reply")
    class CommandFailed(val command: String, val flag: Int) : LoggerException("$command rejected ($flag)")
    class EraseNotConfirmed(val status: Long, val writeAddr: Long) :
        LoggerException("Erase not confirmed: status %X, write address %X".format(status, writeAddr))
}

/** Settings and state read from the device (protocol §2.2). */
data class DeviceInfo(
    val firmware: String,
    val model: String,
    val flashId: Long,
    val format: Long,
    val periodTenthsS: Long,
    val distanceTenthsM: Long,
    val speedTenthsKmh: Long,
    val mode: Long,
    val status: Long,
    val writeAddr: Long,
    val recordCount: Long,
) {
    val flashSize get() = LogFormat.flashSize(flashId)
    val loggingOn get() = status and 0x2 != 0L
    val stopWhenFull get() = mode == 2L
    val usedBytes get() = minOf(writeAddr, flashSize.toLong()).toInt()
    val usedFraction get() = usedBytes.toDouble() / flashSize
    val wrapped get() = writeAddr > flashSize
    val remainingHours: Double?
        get() {
            if (periodTenthsS <= 0) return null
            val size = runCatching { LogFormat.recordSize(format) }.getOrNull() ?: return null
            val perSector = (LogFormat.SECTOR - LogFormat.HEADER) / size
            val sectorsLeft = (flashSize - usedBytes).toDouble() / LogFormat.SECTOR
            return sectorsLeft * perSector * periodTenthsS / 10 / 3600
        }
}

class LoggerClient(private val transport: LoggerTransport, private val replyTimeoutMs: Long = 3000) {
    private val buffer = ByteArrayOutputStream()
    private val mutex = Mutex()

    @Volatile var live: LiveStatus? = null
        private set

    suspend fun close() = transport.close()

    private suspend fun nextLine(deadline: Long): String? {
        while (true) {
            val bytes = buffer.toByteArray()
            val nl = bytes.indexOf(0x0A)
            if (nl >= 0) {
                buffer.reset()
                buffer.write(bytes, nl + 1, bytes.size - nl - 1)
                val body = Nmea.validate(String(bytes, 0, nl, Charsets.US_ASCII)) ?: continue
                LiveStatus.parseGga(body)?.let { live = it }
                return body
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            currentCoroutineContext().ensureActive()
            buffer.write(transport.read(minOf(left, 200)))
        }
    }

    /** Sends a command and returns the first reply body starting with [expect]. */
    suspend fun command(body: String, expect: String, timeoutMs: Long? = null, retries: Int = 5): String =
        mutex.withLock {
            repeat(retries) {
                buffer.reset()
                transport.write(Nmea.sentence(body))
                val deadline = System.currentTimeMillis() + (timeoutMs ?: replyTimeoutMs)
                while (true) {
                    val line = nextLine(deadline) ?: break
                    if (line.startsWith(expect)) return@withLock line
                }
            }
            throw LoggerException.NoReply(body)
        }

    private suspend fun expectAck(body: String, ackPrefix: String, timeoutMs: Long) {
        val line = command(body, ackPrefix, timeoutMs, retries = 1)
        val flag = line.split(",").last().toIntOrNull() ?: throw LoggerException.BadReply(line)
        if (flag != 3) throw LoggerException.CommandFailed(body, flag)
    }

    suspend fun query(n: Int): Long {
        val line = command("PMTK182,2,$n", "PMTK182,3,$n,")
        val f = line.split(",")
        // settings 3–7 are decimal, the others hex (protocol §2.2)
        val radix = if (n in 3..7) 10 else 16
        return f.getOrNull(3)?.toLongOrNull(radix) ?: throw LoggerException.BadReply(line)
    }

    suspend fun readInfo(): DeviceInfo {
        val fw = command("PMTK605", "PMTK705,").split(",")
        return DeviceInfo(
            firmware = fw.getOrElse(1) { "" }, model = fw.getOrElse(3) { "" },
            flashId = query(9), format = query(2), periodTenthsS = query(3), distanceTenthsM = query(4),
            speedTenthsKmh = query(5), mode = query(6), status = query(7), writeAddr = query(8),
            recordCount = query(10),
        )
    }

    suspend fun read(addr: Int, size: Int): ByteArray {
        val body = "PMTK182,7,%08X,%08X".format(addr, size)
        repeat(5) {
            val line = try {
                command(body, "PMTK182,8,", retries = 1)
            } catch (e: LoggerException.NoReply) {
                return@repeat
            }
            val f = line.split(",", limit = 4)
            if (f.size == 4 && f[2].toIntOrNull(16) == addr && f[3].length == size * 2) {
                runCatching { hex(f[3]) }.getOrNull()?.let { return it }
            }
        }
        throw LoggerException.NoReply(body)
    }

    /** Reads [from, to) in chunks, reporting bytes done. */
    suspend fun read(from: Int, to: Int, progress: (Int) -> Unit): ByteArray {
        val out = ByteArrayOutputStream(maxOf(0, to - from))
        var addr = from
        while (addr < to) {
            currentCoroutineContext().ensureActive()
            val size = minOf(CHUNK, to - addr)
            out.write(read(addr, size))
            addr += size
            progress(addr - from)
        }
        return out.toByteArray()
    }

    /** Erase, re-enable logging and verify (protocol §6). */
    suspend fun eraseAndEnable(): DeviceInfo {
        expectAck("PMTK182,6,1", "PMTK001,182,6,", 60_000)
        expectAck("PMTK182,4", "PMTK001,182,4,", 5_000)
        val info = readInfo()
        if (!info.loggingOn || info.writeAddr != 0x200L) throw LoggerException.EraseNotConfirmed(info.status, info.writeAddr)
        return info
    }

    /** Reads NMEA for a moment to refresh [live]. */
    suspend fun pollLive(durationMs: Long = 1200): LiveStatus? = mutex.withLock {
        val deadline = System.currentTimeMillis() + durationMs
        while (nextLine(deadline) != null) { /* drain */ }
        live
    }

    companion object {
        const val CHUNK = 0x800

        fun hex(s: String): ByteArray {
            require(s.length % 2 == 0)
            return ByteArray(s.length / 2) { i -> ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).also { require(it >= 0) }.toByte() }
        }
    }
}

/** What the library remembers about the last download (protocol §5). */
class DownloadState(val flashId: Long, val model: String, val writeAddr: Long, val image: ByteArray)

sealed class DownloadPlan {
    data class Incremental(val from: Int, val to: Int) : DownloadPlan()
    data class Full(val to: Int) : DownloadPlan()
}

object Downloader {
    private fun signature(image: ByteArray, recordSize: Int): ByteArray? {
        val end = LogFormat.HEADER + recordSize
        if (image.size < end) return null
        return image.copyOfRange(2, 20) + image.copyOfRange(LogFormat.HEADER, end)
    }

    fun plan(info: DeviceInfo, head: ByteArray, previous: DownloadState?): DownloadPlan {
        val f = info.flashSize
        val w = info.writeAddr.toInt()
        val full = DownloadPlan.Full(if (w in 1..f) w else f)
        if (previous == null || previous.flashId != info.flashId || previous.model != info.model) return full
        if (w > f || w < previous.writeAddr) return full
        val size = runCatching { LogFormat.recordSize(info.format) }.getOrNull() ?: return full
        val a = signature(previous.image, size) ?: return full
        val b = signature(head, size) ?: return full
        if (!a.contentEquals(b)) return full
        val from = (previous.writeAddr.toInt() / LoggerClient.CHUNK) * LoggerClient.CHUNK
        return DownloadPlan.Incremental(minOf(from, previous.image.size), w)
    }

    fun apply(plan: DownloadPlan, previous: ByteArray?, read: ByteArray): ByteArray = when (plan) {
        is DownloadPlan.Full -> read
        is DownloadPlan.Incremental -> (previous ?: ByteArray(0)).copyOf(plan.from) + read
    }

    data class Result(val info: DeviceInfo, val state: DownloadState, val plan: DownloadPlan)

    /** info, head chunk, plan, read, splice. */
    suspend fun run(client: LoggerClient, previous: DownloadState?, progress: (Double) -> Unit): Result {
        val info = client.readInfo()
        val headSize = minOf(LoggerClient.CHUNK, maxOf(info.writeAddr.toInt(), LogFormat.HEADER))
        val head = client.read(0, headSize)
        val plan = plan(info, head, previous)
        val (from, to) = when (plan) {
            is DownloadPlan.Incremental -> plan.from to plan.to
            is DownloadPlan.Full -> 0 to plan.to
        }
        val total = maxOf(1, to - from)
        val data = if (from == 0 && to <= head.size) head.copyOf(to)
        else client.read(from, to) { progress(it.toDouble() / total) }
        progress(1.0)
        val image = apply(plan, previous?.image, data)
        return Result(info, DownloadState(info.flashId, info.model, info.writeAddr, image), plan)
    }
}
