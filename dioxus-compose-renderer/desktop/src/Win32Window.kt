@file:JvmName("Win32Window")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dioxus.compose.ui.platform

import org.graalvm.nativeimage.CurrentIsolate
import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.function.CFunctionPointer
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory

// A window that is ours on Windows, drawn into with Skia and with no toolkit in between.
//
// The pair of `AppKitWindow.kt`, and beside it for the same reason it is beside
// `NativeHostConnection`: these are the only places that name GraalVM types, so a
// development run on a JVM never loads them.
//
// The C side is `c/win32_window.c`. It owns the window, the Direct3D device, the queue,
// the adapter and the swapchain, and answers with the pointers. Nothing there draws.
//
// The same C symbols the macOS file calls, because the two C files are alternatives:
// exactly one of them is compiled into an image, and each answers for the window its own
// platform knows how to open. What the five pointers mean differs, which is why each
// platform reads them here rather than sharing a struct. What an event looks like does
// not differ, so `WindowEvent` and `drainWindowEvents` are the macOS file's and used as
// they are, and so are the shell's own turns: `pumpWindowEvents` and `isWindowClosed`
// name what every window does and are declared once, there.

@CFunction("dxc_native_window_open")
private external fun openWindow(
    title: CCharPointer?,
    width: Int,
    height: Int,
    out: Pointer?,
): Int

@CFunction("dxc_native_window_size")
private external fun windowSize(
    window: Pointer?,
    width: CIntPointer?,
    height: CIntPointer?,
    scale: CFloatPointer?,
)

@CFunction("dxc_native_frame_begin")
private external fun beginFrame(swapchain: Pointer?, resourceOut: Pointer?): Int

@CFunction("dxc_native_frame_end")
private external fun endFrame(queue: Pointer?)

@CFunction("dxc_native_set_draw_callback")
private external fun setDrawCallback(callback: CFunctionPointer?, isolateThread: IsolateThread?)

@CFunction("dxc_native_set_accessibility")
private external fun setAccessibility(elements: Pointer?, count: Int, window: Pointer?)

/**
 * The five pointers a window is, once Win32 and DXGI have made one.
 *
 * Words rather than objects, because that is what crosses: native-image accepts a word
 * value in straight-line code inside one method and nowhere else, so each is read out
 * once, here, and carried as a plain `Long` after that.
 */
class Win32NativeWindow internal constructor(
    val window: Long,
    val device: Long,
    val queue: Long,
    val adapter: Long,
    val swapchain: Long,
) {

    // A word value is made where it is used and nowhere else. Native-image accepts one
    // in straight-line code inside a single method, so a helper that returned one, or a
    // variable that held one across a call, is rejected: `WordFactory.pointer` is
    // written out at each call rather than wrapped.

    /** The size of the client area in pixels, and how many of them go to a point. */
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(window), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    /**
     * The buffer this frame paints into, or zero where the swapchain had none to give.
     *
     * Zero is not a failure. The window may have been closed or minimised, or it may have
     * just been given a size the swapchain has yet to be made to fit. The answer to all
     * three is to skip the frame rather than to wait.
     */
    fun beginFrame(): Long {
        val resource = StackValue.get<Pointer>(8)
        if (beginFrame(WordFactory.pointer(swapchain), resource) != 0) return 0
        return resource.readWord<Pointer>(0).rawValue()
    }

    /** Puts the painted frame on the screen. */
    fun endFrame() = endFrame(WordFactory.pointer(queue))

}

/**
 * Opens a window, or null where this machine has no Direct3D 12 adapter.
 *
 * Null rather than an exception: a machine without one is not a mistake in this code, and
 * the caller has an older path it can take instead.
 */
fun openWin32Window(title: String, width: Int, height: Int): Win32NativeWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        // Five pointers, in the order the C struct declares them.
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        if (openWindow(holder.get(), width, height, out) != 0) {
            return null
        }
        return Win32NativeWindow(
            window = out.readWord<Pointer>(0).rawValue(),
            device = out.readWord<Pointer>(8).rawValue(),
            queue = out.readWord<Pointer>(16).rawValue(),
            adapter = out.readWord<Pointer>(24).rawValue(),
            swapchain = out.readWord<Pointer>(32).rawValue(),
        )
    } finally {
        holder.close()
    }
}

