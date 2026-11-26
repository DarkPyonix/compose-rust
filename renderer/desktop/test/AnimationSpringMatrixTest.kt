package dev.darkpyonix.composerust.test

import androidx.compose.animation.core.FloatSpringSpec
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.ui.node.Matrix3DInterpolation
import dev.darkpyonix.composerust.ui.node.SpringTiming
import dev.darkpyonix.composerust.ui.node.WebAnimationTiming
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spring timing function and the interpolation of 3D matrices, as tables. The spring is
 * compared with Compose's own spring; the matrices with values worked out from CSS Transforms
 * Level 2 by hand.
 */
class AnimationSpringMatrixTest {

    /**
     * (stiffness, damping ratio, settle time in ms): critically damped, under-damped and
     * over-damped springs. The Host's `AnimationSpec::spring` is checked against the same
     * table, so the length it sends and the curve the Renderer plays agree.
     */
    private val settleTable = listOf(
        Triple(1500f, 1f, 238.4053),
        Triple(1500f, 0.5f, 364.1429),
        Triple(400f, 0.75f, 488.0730),
        Triple(200f, 0.2f, 2449.4766),
        Triple(10_000f, 1f, 92.3340),
        Triple(50f, 2f, 3685.1787),
    )

    @Test
    fun fr41_spring_settle_time_matches_the_host_table() {
        for ((stiffness, dampingRatio, settleMs) in settleTable) {
            val settle = SpringTiming.settleMs(stiffness, dampingRatio)
            assertTrue(
                abs(settle - settleMs) < 1.0,
                "spring($stiffness, $dampingRatio) settles in $settle ms, not $settleMs",
            )
            // Compose's own estimate, which rounds down to whole milliseconds.
            val compose = FloatSpringSpec(dampingRatio, stiffness, SpringTiming.THRESHOLD.toFloat())
                .getDurationNanos(0f, 1f, 0f) / 1_000_000.0
            assertTrue(
                abs(settle - compose) < 1.0,
                "spring($stiffness, $dampingRatio) settles in $settle ms, Compose says $compose",
            )
        }
    }

    /**
     * The eased value at progress u is where Compose's spring is at u × T. Compose rounds its
     * time down to a whole millisecond, so the samples are taken at whole milliseconds. The
     * critically damped spring rises without passing 1; the under-damped one passes 1.
     */
    @Test
    fun fr41_spring_easing_matches_compose_spring() {
        for ((stiffness, dampingRatio, _) in settleTable) {
            val timing = Timing.Spring(stiffness, dampingRatio)
            val settle = SpringTiming.settleMs(stiffness, dampingRatio)
            val compose = FloatSpringSpec(dampingRatio, stiffness)
            assertEquals(0f, WebAnimationTiming.ease(timing, 0f, false), "spring($stiffness, $dampingRatio) at 0")
            assertEquals(1f, WebAnimationTiming.ease(timing, 1f, false), "spring($stiffness, $dampingRatio) at 1")
            var ms = 1L
            while (ms < settle) {
                val u = (ms / settle).toFloat()
                val eased = WebAnimationTiming.ease(timing, u, false, springMs = settle)
                // The progress is a float, so the time it stands for is within a hair of `ms`.
                val expected = compose.getValueFromNanos(ms * 1_000_000L, 0f, 1f, 0f)
                val exact = SpringTiming.position(stiffness, dampingRatio, u * settle / 1000.0).toFloat()
                assertTrue(
                    abs(exact - expected) < 0.001f && abs(eased - exact) < 1e-5f,
                    "spring($stiffness, $dampingRatio) at $ms ms: $eased, Compose $expected",
                )
                ms += maxOf(1L, (settle / 40).toLong())
            }
        }

        val critical = Timing.Spring(1500f, 1f)
        var previous = 0f
        for (step in 1..100) {
            val value = WebAnimationTiming.ease(critical, step / 100f, false)
            assertTrue(value >= previous, "critically damped spring goes back at ${step / 100f}")
            assertTrue(value <= 1f, "critically damped spring passes 1 at ${step / 100f}")
            previous = value
        }
        val bouncy = Timing.Spring(1500f, 0.5f)
        assertTrue(
            (1..99).any { WebAnimationTiming.ease(bouncy, it / 100f, false) > 1f },
            "a damping ratio of 0.5 overshoots",
        )
    }

    private fun rotateY(degrees: Double): FloatArray {
        val radians = degrees * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private fun assertMatrix(expected: FloatArray, actual: FloatArray, what: String) {
        for (cell in 0 until 16) {
            assertTrue(
                abs(expected[cell] - actual[cell]) < 1e-4f,
                "$what, cell $cell: expected ${expected.toList()}, was ${actual.toList()}",
            )
        }
    }

    /**
     * Half way from rotateY(0) to rotateY(90deg) is rotateY(45deg). From the identity to
     * perspective(200px) translate3d(20px, -10px, 0) scale3d(2, 3, 0.5), the parts are
     * interpolated one by one: the perspective term -1/200 halves to -1/400, the translation
     * and the scale meet half way, so the middle is perspective(400px)
     * translate3d(10px, -5px, 0) scale3d(1.5, 2, 0.75). A matrix with no inverse cannot be
     * taken apart, and the value jumps from one end to the other at 0.5.
     */
    @Test
    fun fr41_2_1_matrix_interpolation_follows_css_transforms_2() {
        val out = FloatArray(16)
        Matrix3DInterpolation.interpolate(rotateY(0.0), rotateY(90.0), 0.5f, out)
        assertMatrix(rotateY(45.0), out, "rotateY half way")
        Matrix3DInterpolation.interpolate(rotateY(0.0), rotateY(90.0), 0f, out)
        assertMatrix(rotateY(0.0), out, "rotateY at the start")
        Matrix3DInterpolation.interpolate(rotateY(0.0), rotateY(90.0), 1f, out)
        assertMatrix(rotateY(90.0), out, "rotateY at the end")

        // Column-major: the scale on the diagonal, the perspective term times the z scale in
        // m11, the translation in m12 and m13.
        val to = floatArrayOf(2f, 0f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 0.5f, -0.0025f, 20f, -10f, 0f, 1f)
        val middle = floatArrayOf(1.5f, 0f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 0f, 0.75f, -0.001875f, 10f, -5f, 0f, 1f)
        Matrix3DInterpolation.interpolate(identity, to, 0.5f, out)
        assertMatrix(middle, out, "translate3d, scale3d and perspective half way")

        val flat = floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        Matrix3DInterpolation.interpolate(identity, flat, 0.49f, out)
        assertMatrix(identity, out, "a singular end, before 0.5")
        Matrix3DInterpolation.interpolate(identity, flat, 0.5f, out)
        assertMatrix(flat, out, "a singular end, at 0.5")
        Matrix3DInterpolation.interpolate(flat, identity, 0.25f, out)
        assertMatrix(flat, out, "a singular start, before 0.5")
    }
}
