package md.thomas.openoura.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import md.thomas.openoura.core.Core
import md.thomas.openoura.data.SummaryJson
import md.thomas.openoura.store.DB
import uniffi.oura_core.hourlyHrJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// Hourly heart-rate bars — the day-shaped HR view behind the heart rate vital. One bar per
// local-clock hour spans that hour's 5th–95th percentile band with a tick at the median:
// percentiles, not raw extremes, because the ring's beat streams do emit misdetections.
// The true min/max ride along for the touch readout. Aggregation is in Rust
// (`oura-summary::hourly_hr`, via `hourly_hr_json`). Port of apps/ios/OuraApp/HeartRate.swift.

object HourlyHR {
    data class Bar(
        val unix: Double, // UTC start of the local hour
        val ymd: String,
        val hour: Int,
        val low: Double, // 5th percentile of the hour's beats
        val high: Double, // 95th percentile
        val median: Double,
        val min: Double, // true extremes, for the readout only
        val max: Double,
        val count: Int,
    )

    data class Reading(val bpm: Double, val unix: Double)

    data class Result(val bars: List<Bar> = emptyList(), val latest: Reading? = null, val error: String? = null)

    fun load(context: Context, days: Int): Result = try {
        parse(hourlyHrJson(DB.readPath(context), Core.tzOffsetHours(), days.toUInt()))
    } catch (e: Throwable) {
        Result(error = "core call failed: ${e.message ?: e}")
    }

