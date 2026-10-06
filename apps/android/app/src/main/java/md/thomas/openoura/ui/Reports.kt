package md.thomas.openoura.ui

import android.app.TimePickerDialog
import android.widget.Toast
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import md.thomas.openoura.ble.RingSync
import md.thomas.openoura.data.BedtimeOverrideStore
import md.thomas.openoura.data.DayExport
import md.thomas.openoura.store.DB
import md.thomas.openoura.data.NightRow
import md.thomas.openoura.data.Sleep
import md.thomas.openoura.data.SleepScore
import md.thomas.openoura.data.Summary
import md.thomas.openoura.data.WakingActivityTimeline
import md.thomas.openoura.data.clockLabel
import md.thomas.openoura.data.wakingActivityTimeline
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Full-page, research-grade sleep & activity reports — the Android counterpart to
// apps/ios/OuraApp/Reports.swift and the web dashboard's `sleepReport`/`activityReport`.
// The raw per-night signal series arrive from build_summary (NightRow.series); the
// hypnogram is the on-device SleepNet output (NightRow.stages, `full` flavor).

/** A big mono datum with a tiny uppercase caption — the readout atom. */
@Composable
fun Readout(value: String, caption: String, accent: Color = Obs.colors.ink, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, fontFamily = Obs.mono, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = accent)
        Text(
            caption.uppercase(),
            fontFamily = Obs.mono,
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            color = Obs.colors.ink2,
        )
    }
}

@Composable
private fun ReadoutRow(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((value, caption) in items) {
            Readout(value, caption, modifier = Modifier.weight(1f))
        }
    }
}

/** Sleep ⇄ Activity, the two halves of one day. Port of `DayReportView`. */
@Composable
fun DayReportScreen(
    s: Summary,
    day: String,
    startOnSleep: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSummaryChanged: () -> Unit = {},
) {
    val colors = Obs.colors
    var sleep by remember(day) { mutableStateOf(startOnSleep) }

    Column(modifier.fillMaxSize().background(colors.paper)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "‹ back",
                modifier = Modifier.clickable(onClick = onBack),
                fontFamily = Obs.mono,
                fontSize = 13.sp,
                color = colors.ink2,
            )
            Text(day, fontFamily = Obs.mono, fontSize = 13.sp, color = colors.muted)
            Spacer(Modifier.weight(1f))
            DayExportMenu(s, day, sleep)
            Segmented(
                options = listOf("Sleep", "Activity"),
                selectedIndex = if (sleep) 0 else 1,
                onSelect = { sleep = it == 0 },
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (sleep) {
                val night = s.nightForDay(day)
                if (night == null) {
                    item { EmptyNote("No sleep recorded for this morning.") }
                } else {
                    item { SleepReport(s, night, day, onSummaryChanged = onSummaryChanged) }
                }
            } else {
                item { ActivityReport(s, day) }
            }
        }
    }
}

