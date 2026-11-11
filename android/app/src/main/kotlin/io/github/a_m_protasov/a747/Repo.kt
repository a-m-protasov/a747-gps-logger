package io.github.a_m_protasov.a747

import android.content.Context
import io.github.a_m_protasov.a747.core.DeviceInfo
import io.github.a_m_protasov.a747.core.DownloadPlan
import io.github.a_m_protasov.a747.core.Downloader
import io.github.a_m_protasov.a747.core.Exporter
import io.github.a_m_protasov.a747.core.ImportException
import io.github.a_m_protasov.a747.core.Importer
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

/** A row of the library list: tracks and waypoints together, newest first. */
sealed interface Item {
    val key: String
    /** Stable across downloads that add points to a track (for the map's choice). */
    val mapKey get() = (if (this is Track) "t" else "w") + utc
    val utc: Long
    val day: LocalDate
    val zone: ZoneId
    /** Default name: local date and time. */
    val name: String
    /** Name given by the user. */
    val customName: String?
    val title get() = customName ?: name
}

/** Where a track came from. */
enum class Source { LOGGER, IMPORTED, PHONE }

data class Track(val points: List<LogPoint>, val stats: TrackStats, override val name: String, override val customName: String?,
                 override val day: LocalDate, override val zone: ZoneId, val source: Source = Source.LOGGER,
                 /** The file of an imported or phone track; deleting the track deletes it. */
                 val file: File? = null) : Item {
    val id get() = file?.let { "f:${it.name}" } ?: stats.id
    override val key get() = "t:$id"
    override val utc get() = stats.startUtc
}

data class Waypoint(val point: LogPoint, override val name: String, override val customName: String?,
                    override val day: LocalDate, override val zone: ZoneId,
                    /** An emoji from [WaypointIcons], or null for the plain marker. */
                    val icon: String? = null) : Item {
    val id get() = "${point.utc}-${point.lat}-${point.lon}"
    override val key get() = "w:$id"
    override val utc get() = point.utc
}

/**
 * Icons a waypoint can have (the same list as on macOS). Emoji look the same on the map page and
 * in the lists, and need no artwork.
 */
object WaypointIcons {
    val all = listOf("⭐", "❤️", "🚩", "⚠️", "🎯", "💡",
        "🏠", "🏢", "🏨", "⛺", "🏛️", "⛪", "🏰", "🏥",
        "🍴", "☕", "🍺", "🍷", "🍕", "🍦", "🥐",
        "🛒", "🛍️", "🏪", "💊", "🏦", "📮",
        "⛽", "🔌", "🅿️", "🚗", "🚲", "🚶", "🚌", "🚉", "⚓", "✈️",
        "📷", "🏔️", "🌲", "🏖️", "💧", "🌊", "🌻", "🐾", "🏊", "⛷️", "🎭", "🎡", "🏟️", "🌉")
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
    val library = Library(File(ctx.filesDir, "library"))
    val edits = LibraryEdits(File(ctx.filesDir, "library/edits.json"))
    /** Exports use the names the user gave. */
    val exporter get() = Exporter(tz, edits.names)
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val items = MutableStateFlow<List<Item>>(emptyList())
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
    /** Tracks and waypoints shown on the Map screen ([Item.mapKey]). */
    var mapShown: Set<String>
        get() = prefs.getStringSet("mapShown", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("mapShown", v).apply() }
    /** Seconds between points when recording on this phone. */
    var recordIntervalS: Int
        get() = prefs.getInt("recordIntervalS", 5)
        set(v) { prefs.edit().putInt("recordIntervalS", v).apply() }

    /** The recording on this phone in progress, see [device.RecordService]. */
    data class Recording(val file: File?, val points: Int = 0, val distanceM: Double = 0.0, val startUtc: Long? = null,
                         val last: LogPoint? = null)
    val recording = MutableStateFlow<Recording?>(null)

    /** "system", "light" or "dark". */
    val theme = MutableStateFlow(prefs.getString("theme", "system")!!)
    fun setTheme(v: String) { prefs.edit().putString("theme", v).apply(); theme.value = v }

    var showWaypointsOnTrack: Boolean
        get() = prefs.getBoolean("showWaypointsOnTrack", false)
        set(v) { prefs.edit().putBoolean("showWaypointsOnTrack", v).apply() }

    private var client: LoggerClient? = null
    private var link: Link? = null
    private var poller: Job? = null

    init { reload() }

    fun reload() {
        scope.launch { build() }
    }

