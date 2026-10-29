package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gen-4 sleep-staging recovery helpers (mirrored from iOS `Reports.swift`). SleepNet
 * stamps each epoch with its END time, so `alignedStages` must place it on the true 30 s
 * grid rather than stretching the output — the bug that made every night look incomplete.
 * `estimateStages` is the signal-only fallback for nights with no usable raw beats.
 */
class SleepStagingTest {

    private val start = 1_772_000_000_000L

    @Test
    fun `alignedStages places end-stamped epochs on the true grid`() {
        val ts = listOf(start + 30_000, start + 60_000, start + 90_000)
        assertEquals(
            listOf(1, 2, 3),
            Sleep.alignedStages(ts, listOf(1, 2, 3), start, start + 90_000),
        )
    }

    @Test
    fun `alignedStages drops epochs at or before bedtime start`() {
        val ts = listOf(start, start + 30_000, start + 60_000)
        // The first stamp equals startMs (invalid → 0); the other two land on 0 and 1.
        assertEquals(
            listOf(2, 3),
            Sleep.alignedStages(ts, listOf(1, 2, 3), start, start + 60_000),
        )
    }

    @Test
    fun `alignedStages rejects mismatched sizes`() {
        assertNull(Sleep.alignedStages(listOf(start + 30_000), listOf(1, 2), start, start + 90_000))
    }

    @Test
    fun `alignedStages rejects a stage code outside the 1 to 4 range`() {
        assertNull(Sleep.alignedStages(listOf(start + 30_000), listOf(0), start, start + 90_000))
    }

    @Test
    fun `alignedStages rejects non-monotonic timestamps`() {
        assertNull(
            Sleep.alignedStages(listOf(start + 60_000, start + 30_000), listOf(2, 1), start, start + 90_000),
        )
    }

    @Test
    fun `estimateStages returns a full-length hypnogram from overnight signals`() {
        val stages = Sleep.estimateStages(syntheticNight())
        assertNotNull(stages)
        assertEquals(120, stages!!.size)
        assertTrue(stages.all { it in 1..4 })
    }

    private fun syntheticNight(): NightRow {
        val t0 = 1_772_000_000L
        val hr = (0 until 60).map { listOf((t0 + it * 60).toDouble(), 65.0 - it * 0.2) }
        val hrv = (0 until 60).map { listOf((t0 + it * 60).toDouble(), 30.0 + it * 0.4) }
        val motion = (0 until 60).map { listOf((t0 + it * 60).toDouble(), 0.5) }
        return NightRow(
            startUnix = t0,
            endUnix = t0 + 3600,
            seriesT = NightTimedSeries(hr = hr, hrv = hrv, motion = motion),
        )
    }
}
