package md.thomas.openoura.data

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared DB reader for the on-device models. Sleep staging and activity detection both
 * need the same decoded-JSON event stream and time anchor; this is that read in one
 * place (the CVA model reads raw PPG blobs instead, so it opens the DB itself).
 *
 * Port of apps/ios/OuraApp/EventStore.swift.
 */
object EventStore {

    /**
     * A failed read must never masquerade as "no data": a truncated event list would wipe
     * every model-derived panel and get persisted by the summary cache.
     */
    class ReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** A decoded event row: ring timestamp (ds), tag, decoded JSON, capture unix time. */
    class Ev(
        val ds: Long,
        val tag: Int,
        val json: JsonObject?,
        val cu: Long,
        val body: ByteArray?,
    ) {
        /** A numeric field of the decoded JSON, or null when absent/not a number. */
        fun num(key: String): Double? = json?.get(key)?.jsonPrimitive?.doubleOrNull

        fun long(key: String): Long? = json?.get(key)?.jsonPrimitive?.longOrNull
    }

    private const val SQL =
        "SELECT ring_timestamp, tag, decoded_json, captured_unix, body FROM events " +
            "WHERE decoded_json IS NOT NULL ORDER BY captured_unix, id"

    /** Maximum events to decode per model run (prevents OOM on huge histories). The
     * newest events are kept — the models' windows (last nights, last 30 days) live at
     * the end of the ascending `captured_unix, id` order. */
    private const val MAX_DECODED_EVENTS = 100_000

