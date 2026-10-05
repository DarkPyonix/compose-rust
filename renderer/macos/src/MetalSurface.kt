@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.useContents
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import platform.CoreGraphics.CGSizeMake
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer
import platform.QuartzCore.CATransaction

/**
 * The layer this window draws into, and the one frame it draws at a time.
 *
 * Skiko has a layer of its own and it is a good one, but it keeps the thing that presents
 * a frame to itself, and that is the piece this window needs. A layer that presents on its
 * own schedule is a refresh out of step with the window's frame, which the window server
 * moves the moment the pointer does. Dragging an edge shows it: the faster the hand, the
 * wider the strip of window that has been claimed and not yet drawn. It was measured at 94
 * pixels at an ordinary speed and 350 at a flick, and in both cases that width divided by
 * the speed of the hand came to one refresh exactly.
 *
 * So the layer is ours. [CAMetalLayer.presentsWithTransaction] says that presenting is part
 * of whatever change to the layer tree is being committed rather than something that
 * happens whenever the frame is ready, and that is what puts the drawing and the window's
 * frame on the screen together.
 */
internal class MetalSurface {
    private val device = requireNotNull(MTLCreateSystemDefaultDevice()) {
        "this machine has no Metal device; the renderer draws with Metal on this platform"
    }
    private val queue = requireNotNull(device.newCommandQueue()) {
        "the Metal device would not give a command queue"
    }
    private val context = DirectContext.makeMetal(device.objcPtr(), queue.objcPtr())

    /** What Metal has allocated for this device, in bytes, for measuring. */
    val allocatedBytes: Long get() = device.currentAllocatedSize.toLong()

    /** Skia's resource cache limit, in bytes, for measuring. */
    val cacheLimitBytes: Long get() = context.resourceCacheLimit

    val layer = CAMetalLayer().also {
        @Suppress("CAST_NEVER_SUCCEEDS")
        it.device = device as objcnames.protocols.MTLDeviceProtocol
        it.pixelFormat = MTLPixelFormatBGRA8Unorm
        // Skia draws into the texture rather than only sampling it.
        it.framebufferOnly = false
        // Whatever the window puts behind this reaches the screen only where the drawing
        // did not cover it, and an opaque layer covers all of it whatever the alpha in the
        // texture says.
        it.opaque = false
        // The whole reason this layer is ours. See above.
        it.presentsWithTransaction = true
    }

    /**
     * Tells the layer how many pixels it is, in the density it is being shown at.
     *
     * Said before drawing rather than after, because a drawable handed out at the old size
     * would be drawn into at the new one.
     */
    fun resize(widthInPoints: Double, heightInPoints: Double, scale: Double) {
        layer.contentsScale = scale
        layer.drawableSize = CGSizeMake(widthInPoints * scale, heightInPoints * scale)
    }

    /**
     * Draws one frame and puts it on the screen.
     *
     * Returns false when there was no drawable to be had, which is the ordinary way a
     * layer says it is not on screen or is already as far ahead as it is allowed to be.
     */
    fun draw(paint: (Canvas, Int, Int) -> Unit): Boolean {
        // Its own pool, because the drawable and the command buffer are handed out
        // autoreleased, and a frame drawn inside a resize can be many frames away from the
        // run loop draining the pool it would otherwise land in.
        val drawn = autoreleasepool { drawFrame(paint) }
        releaseDrawablesOfOldSizes()
        return drawn
    }

    private var lastWidth = 0
    private var lastHeight = 0
    private var bytesSinceCollection = 0L

    /**
     * Gives back the textures of sizes the window has left.
     *
     * The drawable this frame was handed reaches Kotlin as an object, and that object holds
     * the drawable, and the drawable its texture, until the collector frees the object. The
     * collector counts what Kotlin allocated and knows nothing of the texture, so a resize,
     * which makes a new texture at every size, outruns it: 100 sizes held 528 MB of Metal
     * memory, with 34 collections run and none of them soon enough, because what a
     * collection frees is given back on the main thread when its run loop next turns, and a
     * resize does not let it turn. A collection run here, on the main thread, gives them
     * back at once.
     *
     * Only when textures of new sizes have added up to more than a few frames' worth. A
     * window that keeps its size reuses the same three drawables and never asks for one.
     */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    private fun releaseDrawablesOfOldSizes() {
        val width = lastDrawn.first
        val height = lastDrawn.second
        if (width == lastWidth && height == lastHeight) return
        lastWidth = width
        lastHeight = height
        bytesSinceCollection += width.toLong() * height * 4
        if (bytesSinceCollection < COLLECT_AFTER_BYTES) return
        bytesSinceCollection = 0
        kotlin.native.runtime.GC.collect()
    }

    private var lastDrawn = 0 to 0

    private fun drawFrame(paint: (Canvas, Int, Int) -> Unit): Boolean {
        val width: Int
        val height: Int
        layer.drawableSize.useContents {
            width = this.width.toInt()
            height = this.height.toInt()
        }
        if (width <= 0 || height <= 0) return false
        lastDrawn = width to height
        val drawable = layer.nextDrawable() ?: return false
        val target = BackendRenderTarget.makeMetal(width, height, drawable.texture.objcPtr())
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
        )
        if (surface == null) {
            target.close()
            return false
        }
        try {
            startFrame(surface.canvas)
            paint(surface.canvas, width, height)
            surface.flushAndSubmit()
            // Presenting is done by hand because the layer presents with the transaction:
            // the work has to be known to be scheduled before the drawable is handed over,
            // and then the handing over belongs to whoever is committing the layer tree.
            // Apple's own wording for a view that has to stay attached while it is resized.
            val commands = queue.commandBuffer()
            if (commands == null) {
                drawable.present()
            } else {
                commands.commit()
                commands.waitUntilScheduled()
                drawable.present()
            }
        } finally {
            surface.close()
            target.close()
        }
        return true
    }

    /** Runs [block] with no animation on anything the layer tree does inside it. */
    fun withoutAnimation(block: () -> Unit) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            block()
        } finally {
            CATransaction.commit()
        }
    }

    fun close() {
        context.close()
    }

    companion object {
        /** About three drawables of a 2400 by 1800 pixel window. */
        private const val COLLECT_AFTER_BYTES = 48L * 1024 * 1024

        /**
         * Empties the canvas a frame is about to be drawn on.
         *
         * Metal hands out drawables from a pool, so the texture is not blank: it holds a
         * frame from two or three back. That is invisible while everything drawn is
         * opaque and it is what the screen shows the moment anything is not. Glass over a
         * page is not, so a window that had been resized stood its previous layout behind
         * its current one.
         *
         * Transparent rather than a colour. What is behind the window belongs to the
         * platform, and a window made of chrome has the desktop back there; clearing to
         * any colour would paint over it.
         */
        internal fun startFrame(canvas: Canvas) {
            canvas.clear(0)
        }
    }
}
