package md.thomas.openoura.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ── the shared build_summary() JSON, decoded (same contract as the other clients) ──
// SIBLING CLIENTS: the web dashboard (dashboard/web/app.js) and the iOS app
// (apps/ios/OuraApp/Models.swift) decode the SAME summary JSON. A user-facing change
// here usually belongs there too — see the feature map in docs/clients.md. New computed
// fields go in crates/oura-summary; new models get an on-device path on every client
// AND a Python runner.

val SummaryJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class Trend(
    val series: List<Double> = emptyList(),
    val latest: Double? = null,
    val baseline: Double? = null,
    @SerialName("delta_pct") val deltaPct: Double? = null,
)

@Serializable
data class LatestVital(
    val latest: Double? = null,
    val date: String? = null,
    val hm: String? = null,
    @SerialName("at_unix") val atUnix: Long? = null,
)

@Serializable
data class Vitals(
    val hrv: Trend = Trend(),
    val rhr: Trend = Trend(),
    val hr: LatestVital? = null,
)

/**
 * Per-night raw signal series (from build_summary event accumulation — present in BOTH
 * the model-free and on-device builds; each covers the whole night so index→time is a
 * shared axis across lanes). Feeds the polysomnograph lanes.
 */
@Serializable
data class NightSeries(
    val hr: List<Double> = emptyList(),
    val hrv: List<Double> = emptyList(),
    val spo2: List<Double> = emptyList(),
    val temp: List<Double> = emptyList(),
    @SerialName("temp_span") val tempSpan: List<Double>? = null,
    val motion: List<Double> = emptyList(),
    @SerialName("motion_time") val motionTime: List<Double>? = null,
)

/** Timestamped `[unix_s, value]` pairs per signal (`series_t`), gaps kept. */
@Serializable
data class NightTimedSeries(
    val hr: List<List<Double>> = emptyList(),
    val hrv: List<List<Double>> = emptyList(),
    val spo2: List<List<Double>> = emptyList(),
    val temp: List<List<Double>> = emptyList(),
    val motion: List<List<Double>> = emptyList(),
)

/** Mean HR/HRV per sleep stage, as computed by the shared core (`nights[].autonomic`). */
@Serializable
data class StageAutonomicJson(
    @SerialName("hrv_deep") val hrvDeep: Double? = null,
    @SerialName("hrv_light") val hrvLight: Double? = null,
    @SerialName("hrv_rem") val hrvRem: Double? = null,
    @SerialName("hr_deep") val hrDeep: Double? = null,
    @SerialName("hr_light") val hrLight: Double? = null,
    @SerialName("hr_rem") val hrRem: Double? = null,
)

/** One component of the literature-based sleep score, with the paper behind it. */
@Serializable
data class SleepScoreComponent(
    val key: String,
    val score: Double,
    val weight: Double,
    val detail: JsonObject? = null,
) {
    val label: String
        get() = when (key) {
            "duration" -> "duration"
            "efficiency" -> "efficiency"
            "onset_latency" -> "time to fall asleep"
            "waso" -> "awake during the night"
            "awakenings" -> "awakenings"
            "architecture" -> "stage balance"
            "physiology" -> "heart & HRV"
            else -> key
        }

    val source: String? get() = (detail?.get("source") as? JsonPrimitive)?.contentOrNull
}

/**
 * A sleep score computed from published norms rather than from Oura's model — every
 * threshold in it traces to a paper. See `oura-summary/src/sleep_score.rs`.
 */
@Serializable
data class SleepScore(
    val score: Double,
    val components: List<SleepScoreComponent> = emptyList(),
    val basis: String? = null,
)

