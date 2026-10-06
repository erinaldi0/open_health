package md.thomas.openoura.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DayGroupsTest {

    @Test
    fun `days are grouped by month in their given order`() {
        val groups = dayGroups(listOf("2026-03-02", "2026-03-01", "2026-02-28", "oops"), Locale.US)
        assertEquals(listOf("March 2026", "February 2026", "undated"), groups.map { it.first })
        assertEquals(listOf("2026-03-02", "2026-03-01"), groups[0].second)
    }
}