private const val WINDOW_STRUCT_BYTES = 40

/**
 * Hands the platform what the window would tell a reader who cannot see it.
 *
 * Written into stack storage and copied on the other side. The elements are few, they
 * change when the screen changes rather than when a frame is drawn, and the alternative
 * is the platform asking across threads at a moment nobody chose.
 */
fun Win32NativeWindow.describeTo(elements: List<AccessibleElement>) {
    val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
    val records = StackValue.get<Pointer>(MAX_ELEMENTS * ELEMENT_BYTES)
    for ((index, element) in capped.withIndex()) {
        val at = index * ELEMENT_BYTES
        records.writeInt(at, element.role)
        records.writeFloat(at + 4, element.x)
        records.writeFloat(at + 8, element.y)
        records.writeFloat(at + 12, element.width)
        records.writeFloat(at + 16, element.height)
        val bytes = win32LabelBytes(element.label)
        for (offset in bytes.indices) {
            records.writeByte(at + ELEMENT_LABEL_OFFSET + offset, bytes[offset])
        }
        records.writeByte(at + ELEMENT_LABEL_OFFSET + bytes.size, ZERO)
    }
    setAccessibility(records, capped.size, WordFactory.pointer(window))
}

/**
 * How many things a screen may say it has.
 *
 * Enough for a screen and not for a document. A list of ten thousand rows is windowed
 * before it reaches the scene, so what is here is what is on screen.
 */
private const val MAX_ELEMENTS = 256
private const val ELEMENT_LABEL_OFFSET = 20
private const val ELEMENT_BYTES = 116
private const val ZERO: Byte = 0
private const val TEXT_BYTES = 96

/** Fits a label in the native record without cutting a UTF-8 character in half. */
internal fun win32LabelBytes(label: String): ByteArray {
    val bytes = label.toByteArray(Charsets.UTF_8)
    if (bytes.size < TEXT_BYTES) return bytes
    var length = TEXT_BYTES - 1
    while (length > 0 && (bytes[length].toInt() and 0xC0) == 0x80) {
        length--
    }
    return bytes.copyOf(length)
}

/**
 * What the swapchain was made with, which Skia has to be told again.
 *
 * `DXGI_FORMAT_R8G8B8A8_UNORM`. Named by its number because the C side holds the header
 * this comes from and nothing on this side can see it, and repeated rather than asked for
 * because a format that disagrees between the two is a window of swapped colour channels
 * rather than a failure anything reports.
 */
private const val SWAPCHAIN_FORMAT = 28

/**
 * The one door a frame is drawn through, and the only thing that decides there is one.
 *
 * Two callers reach it on the same thread. The frame loop asks once a turn, and the
 * window asks from inside a message it is handling, which is how anything is drawn while
 * the reader drags an edge. Nothing inside the drawing hands Windows its messages today,
 * so as the code stands the two do not meet; they are one call apart from meeting,
 * because reading what the window heard is what pumps for more of it. A scene rendered
 * from inside its own render is not something Compose survives, and that is the kind of
 * crash that is found first on somebody else's machine, so the second ask is refused
 * rather than nested.
 *
 * Refused and not queued. What the second caller wanted was the window drawn at the size
 * it now is, and the frame already running is about to do exactly that: it measures the
 * window after taking its buffer, which is after the swapchain has been refitted.
 */
object Win32Frames {

    private var drawing = false

    /**
     * What drawing a frame is, as the loop that owns the scene defines it.
     *
     * Null before a window has been opened and again once it has gone, and both of those
     * are messages arriving with nothing left to draw into rather than mistakes.
     */
    var paint: (() -> Unit)? = null

    /**
     * Draws one frame unless one is already being drawn.
     *
     * False where nothing was drawn, which is either of those two cases.
     */
    fun draw(): Boolean {
        val paint = paint
        if (drawing || paint == null) {
            return false
        }
        drawing = true
        try {
            paint()
        } finally {
            drawing = false
        }
        return true
    }
}

/**
 * Gives the window an address to ask for a frame at, and takes it away again.
 *
 * The thread goes with the address because the other side of it is a Java runtime: an
 * entry point cannot be called without being told which thread of which isolate is
 * calling, and this is the thread the window, the scene and the Host all live on.
 *
 * Written out at the call rather than kept, because native-image accepts a word value in
 * straight-line code inside one method and nowhere else.
 */
