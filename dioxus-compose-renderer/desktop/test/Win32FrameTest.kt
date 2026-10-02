package dioxus.compose.test

import dioxus.compose.ui.platform.Win32Frames
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
