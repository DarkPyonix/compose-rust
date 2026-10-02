package dioxus.compose.test

import dioxus.compose.ui.platform.ResizeEdge
import dioxus.compose.ui.platform.WindowBounds
import dioxus.compose.ui.platform.resize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a drag on each edge does to the window.
 *
 * This is the half of edge resizing that can be wrong invisibly. The grips only exist on
 * an undecorated window, which is Windows and Linux, so on a Mac nobody would find out
 * that a leading edge drags the whole window sideways until someone on another platform
 * did.
 */
class WindowResizeTest {
    private val start = WindowBounds(x = 100, y = 200, width = 800, height = 600)

    /** A trailing edge sizes the window and leaves it where it is. */
    @Test
    fun fr19_4_2_a_trailing_edge_only_changes_the_size() {
        assertEquals(
            WindowBounds(100, 200, 850, 600),
            resize(start, ResizeEdge.Right, dx = 50, dy = 0, minWidth = 200, minHeight = 150),
        )
        assertEquals(
            WindowBounds(100, 200, 800, 640),
            resize(start, ResizeEdge.Bottom, dx = 0, dy = 40, minWidth = 200, minHeight = 150),
        )
    }

    /**
     * A leading edge moves the window as well, and the two agree.
     *
     * The opposite edge has to stay put. If the size changes and the position does not,
     * pulling the left edge drags the right edge along with it, which reads as the window
     * sliding rather than growing.
     */
    @Test
    fun fr19_4_2_a_leading_edge_moves_the_window_and_leaves_the_far_edge_alone() {
        val wider = resize(start, ResizeEdge.Left, dx = -50, dy = 0, minWidth = 200, minHeight = 150)
        assertEquals(WindowBounds(50, 200, 850, 600), wider)
        assertEquals(
            start.x + start.width,
            wider.x + wider.width,
            "pulling the left edge moved the right one",
        )

        val taller = resize(start, ResizeEdge.Top, dx = 0, dy = -40, minWidth = 200, minHeight = 150)
        assertEquals(WindowBounds(100, 160, 800, 640), taller)
        assertEquals(
            start.y + start.height,
            taller.y + taller.height,
            "pulling the top edge moved the bottom one",
        )
    }

    /** A corner is both of its edges, applied at once. */
    @Test
    fun fr19_4_2_a_corner_pulls_both_of_its_edges() {
        assertEquals(
            WindowBounds(50, 160, 850, 640),
            resize(start, ResizeEdge.TopLeft, dx = -50, dy = -40, minWidth = 200, minHeight = 150),
        )
    }

    /**
     * A window stops at its minimum, and stops moving with it.
     *
     * Clamping the size alone is the subtle version of this bug: the window would stop
     * shrinking and then keep sliding, because the leading edge went on travelling after
     * the far edge had nowhere left to go.
     */
    @Test
    fun fr19_4_2_a_window_held_at_its_minimum_stops_moving_too() {
        val squashed =
            resize(start, ResizeEdge.Left, dx = 5_000, dy = 0, minWidth = 200, minHeight = 150)
        assertEquals(200, squashed.width, "the window went under its minimum width")
        assertEquals(
            start.x + start.width,
            squashed.x + squashed.width,
            "a window pinned at its minimum kept sliding, so its far edge moved",
        )
    }
}
