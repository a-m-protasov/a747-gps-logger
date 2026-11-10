package io.github.a_m_protasov.a747

import android.content.Context
import io.github.a_m_protasov.a747.core.DeviceInfo
import io.github.a_m_protasov.a747.core.DownloadPlan
import io.github.a_m_protasov.a747.core.Downloader
import io.github.a_m_protasov.a747.core.Exporter
import io.github.a_m_protasov.a747.core.LiveStatus
import io.github.a_m_protasov.a747.core.LogParser
import io.github.a_m_protasov.a747.core.LogPoint
import io.github.a_m_protasov.a747.core.LoggerClient
import io.github.a_m_protasov.a747.core.LoggerException
import io.github.a_m_protasov.a747.core.LoggerTransport
import io.github.a_m_protasov.a747.core.TimeZoneDB
import io.github.a_m_protasov.a747.core.TrackStats
import io.github.a_m_protasov.a747.core.Tracks
import io.github.a_m_protasov.a747.core.UnsupportedFormatException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

data class Track(val points: List<LogPoint>, val stats: TrackStats, val name: String, val day: LocalDate, val zone: ZoneId) {
    val id get() = stats.id
}

data class Waypoint(val point: LogPoint, val name: String, val zone: ZoneId) {
    val id get() = "${point.utc}-${point.lat}-${point.lon}"
}

enum class Link { USB, BLUETOOTH }

sealed interface DeviceState {
    data object Disconnected : DeviceState
    data object Connecting : DeviceState
    data class Ready(val info: DeviceInfo, val link: Link) : DeviceState
    data class Busy(val info: DeviceInfo, val link: Link, val what: Int, val progress: Float?) : DeviceState
    data class Failed(val message: String) : DeviceState
}

/** App-wide state shared by the UI and the download service. */
class Repo(private val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tz = TimeZoneDB(ctx.assets.open("timezones.bin").use { it.readBytes() })
    val exporter = Exporter(tz)
    val library = Library(File(ctx.filesDir, "library"))
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    private val _device = MutableStateFlow<DeviceState>(DeviceState.Disconnected)
    val device: StateFlow<DeviceState> = _device
    val live = MutableStateFlow<LiveStatus?>(null)
    val message = MutableStateFlow<String?>(null)

    var splitGapMinutes: Int
        get() = prefs.getInt("splitGapMinutes", 30)
        set(v) { prefs.edit().putInt("splitGapMinutes", v).apply(); reload() }
    var units: String
        get() = prefs.getString("units", "system")!!
        set(v) { prefs.edit().putString("units", v).apply() }
    var trackColorBySpeed: Boolean
        get() = prefs.getBoolean("trackColorBySpeed", true)
        set(v) { prefs.edit().putBoolean("trackColorBySpeed", v).apply() }
    var trackColor: String
        get() = prefs.getString("trackColor", "#1F8A70")!!
        set(v) { prefs.edit().putString("trackColor", v).apply() }
    var showWaypointsOnTrack: Boolean
        get() = prefs.getBoolean("showWaypointsOnTrack", false)
        set(v) { prefs.edit().putBoolean("showWaypointsOnTrack", v).apply() }

    private var client: LoggerClient? = null
    private var link: Link? = null
    private var poller: Job? = null

    init { reload() }

    fun reload() {
        scope.launch {
            val (points, wpts) = library.load(System.currentTimeMillis() / 1000)
            tracks.value = Tracks.split(points, splitGapMinutes * 60L).map { t ->
                val zone = tz.zone(t[0].lat, t[0].lon)
                Track(t, Tracks.stats(t), exporter.name(t[0]),
                    java.time.Instant.ofEpochSecond(t[0].utc).atZone(zone).toLocalDate(), zone)
            }.reversed()
            waypoints.value = wpts.map { Waypoint(it, exporter.name(it), tz.zone(it.lat, it.lon)) }.reversed()
        }
    }

    private fun info(): DeviceInfo? = when (val d = _device.value) {
        is DeviceState.Ready -> d.info
        is DeviceState.Busy -> d.info
        else -> null
    }

    fun errorText(e: Throwable): String = when (e) {
        is LoggerException.NoReply -> ctx.getString(R.string.the_logger_did_not_answer_x, e.command)
        is LoggerException.BadReply -> ctx.getString(R.string.unexpected_reply_from_the_logger_x, e.reply)
        is LoggerException.CommandFailed -> ctx.getString(R.string.the_logger_rejected_x_code_n, e.command, e.flag)
        is LoggerException.EraseNotConfirmed ->
            ctx.getString(R.string.erase_not_confirmed_status_x_write_address_x, e.status.toString(16), e.writeAddr.toString(16))
        is UnsupportedFormatException -> ctx.getString(R.string.unsupported_log_format_x_satellite_details_sid_a, "%08X".format(e.mask))
        else -> e.localizedMessage ?: e.toString()
    }

    /** Opens a transport (USB or Bluetooth) and reads the device info. */
    fun connect(kind: Link, open: suspend () -> LoggerTransport) {
        scope.launch {
            disconnect()
            _device.value = DeviceState.Connecting
            try {
                val c = LoggerClient(open())
                val info = c.readInfo()
                client = c
                link = kind
                _device.value = DeviceState.Ready(info, kind)
                startPolling()
            } catch (e: Exception) {
                _device.value = DeviceState.Failed(errorText(e))
            }
        }
    }

    suspend fun disconnect() {
        poller?.cancel()
        client?.let { runCatching { it.close() } }
        client = null
        live.value = null
        _device.value = DeviceState.Disconnected
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                val c = client ?: break
                if (_device.value is DeviceState.Ready) {
                    try {
                        live.value = c.pollLive()
                    } catch (e: Exception) {
                        _device.value = DeviceState.Failed(errorText(e))
                        break
                    }
                }
                delay(2000)
            }
        }
    }

    /** Called from [device.DownloadService]. */
    suspend fun download(erase: Boolean) {
        val c = client ?: return
        val l = link ?: return
        val start = info() ?: return
        _device.value = DeviceState.Busy(start, l, R.string.downloading, 0f)
        try {
            val previous = withContext(Dispatchers.IO) { library.lastState }
            val r = Downloader.run(c, previous) { p -> _device.value = DeviceState.Busy(start, l, R.string.downloading, p.toFloat()) }
            val parsed = LogParser.parse(r.state.image, r.state.flashId, r.state.writeAddr.toInt(), System.currentTimeMillis() / 1000)
            withContext(Dispatchers.IO) { library.add(r.state, replacingLast = r.plan is DownloadPlan.Incremental) }
            reload()
            var msg = ctx.getString(R.string.download_complete_points_n_waypoints_n, parsed.points.size, parsed.waypoints.size)
            var info = r.info
            if (erase) {
                _device.value = DeviceState.Busy(info, l, R.string.erasing_memory, null)
                info = c.eraseAndEnable()
                msg += " " + ctx.getString(R.string.memory_erased_logging_on)
            }
            _device.value = DeviceState.Ready(info, l)
            message.value = msg
        } catch (e: Exception) {
            message.value = errorText(e)
            _device.value = DeviceState.Ready(info() ?: start, l)
        }
    }

    val busy get() = _device.value is DeviceState.Busy
}
