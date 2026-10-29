package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Refresh analysis" replacements, duplicated from the iOS refresh path in
 * Core.swift: `replacingNight` (sleep) and `replacingWorkouts` (activity). They decide
 * WHICH night or sessions a rerun lands on, so the pairing rules get pinned here.
 */
class DayAnalysisRefreshTest {

    private fun night(
        startDs: Long,
        rawStartDs: Long? = null,
        ymd: String = "2026-03-02",
        inBedH: Double = 7.9,
    ) = NightRow(ymd = ymd, startDs = startDs, rawStartDs = rawStartDs, inBedH = inBedH)

    @Test
    fun `replacingNight swaps the night at the same start_ds`() {
        val current = night(1000)
        val rerun = night(1000).copy(deepPct = 55.0)
        val out = Summary(nights = listOf(current)).replacingNight("2026-03-02", rerun)
        assertEquals(1, out.nights.size)
        assertEquals(55.0, out.nights[0].deepPct!!, 1e-9)
    }

    @Test
    fun `replacingNight falls back to the previous start when the bedtime moved`() {
        // The old summary carried the night at start 1000; the rerun re-windowed it to 950.
        val moved = night(950, rawStartDs = 60)
        val out = Summary(nights = listOf(night(1000))).replacingNight("2026-03-02", moved)
        assertEquals(1, out.nights.size)
        assertEquals(950L, out.nights[0].startDs!!)
    }

    @Test
    fun `replacingNight falls back to the raw start and appends when unmatched`() {
        val current = night(1000)
        // The rerun's start is unknown but its raw ring bedtime matches a held night.
        val rerun = night(0, rawStartDs = 60)
        val out = Summary(nights = listOf(current)).replacingNight("2026-03-02", rerun)
        assertEquals(1, out.nights.size)
        assertEquals(0L, out.nights[0].startDs!!)

        // Nothing matches at all: the night is appended, not lost. A day with no prior
        // night means previousStart is null, so a foreign night can't steal the slot.
        val other = Summary(nights = emptyList()).replacingNight("2026-03-02", night(5000, rawStartDs = 999))
        assertEquals(1, other.nights.size)
        assertEquals(5000L, other.nights[0].startDs!!)
    }

    @Test
    fun `replacingWorkouts swaps one day and keeps the rest sorted`() {
        val older = WorkoutSession("2026-03-01 18:00", "19:00", 60, "cycling", 1.0)
        val oldRun = WorkoutSession("2026-03-02 19:00", "20:00", 60, "running", 1.0)
        val newRun = WorkoutSession("2026-03-02 07:30", "08:30", 60, "swimming", 1.0)
        val s = Summary(
            workouts = listOf(oldRun, older),
            modelErrors = listOf("Activity analysis failed for 2026-03-02. See diagnostics for details."),
        )
        val out = s.replacingWorkouts("2026-03-02", listOf(newRun))
        assertEquals(listOf(older, newRun), out.workouts)
        assertTrue(out.modelErrors.isEmpty())
    }

    @Test
    fun `replacingWorkouts keeps unrelated failure messages`() {
        val other = WorkoutSession("2026-03-03 12:00", "13:00", 60, "yoga", 1.0)
        val s = Summary(
            workouts = listOf(other),
            modelErrors = listOf(
                "Activity analysis failed for 2026-03-03.",
                "sleep model file missing from the app assets",
            ),
        )
        val out = s.replacingWorkouts("2026-03-02", emptyList())
        assertEquals(listOf(other), out.workouts)
        assertEquals(listOf("sleep model file missing from the app assets"), out.modelErrors)
    }
}