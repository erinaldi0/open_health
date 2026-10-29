package md.thomas.openoura.models

import android.content.Context
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import md.thomas.openoura.data.EventStore
import md.thomas.openoura.data.NightRow
import md.thomas.openoura.data.Sleep
import md.thomas.openoura.diag.Diagnostics.log
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * On-device sleep staging: read the synced DB, assemble the raw SleepNet inputs (a
 * faithful port of tools/run_sleep_model.py — the model bakes in its own preprocessing),
 * and run sleepnet_moonstone through the LibTorch lite bridge. Returns
 * bedtime-start_ds → per-30s stage codes, matching `nights[].stages` on the other clients.
 *
 * Port of apps/ios/OuraApp/SleepStaging.swift. The Gen-4 fixes (separate 0x60/0x80 beats
 * with quality gating, synthetic beats from 0x5d, 0x75 temperature, SleepNet's output
 * timestamps placed on the true 30 s grid via [Sleep.alignedStages], and the
 * `estimateStages` signal fallback) are what make Gen 4 nights stage correctly.
 */
object SleepStaging {

    private const val MODEL = "sleepnet_moonstone_1_2_0"

    /** SleepNet never emits more than this many 30 s epochs (48 h). Bounds the output buffers. */
    private const val MAX_EPOCHS = 8192

    /** Max beats per night (120 bpm × 48 h = 345,600; cap at 200k for safety). */
    private const val MAX_BEATS_PER_NIGHT = 200_000

    /** Max HRV summary rows per night (one per 5 min × 48 h = 576; generous cap). */
    private const val MAX_HRV_ROWS_PER_NIGHT = 2_000

    /** Max motion samples per night (one per minute × 48 h = 2,880; generous cap). */
    private const val MAX_ACM_PER_NIGHT = 10_000

    /** Max temperature samples per night (one per minute × 48 h = 2,880; generous cap). */
    private const val MAX_TEMP_PER_NIGHT = 10_000

    /** Everything SleepNet receives for one night — also the material the cache fingerprints. */
    class NightInputs(
        val startDs: Long,
        val endDs: Long,
        val startMs: Long,
        val endMs: Long,
        /** (timestampMs, ibiMs, amplitude, valid) */
        val beats: List<Beat>,
        val acm: List<Sample>,
        val temp: List<Sample>,
    )

    class Beat(val t: Long, val ibi: Float, val amplitude: Float, val valid: Float)
    class Sample(val t: Long, val v: Float)

    class Result(val staged: Map<String, List<Int>>, val error: String?)

