package md.thomas.openoura.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import md.thomas.openoura.data.SummaryJson
import md.thomas.openoura.store.DB
import uniffi.oura_core.eventsJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// Everything the ring has actually sent, straight from the local database. The dashboard
// shows interpreted results; this shows the inputs — it answers "is the ring emitting this
// at all?", which decides whether a missing panel is a decode gap, a feature that's off, or
// a night not slept yet. Any numeric field a decoder produced can be plotted against time.
// Port of apps/ios/OuraApp/RawData.swift.

object RawData {
    data class Kind(val name: String, val total: Int, val decoded: Int)

    data class Event(
        val name: String,
        val tag: Int,
        val ringTimestamp: Long,
        val capturedUnix: Long,
        val unix: Double,
        val bodyLen: Int,
        /**
         * Numeric leaves of the decoded object, plus the first level of any numeric array
         * (`{"ibi_ms": [812, 806]}` → one point per sample). Built once at load.
         */
        val numbers: Map<String, List<Double>>,
        val prettyJson: String,
    )

    data class Point(val t: Double, val v: Double)

    data class Page(
        val kinds: List<Kind> = emptyList(),
        val events: List<Event> = emptyList(),
        val fields: List<String> = emptyList(),
        val error: String? = null,
    )

    private val pretty = Json { prettyPrint = true }

    /** The type list alone (no filter) asks for no rows, so the core skips the clock scan. */
    fun load(context: Context, filter: String, limit: Int = 400): Page = try {
        parse(eventsJson(DB.readPath(context), filter, if (filter.isEmpty()) 0u else limit.toUInt()))
    } catch (e: Throwable) {
        Page(error = "core call failed: ${e.message ?: e}")
    }

