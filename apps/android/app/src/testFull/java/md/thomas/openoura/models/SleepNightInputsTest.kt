package md.thomas.openoura.models

import kotlinx.serialization.json.JsonObject
import md.thomas.openoura.data.EventStore
import md.thomas.openoura.data.SummaryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SleepStaging.nightInputs` is the port of `tools/run_sleep_model.py`'s input assembly —
 * the Gen-4 fixes (0x60 tail-noise gating, 0x80 quality gating, 0x60/0x80 merge dedup, 0x5d
 * synthetic beats, 0x75-over-0x46 temperature) live here. It has no device dependencies, so
 * it is the cheapest place to pin the behavior.
 */
class SleepNightInputsTest {

    private val base = 1_772_000_000L
    private val startDs = 1000L
    private val endDs = 37_000L // one hour of ds (10 ds per second)

    private fun j(s: String) = SummaryJson.parseToJsonElement(s) as JsonObject

    private fun ev(ds: Long, tag: Int, json: JsonObject?) =
        EventStore.Ev(ds, tag, json, base + (ds - startDs) / 10, null)

    private fun timeSync() =
        EventStore.Ev(startDs, 0x42, j("""{"unix_time":$base}"""), base, null)

    private fun inputs(events: List<EventStore.Ev>): SleepStaging.NightInputs? {
        val all = listOf(timeSync()) + events
        return SleepStaging.nightInputs(startDs, endDs, base, all, EventStore.RingClock(all))
    }

    @Test
    fun `0x60 tail noise beats are invalidated`() {
        val e = ev(2000, 0x60, j("""{"ibi_ms":[1000,1000,500,450],"amplitude":[1200,1200,0,0]}"""))
        val ni = inputs(listOf(e))
        assertNotNull(ni)
        assertEquals(4, ni!!.beats.size)
        assertEquals(listOf(1f, 1f, 0f, 0f), ni.beats.map { it.valid })
    }

    @Test
    fun `0x80 beats require quality 1`() {
        val e = ev(2000, 0x80, j("""{"ibi_ms":[1000,1000,1000],"amplitude":[1000,1000,1000],"quality":[1,0,1]}"""))
        val ni = inputs(listOf(e))
        assertNotNull(ni)
        assertEquals(listOf(1f, 0f, 1f), ni!!.beats.map { it.valid })
    }

    @Test
    fun `0x80 duplicate of a 0x60 beat within 1-point-5 s is dropped`() {
        val a = ev(2000, 0x60, j("""{"ibi_ms":[1000],"amplitude":[1200]}"""))
        val b = ev(2005, 0x80, j("""{"ibi_ms":[1000],"amplitude":[900],"quality":[1]}"""))
        val ni = inputs(listOf(a, b))
        assertNotNull(ni)
        assertEquals(1, ni!!.beats.size)
        assertEquals(1200f, ni.beats[0].amplitude, 0.001f)
    }

    @Test
    fun `0x75 temperature is preferred over 0x46 and positives are averaged`() {
        val a = ev(2000, 0x75, j("""{"temps_c":[35.5,33.0,0.0]}"""))
        val b = ev(2100, 0x46, j("""{"temps_c":[20.0]}"""))
        val c = ev(2200, 0x60, j("""{"ibi_ms":[1000],"amplitude":[1200]}"""))
        val ni = inputs(listOf(a, b, c))
        assertNotNull(ni)
        assertEquals(1, ni!!.temp.size)
        assertEquals(34.25f, ni.temp[0].v, 0.001f)
    }

    @Test
    fun `synthetic beats are built from 0x5d when no raw beats exist`() {
        val e = ev(2000, 0x5d, j("""{"hr_bpm":[60],"interval_min":1,"rmssd_ms":[30]}"""))
        val ni = inputs(listOf(e))
        assertNotNull(ni)
        assertTrue(ni!!.beats.size in 55..65)
        assertTrue(ni.beats.all { it.valid == 1f })
    }

    @Test
    fun `no usable beats and no HRV yields null`() {
        assertNull(inputs(emptyList()))
    }
}
