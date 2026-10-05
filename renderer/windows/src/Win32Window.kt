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
import java.lang.System
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps

// The C side of the window: `desktop/c/win32_window.c`, linked into the same executable.
// Called by symbol name, which is also how the native image calls it, so the one C file
// serves both renderers and is compiled the same way for both.

@SymbolName("dxc_native_window_open")
private external fun openWindow(title: CPointer<ByteVar>?, width: Int, height: Int, out: COpaquePointer?): Int

@SymbolName("dxc_native_window_size")
private external fun windowSize(
    window: COpaquePointer?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
    scale: CPointer<FloatVar>?,
)

@SymbolName("dxc_native_frame_begin")
private external fun beginFrame(swapchain: COpaquePointer?, resourceOut: CPointer<COpaquePointerVar>?): Int

@SymbolName("dxc_native_frame_end")
private external fun endFrame(queue: COpaquePointer?)

@SymbolName("dxc_native_set_draw_callback")
private external fun setDrawCallback(callback: CPointer<CFunction<(COpaquePointer?) -> Unit>>?, context: COpaquePointer?)

@SymbolName("dxc_native_set_accessibility")
private external fun setAccessibility(elements: COpaquePointer?, count: Int, window: COpaquePointer?)

@SymbolName("dxc_native_poll_event")
private external fun pollEvent(out: COpaquePointer?): Int

@SymbolName("dxc_native_set_cursor")
private external fun setCursor(shape: Int)

@SymbolName("dxc_native_pump")
private external fun pump(seconds: Double)

@SymbolName("dxc_native_window_closed")
private external fun windowClosed(): Int

@SymbolName("dxc_native_window_configure")
private external fun configureWindow(resizable: Int, minWidth: Int, minHeight: Int, systemChrome: Int, backdrop: Int)

@SymbolName("dxc_native_window_action")
private external fun windowAction(action: Int)

@SymbolName("dxc_native_debug_resize")
private external fun debugResize(
    window: COpaquePointer?,
    view: COpaquePointer?,
    fromWidth: Int,
    fromHeight: Int,
    toWidth: Int,
    toHeight: Int,
    steps: Int,
    pauseMicros: Int,
)

@SymbolName("dxc_native_set_raster_resize")
private external fun setRasterResize(enabled: Int)

@SymbolName("dxc_native_frame_mode")
private external fun frameMode(): Int

@SymbolName("dxc_native_raster_begin")
private external fun rasterBegin(
    pixels: CPointer<COpaquePointerVar>?,
    rowBytes: CPointer<IntVar>?,
    width: CPointer<IntVar>?,
    height: CPointer<IntVar>?,
): Int

@SymbolName("dxc_native_raster_end")
private external fun rasterEnd()

@SymbolName("dxc_native_debug_hit_test")
private external fun debugHitTest(window: COpaquePointer?)

@SymbolName("dxc_native_report_metrics")
private external fun reportMetrics(label: CPointer<ByteVar>?)

/**
 * A window of this renderer's own on Windows, drawn into with Skia through Direct3D 12 and with
 * no toolkit in between.
 *
 * The Kotlin/Native twin of the native image's `Win32Window.kt`, and the same window: the C
 * file opens it, owns the device and the swapchain, translates messages into the shared event
 * record, composes text through IMM32 and answers UI Automation. What is here is what a scene
 * needs from all that, drawn the same way the native image draws it.
 *
 * The frame that belongs to a resize is drawn from inside the resize. While the reader drags an
 * edge, Windows runs a loop of its own inside the message that began the drag and this thread's
 * own loop does not get another turn until the drag ends. The C side calls back into
 * [drawFromInsideAResize] from there, and a frame already being drawn refuses a second one
 * rather than nesting it.
 */
