package md.thomas.openoura.data

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ── science: hypnogram-derived metrics (mirror of oura-summary sleep_metrics) ──
//
// The clinical sleep metrics and sleep debt are computed in THREE places and must stay
// identical: Rust (`oura-summary` sleep_metrics / smooth_stages / count_bouts /
// count_periods / sleep_debt_summary / sleep_need_s) for the web, Swift
// (apps/ios/OuraApp/Reports.swift `Sleep.*` / `Summary.stagedSleepDebt`) for iOS, and
// here for Android. The FFI JSON now carries the ring's own hypnogram (`stages_full`)
// even in the model-free build; the on-device SleepNet replaces it when it runs. Either
// way metrics are recomputed here from the FULL hypnogram (`NightRow.hypnogram`), never
// the 120-cell `stages` preview. If you change a smoothing window or a metric
// definition, change all three. See docs/clients.md.

/** Swift's `Double.rounded()` — round half away from zero, not Kotlin's default. */
internal fun rnd(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

/** One decimal place, matching Reports.swift's `r1`. */
internal fun r1(x: Double): Double = rnd(x * 10) / 10

data class SleepMetrics(
    val asleepMin: Double,
    val solMin: Double,
    val remLatencyMin: Double?,
    val wasoMin: Double,
    val awakenings: Int,
    val cycles: Int,
    val fragIndex: Double,
    val deepFirstHalfPct: Double?,
    val remFirstHalfPct: Double?,
)

/**
 * Mean HR/HRV per sleep stage — deep-sleep HRV is the recovery-relevant number. Mirror
 * of oura-summary `autonomic_by_stage`: each `series_t` sample is assigned to the stage at
 * its actual timestamp, exactly like the core and iOS `Sleep.autonomic(night:stages:)`.
 */
data class StageAutonomic(
    val hrvDeep: Double? = null,
    val hrvLight: Double? = null,
    val hrvRem: Double? = null,
    val hrDeep: Double? = null,
    val hrLight: Double? = null,
    val hrRem: Double? = null,
) {
    val any: Boolean
        get() = listOf(hrvDeep, hrvLight, hrvRem, hrDeep, hrLight, hrRem).any { it != null }
}

/** One `series_t` sample placed on the night window: [fraction] 0..1 of the in-bed span. */
data class TimedSample(val unix: Double, val fraction: Double, val value: Double)

object Sleep {

    /**
     * Longest unobserved gap (s) a continuous signal line bridges; longer gaps break the line
     * and its fill. Same value as iOS `Sleep.laneGapSeconds` and `LANE_GAP_S` in app.js.
     */
    const val LANE_GAP_S: Double = 15 * 60.0

    /** `[[unix_s, value]]` pairs → samples on the night window; out-of-window points dropped. */
    fun timedSamples(raw: List<List<Double>>?, startUnix: Long?, endUnix: Long?): List<TimedSample>? {
        if (raw.isNullOrEmpty() || startUnix == null || endUnix == null || endUnix <= startUnix) return null
        val start = startUnix.toDouble()
        val end = endUnix.toDouble()
        val span = end - start
        val out = raw.mapNotNull { pair ->
            if (pair.size < 2) return@mapNotNull null
            val t = pair[0]
            val v = pair[1]
            if (!t.isFinite() || !v.isFinite() || t < start - 1e-6 || t > end + 1e-6) return@mapNotNull null
            TimedSample(t, min(max((t - start) / span, 0.0), 1.0), v)
        }
        return out.ifEmpty { null }
    }

    /** Split samples into runs separated by gaps longer than [maxGapS]. */
    fun contiguousSegments(samples: List<TimedSample>, maxGapS: Double = LANE_GAP_S): List<List<TimedSample>> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<MutableList<TimedSample>>()
        out.add(mutableListOf(samples[0]))
        for (sample in samples.drop(1)) {
            if (sample.unix - out.last().last().unix > maxGapS) out.add(mutableListOf(sample))
            else out.last().add(sample)
        }
        return out
    }

    /** Value of the sample nearest [fraction] within [toleranceS], or null inside a gap. */
    fun sampleAt(
        fraction: Double,
        samples: List<TimedSample>,
        durationS: Double,
        toleranceS: Double = LANE_GAP_S / 2,
    ): Double? {
        if (durationS <= 0 || samples.isEmpty()) return null
        val nearest = samples.minByOrNull { abs(it.fraction - fraction) } ?: return null
        return if (abs(nearest.fraction - fraction) * durationS <= toleranceS) nearest.value else null
    }

    /** A hypnogram is usable for metrics only when every epoch is a real stage (1–4). */
    private fun fullyStaged(stages: List<Int>): Boolean = stages.all { it in 1..4 }

    /**
     * Resolve stage-specific HR and HRV for [night]: timestamped `series_t` samples first,
     * then the core's precomputed `autonomic`, and the index-fraction method only for
     * legacy cached summaries without timestamps. Mirrors iOS `Sleep.autonomic(night:stages:)`.
     */
    fun autonomic(night: NightRow, stages: List<Int>): StageAutonomic {
        val timed = night.seriesT
        val start = night.startUnix
        val end = night.endUnix
        if (timed != null && (timed.hr.isNotEmpty() || timed.hrv.isNotEmpty()) &&
            start != null && end != null && end > start
        ) {
            return autonomicTimed(timed.hr, timed.hrv, start, end, stages)
        }
        night.autonomic?.let { a ->
            val pre = StageAutonomic(a.hrvDeep, a.hrvLight, a.hrvRem, a.hrDeep, a.hrLight, a.hrRem)
            if (pre.any) return pre
        }
        return autonomic(night.series?.hr ?: emptyList(), night.series?.hrv ?: emptyList(), stages)
    }

    /**
     * Mean of each stage's `[unix_s, value]` samples, placing each at its own timestamp.
     * Mirrors oura-summary `autonomic_by_stage` and iOS `Sleep.autonomic(hrTimed:…)`.
     */
    fun autonomicTimed(
        hrTimed: List<List<Double>>,
        hrvTimed: List<List<Double>>,
        startUnix: Long,
        endUnix: Long,
        stages: List<Int>,
    ): StageAutonomic {
        if (stages.isEmpty() || endUnix <= startUnix) return StageAutonomic()
        val start = startUnix.toDouble()
        val span = (endUnix - startUnix).toDouble()
        fun means(pts: List<List<Double>>): Map<Int, Double> {
            val sum = HashMap<Int, Double>()
            val cnt = HashMap<Int, Int>()
            for (pair in pts) {
                if (pair.size < 2) continue
                val t = pair[0]
                val v = pair[1]
                if (v <= 0 || !t.isFinite() || !v.isFinite()) continue
                val f = (t - start) / span
                if (f < -1e-9 || f > 1 + 1e-9) continue
                val clamped = min(max(f, 0.0), 1.0)
                val idx = min(((clamped + 1e-9) * stages.size).toInt(), stages.size - 1)
                val s = stages[idx]
                sum[s] = (sum[s] ?: 0.0) + v
                cnt[s] = (cnt[s] ?: 0) + 1
            }
            return cnt.mapValues { (k, n) -> rnd(sum.getValue(k) / n) }
        }
        val h = means(hrTimed)
        val v = means(hrvTimed)
        return StageAutonomic(
            hrvDeep = v[1], hrvLight = v[2], hrvRem = v[3],
            hrDeep = h[1], hrLight = h[2], hrRem = h[3],
        )
    }

    /**
     * Legacy fallback when `series_t` is unavailable: mean of each stage's samples,
     * mapping series index → stage by fraction of the night.
     * A single overnight HRV slope is intentionally not derived — nocturnal HRV is
     * stage-driven (deep ↑, REM ↓), so a slope tracks stage order, not recovery.
     */
    fun autonomic(hr: List<Double>, hrv: List<Double>, stages: List<Int>): StageAutonomic {
        fun means(series: List<Double>): Map<Int, Double> {
            if (series.size <= 1 || stages.isEmpty()) return emptyMap()
            val sum = HashMap<Int, Double>()
            val cnt = HashMap<Int, Int>()
            series.forEachIndexed { i, v ->
                if (v > 0) {
                    val f = i.toDouble() / (series.size - 1)
                    val s = stages[min((f * stages.size).toInt(), stages.size - 1)]
                    sum[s] = (sum[s] ?: 0.0) + v
                    cnt[s] = (cnt[s] ?: 0) + 1
                }
            }
            return cnt.mapValues { (k, n) -> rnd(sum.getValue(k) / n) }
        }
        val h = means(hr)
        val v = means(hrv)
        return StageAutonomic(
            hrvDeep = v[1], hrvLight = v[2], hrvRem = v[3],
            hrDeep = h[1], hrLight = h[2], hrRem = h[3],
        )
    }

    /**
     * Mode filter over a centered odd window — removes single-epoch flicker so cycle /
     * awakening counts reflect real architecture, not 30 s noise. Ties resolve to the
     * lowest stage code, matching Swift's `max(by:)` and Rust's `smooth_stages`.
     */
    fun smooth(v: List<Int>, win: Int): List<Int> {
        if (v.size < win || win < 3) return v
        val half = win / 2
        return v.indices.map { i ->
            val a = max(0, i - half)
            val b = min(v.size, i + half + 1)
            val counts = IntArray(5)
            for (j in a until b) {
                val s = v[j]
                if (s in 1..4) counts[s]++
            }
            (1..4).maxByOrNull { counts[it] } ?: v[i]
        }
    }

    /** Runs of [code] at least [minLen] epochs long. Mirrors Rust `count_bouts`. */
    internal fun bouts(seq: List<Int>, code: Int, minLen: Int): Int {
        var count = 0
        var run = 0
        for (c in seq) {
            if (c == code) {
                run++
            } else {
                if (run >= minLen) count++
                run = 0
            }
        }
        if (run >= minLen) count++
        return count
    }

    /**
     * Runs of [code] merged across gaps shorter than [mergeGap], then kept when at least
     * [minLen] long. Mirrors Rust `count_periods`.
     */
    internal fun periods(seq: List<Int>, code: Int, mergeGap: Int, minLen: Int): Int {
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < seq.size) {
            if (seq[i] == code) {
                val s = i
                while (i < seq.size && seq[i] == code) i++
                runs.add(intArrayOf(s, i))
            } else {
                i++
            }
        }
        if (runs.isEmpty()) return 0
        val merged = ArrayList<IntArray>()
        merged.add(runs[0])
        for (r in runs.drop(1)) {
            val last = merged.last()
            if (r[0] - last[1] < mergeGap) last[1] = r[1] else merged.add(r)
        }
        return merged.count { it[1] - it[0] >= minLen }
    }

    fun metrics(stages: List<Int>, inBedS: Double): SleepMetrics? {
        val n = stages.size
        // Unknown (0) epochs would silently count as awake; refuse like Swift and Rust.
        if (n == 0 || inBedS <= 0 || !fullyStaged(stages)) return null
        val epochMin = inBedS / 60.0 / n
        if (epochMin <= 0) return null
        val isSleep = { c: Int -> c in 1..3 }

        val onset = stages.indexOfFirst(isSleep)
        val finalSleep = stages.indexOfLast(isSleep)
        if (onset < 0 || finalSleep < 0) return null

        val span = stages.subList(onset, finalSleep + 1)
        val asleepEpochs = stages.count(isSleep)
        val asleepMin = asleepEpochs * epochMin

        val wasoEpochs = span.count { it == 4 }
        val minWake = max(1, ceil(1.0 / epochMin).toInt())
        val awakenings = bouts(span, 4, minWake)

        val remOffset = stages.subList(onset, n).indexOf(3)
        val remLatency = if (remOffset >= 0) remOffset * epochMin else null

        val mergeGap = max(1, rnd(15.0 / epochMin).toInt())
        val minRem = max(1, ceil(3.0 / epochMin).toInt())
        val cycles = periods(span, 3, mergeGap, minRem)

        val transitions = (0 until span.size - 1).count { span[it] != span[it + 1] }
        val fragIndex = if (asleepMin > 0) transitions / (asleepMin / 60.0) else 0.0

        val mid = onset + (finalSleep - onset) / 2
        fun halfPct(code: Int): Double? {
            val total = stages.count { it == code }
            if (total == 0) return null
            val first = stages.subList(onset, mid + 1).count { it == code }
            return rnd(first.toDouble() / total * 100)
        }

        return SleepMetrics(
            asleepMin = r1(asleepMin),
            solMin = r1(onset * epochMin),
            remLatencyMin = remLatency?.let(::r1),
            wasoMin = r1(wasoEpochs * epochMin),
            awakenings = awakenings,
            cycles = cycles,
            fragIndex = r1(fragIndex),
            deepFirstHalfPct = halfPct(1),
            remFirstHalfPct = halfPct(3),
        )
    }

    /** Asleep seconds for a night from its (smoothed) stages. */
    fun asleepS(stages: List<Int>, inBedS: Double): Int {
        val n = stages.size
        if (n == 0 || !fullyStaged(stages)) return 0
        val epochS = inBedS / n
        return (stages.count { it in 1..3 } * epochS).toInt()
    }

    /**
     * Preserve SleepNet's absolute 30-second grid instead of stretching its output.
     * Mirror of iOS `Sleep.alignedStages` and `oura-summary`'s alignment. SleepNet stamps
     * each epoch with its END time: the first output is start+30 s and the last lands on
     * (or just past) the bedtime end. Epoch k covers (t-30 s, t]; reading t as the start
     * left epoch 0 unknown and dropped the last one, so every night was reported
     * incomplete. Returns null when the grid can't be trusted.
     */
    fun alignedStages(timestamps: List<Long>, stages: List<Int>, startMs: Long, endMs: Long): List<Int>? {
        if (timestamps.size != stages.size) return null
        if (endMs <= startMs || endMs - startMs > 48L * 3600 * 1000) return null
        if (!stages.all { it in 1..4 }) return null
        for (i in 0 until timestamps.size - 1) {
            if (timestamps[i] >= timestamps[i + 1]) return null
        }
        val span = endMs - startMs
        val aligned = MutableList(max(1, ((span + 29_999) / 30_000).toInt())) { 0 }
        timestamps.forEachIndexed { i, timestamp ->
            if (timestamp <= startMs) return@forEachIndexed
            val index = ((timestamp - startMs - 1) / 30_000).toInt()
            if (index < aligned.size) aligned[index] = stages[i]
        }
        return aligned
    }

    /** Linear-interpolated percentile over [values]. Mirror of iOS `Reports.percentile`. */
    private fun percentile(values: List<Double>, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val pos = min(max(q, 0.0), 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Per-30 s HR/HRV/motion grid used by the signal-based staging fallback. */
    class EpochSignalGrid(val hr: List<Double?>, val hrv: List<Double?>, val motion: List<Double>)

    /**
     * Place each overnight series on an [n]-epoch grid by true timestamp, preferring the
     * timestamped `series_t` then the flat legacy `series`. Mirror of iOS `epochSignalGrid`.
     */
    private fun epochSignalGrid(night: NightRow, n: Int): EpochSignalGrid? {
        if (n < 20) return null
        val startS = (night.startUnix ?: 0L).toDouble()
        val endS = (night.endUnix ?: night.durationS.toLong()).toDouble()
        val spanS = if (endS > startS) endS - startS else max(night.durationS, n * 30.0)
        if (spanS <= 0) return null
        val stepS = spanS / n

        fun interpolate(pts: List<List<Double>>, fallback: List<Double>, maxHoldS: Double): List<Double?> {
            var pairs = pts.mapNotNull { p ->
                if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite() || p[1] <= 0) null
                else p[0] to p[1]
            }
            if (pairs.isEmpty() && fallback.size > 1) {
                pairs = fallback.mapIndexedNotNull { i, v ->
                    if (!v.isFinite() || v <= 0) null
                    else (startS + spanS * i / (fallback.size - 1)) to v
                }
            }
            if (pairs.isEmpty()) return List(n) { null }
            pairs = pairs.sortedBy { it.first }
            val out = arrayOfNulls<Double>(n)
            var cursor = 0
            for (idx in 0 until n) {
                val center = startS + (idx + 0.5) * stepS
                while (cursor + 1 < pairs.size && pairs[cursor + 1].first <= center) cursor++
                var best = pairs[cursor]
                if (cursor + 1 < pairs.size &&
                    abs(pairs[cursor + 1].first - center) < abs(best.first - center)
                ) {
                    best = pairs[cursor + 1]
                }
                if (abs(best.first - center) <= maxHoldS) out[idx] = best.second
            }
            return out.toList()
        }

        val hr = interpolate(night.seriesT?.hr ?: emptyList(), night.series?.hr ?: emptyList(), 450.0)
        val hrv = interpolate(night.seriesT?.hrv ?: emptyList(), night.series?.hrv ?: emptyList(), 450.0)
        val motion = DoubleArray(n)
        val timedMotion = night.seriesT?.motion
        if (!timedMotion.isNullOrEmpty()) {
            for (p in timedMotion) {
                if (p.size < 2 || !p[0].isFinite() || !p[1].isFinite()) continue
                val f = (p[0] - startS) / spanS
                if (f < -1e-6 || f > 1 + 1e-6) continue
                val idx = min(max((f * n).toInt(), 0), n - 1)
                if (p[1] > motion[idx]) motion[idx] = p[1]
            }
        } else {
            val mVals = night.series?.motion
            if (!mVals.isNullOrEmpty()) {
                val mTimes = night.series?.motionTime
                mVals.forEachIndexed { i, v ->
                    if (v.isFinite()) {
                        val f = if (mTimes != null && i < mTimes.size) mTimes[i]
                        else i.toDouble() / max(1, mVals.size - 1)
                        val idx = min(max((f * n).toInt(), 0), n - 1)
                        if (v > motion[idx]) motion[idx] = v
                    }
                }
            }
        }
        return EpochSignalGrid(hr, hrv, motion.toList())
    }

    /**
     * Recover physiological N3 (Deep) bouts when Oura Gen 4 sparse IBI streams cause
     * SleepNet to collapse N3 into N2 (0% Deep across a full night). Mirror of iOS
     * `Sleep.refineDeepStages` / `oura-summary::refine_deep_stages`.
     */
    fun refineDeepStages(stages: List<Int>, night: NightRow): List<Int> {
        val n = stages.size
        if (n < 40 || !stages.all { it in 1..4 } || stages.contains(1) ||
            stages.count { it == 2 } < 20
        ) {
            return stages
        }
        val grid = epochSignalGrid(night, n) ?: return stages
        val hrValid = grid.hr.filterNotNull()
        if (hrValid.size < 6) return stages
        val hrQ35 = percentile(hrValid, 0.35)
        val hrQ50 = percentile(hrValid, 0.50)
        val hrvValid = grid.hrv.filterNotNull()
        val hrvQ45 = if (hrvValid.size >= 4) percentile(hrvValid, 0.45) else 0.0
        val posMotion = grid.motion.filter { it > 0 }
        val quietMotion = if (posMotion.isEmpty()) 2.0 else max(2.0, min(6.0, percentile(posMotion, 0.35)))

        val candidates = BooleanArray(n) { idx ->
            if (stages[idx] != 2) return@BooleanArray false
            val frac = idx.toDouble() / max(1, n - 1)
            if (frac > 0.78 || grid.motion[idx] > quietMotion) return@BooleanArray false
            val lo = max(0, idx - 3)
            val hi = min(n, idx + 4)
            if ((lo until hi).any { stages[it] == 4 || stages[it] == 3 }) return@BooleanArray false
            val h = grid.hr[idx] ?: return@BooleanArray false
            val hCap = if (frac <= 0.55) hrQ50 else hrQ35
            if (h > hCap + 0.5) return@BooleanArray false
            val hrs = (lo until hi).mapNotNull { grid.hr[it] }
            if (hrs.size >= 3) {
                val mean = hrs.sum() / hrs.size
                val variance = hrs.sumOf { (it - mean) * (it - mean) } / hrs.size
                if (sqrt(variance) > 3.2) return@BooleanArray false
            }
            if (hrvQ45 > 0) {
                val v = grid.hrv[idx]
                if (v != null && v < hrvQ45 * 0.75 && h > hrQ35) return@BooleanArray false
            }
            true
        }

        val smoothed = candidates.copyOf()
        if (n >= 3) {
            for (idx in 1 until n - 1) {
                if (!smoothed[idx] && candidates[idx - 1] && candidates[idx + 1] && stages[idx] == 2) {
                    smoothed[idx] = true
                }
            }
        }
        val refined = stages.toMutableList()
        val maxDeep = max(8, (n * 0.24).toInt())
        var assigned = 0
        var idx = 0
        while (idx < n) {
            if (smoothed[idx]) {
                var end = idx + 1
                while (end < n && smoothed[end]) end++
                if (end - idx >= 6) {
                    var j = idx
                    while (j < end && assigned < maxDeep) {
                        refined[j] = 1
                        assigned++
                        j++
                    }
                }
                idx = end
            } else {
                idx++
            }
        }
        return refined
    }

    /**
     * Physiological 4-stage hypnogram estimator when Torch SleepNet cannot run on a night
     * (e.g. Gen 4 nights with sparse raw PPG beats or model failure) yet valid overnight
     * HR/HRV/temp/motion series are present. Mirror of iOS `Sleep.estimateStages` /
     * `oura-summary::stage_night_from_signals`.
     */
    fun estimateStages(night: NightRow): List<Int>? {
        val su = night.startUnix
        val eu = night.endUnix
        val spanS: Double = if (su != null && eu != null && eu > su) (eu - su).toDouble() else night.durationS
        val n = (spanS / 30.0).toInt()
        if (n !in 40..1920) return null
        val grid = epochSignalGrid(night, n) ?: return null
        val hrValid = grid.hr.filterNotNull()
        val hrvValid = grid.hrv.filterNotNull()
        if (hrValid.size < 6 && hrvValid.size < 6) return null

        val hrQ35 = if (hrValid.isEmpty()) 58.0 else percentile(hrValid, 0.35)
        val hrQ65 = if (hrValid.isEmpty()) 64.0 else percentile(hrValid, 0.65)
        val hrQ85 = if (hrValid.isEmpty()) 70.0 else percentile(hrValid, 0.85)
        val hrvQ35 = if (hrvValid.isEmpty()) 30.0 else percentile(hrvValid, 0.35)
        val hrvQ65 = if (hrvValid.isEmpty()) 50.0 else percentile(hrvValid, 0.65)
        val posMotion = grid.motion.filter { it > 0 }
        val wakeMotion = if (posMotion.isEmpty()) 12.0 else max(8.0, percentile(posMotion, 0.85))
        val quietMotion = if (posMotion.isEmpty()) 2.0 else max(2.0, min(5.0, percentile(posMotion, 0.30)))

        val rawStages = MutableList(n) { 2 }
        for (idx in 0 until n) {
            val frac = idx.toDouble() / max(1, n - 1)
            val m = grid.motion[idx]
            val h = grid.hr[idx]
            val v = grid.hrv[idx]
            val edge = idx < 6 || idx + 6 >= n

            val lo = max(0, idx - 3)
            val hi = min(n, idx + 4)
            val hrs = (lo until hi).mapNotNull { grid.hr[it] }
            val hrStd: Double = if (hrs.size < 3) {
                1.5
            } else {
                val mean = hrs.sum() / hrs.size
                sqrt(hrs.sumOf { (it - mean) * (it - mean) } / hrs.size)
            }

            if (m >= wakeMotion || (edge && m > quietMotion) || ((h ?: 0.0) >= hrQ85 + 2.0 && m > quietMotion)) {
                rawStages[idx] = 4
                continue
            }
            val cyclePhase = sin(2.0 * PI * (spanS * frac / 5400.0 - 0.25))
            if (frac >= 0.12 && m <= quietMotion * 1.5 &&
                (hrStd >= 2.0 || (cyclePhase > 0.35 && (h ?: hrQ65) >= hrQ35))
            ) {
                if (v != null && v >= hrvQ65 && (h ?: hrQ65) > hrQ35) {
                    rawStages[idx] = 3
                    continue
                } else if (hrStd >= 2.2 && frac >= 0.20) {
                    rawStages[idx] = 3
                    continue
                }
            }
            if (frac <= 0.72 && m <= quietMotion && (h ?: (hrQ35 + 1.0)) <= hrQ35 + 0.5 && hrStd <= 2.5) {
                if ((v ?: hrvQ35) >= hrvQ35 * 0.85) {
                    rawStages[idx] = 1
                    continue
                }
            }
            rawStages[idx] = 2
        }
        val smoothed = smooth(rawStages, 5)
        return refineDeepStages(smoothed, night)
    }
}

