package md.thomas.openoura.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Port of the web dashboard's `batteryChart` axis maths (app.js). A full battery is the
 * top of the scale but the bottom follows the data, and the line splits into one segment
 * per run of the same direction so charging stretches can be drawn apart from draining ones.
 */
class BatteryChartTest {

    @Test
    fun floorFollowsReadings() {
        val pts = listOf(0.0 to 82.0, 86_400.0 to 85.0, 172_800.0 to 88.0)
        val (t0, lo, segs) = batteryAxis(pts)
        assertEquals(0.0, t0, 1e-9)
        // min = 82 -> floor(8.2) * 10 = 80 -> - 5 = 75
        assertEquals(75.0, lo, 1e-9)
        assertEquals(listOf(BatterySeg(up = true, from = 0, to = 2)), segs)
    }

    @Test
    fun floorNeverGoesNegative() {
        val pts = listOf(0.0 to 2.0, 86_400.0 to 6.0)
        val (_, lo, _) = batteryAxis(pts)
        assertEquals(0.0, lo, 1e-9)
    }

    @Test
    fun flatStretchCountsAsDownRunAndChargeSplitsOff() {
        // 50, 50 is flat (not up); 50 -> 60 is a charge, so it must start its own segment.
        val pts = listOf(0.0 to 50.0, 86_400.0 to 50.0, 172_800.0 to 60.0)
        val (_, _, segs) = batteryAxis(pts)
        assertEquals(
            listOf(BatterySeg(up = false, from = 0, to = 1), BatterySeg(up = true, from = 1, to = 2)),
            segs,
        )
    }

    @Test
    fun turningPointBelongsToBothNeighbouringSegments() {
        val pts = listOf(
            0.0 to 80.0, 86_400.0 to 88.0, 172_800.0 to 84.0, 259_200.0 to 81.0, 345_600.0 to 86.0,
        )
        val (_, _, segs) = batteryAxis(pts)
        assertEquals(
            listOf(
                BatterySeg(up = true, from = 0, to = 1),
                BatterySeg(up = false, from = 1, to = 3),
                BatterySeg(up = true, from = 3, to = 4),
            ),
            segs,
        )
    }
}