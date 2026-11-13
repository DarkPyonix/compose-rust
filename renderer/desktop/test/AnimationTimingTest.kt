package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.FillMode
import dev.darkpyonix.composerust.protocol.PlaybackDirection
import dev.darkpyonix.composerust.protocol.StepPosition
import dev.darkpyonix.composerust.protocol.Timing
import dev.darkpyonix.composerust.ui.node.AnimationPhase
import dev.darkpyonix.composerust.ui.node.TimeSample
import dev.darkpyonix.composerust.ui.node.WebAnimationTiming
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timing model, as a table. Every expected value is worked out by hand from the
 * formulas in Web Animations Level 1 and CSS Easing 1, not read back from the code.
 */
class AnimationTimingTest {

    private fun at(
        localMs: Double,
        delayMs: Double = 0.0,
        durationMs: Double = 1000.0,
        iterations: Double = 1.0,
        direction: PlaybackDirection = PlaybackDirection.Normal,
        fill: FillMode = FillMode.None,
    ): TimeSample = TimeSample().also {
        WebAnimationTiming.sample(localMs, delayMs, durationMs, iterations, direction, fill, it)
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        assertTrue(abs(expected - actual) < 1e-4f, "$what: expected $expected, was $actual")
    }

    @Test
    fun fr41_linear_progress_is_time_over_duration() {
        assertClose(0.5f, at(500.0).progress, "half way")
        assertClose(0.25f, WebAnimationTiming.ease(Timing.Linear, 0.25f, false), "linear easing")
    }

    /** CSS `ease` is cubic-bezier(0.25, 0.1, 0.25, 1); at x = 0.5 it is 0.8024033877. */
    @Test
    fun fr41_ease_at_its_middle() {
        val ease = Timing.CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        assertTrue(abs(WebAnimationTiming.ease(ease, 0.5f, false) - 0.8024034f) < 1e-3f)
    }

    /**
     * steps(4) under each jump term, at the start, at a boundary and with the before flag:
     * jump-start jumps at 0, jump-end at the end, jump-none has three jumps over four
     * steps, and jump-both five intervals.
     */
    @Test
    fun fr41_steps_with_every_jump_term_and_the_before_flag() {
        fun steps(position: StepPosition, input: Float, before: Boolean = false) =
            WebAnimationTiming.steps(input, 4, position, before)
        assertClose(0.25f, steps(StepPosition.JumpStart, 0f), "jump-start at 0")
        assertClose(0f, steps(StepPosition.JumpStart, 0f, before = true), "jump-start at 0, before")
        assertClose(0.5f, steps(StepPosition.JumpStart, 0.25f), "jump-start at a boundary")
        assertClose(0f, steps(StepPosition.JumpEnd, 0f), "jump-end at 0")
        assertClose(0.25f, steps(StepPosition.JumpEnd, 0.25f), "jump-end at a boundary")
        assertClose(0f, steps(StepPosition.JumpEnd, 0.25f, before = true), "jump-end at a boundary, before")
        assertClose(1f, steps(StepPosition.JumpEnd, 1f), "jump-end at 1")
        assertClose(0f, steps(StepPosition.JumpNone, 0f), "jump-none at 0")
        assertClose(1f / 3f, steps(StepPosition.JumpNone, 0.25f), "jump-none at a boundary")
        assertClose(1f, steps(StepPosition.JumpNone, 1f), "jump-none at 1")
        assertClose(0.2f, steps(StepPosition.JumpBoth, 0f), "jump-both at 0")
        assertClose(0.4f, steps(StepPosition.JumpBoth, 0.25f), "jump-both at a boundary")
        assertClose(1f, steps(StepPosition.JumpBoth, 1f), "jump-both at 1")
    }

    /**
     * 2.5 iterations of 100 ms, at 120 ms: iteration 1, 0.2 of the way through it. Each
     * direction reads that the way its name says. At 250 ms, the end, it is half way
     * through iteration 2.
     */
    @Test
    fun fr41_fractional_iterations_in_every_direction() {
        val expected = mapOf(
            PlaybackDirection.Normal to 0.2f,
            PlaybackDirection.Reverse to 0.8f,
            PlaybackDirection.Alternate to 0.8f,
            PlaybackDirection.AlternateReverse to 0.2f,
        )
        for ((direction, progress) in expected) {
            val sample = at(120.0, durationMs = 100.0, iterations = 2.5, direction = direction)
            assertEquals(1.0, sample.iteration, "$direction iteration")
            assertClose(progress, sample.progress, "$direction progress")
        }
        val end = at(250.0, durationMs = 100.0, iterations = 2.5, fill = FillMode.Forwards)
        assertEquals(AnimationPhase.After, end.phase)
        assertEquals(2.0, end.iteration)
        assertClose(0.5f, end.progress, "the end of 2.5 iterations")
    }

    /** A negative delay starts part way through: at 0 ms with a delay of -250, a quarter. */
    @Test
    fun fr41_a_negative_delay_starts_part_way() {
        val sample = at(0.0, delayMs = -250.0)
        assertEquals(AnimationPhase.Active, sample.phase)
        assertClose(0.25f, sample.progress, "progress")
    }

    /** Each fill mode, before the delay ends and after the animation does. */
    @Test
    fun fr41_every_fill_mode_before_and_after() {
        for (fill in FillMode.entries) {
            val before = at(50.0, delayMs = 100.0, durationMs = 100.0, fill = fill)
            val after = at(250.0, delayMs = 100.0, durationMs = 100.0, fill = fill)
            assertEquals(AnimationPhase.Before, before.phase)
            assertEquals(AnimationPhase.After, after.phase)
            val backwards = fill == FillMode.Backwards || fill == FillMode.Both
            val forwards = fill == FillMode.Forwards || fill == FillMode.Both
            assertEquals(backwards, before.inEffect, "$fill before")
            assertEquals(forwards, after.inEffect, "$fill after")
            if (backwards) assertClose(0f, before.progress, "$fill holds the first keyframe")
            if (forwards) assertClose(1f, after.progress, "$fill holds the last keyframe")
        }
    }

    /** Infinite iterations never reach the after phase. */
    @Test
    fun fr41_infinite_iterations_run_on() {
        val sample = at(350.0, durationMs = 100.0, iterations = Double.POSITIVE_INFINITY)
        assertEquals(AnimationPhase.Active, sample.phase)
        assertEquals(3.0, sample.iteration)
        assertClose(0.5f, sample.progress, "progress")
    }

    /** A duration of zero is over at once, and a forwards fill holds its end. */
    @Test
    fun fr41_a_zero_duration_is_over_at_once() {
        val none = at(0.0, durationMs = 0.0)
        assertEquals(AnimationPhase.After, none.phase)
        assertFalse(none.inEffect)
        val held = at(0.0, durationMs = 0.0, fill = FillMode.Both)
        assertTrue(held.inEffect)
        assertClose(1f, held.progress, "the end, held")
    }
}