@Composable
fun SleepReport(
    s: Summary,
    night: NightRow,
    day: String,
    modifier: Modifier = Modifier,
    onSummaryChanged: () -> Unit = {},
) {
    val colors = Obs.colors
    var editingBedtime by remember(night.id) { mutableStateOf(false) }
    // Full-resolution hypnogram (never the 120-cell preview); metrics return null for a
    // partially staged night rather than counting its unknown epochs as awake.
    val smoothed = remember(night) { night.hypnogram?.takeIf { it.size > 1 }?.let { Sleep.smooth(it, 5) } }
    val metrics = remember(night, smoothed) {
        smoothed?.let { Sleep.metrics(it, night.durationS) }
    }
    val autonomic = remember(night, smoothed) {
        smoothed?.let { Sleep.autonomic(night, it) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ReadoutRow(
            listOf(
                fmtHours(night.inBedH) to "in bed",
                fmtMinutes(metrics?.asleepMin) to "asleep",
                (night.efficiency?.let { "${it.roundToInt()}%" } ?: "—") to "efficiency",
                (night.start ?: "—") to "bedtime",
            )
        )
        Text(
            "${night.start ?: "—"}–${night.end ?: "—"}  ·  " +
                if (night.isBedtimeManual) "bedtime edited" else "adjust bedtime",
            modifier = Modifier.clickable { editingBedtime = !editingBedtime },
            fontFamily = Obs.mono,
            fontSize = 11.sp,
            color = if (editingBedtime) colors.chart else colors.link,
        )
        if (editingBedtime) {
            BedtimeEditor(night, nightKey = night.date ?: day, onDone = { changed ->
                editingBedtime = false
                if (changed) onSummaryChanged()
            })
        }

        night.sleepScore?.let { score ->
            Rule("sleep score")
            SleepScoreCard(score, night.breathRate)
        }

        if (smoothed != null) {
            if (!night.stagingComplete) {
                Text(
                    "Incomplete sleep analysis. Gaps mean no data, not awake time; sleep metrics are withheld.",
                    fontFamily = Obs.prose,
                    fontSize = 13.sp,
                    color = colors.bad,
                )
            }
            Rule("polysomnograph")
            Polysomnograph(night)
            StageLegend()
            StageBar(night.deepPct, night.lightPct, night.remPct, night.wakePct)
            ReadoutRow(
                listOf(
                    pct(night.deepPct) to "deep",
                    pct(night.lightPct) to "light",
                    pct(night.remPct) to "rem",
                    pct(night.wakePct) to "awake",
                )
            )
        } else {
            // No hypnogram for this night (neither the ring's nor the on-device model's) —
            // say so rather than rendering an empty chart frame.
            EmptyNote(
                "No sleep stages for this night. The raw signals below still come from the " +
                    "shared core."
            )
            if (night.hasSignals) Polysomnograph(night)
        }

        metrics?.let { m ->
            Rule("clinical")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ObsStat("sleep onset", fmtMinutes(m.solMin))
                ObsStat("rem latency", fmtMinutes(m.remLatencyMin))
                ObsStat("waso", fmtMinutes(m.wasoMin))
                ObsStat("awakenings", "${m.awakenings}")
                ObsStat("cycles", "${m.cycles}")
                ObsStat("fragmentation", "%.1f /h".format(m.fragIndex))
            }
        }

        if (autonomic?.any == true) {
            Rule("autonomic recovery")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ObsStat("hrv · deep", fmt(autonomic.hrvDeep) + " ms")
                ObsStat("hrv · light", fmt(autonomic.hrvLight) + " ms")
                ObsStat("hrv · rem", fmt(autonomic.hrvRem) + " ms")
                ObsStat("hr · deep", fmt(autonomic.hrDeep) + " bpm")
                ObsStat("hr · light", fmt(autonomic.hrLight) + " bpm")
                ObsStat("hr · rem", fmt(autonomic.hrRem) + " bpm")
            }
            Text(
                "Deep-sleep HRV is the recovery-relevant number. A single overnight HRV " +
                    "trend is deliberately not shown: nocturnal HRV is stage-driven, so a " +
                    "slope tracks stage order rather than recovery.",
                fontFamily = Obs.prose,
                fontSize = 12.sp,
                color = colors.muted,
            )
        }

        metrics?.let { m ->
            Rule("interpretation")
            for (line in sleepInterpretation(night, m)) {
                Text(line, fontFamily = Obs.prose, fontSize = 14.sp, color = colors.ink2)
            }
        }

        s.sleepDebt?.takeIf { it.valid }?.let { debt ->
            Text(
                "Sleep debt over the last ${debt.windowDays} days: ${fmtMinutes(debt.debtMin)} " +
                    "against a nightly need of ${"%.1f".format(debt.needH)} h.",
                fontFamily = Obs.prose,
                fontSize = 13.sp,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun StageLegend() {
    val colors = Obs.colors
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((code, name) in listOf(1 to "deep", 2 to "light", 3 to "rem", 4 to "awake")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(10.dp).height(3.dp).background(colors.stage(code)))
                Text(name, fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink2)
            }
        }
    }
}

