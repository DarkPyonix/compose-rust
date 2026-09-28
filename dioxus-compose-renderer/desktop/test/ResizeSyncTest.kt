package dioxus.compose.test

import dioxus.compose.ui.platform.ResizeSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a window tells its window manager while its edge is being dragged, and when.
 *
 * None of this can be watched from here: there is no X11 display server on this machine and
 * the thing being claimed is what a compositor put on the screen. It can all be tested
 * without one, because the mistakes are about order and about a debt left unpaid rather
 * than about pixels.
 *
 * What no test here can show is the screen. Whether the moved edge and the drawing inside it
 * arrive together is a fact about what the display server composited, and it is checked by
 * running the window and dragging it. The two ways to get it wrong that a drag would reveal
 * are both below: telling the manager before the drawing exists, which shows the previous
 * size inside the new frame, and never telling it, which stops the window dead until the
 * manager gives up waiting.
 */
class ResizeSyncTest {

    private val order = mutableListOf<String>()
    private val told = mutableListOf<Long>()
    private val sync = ResizeSync(
        present = { order.add("present") },
        tell = { value ->
            order.add("tell")
            told.add(value)
        },
    )

    /**
     * The drawing is handed over first and the manager told second.
     *
     * Told first, the manager shows the frame it was holding as soon as it sees the counter,
     * and what is inside that frame is the drawing for the previous size.
     */
    @Test
    fun nfr9_the_manager_is_told_after_the_drawing_is_handed_over() {
        sync.requested(41L)
        sync.frameDrawn()
        assertEquals(listOf("present", "tell"), order)
        assertEquals(listOf(41L), told)
    }

    /**
     * A request that produced no frame is still paid.
     *
     * A frame can be refused because one is already being drawn, and a size change can turn
     * out not to be one. The manager asked either way, and a counter nobody sets is a window
     * that stops moving with the hand that is dragging it.
     */
    @Test
    fun nfr9_a_request_that_produced_no_frame_is_still_paid() {
        sync.requested(7L)
        assertTrue(sync.isOwed)
        sync.noFrame()
        assertEquals(listOf("tell"), order, "nothing was drawn, so nothing was presented")
        assertEquals(listOf(7L), told)
        assertFalse(sync.isOwed)
    }

    /**
     * A frame nobody asked about sets no counter.
     *
     * Most frames are not resizes: something in the scene changed, or the window was
     * uncovered. Setting the counter for one of those would tell a manager that is not
     * waiting about a size it never asked for, and the next real request would then be
     * answered by a number it had already seen.
     */
    @Test
    fun the_counter_is_not_set_when_the_manager_asked_for_nothing() {
        sync.frameDrawn()
        assertEquals(listOf("present"), order)
        assertTrue(told.isEmpty())

        sync.noFrame()
        assertEquals(listOf("present"), order, "a refused frame with no debt owes nothing")
    }

    /** A debt is paid once, not on every frame after it. */
    @Test
    fun the_counter_is_set_once_for_each_request() {
        sync.requested(3L)
        sync.frameDrawn()
        sync.frameDrawn()
        sync.noFrame()
        assertEquals(listOf("present", "tell", "present"), order)
        assertEquals(listOf(3L), told)
    }

    /**
     * Only the newest number the manager handed out is paid.
     *
     * A drag produces a request per size, and frames are refused while one is being drawn,
     * so a second request can arrive before the first was answered. The manager waits on its
     * newest number; paying an older one tells it about a size it has already left behind.
     */
    @Test
    fun nfr9_a_second_request_replaces_the_first() {
        sync.requested(11L)
        sync.requested(12L)
        sync.frameDrawn()
        assertEquals(listOf(12L), told)
    }

    /** The counter carries whatever the manager asked for, however large. */
    @Test
    fun the_counter_carries_a_number_wider_than_an_int() {
        val wide = (1L shl 40) + 5L
        sync.requested(wide)
        sync.frameDrawn()
        assertEquals(listOf(wide), told)
    }
}
