package md.thomas.openoura.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * One day of the report as JSON — what the Sleep / Activity tab of the day report shows, in
 * the same snake_case keys as the shared brain's summary so a file can be diffed against
 * `oura dashboard`'s /api/summary. Only the selected tab's section is included, plus a
 * header. Mirrors iOS `DayExport` (DayExport.swift) and the web's `exportDayJson`.
 */
@Serializable
data class DayExport(
    val day: String,
    val kind: String,
    val generated_at: String,
    val timezone: String,
    val app_version: String,
    val profile: Profile? = null,
    val sleep: SleepSection? = null,
    val activity: ActivitySection? = null,
) {
    @Serializable
    data class SleepSection(
        val night: NightRow,
        val metrics: Metrics? = null,
        val autonomic: StageAutonomicJson? = null,
        val sleep_debt: SleepDebtDay? = null,
    )

    @Serializable
    data class Metrics(
        val asleep_min: Double,
        val sol_min: Double,
        val rem_latency_min: Double? = null,
        val waso_min: Double,
        val awakenings: Int,
        val cycles: Int,
        val frag_index: Double,
        val deep_first_half_pct: Double? = null,
        val rem_first_half_pct: Double? = null,
    )

    @Serializable
    data class ActivitySection(
        val daily: DailyStat? = null,
        /** 96 × 15-min mean MET above rest */
        val profile_met: List<Double>,
        val timeline: Timeline,
        val workouts: List<WorkoutSession>,
        val model_errors: List<String>,
    )

    @Serializable
    data class Timeline(
        val start_hour: Double,
        val end_hour: Double,
        val start_caption: String,
        val end_caption: String,
        val points: List<Point>,
    ) {
        @Serializable
        data class Point(val hour: Double, val met: Double)
    }

    val isEmpty: Boolean get() = sleep == null && activity == null

    val fileName: String get() = "oura-$day-$kind.json"

    fun json(): String = EXPORT_JSON.encodeToString(serializer(), this)

    companion object {
        private val EXPORT_JSON = Json(SummaryJson) { prettyPrint = true }

        fun build(
            s: Summary,
            day: String,
            sleep: Boolean,
            appVersion: String,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): DayExport {
            var sleepSection: SleepSection? = null
            var activitySection: ActivitySection? = null
            if (sleep) {
                s.nightForDay(day)?.let { n ->
                    val smoothed = n.hypnogram?.let { Sleep.smooth(it, 5) }
                    val m = smoothed?.let { Sleep.metrics(it, n.durationS) }
                    val a = smoothed?.let { Sleep.autonomic(n, it) }?.takeIf { it.any }
                    sleepSection = SleepSection(
                        night = n,
                        metrics = m?.let {
                            Metrics(
                                it.asleepMin, it.solMin, it.remLatencyMin, it.wasoMin, it.awakenings,
                                it.cycles, it.fragIndex, it.deepFirstHalfPct, it.remFirstHalfPct,
                            )
                        },
                        autonomic = a?.let { StageAutonomicJson(it.hrvDeep, it.hrvLight, it.hrvRem, it.hrDeep, it.hrLight, it.hrRem) },
                        sleep_debt = s.sleepDebt?.days?.firstOrNull { it.date == day },
                    )
                }
            } else {
                val t = s.wakingActivityTimeline(day)
                activitySection = ActivitySection(
                    daily = s.activityDaily[day],
                    profile_met = s.activityProfile[day] ?: emptyList(),
                    timeline = Timeline(t.startHour, t.endHour, t.startCaption, t.endCaption, t.points.map { Timeline.Point(it.hour, it.met) }),
                    workouts = s.workoutsOn(day),
                    model_errors = s.modelErrors.filter { it.contains(day) },
                )
            }
            return DayExport(
                day = day,
                kind = if (sleep) "sleep" else "activity",
                generated_at = now.toString(),
                timezone = zone.id,
                app_version = appVersion,
                profile = s.profile,
                sleep = sleepSection,
                activity = activitySection,
            )
        }
    }
}
