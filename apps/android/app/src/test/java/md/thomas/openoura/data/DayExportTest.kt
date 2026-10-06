package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DayExportTest {

    private val night = NightRow(
        ymd = "2026-03-01", wakeYmd = "2026-03-02", start = "23:00", end = "07:00",
        startUnix = 0, endUnix = 8 * 3600, inBedH = 8.0, stagesFull = List(960) { 2 },
    )
    private val s = Summary(nights = listOf(night), activityProfile = mapOf("2026-03-02" to List(96) { 0.5 }))

    @Test
    fun `the sleep export carries the night in core keys plus recomputed metrics`() {
        val e = DayExport.build(s, "2026-03-02", sleep = true, appVersion = "t", now = Instant.EPOCH, zone = ZoneOffset.UTC)
        assertNull(e.activity)
        assertEquals(480.0, e.sleep!!.metrics!!.asleep_min, 1e-9)
        val json = e.json()
        // snake_case, diffable against the web's /api/summary
        assertTrue(json.contains("\"wake_ymd\": \"2026-03-02\""))
        assertTrue(json.contains("\"asleep_min\""))
        assertEquals("oura-2026-03-02-sleep.json", e.fileName)
    }

    @Test
    fun `the activity export carries the day's profile and nothing from sleep`() {
        val e = DayExport.build(s, "2026-03-02", sleep = false, appVersion = "t")
        assertNull(e.sleep)
        assertEquals(96, e.activity!!.profile_met.size)
    }

    @Test
    fun `a day without a night exports nothing for sleep`() {
        assertTrue(DayExport.build(s, "2026-01-01", sleep = true, appVersion = "t").isEmpty)
    }
}
