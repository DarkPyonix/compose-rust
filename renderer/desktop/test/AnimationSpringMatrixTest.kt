package dev.darkpyonix.composerust.test

import androidx.compose.animation.core.FloatSpringSpec
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.ui.node.SpringTiming
import dev.darkpyonix.composerust.ui.node.WebAnimationTiming
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The spring timing function as tables, compared with Compose's own spring. */
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
}
