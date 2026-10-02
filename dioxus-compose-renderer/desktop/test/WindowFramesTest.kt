package dioxus.compose.test

import androidx.compose.ui.unit.IntSize
import dioxus.compose.ui.platform.WindowFrames
import dioxus.compose.ui.platform.WindowMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a frame's size comes from, and what a second request for one does.
 *
 * Both are claims about a resize that nobody can see in a screenshot. A window whose edge
 * is being dragged is moved by the display server at once, and the frame that belongs to
 * that move is drawn from inside the move rather than on the next turn of the frame loop:
 * drawn a refresh later, the strip between where the edge went and what was painted is as
 * wide as the speed of the hand. So the size has to be read at the moment of drawing, and
 * a request arriving while a frame is already being drawn has to be refused rather than
 * start a second one on top of it.
 *
 * Neither can be watched from here, because there is no display server on this machine to
 * drag a window on. Both can be tested without one.
 */
class WindowFramesTest {
    private var measured = WindowMeasurement(width = 520, height = 360, scale = 1.0f)
    private val painted = mutableListOf<IntSize>()
    private val scales = mutableListOf<Float>()

    private val frames = WindowFrames({ measured }) { size, density ->
        painted.add(size)
        scales.add(density.density)
    }

    // A field rather than a local, because the one test that uses it has its painting ask
    // the very thing being made for another frame, and a local cannot be named there.
    private lateinit var gate: WindowFrames

    /**
     * A frame is drawn at the size the window has now, not at one it was told about.
     *
     * The resize records the new size and draws in the same step, so nothing has had a
     * chance to hand that size to the loop. A frame that painted what the loop last knew
     * would paint the old size into a corner of the new window.
     */
    @Test
    fun nfr9_a_frame_is_drawn_at_the_size_the_window_reports_now() {
        assertTrue(frames.draw())
        assertEquals(listOf(IntSize(520, 360)), painted)

        // What a resize does: the window's answer changes and no one is told.
        measured = WindowMeasurement(width = 900, height = 610, scale = 2.0f)

        assertTrue(frames.draw())
        assertEquals(listOf(IntSize(520, 360), IntSize(900, 610)), painted)
        assertEquals(listOf(1.0f, 2.0f), scales, "a frame is drawn at the density beside the size")
    }

    /**
     * A request that arrives while a frame is being drawn does not start another.
     *
     * Two things ask for frames now, and one of them asks from inside an event the other
     * is in the middle of reading. A second frame started on top of the first would have
     * the scene composing into a surface it is already painting.
     */
    @Test
    fun nfr9_a_second_request_while_a_frame_is_drawing_does_not_start_another() {
        val refused = mutableListOf<Boolean>()
        gate = WindowFrames({ measured }) { size, _ ->
            painted.add(size)
            // The window asking for a frame in the middle of one, which is what a resize
            // arriving while a frame is already being drawn does.
            refused.add(gate.draw())
        }

        assertTrue(gate.draw())
        assertEquals(1, painted.size, "the frame inside the frame should not have been drawn")
        assertEquals(listOf(false), refused, "the second request should have said it drew nothing")

        // And the refusal lasts exactly as long as the frame it was refused for.
        assertTrue(gate.draw())
        assertEquals(2, painted.size, "the next frame should be drawn like any other")
        assertEquals(listOf(false, false), refused)
    }

    /**
     * A window with no pixels is not drawn into.
     *
     * An X11 window that has been unmapped or given a size of zero answers with a zero, and
     * a surface of that size is neither something to paint into nor a failure to report.
     */
    @Test
    fun nfr9_a_window_with_no_pixels_is_not_drawn() {
        measured = WindowMeasurement(width = 0, height = 0, scale = 1.0f)
        assertFalse(frames.draw())
        assertTrue(painted.isEmpty())

        measured = WindowMeasurement(width = 520, height = 0, scale = 1.0f)
        assertFalse(frames.draw())
        assertTrue(painted.isEmpty())

        measured = WindowMeasurement(width = 520, height = 360, scale = 1.0f)
        assertTrue(frames.draw())
        assertEquals(listOf(IntSize(520, 360)), painted)
    }
}
