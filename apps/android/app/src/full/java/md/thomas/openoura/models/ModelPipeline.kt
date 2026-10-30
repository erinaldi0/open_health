package md.thomas.openoura.models

import android.content.Context
import md.thomas.openoura.core.Core
import md.thomas.openoura.data.Cardio
import md.thomas.openoura.data.DayAnalysisKind
import md.thomas.openoura.data.EventStore
import md.thomas.openoura.data.Sleep
import md.thomas.openoura.data.StageAutonomicJson
import md.thomas.openoura.data.Summary
import md.thomas.openoura.data.stagedSleepDebt
import md.thomas.openoura.diag.Diagnostics.log
import md.thomas.openoura.store.DB
import kotlin.math.roundToLong

/**
 * The slow part: run the on-device torch models and fold their results into the summary.
 * Port of `Core.withModels` in apps/ios/OuraApp/Core.swift.
 *
 * Models share one LibTorch runtime, so they run one after another (the JNI bridge also
 * serializes `forward()`). Concurrent inference raced the interpreter and peaked RAM on
 * hiking-heavy histories. Each reports a per-model error for genuine failures; those
 * surface in `modelErrors`.
 *
 * Memory is bounded instead by the shared read: `EventStore.decodedEvents` keeps at most
 * the newest 100k events (the model windows live at the end of the sync order), models
 * cap and cache their per-day tensors, and one-pass preprocessing drops the JSON of
 * step packets (`0x7E`/`0x7F`) up front.
 */
object ModelPipeline {
    const val AVAILABLE = true

    fun run(
        context: Context,
        base: Summary,
        previous: Summary?,
        progress: (String) -> Unit,
    ): Summary? {
        TorchBridge.loadError?.let {
            log("models", it)
            return base.copy(modelErrors = listOf(it))
        }

        var s = base
        val profile = base.profile

        var staged: Map<String, List<Int>> = emptyMap()
        var cva: CvaModel.Result? = null
        var workouts = emptyList<md.thomas.openoura.data.WorkoutSession>()
        var illness: md.thomas.openoura.data.IllnessResult? = null
        var sleepErr: String? = null
        var cvaErr: String? = null
        var actErr: String? = null
        var illErr: String? = null

        // One shared read: one failure point, one lock-contention window, and the
        // RingClock epoch recovery is paid once instead of once per model.
        progress("reading ring data…")
        var readErr: String? = null
        var events: List<EventStore.Ev> = emptyList()
        try {
            events = EventStore.decodedEvents(DB.readPath(context))
        } catch (e: Exception) {
            readErr = e.message ?: e.toString()
        }

        if (readErr == null && events.isNotEmpty()) {
            log("models", "read ${events.size} events")
            val clock = EventStore.RingClock(events)
            val rSleep = SleepStaging.run(context, base.nights, events, clock, profile, progress = progress)
            staged = rSleep.staged
            sleepErr = rSleep.error

            val rAct = ActivityModel.run(context, profile, events, clock, progress = progress)
            workouts = rAct.sessions
            actErr = rAct.error

            val rIll = IllnessModel.run(context, profile, events, clock)
            illness = rIll.result
            illErr = rIll.error
        } else if (readErr != null) {
            // The shared read failed: every event-fed model is unavailable this pass.
            // Surface one error; the publish below falls back to `previous`.
            sleepErr = readErr
            actErr = readErr
            illErr = readErr
        }

        val rCva = CvaModel.run(
            context,
            sex = profile?.sex ?: "M",
            age = profile?.age ?: 30.0,
            heightM = profile?.heightM ?: 1.78,
            weightKg = profile?.weightKg ?: 75.0,
            ringSize = profile?.ringSize ?: 10.0,
        )
        cva = rCva.result
        cvaErr = rCva.error

        // If staging failed outright, refill from the last published summary so a
        // transient read failure can't strip hypnograms that were already on screen.
        if (sleepErr != null && staged.isEmpty() && previous != null) {
            staged = previous.nights.mapNotNull { night ->
                // Only our own on-device staging — not the ring hypnogram the core supplies.
                val source = night.stagingSource ?: return@mapNotNull null
                if (!Sleep.isOuraStaging(source) && source != "signals" && source != "sleepnet") {
                    return@mapNotNull null
                }
                val stages = night.stagesFull?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                night.stagingKey to stages
            }.toMap()
        }

        // Fold SleepNet's hypnogram + stage breakdown into each night, keyed by the exact
        // bedtime start_ds so two sleeps on one calendar day don't collide.
        s = applySleepStages(context, staged, s)

        s = when {
            cva != null -> s.copy(
                cardio = Cardio(
                    vascularAge = cva.vascularAge,
                    chronologicalAge = profile?.age ?: 30.0,
                    pwvMs = cva.pwv,
                    segments = cva.segments,
                )
            )
            cvaErr != null -> s.copy(cardio = previous?.cardio)
            else -> s
        }

        // Never let a failed run replace real results with emptiness (the same principle
        // as the staged-sleep-debt coverage guard above).
        s = s.copy(
            workouts = if (actErr == null || workouts.isNotEmpty()) workouts
            else previous?.workouts ?: emptyList(),
            illness = if (illErr == null || illness != null) illness else previous?.illness,
            // Deduplicated: a failed shared read sets the same message on three models.
            modelErrors = listOfNotNull(sleepErr, cvaErr, actErr, illErr).distinct(),
        )
        return s
    }

