package md.thomas.openoura.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDataTest {

    @Test
    fun `parses counts and events, extracting numeric fields and arrays`() {
        val page = RawData.parse(
            """{"counts":[{"name":"ibi_event","total":10,"decoded":8}],
               "events":[{"name":"ibi_event","tag":96,"ring_timestamp":5,"captured_unix":100,"unix_s":99.5,"body_len":12,
                          "decoded":{"ibi_ms":[812,806],"quality":3,"label":"x"}},
                         {"name":"ibi_event","tag":96,"ring_timestamp":4,"captured_unix":100,"body_len":7}]}"""
        )
        assertEquals(8, page.kinds.single().decoded)
        assertEquals(listOf("ibi_ms", "quality"), page.fields) // strings aren't plotted
        assertEquals(listOf(812.0, 806.0), page.events[0].numbers["ibi_ms"])
        assertEquals(100.0, page.events[1].unix, 1e-9) // falls back to captured_unix
        assertTrue(page.events[1].prettyJson.startsWith("not decoded"))
    }

    @Test
    fun `points run oldest to newest and thin long series to the envelope`() {
        fun ev(t: Double, vararg v: Double) = RawData.Event("e", 0, 0, 0, t, 0, mapOf("f" to v.toList()), "")
        // events arrive newest first
        val pts = RawData.points(listOf(ev(20.0, 5.0), ev(10.0, 1.0, 2.0)), "f")
        assertEquals(listOf(1.0, 2.0, 5.0), pts.map { it.v })

        val many = (0 until 5000).map { ev(5000.0 - it, if (it == 2500) 999.0 else 1.0) }
        val thinned = RawData.points(many, "f", maxPoints = 100)
        assertTrue(thinned.size <= 102)
        assertTrue(thinned.any { it.v == 999.0 }) // a spike survives thinning
    }
}
