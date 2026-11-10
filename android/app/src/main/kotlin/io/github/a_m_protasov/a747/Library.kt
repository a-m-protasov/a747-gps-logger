package io.github.a_m_protasov.a747

import io.github.a_m_protasov.a747.core.DownloadState
import io.github.a_m_protasov.a747.core.LogParser
import io.github.a_m_protasov.a747.core.LogPoint
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

    private fun dedupe(pts: List<LogPoint>): List<LogPoint> {
        val seen = HashSet<Triple<Long, Double, Double>>()
        return pts.sortedBy { it.utc }.filter { seen.add(Triple(it.utc, it.lat, it.lon)) }
    }
}
