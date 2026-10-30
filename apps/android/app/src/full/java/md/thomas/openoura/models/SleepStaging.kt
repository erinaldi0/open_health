package md.thomas.openoura.models

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import md.thomas.openoura.data.EventStore
import md.thomas.openoura.data.NightRow
import md.thomas.openoura.data.Profile
import md.thomas.openoura.data.Sleep
import md.thomas.openoura.diag.Diagnostics.log
import kotlin.math.abs

/**
 * On-device sleep staging with Oura's NSSA model (sleepstaging_2_6_0), exactly as the
 * official app stages each ring bedtime — a twin of tools/run_sleep_model.py, ported
 * from apps/ios/OuraApp/SleepStaging.swift's NSSA rewrite. The pure input builders live
 * in [NssaInputs] (twin of tools/nssa_inputs.py), the beat correction in
 * [IbiCorrection] (twin of tools/ibi_correction.py), and the custom ops + classifier in
 * the native [TorchBridge.nssa] via cpp/torch_bridge.cpp + nssa_ops.c.
 *
 * Returns bedtime-start_ds → per-30s stage codes, matching `nights[].stages` elsewhere;
 * failed bedtimes fall back to the signal estimate (`estimateStages`, cache source
 * "signals") exactly as on iOS.
 */
object SleepStaging {

    const val MODEL_ID = "nssa-2.6.0-r1"
    private const val MODEL = "sleepstaging_2_6_0"
    private const val CLASSIFIER = "sleepstaging_2_6_0_classifier"

    /** NSSA never emits more than this many 30 s epochs (48 h). Bounds the output buffers. */
    private const val MAX_EPOCHS = 8192

    class Staged(val stages: List<Int>, val source: String, val t0Unix: Double?)

    class Result(val staged: Map<String, List<Int>>, val error: String?)

