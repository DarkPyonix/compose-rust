@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.ui.node.LayoutProfile
import java.lang.System
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.staticCFunction
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.WindowMeasurement
import org.thisisthepy.compose.window.windows.Win32Cursor
import org.thisisthepy.compose.window.windows.Win32Loop
import org.thisisthepy.compose.window.windows.Win32NativeWindow

// The window itself is the Compose fork's windows-shared module: its C
// (`extended/window/windows-shared/c/win32_window.c`), compiled for MSVC by
// `desktop/scripts/build-windows.sh` and linked into the same executable, and the
// Kotlin/Native layer over it (`Win32NativeWindow`, `Win32Loop`), which this links as
// org.thisisthepy.compose.window:windows-shared. What is here is the scene: what it draws and
// what it is told.

/**
 * A window of this renderer's own on Windows, drawn into with Skia through Direct3D 12 and with
 * no toolkit in between.
 *
 * The Kotlin/Native twin of the native image's `Win32Window.kt`, and the same window: the
 * fork's C opens it, owns the device and the swapchain, translates messages into the shared event
 * record, composes text through IMM32 and answers UI Automation. What is here is what a scene
 * needs from all that, drawn the same way the native image draws it.
 *
 * The frame that belongs to a resize is drawn from inside the resize. While the reader drags an
 * edge, Windows runs a loop of its own inside the message that began the drag and this thread's
 * own loop does not get another turn until the drag ends. The C side calls back into
 * [draw] from there, and a frame already being drawn refuses a second one
 * rather than nesting it.
 */