internal class Win32Window private constructor(
    private val window: COpaquePointer,
    private val queue: COpaquePointer,
    private val swapchain: COpaquePointer,
    private val context: DirectContext,
    measured: WindowMeasurement,
) {

    private val reportInput = System.getenv("DXC_REPORT_INPUT") != null

    /** The size the scene was last told. Changed only where a frame finds the buffer is new. */
    private var size = IntSize(measured.width, measured.height)

    private val textInput = NativeTextInput()

    /** The right-click menu of text and selections, drawn by the system. */
    private val textToolbar = Win32TextToolbar { window }

    /** Handed to UI Automation, which the C side answers for. */
    private val semantics = NativeSemantics { elements -> describe(elements) }

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
                setCursor(
                    when (pointerIcon) {
                        PointerIcon.Hand -> CURSOR_HAND
                        PointerIcon.Text -> CURSOR_TEXT
                        PointerIcon.Crosshair -> CURSOR_CROSSHAIR
                        else -> CURSOR_ARROW
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

    fun setContent(content: @Composable () -> Unit) = scene.setContent(content)

    /** Holds the window until it closes. */
    fun run() {
        resizing = this
        setDrawCallback(
            staticCFunction { _: COpaquePointer? -> resizing?.draw(); Unit },
            null,
        )
        // Rests only when the last turn found nothing to do. A frame that drew has already
        // waited for the screen inside Present, and waiting again would halve the rate of
        // anything that animates.
        var busy = true
        if (System.getenv("DXC_HITTEST_CHECK") == "1") debugHitTest(window)
        while (windowClosed() == 0) {
            drew = false
            // The window's own turn, before anything is read from it. This thread is the one
            // Windows delivers to, so the messages of this frame arrive here or not at all.
            pump(if (busy) 0.0 else FRAME_SECONDS)
            work.runPending()
            if (DrawBench.due()) DrawBench.run(scene, context) { nanos }
            if (ResizeMetrics.due()) {
                ResizeMetrics.run(
                    phase = { name ->
                        System.err.println(
                            "compose-rust: metrics phase $name ${ResizeMetrics.summary()} " +
                                "skia_cache_limit_mb=${context.resourceCacheLimit / 1048576}",
                        )
                        memScoped { reportMetrics(name.cstr.ptr) }
                    },
                    resize = { from, to, steps ->
                        debugResize(window, null, from.first, from.second, to.first, to.second, steps, 0)
                    },
                )
            }
            var heard = false
            for (event in drainEvents()) {
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
        setDrawCallback(null, null)
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
            val drawn = if (frameMode() != 0) drawRasterFrame() else drawFrame()
            ResizeMetrics.frameEnd(size.width, size.height)
            if (drawn) {
                painted = true
                drew = true
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
        val resource = memScoped {
            val out = alloc<COpaquePointerVar>()
            if (beginFrame(swapchain, out.ptr) != 0) return false
            out.value
        } ?: return false
        // After the buffer and not before it: that is where a swapchain waiting to be refitted
        // is refitted, and what is measured here is the buffer that came back.
        val measured = measure(window)
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
        endFrame(queue)
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
    private fun drawRasterFrame(): Boolean = memScoped {
        val pixels = alloc<COpaquePointerVar>()
        val rowBytes = alloc<IntVar>()
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        if (rasterBegin(pixels.ptr, rowBytes.ptr, width.ptr, height.ptr) != 0) return false
        val address = pixels.value ?: return false
        val measured = measure(window)
        val fitted = IntSize(width.value, height.value)
        val density = Density(measured.scale)
        if (scene.size != fitted || scene.density != density) {
            scene.density = density
            scene.size = fitted
        }
        size = fitted
        RasterDraw.draw(RasterVariant.chosen, address, rowBytes.value, fitted.width, fitted.height) { canvas ->
            scene.render(canvas.asComposeCanvas(), nanos)
        }
        rasterEnd()
        true
    }

    private fun describe(elements: List<AccessibleElement>) {
        val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
        memScoped {
            val records = allocArray<ByteVar>(MAX_ELEMENTS * ELEMENT_BYTES)
            for ((index, element) in capped.withIndex()) {
                val at = records + index * ELEMENT_BYTES
                at!!.reinterpret<IntVar>()[0] = element.role
                val floats = (at + 4)!!.reinterpret<FloatVar>()
                floats[0] = element.x
                floats[1] = element.y
                floats[2] = element.width
                floats[3] = element.height
                val label = labelBytes(element.label)
                val text = (at + ELEMENT_LABEL_OFFSET)!!
                for (offset in label.indices) text[offset] = label[offset]
                text[label.size] = 0
            }
            setAccessibility(records, capped.size, window)
        }
    }

    companion object {
        /**
         * Opens a window, or null where this machine has no Direct3D 12 adapter.
         *
         * Null rather than an exception: a machine without one is not a mistake in this code.
         */
        /** What a caption button asks of the window: 0 minimises, 1 maximises or restores, 2 closes. */
        fun action(which: Int) = windowAction(which)

        fun open(
            title: String,
            width: Int,
            height: Int,
            resizable: Boolean = true,
            minWidth: Int = 0,
            minHeight: Int = 0,
            systemChrome: Boolean = true,
        ): Win32Window? {
            val pointers = nativeHeap.allocArray<COpaquePointerVar>(WINDOW_POINTERS)
            try {
                configureWindow(if (resizable) 1 else 0, minWidth, minHeight, if (systemChrome) 1 else 0, 0)
                val opened = memScoped { openWindow(title.cstr.ptr, width, height, pointers) }
                if (opened != 0) return null
                // Resize frames on the CPU unless asked not to (DXC_RASTER_RESIZE=0).
                setRasterResize(if (System.getenv("DXC_RASTER_RESIZE") == "0") 0 else 1)
                // Five pointers, in the order the C struct declares them.
                val window = pointers[0] ?: return null
                val device = pointers[1] ?: return null
                val queue = pointers[2] ?: return null
                val adapter = pointers[3] ?: return null
                val swapchain = pointers[4] ?: return null
                val context = DirectContext.makeDirect3D(adapter.rawValue, device.rawValue, queue.rawValue)
                if (System.getenv("DXC_PIXEL_COMPARE") == "1") PixelCompare.run(context)
                val measured = measure(window)
                System.err.println(
                    "compose-rust: a window of our own, ${measured.width}x${measured.height} " +
                        "at ${measured.scale}x, with no toolkit and no virtual machine in it",
                )
                return Win32Window(window, queue, swapchain, context, measured)
            } finally {
                nativeHeap.free(pointers.rawValue)
            }
        }

        private fun measure(window: COpaquePointer): WindowMeasurement = memScoped {
            val width = alloc<IntVar>()
            val height = alloc<IntVar>()
            val scale = alloc<FloatVar>()
            windowSize(window, width.ptr, height.ptr, scale.ptr)
            WindowMeasurement(width.value, height.value, scale.value)
        }

        /** Everything the window heard since the last turn, read out of the C side's queue. */
        private fun drainEvents(): List<WindowEvent> {
            val events = ArrayList<WindowEvent>()
            memScoped {
                val record = allocArray<ByteVar>(EVENT_BYTES)
                while (pollEvent(record) != 0) {
                    val ints = record.reinterpret<IntVar>()
                    val floats = record.reinterpret<FloatVar>()
                    val text = (record + TEXT_OFFSET)!!
                    var length = 0
                    while (length < TEXT_BYTES && text[length] != 0.toByte()) length++
                    events.add(
                        WindowEvent(
                            kind = ints[0],
                            x = floats[1],
                            y = floats[2],
                            buttons = ints[3],
                            modifiers = ints[4],
                            keyCode = ints[5],
                            codePoint = ints[6],
                            text = if (length == 0) "" else text.readBytes(length).decodeToString(),
                        ),
                    )
                }
            }
            return events
        }

        /** Fits a label in the native record without cutting a UTF-8 character in half. */
        private fun labelBytes(label: String): ByteArray {
            val bytes = label.encodeToByteArray()
            if (bytes.size < TEXT_BYTES) return bytes
            var length = TEXT_BYTES - 1
            while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) length--
            return bytes.copyOf(length)
        }
    }
}

/**
 * The window a resize is drawn for, which is the only one there is. Top level because the
 * callback the C side holds cannot capture anything, so it finds the window here.
 */
private var resizing: Win32Window? = null

// The C side's layouts. `struct dxc_native_window` is five pointers; `struct dxc_event` is
// seven four-byte fields and 96 bytes of text; `struct dxc_element` is a role, four floats and
// 96 bytes of label.
private const val WINDOW_POINTERS = 5
private const val EVENT_BYTES = 124
private const val TEXT_OFFSET = 28
private const val TEXT_BYTES = 96
private const val MAX_ELEMENTS = 256
private const val ELEMENT_LABEL_OFFSET = 20
private const val ELEMENT_BYTES = 116

/**
 * DXGI_FORMAT_B8G8R8A8_UNORM, what the swapchain was made with and Skia has to be told again.
 * A format that disagrees between the two is a window of swapped colour channels rather than
 * a failure anything reports.
 */
private const val SWAPCHAIN_FORMAT = 87

private const val CURSOR_ARROW = 0
private const val CURSOR_HAND = 1
private const val CURSOR_TEXT = 2
private const val CURSOR_CROSSHAIR = 3

private const val FRAME_SECONDS = 0.016
private const val FRAME_NANOS = 16_000_000L
