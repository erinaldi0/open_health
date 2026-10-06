package md.thomas.openoura.ui.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateSmootherTest {

    private fun HeartRateSmoother.feed(vararg ibis: Int) = ibis.forEach { add(it) }

    @Test
    fun `stays silent until five seconds of beats are in`() {
        val s = HeartRateSmoother()
        repeat(4) { s.add(1000) } // 4 s
        assertNull(s.bpm)
        s.add(1000) // 5 s
        assertEquals(60, s.bpm)
    }

    @Test
    fun `averages IBIs, not per-beat bpm`() {
        // 700/900 ms alternating (normal breathing-driven swing): mean IBI 800 ms = 75 bpm.
        // Averaging the per-beat bpm (85.7 and 66.7) would say 76.
        val s = HeartRateSmoother()
        repeat(6) { s.feed(700, 900) }
        assertEquals(75, s.bpm)
    }

    @Test
    fun `a missed beat is rejected and does not move the reading`() {
        val s = HeartRateSmoother()
        repeat(8) { s.add(1000) }
        assertFalse(s.add(2000)) // ring missed a beat: IBI doubled
        assertFalse(s.add(500))  // or split one in two
        assertEquals(60, s.bpm)
    }

    @Test
    fun `window holds only the last ten seconds`() {
        val s = HeartRateSmoother()
        repeat(15) { s.add(1000) }
        assertEquals(10, s.beatsInWindow)
    }

    @Test
    fun `a sustained real change is adopted, not rejected forever`() {
        val s = HeartRateSmoother()
        repeat(10) { s.add(1000) } // 60 bpm
        // Stand up: jump to 600 ms (100 bpm). The first few look like outliers, but once
        // they dominate the raw median they are accepted.
        val accepted = (1..30).count { s.add(600) }
        assertTrue(accepted >= 25)
        assertEquals(100, s.bpm)
    }
}