@Composable
fun ActivityReport(s: Summary, day: String, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    val daily = s.activityDaily[day]
    val timeline = remember(s, day) { s.wakingActivityTimeline(day) }
    val sessions = s.workoutsOn(day)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ReadoutRow(
            listOf(
                fmtSteps(daily?.steps) to "steps",
                fmt(daily?.activeKcal) to "active kcal",
                fmt(daily?.totalKcal) to "total kcal",
                fmtKm(daily?.distanceM) to "distance",
            )
        )

        Rule("waking day")
        MetProfile(timeline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeline.startCaption, fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink2)
            Text(timeline.endCaption, fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink2)
        }

        val points = timeline.points.map { it.met }
        if (points.isNotEmpty()) {
            // 3 MET above rest is the conventional moderate-intensity threshold; 1.5 is
            // the light/sedentary boundary. Bucket width comes from the profile's own
            // resolution (96 × 15 min in the shared summary).
            val bucketMin = if (points.size > 1) 24.0 * 60 / (s.activityProfile[day]?.size ?: 96) else 15.0
            ReadoutRow(
                listOf(
                    "${(points.count { it >= 3.0 } * bucketMin).roundToInt()}m" to "active",
                    "${(points.count { it in 1.5..3.0 } * bucketMin).roundToInt()}m" to "lightly active",
                    "%.1f".format(points.max()) to "peak met",
                )
            )
        }

        Rule("detected sessions")
        if (sessions.isEmpty()) {
            EmptyNote(
                "No sessions detected for this day. Automatic activity detection needs the " +
                    "on-device model build."
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (w in sessions) SessionRow(w.label, w.durationMin, w.startHM)
            }
        }
    }
}

/**
 * The waking-day MET curve. Unlike the calendar-day ridge on the home card, this one
 * runs wake → bed and crosses midnight when it needs to.
 */
@Composable
fun MetProfile(timeline: WakingActivityTimeline, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val pts = timeline.points
        if (pts.size < 2) return@Canvas
        val span = max(timeline.endHour - timeline.startHour, 1e-6)
        val peak = max(pts.maxOf { it.met }, 1.0)

        drawHGrid(colors.rule)

        fun pt(i: Int) = Offset(
            (size.width * ((pts[i].hour - timeline.startHour) / span)).toFloat().coerceIn(0f, size.width),
            (size.height * (1 - min(1.0, pts[i].met / peak))).toFloat(),
        )

        val line = Path().apply {
            moveTo(pt(0).x, pt(0).y)
            for (i in 1 until pts.size) lineTo(pt(i).x, pt(i).y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(pt(pts.size - 1).x, size.height)
            lineTo(pt(0).x, size.height)
            close()
        }
        drawPath(area, colors.chart.copy(alpha = 0.14f))
        drawPath(line, colors.chart.copy(alpha = 0.9f), style = Stroke(1.3f, join = StrokeJoin.Round))
    }
}

/** A small segmented control in the Quiet Ink idiom (Material's is far too loud). */
@Composable
fun Segmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Row(
        modifier.background(colors.paper, RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Text(
                label,
                modifier = Modifier
                    .clickable { onSelect(i) }
                    .background(
                        if (selected) colors.ink.copy(alpha = 0.08f) else Color.Transparent,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                fontFamily = Obs.mono,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) colors.ink else colors.muted,
            )
        }
    }
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth(),
        fontFamily = Obs.prose,
        fontSize = 13.sp,
        color = Obs.colors.muted,
    )
}