    /**
     * Returns start_ds → stage codes, plus a non-null [Result.error] only for genuine
     * failures (missing model asset). An empty map with a null error just means there is
     * no sleep data or no ring bedtime could be staged.
     *
     * [force] bypasses the per-night fingerprint cache (the "Refresh analysis" action);
     * [pruneCache] drops cache entries for nights no longer in the summary.
     */
    fun run(
        context: Context,
        nights: List<NightRow>,
        events: List<EventStore.Ev>,
        clock: EventStore.RingClock,
        profile: Profile? = null,
        force: Boolean = false,
        pruneCache: Boolean = true,
        progress: (String) -> Unit = {},
    ): Result {
        val modelPath = ModelAssets.path(context, MODEL)
            ?: return Result(emptyMap(), "sleep model file missing from the app assets")
        val classifierPath = ModelAssets.path(context, CLASSIFIER, ext = "bin")
            ?: return Result(emptyMap(), "sleep classifier file missing from the app assets")
        if (events.isEmpty()) return Result(emptyMap(), null)
        TorchBridge.nssaSetClassifierPath(classifierPath)
        val demo = demographics(profile)

        fun ms(ds: Long, cu: Long): Long {
            // ms(ds) → absolute epoch ms, half-to-even like Swift's .toNearestOrEven
            // (epoch-aware: ds resets on ring reboot, see EventStore.RingClock).
            val x = clock.unixSeconds(ds, cu) * 1000.0
            val fl = kotlin.math.floor(x)
            val rounded = when {
                x - fl > 0.5 -> fl + 1
                x - fl < 0.5 -> fl
                else -> if (fl % 2 == 0.0) fl else fl + 1
            }
            return rounded.toLong()
        }

        // One pass over the rows NSSA needs (bedtimes + its three streams), in the same
        // captured_unix,id order iOS's events.restricted read produces.
        val rows = ArrayList<NssaInputs.Row>()
        val bedtimes = ArrayList<NssaInputs.Row>()
        for (e in events) {
            if (e.tag != NssaInputs.TAG_BEDTIME && e.tag != NssaInputs.TAG_ACM &&
                e.tag != NssaInputs.TAG_IBI && e.tag != NssaInputs.TAG_TEMP
            ) continue
            val r = NssaInputs.Row(e.ds, e.tag, e.json, e.cu, e.body)
            if (e.tag == NssaInputs.TAG_BEDTIME) bedtimes.add(r) else rows.add(r)
        }

        /** run_sleep_model.ring_bedtimes: latest 0x76 per start in the same boot. */
        fun ringBedtimes(startDs: Long, endDs: Long, captured: Long): List<Triple<Long, Long, Long>> {
            val latest = HashMap<Long, Triple<Long, Long, Long>>()
            for (r in bedtimes) {
                val s = r.json?.get("bedtime_start_ds")?.jsonPrimitive?.longOrNull ?: continue
                val en = r.json?.get("bedtime_end_ds")?.jsonPrimitive?.longOrNull ?: continue
                if (en <= s) continue
                if (abs(ms(s, r.cu) - ms(s, captured)) > 300_000) continue
                if (s < endDs && en > startDs) latest[s] = Triple(s, en, r.cu)
            }
            return latest.values.sortedBy { it.first }
        }

        val globalKey = ModelCacheStore.globalKey(context, profile) + "|" + cacheTail(context)
        val cache = ModelCacheStore.load(
            context, ModelCacheStore.STAGING_FILE, globalKey, StagedNightEntry.serializer()
        )

        class OneNight(
            val night: NightRow,
            val startDs: Long,
            val endDs: Long,
            val cu: Long,
            val key: String,
        )

        val result = HashMap<String, List<Int>>()
        val currentKeys = HashSet<String>()
        var failures = 0
        var recomputed = 0
        for ((index, night0) in nights.withIndex()) {
            val night = night0
            val startDs = night.startDs ?: continue
            val endDs = night.endDs ?: continue
            val cu = night.capturedUnix ?: events.lastOrNull()?.cu ?: 0L
            val key = night.stagingKey
            currentKeys.add(key)
            if (clock.datedUnixSeconds(startDs, cu) == null ||
                clock.datedUnixSeconds(endDs, cu) == null
            ) continue
            progress(
                if (nights.size > 1) "staging sleep · night ${index + 1}/${nights.size}"
                else "staging sleep"
            )
            var beds = ringBedtimes(startDs, endDs, cu)
            if (beds.isEmpty()) beds = listOf(Triple(startDs, endDs, cu))
            val periods = ArrayList<Pair<LongArray, List<Int>>>()
            val failed = ArrayList<Pair<Long, Long>>()
            val fp = FNV64()
            for (b in MODEL_ID.toByteArray(Charsets.UTF_8)) fp.combine(b.toInt())
            for (d in demo) fp.combine(d)
            fp.combine(startDs)
            fp.combine(endDs)
            val inputsList = ArrayList<NssaInputs.Input>()
            for ((bs, be, _) in beds) {
                val inp = NssaInputs.collect(rows, bs, be, cu, ::ms)
                fp.combine(inp.bedtimeStartMs)
                fp.combine(inp.bedtimeEndMs)
                fp.combine(inp.acm.size)
                for (a in inp.acm) {
                    fp.combine(a.first)
                    for (v in a.second) fp.combine(v)
                }
                fp.combine(inp.ibi.size)
                for (b in inp.ibi) {
                    fp.combine(b.ts); fp.combine(b.ibi); fp.combine(b.amp); fp.combine(b.validity)
                }
                fp.combine(inp.temp.size)
                for (t in inp.temp) { fp.combine(t.first); fp.combine(t.second) }
                inputsList.add(inp)
            }
            val fpHex = fp.hex
            val entry = cache[key]
            if (!force && entry != null && entry.fp == fpHex && entry.stages.isNotEmpty()) {
                // Accept cache hit even if windowKey differs (e.g., summary durationS=0)
                // The fingerprint already encodes all model inputs; windowKey is a secondary check.
                if (entry.windowKey != windowKey(night)) {
                    log("models", "cache hit with windowKey mismatch for $key: cached=${entry.windowKey} current=${windowKey(night)}")
                }
                result[key] = entry.stages
                continue
            }
            for (inp in inputsList) {
                val staged = stage(inp, demo, modelPath) ?: run { failed.add(inp.bedtimeStartMs to inp.bedtimeEndMs); null }
                if (staged != null) periods.add(staged)
            }
            var stages: List<Int>? = null
            var source = "nssa"
            var t0: Double? = null
            if (periods.isNotEmpty()) {
                stages = NssaInputs.gridStages(
                    periods,
                    failed,
                    ms(startDs, cu),
                    ms(endDs, cu),
                )
                var main = periods.first()
                for (p in periods.drop(1)) if (p.second.size > main.second.size) main = p
                t0 = main.first.firstOrNull()?.toDouble()?.div(1000.0)
            } else if (night.hasSignals) {
                val est = Sleep.estimateStages(night)
                if (est != null) { stages = est; source = "signals" }
            }
            val finalStages = stages?.takeIf { it.isNotEmpty() } ?: run { failures++; continue }
            result[key] = finalStages
            cache[key] = StagedNightEntry(
                fp = fpHex,
                stages = finalStages,
                windowKey = windowKey(night),
                source = source,
                t0Unix = t0,
            )
            ModelCacheStore.save(
                context, ModelCacheStore.STAGING_FILE, globalKey, cache, StagedNightEntry.serializer(),
            )
            recomputed++
        }
        log("models", "sleep recomputed=$recomputed total=${currentKeys.size} failures=$failures")
        if (pruneCache) {
            val pruned = cache.filterKeys { it in currentKeys }
            if (pruned.size != cache.size) {
                ModelCacheStore.save(
                    context, ModelCacheStore.STAGING_FILE, globalKey, pruned, StagedNightEntry.serializer(),
                )
            }
        }
        if (result.isEmpty() && failures > 0) return Result(result, "sleep inference failed")
        return Result(result, null)
    }