internal class Win32Window private constructor(
    private val native: Win32NativeWindow,
    private val context: DirectContext,
    measured: WindowMeasurement,
) {

    private val reportInput = System.getenv("DXC_REPORT_INPUT") != null

    /** The size the scene was last told. Changed only where a frame finds the buffer is new. */
    private var size = IntSize(measured.width, measured.height)

    private val textInput = NativeTextInput()

    /** The right-click menu of text and selections, drawn by the system. */
    private val textToolbar = Win32TextToolbar { native.window }

    /** Handed to UI Automation, which the C side answers for. */
    private val semantics = NativeSemantics { elements -> native.setAccessibility(elements) }

    /** Where the scene's own work runs: here, on the thread that draws, once a frame. */
    private val work = FrameDispatcher()

    private val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = true
        override val containerSize: IntSize get() = size
    }

    private val platformContext: PlatformContext =
        object : PlatformContext by PlatformContext.Empty() {
            override val windowInfo get() = this@Win32Window.windowInfo
            override val textToolbar get() = this@Win32Window.textToolbar
            override val semanticsOwnerListener get() = semantics

            override suspend fun startInputMethod(
                request: PlatformTextInputMethodRequest,
            ): Nothing = textInput.run(request)

            /**
             * The shape the pointer takes over whatever it is on. The numbering is the C
             * side's, the same one every desktop window here answers to.
             */
            override fun setPointerIcon(pointerIcon: PointerIcon) {
                Win32Loop.setCursor(
                    when (pointerIcon) {
                        PointerIcon.Hand -> Win32Cursor.HAND
                        PointerIcon.Text -> Win32Cursor.TEXT
                        PointerIcon.Crosshair -> Win32Cursor.CROSSHAIR
                        else -> Win32Cursor.ARROW
                    },
                )
            }
        }

    private val scene: ComposeScene = CanvasLayersComposeScene(
        density = Density(measured.scale),
        size = size,
        coroutineContext = work,
        platformContext = platformContext,
    )

    private var nanos = 0L
    private var drawing = false
    private var painted = false
    private var drew = false

    fun setContent(content: @Composable () -> Unit) {
        scene.setContent(content)
        WindowStartReport.sceneCreated(size.width, size.height, scene.density.density)
    }

    /** Holds the window until it closes. */
    fun run() {
        resizing = this
        Win32Loop.setDrawCallback(staticCFunction { _: COpaquePointer? -> resizing?.draw(); Unit })
        // Rests only when the last turn found nothing to do. A frame that drew has already
        // waited for the screen inside Present, and waiting again would halve the rate of
        // anything that animates.
        var busy = true
        if (System.getenv("DXC_HITTEST_CHECK") == "1") native.debugHitTest()
        while (!Win32Loop.closed) {
            drew = false
            // The window's own turn, before anything is read from it. This thread is the one
            // Windows delivers to, so the messages of this frame arrive here or not at all.
            Win32Loop.pump(if (busy) 0.0 else FRAME_SECONDS)
            work.runPending()
            if (DrawBench.due()) DrawBench.run(scene, context) { nanos }
            if (ResizeMetrics.due()) {
                ResizeMetrics.run(
                    phase = { name ->
                        System.err.println(
                            "compose-rust: metrics phase $name ${ResizeMetrics.summary()} " +
                                "skia_cache_limit_mb=${context.resourceCacheLimit / 1048576}",
                        )
                        Win32Loop.reportMetrics(name)
                    },
                    resize = { from, to, steps ->
                        native.debugResize(from.first, from.second, to.first, to.second, steps, 0)
                    },
                )
            }
            var heard = false
            for (event in Win32Loop.drainEvents()) {
                if (reportInput && event.kind != WindowEvent.POINTER_MOVE) {
                    System.err.println("compose-rust: window heard $event")
                }
                // Told which desktop it is, because the key numbers are Windows' own.
                scene.receive(event, win32 = true)
                textInput.receive(event)
                heard = true
            }
            if (!painted || heard || scene.hasInvalidations()) {
                draw()
            }
            // After the drawing, because that is when what is in the window has been placed.
            semantics.pushIfChanged(afterDrawing = drew)
            busy = heard || drew
        }
    }

    /** In this order: a message dispatched after the scene has closed would draw into it. */
    fun close() {
        Win32Loop.setDrawCallback(null)
        resizing = null
        scene.close()
        context.close()
    }

    /** Draws one frame unless one is already being drawn. */
    fun draw(): Boolean {
        if (drawing) return false
        drawing = true
        try {
            // The scene's own work first: during a drag of the window's edge this is the only
            // place that runs at all.
            work.runPending()
            nanos += FRAME_NANOS
            ResizeMetrics.frameBegin()
            // While the window is changing size the C side asks for the frame in CPU
            // pixels, which it copies into the window before the resize returns.
            val drawn = if (Win32Loop.rasterFrameWanted) drawRasterFrame() else drawFrame()
            ResizeMetrics.frameEnd(size.width, size.height)
            if (drawn) {
                painted = true
                drew = true
                WindowStartReport.frameDrawn(size.width, size.height)
            }
        } finally {
            drawing = false
        }
        return true
    }

    /**
     * One frame: take a buffer, let the scene paint it, give it to the screen.
     *
     * False where there was nothing to draw into. A minimised window answers that for as long
     * as it stays down, and a swapchain that could not be made to fit a new size answers it
     * once.
     */
    private fun drawFrame(): Boolean {
        val resource = native.beginFrame() ?: return false
        // After the buffer and not before it: that is where a swapchain waiting to be refitted
        // is refitted, and what is measured here is the buffer that came back.
        val measured = native.measure()
        val fitted = IntSize(measured.width, measured.height)
        val density = Density(measured.scale)
        if (scene.size != fitted || scene.density != density) {
            scene.density = density
            scene.size = fitted
        }
        size = fitted
        val target = BackendRenderTarget.makeDirect3D(
            measured.width,
            measured.height,
            resource.rawValue,
            SWAPCHAIN_FORMAT,
            // One sample and one level: a swapchain buffer is neither multisampled nor
            // mipmapped.
            1,
            1,
        )
        val surface = Surface.makeFromBackendRenderTarget(
            context,
            target,
            SurfaceOrigin.TOP_LEFT,
            SurfaceColorFormat.BGRA_8888,
            ColorSpace.sRGB,
            SurfaceProps(PixelGeometry.RGB_H),
        )
        if (surface == null) {
            // Not ended: ending moves the texture from render target to present, and Skia
            // never put it in render target, so that barrier would be invalid.
            target.close()
            return false
        }
        scene.render(surface.canvas.asComposeCanvas(), nanos)
        // Submitted, not only recorded: a buffer presented before Skia's command list runs is
        // a buffer with nothing in it.
        surface.flushAndSubmit(true)
        surface.close()
        target.close()
        // Waits for the screen, so there is no sleep after this.
        native.endFrame()
        return true
    }

    /**
     * One resize frame on the CPU: the same scene, drawn by Skia's raster backend into the
     * pixels the C side hands over (its DIB), at the size the window is becoming.
     *
     * The same colour type order as the window's buffer reads (BGRA is the DIB's byte order),
     * the same colour space and the same pixel geometry as the GPU frame, so the switch
     * between the two is not visible.
     */
    private fun drawRasterFrame(): Boolean {
        val target = Win32Loop.beginRasterFrame() ?: return false
        val measured = native.measure()
        val fitted = IntSize(target.width, target.height)
        val density = Density(measured.scale)
        if (scene.size != fitted || scene.density != density) {
            scene.density = density
            scene.size = fitted
        }
        size = fitted
        RasterDraw.draw(RasterVariant.chosen, target.pixels, target.rowBytes, fitted.width, fitted.height) { canvas ->
            scene.render(canvas.asComposeCanvas(), nanos)
        }
        Win32Loop.endRasterFrame()
        return true
    }

    companion object {
        /** What a caption button asks of the window: 0 minimises, 1 maximises or restores, 2 closes. */
        fun action(which: Int) = Win32Loop.action(which)

        /**
         * Opens a window, or null where this machine has no Direct3D 12 adapter.
         *
         * Null rather than an exception: a machine without one is not a mistake in this code.
         */
        fun open(
            title: String,
            width: Int,
            height: Int,
            resizable: Boolean = true,
            minWidth: Int = 0,
            minHeight: Int = 0,
            systemChrome: Boolean = true,
        ): Win32Window? {
            if (System.getenv("DXC_LAYOUT_PROFILE") == "1") LayoutProfile.enabled = true
            Win32NativeWindow.configure(resizable, minWidth, minHeight, systemChrome, backdrop = 0)
            val native = Win32NativeWindow.open(title, width, height) ?: return null
            // Resize frames on the CPU unless asked not to (DXC_RASTER_RESIZE=0).
            Win32Loop.setRasterResize(System.getenv("DXC_RASTER_RESIZE") != "0")
            val context = DirectContext.makeDirect3D(native.adapter.rawValue, native.device.rawValue, native.queue.rawValue)
            if (System.getenv("DXC_PIXEL_COMPARE") == "1") PixelCompare.run(context)
            val measured = native.measure()
            WindowStartReport.windowOpened(measured.width, measured.height, measured.scale)
            return Win32Window(native, context, measured)
        }
    }
}

/**
 * The window a resize is drawn for, which is the only one there is. Top level because the
 * callback the C side holds cannot capture anything, so it finds the window here.
 */
private var resizing: Win32Window? = null

/**
 * DXGI_FORMAT_B8G8R8A8_UNORM, what the swapchain was made with and Skia has to be told again.
 * A format that disagrees between the two is a window of swapped colour channels rather than
 * a failure anything reports.
 */
private const val SWAPCHAIN_FORMAT = 87

private const val FRAME_SECONDS = 0.016
private const val FRAME_NANOS = 16_000_000L
