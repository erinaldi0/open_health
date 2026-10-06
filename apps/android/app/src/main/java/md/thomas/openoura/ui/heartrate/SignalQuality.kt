package md.thomas.openoura.ui.heartrate

/**
 * Says when the live stream is too sparse to trust, so the screen shows "weak signal"
 * instead of a confident wrong number.
 *
 * Measure: **coverage** — how much of the last [WINDOW_MS] of wall-clock time is accounted
 * for by accepted beats (sum of their IBIs ÷ elapsed time). With a good pulse lock every beat
 * is reported, so coverage sits near 1. With poor contact the ring flags most beats as
 * unlocked (oura-link drops those) and the few that get through are unreliable — in the
 * Ring 5 capture that read 143 bpm against a watch's 72, the accepted beats were ~20% of the
 * frames and noisy, so the number was meaningless.
 *
 * Calibrated on real captures (2026-10-06): weak-contact sessions never exceeded 0.48
 * coverage, a good session never dropped below 0.75. [MIN_COVERAGE] sits in that gap.
 *
 * Wall-clock based, so it also catches beats stopping altogether — the caller should poll
 * [isWeak] on a timer, not only when a beat arrives. Times are a monotonic clock in ms.
 */
class SignalQuality {

    companion object {
        const val WINDOW_MS = 15_000L
        const val MIN_COVERAGE = 0.6
        /** Don't judge before this much time has passed since the first beat. */
        const val MIN_ELAPSED_MS = 5_000L
    }

    private val beats = ArrayDeque<Pair<Long, Int>>() // (arrival ms, ibi ms)
    private var firstAtMs: Long? = null

    fun addBeat(atMs: Long, ibiMs: Int) {
        if (firstAtMs == null) firstAtMs = atMs
        beats.addLast(atMs to ibiMs)
    }

    /** Fraction of recent time covered by accepted beats, or null while too early to tell. */
    fun coverage(nowMs: Long): Double? {
        val first = firstAtMs ?: return null
        val elapsed = minOf(WINDOW_MS, nowMs - first)
        if (elapsed < MIN_ELAPSED_MS) return null
        while (beats.isNotEmpty() && beats.first().first <= nowMs - WINDOW_MS) beats.removeFirst()
        // BLE delivers beats in bursts, so a window edge can briefly hold > 1 s per second.
        return minOf(1.0, beats.sumOf { it.second }.toDouble() / elapsed)
    }

    fun isWeak(nowMs: Long): Boolean = coverage(nowMs)?.let { it < MIN_COVERAGE } ?: false

    fun reset() {
        beats.clear()
        firstAtMs = null
    }
}
