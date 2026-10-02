@file:JvmName("X11Window")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dioxus.compose.ui.platform

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory

// X11 and GLX own the window and framebuffer. The event record, scene input conversion
// and window content are shared with the other desktop windows.
@CFunction("dxc_native_window_open")
private external fun openWindow(title: CCharPointer?, width: Int, height: Int, out: Pointer?): Int

@CFunction("dxc_native_window_size")
private external fun windowSize(window: Pointer?, width: CIntPointer?, height: CIntPointer?, scale: CFloatPointer?)

@CFunction("dxc_native_frame_begin")
private external fun beginFrame(window: Pointer?): Int

@CFunction("dxc_native_frame_end")
private external fun endFrame(display: Pointer?)

class X11NativeWindow internal constructor(
    val window: Long,
    val view: Long,
    val device: Long,
    val queue: Long,
    val layer: Long,
) {
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(window), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    fun beginFrame(): Boolean = beginFrame(WordFactory.pointer(window)) == 0
    fun endFrame() = endFrame(WordFactory.pointer(queue))
}

/**
 * Hands the window what it would tell a reader who cannot see it.
 *
 * The same records the other windows are given, written by the same code: what a tree looks
 * like on the way across does not differ between platforms, and what each platform does
 * with it does. This one keeps it until something on this desktop is able to answer from
 * it.
 */
fun X11NativeWindow.describeTo(elements: List<AccessibleElement>) = describeWindow(window, elements)

fun openX11Window(title: String, width: Int, height: Int): X11NativeWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        if (openWindow(holder.get(), width, height, out) != 0) return null
        return X11NativeWindow(
            window = out.readWord<Pointer>(0).rawValue(),
            view = out.readWord<Pointer>(8).rawValue(),
            device = out.readWord<Pointer>(16).rawValue(),
            queue = out.readWord<Pointer>(24).rawValue(),
            layer = out.readWord<Pointer>(32).rawValue(),
        )
    } finally {
        holder.close()
    }
}

private const val WINDOW_STRUCT_BYTES = 40
private const val GL_RGBA8 = 0x8058

/**
 * Draws a Compose scene into a window of our own on X11, and holds it there until it is
 * closed.
 *
 * The pair of the macOS loop and the same shape: the window is given its turn to hear
 * things, the scene's own work is run on the thread that draws, what the window heard
 * reaches the scene, and a frame is drawn when something has changed. XWayland takes the
 * same connection, so this is the loop on a Wayland desktop as well, until a Wayland window
 * of its own is written.
 *
 * One thing is not in the loop, and it is the thing the loop cannot do: the frame that
 * belongs to a resize is drawn inside the handling of the resize, by the window, through
 * the painter registered below. A loop that drew it on its next turn would be a window
 * whose edge moves before its content does.
 *
 * Reached by setting `DXC_X11_WINDOW`, so the ordinary path is untouched.
 */
