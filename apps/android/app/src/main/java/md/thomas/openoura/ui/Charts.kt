package md.thomas.openoura.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.thomas.openoura.core.Core
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Hand-drawn charts, ported from apps/ios/OuraApp/Components.swift. Compose `Canvas`
// stands in for SwiftUI `Canvas`; no charting library, matching the iOS constraint that
// keeps the look bespoke and the app dependency-free offline.

/**
 * Fritsch-Carlson-style monotone cubic interpolation. Sign changes flatten the tangent,
 * and the slope limiter keeps each Bézier segment inside its data interval — smoother
 * than a polyline, but it never invents a peak the data does not contain.
 */
fun monotonePath(points: List<Offset>): Path {
    val path = Path()
    if (points.size < 2) {
        if (points.size == 1) path.moveTo(points[0].x, points[0].y)
        return path
    }

    val deltas = ArrayList<Float>(points.size - 1)
    for (i in 0 until points.size - 1) {
        val dx = points[i + 1].x - points[i].x
        if (abs(dx) <= 1e-6f) {
            // Degenerate spacing: fall back to a polyline rather than divide by ~0.
            path.moveTo(points[0].x, points[0].y)
            for (p in points.drop(1)) path.lineTo(p.x, p.y)
            return path
        }
        deltas.add((points[i + 1].y - points[i].y) / dx)
    }

    val tangents = FloatArray(points.size)
    tangents[0] = deltas.first()
    tangents[points.size - 1] = deltas.last()
    if (points.size > 2) {
        for (i in 1 until points.size - 1) {
            tangents[i] =
                if (deltas[i - 1] * deltas[i] <= 0f) 0f else (deltas[i - 1] + deltas[i]) / 2f
        }
    }
    for (i in deltas.indices) {
        if (deltas[i] == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
            continue
        }
        val a = tangents[i] / deltas[i]
        val b = tangents[i + 1] / deltas[i]
        val magnitude = a * a + b * b
        if (magnitude > 9f) {
            val scale = 3f / sqrt(magnitude)
            tangents[i] = scale * a * deltas[i]
            tangents[i + 1] = scale * b * deltas[i]
        }
    }

    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val width = points[i + 1].x - points[i].x
        path.cubicTo(
            points[i].x + width / 3f, points[i].y + tangents[i] * width / 3f,
            points[i + 1].x - width / 3f, points[i + 1].y - tangents[i + 1] * width / 3f,
            points[i + 1].x, points[i + 1].y,
        )
    }
    return path
}

@Composable
fun Sparkline(
    series: List<Double>,
    accent: Color = Obs.colors.chart,
    baseline: Double? = null,
    trace: Color = Obs.colors.rule,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxWidth().height(30.dp)) {
        val values = series.filter { it.isFinite() }
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val observedSpan = hi - lo
        val padding = max(observedSpan, 1e-6) * 0.12
        val domainLo = lo - padding
        val domainSpan = max(observedSpan + padding * 2, 1e-6)
        val inset = 2.5f
        val chartHeight = max(1f, size.height - inset * 2)
        val points = values.mapIndexed { index, value ->
            val normalized = if (observedSpan <= 1e-6) 0.5 else (value - domainLo) / domainSpan
            Offset(
                inset + (size.width - inset * 2) * index / (values.size - 1).toFloat(),
                inset + chartHeight * (1f - normalized.toFloat()),
            )
        }

        // A reference line is useful when the summary has a real baseline. Keep it inside
        // the observed domain rather than stretching the chart to manufacture visual
        // movement around an off-screen reference.
        if (baseline != null && baseline.isFinite() && baseline >= lo && baseline <= hi) {
            val y = inset + chartHeight * (1f - ((baseline - domainLo) / domainSpan).toFloat())
            drawLine(
                color = trace.copy(alpha = 0.55f),
                start = Offset(inset, y),
                end = Offset(size.width - inset, y),
                strokeWidth = 0.65f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5f, 3f)),
            )
        }

        val path = monotonePath(points)
        drawPath(path, accent.copy(alpha = 0.10f), style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, accent.copy(alpha = 0.86f), style = Stroke(1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Real samples remain visible as quiet solid marks; the smooth path is only
        // interpolation between them. The latest point is slightly stronger so the
        // direction of time stays clear without a separate axis label.
        points.forEachIndexed { index, point ->
            val isLatest = index == points.size - 1
            drawCircle(
                color = accent.copy(alpha = if (isLatest) 0.90f else 0.52f),
                radius = if (isLatest) 2.2f else 1.7f,
                center = point,
            )
        }
    }
}

/**
 * Sleep-stage hypnogram: one ink hue per stage, height encoding depth (deep full → wake
 * short) so the night reads without a rainbow.
 */
