package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.FrameClock
import dev.darkpyonix.composerust.ui.platform.Win32Frames
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The door every frame the Windows window draws goes through.
 *
 * Two callers reach it on one thread: the frame loop, and the window itself from inside a
 * message it is handling, which is how anything is drawn while the reader drags an edge.
 * What is checked here is that the second of those never starts a frame inside the first.
 */
class Win32FrameTest {

    @AfterTest
    fun forgetThePainter() {
        Win32Frames.paint = null
        Win32Frames.clock = FrameClock()
    }

    @Test
    fun nfr9_a_frame_asked_for_during_a_frame_does_not_start_a_second_one() {
        var frames = 0
        var asked = true
        Win32Frames.paint = {
            frames++
            // What the window does when a size arrives while a frame is already running:
            // the ask is made and has to come back refused rather than nested.
            asked = Win32Frames.draw()
        }

        assertTrue(Win32Frames.draw())

        assertEquals(1, frames)
        assertFalse(asked)
    }

    @Test
    fun nfr9_the_frame_after_one_that_finished_is_drawn() {
        var frames = 0
        Win32Frames.paint = { frames++ }

        assertTrue(Win32Frames.draw())
        assertTrue(Win32Frames.draw())

        assertEquals(2, frames)
    }

    @Test
    fun nfr9_a_frame_that_threw_does_not_shut_the_door_on_the_next_one() {
        var frames = 0
        Win32Frames.paint = {
            frames++
            if (frames == 1) throw IllegalStateException("the swapchain went away")
        }

        assertFailsWith<IllegalStateException> { Win32Frames.draw() }
        assertTrue(Win32Frames.draw())

        assertEquals(2, frames)
    }

    @Test
    fun nfr9_a_window_that_has_gone_draws_nothing() {
        Win32Frames.paint = null

        assertFalse(Win32Frames.draw())
    }

    /**
     * A frame is handed the time that passed, not a fixed step per frame.
     *
     * The window used to add 16 ms for every frame it painted. On a display refreshing at
     * 120 Hz a frame is 8.3 ms apart, so every animation ran at nearly twice its speed, and
     * the Host was told a frame time that had nothing to do with the clock on the wall.
     */
    @Test
    fun pr3_a_frame_clock_driven_at_120_hz_advances_by_real_time() {
        val time = TestTimeSource()
        Win32Frames.clock = FrameClock(time)
        val stamps = mutableListOf<Long>()
        Win32Frames.paint = { nanos -> stamps.add(nanos) }
        val refresh = 1.seconds / 120

        repeat(120) {
            time += refresh
            assertTrue(Win32Frames.draw())
        }

        val steps = stamps.zipWithNext { earlier, later -> later - earlier }
        assertEquals(
            List(119) { refresh.inWholeNanoseconds },
            steps,
            "each frame is one refresh after the last, not a fixed 16 ms after it",
        )
        assertEquals(
            (refresh * 120).inWholeNanoseconds,
            stamps.last(),
            "a second of frames at 120 Hz is a second of frame time",
        )
    }

    /** A frame drawn again before any time has passed is drawn at the same time. */
    @Test
    fun pr3_frames_with_no_time_between_them_share_a_frame_time() {
        val time = TestTimeSource()
        Win32Frames.clock = FrameClock(time)
        val stamps = mutableListOf<Long>()
        Win32Frames.paint = { nanos -> stamps.add(nanos) }

        time += 5.milliseconds
        assertTrue(Win32Frames.draw())
        assertTrue(Win32Frames.draw())
        time += 40.milliseconds
        assertTrue(Win32Frames.draw())

        assertEquals(
            listOf(5.milliseconds, 5.milliseconds, 45.milliseconds).map { it.inWholeNanoseconds },
            stamps,
        )
    }
}