    internal fun parse(raw: String): Page {
        val root = try {
            SummaryJson.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            return Page(error = "unreadable response")
        }
        (root["error"] as? JsonPrimitive)?.contentOrNull?.let { return Page(error = it) }
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

        val kinds = (root["counts"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            Kind(o.str("name") ?: "?", o.int("total") ?: 0, o.int("decoded") ?: 0)
        }
        val events = (root["events"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val decoded = o["decoded"] as? JsonObject
            val bodyLen = o.int("body_len") ?: 0
            Event(
                name = o.str("name") ?: "?",
                tag = o.int("tag") ?: 0,
                ringTimestamp = o.long("ring_timestamp") ?: 0,
                capturedUnix = o.long("captured_unix") ?: 0,
                unix = (o["unix_s"] as? JsonPrimitive)?.doubleOrNull ?: (o.long("captured_unix") ?: 0).toDouble(),
                bodyLen = bodyLen,
                numbers = numbersOf(decoded),
                prettyJson = decoded?.let { pretty.encodeToString(JsonElement.serializer(), JsonObject(it.toSortedMap())) }
                    ?: "not decoded — $bodyLen raw bytes stored",
            )
        }
        return Page(kinds, events, events.flatMap { it.numbers.keys }.toSortedSet().toList())
    }

    private fun numbersOf(decoded: JsonObject?): Map<String, List<Double>> {
        val out = LinkedHashMap<String, List<Double>>()
        for ((key, value) in decoded ?: return out) {
            when (value) {
                is JsonPrimitive -> if (!value.isString) value.doubleOrNull?.let { out[key] = listOf(it) }
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }
                    .takeIf { it.isNotEmpty() }?.let { out[key] = it }
                else -> {}
            }
        }
        return out
    }

    /**
     * Chart series for one field, oldest → newest. Long series are thinned to each bucket's
     * min and max, keeping spikes and the envelope while drawing a few thousand segments.
     */
    fun points(events: List<Event>, field: String, maxPoints: Int = 2000): List<Point> {
        val all = events.asReversed().flatMap { e ->
            val values = e.numbers[field]
            if (values == null || e.unix <= 0) emptyList()
            else values.mapIndexed { i, v -> Point(e.unix + i * 0.001, v) }
        }
        if (all.size <= maxPoints) return all
        val bucket = ceil(all.size.toDouble() / max(1, maxPoints / 2)).toInt()
        val out = ArrayList<Point>(maxPoints + 2)
        var start = 0
        while (start < all.size) {
            val slice = all.subList(start, minOf(start + bucket, all.size))
            val lo = slice.minBy { it.v }
            val hi = slice.maxBy { it.v }
            if (lo.t <= hi.t) {
                out.add(lo)
                if (hi != lo) out.add(hi)
            } else {
                out.add(hi)
                out.add(lo)
            }
            start += bucket
        }
        return out
    }
}

@Composable
fun RawDataScreen(onBack: () -> Unit) {
    val colors = Obs.colors
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<RawData.Page?>(null) }
    var kinds by remember { mutableStateOf<List<RawData.Kind>>(emptyList()) }
    var loadingEvents by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf<String?>(null) }
    var fieldMenu by remember { mutableStateOf(false) }

    LaunchedEffect(filter) {
        loadingEvents = filter.isNotEmpty()
        val loaded = withContext(Dispatchers.IO) { RawData.load(context.applicationContext, filter) }
        page = loaded
        if (loaded.error == null) kinds = loaded.kinds
        field = if (filter.isEmpty()) null else field?.takeIf { it in loaded.fields } ?: loaded.fields.firstOrNull()
        loadingEvents = false
    }
    val events = if (filter.isEmpty()) emptyList() else page?.events.orEmpty()
    val points = remember(events, field) { field?.let { RawData.points(events, it) }.orEmpty() }

    Sheet(if (filter.isEmpty()) "Raw data" else filter, onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val error = page?.error
            when {
                error != null && kinds.isEmpty() -> Text(error, fontFamily = Obs.prose, fontSize = 14.sp, color = colors.alert)
                page == null -> Text("Reading the ring database…", fontFamily = Obs.mono, fontSize = 12.sp, color = colors.ink2)
                kinds.isEmpty() -> Text("Nothing stored yet. Sync the ring and come back.", fontFamily = Obs.prose, fontSize = 14.sp, color = colors.ink2)
                else -> {
                    Column(Modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("event types", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2)
                        for (k in kinds) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 36.dp)
                                    .clickable { filter = if (filter == k.name) "" else k.name },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(k.name, fontFamily = Obs.mono, fontSize = 12.sp, color = if (filter == k.name) colors.ink else colors.ink2, modifier = Modifier.weight(1f))
                                Text(
                                    if (k.decoded == k.total) "${k.total}" else "${k.decoded}/${k.total}",
                                    fontFamily = Obs.mono, fontSize = 12.sp, color = colors.muted,
                                )
                            }
                        }
                        Text(
                            "count shown as decoded/total when some bodies have no decoder yet.",
                            fontFamily = Obs.prose, fontSize = 12.sp, color = colors.ink2,
                        )
                    }
                    if (filter.isNotEmpty()) {
                        if (loadingEvents && events.isEmpty()) {
                            Text("loading…", fontFamily = Obs.mono, fontSize = 12.sp, color = colors.muted)
                        } else {
                            val fields = page?.fields.orEmpty()
                            Column(Modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("chart", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2)
                                    Spacer(Modifier.weight(1f))
                                    if (fields.isNotEmpty()) {
                                        Box {
                                            Text(
                                                field ?: "pick a field",
                                                modifier = Modifier.clickable { fieldMenu = true },
                                                fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink,
                                            )
                                            DropdownMenu(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }) {
                                                for (f in fields) {
                                                    DropdownMenuItem(text = { Text(f) }, onClick = {
                                                        field = f
                                                        fieldMenu = false
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                                when {
                                    fields.isEmpty() -> Text("No numeric fields in these events.", fontFamily = Obs.prose, fontSize = 12.sp, color = colors.ink2)
                                    field == null -> Text("Pick a field to plot it over time.", fontFamily = Obs.prose, fontSize = 12.sp, color = colors.ink2)
                                    else -> {
                                        TimeSeriesChart(points, Modifier.fillMaxWidth().height(180.dp))
                                        if (points.isNotEmpty()) {
                                            Row(Modifier.fillMaxWidth()) {
                                                Text(rawStamp(points.first().t), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                                                Spacer(Modifier.weight(1f))
                                                Text("${points.size} pts", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                                                Spacer(Modifier.weight(1f))
                                                Text(rawStamp(points.last().t), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                                            }
                                        }
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("newest first · ${events.size} shown", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2)
                                for (e in events.take(60)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(if (e.unix > 0) rawStamp(e.unix) else "no time", fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink2)
                                        Spacer(Modifier.weight(1f))
                                        Text("tag 0x${e.tag.toString(16)} · ds ${e.ringTimestamp}", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                                    }
                                    SelectionContainer {
                                        Text(e.prettyJson, fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink)
                                    }
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A plain time-series line: x is real time, so gaps in the data look like gaps. */
@Composable
private fun TimeSeriesChart(points: List<RawData.Point>, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Column(modifier) {
        if (points.size > 1) {
            val y0 = points.minOf { it.v }
            val y1 = points.maxOf { it.v }
            Text(fmtAxis(y1), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val x0 = points.minOf { it.t }
                val xSpan = max(points.maxOf { it.t } - x0, 1e-6)
                val ySpan = max(y1 - y0, 1e-6)
                fun place(p: RawData.Point) = Offset(
                    (size.width * (p.t - x0) / xSpan).toFloat(),
                    (size.height * (1 - (p.v - y0) / ySpan)).toFloat(),
                )
                val line = Path().apply {
                    val first = place(points[0])
                    moveTo(first.x, first.y)
                    for (p in points.drop(1)) place(p).let { lineTo(it.x, it.y) }
                }
                drawPath(line, colors.chart, style = Stroke(1.1f, join = StrokeJoin.Round))
            }
            Text(fmtAxis(y0), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
        }
    }
}

private fun fmtAxis(v: Double): String =
    if (abs(v) >= 100 || v == v.roundToInt().toDouble()) "${v.roundToInt()}" else "%.1f".format(v)

private val RAW_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun rawStamp(unix: Double): String = RAW_STAMP.format(Instant.ofEpochSecond(unix.toLong()))