internal fun runX11Window() {
    // The Host is started before there is a window, because what the window should look
    // like is in its first batch and a window cannot be told afterwards. Started on this
    // thread, which is the one every later call to it is made from.
    val host = dioxus.compose.runtime.DioxusHost(NativeHostConnection())
    host.start()
    val asked = host.table.window
    val window = openX11Window(
        asked?.title?.takeIf { it.isNotEmpty() } ?: "dioxus-compose",
        if (asked != null && asked.width > 0) asked.width else 520,
        if (asked != null && asked.height > 0) asked.height else 360,
    )
    if (window == null) {
        System.err.println("dioxus-compose: X11 or a GLX visual is unavailable")
        host.shutdown()
        return
    }
    val context = org.jetbrains.skia.DirectContext.makeGL()
    val measured = window.measure()
    System.err.println(
        "dioxus-compose: a window of our own, ${measured.width}x${measured.height} " +
            "at ${measured.scale}x, with no toolkit in it",
    )

    val report = System.getenv("DXC_REPORT_INPUT") != null
    // Held rather than measured once. Everything that reads a size reads this: the scene,
    // the render target, and what the scene is told about the window it is in.
    var size = androidx.compose.ui.unit.IntSize(measured.width, measured.height)
    val textInput = NativeTextInput()
    val semantics = NativeSemantics { elements ->
        if (report) {
            System.err.println("dioxus-compose: the window has ${elements.size} things to say")
        }
        window.describeTo(elements)
    }
    // Kept rather than left to the scene. What a scene picks for itself is the toolkit's
    // queue, and the Host this renderer talks to is on this thread and invisible from
    // there.
    val work = FrameDispatcher()
    val scene = CanvasLayersComposeScene(
        density = androidx.compose.ui.unit.Density(measured.scale),
        size = size,
        coroutineContext = work,
        platformContext = NativePlatformContext({ size }, textInput, semantics),
    )
    // The application's own tree, drawn by the same interpreter the toolkit path uses.
    // Nothing in it knows which of the two it is running on, which is the point.
    scene.setContent { dioxus.compose.runtime.DioxusContent(host) }

    // A clock rather than a count of turns, because a turn and a frame are no longer the
    // same thing: a resize draws its own, and a count only the loop advanced would hand
    // two frames in a row the same time and stop whatever is animating between them.
    val opened = System.nanoTime()
    var painted = false
    val frames = WindowFrames({ window.measure() }) { fitted, density ->
        // Told to the scene here, in the frame that is about to be drawn at that size,
        // because a drawable that fits and a scene that does not is a window drawing its
        // old size into a corner of its new one.
        if (scene.size != fitted || scene.density != density) {
            scene.density = density
            scene.size = fitted
        }
        size = fitted
        if (drawFrame(window, context, scene, System.nanoTime() - opened, fitted)) {
            painted = true
        }
    }
    try {
        // Before the first frame and before the first event is read, because the events
        // that mapped the window are already waiting and one of them is its first real
        // size. Inside the try, so that whatever happens next the window is left asking
        // nothing of a scene that has closed.
        setX11FramePainter { frames.draw() }
        while (!isWindowClosed()) {
            // The window's own turn, before anything is read from it. This thread is the
            // one the display server answers on, so the events of this frame arrive here
            // or not at all. Waiting the frame's length rather than sleeping afterwards,
            // because a window with nothing happening should rest rather than spin, and
            // because a resize that arrives during the wait is drawn inside it.
            pumpWindowEvents(FRAME_SECONDS)
            // Before the events and before the drawing. What is waiting here is the
            // scene's own work, and a list that asked for rows on the last frame wants
            // them in hand before this one is measured.
            work.runPending()
            var heard = false
            var drew = false
            for (event in drainWindowEvents()) {
                if (report && event.kind != WindowEvent.POINTER_MOVE) {
                    System.err.println("dioxus-compose: window heard $event")
                }
                scene.receive(event)
                heard = true
            }
            // Only when there is something to draw. A window that is being resized has
            // already had its frame drawn by the resize, and a window where nothing is
            // happening should leave the screen alone.
            if (!painted || heard || scene.hasInvalidations()) {
                drew = frames.draw()
            }
            // Every frame, and after the drawing. After, because that is when what is in
            // the window has been placed and can say where it is. Every frame, because a
            // tree that changed on the last one is a tree nobody has been told about, and
            // a window that has gone still is exactly where that would be forgotten.
            semantics.pushIfChanged(afterDrawing = drew)
        }
    } finally {
        // Before the scene closes. A resize arriving between the two would otherwise ask a
        // scene that has gone to draw into a context that has gone with it.
        clearX11FramePainter()
        scene.close()
        context.close()
        host.shutdown()
    }
}

/**
 * One frame: take the framebuffer, let the scene paint it, give it to the screen.
 *
 * False where there was nothing to draw into, which is a closed window or one with no
 * pixels. The size is the caller's, measured in the same step that decided to draw, so that
 * what Skia is told and what the scene was told cannot disagree.
 */
private fun drawFrame(
    window: X11NativeWindow,
    context: org.jetbrains.skia.DirectContext,
    scene: ComposeScene,
    nanos: Long,
    size: androidx.compose.ui.unit.IntSize,
): Boolean {
    if (!window.beginFrame()) return false
    val target = org.jetbrains.skia.BackendRenderTarget.makeGL(
        size.width, size.height, 0, 0, 0, GL_RGBA8,
    )
    val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
        context,
        target,
        org.jetbrains.skia.SurfaceOrigin.BOTTOM_LEFT,
        org.jetbrains.skia.SurfaceColorFormat.RGBA_8888,
        org.jetbrains.skia.ColorSpace.sRGB,
        org.jetbrains.skia.SurfaceProps(org.jetbrains.skia.PixelGeometry.RGB_H),
    )
    if (surface == null) {
        target.close()
        return false
    }
    scene.render(surface.canvas.asComposeCanvas(), nanos)
    // Submitted, not only recorded. Skia keeps the frame in work of its own, and a buffer
    // swapped before that work runs is a buffer with nothing in it.
    surface.flushAndSubmit(true)
    surface.close()
    target.close()
    window.endFrame()
    return true
}

/**
 * How long a turn of the loop is willing to wait for something to happen.
 *
 * A frame at sixty per second. It is a ceiling rather than a pace: anything arriving sooner
 * ends the wait, and a resize is drawn inside it rather than after it.
 */
private const val FRAME_SECONDS = 0.016
