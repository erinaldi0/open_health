package md.thomas.openoura.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import md.thomas.openoura.data.Sleep
import md.thomas.openoura.data.SummaryJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin twins of tools/nssa_inputs.py, tools/ibi_correction.py and the 5-minute
 * aggregation must match the Python reference exactly. The fixture is synthetic
 * (tools/make_nssa_fixture.py, fixed seed) and checked on the Python side too.
 * Port of apps/ios/OuraApp/Tests/NssaParityTests.swift — the resource-fixture runs in
 * the `full` JVM test flavor (no device or torch needed).
 */
class NssaParityTest {

    private fun fixture(): JsonObject {
        val txt = javaClass.classLoader!!.getResourceAsStream("NssaParityFixture.json")!!
            .bufferedReader().readText()
        return SummaryJson.parseToJsonElement(txt) as JsonObject
    }

    private fun ints(v: Any?): List<Int> = (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.intOrNull } ?: emptyList()
    private fun bytes(v: Any?): ByteArray = ints(v).map { it.toByte() }.toByteArray()
    private fun doubles(v: Any?): List<Double> =
        (v as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: emptyList()

    private fun arr(obj: JsonObject, key: String): List<Any> {
        val e = obj[key] as? JsonArray ?: return emptyList()
        return e.map { it as Any }
    }

    @Test
    fun acmPeriodDecodeIsBitExact() {
        val cases = arr(fixture(), "acm").map { it as JsonObject }
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val got = NssaInputs.decodeAcmPeriod(bytes(c["body"]))!!
            val want = doubles(c["values"]).map { it.toFloat() }
            assertEquals(got.map { it.toRawBits() }, want.map { it.toRawBits() })
        }
        assertNull(NssaInputs.decodeAcmPeriod(ByteArray(11)))
    }

    @Test
    fun sleepTempExpansion() {
        for (c in arr(fixture(), "temp").map { it as JsonObject }) {
            val got = NssaInputs.expandTempEvent(bytes(c["body"]), c["event_ms"]!!.jsonPrimitive.longOrNull!!)
            val want = arr(c, "samples").map { it as JsonArray }
            assertEquals(want.map { it[0].jsonPrimitive.longOrNull!! }, got.map { it.first })
            assertEquals(
                want.map { it[1].jsonPrimitive.doubleOrNull!!.toFloat().toRawBits() },
                got.map { it.second.toRawBits() },
            )
        }
    }

    @Test
    fun ibiExpansionClampAndWindow() {
        val f = fixture()
        var last = 0L
        val all = ArrayList<Triple<Long, Int, Int>>()
        for (c in arr(f, "ibi_events").map { it as JsonObject }) {
            val before = all.size
            last = NssaInputs.expandIbiEvent(
                ints(c["ibis"]), ints(c["amps"]), c["event_ms"]!!.jsonPrimitive.longOrNull!!, last,
                all,
            )
            val want = arr(c, "beats").map { it as JsonArray }
            val added = all.subList(before, all.size)
            assertEquals(want.map { it[0].jsonPrimitive.longOrNull!! }, added.map { it.first })
            assertEquals(want.map { it[1].jsonPrimitive.intOrNull!! }, added.map { it.second })
            assertEquals(want.map { it[2].jsonPrimitive.intOrNull!! }, added.map { it.third })
        }
        for (w in arr(f, "ibi_windows").map { it as JsonObject }) {
            val kept = NssaInputs.inclusiveWindow(
                all, w["start"]!!.jsonPrimitive.longOrNull!!, w["end"]!!.jsonPrimitive.longOrNull!!,
            ) { it.first }
            assertEquals(w["kept"]!!.jsonPrimitive.intOrNull!!, kept.size)
            assertEquals(w["first"]?.jsonPrimitive?.longOrNull, kept.firstOrNull()?.first)
        }
    }

    @Test
    fun ibiCorrectionMatchesPythonPort() {
        val streams = arr(fixture(), "ibi_correction").map { it as JsonObject }
        assertEquals(6, streams.size)
        for (s in streams) {
            val raw = arr(s, "raw").map { it as JsonArray }
                .map { Triple(it[0].jsonPrimitive.longOrNull!!, it[1].jsonPrimitive.intOrNull!!, it[2].jsonPrimitive.intOrNull!!) }
            val want = arr(s, "corrected").map { it as JsonArray }
                .map { listOf(it[0].jsonPrimitive.longOrNull!!, it[1].jsonPrimitive.longOrNull!!, it[2].jsonPrimitive.longOrNull!!, it[3].jsonPrimitive.longOrNull!!) }
            val got = IbiCorrection.correct(raw).map { listOf(it.ts, it.ibi.toLong(), it.amp.toLong(), it.validity.toLong()) }
            assertEquals(want, got)
        }
    }

    @Test
    fun fiveMinuteAggregation() {
        for (c in arr(fixture(), "aggregate_5min").map { it as JsonObject }) {
            assertEquals(ints(c["five_min"]), Sleep.aggregate5Min(ints(c["stages"])))
        }
        assertEquals(listOf(4), Sleep.aggregate5Min(listOf(2, 2, 2, 2, 2, 2, 4, 4, 4, 4)))
        assertEquals(listOf(1), Sleep.aggregate5Min(listOf(2, 2, 2, 1, 1, 1, 4, 4, 3, 3)))
        assertTrue(Sleep.isOuraStaging("nssa"))
        assertFalse(Sleep.isOuraStaging("signals"))
    }

    @Test
    fun gridMappingMirrorsRunner() {
        // start 23:34:30.5 style offset: epochs on floor30 map to the cell holding their midpoint.
        val start = 1_000_500L
        val end = start + 5 * 30_000 + 1_000
        val ts = longArrayOf(1_000_000, 1_030_000, 1_060_000, 1_090_000)
        val grid = NssaInputs.gridStages(
            listOf(ts to listOf(1, 2, 3, 1)), emptyList(), start, end,
        )
        assertEquals(listOf(1, 2, 3, 1, 4, 4), grid)
        val failed = NssaInputs.gridStages(
            listOf(ts to listOf(1, 2, 3, 1)), listOf(1_120_000L to 1_200_000L), start, end,
        )
        assertEquals(listOf(1, 2, 3, 1, 0, 0), failed)
        // an epoch ending before cell 0's midpoint is not painted; 10 s before still is
        assertEquals(
            listOf(4),
            NssaInputs.gridStages(listOf(longArrayOf(980_000) to listOf(3)), emptyList(), 1_000_000L, 1_030_000L),
        )
        assertEquals(
            listOf(3),
            NssaInputs.gridStages(listOf(longArrayOf(990_000) to listOf(3)), emptyList(), 1_000_000L, 1_030_000L),
        )
    }

    @Test
    fun demographicsMatchStager() {
        val d = NssaInputs.demographics(age = 30.9, heightM = 1.78, weightKg = 75.0, sex = "M")
        assertEquals(30f, d[0])
        assertEquals((75.0 / (1.78 * 1.78)).toFloat().toRawBits(), d[1].toRawBits())
        assertEquals(1f, d[2])
        assertEquals(-1f, NssaInputs.demographics(40.0, 1.6, 60.0, "F")[2])
        assertEquals(0f, NssaInputs.demographics(40.0, 1.6, 60.0, "O")[2])
    }
}
