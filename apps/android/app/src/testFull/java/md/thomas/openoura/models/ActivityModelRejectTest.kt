package md.thomas.openoura.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ActivityModel.isDegenerate` is the mirror of the AAD model's `get_last_valid_time`
 * reject rule, shared with `tools/run_activity_model.py::model_would_reject` and iOS
 * `ActivityModel.isDegenerate`. A degenerate day must yield no sessions instead of
 * aborting the whole run, so the boundary cases are pinned here.
 */
class ActivityModelRejectTest {

    private fun mat(rows: List<FloatArray>): ActivityModel.Matrix {
        val cols = rows.firstOrNull()?.size ?: 0
        val flat = FloatArray(rows.size * cols)
        rows.forEachIndexed { i, r -> r.copyInto(flat, i * cols) }
        return ActivityModel.Matrix(flat, rows.size)
    }

    private fun met(startMinute: Float) =
        mat((0..12).map { floatArrayOf(startMinute + it, 1.2f) })

    private val nan = Float.NaN

    /** Motion stops after two minutes, so fewer than 10 MET minutes count as worn. */
    private val fewMotion = mat(
        listOf(
            floatArrayOf(600f, 0f, 5f, 0f, 0f, 0f, nan, 1f, 0f),
            floatArrayOf(601f, 0f, 5f, 0f, 0f, 0f, nan, 1f, 0f),
        )
    )

    @Test
    fun `a day whose valid window collapses to minute zero while MET starts at 600 is rejected`() {
        assertTrue(
            ActivityModel.isDegenerate(
                met(600f), fewMotion,
                mat(listOf(floatArrayOf(600f, 33f))),
                mat(listOf(floatArrayOf(600f, 60f))),
            )
        )
    }

    @Test
    fun `the same data starting at midnight is accepted`() {
        assertFalse(
            ActivityModel.isDegenerate(
                met(0f), fewMotion,
                mat(listOf(floatArrayOf(0f, 33f))),
                mat(listOf(floatArrayOf(0f, 60f))),
            )
        )
    }

    @Test
    fun `a full day with every channel covering the span is accepted`() {
        val fullMotion = mat((0..12).map { floatArrayOf(600f + it, 0f, 30f, 0f, 0f, 0f, nan, 10f, 1f) })
        assertFalse(
            ActivityModel.isDegenerate(
                met(600f), fullMotion,
                mat(listOf(floatArrayOf(612f, 33f))),
                mat(listOf(floatArrayOf(612f, 60f))),
            )
        )
    }
}