@Serializable
data class NightRow(
    val date: String? = null,
    val ymd: String? = null,
    @SerialName("start_ds") val startDs: Long? = null,
    @SerialName("end_ds") val endDs: Long? = null,
    @SerialName("raw_start_ds") val rawStartDs: Long? = null,
    @SerialName("raw_end_ds") val rawEndDs: Long? = null,
    @SerialName("bedtime_adjusted") val bedtimeAdjusted: Boolean? = null,
    @SerialName("bedtime_manual") val bedtimeManual: Boolean? = null,
    @SerialName("raw_start") val rawStart: String? = null,
    @SerialName("raw_end") val rawEnd: String? = null,
    val start: String? = null,
    val end: String? = null,
    // Supplied by the shared brain: the morning you woke (the day the night belongs to),
    // absolute bounds, and how the ring clock was tied to wall time for this night.
    @SerialName("wake_ymd") val wakeYmd: String? = null,
    @SerialName("start_unix") val startUnix: Long? = null,
    @SerialName("end_unix") val endUnix: Long? = null,
    @SerialName("clock_source") val clockSource: String? = null,
    @SerialName("captured_unix") val capturedUnix: Long? = null,
    @SerialName("in_bed_h") val inBedH: Double? = null,
    @SerialName("hrv_ms") val hrvMs: Double? = null,
    val rhr: Double? = null,
    @SerialName("skin_temp") val skinTemp: Double? = null,
    @SerialName("spo2_mean") val spo2Mean: Double? = null,
    // model-derived (present once the hypnogram runner is wired): per-30s stage codes
    // 1=deep 2=light 3=rem 4=wake, the stage percentages, and efficiency.
    @SerialName("deep_pct") val deepPct: Double? = null,
    @SerialName("light_pct") val lightPct: Double? = null,
    @SerialName("rem_pct") val remPct: Double? = null,
    @SerialName("wake_pct") val wakePct: Double? = null,
    val efficiency: Double? = null,
    /** A ≤120-cell PREVIEW for compact charts — never compute metrics from it. */
    val stages: List<Int>? = null,
    /** The full-resolution hypnogram (one code per 30 s epoch; 0 = not staged). */
    @SerialName("stages_full") val stagesFull: List<Int>? = null,
    @SerialName("staging_source") val stagingSource: String? = null,
    @SerialName("staging_coverage_pct") val stagingCoveragePct: Double? = null,
    @SerialName("staging_complete") val stagingCompleteJson: Boolean? = null,
    val series: NightSeries? = null,
    @SerialName("series_t") val seriesT: NightTimedSeries? = null,
    val autonomic: StageAutonomicJson? = null,
    /** Nightly respiratory rate (breaths/min), the ring's own per-window estimate. */
    @SerialName("breath_rate") val breathRate: Double? = null,
    @SerialName("sleep_score") val sleepScore: SleepScore? = null,
) {
    val id: String get() = (date ?: "") + (start ?: "")

    /** The hypnogram to use: full resolution when present, the preview only as a fallback. */
    val hypnogram: List<Int>? get() = stagesFull ?: stages
    val hasHypnogram: Boolean get() = (hypnogram?.size ?: 0) > 1

    /**
     * Every epoch carries a real stage (1–4). A partially staged night (code 0 epochs)
     * must not feed sleep metrics or debt — the unknown epochs would count as awake.
     */
    val stagingComplete: Boolean
        get() = stagingCompleteJson ?: (hypnogram?.let { h -> h.isNotEmpty() && h.all { it in 1..4 } } ?: false)

    /** Seconds in bed: exact wall-clock bounds when the core supplied them. */
    val durationS: Double
        get() {
            val s = startUnix
            val e = endUnix
            return if (s != null && e != null) (e - s).toDouble() else (inBedH ?: 0.0) * 3600
        }

    val isBedtimeManual: Boolean get() = bedtimeManual ?: false

    /** Key the on-device staging cache uses for this night (matches iOS `stagingKey`). */
    val stagingKey: String get() = "${startDs ?: 0}:${startUnix ?: capturedUnix ?: 0}"

    val hasSignals: Boolean
        get() = !(seriesT?.hr.isNullOrEmpty()) || !(series?.hr.isNullOrEmpty()) ||
            !(seriesT?.motion.isNullOrEmpty()) || !(series?.motion.isNullOrEmpty())
}

