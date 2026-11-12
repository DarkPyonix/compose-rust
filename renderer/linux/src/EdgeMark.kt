package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/**
 * A probe for live resize: a black bar flush with the right edge of what a frame drew.
 *
 * Switched on by the `DXC_EDGE_MARK` environment variable, read once when a window opens, and
 * off otherwise, when the frame is drawn exactly as it would be without this file. With it on,
 * the distance in a screenshot from the window's own right edge to the bar is how far the
 * drawing is behind the frame the window manager has already moved. Painting the window's
 * background instead would measure nothing: a scene leaves parts of itself transparent, so that
 * colour means "not drawn yet" and "drawn as nothing" at once.
 *
 * `.github/scripts/linux-live-resize.sh` drags a window's edge and reads that distance.
 */
internal object EdgeMark {
    /** How wide the bar is, in pixels. The measuring script looks for a run this wide. */
    const val WIDTH = 12

    private val black = Paint().apply { color = 0xFF000000.toInt() }

    fun enabled(): Boolean = System.getenv("DXC_EDGE_MARK") != null

    /** Where the bar goes in a frame [width] by [height] pixels. */
    fun rect(width: Int, height: Int): Rect =
        Rect.makeXYWH((width - WIDTH).toFloat(), 0f, WIDTH.toFloat(), height.toFloat())

    /**
     * Runs [draw], then paints the bar over what it left. The canvas is restored first, because
     * the scene leaves its own clip and transform behind, and a bar drawn inside that clip is a
     * bar nobody can find.
     */
    fun around(canvas: Canvas, width: Int, height: Int, draw: () -> Unit) {
        val depth = canvas.save()
        draw()
        canvas.restoreToCount(depth)
        canvas.drawRect(rect(width, height), black)
    }
}
