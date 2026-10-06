package md.thomas.openoura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import md.thomas.openoura.data.Cardio
import md.thomas.openoura.data.Fitness
import md.thomas.openoura.data.IllnessBiomarker
import md.thomas.openoura.data.IllnessResult
import md.thomas.openoura.data.rnd
import md.thomas.openoura.data.SleepDebtSummary
import md.thomas.openoura.data.Summary
import kotlin.math.abs
import kotlin.math.roundToInt

// The home-screen cards: one day = last night + that day's activity, then sleep debt,
// Symptom Radar and the cardiovascular readout. Ports of `TodayCard`, `SleepDebtCard`,
// `IllnessCard` and the cardio section in apps/ios/OuraApp/OuraApp.swift + Reports.swift.

/**
 * One card = last night's sleep + that day's activity, each half tappable. The day unit
 * is defined by wake date — see "The day is one unit" in docs/clients.md.
 */
@Composable
fun TodayCard(
    s: Summary,
    day: String,
    onSleep: () -> Unit,
    onActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Obs.colors
    val night = s.nightForDay(day)
    val daily = s.activityDaily[day]
    val profile = s.activityProfile[day].orEmpty()
    val sessions = s.workoutsOn(day)

    Column(modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ObsTag("today")
            Text(day, fontFamily = Obs.mono, fontSize = 11.sp, color = colors.muted)
        }

        // ── night half ──
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onSleep),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    fmtHours(night?.inBedH),
                    fontFamily = Obs.mono,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                Text("in bed", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2)
                Spacer(Modifier.weight(1f))
                if (night != null) {
                    Text(
                        "${night.start ?: "—"} → ${night.end ?: "—"}",
                        fontFamily = Obs.mono,
                        fontSize = 12.sp,
                        color = colors.ink2,
                    )
                }
            }
            val stages = night?.hypnogram
            if (stages != null && stages.size > 1) {
                Hypnogram(stages, height = 34.dp)
            } else if (night?.efficiency != null) {
                ObsStat("efficiency", "${night.efficiency!!.roundToInt()}%")
            } else if (night == null) {
                EmptyNote("No sleep recorded for this morning.")
            }
        }

        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.rule))

        // ── activity half ──
        Column(
            Modifier.fillMaxWidth().clickable(onClick = onActivity),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    fmtSteps(daily?.steps),
                    fontFamily = Obs.mono,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                )
                Spacer(Modifier.width(8.dp))
                Text("steps", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.ink2)
                Spacer(Modifier.weight(1f))
                Text(
                    "${fmt(daily?.activeKcal)} kcal",
                    fontFamily = Obs.mono,
                    fontSize = 12.sp,
                    color = colors.ink2,
                )
            }
            if (profile.size > 1) MovementRidge(profile, height = 40.dp)
            for (w in sessions.take(2)) SessionRow(w.label, w.durationMin, w.startHM)
        }
    }
}

@Composable
fun SleepDebtCard(debt: SleepDebtSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Column(
        modifier.fillMaxWidth().obsCard().clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ObsTag("sleep debt")
            Text(
                "${debt.validDays}/${debt.windowDays} days",
                fontFamily = Obs.mono,
                fontSize = 10.sp,
                color = colors.muted,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (debt.valid) fmtMinutes(debt.debtMin) else "—",
                fontFamily = Obs.mono,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                color = colors.debt(debt.state),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "need ${"%.1f".format(debt.needH)} h",
                fontFamily = Obs.mono,
                fontSize = 11.sp,
                color = colors.ink2,
            )
        }
        Text(
            if (debt.valid) debtStateCopy(debt.state)
            else "Not enough staged nights yet — at least five of the last 14 days are needed.",
            fontFamily = Obs.prose,
            fontSize = 13.sp,
            color = colors.ink2,
        )
    }
}

/**
 * Symptom Radar — overnight biometrics against your own baseline. Shows the on-device
 * model's result when it ran, else the shared core's model-free comparison
 * (`Summary.symptomRadar`). Status + explanation first; "View details" reveals the
 * measurements with their personal ranges. Mirrors iOS `IllnessCard` (Reports.swift).
 */
@Composable
fun IllnessCard(illness: IllnessResult, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    var showDetails by rememberSaveable(illness.date, illness.status) { mutableStateOf(false) }
    val tint = when (illness.trafficLight) {
        "NO_SIGNS" -> colors.good
        "MINOR_SIGNS" -> colors.bad
        "MAJOR_SIGNS" -> colors.alert
        else -> colors.muted
    }
    Column(modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Rule("symptom radar", Modifier.weight(1f))
            if (illness.date.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(illness.date, fontFamily = Obs.mono, fontSize = 10.sp, color = colors.ink2)
            }
        }
        Text(
            if (illness.available) illnessStatus(illness) else "Getting to know you",
            fontFamily = Obs.mono,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (illness.available) tint else colors.ink2,
        )
        Text(illnessCopy(illness), fontFamily = Obs.prose, fontSize = 14.sp, color = colors.ink2)
        if (!illness.available) return@Column

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clickable { showDetails = !showDetails }
                .semantics { stateDescription = if (showDetails) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showDetails) "Hide details" else "View details",
                fontFamily = Obs.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(if (showDetails) "▴" else "▾", fontSize = 12.sp, color = colors.ink)
        }
        if (!showDetails) return@Column

        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Text("Overnight measurements", fontFamily = Obs.prose, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.ink)
        for (type in BIOMARKER_ORDER) {
            illness.biomarkers.firstOrNull { it.type == type }?.let { BiomarkerRow(it) }
        }
        if (illness.biomarkers.isEmpty()) {
            Text("Measurement details aren't available for this result.", fontFamily = Obs.prose, fontSize = 13.sp, color = colors.muted)
        }
        Text("Based on ${illness.daysWithData} nights in the past 30 days", fontFamily = Obs.prose, fontSize = 12.sp, color = colors.muted)
    }
}