@Serializable
data class DailyStat(
    @SerialName("active_kcal") val activeKcal: Double? = null,
    @SerialName("total_kcal") val totalKcal: Double? = null,
    val steps: Double? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
)

@Serializable
data class Profile(
    val sex: String? = null,
    val age: Double? = null,
    @SerialName("height_m") val heightM: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("ring_size") val ringSize: Double? = null,
)

/** A detected activity session (on-device automatic_activity_detection). */
@Serializable
data class WorkoutSession(
    val start: String,
    val end: String,
    val durationMin: Int,
    val label: String,
    val isWorkout: Double,
) {
    val id: String get() = start + label
    val dayLabel: String get() = start.take(10)      // YYYY-MM-DD
    val startHM: String get() = start.takeLast(5)    // HH:MM
}

@Serializable
data class Cardio(
    @SerialName("vascular_age") val vascularAge: Double? = null,
    @SerialName("chronological_age") val chronologicalAge: Double? = null,
    @SerialName("pwv_ms") val pwvMs: Double? = null,
    val segments: Int? = null,
)

@Serializable
data class Fitness(val vo2max: Double? = null)

@Serializable
data class SleepDebtDay(
    val date: String,
    @SerialName("total_sleep_min") val totalSleepMin: Double? = null,
    @SerialName("sleep_need_min") val sleepNeedMin: Double,
    @SerialName("shortfall_min") val shortfallMin: Double? = null,
    @SerialName("cumulative_debt_min") val cumulativeDebtMin: Double? = null,
    @SerialName("valid_days") val validDays: Int,
)

@Serializable
data class SleepDebtSummary(
    @SerialName("debt_min") val debtMin: Double = 0.0,
    @SerialName("recent_shortfall_min") val recentShortfallMin: Double = 0.0,
    val valid: Boolean = false,
    @SerialName("need_h") val needH: Double = 8.0,
    @SerialName("valid_days") val validDays: Int = 0,
    @SerialName("window_days") val windowDays: Int = 14,
    val state: String = "none",
    val days: List<SleepDebtDay> = emptyList(),
)

@Serializable
data class Device(
    val serial: String? = null,
    val firmware: String? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    @SerialName("days_of_data") val daysOfData: Double? = null,
    val nights: Int? = null,
    val synced: String? = null,
    @SerialName("synced_hm") val syncedHm: String? = null,
    @SerialName("elapsed_days") val elapsedDays: Double? = null,
    @SerialName("observed_days") val observedDays: Int? = null,
    @SerialName("observed_coverage_days") val observedCoverageDays: Double? = null,
    @SerialName("total_events") val totalEvents: Int? = null,
    @SerialName("raw_events") val rawEvents: Int? = null,
    @SerialName("decoded_events") val decodedEvents: Int? = null,
    /** `[unix_s, percent]` readings over the last 14 days. */
    @SerialName("battery_history") val batteryHistory: List<List<Double>> = emptyList(),
)

/** Per-boot ring clock diagnostics from the shared brain (`clock` in the summary JSON). */
@Serializable
data class ClockEpoch(
    @SerialName("min_ds") val minDs: Long? = null,
    @SerialName("max_ds") val maxDs: Long? = null,
    @SerialName("span_h") val spanH: Double? = null,
    @SerialName("capture_min") val captureMin: Long? = null,
    @SerialName("capture_max") val captureMax: Long? = null,
    val anchors: Int? = null,
    @SerialName("anchor_sources") val anchorSources: List<String>? = null,
    @SerialName("latest_anchor_unix") val latestAnchorUnix: Long? = null,
)

@Serializable
data class UndatedReason(
    val reason: String? = null,
    val count: Int? = null,
    val recoverable: Boolean? = null,
    val message: String? = null,
    @SerialName("span_h") val spanH: Double? = null,
)