private fun pct(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"

internal fun fmtSteps(steps: Double?): String {
    if (steps == null || !steps.isFinite()) return "—"
    return if (steps >= 10_000) "%.1fk".format(steps / 1000) else "${steps.roundToInt()}"
}

internal fun fmtKm(metres: Double?): String {
    if (metres == null || !metres.isFinite()) return "—"
    return "%.1f km".format(metres / 1000)
}

/**
 * Plain-language reading of one night. Deliberately conservative: it describes what the
 * hypnogram shows rather than scoring it, matching the tone of the web report.
 */
internal fun sleepInterpretation(night: NightRow, m: md.thomas.openoura.data.SleepMetrics): List<String> {
    val out = ArrayList<String>()
    out.add(
        "You were in bed for ${fmtHours(night.inBedH)} and asleep for ${fmtMinutes(m.asleepMin)}, " +
            "falling asleep in ${fmtMinutes(m.solMin)}."
    )
    m.remLatencyMin?.let {
        out.add("First REM arrived ${fmtMinutes(it)} after sleep onset, across ${m.cycles} cycle(s).")
    }
    out.add(
        "You were awake for ${fmtMinutes(m.wasoMin)} after first falling asleep, " +
            "in ${m.awakenings} awakening(s)."
    )
    m.deepFirstHalfPct?.let {
        val where = if (it >= 60) "front-loaded, as it usually is" else "spread through the night"
        out.add("Deep sleep was $where (${it.roundToInt()}% of it in the first half).")
    }
    m.remFirstHalfPct?.let {
        if (it <= 40) out.add("REM was concentrated in the second half, the normal pattern.")
    }
    return out
}

/**
 * The literature-based sleep score: total, then each component's bar (width = score,
 * opacity = how much it counted) with its source paper on demand. Mirrors iOS
 * `SleepScoreCard` (Reports.swift); computed in `oura-summary/src/sleep_score.rs`.
 */
@Composable
fun SleepScoreCard(score: SleepScore, breathRate: Double?, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tint = when {
        score.score >= 85 -> colors.good
        score.score >= 70 -> colors.chart
        else -> colors.bad
    }
    Column(modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${score.score.toInt()}", fontFamily = Obs.mono, fontSize = 38.sp, fontWeight = FontWeight.Medium, color = tint)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("/ 100", fontFamily = Obs.mono, fontSize = 12.sp, color = colors.ink2)
                Text(score.basis ?: "published norms", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
            }
            breathRate?.let { br ->
                Column(horizontalAlignment = Alignment.End) {
                    Text("%.1f".format(br), fontFamily = Obs.mono, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text("br/min", fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted)
                }
            }
        }
        for (c in score.components) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(c.label, fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2, modifier = Modifier.weight(1f))
                    Text("${c.score.toInt()}", fontFamily = Obs.mono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(colors.rule)) {
                    Box(
                        Modifier
                            .fillMaxWidth((c.score / 100).toFloat().coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(colors.chart.copy(alpha = (0.35 + 0.65 * minOf(1.0, c.weight * 3)).toFloat()))
                    )
                }
                if (expanded) {
                    c.source?.let { Text(it, fontFamily = Obs.mono, fontSize = 9.sp, color = colors.muted) }
                }
            }
        }
        Text(
            if (expanded) "hide sources" else "show sources",
            fontFamily = Obs.mono,
            fontSize = 10.sp,
            color = colors.ink2,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

/**
 * Inline bedtime / wake-up correction. Writes `bedtime_overrides.json` (see
 * [BedtimeOverrideStore]); the reload that follows rebuilds the night in the core and, in
 * the full build, re-stages it (its staging cache is keyed by the new window). Mirrors iOS
 * `SleepReport.bedtimeEditor`.
 */
@Composable
private fun BedtimeEditor(night: NightRow, nightKey: String, onDone: (changed: Boolean) -> Unit) {
    val colors = Obs.colors
    val context = LocalContext.current
    var start by remember(night.id) { mutableStateOf(night.start ?: "23:00") }
    var end by remember(night.id) { mutableStateOf(night.end ?: "07:00") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun pick(current: String, onPicked: (String) -> Unit) {
        val m = BedtimeOverrideStore.hmMinutes(current)
        TimePickerDialog(context, { _, h, min -> onPicked("%02d:%02d".format(h, min)) }, m / 60, m % 60, true).show()
    }
    fun persist(action: (List<java.io.File>) -> Unit) {
        saving = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { action(overrideFiles(context)) }
            }
            saving = false
            result.onSuccess { onDone(true) }.onFailure { error = "Couldn't save: ${it.message ?: it}" }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.paper.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for ((label, value, set) in listOf(
                Triple("BEDTIME", start) { v: String -> start = v },
                Triple("WAKE-UP", end) { v: String -> end = v },
            )) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontFamily = Obs.mono, fontSize = 9.sp, letterSpacing = 1.1.sp, color = colors.ink2)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        value,
                        modifier = Modifier
                            .clickable(enabled = !saving) { pick(value, set) }
                            .background(colors.rule.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        fontFamily = Obs.mono,
                        fontSize = 14.sp,
                        color = colors.ink,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (saving) "saving…" else "apply",
                modifier = Modifier
                    .clickable(enabled = !saving) {
                        persist { files -> BedtimeOverrideStore.set(files, nightKey, night, start, end) }
                    }
                    .background(colors.ink, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                fontFamily = Obs.mono, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.paper,
            )
            if (night.isBedtimeManual || night.rawStart != null) {
                Text(
                    "reset" + (night.rawStart?.let { " ($it–${night.rawEnd ?: ""})" } ?: ""),
                    modifier = Modifier.clickable(enabled = !saving) {
                        persist { files -> BedtimeOverrideStore.clear(files, nightKey, night) }
                    },
                    fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2,
                )
            }
            Text(
                "cancel",
                modifier = Modifier.clickable(enabled = !saving) { onDone(false) },
                fontFamily = Obs.mono, fontSize = 11.sp, color = colors.muted,
            )
        }
        error?.let { Text(it, fontFamily = Obs.mono, fontSize = 11.sp, color = colors.bad) }
    }
}

/** The override file beside the DB the core reads, and beside the writable copy (as iOS). */
private fun overrideFiles(context: android.content.Context): List<java.io.File> = listOf(
    java.io.File(java.io.File(DB.readPath(context)).parentFile, BedtimeOverrideStore.FILE),
    java.io.File(DB.file(context).parentFile, BedtimeOverrideStore.FILE),
)

/**
 * "export" → copy or share the open tab as JSON (`DayExport`). Same sections and keys as
 * iOS `DayReportView`'s export menu and the web's "Export JSON".
 */
@Composable
private fun DayExportMenu(s: Summary, day: String, sleep: Boolean) {
    val colors = Obs.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    fun payload(): DayExport? = DayExport.build(s, day, sleep, appVersion(context)).takeUnless { it.isEmpty }
    Box {
        Text(
            "export",
            modifier = Modifier.clickable { open = true },
            fontFamily = Obs.mono,
            fontSize = 12.sp,
            color = colors.link,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Copy JSON") }, onClick = {
                open = false
                payload()?.let { export ->
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(export.fileName, export.json()))
                } ?: Toast.makeText(context, "Nothing to export for this day.", Toast.LENGTH_SHORT).show()
            })
            DropdownMenuItem(text = { Text("Share file") }, onClick = {
                open = false
                val export = payload()
                if (export == null) {
                    Toast.makeText(context, "Nothing to export for this day.", Toast.LENGTH_SHORT).show()
                    return@DropdownMenuItem
                }
                val dir = java.io.File(context.cacheDir, RingSync.EXPORT_DIR).apply { mkdirs() }
                val file = java.io.File(dir, export.fileName).apply { writeText(export.json()) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, export.fileName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Export ${export.kind}"))
            })
        }
    }
}

private fun appVersion(context: android.content.Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
} catch (_: Exception) {
    "?"
}