private fun registerFrameCallback() =
    setDrawCallback(Win32DrawCallback.POINTER.functionPointer, CurrentIsolate.getCurrentThread())

private fun forgetFrameCallback() = setDrawCallback(
    WordFactory.nullPointer<CFunctionPointer>(),
    WordFactory.nullPointer<IsolateThread>(),
)

/**
 * Draws the application into a window of our own, and holds it there until it closes.
 *
 * The Windows half of the step everything after it rests on: a scene that Compose
 * composed, painted by Skia into a swapchain buffer Direct3D gave us, reaching the screen
 * with no toolkit anywhere between.
 *
 * Reached by setting `DXC_WIN32_WINDOW`, so the ordinary path is untouched.
 */
internal fun runWin32Window() {
    // The Host is started before there is a window, because what the window should look
    // like is in its first batch and a window cannot be told afterwards. Started on this
    // thread, which is the one every later call to it is made from: the boundary is a
    // direct call on one thread and the Host keeps its state there.
    val host = dioxus.compose.runtime.DioxusHost(NativeHostConnection())
    host.start()
    val asked = host.table.window
    val window = openWin32Window(
        asked?.title?.takeIf { it.isNotEmpty() } ?: "dioxus-compose",
        if (asked != null && asked.width > 0) asked.width else 520,
        if (asked != null && asked.height > 0) asked.height else 360,
    )
    if (window == null) {
        System.err.println("dioxus-compose: this machine has no Direct3D 12 adapter")
        host.shutdown()
        return
    }
    val context = org.jetbrains.skia.DirectContext.makeDirect3D(
        window.adapter,
        window.device,
        window.queue,
    )
    val measured = window.measure()
    System.err.println(
        "dioxus-compose: a window of our own, ${measured.width}x${measured.height} " +
            "at ${measured.scale}x, with no toolkit in it",
    )

    val report = System.getenv("DXC_REPORT_INPUT") != null
    // Held rather than measured once. The window is resizable, and what the scene is told
    // about the window it sits in is read from here.
    var size = IntSize(measured.width, measured.height)
    val textInput = NativeTextInput()
    // What the window would tell a reader who cannot see it, read after each frame that
    // painted and handed on when it has changed. Where it goes is UI Automation, which
    // the window answers for rather than this file.
    val semantics = NativeSemantics { elements ->
        if (report) {
            System.err.println("dioxus-compose: the window has ${elements.size} things to say")
        }
        window.describeTo(elements)
    }
    // Kept rather than left to the scene. What a scene picks for itself is the toolkit's
    // queue, and the Host this renderer talks to is on this thread and invisible from
    // there: a list asking for the rows it is about to show asked from a thread with no
    // Host and was told nothing had been initialised.
    val work = FrameDispatcher()
    val scene = CanvasLayersComposeScene(
        density = Density(measured.scale),
        size = size,
        coroutineContext = work,
        platformContext = NativePlatformContext({ size }, textInput, semantics),
    )
    // The application's own tree, drawn by the same interpreter the toolkit path uses.
    // Nothing in it knows which of the two it is running on, which is the point.
    scene.setContent { dioxus.compose.runtime.DioxusContent(host) }

    // What a frame is, wherever the ask comes from. The loop below is one caller and the
    // window's own resize handling is the other, and they draw the same frame.
    var nanos = 0L
    var painted = false
    var drew = false
    Win32Frames.paint = {
        // The scene's own work first. A list that asked for rows on the last frame wants
        // them in hand before this one is measured, and during a drag of the window's
        // edge this is the only place that runs at all.
        work.runPending()
        nanos += FRAME_NANOS
        val at = drawFrame(window, context, scene, nanos)
        if (at != null) {
            size = at
            painted = true
            drew = true
        }
    }
    registerFrameCallback()

    try {
        // Rests only when the last turn found nothing to do. A frame that drew has
        // already waited for the screen inside `Present`, and waiting again on top of
        // that would halve the rate of anything that animates.
        var busy = true
        while (!isWindowClosed()) {
            // Cleared before the window is given its turn rather than after. A drag of an
            // edge draws its frames from inside that turn, and a turn that forgot them
            // would be a window that said nothing about itself for the length of a drag,
            // which is exactly when everything in it has moved.
            drew = false
            // The window's own turn, before anything is read from it. This thread is the
            // one Windows delivers to, so the messages of this frame arrive here or not
            // at all.
            pumpWindowEvents(if (busy) 0.0 else FRAME_SECONDS)
            work.runPending()
            var heard = false
            for (event in drainWindowEvents()) {
                if (report && event.kind != WindowEvent.POINTER_MOVE) {
                    System.err.println("dioxus-compose: window heard $event")
                }
                // Told which desktop it is, because the key numbers differ: the shared
                // table is macOS's, and without this Home arrives as Enter.
                scene.receive(event, win32 = true)
                textInput.receive(event)
                heard = true
            }
            // Only when there is something to draw. A window that is being looked at
            // rather than used should cost a comparison a frame.
            if (!painted || heard || scene.hasInvalidations()) {
                Win32Frames.draw()
            }
            // Every frame, and after the drawing. After, because that is when what is in
            // the window has been placed and can say where it is. Every frame, because a
            // tree that changed on the last one is a tree nobody has been told about, and
            // a window that has gone still is exactly where that would be forgotten.
            semantics.pushIfChanged(afterDrawing = drew)
            busy = heard || drew
        }
    } finally {
        // In this order. A message dispatched after the scene has closed would otherwise
        // be a frame drawn into it.
        Win32Frames.paint = null
        forgetFrameCallback()
        scene.close()
        context.close()
        host.shutdown()
    }
}

