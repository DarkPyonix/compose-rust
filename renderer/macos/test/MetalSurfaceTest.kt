@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.useContents
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The layer this window draws into, and the two things about it that a window's edge
 * depends on.
 *
 * These are assertions about what Core Animation actually holds, not about what the code
 * meant to ask for, because both of the settings below are ones a layer has by default in
 * the wrong state and both are invisible from inside the process when they are wrong.
 *
 * What no test here can show is the screen. Whether the drawing and the window's frame
 * arrive together is a fact about what the display server composited, and it is checked by
 * painting the window's background a colour nothing else on the screen is, dragging an edge
 * at a known speed, and counting how many pixels of that colour a capture of the screen
 * holds. Measured that way on 2026-09-25: with a layer that presented on its own schedule
 * the strip averaged 3 pixels at 640 px/s and reached 350 at 28,845 px/s, which in each case
 * is the speed of the hand times one refresh of the screen; with the layer below it was zero
 * at every speed.
 */
class MetalSurfaceTest {

    /**
     * A frame starts from nothing, rather than from whatever was in the texture.
     *
     * Metal hands out drawables from a pool, so the texture a frame is given holds a frame
     * from two or three back. Painting straight onto it is invisible while everything
     * drawn is opaque, and it is exactly what the screen shows once anything is not:
     * translucent glass over a page lets an older frame through, and a window that had
     * been resized showed the previous layout standing behind the current one, complete
     * with a second composer and a second sidebar. Nothing in the frame was wrong. What
     * was wrong was underneath it.
     *
     * Cleared to transparent rather than to a colour, because what is behind the window is
     * the platform's business: a window made of chrome has the desktop behind it, and a
     * clear to any colour would paint over that.
     */
    @Test
    fun nfr9_a_frame_does_not_start_from_the_one_before_it() {
        val surface = Surface.makeRasterN32Premul(4, 4)
        try {
            surface.canvas.clear(0xFFFF0000.toInt())
            MetalSurface.startFrame(surface.canvas)
            val pixels = surface.makeImageSnapshot().peekPixels()
            assertTrue(pixels != null, "the raster surface gave up no pixels to read")
            var left = 0
            for (x in 0 until 4) {
                for (y in 0 until 4) {
                    if (pixels.getColor(x, y) != 0) left++
                }
            }
            assertEquals(
                0,
                left,
                "a pixel left over from the frame before shows through anything drawn on " +
                    "top of it that is not opaque",
            )
        } finally {
            surface.close()
        }
    }

    @Test
    fun nfr9_the_layer_presents_with_the_transaction() {
        val surface = MetalSurface()
        try {
            assertTrue(
                surface.layer.presentsWithTransaction,
                "a layer that presents on its own schedule puts what was drawn on the " +
                    "screen after the window's frame has already moved, which a hand " +
                    "dragging an edge sees as the drawing coming away from the pointer",
            )
        } finally {
            surface.close()
        }
    }

    @Test
    fun nfr9_the_layer_is_sized_in_pixels_rather_than_points() {
        val surface = MetalSurface()
        try {
            surface.resize(widthInPoints = 520.0, heightInPoints = 360.0, scale = 2.0)
            assertEquals(2.0, surface.layer.contentsScale)
            surface.layer.drawableSize.useContents {
                assertEquals(1040.0, width, "a drawable measured in points is half a window")
                assertEquals(720.0, height)
            }
        } finally {
            surface.close()
        }
    }

    @Test
    fun the_layer_lets_skia_draw_into_its_texture() {
        val surface = MetalSurface()
        try {
            assertFalse(
                surface.layer.framebufferOnly,
                "a framebuffer-only texture cannot be read back, and Skia reads one back",
            )
        } finally {
            surface.close()
        }
    }
    /**
     * A frame gives its drawable back when it is done, however many frames are drawn
     * before the run loop next turns.
     *
     * The drawable is handed out autoreleased and owns a texture the size of the window. A
     * frame that left it to the caller's pool kept it until that pool drained, and a resize
     * draws frame after frame inside one turn: 100 sizes in a row held 528 MB of Metal
     * memory on macOS CI. A test thread has no pool that drains at all, so it is the
     * strictest place to ask.
     *
     * The bound is three drawables at the largest size drawn (Core Animation keeps up to
     * three), plus room for Skia's own resources, and the leak was over ten times it.
     */
    @Test
    fun nfr9_frames_drawn_in_one_turn_release_their_drawables() {
        val surface = MetalSurface()
        try {
            // A layer has to be given a size before it hands out a drawable.
            surface.withoutAnimation {
                surface.resize(800.0, 600.0, 1.0)
                surface.draw { _, _, _ -> }
            }
            val before = surface.allocatedBytes
            for (step in 1..100) {
                val grow = if (step <= 50) step else 100 - step
                surface.withoutAnimation {
                    surface.resize(800.0 + grow * 8, 600.0 + grow * 6, 1.0)
                    surface.draw { canvas, _, _ -> canvas.clear(0xFF336699.toInt()) }
                }
            }
            val grownMb = (surface.allocatedBytes - before) / 1048576.0
            @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
            kotlin.native.runtime.GC.collect()
            val afterGcMb = (surface.allocatedBytes - before) / 1048576.0
            platform.Foundation.NSRunLoop.mainRunLoop.runUntilDate(
                platform.Foundation.NSDate.dateWithTimeIntervalSinceNow(0.5),
            )
            val afterTurnMb = (surface.allocatedBytes - before) / 1048576.0
            println("metal growth MB: frames=$grownMb after_gc=$afterGcMb after_turn=$afterTurnMb")
            val largestDrawableMb = 1200.0 * 900.0 * 4 / 1048576.0
            val boundMb = largestDrawableMb * 3 + 16
            assertTrue(
                grownMb <= boundMb,
                "Metal memory grew by $grownMb MB over 100 frames at changing sizes; " +
                    "at most $boundMb MB is expected, three drawables at the largest size " +
                    "and Skia's resources. Something a frame was handed is being kept.",
            )
        } finally {
            surface.close()
        }
    }
}
