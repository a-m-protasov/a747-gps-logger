package io.github.a_m_protasov.a747

import io.github.a_m_protasov.a747.core.DownloadState
import io.github.a_m_protasov.a747.core.LogParser
import io.github.a_m_protasov.a747.core.LogPoint
import io.github.a_m_protasov.a747.core.PointsFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The local library: raw images of every download. Tracks and waypoints are derived from all
 * images together, so re-downloading never creates duplicates (same design as on macOS).
 */
class Library(private val dir: File) {
    data class Entry(
        val id: String,
        val file: String,
        val downloadedAt: Long,
        val flashId: Long,
        val model: String,
        val writeAddr: Long,
    )

    private val images = File(dir, "images").apply { mkdirs() }
    private val index = File(dir, "library.json")
    var entries: List<Entry> = load()
        private set

    private fun load(): List<Entry> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(o.getString("id"), o.getString("file"), o.getLong("downloadedAt"), o.getLong("flashId"),
                o.getString("model"), o.getLong("writeAddr"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        for (e in entries) a.put(JSONObject().put("id", e.id).put("file", e.file).put("downloadedAt", e.downloadedAt)
            .put("flashId", e.flashId).put("model", e.model).put("writeAddr", e.writeAddr))
        val tmp = File(dir, "library.json.tmp")
        tmp.writeText(a.toString(1))
        tmp.renameTo(index)
    }

    fun imageFile(e: Entry) = File(images, e.file)

    /** The newest download, for an incremental read. */
    val lastState: DownloadState?
        get() = entries.maxByOrNull { it.downloadedAt }?.let { e ->
            runCatching { DownloadState(e.flashId, e.model, e.writeAddr, imageFile(e).readBytes()) }.getOrNull()
        }

    val lastImage: File? get() = entries.maxByOrNull { it.downloadedAt }?.let(::imageFile)

    /** Stores a download. An incremental download replaces the image it continued. */
    @Synchronized
    fun add(state: DownloadState, replacingLast: Boolean) {
        var list = entries
        if (replacingLast) list.maxByOrNull { it.downloadedAt }?.let { last ->
            imageFile(last).delete()
            list = list - last
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd'_'HHmmss", Locale.ROOT).format(Date())
        val e = Entry(UUID.randomUUID().toString(), "747-$stamp.bin", System.currentTimeMillis(), state.flashId, state.model, state.writeAddr)
        val tmp = File(images, e.file + ".tmp")
        tmp.writeBytes(state.image)
        tmp.renameTo(imageFile(e))
        entries = list + e
        save()
    }

    /** Parses all images and merges them. */
    fun load(nowSec: Long): Pair<List<LogPoint>, List<LogPoint>> {
        val points = ArrayList<LogPoint>()
        val wpts = ArrayList<LogPoint>()
        for (e in entries) {
            val r = runCatching { LogParser.parse(imageFile(e).readBytes(), e.flashId, e.writeAddr.toInt(), nowSec) }.getOrNull() ?: continue
            points += r.points
            wpts += r.waypoints
        }
        return dedupe(points) to dedupe(wpts)
    }

    // MARK: Track files

    /**
     * Tracks imported from files or recorded on this phone: one track (or a set of waypoints) per
     * file in `tracks/`, in the PointsFile format. Names start with `import-` or `phone-`.
     */
    val tracksDir = File(dir, "tracks")

    class TrackFile(val file: File, val track: List<LogPoint>, val waypoints: List<LogPoint>) {
        val isPhone get() = file.name.startsWith("phone-")
    }

    fun loadFiles(): List<TrackFile> =
        tracksDir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            runCatching { PointsFile.decode(f.readText()) }.getOrNull()?.let { (t, w) -> TrackFile(f, t, w) }
        }

    fun addFile(track: List<LogPoint> = emptyList(), waypoints: List<LogPoint> = emptyList()): File {
        tracksDir.mkdirs()
        val first = (track.firstOrNull() ?: waypoints.firstOrNull())?.utc ?: 0
        val f = File(tracksDir, "import-$first-${UUID.randomUUID().toString().take(8)}.csv")
        val tmp = File(tracksDir, f.name + ".tmp")
        tmp.writeText(PointsFile.encode(track, waypoints))
        tmp.renameTo(f)
        return f
    }

    /** A new file for a recording on this phone (points are appended as they come). */
    fun newRecording(startUtc: Long): File {
        tracksDir.mkdirs()
        return File(tracksDir, "phone-$startUtc.csv").apply { writeText(PointsFile.HEADER) }
    }

    fun dedupe(pts: List<LogPoint>): List<LogPoint> {
        val seen = HashSet<Triple<Long, Double, Double>>()
        return pts.sortedBy { it.utc }.filter { seen.add(Triple(it.utc, it.lat, it.lon)) }
    }
}

/**
 * What the user changed on top of the raw downloads: names, and deleted tracks and waypoints
 * (same file format as on macOS). Deletions are time ranges, so a deleted track stays hidden
 * after later downloads too.
 */
class LibraryEdits(private val file: File) {
    /** Names keyed by the UTC time of a track's first point or of a waypoint. */
    var names: Map<Long, String> = emptyMap(); private set
    /** Deleted tracks as first..last UTC times. */
    var deletedTracks: List<LongRange> = emptyList(); private set
    var deletedWaypoints: Set<Long> = emptySet(); private set

    init {
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONObject("names")?.let { n -> names = n.keys().asSequence().associate { it.toLong() to n.getString(it) } }
            o.optJSONArray("deletedTracks")?.let { a ->
                deletedTracks = (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getLong(0)..r.getLong(1) } }
            }
            o.optJSONArray("deletedWaypoints")?.let { a -> deletedWaypoints = (0 until a.length()).map(a::getLong).toSet() }
        }
    }

    fun isDeleted(utc: Long) = deletedTracks.any { utc in it }

    @Synchronized
    fun rename(utc: Long, name: String) {
        val n = name.trim()
        names = if (n.isEmpty()) names - utc else names + (utc to n)
        save()
    }

    @Synchronized
    fun delete(tracks: List<LongRange>, waypoints: List<Long>) {
        deletedTracks = deletedTracks + tracks
        deletedWaypoints = deletedWaypoints + waypoints
        save()
    }

    /** Waypoints imported again come back. */
    @Synchronized
    fun undelete(waypoints: List<Long>) {
        deletedWaypoints = deletedWaypoints - waypoints.toSet()
        save()
    }

    private fun save() {
        val o = JSONObject()
            .put("names", JSONObject().apply { for ((k, v) in names) put(k.toString(), v) })
            .put("deletedTracks", JSONArray().apply { for (r in deletedTracks) put(JSONArray().put(r.first).put(r.last)) })
            .put("deletedWaypoints", JSONArray().apply { for (w in deletedWaypoints.sorted()) put(w) })
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString(1))
        tmp.renameTo(file)
    }
}