    /**
     * All events with decoded JSON, ordered by sync order. Throws on any failure —
     * callers must distinguish "no data" (empty list) from "couldn't read".
     */
    fun decodedEvents(dbPath: String): List<Ev> {
        val db = try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw ReadException("couldn't open the ring database: ${e.message}", e)
        }
        try {
            // Keep the newest MAX_DECODED_EVENTS rows while preserving ascending order
            // (RingClock's epoch walk needs chronological input).
            var sql = SQL
            db.rawQuery("SELECT COUNT(*) FROM events WHERE decoded_json IS NOT NULL", null).use { c ->
                if (c.moveToFirst()) {
                    val total = c.getLong(0)
                    if (total > MAX_DECODED_EVENTS) sql += " LIMIT -1 OFFSET ${total - MAX_DECODED_EVENTS}"
                }
            }
            val events = ArrayList<Ev>(min(MAX_DECODED_EVENTS, 4096))
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val tag = c.getInt(1)
                    val json = if (tag == 0x7E || tag == 0x7F) {
                        // real_step packets (0x7E/0x7F) are 14-byte bodies. Parsing their JSON
                        // on hiking-heavy histories is a large share of the analysis RAM spike
                        // and the models never read that JSON.
                        null
                    } else {
                        val text = c.getString(2) ?: continue
                        try {
                            SummaryJson.parseToJsonElement(text) as? JsonObject ?: continue
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    val body = if (c.isNull(4)) null else c.getBlob(4)
                    events.add(Ev(c.getLong(0), tag, json, c.getLong(3), body))
                }
            }
            return events
        } catch (e: ReadException) {
            throw e
        } catch (e: Exception) {
            throw ReadException("ring database read was interrupted: ${e.message}", e)
        } finally {
            db.close()
        }
    }


    class Epoch(
        var minDs: Long,
        var maxDs: Long,
        var captureMin: Long,
        var captureMax: Long,
        var fallbackAnchorUnix: Long,
        val anchors: MutableList<Anchor>,
        val boots: MutableList<Long> = mutableListOf(),
    )

    data class Anchor(val ds: Long, val unix: Long)

    /** How a wall-clock time was obtained. Mirrors `ClockSource` in ring_time.rs. */
    enum class ClockSource {
        ANCHOR,
        PROJECTED,
        DOWNLOAD_TIME,
        UNDATED;

        val isDated: Boolean
            get() = this == ANCHOR || this == PROJECTED
    }

    data class ResolvedTime(val unix: Double, val source: ClockSource)

    /**
     * `ds` (ring_timestamp) is a per-boot relative deciseconds counter — it resets to ~0
     * every time the ring reboots. A single global anchor therefore mis-dates older boots
     * (data scattered months off). Recover each boot "epoch" by walking events in real
     * sync order (captured_unix, then insertion id) and splitting on backward jumps in
     * ds, then anchor each epoch independently.
     *
     * This is one of THREE implementations that must agree — see docs/clients.md:
     * `crates/oura-summary/src/ring_time.rs`, `tools/epoch_time.py`, and this file
     * (plus its iOS twin in EventStore.swift).
     *
     * Epoch construction and replay recovery are paid once per model run rather than
     * once per sample.
     */
    class RingClock(events: List<Ev>) {

        private val epochs: List<Epoch>
        private val anchorOffsetsDs: List<AnchorOffset>

        private data class AnchorOffset(val offset: Long, val epoch: Int)

        init {
            require(events.isNotEmpty()) { "RingClock needs at least one event" }
            val built = ArrayList<Epoch>()
            for (event in events) {
                val last = built.lastOrNull()
                if (last != null && event.ds >= last.maxDs - EPOCH_RESET_SLACK_DS) {
                    if (event.ds >= last.maxDs) {
                        last.maxDs = event.ds
                        last.fallbackAnchorUnix = event.cu
                    }
                    last.minDs = min(last.minDs, event.ds)
                    last.captureMin = min(last.captureMin, event.cu)
                    last.captureMax = maxOf(last.captureMax, event.cu)
                    if ((event.tag == 0x42 || event.tag == 0x85)) {
                        event.long("unix_time")?.let {
                            // Uncapped: an epoch's anchors index its whole time-sync span
                            // (bracketing walks adjacent pairs), so a cap would invalidate
                            // the newest boots.
                            last.anchors.add(Anchor(event.ds, it))
                        }
                    }
                    if (event.tag == 0x41) {
                        last.boots.add(event.ds)
                    }
                } else {
                    val anchors = ArrayList<Anchor>()
                    if ((event.tag == 0x42 || event.tag == 0x85)) {
                        event.long("unix_time")?.let { anchors.add(Anchor(event.ds, it)) }
                    }
                    val boots = if (event.tag == 0x41) mutableListOf(event.ds) else mutableListOf<Long>()
                    built.add(
                        Epoch(
                            minDs = event.ds,
                            maxDs = event.ds,
                            captureMin = event.cu,
                            captureMax = event.cu,
                            fallbackAnchorUnix = event.cu,
                            anchors = anchors,
                            boots = boots,
                        )
                    )
                }
            }
            epochs = built
            built.forEach { it.anchors.sortBy { a -> a.ds } }
            anchorOffsetsDs = built.withIndex()
                .flatMap { (index, epoch) -> epoch.anchors.map { AnchorOffset(it.unix * 10 - it.ds, index) } }
                .sortedBy { it.offset }
        }

        /**
         * Map a raw ds to wall-clock seconds via time-sync or RTC beacon anchors.
         * [capturedUnix] selects the right boot when ds ranges overlap.
         */
        fun unixSeconds(ds: Long, capturedUnix: Long? = null): Double {
            return resolve(ds, capturedUnix).unix
        }

        /**
         * Wall-clock seconds only when the boot clock can be trusted for this ds;
         * null for undated data, which the per-day models must leave out.
         */
        fun datedUnixSeconds(ds: Long, capturedUnix: Long? = null): Double? {
            val resolved = resolve(ds, capturedUnix)
            return if (resolved.source == ClockSource.UNDATED) null else resolved.unix
        }

        private enum class Bracket {
            CONSISTENT,
            ERRATIC;
        }

        private data class BracketStalled(val before: Anchor, val after: Anchor)

        internal fun resolve(ds: Long, capturedUnix: Long?): ResolvedTime {
            val candidates = epochs.filter {
                ds >= it.minDs - EPOCH_RESET_SLACK_DS && ds <= it.maxDs + EPOCH_RESET_SLACK_DS
            }
            val epoch: Epoch = if (capturedUnix != null && candidates.isNotEmpty()) {
                candidates.minByOrNull { captureDistance(capturedUnix, it) }!!
            } else {
                candidates.minByOrNull { it.maxDs - it.minDs } ?: epochs.last()
            }

            epoch.anchors.minByOrNull { abs(it.ds - ds) }?.let { anchor ->
                val predicted = anchor.unix.toDouble() + (ds - anchor.ds) / 10.0
                val bracketResult = bracket(epoch.anchors, ds)
                when (bracketResult) {
                    Bracket.ERRATIC -> {
                        return ResolvedTime(predicted, ClockSource.UNDATED)
                    }
                    is BracketStalled -> {
                        val before = bracketResult.before
                        val after = bracketResult.after
                        val late = after.unix.toDouble() - (after.ds - ds) / 10.0
                        val early = before.unix.toDouble() + (ds - before.ds) / 10.0
                        // A ring_start between the anchors marks the stall exactly.
                        val bootsInRange = epoch.boots.filter { it > before.ds && it <= after.ds }
                        if (bootsInRange.isNotEmpty()) {
                            val first = bootsInRange.minOrNull()
                            val last = bootsInRange.maxOrNull()
                            if (first != null && last != null && ds >= first && ds < last) {
                                return ResolvedTime(predicted, ClockSource.UNDATED)
                            }
                            if (bootsInRange.maxOrNull() != null) {
                                val boot = bootsInRange.maxOrNull()!!
                                return ResolvedTime(if (ds >= boot) late else early, ClockSource.ANCHOR)
                            }
                        }
                        val plausible = capturedUnix?.let { late <= it + FUTURE_SLACK_SECONDS } ?: true
                        return ResolvedTime(if (plausible) late else early, ClockSource.ANCHOR)
                    }
                    Bracket.CONSISTENT -> {
                        // fall through
                    }
                    else -> {
                        // Handle null or unexpected types
                    }
                }
                if (capturedUnix == null
                    || predicted <= (capturedUnix + FUTURE_SLACK_SECONDS).toDouble()
                ) {
                    return ResolvedTime(predicted, ClockSource.ANCHOR)
                }
                latestPlausibleProjection(ds, capturedUnix)?.let { return ResolvedTime(it, ClockSource.PROJECTED) }
                val fallback = epoch.fallbackAnchorUnix.toDouble() - (epoch.maxDs - ds) / 10.0
                val unix = min(fallback, (capturedUnix + FUTURE_SLACK_SECONDS).toDouble())
                return ResolvedTime(unix, ClockSource.UNDATED)
            }

            // Download-time arithmetic only when the phone kept up with the ring
            // (capture span comparable to the ds span). A boot downloaded in one go
            // would be dated to the sync moment, so it is reported undated instead.
            val dsSpan = (epoch.maxDs - epoch.minDs) / 10.0
            val captureSpan = (epoch.captureMax - epoch.captureMin).toDouble()
            val incremental = dsSpan <= 0 || captureSpan * 2 >= dsSpan
            val fallback = epoch.fallbackAnchorUnix.toDouble() - (epoch.maxDs - ds) / 10.0
            val unix = capturedUnix?.let { min(fallback, (it + FUTURE_SLACK_SECONDS).toDouble()) } ?: fallback
            return ResolvedTime(unix, if (incremental) ClockSource.DOWNLOAD_TIME else ClockSource.UNDATED)
        }

        private fun captureDistance(capturedUnix: Long, epoch: Epoch): Long = when {
            capturedUnix < epoch.captureMin -> epoch.captureMin - capturedUnix
            capturedUnix > epoch.captureMax -> capturedUnix - epoch.captureMax
            else -> 0
        }

        private fun latestPlausibleProjection(ds: Long, capturedUnix: Long): Double? {
            val maxOffset = (capturedUnix + FUTURE_SLACK_SECONDS) * 10 - ds
            var low = 0
            var high = anchorOffsetsDs.size
            while (low < high) {
                val middle = low + (high - low) / 2
                if (anchorOffsetsDs[middle].offset <= maxOffset) low = middle + 1 else high = middle
            }
            if (low == 0) return null
            // Borrowing another boot's clock is only legitimate when this ds continues
            // that boot's counter: a new boot restarts near zero and must never be
            // projected through an older boot that only ran at higher counts, and a
            // counter jump far beyond an older boot's maxDs must not borrow it either.
            var index = low - 1
            while (index >= 0) {
                val candidate = anchorOffsetsDs[index]
                val ep = epochs[candidate.epoch]
                if (ds >= ep.minDs - EPOCH_RESET_SLACK_DS && ds <= ep.maxDs + EPOCH_RESET_SLACK_DS) {
                    return (ds + candidate.offset) / 10.0
                }
                index -= 1
            }
            return null
        }

        private fun bracket(anchors: List<Anchor>, ds: Long): Any? {
            var low = 0
            var high = anchors.size
            while (low < high) {
                val middle = (low + high) / 2
                if (anchors[middle].ds < ds) low = middle + 1 else high = middle
            }
            if (low >= anchors.size || low == 0) return Bracket.CONSISTENT
            val next = anchors[low]
            val prev = anchors[low - 1]
            if (next.ds == ds) return Bracket.CONSISTENT
            val wall = (next.unix - prev.unix).toDouble()
            val counter = (next.ds - prev.ds) / 10.0
            val tolerance = maxOf(ANCHOR_AGREEMENT_SECONDS, counter * ANCHOR_AGREEMENT_FRACTION)
            return if (wall < counter - tolerance) {
                Bracket.ERRATIC
            } else if (wall > counter + tolerance) {
                BracketStalled(prev, next)
            } else {
                Bracket.CONSISTENT
            }
        }

        val latestUnix: Long
            get() = epochs.flatMap { it.anchors }.maxOfOrNull { it.unix }
                ?: epochs.maxOf { it.fallbackAnchorUnix }

        companion object {
            private const val EPOCH_RESET_SLACK_DS = 6L * 3600 * 10
            private const val FUTURE_SLACK_SECONDS = 6L * 3600
            private const val ANCHOR_AGREEMENT_SECONDS = 30.0 * 60.0
            private const val ANCHOR_AGREEMENT_FRACTION = 0.02
        }
    }
}