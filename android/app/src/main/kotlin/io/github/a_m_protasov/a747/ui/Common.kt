package io.github.a_m_protasov.a747.ui

import android.annotation.SuppressLint
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

@Composable
fun A747Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
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
    MaterialTheme(colorScheme = scheme, content = content)
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

    private fun num(x: Double, decimals: Int) = String.format(Locale.getDefault(), "%.${decimals}f", x)

    fun distance(m: Double): String = if (metric) {
        if (m >= 1000) "${num(m / 1000, 1)} km" else "${num(m, 0)} m"
    } else {
        if (m >= 1609) "${num(m / 1609.344, 1)} mi" else "${num(m * 3.28084, 0)} ft"
    }

    fun height(m: Double) = if (metric) "${num(m, 0)} m" else "${num(m * 3.28084, 0)} ft"
    fun speed(kmh: Double) = if (metric) "${num(kmh, 1)} km/h" else "${num(kmh / 1.609344, 1)} mph"
    fun speedValue(kmh: Double) = if (metric) kmh else kmh / 1.609344
    fun heightValue(m: Double) = if (metric) m else m * 3.28084

    fun duration(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
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
fun MapWeb(tracks: List<List<LogPoint>>, waypoints: List<Waypoint>, modifier: Modifier = Modifier) {
    val repo = A747App.repo
    val metric = UI.isMetric
    val bySpeed = repo.trackColorBySpeed
    val color = repo.trackColor
    // built once per input: rebuilding (and re-sending) it on every recomposition reset the view
    val data = remember(tracks, waypoints, metric, bySpeed, color) { JSONObject().apply {
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
            for (w in waypoints) put(JSONObject().put("lon", w.point.lon).put("lat", w.point.lat).put("name", w.title))
        })
        put("style", JSONObject()
            .put("mode", if (bySpeed) "speed" else "solid")
            .put("color", color)
            .put("unit", if (metric) "km/h" else "mph")
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
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        return true
                    }
                }
                loadUrl("file:///android_asset/map.html")
            }
        },
        update = { web ->
            web.tag = data
            // send only new data: setData fits the view to the data and would undo the user's pan and zoom
            if (web.getTag(MapBridge.LOADED) == true && web.getTag(MapBridge.SENT) != data) {
                web.evaluateJavascript("window.setData($data)", null)
                web.setTag(MapBridge.SENT, data)
            }
        },
    )
}

object MapBridge {
    val LOADED = io.github.a_m_protasov.a747.R.id.map_loaded
    val SENT = io.github.a_m_protasov.a747.R.id.map_sent
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