    /** `{hours:[{unix, ymd, hour, low, high, median, min, max, count}], latest:{bpm, unix}}` */
    internal fun parse(json: String): Result {
        val root = try {
            SummaryJson.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            return Result(error = "unreadable response")
        }
        (root["error"] as? JsonPrimitive)?.contentOrNull?.let { return Result(error = it) }
        fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
        val bars = (root["hours"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: emptyList()).mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val unix = row.num("unix") ?: return@mapNotNull null
            val low = row.num("low") ?: return@mapNotNull null
            val high = row.num("high") ?: return@mapNotNull null
            Bar(
                unix = unix,
                ymd = (row["ymd"] as? JsonPrimitive)?.contentOrNull ?: "",
                hour = (row["hour"] as? JsonPrimitive)?.intOrNull ?: 0,
                low = low,
                high = high,
                median = row.num("median") ?: ((low + high) / 2),
                min = row.num("min") ?: low,
                max = row.num("max") ?: high,
                count = (row["count"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        val latest = (root["latest"] as? JsonObject)?.let { l ->
            val bpm = l.num("bpm")
            val unix = l.num("unix")
            if (bpm != null && unix != null) Reading(bpm, unix) else null
        }
        return Result(bars, latest)
    }

    /**
     * Bars inside the window, anchored on the newest hour that has data — a ring that last
     * synced yesterday still fills the chart.
     */
    internal fun visible(bars: List<Bar>, window: HourlyWindow): List<Bar> {
        val newest = bars.lastOrNull()?.unix ?: return emptyList()
        val cut = newest - (window.hours - 1) * 3600.0
        return bars.filter { it.unix >= cut }
    }

    /**
     * One slot per hour of the window, gaps included: the axis is real time, so a night on
     * the charger reads as a gap, not a shorter chart.
     */
    internal fun slots(bars: List<Bar>, window: HourlyWindow): List<Double> {
        val newest = bars.lastOrNull()?.unix ?: return emptyList()
        return (0 until window.hours).map { newest - (window.hours - 1 - it) * 3600.0 }
    }
}

enum class HourlyWindow(val label: String, val hours: Int) {
    H24("24h", 24), H48("48h", 48), D7("7d", 24 * 7);

    /** What to ask the FFI for — one extra day so switching windows never refetches. */
    val days: Int get() = if (this == D7) 8 else 2
}

/** The hourly panel: current reading, the range bars, and the window's extremes. */
@Composable
fun HourlyHeartRateSection(modifier: Modifier = Modifier) {
    val colors = Obs.colors
    val context = LocalContext.current
    var window by remember { mutableStateOf(HourlyWindow.H24) }
    var result by remember { mutableStateOf(HourlyHR.Result()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<HourlyHR.Bar?>(null) }

    LaunchedEffect(window.days) {
        loading = true
        result = withContext(Dispatchers.IO) { HourlyHR.load(context.applicationContext, window.days) }
        loading = false
    }
    val visible = remember(result, window) { HourlyHR.visible(result.bars, window) }

    Column(modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Rule("hourly", Modifier.weight(1f))
            if (loading) Text("loading…", fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted)
        }
        Segmented(
            options = HourlyWindow.entries.map { it.label },
            selectedIndex = HourlyWindow.entries.indexOf(window),
            onSelect = {
                selected = null
                window = HourlyWindow.entries[it]
            },
        )
        val error = result.error
        when {
            error != null -> Text(error, fontFamily = Obs.mono, fontSize = 12.sp, color = colors.bad)
            visible.isEmpty() -> Text(
                if (loading) "Reading the ring database…" else "No beats stored for this window yet. Sync the ring.",
                fontFamily = Obs.mono, fontSize = 12.sp, color = colors.ink2,
            )
            else -> {
                HourlyReadout(selected, result.latest)
                HourlyRangeChart(visible, window, selected, onSelect = { selected = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(slotLabel(visible.first()), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.ink2)
                    Spacer(Modifier.weight(1f))
                    Text("touch a bar for that hour", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                    Spacer(Modifier.weight(1f))
                    Text(slotLabel(visible.last()), fontFamily = Obs.mono, fontSize = 9.sp, color = colors.ink2)
                }
                val beats = visible.sumOf { it.count }
                val weighted = visible.sumOf { it.median * it.count }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    visible.minOfOrNull { it.low }?.let { ObsStat("quietest hour", "${it.roundToInt()} bpm") }
                    visible.maxOfOrNull { it.high }?.let { ObsStat("busiest hour", "${it.roundToInt()} bpm") }
                    if (beats > 0) ObsStat("typical", "${(weighted / beats).roundToInt()} bpm")
                    ObsStat("hours with data", "${visible.size}/${window.hours}")
                }
            }
        }
    }
}

/** Either the hour you are touching, or the latest reading when nothing is held. */
@Composable
private fun HourlyReadout(bar: HourlyHR.Bar?, latest: HourlyHR.Reading?) {
    val colors = Obs.colors
    if (bar != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${bar.low.roundToInt()}–${bar.high.roundToInt()}", fontFamily = Obs.mono, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                Spacer(Modifier.width(6.dp))
                Text("bpm", fontFamily = Obs.mono, fontSize = 13.sp, color = colors.ink2)
            }
            Text(
                "${slotLabel(bar)} · median ${bar.median.roundToInt()} · range ${bar.min.roundToInt()}–${bar.max.roundToInt()} · ${bar.count} beats",
                fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted,
            )
        }
    } else if (latest != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${latest.bpm.roundToInt()}", fontFamily = Obs.mono, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                Spacer(Modifier.width(6.dp))
                Text("bpm", fontFamily = Obs.mono, fontSize = 13.sp, color = colors.ink2)
                Spacer(Modifier.width(6.dp))
                Text("latest", fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted)
            }
            Text(STAMP.format(Instant.ofEpochSecond(latest.unix.toLong())), fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted)
        }
    }
}

/**
 * Range bars on a real-time axis: every hour of the window gets a slot, so a gap in the
 * data is a gap in the chart. Each bar spans the hour's 5th–95th percentile band with a
 * tick at the median.
 */
@Composable
private fun HourlyRangeChart(
    bars: List<HourlyHR.Bar>,
    window: HourlyWindow,
    selected: HourlyHR.Bar?,
    onSelect: (HourlyHR.Bar?) -> Unit,
) {
    val colors = Obs.colors
    val slots = remember(bars, window) { HourlyHR.slots(bars, window) }
    val byHour = remember(bars) { bars.associateBy { it.unix } }
    val lo = bars.minOfOrNull { it.low } ?: 40.0
    val hi = bars.maxOfOrNull { it.high } ?: 120.0
    val pad = max(hi - lo, 1.0) * 0.1
    val domainLo = lo - pad
    val domainHi = hi + pad
    val span = max(domainHi - domainLo, 1e-6)
    // A local midnight is a multiple of a day once the tz offset is removed (buckets are UTC).
    val midnightOffset = (((-Core.tzOffsetHours() * 3600) % 86_400) + 86_400) % 86_400

    Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(30.dp).fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
            Text("${domainHi.roundToInt()}", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
            Text("${((domainLo + domainHi) / 2).roundToInt()}", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
            Text("${domainLo.roundToInt()}", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics { contentDescription = "Hourly heart rate band, ${bars.size} hours with data" }
                .pointerInput(slots, byHour) {
                    fun pick(x: Float) {
                        val slotW = size.width / max(slots.size, 1).toFloat()
                        val i = (x / max(slotW, 0.001f)).toInt()
                        if (i in slots.indices) onSelect(byHour[slots[i]])
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(slots, byHour) {
                    detectDragGestures(onDragStart = { pos ->
                        val slotW = size.width / max(slots.size, 1).toFloat()
                        val i = (pos.x / max(slotW, 0.001f)).toInt()
                        if (i in slots.indices) onSelect(byHour[slots[i]])
                    }) { change, _ ->
                        val slotW = size.width / max(slots.size, 1).toFloat()
                        val i = (change.position.x / max(slotW, 0.001f)).toInt()
                        if (i in slots.indices) onSelect(byHour[slots[i]])
                    }
                }
        ) {
            fun y(v: Double) = (size.height * (1 - (v - domainLo) / span)).toFloat()
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val gy = size.height * fraction
                drawLine(colors.rule.copy(alpha = 0.4f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 0.5f)
            }
            val slotW = size.width / max(slots.size, 1).toFloat()
            // midnight rules, so a multi-day window reads as days
            slots.forEachIndexed { i, start ->
                if (Math.floorMod(start.roundToInt().toLong(), 86_400L) == midnightOffset) {
                    val x = slotW * i
                    drawLine(
                        colors.rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
                    )
                }
            }
            val barW = max(1.5f, slotW * 0.62f)
            slots.forEachIndexed { i, start ->
                val bar = byHour[start] ?: return@forEachIndexed
                val cx = slotW * (i + 0.5f)
                val isSelected = selected?.unix == start
                val tint = if (isSelected) colors.ink else colors.chart
                val top = y(bar.high)
                val bottom = y(bar.low)
                drawRoundRect(
                    tint.copy(alpha = if (isSelected) 0.55f else 0.32f),
                    topLeft = Offset(cx - barW / 2, top),
                    size = Size(barW, max(1.4f, bottom - top)),
                    cornerRadius = CornerRadius(minOf(2.5f, barW / 2)),
                )
                val my = y(bar.median)
                drawLine(tint, Offset(cx - barW / 2, my), Offset(cx + barW / 2, my), strokeWidth = if (isSelected) 2f else 1.4f)
            }
        }
    }
}

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun slotLabel(bar: HourlyHR.Bar): String = "${bar.ymd.takeLast(5)} ${"%02d:00".format(bar.hour)}"
