package dioxus.compose.ui.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

/**
 * The one place a frame is drawn from, whoever asked for it.
 *
 * Two things ask. The frame loop asks once a turn, because something in the scene changed
 * or because nothing has been drawn yet. The window asks in the middle of being resized,
 * because the display server has already moved its edge and what is inside that edge is
 * whatever was last painted: a frame produced a turn of the loop later leaves a strip of
 * the window claimed and empty, as wide as the speed of the hand times how late the
 * painting is.
 *
 * Pure, and kept out of the window's own file, because this is the part that can be wrong
 * in a way nobody sees on the platform the author runs. A frame drawn at a size the window
 * no longer has, and a second frame started on top of one already being drawn, are both
 * invisible in a screenshot and both visible to a test.
 */
/** The size of a window's drawable in pixels, and how many of them go to a point. */
data class WindowMeasurement(val width: Int, val height: Int, val scale: Float)

internal class WindowFrames(
    private val measure: () -> WindowMeasurement,
    private val paint: (IntSize, Density) -> Unit,
) {

    /**
     * Draws a frame, and answers whether it drew one.
     *
     * False says one of two things and neither is a failure: a frame is already being
     * drawn, or the window has no pixels to draw into because it is unmapped or has been
     * given a size of zero.
     */
    fun draw(): Boolean {
        // Refused rather than queued. The frame that is running is already producing what
        // a second request would ask for, and starting one inside it would have the scene
        // composing into a surface it is in the middle of painting.
        if (painting) {
            return false
        }
        // Asked for now rather than remembered. The size that matters is the one the
        // window has at the moment of drawing: a resize recorded it a moment ago, and
        // nothing has told the loop about it yet.
        val measured = measure()
        if (measured.width <= 0 || measured.height <= 0) {
            return false
        }
        painting = true
        try {
            paint(IntSize(measured.width, measured.height), Density(measured.scale))
        } finally {
            painting = false
        }
        return true
    }

    private var painting = false
}