@Composable
fun Hypnogram(stages: List<Int>, height: Dp = 40.dp, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (stages.isEmpty()) return@Canvas
        val w = size.width / stages.size
        stages.forEachIndexed { i, s ->
            // 0 = not staged: leave a gap rather than drawing it as awake (same as iOS).
            if (s !in 1..4) return@forEachIndexed
            val frac = when (s) {
                1 -> 1.0f
                2 -> 0.72f
                3 -> 0.48f
                else -> 0.28f
            }
            val h = size.height * frac
            drawRect(
                color = colors.stage(s),
                topLeft = Offset(i * w, size.height - h),
                size = Size(w + 0.4f, h),
            )
        }
    }
}

/**
 * Continuous movement ridge from the 96 × 15-min MET-above-rest buckets — the web
 * actogram's ridge, model-free (computed from raw MET). One day's profile.
 */
@Composable
fun MovementRidge(profile: List<Double>, height: Dp = 44.dp, modifier: Modifier = Modifier) {
    val chart = Obs.colors.chart
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (profile.size < 2) return@Canvas
        val peak = max(profile.max(), 0.5)
        val n = profile.size
        fun pt(i: Int) = Offset(
            size.width * i / (n - 1).toFloat(),
            size.height * (1f - min(1.0, profile[i] / peak).toFloat()),
        )

        val area = Path().apply {
            moveTo(0f, size.height)
            for (i in 0 until n) lineTo(pt(i).x, pt(i).y)
            lineTo(size.width, size.height)
            close()
        }
        drawPath(area, chart.copy(alpha = 0.14f))

        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until n) lineTo(pt(i).x, pt(i).y)
        }
        drawPath(line, chart.copy(alpha = 0.85f), style = Stroke(1.2f, join = StrokeJoin.Round))
    }
}

