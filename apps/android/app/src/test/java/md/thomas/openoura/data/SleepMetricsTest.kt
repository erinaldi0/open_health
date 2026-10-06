package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clinical sleep metrics exist in three implementations (Rust `oura-summary`, Swift
 * `Reports.swift`, and Kotlin `Sleep.kt`). docs/clients.md requires them to agree, so
 * these pin the definitions rather than the current output.
 *
 * Stage codes: 1=deep 2=light 3=rem 4=wake.
 */
class SleepMetricsTest {

    /** 30-second epochs, the SleepNet output resolution. */
    private fun inBedSFor(epochs: Int) = epochs * 30.0

    private fun run(vararg pairs: Pair<Int, Int>): List<Int> =
        pairs.flatMap { (code, count) -> List(count) { code } }

    @Test
    fun `smooth removes a single-epoch flicker`() {
        val v = listOf(2, 2, 2, 4, 2, 2, 2)
        assertEquals(listOf(2, 2, 2, 2, 2, 2, 2), Sleep.smooth(v, 5))
    }

    @Test
    fun `smooth leaves a genuine run intact`() {
        val v = run(2 to 6, 4 to 6, 2 to 6)
        val out = Sleep.smooth(v, 5)
        // the 3-minute wake block survives a 5-epoch mode filter
        assertTrue(out.count { it == 4 } >= 4)
    }

    @Test
    fun `smooth is a no-op for windows below three or sequences shorter than the window`() {
        val v = listOf(1, 4, 1)
        assertEquals(v, Sleep.smooth(v, 1))
        assertEquals(listOf(1, 4), Sleep.smooth(listOf(1, 4), 5))
    }

    @Test
    fun `smooth ties resolve to the lowest stage code`() {
        // window of 4 around index 1: two 1s and two 2s -> tie, lowest code wins
        val out = Sleep.smooth(listOf(1, 1, 2, 2), 3)
        assertEquals(1, out[1])
    }

    @Test
    fun `bouts counts runs meeting the minimum length`() {
        val seq = listOf(4, 4, 2, 4, 2, 4, 4, 4)
        assertEquals(2, Sleep.bouts(seq, 4, 2)) // the 2-run and the 3-run; the single is dropped
        assertEquals(3, Sleep.bouts(seq, 4, 1))
        assertEquals(1, Sleep.bouts(seq, 4, 3))
    }

    @Test
    fun `periods merges runs separated by less than the gap`() {
        val seq = run(3 to 4, 2 to 2, 3 to 4, 2 to 20, 3 to 4)
        // gap of 2 < mergeGap 5, so the first two REM runs are one period; the third is
        // separated by 20 epochs and stays its own.
        assertEquals(2, Sleep.periods(seq, 3, 5, 3))
        // with a tiny merge gap they stay three separate periods
        assertEquals(3, Sleep.periods(seq, 3, 1, 3))
    }

    @Test
    fun `periods drops merged runs shorter than the minimum`() {
        assertEquals(0, Sleep.periods(run(3 to 2, 2 to 30, 3 to 2), 3, 1, 3))
    }

    @Test
    fun `metrics computes onset latency, WASO, awakenings and REM latency`() {
        // 10 epochs wake -> 20 deep -> 10 wake -> 40 light -> 20 rem -> 10 wake
        val stages = run(4 to 10, 1 to 20, 4 to 10, 2 to 40, 3 to 20, 4 to 10)
        val m = Sleep.metrics(stages, inBedSFor(stages.size))!!

        // epochMin is 0.5, so 10 wake epochs before the first sleep = 5 minutes
        assertEquals(5.0, m.solMin, 1e-9)
        // 80 sleep epochs * 0.5 min
        assertEquals(40.0, m.asleepMin, 1e-9)
        // only the wake INSIDE the sleep span counts (the trailing 10 do not)
        assertEquals(5.0, m.wasoMin, 1e-9)
        assertEquals(1, m.awakenings)
        // first REM epoch is 70 epochs after onset
        assertEquals(35.0, m.remLatencyMin!!, 1e-9)
        assertEquals(1, m.cycles)
    }

    @Test
    fun `metrics returns null without any sleep`() {
        assertNull(Sleep.metrics(List(20) { 4 }, inBedSFor(20)))
        assertNull(Sleep.metrics(emptyList(), 0.0))
    }

    @Test
    fun `metrics reports no REM latency when there is no REM`() {
        val stages = run(4 to 4, 1 to 20, 2 to 20)
        val m = Sleep.metrics(stages, inBedSFor(stages.size))!!
        assertNull(m.remLatencyMin)
        assertEquals(0, m.cycles)
    }

    @Test
    fun `deep is front-loaded and REM back-loaded in a realistic night`() {
        // deep concentrated in the first half, REM in the second — the normal pattern
        val stages = run(4 to 4, 1 to 40, 2 to 40, 2 to 40, 3 to 40)
        val m = Sleep.metrics(stages, inBedSFor(stages.size))!!
        assertEquals(100.0, m.deepFirstHalfPct!!, 1e-9)
        assertEquals(0.0, m.remFirstHalfPct!!, 1e-9)
    }

    @Test
    fun `fragmentation index counts stage transitions per asleep hour`() {
        // 120 epochs = 60 min asleep, alternating deep/light every 30 epochs -> 3 changes
        val stages = run(1 to 30, 2 to 30, 1 to 30, 2 to 30)
        val m = Sleep.metrics(stages, inBedSFor(stages.size))!!
        assertEquals(60.0, m.asleepMin, 1e-9)
        assertEquals(3.0, m.fragIndex, 1e-9)
    }

