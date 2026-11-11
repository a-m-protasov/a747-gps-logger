package io.github.a_m_protasov.a747.ui

import android.annotation.SuppressLint
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.icu.util.ULocale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.a_m_protasov.a747.A747App
import io.github.a_m_protasov.a747.Waypoint
import io.github.a_m_protasov.a747.core.LogPoint
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

val Brand = Color(0xFF1F8A70)
val WaypointColor = Color(0xFFF2994A)

/** Whether the app shows its dark theme (chosen in Settings, or the system's). */
val LocalDark = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun A747Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // brand green on neutral surfaces (the macOS app uses the same palette)
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF6ED3B5), onPrimary = Color(0xFF00382B), primaryContainer = Color(0xFF0F5C4A),
        onPrimaryContainer = Color(0xFFB4F1DA), secondary = Color(0xFF6ED3B5),
        secondaryContainer = Color(0xFF0F5C4A), onSecondaryContainer = Color(0xFFB4F1DA),
        background = Color(0xFF121314), surface = Color(0xFF121314), surfaceContainer = Color(0xFF1E1F21),
        surfaceContainerLow = Color(0xFF1A1B1D), surfaceContainerHigh = Color(0xFF26282A),
    ) else lightColorScheme(
        primary = Brand, onPrimary = Color.White, primaryContainer = Color(0xFFCDEFE3),
        onPrimaryContainer = Color(0xFF00382B), secondary = Brand,
        secondaryContainer = Color(0xFFCDEFE3), onSecondaryContainer = Color(0xFF00382B),
        background = Color(0xFFFCFCFC), surface = Color(0xFFFCFCFC), surfaceContainer = Color(0xFFF2F3F3),
        surfaceContainerLow = Color(0xFFF6F7F7), surfaceContainerHigh = Color(0xFFECEDED),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun InfoCard(title: String, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** UI formatting (localized, unlike exports). */
object UI {
    val isMetric: Boolean get() = metric

    private val metric: Boolean
        get() = when (A747App.repo.units) {
            "metric" -> true
            "imperial" -> false
            else -> Locale.getDefault().country !in setOf("US", "LR", "MM", "GB")
        }

    /** A value with its unit, abbreviated in the app's language ("12,3 км", "5 h 2 min"). */
    private fun measure(v: Number, unit: MeasureUnit, decimals: Int): String {
        val nf = android.icu.text.NumberFormat.getInstance(ULocale.getDefault())
            .apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }
        return MeasureFormat.getInstance(ULocale.getDefault(), MeasureFormat.FormatWidth.SHORT, nf).format(Measure(v, unit))
    }

    fun distance(m: Double): String = if (metric) {
        if (m >= 1000) measure(m / 1000, MeasureUnit.KILOMETER, 1) else measure(m, MeasureUnit.METER, 0)
    } else {
        if (m >= 1609) measure(m / 1609.344, MeasureUnit.MILE, 1) else measure(m * 3.28084, MeasureUnit.FOOT, 0)
    }

    fun height(m: Double) = if (metric) measure(m, MeasureUnit.METER, 0) else measure(m * 3.28084, MeasureUnit.FOOT, 0)
    fun speed(kmh: Double) =
        if (metric) measure(kmh, MeasureUnit.KILOMETER_PER_HOUR, 1) else measure(kmh / 1.609344, MeasureUnit.MILE_PER_HOUR, 1)

    /** "km/h" or "mph" in the app's language, for the map's legend. */
    fun speedUnit(): String {
        val u = if (metric) MeasureUnit.KILOMETER_PER_HOUR else MeasureUnit.MILE_PER_HOUR
        // the unit as it is written next to a number (the display name can be longer: "km/hour")
        return MeasureFormat.getInstance(ULocale.getDefault(), MeasureFormat.FormatWidth.SHORT).format(Measure(0, u))
            .replace(Regex("[\\p{Nd}\\s]+"), " ").trim()
    }
    fun speedValue(kmh: Double) = if (metric) kmh else kmh / 1.609344
    fun heightValue(m: Double) = if (metric) m else m * 3.28084

    fun duration(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val f = MeasureFormat.getInstance(ULocale.getDefault(), MeasureFormat.FormatWidth.SHORT)
        return if (h > 0) f.formatMeasures(Measure(h, MeasureUnit.HOUR), Measure(m, MeasureUnit.MINUTE))
        else f.format(Measure(m, MeasureUnit.MINUTE))
    }

    fun time(utc: Long, zone: ZoneId, withDate: Boolean = false): String {
        val f = if (withDate) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        return f.withLocale(Locale.getDefault()).format(Instant.ofEpochSecond(utc).atZone(zone))
    }

    fun coordinates(p: LogPoint) = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)

    fun day(d: java.time.LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault()).format(d)

    fun bytes(n: Int): String = android.text.format.Formatter.formatShortFileSize(ctx, n.toLong())

    lateinit var ctx: Context
}