    /**
     * Fold the staged hypnogram (NSSA, or the signal estimate fallback) into the nights
     * and refresh the debt window once the staged coverage is at least as large as the
     * model-free one. Port of `Core.applySleepStages` in apps/ios/OuraApp/Core.swift.
     */
    private fun applySleepStages(
        context: Context,
        staged: Map<String, List<Int>>,
        summary: Summary,
    ): Summary {
        // Source + first-epoch time of each staged night (NSSA, or the signal estimate).
        val meta = SleepStaging.cachedEntries(context, summary.nights, summary.profile)
        // Also load raw cache without windowKey validation as final fallback
        val globalKey = ModelCacheStore.globalKey(context, summary.profile) + "|nssa"
        val rawCache = ModelCacheStore.load(
            context, ModelCacheStore.STAGING_FILE, globalKey, StagedNightEntry.serializer()
        )
        var s = summary
        s = s.copy(
            nights = s.nights.map { night ->
                // Priority: 1) staged this run, 2) validated cache (meta), 3) raw cache
                val stages0 = staged[night.stagingKey]
                    ?: meta[night.stagingKey]?.stages
                    ?: rawCache[night.stagingKey]?.stages
                    ?: return@map night
                if (stages0.isEmpty()) return@map night
                val entry = meta[night.stagingKey] ?: rawCache[night.stagingKey]
                val source = entry?.source ?: "nssa"
                var stages = stages0
                // Oura's own hypnograms are used verbatim (oura-summary is_oura_staging);
                // only the signal estimate gets the deep-sleep recovery.
                if (!Sleep.isOuraStaging(source) && stages.none { it == 1 }) {
                    stages = Sleep.refineDeepStages(stages, night)
                }
                val total = stages.size.toDouble()
                fun pct(code: Int) = (stages.count { it == code } / total * 100).roundToLong().toDouble()
                val asleep = total - stages.count { it == 4 }
                val complete = stages.all { it in 1..4 }
                val autonomic = Sleep.autonomic(night, night.analysisStages)
                // The on-device hypnogram replaces the ring's in BOTH the preview and the
                // full-resolution field, so metrics can't read the old one.
                night.copy(
                    stages = stages,
                    stagesFull = stages,
                    stagingSource = source,
                    stages5min = if (Sleep.isOuraStaging(source)) Sleep.aggregate5Min(stages) else null,
                    stagesT0Unix = entry?.t0Unix,
                    stagingCompleteJson = complete,
                    stagingCoveragePct = stages.count { it in 1..4 } / total * 100,
                    deepPct = pct(1),
                    lightPct = pct(2),
                    remPct = pct(3),
                    wakePct = pct(4),
                    efficiency = if (complete) (asleep / total * 100).roundToLong().toDouble() else null,
                    sleepScore = null,                                  // the core's score describes a different hypnogram
                    autonomic = StageAutonomicJson(                     // mean HR/HRV per stage over the analysis hypnogram
                        hrvDeep = autonomic.hrvDeep,
                        hrvLight = autonomic.hrvLight,
                        hrvRem = autonomic.hrvRem,
                        hrDeep = autonomic.hrDeep,
                        hrLight = autonomic.hrLight,
                        hrRem = autonomic.hrRem,
                    ),
                )
            }
        )
        // Staging can be partial while model inputs are still arriving. Never replace a
        // more complete model-free debt window with a transient "0 of 5" result; prefer
        // staged sleep only when it covers at least as many distinct days.
        s.stagedSleepDebt()?.let { stagedDebt ->
            if (stagedDebt.validDays >= (s.sleepDebt?.validDays ?: 0)) {
                s = s.copy(sleepDebt = stagedDebt)
            }
        }
        return s
    }

    /**
     * Rerun ONE report (sleep or activity) for ONE day without touching the other models or
     * the other days. Port of `Core.refreshAnalysis` in apps/ios/OuraApp/Core.swift. Returns
     * the updated summary, or the previous one plus a user-facing error when nothing changes.
     */
    fun refreshDay(
        context: Context,
        previous: Summary,
        day: String,
        kind: DayAnalysisKind,
        progress: (String) -> Unit = {},
    ): Pair<Summary, String?> {
        TorchBridge.loadError?.let { return previous to "Couldn't refresh analysis: $it" }
        val base = Core.base(context)
        if (base.error != null) return previous to "Couldn't read saved data. Try again."
        return try {
            val events = EventStore.decodedEvents(DB.readPath(context))
            val clock = EventStore.RingClock(events)
            var s = previous
            when (kind) {
                DayAnalysisKind.SLEEP -> {
                    val night = base.nightForDay(day)
                    val start = night?.startDs
                    if (night == null || start == null) {
                        return previous to "No sleep data is saved for this day."
                    }
                    val result = SleepStaging.run(
                        context, listOf(night), events, clock, base.profile,
                        force = true, pruneCache = false, progress = progress,
                    )
                    if (result.error != null) {
                        return previous to "Couldn't refresh sleep analysis: ${result.error}"
                    }
                    val stages = result.staged[night.stagingKey]
                    if (stages.isNullOrEmpty()) {
                        return previous to "Not enough saved sleep data to refresh this night."
                    }
                    s = s.replacingNight(day, night)
                    s = applySleepStages(context, result.staged, s)
                }
                DayAnalysisKind.ACTIVITY -> {
                    if (base.activityProfile[day] == null) {
                        return previous to "No activity data is saved for this day."
                    }
                    val result = ActivityModel.run(
                        context, base.profile, events, clock,
                        onlyDay = day, force = true, progress = progress,
                    )
                    if (result.error != null) {
                        return previous to "Couldn't refresh activity analysis: ${result.error}"
                    }
                    s = s.replacingWorkouts(day, result.sessions)
                }
            }
            s to null
        } catch (e: Throwable) {
            log("models", "refresh day=$day kind=$kind failed: ${e.message ?: e}")
            previous to "Couldn't refresh ${kind.label.lowercase()} analysis. Your previous results are still available. See diagnostics for details."
        }
    }
}
