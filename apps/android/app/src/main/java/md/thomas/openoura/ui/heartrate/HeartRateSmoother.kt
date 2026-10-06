package md.thomas.openoura.ui.heartrate

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the ring's beat-by-beat live stream into a steady on-screen heart rate.
 *
 * Raw per-beat bpm swings by design (respiratory sinus arrhythmia moves it with every
 * breath) and the ring occasionally reports a missed or doubled beat. So:
 *
 * 1. **Ectopic/artifact rejection** — a beat whose IBI is more than [MAX_DEVIATION] off the
 *    median of the last [MEDIAN_OF] *raw* beats is dropped. Taking the median over raw beats
 *    (not just accepted ones) lets a genuine shift (standing up) become the new normal within
 *    a few beats instead of being rejected forever. Same 30% rule as `tools/respiratory_rate.py`;
 *    a breathing-driven swing of ±15–25% is normal at rest and must not be filtered out.
 * 2. **Window on the beat clock** — accepted beats are kept while their IBIs sum to at most
 *    [WINDOW_MS], i.e. the last 10 s of the heart's own time, not of BLE delivery (which
 *    arrives in bursts).
 * 3. **bpm = 60000 / mean(IBI)** — beats per elapsed minute. Averaging per-beat bpm instead
 *    would be biased high (mean of 1/x ≥ 1/mean of x).
 *
 * Reports nothing until [MIN_SPAN_MS] of clean beats are in, so the first number shown is
 * not a single beat in disguise.
 */
class HeartRateSmoother {

    companion object {
        const val WINDOW_MS = 10_000
        const val MIN_SPAN_MS = 5_000
        const val MAX_DEVIATION = 0.30
        const val MEDIAN_OF = 7
    }

    private val recentRaw = ArrayDeque<Int>()
    private val window = ArrayDeque<Int>()
    private var windowSumMs = 0

    /** Beats currently averaged. */
    val beatsInWindow: Int get() = window.size

    /** Smoothed bpm, or null while the window is still too short. */
    val bpm: Int?
        get() = if (windowSumMs < MIN_SPAN_MS) null else (60_000.0 * window.size / windowSumMs).roundToInt()

    /** Feed one beat; returns whether it was accepted (false = rejected as an artifact). */
    fun add(ibiMs: Int): Boolean {
        val median = median(recentRaw)
        recentRaw.addLast(ibiMs)
        if (recentRaw.size > MEDIAN_OF) recentRaw.removeFirst()

        // Too few beats to judge yet: accept, the window minimum guards the first reading.
        if (median != null && abs(ibiMs - median) > MAX_DEVIATION * median) return false

        window.addLast(ibiMs)
        windowSumMs += ibiMs
        while (windowSumMs > WINDOW_MS && window.size > 1) {
            windowSumMs -= window.removeFirst()
        }
        return true
    }

    fun reset() {
        recentRaw.clear()
        window.clear()
        windowSumMs = 0
    }

    private fun median(xs: Collection<Int>): Double? {
        if (xs.size < 3) return null
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m].toDouble() else (s[m - 1] + s[m]) / 2.0
    }
}