/** The shared MapLibre page (shared/map/map.html) with OpenFreeMap tiles. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapWeb(tracks: List<List<LogPoint>>, waypoints: List<Waypoint>, modifier: Modifier = Modifier,
           /** Shows a cross at the centre, whose place [controller] reads. */
           picking: Boolean = false, controller: MapController? = null,
           /** Room kept free at the bottom when the map fits the data, in dp. */
           bottomInset: Int = 0) {
    val repo = A747App.repo
    val theme = if (LocalDark.current) "dark" else "light"
    val metric = UI.isMetric
    val bySpeed = repo.trackColorBySpeed
    val color = repo.trackColor
    // built once per input: rebuilding (and re-sending) it on every recomposition reset the view
    val data = remember(tracks, waypoints, metric, bySpeed, color, bottomInset) { JSONObject().apply {
        put("bottomInset", bottomInset)
        put("tracks", JSONArray().apply {
            for (t in tracks) {
                val speeds = speeds(t)
                val step = maxOf(1, t.size / 3000)
                val idx = (t.indices step step).toMutableList().apply { if (last() != t.size - 1) add(t.size - 1) }
                put(JSONObject()
                    .put("c", JSONArray().apply { for (i in idx) put(JSONArray().put(t[i].lon).put(t[i].lat)) })
                    .put("s", JSONArray().apply { for (i in idx) put(Math.round(speeds[i] * 10) / 10.0) }))
            }
        })
        put("waypoints", JSONArray().apply {
            for (w in waypoints) put(JSONObject().put("lon", w.point.lon).put("lat", w.point.lat).put("name", w.title)
                .apply { w.icon?.let { put("icon", it) } })
        })
        put("style", JSONObject()
            .put("mode", if (bySpeed) "speed" else "solid")
            .put("color", color)
            .put("unit", UI.speedUnit())
            .put("factor", if (metric) 1.0 else 1 / 1.609344))
    }.toString() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                // WRAP_CONTENT (the default) lays the page out with a 0 px viewport height
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                // no orange focus frame around the map after typing in a dialog
                defaultFocusHighlightEnabled = false
                settings.allowFileAccess = true
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.d("A747Map", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        (view.tag as? String)?.let { view.evaluateJavascript("window.setData($it)", null); view.setTag(MapBridge.SENT, it) }
                        view.setTag(MapBridge.LOADED, true)
                        sendState(view)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        return true
                    }
                }
                controller?.web = this
                setTag(MapBridge.THEME_SENT, theme)
                loadUrl("file:///android_asset/map.html?theme=$theme")
            }
        },
        update = { web ->
            controller?.web = web
            web.tag = data
            web.setTag(MapBridge.THEME, theme)
            web.setTag(MapBridge.PICKING, picking)
            if (web.getTag(MapBridge.LOADED) == true) sendState(web)
            // send only new data: setData fits the view to the data and would undo the user's pan and zoom
            if (web.getTag(MapBridge.LOADED) == true && web.getTag(MapBridge.SENT) != data) {
                web.evaluateJavascript("window.setData($data)", null)
                web.setTag(MapBridge.SENT, data)
            }
        },
    )
}

/** Theme and the picking cross, sent when they change. */
private fun sendState(web: WebView) {
    val theme = web.getTag(MapBridge.THEME) as? String
    if (theme != null && web.getTag(MapBridge.THEME_SENT) != theme) {
        web.evaluateJavascript("window.setTheme('$theme')", null)
        web.setTag(MapBridge.THEME_SENT, theme)
    }
    val picking = web.getTag(MapBridge.PICKING) == true
    if (web.getTag(MapBridge.PICKING_SENT) != picking) {
        web.evaluateJavascript("window.setPicking($picking)", null)
        web.setTag(MapBridge.PICKING_SENT, picking)
    }
}

object MapBridge {
    val LOADED = io.github.a_m_protasov.a747.R.id.map_loaded
    val SENT = io.github.a_m_protasov.a747.R.id.map_sent
    val THEME = io.github.a_m_protasov.a747.R.id.map_theme
    val THEME_SENT = io.github.a_m_protasov.a747.R.id.map_theme_sent
    val PICKING = io.github.a_m_protasov.a747.R.id.map_picking
    val PICKING_SENT = io.github.a_m_protasov.a747.R.id.map_picking_sent
}

/** Reads the centre of the map (where the picking cross is) and moves the map. */
class MapController {
    var web: WebView? = null

    fun center(done: (lat: Double, lon: Double) -> Unit) {
        web?.evaluateJavascript("JSON.stringify(window.center())") { r ->
            // the result is the JSON of the returned string
            runCatching { JSONArray(org.json.JSONTokener(r).nextValue() as String) }.getOrNull()
                ?.let { done(it.getDouble(0), it.getDouble(1)) }
        }
    }

    fun goTo(lat: Double, lon: Double) { web?.evaluateJavascript("window.goTo($lat, $lon)", null) }
}

/** A simple area/line chart (no chart library needed). */
@Composable
fun MiniChart(values: List<Double>, color: Color, fill: Boolean, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max().let { if (it - min < 1e-6) min + 1 else it }
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val dx = size.width / (values.size - 1)
        fun y(v: Double) = size.height - ((v - min) / (max - min) * size.height).toFloat()
        val line = Path().apply {
            moveTo(0f, y(values[0]))
            values.forEachIndexed { i, v -> lineTo(i * dx, y(v)) }
        }
        if (fill) {
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, color.copy(alpha = 0.25f))
        }
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        drawLine(color.copy(alpha = 0.2f), Offset(0f, size.height), Offset(size.width, size.height))
    }
}

fun openUrl(ctx: Context, url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/** Speed at each point in km/h: the logged value, or derived from the previous point. */
fun speeds(t: List<LogPoint>): DoubleArray = DoubleArray(t.size) { i ->
    t[i].speed ?: if (i > 0 && t[i].utc > t[i - 1].utc)
        io.github.a_m_protasov.a747.core.Tracks.haversine(t[i - 1], t[i]) / (t[i].utc - t[i - 1].utc) * 3.6 else 0.0
}
