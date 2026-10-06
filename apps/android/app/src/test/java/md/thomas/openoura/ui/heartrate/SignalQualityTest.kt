package md.thomas.openoura.ui.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patterns modelled on the Ring 5 captures SignalQuality was calibrated on: a good lock
 * reports every beat; weak contact lets ~1 in 5 through.
 */
class SignalQualityTest {

    /** Feed beats of [ibiMs] for [seconds], keeping only every [keepEvery]-th; returns end time. */
    private fun SignalQuality.stream(fromMs: Long, seconds: Int, ibiMs: Int, keepEvery: Int = 1): Long {
        var t = fromMs
        var i = 0
        while (t < fromMs + seconds * 1000L) {
            t += ibiMs
            if (i++ % keepEvery == 0) addBeat(t, ibiMs)
        }
        return t
    }

    @Test
    fun `no verdict in the first five seconds`() {
        val q = SignalQuality()
        q.addBeat(0, 800)
        assertNull(q.coverage(4_000))
        assertFalse(q.isWeak(4_000))
    }

    @Test
    fun `a good lock is not weak`() {
        val q = SignalQuality()
        val end = q.stream(0, 30, ibiMs = 720)
        assertEquals(1.0, q.coverage(end)!!, 0.06)
        assertFalse(q.isWeak(end))
    }

    @Test
    fun `weak contact - one beat in five getting through - is weak`() {
        val q = SignalQuality()
        val end = q.stream(0, 30, ibiMs = 800, keepEvery = 5)
        assertTrue(q.coverage(end)!! < 0.3)
        assertTrue(q.isWeak(end))
    }

    @Test
    fun `beats stopping altogether turns weak without any new beat`() {
        val q = SignalQuality()
        val end = q.stream(0, 20, ibiMs = 800)
        assertFalse(q.isWeak(end))
        assertTrue(q.isWeak(end + 10_000)) // only polled, nothing added
    }

    @Test
    fun `recovers once the lock comes back`() {
        val q = SignalQuality()
        var t = q.stream(0, 20, ibiMs = 800, keepEvery = 5)
        assertTrue(q.isWeak(t))
        t = q.stream(t, 15, ibiMs = 800)
        assertFalse(q.isWeak(t))
    }

    @Test
    fun `a BLE burst cannot push coverage above one`() {
        val q = SignalQuality()
        repeat(10) { q.addBeat(0, 800) }
        repeat(30) { q.addBeat(6_000, 800) } // 24 s of beats delivered at once
        assertEquals(1.0, q.coverage(6_000)!!, 0.0)
    }
}