@Serializable
data class UndatedNight(
    @SerialName("start_ds") val startDs: Long? = null,
    @SerialName("end_ds") val endDs: Long? = null,
    @SerialName("in_bed_h") val inBedH: Double? = null,
    @SerialName("captured_unix") val capturedUnix: Long? = null,
    val source: String? = null,
    val reason: String? = null,
    val recoverable: Boolean? = null,
)

@Serializable
data class ClockDiag(
    val epochs: List<ClockEpoch> = emptyList(),
    val warnings: List<String> = emptyList(),
    @SerialName("undated_reasons") val undatedReasons: List<UndatedReason>? = null,
    @SerialName("undated_nights") val undatedNights: List<UndatedNight> = emptyList(),
)

/**
 * Symptom Radar (on-device illness detection). Mirrors the web summary's `illness`
 * block; computed on-device by IllnessModel so it isn't part of the FFI JSON.
 */
@Serializable
data class IllnessBiomarker(
    val type: String,        // AverageBreath | LowestHeartRate | AverageHrv | TemperatureDeviation
    val value: Double,
    val lower: Double,
    val upper: Double,
    val indicatesSymptoms: Boolean = false,
    val reason: String? = null, // "ELEVATED" | "DECREASED" | null
) {
    val id: String get() = type
}

@Serializable
data class IllnessResult(
    val available: Boolean = false,
    val status: String = "MISSING_SLEEP_DATA", // NO_SIGNS | MINOR_SIGNS | MAJOR_SIGNS | MISSING_LAST_NIGHT_SLEEP | MISSING_SLEEP_DATA
    val trafficLight: String = "NO_SIGNS",     // NO_SIGNS | MINOR_SIGNS | MAJOR_SIGNS
    val score: Double = 0.0,
    val decision: Int = 0,
    val date: String = "",
    val daysWithData: Int = 0,
    val biomarkers: List<IllnessBiomarker> = emptyList(),
)