private val BIOMARKER_ORDER = listOf("AverageBreath", "LowestHeartRate", "AverageHrv", "TemperatureDeviation")

private fun biomarkerUnit(type: String): String = when (type) {
    "AverageBreath" -> "br/min"
    "LowestHeartRate" -> "bpm"
    "AverageHrv" -> "ms"
    "TemperatureDeviation" -> "°C"
    else -> ""
}

@Composable
private fun BiomarkerRow(b: IllnessBiomarker) {
    val colors = Obs.colors
    val unit = biomarkerUnit(b.type)
    fun fmt(v: Double): String = when {
        !v.isFinite() -> "–"
        unit == "°C" -> "%.1f".format(v)
        v == rnd(v) -> "%.0f".format(v)
        else -> "%.1f".format(v)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(biomarkerName(b.type), fontFamily = Obs.prose, fontSize = 14.sp, color = colors.ink2, modifier = Modifier.weight(1f))
            Text(
                "${fmt(b.value)} $unit",
                fontFamily = Obs.mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (b.indicatesSymptoms) colors.alert else colors.ink,
            )
        }
        if (b.indicatesSymptoms) {
            Text(
                when (b.reason) {
                    "ELEVATED" -> "Above usual"
                    "DECREASED" -> "Below usual"
                    else -> "Outside usual range"
                },
                fontFamily = Obs.prose, fontSize = 12.sp, color = colors.alert,
            )
        }
        Text(
            if (b.lower.isFinite() && b.upper.isFinite() && b.lower <= b.upper)
                "Personal range: ${fmt(b.lower)} to ${fmt(b.upper)} $unit"
            else "Personal range unavailable",
            fontFamily = Obs.prose, fontSize = 12.sp, color = colors.muted,
        )
        RangeTrack(b.value, b.lower, b.upper, b.indicatesSymptoms)
    }
}

internal fun illnessStatus(illness: IllnessResult): String = when (illness.trafficLight) {
    "NO_SIGNS" -> "No signs"
    "MINOR_SIGNS" -> "Minor signs"
    "MAJOR_SIGNS" -> "Major signs"
    else -> "No result yet"
}

@Composable
fun CardioCard(cardio: Cardio?, fitness: Fitness?, modifier: Modifier = Modifier) {
    val colors = Obs.colors
    Column(modifier.fillMaxWidth().obsCard(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Rule("cardiovascular")
        if (cardio?.vascularAge == null) {
            EmptyNote("Cardiovascular age needs the on-device model build.")
        } else {
            val delta = cardio.chronologicalAge?.let { cardio.vascularAge!! - it }
            ObsStat("vascular age", "%.1f".format(cardio.vascularAge), accent = colors.ink)
            if (delta != null) {
                val years = "%.1f".format(abs(delta))
                ObsStat(
                    "vs your age",
                    if (delta <= 0) "$years yr younger" else "$years yr older",
                    accent = if (delta <= 0) colors.good else colors.bad,
                )
            }
            cardio.pwvMs?.let { ObsStat("pulse-wave velocity", "%.1f m/s".format(it)) }
            cardio.segments?.let { ObsStat("segments analysed", "$it") }
        }
        fitness?.vo2max?.let { ObsStat("vo₂max", "%.1f".format(it)) }
    }
}

internal fun debtStateCopy(state: String): String = when (state) {
    "high" -> "Your sleep debt is high right now. Prioritize several consistent nights with enough sleep."
    "moderate" -> "You've built up a moderate amount of sleep debt. A few longer nights can help you recover."
    "low" -> "You're mostly meeting your sleep need, with a small amount left to recover."
    else -> "You've met your sleep need consistently over the past two weeks."
}

internal fun illnessCopy(illness: IllnessResult): String = when (illness.status) {
    // The decision comes from the model's overall score, not a count of flags: a night can
    // have a biometric out of range and still not add up to strain. Same copy as iOS/web.
    "NO_SIGNS" -> when (val flagged = illness.biomarkers.count { it.indicatesSymptoms }) {
        0 -> "Your overnight biometrics are within their usual ranges."
        1 -> "One overnight biometric is outside your usual range, but together they don't match the pattern of your body fighting something."
        else -> "$flagged overnight biometrics are outside your usual range, but together they don't match the pattern of your body fighting something."
    }
    "MINOR_SIGNS" -> "Some overnight biometrics are outside your usual ranges."
    "MAJOR_SIGNS" -> "Several overnight biometrics are outside your usual ranges."
    "MISSING_LAST_NIGHT_SLEEP" -> "Wear your ring overnight, then sync to see your latest result."
    else -> "Your radar needs at least 7 nights of data from the last 14 days."
}

internal fun biomarkerName(type: String): String = when (type) {
    "AverageBreath" -> "Breathing rate"
    "LowestHeartRate" -> "Lowest heart rate"
    "AverageHrv" -> "Heart rate variability"
    "TemperatureDeviation" -> "Temperature change"
    else -> type
}
