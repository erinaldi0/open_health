package md.thomas.openoura.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyHRTest {

    @Test
    fun `parses hours and the latest reading, filling optional fields`() {
        val r = HourlyHR.parse(
            """{"hours":[{"unix":3600,"ymd":"2026-03-01","hour":1,"low":50,"high":70},
                         {"unix":7200,"ymd":"2026-03-01","hour":2,"low":55,"high":65,"median":58,"min":40,"max":90,"count":120},
                         {"unix":"bad"}],
               "latest":{"bpm":61,"unix":7300}}"""
        )
        assertEquals(2, r.bars.size)
        assertEquals(60.0, r.bars[0].median, 1e-9) // midpoint when median is missing
        assertEquals(50.0, r.bars[0].min, 1e-9)
        assertEquals(120, r.bars[1].count)
        assertEquals(61.0, r.latest!!.bpm, 1e-9)
    }

    @Test
    fun `an error object or junk becomes an error result`() {
        assertEquals("no db", HourlyHR.parse("""{"error":"no db"}""").error)
        assertEquals("unreadable response", HourlyHR.parse("not json").error)
        assertNull(HourlyHR.parse("""{"hours":[]}""").error)
    }

    @Test
    fun `the window anchors on the newest hour and keeps gaps as empty slots`() {
        fun bar(h: Int) = HourlyHR.Bar(h * 3600.0, "", h, 1.0, 2.0, 1.5, 1.0, 2.0, 1)
        val bars = listOf(bar(0), bar(10), bar(30), bar(40))
        assertEquals(listOf(30, 40), HourlyHR.visible(bars, HourlyWindow.H24).map { it.hour })
        val slots = HourlyHR.slots(bars, HourlyWindow.H24)
        assertEquals(24, slots.size)
        assertEquals(40 * 3600.0, slots.last(), 1e-9)
        assertEquals(17 * 3600.0, slots.first(), 1e-9)
    }
}
