package md.thomas.openoura.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/**
 * Pure NSSA input builders — a line-by-line twin of tools/nssa_inputs.py, ported from
 * apps/ios/OuraApp/SleepStaging.swift's NssaInputs enum. Pure Kotlin (no torch), so the
 * JVM tests can run them against NssaParityFixture.json in every build.
 *
 * Official pipeline (decompiled 7.18.0, see docs/algorithms/sleep-staging.md):
 * bedtime = the ring's own bedtime_period (0x76), the latest event for that start;
 * ACM = sleep_acm_period (0x72), one 30 s window per event with six float32 statistics;
 * IBI = ibi_and_amplitude_event (0x60) only (never 0x80), every beat stamped at the
 * START of its interval, clamped, then corrected by ecore's IbiCorrection;
 * temperature = sleep_temp_event (0x75), one float32 sample per 30 s, the last sample
 * at the event time; each stream keeps events whose ring time falls inside the
 * bedtime and then the samples from the first to the last one inside [start, end].
 */
object NssaInputs {
    const val TAG_BEDTIME = 0x76
    const val TAG_ACM = 0x72
    const val TAG_IBI = 0x60
    const val TAG_TEMP = 0x75
    const val TEMP_SAMPLE_MS = 30_000L
    const val EPOCH_MS = 30_000L

    /**
     * parse_api_sleep_acm_period: finger angles (tmean, max, iqr) = int + frac/255,
     * MAD (tmean, max, iqr) = 12-bit/4095 + high nibble, all Float. Returned in the
     * model's order [madIqr, madMax, madTmean, faIqr, faMax, faMean].
     */
    fun decodeAcmPeriod(body: ByteArray?): FloatArray? {
        val b = body ?: return null
        if (b.size != 12) return null
        val angle = FloatArray(3)
        for (i in 0..2) {
            angle[i] = (b[2 * i].toInt() and 0xff).toFloat() / 255.0f + (b[2 * i + 1].toInt() and 0xff).toFloat()
        }
        val mad = FloatArray(3)
        for (i in 0..2) {
            val lo = b[6 + 2 * i].toInt() and 0xff
            val hi = b[7 + 2 * i].toInt() and 0xff
            mad[i] = (lo or ((hi and 0xF) shl 8)).toFloat() / 4095.0f + (hi shr 4).toFloat()
        }
        return floatArrayOf(mad[2], mad[1], mad[0], angle[2], angle[1], angle[0])
    }

    /**
     * parse_api_sleep_temp_event: LE centi-degrees, sample i of n at
     * event-(n-1-i)·30 s (the packet is stamped at its LAST sample — the walk-back rule).
     */
    fun expandTempEvent(body: ByteArray?, eventMs: Long): List<Pair<Long, Float>> {
        val b = body ?: return emptyList()
        if (b.size < 2 || b.size > 15 || b.size % 2 != 0) return emptyList()
        val n = b.size / 2
        return (0 until n).map { i ->
            val raw = (b[2 * i].toInt() and 0xff) or ((b[2 * i + 1].toInt() and 0xff) shl 8)
            (eventMs - (n - 1 - i).toLong() * TEMP_SAMPLE_MS) to raw.toFloat() / 100.0f
        }
    }

    /**
     * parse_api_ibi_and_amplitude_event: beat k starts at event - ibi[k...5],
     * clamped to the previous stamp. Returns (beats, newLast).
     */
    fun expandIbiEvent(
        ibis: List<Int>, amps: List<Int>, eventMs: Long, last0: Long,
        out: MutableList<Triple<Long, Int, Int>>,
    ): Long {
        var last = last0
        var stamp = eventMs - ibis.sum().toLong()
        for ((i, ibi) in ibis.withIndex()) {
            val clamped = maxOf(stamp, last)
            out.add(Triple(clamped, ibi, if (i < amps.size) amps[i] else 0))
            last = clamped
            stamp += ibi.toLong()
        }
        return last
    }

    /**
     * indexOfFirstAndLastOrNull + subListInclusive over item timestamps: keep everything
     * from the FIRST to the LAST item whose time falls inside [start, end] (gaps included).
     */
    fun <T> inclusiveWindow(items: List<T>, start: Long, end: Long, ts: (T) -> Long): List<T> {
        val first = items.indexOfFirst { start <= ts(it) && ts(it) <= end }
        if (first < 0) return emptyList()
        val last = items.indexOfLast { start <= ts(it) && ts(it) <= end }
        return items.subList(first, last + 1)
    }

    /** One decoded event row (the subset NssaInputs.collect needs). */
    class Row(
        val ds: Long,
        val tag: Int,
        val json: JsonObject?,
        val cu: Long,
        val body: ByteArray?,
    ) {
        fun intArray(key: String): List<Int>? {
            val arr = json?.get(key) as? JsonArray ?: return null
            return arr.mapNotNull { it.jsonPrimitive.intOrNull }
        }
    }