/** Proportional deep/light/REM/wake band. */
@Composable
fun StageBar(
    deep: Double?,
    light: Double?,
    rem: Double?,
    wake: Double?,
    height: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val colors = Obs.colors
    val parts = listOf(1 to deep, 2 to light, 3 to rem, 4 to wake)
        .mapNotNull { (code, pct) -> pct?.takeIf { it.isFinite() && it > 0 }?.let { code to it } }
    val total = parts.sumOf { it.second }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (total <= 0) {
            drawRect(colors.rule, size = size)
            return@Canvas
        }
        var x = 0f
        for ((code, pct) in parts) {
            val w = (size.width * (pct / total)).toFloat()
            drawRect(colors.stage(code), topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

/**
 * A value against its normal range — the Symptom Radar biomarker track. The band is the
 * reference interval; the mark is where you actually are.
 */
@Composable
fun RangeTrack(
    value: Double,
    lower: Double,
    upper: Double,
    outOfRange: Boolean,
    height: Dp = 18.dp,
    modifier: Modifier = Modifier,
) {
    val colors = Obs.colors
    Canvas(modifier.fillMaxWidth().height(height)) {
        val span = max(upper - lower, 1e-6)
        // Show a full range-width of headroom either side, so an out-of-range value has
        // somewhere to sit instead of being clamped onto the band's edge.
        val domainLo = lower - span
        val domainSpan = span * 3
        fun x(v: Double) = (size.width * ((v - domainLo) / domainSpan)).toFloat().coerceIn(0f, size.width)

        val midY = size.height / 2
        drawLine(colors.rule, Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)
        drawRect(
            color = colors.chart.copy(alpha = 0.18f),
            topLeft = Offset(x(lower), midY - size.height * 0.3f),
            size = Size(x(upper) - x(lower), size.height * 0.6f),
        )
        drawCircle(
            color = if (outOfRange) colors.bad else colors.chart,
            radius = size.height * 0.22f,
            center = Offset(x(value), midY),
        )
    }
}

/** Shared axis/grid helper for the full-page report lanes. */
internal fun DrawScope.drawHGrid(color: Color, fractions: List<Float> = listOf(0f, 0.5f, 1f)) {
    for (f in fractions) {
        val y = size.height * f
        drawLine(color.copy(alpha = 0.4f), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
    }
}

/** One run of the same direction; charging stretches are drawn apart from draining runs. */
internal data class BatterySeg(val up: Boolean, val from: Int, val to: Int)

/**
 * Pure `batteryChart` maths, kept off the canvas so the JVM tests can pin it: the data
 * floor (a full battery is the top of the scale, the bottom follows the readings) and
 * the runs of the same direction, so charging can be drawn differently.
 */
internal fun batteryAxis(pts: List<Pair<Double, Double>>): Triple<Double, Double, List<BatterySeg>> {
    val lo = max(0.0, floor(pts.minOf { it.second } / 10) * 10 - 5.0)
    val segs = ArrayList<BatterySeg>()
    for (i in 1 until pts.size) {
        val up = pts[i].second > pts[i - 1].second
        if (segs.isEmpty() || segs.last().up != up) segs += BatterySeg(up, i - 1, i)
        else segs[segs.size - 1] = BatterySeg(up, segs.last().from, i)
    }
    return Triple(pts.first().first, lo, segs)
}

/**
 * The ring's own battery readings (device.battery_history, [unix_s, percent]) over the
 * days they span. Port of the web dashboard's `batteryChart` (app.js). Stretches where
 * the level rises are it charging, which the readings show on their own — the event's
 * two flag bytes aren't decoded, so nothing here depends on guessing what they mean.
 *
 * A full battery is the top of the scale, but the bottom follows the data: a ring that
 * has only been 80–100% draws as a flat line against a 0–100 axis instead of inventing
 * a swing. Both ends are labelled so a zoomed axis can't read as a bigger swing than it is.
 */
@Composable
fun BatteryHistoryChart(history: List<List<Double>>, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    val pts = history.mapNotNull { p ->
        if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) null else p[0] to p[1]
    }
    if (pts.size < 2) return
    val (t0, lo, segs) = batteryAxis(pts)
    val t1 = pts.last().first
    val span = max(t1 - t0, 1.0)
    val hi = 100.0

    var cursorIndex by remember(pts) { mutableStateOf<Int?>(null) }
    fun nearest(x: Float, w: Float): Int? =
        pts.indices.minByOrNull { abs(pts[it].first - (t0 + x / max(w, 1f) * span)) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Battery",
                fontFamily = Obs.mono,
                fontSize = 10.sp,
                letterSpacing = 1.2.sp,
                color = colors.ink2,
            )
            val c = cursorIndex
            Text(
                if (c != null) "${battStamp(pts[c].first)} · ${pts[c].second.roundToInt()}%"
                else "",
                fontFamily = Obs.mono,
                fontSize = 11.sp,
                color = colors.muted,
            )
        }

        Box(Modifier.fillMaxWidth().height(44.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(end = 40.dp)
                    .pointerInput(pts) {
                        detectTapGestures(onTap = { cursorIndex = nearest(it.x, size.width.toFloat()) })
                    }
                    .pointerInput(pts) {
                        detectDragGestures(
                            onDragStart = { cursorIndex = nearest(it.x, size.width.toFloat()) },
                            onDrag = { change, _ -> cursorIndex = nearest(change.position.x, size.width.toFloat()) },
                            onDragEnd = { cursorIndex = null },
                            onDragCancel = { cursorIndex = null },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                fun x(t: Double) = ((t - t0) / span * w).toFloat()
                fun y(p: Double) = h - ((p - lo) / (hi - lo) * h).toFloat()

                var v = ceil((lo + 1) / 10) * 10
                while (v < hi) {
                    drawLine(colors.rule.copy(alpha = 0.55f), Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 0.5f)
                    v += 10
                }
                for (seg in segs) {
                    val path = Path().apply {
                        for (i in seg.from..seg.to) {
                            val p = pts[i]
                            if (i == seg.from) moveTo(x(p.first), y(p.second))
                            else lineTo(x(p.first), y(p.second))
                        }
                    }
                    if (seg.up) {
                        drawPath(path, colors.good, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else {
                        drawPath(
                            path,
                            colors.chart.copy(alpha = 0.85f),
                            style = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                cursorIndex?.let { i ->
                    drawLine(colors.good, Offset(x(pts[i].first), 0f), Offset(x(pts[i].first), h), strokeWidth = 1f)
                }
            }

            Text(
                "${hi.toInt()}%",
                Modifier.align(Alignment.TopEnd),
                fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted,
            )
            Text(
                "${lo.roundToInt()}%",
                Modifier.align(Alignment.BottomEnd),
                fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted,
            )
        }

        val days = max(1, ((t1 - t0) / 86_400.0).roundToInt())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${battDay(t0)} → ${battStamp(t1)}",
                fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted,
            )
            Text(
                "$days day${if (days == 1) "" else "s"}",
                fontFamily = Obs.mono, fontSize = 10.sp, color = colors.muted,
            )
        }
    }
}

// Labels follow the summary's fixed tz offset (the web's CURRENT_TZ), not the system
// zone: a reading mid-DST gets labelled in the same frame the report otherwise uses.
private fun battZone(): ZoneOffset = ZoneOffset.ofTotalSeconds(Core.tzOffsetHours().toInt() * 3600)

private fun battFormat(pattern: String, unix: Double): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(
        Instant.ofEpochSecond(unix.toLong()).atOffset(battZone()),
    )

/** "Thu · Mar 2" — the first reading's date, web `dayTitle(ymdOf(t0))`. */
internal fun battDay(unix: Double): String = battFormat("EEE · MMM d", unix)

/** "03/06 20:31" — web `hhmmOf`. */
internal fun battStamp(unix: Double): String = battFormat("MM/dd HH:mm", unix)