    /** Load the cached entries for these nights, validating window/source like iOS does. */
    fun cachedEntries(
        context: Context,
        nights: List<NightRow>,
        profile: Profile?,
    ): Map<String, StagedNightEntry> {
        val globalKey = ModelCacheStore.globalKey(context, profile) + "|" + cacheTail(context)
        val cache = ModelCacheStore.load(
            context, ModelCacheStore.STAGING_FILE, globalKey, StagedNightEntry.serializer()
        )
        val out = HashMap<String, StagedNightEntry>()
        for (night in nights) {
            val entry = cache[night.stagingKey] ?: continue
            if (entry.windowKey == windowKey(night) && entry.stages.isNotEmpty()) out[night.stagingKey] = entry
        }
        return out
    }

    private fun cacheTail(context: Context): String {
        // Cheap identity of the inputs the entries were computed from; iOS folds it into
        // its globalKey. Kotlin's globalKey already covers build + DB, so keep it light.
        return "nssa"
    }

    /** Same as oura-summary Demographics / run_sleep_model.read_profile. */
    fun demographics(p: Profile?): FloatArray = NssaInputs.demographics(
        age = p?.age ?: 30.0,
        heightM = p?.heightM ?: 1.78,
        weightKg = p?.weightKg ?: 75.0,
        sex = p?.sex ?: "M",
    )

    fun windowKey(night: NightRow): String =
        night.stagingKey + "|" + (night.endDs?.toString() ?: "nil") +
            "|" + (night.endUnix?.toString() ?: "nil") + "|" + night.durationS.toRawBits()

    /**
     * One NSSA forward over one ring bedtime; null when the model's validator would
     * reject it (the official app then has no hypnogram either).
     */
    private fun stage(inp: NssaInputs.Input, demo: FloatArray, modelPath: String): Pair<LongArray, List<Int>>? {
        if (inp.acm.isEmpty() || inp.ibi.isEmpty() || inp.temp.isEmpty()) return null
        val acmTs = LongArray(inp.acm.size) { inp.acm[it].first }
        val acmVal = FloatArray(inp.acm.size * 6).also { arr ->
            inp.acm.forEachIndexed { i, (_, six) -> six.copyInto(arr, i * 6) }
        }
        val ibiTs = LongArray(inp.ibi.size) { it -> inp.ibi[it].ts }
        val ibiVal = FloatArray(inp.ibi.size * 3).also { arr ->
            inp.ibi.forEachIndexed { i, b ->
                arr[i * 3] = b.ibi.toFloat()
                arr[i * 3 + 1] = b.amp.toFloat()
                arr[i * 3 + 2] = b.validity.toFloat()
            }
        }
        val tempTs = LongArray(inp.temp.size) { inp.temp[it].first }
        val tempVal = FloatArray(inp.temp.size) { inp.temp[it].second }

        val timestamps = LongArray(MAX_EPOCHS)
        val out = TorchBridge.nssa(
            modelPath,
            acmTs, acmVal, inp.acm.size,
            ibiTs, ibiVal, inp.ibi.size,
            tempTs, tempVal, inp.temp.size,
            demo,
            inp.bedtimeStartMs, inp.bedtimeEndMs,
            timestamps, MAX_EPOCHS,
        ) ?: return null
        if (out.isEmpty()) return null
        return timestamps.take(out.size).toLongArray() to out.toList()
    }
}