    /**
     * Returns start_ds → stage codes, plus a non-null [Result.error] only for genuine
     * failures (missing model asset). An empty map with a null error just means there is
     * no sleep data to stage.
     *
     * [force] bypasses the per-night fingerprint cache (the "Refresh analysis" action);
     * [pruneCache] drops cache entries for nights no longer in the summary.
     */
    fun run(
        context: Context,
        nights: List<NightRow>,
        events: List<EventStore.Ev>,
        clock: EventStore.RingClock,
        force: Boolean = false,
        pruneCache: Boolean = true,
        progress: (String) -> Unit = {},
    ): Result {
        val modelPath = ModelAssets.path(context, MODEL)
            ?: return Result(emptyMap(), "sleep model file missing from the app assets")
        if (events.isEmpty()) return Result(emptyMap(), null)

        // The shared Rust summary canonicalizes brief wake splits and premature bedtime
        // ends. Consume those exact windows so the model cannot reintroduce the raw ring
        // boundary the UI already corrected. `nights` arrive newest-first from the
        // summary; keep that order so the night the user is looking at stages first.
        class Bed(val night: NightRow, val start: Long, val end: Long, val cu: Long, val key: String)
        val beds = nights.mapNotNull { night ->
            val start = night.startDs ?: return@mapNotNull null
            val end = night.endDs ?: return@mapNotNull null
            val captured = night.capturedUnix ?: events.firstOrNull { e ->
                (e.tag == 0x76 && e.long("bedtime_start_ds") == start) ||
                    (e.tag == 0x4e && e.long("bedtime_start") == start)
            }?.cu
            Bed(night, start, end, captured ?: events.lastOrNull()?.cu ?: 0L, start.toString())
        }

        val globalKey = ModelCacheStore.globalKey(context, null)
        val cache = ModelCacheStore.load(
            context, ModelCacheStore.STAGING_FILE, globalKey, StagedNightEntry.serializer()
        )

        class Pending(val night: NightRow, val key: String, val fp: String, val inputs: NightInputs?)

        val result = HashMap<String, List<Int>>()
        val currentKeys = HashSet<String>()
        val pending = ArrayList<Pending>()
        for (bed in beds) {
            val inputs = nightInputs(bed.start, bed.end, bed.cu, events, clock)
            val key = bed.key
            if (inputs != null) {
                val fp = fingerprint(inputs)
                currentKeys.add(key)
                val entry = cache[key]
                if (!force && entry != null && entry.fp == fp) {
                    if (entry.stages.isNotEmpty()) result[key] = entry.stages
                } else {
                    pending.add(Pending(bed.night, key, fp, inputs))
                }
            } else if (bed.night.hasSignals) {
                // No usable raw beats, but overnight HR/HRV/motion exist: stage from the
                // signals via the physiological estimator instead of leaving the night blank.
                val fp = "signals:${bed.start}:${bed.end}:${bed.cu}"
                currentKeys.add(key)
                val entry = cache[key]
                if (!force && entry != null && entry.fp == fp && entry.stages.isNotEmpty()) {
                    result[key] = entry.stages
                } else {
                    pending.add(Pending(bed.night, key, fp, null))
                }
            }
        }

        var inferenceFailures = 0
        var recomputed = 0
        pending.forEachIndexed { index, item ->
            progress(
                if (pending.size > 1) "staging sleep · night ${index + 1}/${pending.size}"
                else "staging sleep"
            )
            val stages = item.inputs?.let { stageNight(it, modelPath) }?.takeIf { it.isNotEmpty() }
                ?: Sleep.estimateStages(item.night)
            if (stages.isNullOrEmpty()) {
                inferenceFailures++
                return@forEachIndexed
            }
            result[item.key] = stages
            cache[item.key] = StagedNightEntry(item.fp, stages)
            ModelCacheStore.save(
                context, ModelCacheStore.STAGING_FILE, globalKey, cache,
                StagedNightEntry.serializer(),
            )
            recomputed++
        }
        log("models", "sleep recomputed=$recomputed total=${currentKeys.size} failures=$inferenceFailures")

        val pruned = cache.filterKeys { it in currentKeys }
        if (pruneCache && pruned.size != cache.size) {
            ModelCacheStore.save(
                context, ModelCacheStore.STAGING_FILE, globalKey, pruned,
                StagedNightEntry.serializer(),
            )
        }
        if (result.isEmpty() && inferenceFailures > 0 && pending.isNotEmpty()) {
            return Result(result, "sleep inference failed")
        }
        return Result(result, null)
    }

