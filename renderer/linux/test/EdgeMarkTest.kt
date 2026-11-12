@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * The live resize probe: off unless asked for, and flush with the right edge when it is on.
 *
 * Drawn into a raster surface rather than a window, so it needs no display server.
 */
class EdgeMarkTest {

    private fun withEdgeMark(value: String?, block: () -> Unit) {
        val before = getenv("DXC_EDGE_MARK")?.toKString()
        if (value == null) unsetenv("DXC_EDGE_MARK") else setenv("DXC_EDGE_MARK", value, 1)
        try {
            block()
        } finally {
            if (before == null) unsetenv("DXC_EDGE_MARK") else setenv("DXC_EDGE_MARK", before, 1)
        }
    }

    @Test
    fun fr19_8_linux_edge_mark_is_off_unless_asked_for() {
        withEdgeMark(null) { assertFalse(EdgeMark.enabled(), "the mark is drawn with DXC_EDGE_MARK unset") }
        withEdgeMark("1") { assertTrue(EdgeMark.enabled(), "DXC_EDGE_MARK=1 does not switch the mark on") }
    }

    @Test
    fun fr19_8_linux_edge_mark_is_flush_with_the_right_edge_of_the_frame() {
        val width = 200
        val height = 80
        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas
        EdgeMark.around(canvas, width, height) {
            // A scene that leaves a clip and a transform behind, as a real one can.
            canvas.save()
            canvas.clipRect(Rect.makeXYWH(0f, 0f, 50f, 50f))
            canvas.translate(10f, 10f)
            canvas.drawPaint(Paint().apply { color = Color.WHITE })
            canvas.clear(Color.WHITE)
        }
        val bitmap = Bitmap.makeFromImage(surface.makeImageSnapshot())
        val row = height / 2
        for (x in width - EdgeMark.WIDTH until width) {
            assertEquals(Color.BLACK, bitmap.getColor(x, row), "column $x of the bar is not black")
        }
        assertEquals(
            Color.BLACK,
            bitmap.getColor(width - 1, height - 1),
            "the bar does not reach the bottom right corner, so the scene's clip was left on",
        )
        assertTrue(
            bitmap.getColor(width - EdgeMark.WIDTH - 1, row) != Color.BLACK,
            "the column left of the bar is black, so the bar is wider than it says",
        )
    }
}