    @Test
    fun `asleepS counts only sleep stages`() {
        val stages = run(4 to 10, 1 to 10, 2 to 10, 3 to 10, 4 to 10)
        // 30 of 50 epochs are sleep, over a 25-minute in-bed window
        assertEquals(900, Sleep.asleepS(stages, inBedSFor(stages.size)))
    }

    @Test
    fun `autonomic means are grouped by stage via index fraction`() {
        // first half deep, second half REM; HR is 50 then 60
        val stages = run(1 to 10, 3 to 10)
        val hr = List(10) { 50.0 } + List(10) { 60.0 }
        val a = Sleep.autonomic(hr = hr, hrv = emptyList(), stages = stages)
        assertEquals(50.0, a.hrDeep!!, 1.0)
        assertEquals(60.0, a.hrRem!!, 1.0)
        assertNull(a.hrvDeep)
        assertTrue(a.any)
    }

    @Test
    fun `autonomic ignores non-positive samples`() {
        val stages = List(20) { 1 }
        val a = Sleep.autonomic(hr = List(20) { 0.0 }, hrv = emptyList(), stages = stages)
        assertNull(a.hrDeep)
        assertTrue(!a.any)
    }

    @Test
    fun `metrics and asleepS refuse a hypnogram with unstaged epochs`() {
        // Unknown (0) epochs would otherwise count as awake. Rust and Swift refuse too.
        val partial = List(100) { 2 } + List(20) { 0 }
        assertNull(Sleep.metrics(partial, 3600.0))
        assertEquals(0, Sleep.asleepS(partial, 3600.0))
    }

    @Test
    fun `autonomic places timed samples at their own timestamps`() {
        // 0–50 % of the night deep, 50–100 % REM. Both HR samples land in the first half
        // even though they're the first and last of the list — index-fraction would have
        // put the second one in REM.
        val stages = List(10) { 1 } + List(10) { 3 }
        val a = Sleep.autonomicTimed(
            hrTimed = listOf(listOf(1000.0, 50.0), listOf(1400.0, 60.0)),
            hrvTimed = listOf(listOf(1100.0, 40.0), listOf(1900.0, 80.0)),
            startUnix = 1000, endUnix = 2000, stages = stages,
        )
        assertEquals(55.0, a.hrDeep!!, 1e-9)
        assertNull(a.hrRem)
        assertEquals(40.0, a.hrvDeep!!, 1e-9)
        assertEquals(80.0, a.hrvRem!!, 1e-9)
    }

    @Test
    fun `autonomic drops timed samples outside the night`() {
        val a = Sleep.autonomicTimed(
            hrTimed = listOf(listOf(900.0, 99.0), listOf(1500.0, 50.0), listOf(2100.0, 99.0)),
            hrvTimed = emptyList(), startUnix = 1000, endUnix = 2000, stages = List(4) { 2 },
        )
        assertEquals(50.0, a.hrLight!!, 1e-9)
    }

    @Test
    fun `autonomic for a night prefers series_t, then the core's values, then index fraction`() {
        val stages = List(4) { 1 }
        val timed = NightRow(
            startUnix = 0, endUnix = 100,
            seriesT = NightTimedSeries(hr = listOf(listOf(50.0, 61.0))),
            autonomic = StageAutonomicJson(hrDeep = 70.0),
            series = NightSeries(hr = listOf(80.0, 80.0)),
        )
        assertEquals(61.0, Sleep.autonomic(timed, stages).hrDeep!!, 1e-9)
        val precomputed = timed.copy(seriesT = null)
        assertEquals(70.0, Sleep.autonomic(precomputed, stages).hrDeep!!, 1e-9)
        val legacy = precomputed.copy(autonomic = null)
        assertEquals(80.0, Sleep.autonomic(legacy, stages).hrDeep!!, 1e-9)
    }

    @Test
    fun `timedSamples places samples on the night window and drops outsiders`() {
        val t = Sleep.timedSamples(
            listOf(listOf(900.0, 1.0), listOf(1000.0, 2.0), listOf(1500.0, 3.0), listOf(2000.0, 4.0), listOf(2001.0, 5.0)),
            startUnix = 1000, endUnix = 2000,
        )!!
        assertEquals(listOf(0.0, 0.5, 1.0), t.map { it.fraction })
        assertNull(Sleep.timedSamples(emptyList(), 1000, 2000))
        assertNull(Sleep.timedSamples(listOf(listOf(1500.0, 1.0)), 2000, 1000)) // inverted window
    }

    @Test
    fun `contiguousSegments breaks the line only on gaps longer than 15 minutes`() {
        fun at(u: Double) = TimedSample(u, 0.0, 1.0)
        val segs = Sleep.contiguousSegments(listOf(at(0.0), at(900.0), at(1801.0), at(1900.0)))
        // 0→900 is exactly the limit (kept together); 900→1801 is one second over.
        assertEquals(listOf(2, 2), segs.map { it.size })
    }

    @Test
    fun `sampleAt reads the nearest sample, or nothing inside a gap`() {
        val samples = listOf(TimedSample(0.0, 0.0, 50.0), TimedSample(0.0, 1.0, 70.0))
        val night = 8 * 3600.0
        assertEquals(50.0, Sleep.sampleAt(0.01, samples, night)!!, 1e-9) // ~5 min away
        assertNull(Sleep.sampleAt(0.5, samples, night)) // 4 h from either sample
    }
}