    /** Gather one night's raw model inputs; null when there is no usable beat data. */
    fun nightInputs(
        startDs: Long,
        endDs: Long,
        bedCu: Long,
        events: List<EventStore.Ev>,
        clock: EventStore.RingClock,
    ): NightInputs? {
        // ms(ds) → absolute epoch ms, epoch-aware (ds resets on ring reboot; see EventStore)
        fun ms(ds: Long, cu: Long): Long = (clock.unixSeconds(ds, cu) * 1000).toLong()

        val startMs = ms(startDs, bedCu)
        val endMs = ms(endDs, bedCu)
        val lo = startDs - 6000
        val hi = endDs + 6000
        val beats60 = ArrayList<Beat>()
        val beats80 = ArrayList<Beat>()
        val hrvRows = ArrayList<Pair<Long, JsonObject>>()
        val acm = ArrayList<Sample>()
        val temp46 = ArrayList<Sample>()
        val temp75 = ArrayList<Sample>()

        for (e in events) {
            if (e.ds < lo || e.ds > hi) continue
            // 0x60/0x80 raw beats, 0x5d HRV, 0x47 motion, 0x75/0x46 temperature.
            if (e.tag != 0x60 && e.tag != 0x80 && e.tag != 0x5d &&
                e.tag != 0x47 && e.tag != 0x75 && e.tag != 0x46
            ) {
                continue
            }
            val timestamp = ms(e.ds, e.cu)
            if (timestamp < startMs - 600_000 || timestamp > endMs + 600_000) continue
            if (abs(timestamp - (startMs + (e.ds - startDs) * 100)) > 300_000) continue
            when (e.tag) {
                0x60 -> {
                    val ibi = e.json?.get("ibi_ms") as? JsonArray ?: continue
                    val amp = e.json["amplitude"] as? JsonArray
                    val t = timestamp
                    var acc = 0L
                    ibi.forEachIndexed { i, element ->
                        if (beats60.size >= MAX_BEATS_PER_NIGHT) return@forEachIndexed
                        val x = element.jsonPrimitive.doubleOrNull?.toLong() ?: return@forEachIndexed
                        if (x <= 0) return@forEachIndexed
                        acc += x
                        val ampVal = amp?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
                        // The last two sub-600 ms beats with no amplitude are optical tail
                        // noise, not real IBIs — Gen 4 bright light or a shifting ring.
                        val tailNoise = ibi.size >= 4 && i >= ibi.size - 2 && x < 600 && ampVal <= 0f
                        val valid = if (x in 300..2000 && !tailNoise) 1f else 0f
                        beats60.add(Beat(t + acc, x.toFloat(), ampVal, valid))
                    }
                }
                0x80 -> {
                    val ibi = e.json?.get("ibi_ms") as? JsonArray ?: continue
                    val amp = e.json["amplitude"] as? JsonArray
                    val quality = e.json["quality"] as? JsonArray
                    val t = timestamp
                    var acc = 0L
                    ibi.forEachIndexed { i, element ->
                        if (beats80.size >= MAX_BEATS_PER_NIGHT) return@forEachIndexed
                        val x = element.jsonPrimitive.doubleOrNull?.toLong() ?: return@forEachIndexed
                        if (x <= 0) return@forEachIndexed
                        acc += x
                        val q = quality?.getOrNull(i)?.jsonPrimitive?.intOrNull
                        val valid = if (x in 300..2000 && q == 1) 1f else 0f
                        val ampVal = amp?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
                        beats80.add(Beat(t + acc, x.toFloat(), ampVal, valid))
                    }
                }
                0x5d -> if (hrvRows.size < MAX_HRV_ROWS_PER_NIGHT) e.json?.let { hrvRows.add(timestamp to it) }
                0x47 -> if (acm.size < MAX_ACM_PER_NIGHT) e.num("motion_seconds")?.let { acm.add(Sample(timestamp, it.toFloat())) }
                0x75 -> if (temp75.size < MAX_TEMP_PER_NIGHT) {
                    val temps = e.json?.get("temps_c") as? JsonArray
                    if (temps != null) {
                        val vals = temps.mapNotNull { it.jsonPrimitive.doubleOrNull?.toFloat() }
                            .filter { it > 0f }
                        if (vals.isNotEmpty()) temp75.add(Sample(timestamp, vals.sum() / vals.size))
                    }
                }
                0x46 -> if (temp46.size < MAX_TEMP_PER_NIGHT) {
                    val temps = e.json?.get("temps_c") as? JsonArray
                    temps?.firstOrNull()?.jsonPrimitive?.doubleOrNull?.let {
                        temp46.add(Sample(timestamp, it.toFloat()))
                    }
                }
            }
        }

        var beats: List<Beat> = when {
            beats60.isNotEmpty() && beats80.isNotEmpty() -> {
                beats60.sortBy { it.t }
                // Cap beats60 first so the merged list never exceeds the limit.
                val beats60Capped = beats60.take(MAX_BEATS_PER_NIGHT)
                val ts60 = beats60Capped.map { it.t }
                val posAmps = beats60Capped.map { it.amplitude }.filter { it > 0f }.sorted()
                val medAmp = if (posAmps.isEmpty()) 1200f else posAmps[posAmps.size / 2]
                val merged = ArrayList<Beat>(beats60Capped)
                for (b in beats80) {
                    if (merged.size >= MAX_BEATS_PER_NIGHT) break
                    // The 0x60 stream is cleaner; only add an 0x80 beat when it isn't a
                    // duplicate within 1.5 s (lane-amplitude dedup by timestamp).
                    var loIdx = 0
                    var hiIdx = ts60.size
                    while (loIdx < hiIdx) {
                        val mid = (loIdx + hiIdx) / 2
                        if (ts60[mid] < b.t) loIdx = mid + 1 else hiIdx = mid
                    }
                    var near = false
                    if (loIdx < ts60.size && abs(ts60[loIdx] - b.t) <= 15_000) near = true
                    if (loIdx > 0 && abs(ts60[loIdx - 1] - b.t) <= 15_000) near = true
                    if (!near) {
                        merged.add(Beat(b.t, b.ibi, if (b.amplitude > 0f) b.amplitude else medAmp, b.valid))
                    }
                }
                merged
            }
            beats60.isNotEmpty() -> beats60.take(MAX_BEATS_PER_NIGHT)
            else -> beats80.map { Beat(it.t, it.ibi, if (it.amplitude > 0f) it.amplitude else 1200f, it.valid) }.take(MAX_BEATS_PER_NIGHT)
        }

        // The ring sometimes emits no usable IBI at all (Gen 4 firmware). Reconstruct a
        // plausible beat train from the 5-minute HR/HRV summaries so the night still stages.
        if (beats.none { it.valid == 1f } && hrvRows.isNotEmpty()) {
            val synth = ArrayList<Beat>()
            for ((ts0, json) in hrvRows.sortedBy { it.first }) {
                if (synth.size >= MAX_BEATS_PER_NIGHT) break
                val intervalMin = json["interval_min"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 5L
                val stepMs = max(1L, intervalMin) * 60_000
                val hrs = json["hr_bpm"] as? JsonArray ?: continue
                val rmssds = json["rmssd_ms"] as? JsonArray
                hrs.forEachIndexed { i, element ->
                    if (synth.size >= MAX_BEATS_PER_NIGHT) return@forEachIndexed
                    val hr = element.jsonPrimitive.doubleOrNull?.toFloat() ?: return@forEachIndexed
                    if (hr <= 0f) return@forEachIndexed
                    val meanIbi = min(max(60_000.0f / hr, 350f), 1800f)
                    val rmssd = rmssds?.getOrNull(i)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 30f
                    val jitter = min(max(rmssd * 0.5f, 4f), 60f)
                    val slotStart = ts0 + i * stepMs
                    var tCur = slotStart
                    var k = 0
                    while (tCur < slotStart + stepMs && synth.size < MAX_BEATS_PER_NIGHT) {
                        val sign = if (k % 2 == 0) 1f else -1f
                        val raw = (meanIbi + sign * jitter * 0.5f).toDouble()
                        val ibi = min(max(floor(raw + 0.5), 330.0), 1950.0).toLong()
                        tCur += ibi
                        synth.add(Beat(tCur, ibi.toFloat(), 1200f, 1f))
                        k++
                    }
                }
            }
            beats = synth
        }

        val temp = (if (temp75.isEmpty()) temp46 else temp75).sortedBy { it.t }
        val sortedBeats = beats.sortedBy { it.t }
        acm.sortBy { it.t }
        if (sortedBeats.isEmpty() || sortedBeats.none { it.valid == 1f }) return null
        return NightInputs(
            startDs = startDs,
            endDs = endDs,
            startMs = startMs,
            endMs = endMs,
            beats = sortedBeats,
            acm = acm,
            temp = temp,
        )
    }

    /**
     * A night's fingerprint covers its exact model inputs; a hit is by construction the
     * hypnogram the model would have produced.
     */
    private fun fingerprint(inputs: NightInputs): String {
        val h = FNV64()
        h.combine(inputs.startDs)
        h.combine(inputs.endDs)
        h.combine(inputs.startMs)  // RingClock re-dating backstop
        h.combine(inputs.endMs)
        h.combine(inputs.beats.size)
        for (b in inputs.beats) {
            h.combine(b.t); h.combine(b.ibi); h.combine(b.amplitude); h.combine(b.valid)
        }
        h.combine(inputs.acm.size)
        for (a in inputs.acm) { h.combine(a.t); h.combine(a.v) }
        h.combine(inputs.temp.size)
        for (t in inputs.temp) { h.combine(t.t); h.combine(t.v) }
        return h.hex
    }

    /** One SleepNet inference over one night's inputs, placed on the true 30 s grid. */
    private fun stageNight(inputs: NightInputs, modelPath: String): List<Int>? {
        val ibiTs = LongArray(inputs.beats.size) { inputs.beats[it].t }
        val ibiVal = FloatArray(inputs.beats.size * 3)
        inputs.beats.forEachIndexed { i, b ->
            ibiVal[i * 3] = b.ibi
            ibiVal[i * 3 + 1] = b.amplitude
            ibiVal[i * 3 + 2] = b.valid
        }
        val acmTs = LongArray(inputs.acm.size) { inputs.acm[it].t }
        val acmVal = FloatArray(inputs.acm.size) { inputs.acm[it].v }
        val tempTs = LongArray(inputs.temp.size) { inputs.temp[it].t }
        val tempVal = FloatArray(inputs.temp.size) { inputs.temp[it].v }

        val timestamps = LongArray(MAX_EPOCHS)
        val out = TorchBridge.sleepnet(
            modelPath,
            ibiTs, ibiVal,
            acmTs, acmVal,
            tempTs, tempVal,
            inputs.startMs, inputs.endMs,
            timestamps,
        ) ?: return null
        if (out.isEmpty()) return null
        return Sleep.alignedStages(
            timestamps.take(out.size),
            out.toList(),
            inputs.startMs,
            inputs.endMs,
        )
    }
}