/**
 * One frame: take a buffer, let the scene paint it, give it to the screen.
 *
 * Answers the size it was drawn at, which is the swapchain's and not the size anything
 * asked for: a resize is taken inside the call that hands this frame its buffer, so the
 * size that comes back is the one the buffer really is. Null where there was nothing to
 * draw into. A closed window answers that, a minimised one answers it for as long as it
 * stays down, and a swapchain that could not be made to fit a size it was given answers
 * it once.
 */
private fun drawFrame(
    window: Win32NativeWindow,
    context: org.jetbrains.skia.DirectContext,
    scene: ComposeScene,
    nanos: Long,
): IntSize? {
    val resource = window.beginFrame()
    if (resource == 0L) return null
    // After the buffer and not before it, because that is where a swapchain waiting to be
    // refitted is refitted, and what is measured here is the buffer that came back.
    val measured = window.measure()
    val fitted = IntSize(measured.width, measured.height)
    val density = Density(measured.scale)
    // The window was resized, or moved onto a screen of another density. Told to the
    // scene here, because a buffer that fits and a scene that does not is a window drawing
    // its old size into a corner of its new one.
    if (scene.size != fitted || scene.density != density) {
        scene.density = density
        scene.size = fitted
    }
    val target = org.jetbrains.skia.BackendRenderTarget.makeDirect3D(
        measured.width,
        measured.height,
        resource,
        SWAPCHAIN_FORMAT,
        // One sample and one level: a swapchain buffer is neither multisampled nor
        // mipmapped, and saying otherwise would have Skia describe a buffer that is not
        // the one it was handed.
        1,
        1,
    )
    val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
        context,
        target,
        org.jetbrains.skia.SurfaceOrigin.TOP_LEFT,
        org.jetbrains.skia.SurfaceColorFormat.RGBA_8888,
        org.jetbrains.skia.ColorSpace.sRGB,
        org.jetbrains.skia.SurfaceProps(org.jetbrains.skia.PixelGeometry.RGB_H),
    )
    if (surface == null) {
        target.close()
        window.endFrame()
        return null
    }
    scene.render(surface.canvas.asComposeCanvas(), nanos)
    // Submitted, not only recorded. Skia's Direct3D backend keeps the frame in a command
    // list of its own, and a buffer presented before that list runs is a buffer with
    // nothing in it: on macOS the same mistake made the window come up black with the
    // paint never reaching the GPU.
    surface.flushAndSubmit(true)
    surface.close()
    target.close()
    // Waits for the screen, so there is no sleep after this: presenting with an interval
    // of one is what paces a frame that was drawn.
    window.endFrame()
    return fitted
}

private const val FRAME_SECONDS = 0.016
private const val FRAME_NANOS = 16_000_000L
