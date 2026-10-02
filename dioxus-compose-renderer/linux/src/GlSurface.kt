@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dioxus.compose.ui.platform

import kotlinx.cinterop.CPointer
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import x11.Display
import x11.GLXContext
import x11.Window
import x11.glXMakeCurrent
import x11.glXSwapBuffers

/** The name OpenGL gives the format Skia is told the framebuffer is in. */
private const val GL_RGBA8 = 0x8058

/**
 * The framebuffer this window draws into, and the one frame it draws at a time.
 *
 * The twin of the macOS window's layer, and much smaller than it, because on this platform the
 * decision that layer exists to make is not ours. macOS has to ask for a layer that presents
 * with the transaction it is part of, or the drawing reaches the screen on its own schedule.
 * Here the frame is handed to the display server by [present] and the window manager is what
 * holds it: see [ResizeSync], which owns the ordering.
 *
 * Skia draws straight into the window's back buffer rather than into a texture of its own.
 * There is nothing in between to copy through, and the buffer is described to Skia rather
 * than allocated by it, which is what [BackendRenderTarget] means here.
 */
internal class GlSurface(
    private val display: CPointer<Display>,
    private val window: Window,
    private val context: GLXContext,
) {

    private val skia = DirectContext.makeGL()

    /**
     * Draws one frame at [width] by [height] pixels, and answers whether it drew one.
     *
     * False where there was nothing to draw into: a window with no pixels, a context the
     * server would not make current, or a framebuffer Skia would not wrap. None of the three
     * is a failure to report. A window that has been unmapped or given a size of zero is in
     * every one of those states and comes back from it on its own.
     *
     * Nothing is presented here. The frame is handed to the server separately, because what
     * has to happen between the drawing and the presenting is a promise to the window manager
     * and that promise is not this class's to keep.
     */
    fun draw(width: Int, height: Int, paint: (Canvas) -> Unit): Boolean {
        if (width <= 0 || height <= 0) return false
        if (glXMakeCurrent(display, window, context) == 0) return false
        val target = BackendRenderTarget.makeGL(width, height, 0, 0, 0, GL_RGBA8)
        val surface = Surface.makeFromBackendRenderTarget(
            skia,
            target,
            // GL counts rows from the bottom of the window and Skia counts them from the top,
            // so a scene drawn without this is a scene drawn upside down.
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
            // Text on this desktop is drawn against the subpixels of the display rather than
            // in grey, which is what every other application on it looks like.
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            target.close()
            return false
        }
        try {
            paint(surface.canvas)
            // Submitted, not only recorded. Skia keeps the frame in work of its own, and a
            // buffer presented before that work runs is a buffer with nothing in it.
            surface.flushAndSubmit(true)
        } finally {
            surface.close()
            target.close()
        }
        return true
    }

    /** Hands the frame just drawn to the display server. */
    fun present() {
        glXSwapBuffers(display, window)
    }

    fun close() {
        skia.close()
    }
}