/**
 * Android, like iOS, stages sleep on-device after the shared JSON is built. Rebuild the
 * same 14-day result after staging, grouping main sleep + naps by wake date. The web
 * receives this exact shape directly from `oura-summary`.
 */
fun Summary.stagedSleepDebt(): SleepDebtSummary? {
    val defaultNeedS = 8.0 * 3600.0
    val byDay = HashMap<String, Double>()
    // A wake day with ANY partially staged night is left out entirely rather than
    // under-counted (its unknown epochs aren't sleep we can vouch for). Same as iOS.
    val incompleteDays = HashSet<String>()
    for (n in nights) {
        val day = wakeYmd(n) ?: continue
        // hasHypnogram (> 1 epoch) on top of iOS's rule: a one-epoch "hypnogram" stretched
        // over a whole night isn't staging. Real ones have hundreds of epochs, so the two
        // clients agree on every actual night.
        if (!n.hasHypnogram || !n.stagingComplete) {
            incompleteDays.add(day)
            continue
        }
        val actual = Sleep.asleepS(Sleep.smooth(n.hypnogram ?: emptyList(), 5), n.durationS).toDouble()
        if (actual > 0) byDay[day] = (byDay[day] ?: 0.0) + actual
    }
    byDay.keys.removeAll(incompleteDays)
    val anchor = byDay.keys.maxOrNull() ?: return null
    val anchorDate = try {
        LocalDate.parse(anchor)
    } catch (_: Exception) {
        return null
    }

    fun key(base: LocalDate, offset: Int): String = base.plusDays(offset.toLong()).toString()

    // Personalized daily need — the mirror of oura-summary's `sleep_need_s` (typical
    // sleep over the last 90 days, IQR-filtered, clamped to 7–9 h, causal so a night
    // never sets its own need). Keep all three implementations in sync.
    fun needS(day: LocalDate): Double {
        val vals = (1..90).mapNotNull { byDay[key(day, -it)] }.filter { it > 0 }.sorted()
        if (vals.size < 14) return defaultNeedS
        fun quantile(p: Double): Double {
            val idx = p * (vals.size - 1)
            val lo = floor(idx).toInt()
            val hi = ceil(idx).toInt()
            return vals[lo] + (vals[hi] - vals[lo]) * (idx - lo)
        }
        val q1 = quantile(0.25)
        val q3 = quantile(0.75)
        val fence = 1.5 * (q3 - q1)
        val kept = vals.filter { it >= q1 - fence && it <= q3 + fence }
        val mean = kept.sum() / kept.size
        return rnd(min(max(mean, 7 * 3600.0), 9 * 3600.0) / 900) * 900
    }

    data class Score(val debt: Double, val recent: Double, val valid: Boolean, val count: Int)

    fun score(end: LocalDate): Score {
        val actual = (0 until 14).map { byDay[key(end, -it)] ?: 0.0 }
        val needs = (0 until 14).map { needS(end.minusDays(it.toLong())) }
        val count = actual.count { it > 0 }
        if (actual[0] <= 0) return Score(0.0, 0.0, false, count)
        val decay = 0.75 / 13.0
        var debt = 0.0
        for (i in actual.indices) {
            if (actual[i] > 0) debt += (1.0 - decay * i) * (needs[i] - actual[i])
        }
        debt = min(max(debt, 0.0), 36_000.0)
        debt = rnd(debt / 2700) * 2700
        return Score(debt, needs[0] - actual[0], count >= 5, count)
    }

    val days = (-13..0).map { offset ->
        val d = anchorDate.plusDays(offset.toLong())
        val k = d.toString()
        val total = byDay[k]
        val need = needS(d)
        val result = score(d)
        SleepDebtDay(
            date = k,
            totalSleepMin = total?.let { rnd(it / 60) },
            sleepNeedMin = rnd(need / 60),
            shortfallMin = total?.let { rnd((need - it) / 60) },
            cumulativeDebtMin = if (result.valid) rnd(result.debt / 60) else null,
            validDays = result.count,
        )
    }

    val current = score(anchorDate)
    val minutes = if (current.valid) rnd(current.debt / 60) else 0.0
    val state = when {
        minutes >= 540 -> "high"
        minutes >= 360 -> "moderate"
        minutes >= 180 -> "low"
        else -> "none"
    }
    return SleepDebtSummary(
        debtMin = minutes,
        recentShortfallMin = if (current.valid) rnd(current.recent / 60) else 0.0,
        valid = current.valid,
        needH = rnd(needS(anchorDate) / 36) / 100,
        validDays = current.count,
        windowDays = 14,
        state = state,
        days = days,
    )
}