    class Input(
        val bedtimeStartMs: Long,
        val bedtimeEndMs: Long,
        /** (timestampMs, six-statistics row) */
        val acm: List<Pair<Long, FloatArray>>,
        /** (ts, ibi, amplitude, validity) after IbiCorrection */
        val ibi: List<IbiCorrection.Beat>,
        val temp: List<Pair<Long, Float>>,
    )

    /** Twin of nssa_inputs.collect_nssa_inputs. */
    fun collect(
        rows: List<Row>,
        startDs: Long,
        endDs: Long,
        capturedUnix: Long,
        unixMs: (ds: Long, capturedUnix: Long) -> Long,
        correct: (List<Triple<Long, Int, Int>>) -> List<IbiCorrection.Beat> = IbiCorrection::correct,
    ): Input {
        val startMs = unixMs(startDs, capturedUnix)
        val endMs = unixMs(endDs, capturedUnix)
        val acmEvents = ArrayList<Pair<Long, FloatArray>>()
        val tempEvents = ArrayList<Pair<Long, ByteArray?>>()
        val ibiEvents = ArrayList<Triple<Long, List<Int>, List<Int>>>()
        for (r in rows) {
            if (r.tag != TAG_ACM && r.tag != TAG_IBI && r.tag != TAG_TEMP) continue
            if (r.ds < startDs - 6000 || r.ds > endDs + 6000) continue
            val eventMs = unixMs(r.ds, r.cu)
            if (abs(eventMs - (startMs + (r.ds - startDs) * 100)) > 300_000) continue
            if (eventMs < startMs || eventMs > endMs) continue
            if (r.tag == TAG_ACM) {
                val s = decodeAcmPeriod(r.body) ?: continue
                acmEvents.add(eventMs to s)
            } else if (r.tag == TAG_TEMP) {
                tempEvents.add(eventMs to r.body)
            } else {
                val ibis = r.intArray("ibi_ms") ?: continue
                if (ibis.size != 6) continue
                ibiEvents.add(Triple(eventMs, ibis, r.intArray("amplitude") ?: emptyList()))
            }
        }
        // Kotlin's sortBy is stable, exactly like Python's list.sort.
        val acm = acmEvents.sortedBy { it.first }
        val temp: List<Pair<Long, Float>> = tempEvents.sortedBy { it.first }
            .flatMap { (t, b) -> expandTempEvent(b, t) }
        var last = 0L
        val rawBeats = ArrayList<Triple<Long, Int, Int>>()
        for ((t, ibis, amps) in ibiEvents.sortedBy { it.first }) {
            last = expandIbiEvent(ibis, amps, t, last, rawBeats)
        }
        val beats = inclusiveWindow(rawBeats, startMs, endMs) { it.first }
        return Input(
            bedtimeStartMs = startMs,
            bedtimeEndMs = endMs,
            acm = inclusiveWindow(acm, startMs, endMs) { it.first },
            ibi = if (beats.isEmpty()) emptyList() else correct(beats),
            temp = inclusiveWindow(temp, startMs, endMs) { it.first },
        )
    }

    /**
     * Twin of run_sleep_model.grid_stages: midpoint rule on the night's 30 s grid,
     * cells outside every ring bedtime = 4, cells of a failed bedtime = 0.
     */
    fun gridStages(
        periods: List<Pair<LongArray, List<Int>>>,
        failed: List<Pair<Long, Long>>,
        startMs: Long,
        endMs: Long,
    ): List<Int> {
        val n = maxOf(1L, ceilDivUp(endMs - startMs, EPOCH_MS)).toInt()
        val out = IntArray(n) { 4 }
        for ((bs, be) in failed) {
            for (idx in 0 until n) {
                val mid = startMs + idx * EPOCH_MS + EPOCH_MS / 2
                if (bs <= mid && mid < be) out[idx] = 0
            }
        }
        for ((ts, codes) in periods) {
            ts.forEachIndexed { i, t ->
                if (i >= codes.size) return@forEachIndexed
                val code = codes[i]
                val num = t - startMs - EPOCH_MS / 2
                // ceil division matching Python's -(-a // b) for negative a too
                val idx = ceilDivUp(num, EPOCH_MS).toInt()
                if (idx in 0 until n && code in 1..4) out[idx] = code
            }
        }
        return out.toList()
    }

    private fun ceilDivUp(a: Long, b: Long): Long = if (a >= 0) (a + b - 1) / b else -((-a) / b)

    /** NssaPyTorchModel demographics [age, bmi, sex] (nssa_stager.demographics). */
    fun demographics(age: Double, heightM: Double, weightKg: Double, sex: String): FloatArray {
        val bmi = weightKg / (heightM * heightM)
        val u = sex.uppercase()
        val s = if (u.startsWith("F")) -1f else if (u.startsWith("M")) 1f else 0f
        return floatArrayOf(age.toLong().toFloat(), bmi.toFloat(), s)
    }
}
