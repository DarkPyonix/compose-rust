package dioxus.compose.test

import dioxus.compose.ui.platform.WindowEvent
import dioxus.compose.ui.platform.WindowEventLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who is allowed to read the display server, and when the frame loop gets what it said.
 *
 * The defect these defend against has been found twice on the X11 window and is invisible
 * from inside the process: a frame drawn from inside a size change read the server for the
 * window's new size, took the rest of the drag out of the queue as a side effect, and left
 * those sizes handled by nobody. The window went on moving under the hand and stopped being
 * painted, and every frame the process could see looked on time and the right size.
 */
class WindowEventLogTest {

    private val log = WindowEventLog()
    private val drained = mutableListOf<WindowEvent>()

    private fun event(kind: Int, x: Float = 0f) =
        WindowEvent(kind, x, 0f, 0, 0, 0, 0, "")

    /**
     * A read reached from inside a read does nothing.
     *
     * This is the resize case exactly: the outer read is handling a size change, the frame
     * it draws asks the window something, and whatever that something is must not be able to
     * empty the queue underneath it.
     */
    @Test
    fun nfr9_a_read_from_inside_a_read_takes_nothing_from_the_queue() {
        var nested: Boolean? = null
        var nestedRan = false
        val outer = log.read {
            log.heard(event(WindowEvent.POINTER_MOVE, x = 1f))
            nested = log.read { nestedRan = true }
            log.heard(event(WindowEvent.POINTER_MOVE, x = 2f))
        }

        assertTrue(outer, "the first read is the one that is allowed to happen")
        assertEquals(false, nested, "the read inside it should have refused")
        assertFalse(nestedRan, "and should not have touched the server at all")

        log.drain(drained)
        assertEquals(listOf(1f, 2f), drained.map { it.x }, "both events survived the nesting")
    }

    /** Once the read that was running has finished, the next one is allowed again. */
    @Test
    fun the_guard_lasts_exactly_as_long_as_the_read_it_was_set_for() {
        assertTrue(log.read {})
        assertTrue(log.read {})
    }

    /**
     * The loop is given what the window heard, in order, once.
     *
     * Order because a press that arrives before a release is a click and the other way round
     * is nothing, and once because an event delivered twice is a button pressed twice.
     */
    @Test
    fun what_the_window_heard_reaches_the_loop_in_order_and_only_once() {
        log.read {
            log.heard(event(WindowEvent.POINTER_DOWN))
            log.heard(event(WindowEvent.POINTER_MOVE))
            log.heard(event(WindowEvent.POINTER_UP))
        }

        log.drain(drained)
        assertEquals(
            listOf(WindowEvent.POINTER_DOWN, WindowEvent.POINTER_MOVE, WindowEvent.POINTER_UP),
            drained.map { it.kind },
        )

        drained.clear()
        log.drain(drained)
        assertTrue(drained.isEmpty(), "the second drain of the same turn has nothing left")
    }

    /** The list the loop keeps is added to rather than replaced, so nothing it held is lost. */
    @Test
    fun draining_adds_to_what_the_caller_already_had() {
        drained.add(event(WindowEvent.KEY_DOWN))
        log.read { log.heard(event(WindowEvent.KEY_UP)) }
        log.drain(drained)
        assertEquals(listOf(WindowEvent.KEY_DOWN, WindowEvent.KEY_UP), drained.map { it.kind })
    }
}