@Serializable
data class Summary(
    val digest: String? = null,
    val device: Device? = null,
    val nights: List<NightRow> = emptyList(),
    val clock: ClockDiag? = null,
    val vitals: Vitals = Vitals(),
    /** date → 96 × 15-min mean MET-above-rest */
    @SerialName("activity_profile") val activityProfile: Map<String, List<Double>> = emptyMap(),
    /** date → steps / active-kcal / total-kcal */
    @SerialName("activity_daily") val activityDaily: Map<String, DailyStat> = emptyMap(),
    val profile: Profile? = null,
    val cardio: Cardio? = null,
    val fitness: Fitness? = null,
    @SerialName("sleep_debt") val sleepDebt: SleepDebtSummary? = null,
    val error: String? = null,
    // Filled on-device, not present in the FFI JSON. They round-trip through the local
    // summary cache, which is why they are @Serializable rather than excluded outright.
    val illness: IllnessResult? = null,
    /**
     * Model-free Symptom Radar from the shared core — same biomarkers, same shape, judged
     * against your own baseline. Used when the on-device model is not present.
     */
    val symptoms: IllnessResult? = null,
    val workouts: List<WorkoutSession> = emptyList(),
    val modelErrors: List<String> = emptyList(),
) {
    /**
     * Whichever Symptom Radar this build has: the on-device model when it ran, the shared
     * core's baseline comparison otherwise. Same rule as iOS `Summary.symptomRadar`.
     */
    val symptomRadar: IllnessResult?
        get() = illness?.takeIf { it.available } ?: symptoms

    /** recent days (newest first) that have a movement profile. */
    val activeDays: List<String> get() = activityProfile.keys.sortedDescending()

    /**
     * The calendar date you WOKE from a night. Nights are labelled by onset date (the
     * evening you went to bed), so an overnight sleep crossing midnight belongs to the
     * next day's morning. Pairing a day with the sleep you woke from — not the sleep you
     * started that evening — is what makes "night + activity of the day" one coherent
     * day. Kept identical to the web dashboard's wakeYmd() and Swift's Summary.wakeYmd.
     *
     * The shared core now supplies `wake_ymd` (from the true wall-clock end); the clock
     * string inference below only serves older cached summaries that predate it.
     */
    fun wakeYmd(n: NightRow): String? {
        n.wakeYmd?.let { return it }
        val ymd = n.ymd ?: return null
        val s = n.start
        val e = n.end
        if (s == null || e == null || e >= s) return ymd
        val parts = ymd.split("-").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return ymd
        return try {
            LocalDate.of(parts[0], parts[1], parts[2]).plusDays(1).format(YMD)
        } catch (_: Exception) {
            ymd
        }
    }

    /**
     * Every date with a night (by wake date) or activity — newest first; the unit both
     * the home hero and the all-days browser iterate.
     */
    val days: List<String>
        get() {
            val set = activityProfile.keys.toMutableSet()
            for (n in nights) wakeYmd(n)?.let { set.add(it) }
            return set.sortedDescending()
        }

    /**
     * The primary sleep you woke from on the morning of [day] — the longest in-bed night
     * wins over same-morning naps. Falls back to a MM-DD match for older data lacking ymd.
     */
    fun nightForDay(day: String): NightRow? {
        val cands = nights.filter { wakeYmd(it) == day }
        cands.maxByOrNull { it.inBedH ?: 0.0 }?.let { return it }
        val suffix = day.takeLast(5)
        return nights.firstOrNull { it.ymd == null && (it.date ?: "").endsWith(suffix) }
    }

    fun workoutsOn(day: String): List<WorkoutSession> =
        workouts.filter { it.isWorkout >= 0.5 && it.dayLabel == day }

    /** One value per wake-date (longest night wins), oldest first — feeds vital trend charts. */
    fun nightlySeries(pick: (NightRow) -> Double?): List<DatedVital> {
        val byDay = HashMap<String, Pair<Double, Double>>() // day → (in-bed hours, value)
        for (night in nights) {
            val value = pick(night) ?: continue
            if (!value.isFinite()) continue
            val day = wakeYmd(night) ?: night.ymd ?: continue
            val dur = night.inBedH ?: 0.0
            val existing = byDay[day]
            if (existing == null || dur > existing.first) byDay[day] = dur to value
        }
        return byDay.keys.sorted().map { DatedVital(it, byDay.getValue(it).second) }
    }

    companion object {
        internal val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

data class DatedVital(val date: String, val value: Double) {
    val id: String get() = date
}

enum class VitalKind {
    HRV, HEART_RATE, TEMP, OXYGEN;

    val title: String
        get() = when (this) {
            HRV -> "nightly hrv"
            HEART_RATE -> "heart rate"
            TEMP -> "skin temp"
            OXYGEN -> "blood o₂"
        }

    val unit: String
        get() = when (this) {
            HRV -> "ms"
            HEART_RATE -> "bpm"
            TEMP -> "°c"
            OXYGEN -> "%"
        }

    val caption: String
        get() = when (this) {
            HRV -> "RMSSD from the longest sleep of each morning"
            HEART_RATE -> "Nightly minimum resting heart rate"
            TEMP -> "Nightly skin temperature"
            OXYGEN -> "Nightly average blood oxygen"
        }

    val decimals: Int get() = if (this == TEMP) 1 else 0

    fun series(s: Summary): List<DatedVital> = when (this) {
        HRV -> s.nightlySeries { it.hrvMs }
        HEART_RATE -> s.nightlySeries { it.rhr }
        TEMP -> s.nightlySeries { it.skinTemp }
        OXYGEN -> s.nightlySeries { it.spo2Mean }
    }

    fun baseline(s: Summary): Double? = when (this) {
        HRV -> s.vitals.hrv.baseline
        HEART_RATE -> s.vitals.rhr.baseline
        else -> null
    }

    val goodWhenPositive: Boolean
        get() = when (this) {
            HRV, OXYGEN -> true
            HEART_RATE, TEMP -> false
        }
}

/** Selects which day + which tab the full-page report opens on. */
data class ReportSel(val day: String, val sleep: Boolean) {
    val id: String get() = day + if (sleep) "-s" else "-a"
}

/**
 * A calendar-day activity profile is convenient for storage, but people experience
 * activity between waking and going back to bed. This view model joins the tail of the
 * selected day to the next day's post-midnight buckets when necessary.
 */
data class TimedActivityPoint(val hour: Double, val met: Double)

data class WakingActivityTimeline(
    val startHour: Double,
    val endHour: Double,
    val points: List<TimedActivityPoint>,
    val startCaption: String,
    val endCaption: String,
)

fun Summary.wakingActivityTimeline(
    day: String,
    nowDay: String = LocalDate.now().format(Summary.YMD),
    nowTime: LocalTime = LocalTime.now(),
): WakingActivityTimeline {
    val recentMainSleeps = nights.filter { (it.inBedH ?: 0.0) >= 3.0 }.take(14)
    val wake = nightForDay(day)?.end?.let(::clockHour)
        ?: median(recentMainSleeps.mapNotNull { it.end?.let(::clockHour) })
        ?: 6.0

    // Prefer the sleep that actually started after this waking day. On a current day it
    // does not exist yet, so use the recent median main-sleep onset.
    val sameDaySleeps = nights.filter { it.ymd == day && (it.inBedH ?: 0.0) >= 3.0 }
    val observedBed = sameDaySleeps.maxByOrNull { it.inBedH ?: 0.0 }
        ?.start?.let(::clockHour)?.let { hourAfterWake(it, wake) }
    val estimatedBed = median(
        recentMainSleeps.mapNotNull { n ->
            n.start?.let(::clockHour)?.let { if (it < 12) it + 24 else it }
        }
    ) ?: 23.0

    val nowHour: Double? =
        if (day == nowDay) nowTime.hour + nowTime.minute / 60.0 else null
    val bed = observedBed ?: hourAfterWake(estimatedBed, wake)
    val end = minOf(30.0, maxOf(wake + 4, bed, nowHour ?: 0.0))
    val endIsNow = nowHour?.let { it > bed } ?: false

    val profiles = listOf(
        0.0 to (activityProfile[day] ?: emptyList()),
        24.0 to (nextDay(day)?.let { activityProfile[it] } ?: emptyList()),
    )
    val points = ArrayList<TimedActivityPoint>()
    for ((offset, values) in profiles) {
        if (values.size <= 1) continue
        val bucket = 24.0 / values.size
        values.forEachIndexed { index, value ->
            val hour = offset + (index + 0.5) * bucket
            if (hour >= wake - bucket && hour <= end + bucket) {
                points.add(TimedActivityPoint(hour, value))
            }
        }
    }

    return WakingActivityTimeline(
        startHour = wake,
        endHour = end,
        points = points,
        startCaption = "wake ${clockLabel(wake)}",
        endCaption = if (endIsNow) "now ${clockLabel(end)}"
        else "${if (observedBed == null) "estimated bed" else "bed"} ${clockLabel(end)}",
    )
}

internal fun clockHour(value: String): Double? {
    val parts = value.split(":").mapNotNull { it.toDoubleOrNull() }
    if (parts.size < 2 || parts[0] !in 0.0..23.999 || parts[1] !in 0.0..59.999) return null
    return parts[0] + parts[1] / 60
}

internal fun hourAfterWake(hour: Double, wake: Double): Double {
    var result = hour
    while (result <= wake) result += 24
    return result
}

internal fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
}

internal fun nextDay(day: String): String? {
    val parts = day.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return null
    return try {
        LocalDate.of(parts[0], parts[1], parts[2]).plusDays(1).format(Summary.YMD)
    } catch (_: Exception) {
        null
    }
}

internal fun clockLabel(hour: Double): String {
    val totalMinutes = Math.round(hour * 60).toInt()
    return "%02d:%02d".format((totalMinutes / 60) % 24, totalMinutes % 60)
}