    @Synchronized
    private fun build() {
        val (all, allWpts) = library.load(System.currentTimeMillis() / 1000)
        val points = all.filterNot { edits.isDeleted(it.utc) }
        val files = library.loadFiles()
        val wpts = library.dedupe(allWpts + files.flatMap { it.waypoints }).filterNot { it.utc in edits.deletedWaypoints }
        val ex = exporter
        fun day(p: LogPoint, zone: ZoneId) = java.time.Instant.ofEpochSecond(p.utc).atZone(zone).toLocalDate()
        fun track(t: List<LogPoint>, source: Source, file: File?): Track {
            val zone = tz.zone(t[0].lat, t[0].lon)
            return Track(t, Tracks.stats(t), ex.name(t[0]), edits.names[t[0].utc], day(t[0], zone), zone, source, file)
        }
        val logger = Tracks.split(points, splitGapMinutes * 60L).map { track(it, Source.LOGGER, null) }
        val imported = files.filter { it.track.isNotEmpty() }
            .map { track(it.track, if (it.isPhone) Source.PHONE else Source.IMPORTED, it.file) }
        val t = (logger + imported).sortedByDescending { it.utc }
        val w = wpts.map {
            val zone = tz.zone(it.lat, it.lon)
            Waypoint(it, ex.name(it), edits.names[it.utc], day(it, zone), zone, edits.icons[it.utc])
        }.reversed()
        tracks.value = t
        waypoints.value = w
        items.value = (t + w).sortedByDescending { it.utc }
    }

    /**
     * Adds GPX, KML, CSV and NMEA files, and raw .bin dumps of the logger's memory. A track or
     * waypoint that is already in the library is skipped; names in the files are kept.
     */
    fun importFiles(files: List<Pair<String, ByteArray>>) {
        scope.launch(Dispatchers.IO) {
            build()
            var tracksAdded = 0
            var wptsAdded = 0
            var skipped = 0
            val failed = ArrayList<String>()
            val ex = exporter
            val names = HashMap<Long, String>()
            for ((name, data) in files) {
                try {
                    if (name.lowercase().endsWith(".bin")) {
                        LogParser.parse(data, 0, data.size, System.currentTimeMillis() / 1000)
                        val before = tracks.value.size to waypoints.value.size
                        library.add(io.github.a_m_protasov.a747.core.DownloadState(0, "import", data.size.toLong(), data), replacingLast = false)
                        build()
                        tracksAdded += maxOf(0, tracks.value.size - before.first)
                        wptsAdded += maxOf(0, waypoints.value.size - before.second)
                        continue
                    }
                    val r = Importer.parse(data, name)
                    for (t in r.tracks) {
                        if (tracks.value.any { it.stats.startUtc == t[0].utc && it.stats.endUtc == t.last().utc }) { skipped++; continue }
                        library.addFile(track = t)
                        tracksAdded++
                    }
                    val have = waypoints.value.map { it.point.utc }.toSet()
                    val newWpts = r.waypoints.filter { it.utc !in have }
                    skipped += r.waypoints.size - newWpts.size
                    if (newWpts.isNotEmpty()) {
                        library.addFile(waypoints = newWpts)
                        wptsAdded += newWpts.size
                        edits.undelete(newWpts.map { it.utc })
                    }
                    for ((utc, n) in r.names) {
                        if (edits.names.containsKey(utc)) continue
                        val p = r.tracks.firstOrNull { it[0].utc == utc }?.get(0) ?: r.waypoints.firstOrNull { it.utc == utc } ?: continue
                        if (n != ex.name(p)) names[utc] = n
                    }
                    build()
                } catch (e: Exception) {
                    failed += "$name: " + when (e) {
                        is ImportException.UnknownFormat -> ctx.getString(R.string.not_a_gpx_kml_csv_or_nmea_file)
                        is ImportException.NoTimedPoints -> ctx.getString(R.string.the_file_has_no_points_with_a_time)
                        else -> errorText(e)
                    }
                }
            }
            for ((utc, n) in names) edits.rename(utc, n)
            build()
            var msg = ctx.getString(R.string.tracks_added_n_waypoints_added_n, tracksAdded, wptsAdded)
            if (skipped > 0) msg += " " + ctx.getString(R.string.already_in_the_library_n, skipped)
            if (failed.isNotEmpty()) msg += "\n" + failed.joinToString("\n")
            message.value = msg
        }
    }

    /** Sets (or, with an empty string, clears) the name of a track or waypoint. */
    fun rename(item: Item, name: String) {
        edits.rename(if (item is Track) item.points[0].utc else (item as Waypoint).point.utc, name)
        reload()
    }

    /** Adds a waypoint made by hand, timed now; returns it once the list is rebuilt. */
    fun addWaypoint(lat: Double, lon: Double, name: String, icon: String?, done: (Waypoint?) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            build()
            var utc = System.currentTimeMillis() / 1000
            val taken = waypoints.value.map { it.point.utc }.toSet()
            while (utc in taken) utc++  // the time is the waypoint's key
            library.addWaypoint(LogPoint(utc, lat, lon))
            edits.undelete(listOf(utc))
            edits.rename(utc, name)
            edits.setIcon(utc, icon)
            build()
            val w = waypoints.value.firstOrNull { it.point.utc == utc }
            withContext(Dispatchers.Main) { done(w) }
        }
    }

    /** Sets the name and icon of a waypoint. */
    fun edit(w: Waypoint, name: String, icon: String?) {
        edits.rename(w.point.utc, name)
        edits.setIcon(w.point.utc, icon)
        reload()
    }

    fun delete(items: List<Item>) {
        val active = recording.value?.file
        for (t in items.filterIsInstance<Track>()) t.file?.takeIf { it != active }?.delete()
        edits.delete(items.filterIsInstance<Track>().filter { it.file == null }.map { it.stats.startUtc..it.stats.endUtc },
            items.filterIsInstance<Waypoint>().map { it.point.utc })
        reload()
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
