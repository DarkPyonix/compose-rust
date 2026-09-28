@file:JvmName("AppKitWindow")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dioxus.compose.ui.platform

import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer
import org.graalvm.nativeimage.c.type.CFloatPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.graalvm.word.Pointer
import org.graalvm.word.WordFactory

// A window that is ours, drawn into with Skia and with no toolkit in between.
//
// Beside `NativeHostConnection` rather than among the renderer's own files, and for the
// same reason: this is the only other place that names GraalVM types, so a development
// run on a JVM never loads them.
//
// The C side is `c/appkit_window.m`. It owns the window, the view, the layer, the Metal
// device and the queue, and answers with the pointers. Nothing there draws.
//
// The scene this drives is Compose's own, reached through an interface the library marks
// as being for its own modules. There is no other way in: the supported entry builds a
// toolkit window, and a toolkit window is the thing being removed. Kept to this one file
// and pinned to the version in the module file, which is what the rule about unstable
// APIs asks for. A Compose upgrade changes this file or it changes nothing.

@CFunction("dxc_native_window_open")
private external fun openWindow(
    title: CCharPointer?,
    width: Int,
    height: Int,
    out: Pointer?,
): Int

@CFunction("dxc_native_window_size")
private external fun windowSize(
    view: Pointer?,
    width: CIntPointer?,
    height: CIntPointer?,
    scale: CFloatPointer?,
)

@CFunction("dxc_native_frame_begin")
private external fun beginFrame(layer: Pointer?, textureOut: Pointer?): Int

@CFunction("dxc_native_frame_end")
private external fun endFrame(queue: Pointer?)

@CFunction("dxc_native_poll_event")
private external fun pollEvent(out: Pointer?): Int

@CFunction("dxc_native_set_accessibility")
private external fun setAccessibility(elements: Pointer?, count: Int, view: Pointer?)

@CFunction("dxc_native_set_cursor")
private external fun setCursorShape(shape: Int)

@CFunction("dxc_native_pump")
private external fun pumpEvents(seconds: Double)

@CFunction("dxc_native_clipboard_read")
private external fun clipboardRead(out: Pointer?, capacity: Int): Int

@CFunction("dxc_native_clipboard_write")
private external fun clipboardWrite(text: CCharPointer?)

@CFunction("dxc_native_install_menu")
private external fun installMenu(name: CCharPointer?)

@CFunction("dxc_native_window_closed")
private external fun windowClosed(): Int

/**
 * The four pointers a window is, once AppKit has made one.
 *
 * Words rather than objects, because that is what crosses: native-image accepts a word
 * value in straight-line code inside one method and nowhere else, so each is read out
 * once, here, and carried as a plain `Long` after that.
 */
class NativeWindow internal constructor(
    val window: Long,
    val view: Long,
    val device: Long,
    val queue: Long,
    val layer: Long,
) {

    // A word value is made where it is used and nowhere else. Native-image accepts one
    // in straight-line code inside a single method, so a helper that returned one, or a
    // variable that held one across a call, is rejected: `WordFactory.pointer` is
    // written out at each call rather than wrapped.

    /** The size of the drawable in pixels, and how many of them go to a point. */
    fun measure(): WindowMeasurement {
        val width = StackValue.get<CIntPointer>(4)
        val height = StackValue.get<CIntPointer>(4)
        val scale = StackValue.get<CFloatPointer>(4)
        windowSize(WordFactory.pointer(layer), width, height, scale)
        return WindowMeasurement(width.read(), height.read(), scale.read())
    }

    /**
     * The texture this frame paints into, or zero where the system had none to give.
     *
     * Zero is not a failure. It means frames are being produced faster than the screen
     * takes them, and the answer to that is to skip one rather than to wait.
     */
    fun beginFrame(): Long {
        val texture = StackValue.get<Pointer>(8)
        if (beginFrame(WordFactory.pointer(layer), texture) != 0) return 0
        return texture.readWord<Pointer>(0).rawValue()
    }

    /** Puts the painted frame on the screen. */
    fun endFrame() = endFrame(WordFactory.pointer(queue))

}

/**
 * Takes everything the window has heard since the last frame.
 *
 * Drained rather than delivered. AppKit answers on its own thread and the Host keeps its
 * state on the one that draws, so an event that arrived as a call would arrive on the
 * wrong thread; the shell writes them down and this reads them where they can be used.
 */
fun drainWindowEvents(): List<WindowEvent> {
    val record = StackValue.get<Pointer>(EVENT_STRUCT_BYTES)
    val events = ArrayList<WindowEvent>()
    // Everything about the record is read here. A word value may not leave the method it
    // was made in, so the text is copied out byte by byte rather than by handing the
    // pointer to something that knows how to read a string.
    val bytes = ByteArray(TEXT_BYTES)
    while (pollEvent(record) != 0) {
        var length = 0
        while (length < TEXT_BYTES) {
            val byte = record.readByte(TEXT_OFFSET + length)
            if (byte == ZERO) break
            bytes[length] = byte
            length++
        }
        events.add(
            WindowEvent(
                kind = record.readInt(0),
                x = record.readFloat(4),
                y = record.readFloat(8),
                buttons = record.readInt(12),
                modifiers = record.readInt(16),
                keyCode = record.readInt(20),
                codePoint = record.readInt(24),
                text = if (length == 0) "" else String(bytes, 0, length, Charsets.UTF_8),
            ),
        )
    }
    return events
}

/**
 * Hands the platform what the window would tell a reader who cannot see it.
 *
 * Written into stack storage and copied on the other side. The elements are few, they
 * change when the screen changes rather than when a frame is drawn, and the alternative
 * is the platform asking across threads at a moment nobody chose.
 */
fun NativeWindow.describeTo(elements: List<AccessibleElement>) = describeWindow(view, elements)

/**
 * Writes the records and hands them to whichever window asked.
 *
 * Apart from the extension above because the windows the other desktops open are not this
 * class, and what a tree looks like on the way across does not differ between them: one
 * layout, written once, so a field that moves cannot move in one place only.
 */
internal fun describeWindow(view: Long, elements: List<AccessibleElement>) {
    val capped = if (elements.size > MAX_ELEMENTS) elements.take(MAX_ELEMENTS) else elements
    val records = StackValue.get<Pointer>(MAX_ELEMENTS * ELEMENT_BYTES)
    for ((index, element) in capped.withIndex()) {
        val at = index * ELEMENT_BYTES
        records.writeInt(at, element.role)
        records.writeFloat(at + 4, element.x)
        records.writeFloat(at + 8, element.y)
        records.writeFloat(at + 12, element.width)
        records.writeFloat(at + 16, element.height)
        val bytes = element.label.toByteArray(Charsets.UTF_8)
        var length = 0
        while (length < bytes.size && length < TEXT_BYTES - 1) {
            records.writeByte(at + ELEMENT_LABEL_OFFSET + length, bytes[length])
            length++
        }
        records.writeByte(at + ELEMENT_LABEL_OFFSET + length, ZERO)
    }
    setAccessibility(records, capped.size, WordFactory.pointer(view))
}

/**
 * Sets the shape of the pointer over the window.
 *
 * The scene decides: a control that is a link asks for a hand, a field asks for a bar.
 * Which platform cursor that is belongs to the shell, so what crosses is a number.
 */
fun setPointerShape(shape: Int) = setCursorShape(shape)

/**
 * Lets the window answer for itself for a moment.
 *
 * Called once a frame. The thread that draws is the thread the platform delivers on, so a
 * loop that never gave it a turn would be a window that heard nothing.
 */
fun pumpWindowEvents(seconds: Double) = pumpEvents(seconds)

/** True once the reader has closed the window. */
fun isWindowClosed(): Boolean = windowClosed() != 0

/**
 * Gives the application the menu bar every application on this platform has.
 *
 * Without one, the shortcuts a reader expects do nothing: command-Q does not quit and
 * command-C does not copy. The items are the system's own actions and are sent to
 * whatever holds focus, so no window is asked to implement them.
 */
fun installApplicationMenu(name: String) {
    val holder = CTypeConversion.toCString(name)
    try {
        installMenu(holder.get())
    } finally {
        holder.close()
    }
}

/** What is on the clipboard, or empty where it holds something that is not text. */
fun readClipboard(): String {
    val buffer = StackValue.get<Pointer>(CLIPBOARD_BYTES)
    val length = clipboardRead(buffer, CLIPBOARD_BYTES)
    if (length <= 0) return ""
    val bytes = ByteArray(length)
    for (index in 0 until length) {
        bytes[index] = buffer.readByte(index)
    }
    return String(bytes, Charsets.UTF_8)
}

/** Puts text on the clipboard, replacing what was there. */
fun writeClipboard(text: String) {
    val holder = CTypeConversion.toCString(text)
    try {
        clipboardWrite(holder.get())
    } finally {
        holder.close()
    }
}

/**
 * How much of the clipboard a paste may carry.
 *
 * A paragraph rather than a book. What crosses is stack storage, and a field that is
 * handed a novel has a different problem from the one this is solving.
 */
private const val CLIPBOARD_BYTES = 64 * 1024

/** What a pointer can look like, in the small set both sides agree on. */
object PointerShape {
    const val ARROW = 0
    const val HAND = 1
    const val TEXT = 2
    const val CROSSHAIR = 3
    const val RESIZE_LEFT_RIGHT = 4
    const val RESIZE_UP_DOWN = 5
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
private const val TEXT_OFFSET = 28
private const val TEXT_BYTES = 96
private const val EVENT_STRUCT_BYTES = 124

/**
 * Opens a window, or null where this machine has no Metal device.
 *
 * Null rather than an exception: a machine without Metal is not a mistake in this code,
 * and the caller has an older path it can take instead.
 */
fun openNativeWindow(title: String, width: Int, height: Int): NativeWindow? {
    val holder = CTypeConversion.toCString(title)
    try {
        // Four pointers, in the order the C struct declares them.
        val out = StackValue.get<Pointer>(WINDOW_STRUCT_BYTES)
        if (openWindow(holder.get(), width, height, out) != 0) {
            return null
        }
        return NativeWindow(
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

/**
 * Draws a Compose scene into a window of our own, and holds it there.
 *
 * The step worth taking first, and the one everything after it rests on: a scene that
 * Compose composed, painted by Skia into a drawable AppKit gave us, reaching the screen
 * with no toolkit anywhere between. What follows is input, text and the rest, and none of
 * it means anything until this does.
 *
 * Reached by setting `DXC_APPKIT_WINDOW`, so the ordinary path is untouched.
 */
internal fun runAppKitSpike() {
    // The Host is started before there is a window, because what the window should look
    // like is in its first batch and a window cannot be told afterwards. Started on this
    // thread, which is the one every later call to it is made from: the boundary is a
    // direct call on one thread and the Host keeps its state there.
    val host = dioxus.compose.runtime.DioxusHost(NativeHostConnection())
    host.start()
    val asked = host.table.window
    val window = openNativeWindow(
        asked?.title?.takeIf { it.isNotEmpty() } ?: "dioxus-compose",
        if (asked != null && asked.width > 0) asked.width else 520,
        if (asked != null && asked.height > 0) asked.height else 360,
    )
    if (window == null) {
        System.err.println("dioxus-compose: this machine has no Metal device")
        return
    }
    val context = org.jetbrains.skia.DirectContext.makeMetal(window.device, window.queue)
    val measured = window.measure()
    System.err.println(
        "dioxus-compose: a window of our own, ${measured.width}x${measured.height} " +
            "at ${measured.scale}x, with no toolkit in it",
    )

    val report = System.getenv("DXC_REPORT_INPUT") != null
    // Held rather than measured once. The window is resizable, and everything that reads
    // a size reads this: the scene, the render target, and what the scene is told about
    // the window it is in.
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
    // there: a list asking for the rows it is about to show asked from a thread with no
    // Host and was told nothing had been initialised.
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
    installApplicationMenu(asked?.title?.takeIf { it.isNotEmpty() } ?: "dioxus-compose")

    try {
        // A plain loop rather than a clock. Pacing is the frame clock's work and comes
        // later; what this has to show is that what the window hears reaches the scene
        // and changes what the next frame draws.
        var painted = false
        var frame = 0
        while (!isWindowClosed()) {
            frame++
            // The window's own turn, before anything is read from it. This thread is the
            // one AppKit delivers on, so the events of this frame arrive here or not at
            // all. Waiting the frame's length rather than sleeping afterwards, because a
            // window with nothing happening should rest rather than spin.
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
                if (event.kind == WindowEvent.FILES_DROPPED) {
                    val paths = event.text.split('\u0000').filter { it.isNotEmpty() }
                    spikeDroppedFiles.value = "dropped ${paths.size}: ${paths.joinToString(", ")}"
                }
                if (event.kind == WindowEvent.RESIZE) {
                    size = androidx.compose.ui.unit.IntSize(event.x.toInt(), event.y.toInt())
                    scene.size = size
                }
                scene.receive(event)
                textInput.receive(event)
                heard = true
            }
            // Only when there is something to draw. Every frame reaches the window by
            // asking the main thread for a drawable and waiting for it, and the main
            // thread is where AppKit answers everything else: sixty of those a second
            // left the input method unable to reach this process at all, which showed up
            // as every letter being committed on its own instead of composing.
            if (!painted || heard || scene.hasInvalidations()) {
                drawFrame(window, context, scene, frame.toLong() * FRAME_NANOS, size)
                painted = true
                drew = true
            }
            // Every frame, and after the drawing. After, because that is when what is in
            // the window has been placed and can say where it is. Every frame, because a
            // tree that changed on the last one is a tree nobody has been told about, and
            // a window that has gone still is exactly where that would be forgotten.
            // Costs a comparison when nothing has changed, which is almost always.
            semantics.pushIfChanged(afterDrawing = drew)
        }
    } finally {
        scene.close()
        context.close()
        host.shutdown()
    }
}

/** One frame: take a drawable, let the scene paint it, give it to the screen. */
private fun drawFrame(
    window: NativeWindow,
    context: org.jetbrains.skia.DirectContext,
    scene: ComposeScene,
    nanos: Long,
    size: androidx.compose.ui.unit.IntSize,
) {
    val texture = window.beginFrame()
    // Zero means the system had no drawable to give, which happens when frames are made
    // faster than the screen takes them. The answer is to skip one.
    if (texture == 0L) return
    val target = org.jetbrains.skia.BackendRenderTarget.makeMetal(size.width, size.height, texture)
    val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
        context,
        target,
        org.jetbrains.skia.SurfaceOrigin.TOP_LEFT,
        org.jetbrains.skia.SurfaceColorFormat.BGRA_8888,
        org.jetbrains.skia.ColorSpace.sRGB,
        org.jetbrains.skia.SurfaceProps(org.jetbrains.skia.PixelGeometry.RGB_H),
    )
    if (surface == null) {
        target.close()
        window.endFrame()
        return
    }
    scene.render(surface.canvas.asComposeCanvas(), nanos)
    // Submitted, not only recorded. Skia's Metal backend keeps the frame in a command
    // buffer of its own, and a drawable presented before that buffer runs is a drawable
    // with nothing in it: the window came up black with the paint never reaching the GPU.
    surface.flushAndSubmit(true)
    surface.close()
    target.close()
    window.endFrame()
}

/**
 * Something recognisably Compose, so that a screenshot answers a question.
 *
 * Shared with the window Windows opens for itself, which is why it is not private to this
 * file: what a spike draws is not platform work, and two copies of it would drift.
 *
 * Text and a shape: text because it is the part that needs a font manager, a shaper and a
 * layout pass, and a shape because a page of text alone would leave it unclear whether
 * anything was drawn or the window simply stayed empty.
 */
@Composable
internal fun SpikeContent() {
    var clicks by remember { mutableStateOf(0) }
    val dropped = spikeDroppedFiles
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxSize().background(Color(0xFF12321A))) {
        Column(Modifier.padding(top = 40.dp, start = 24.dp)) {
            BasicText("no toolkit here", style = TextStyle(color = Color.White, fontSize = 24.sp))
            BasicText(
                if (dropped.value.isEmpty()) {
                    "composed, painted by Skia, shown by AppKit"
                } else {
                    dropped.value
                },
                style = TextStyle(color = Color(0xFF9CCC9C), fontSize = 14.sp),
            )
            // A field, because typing is what the next step has to carry and this is
            // where it will first show. It holds its own text, the way every field in
            // this renderer does.
            var typed by remember { mutableStateOf("") }
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                modifier = Modifier
                    .padding(top = 16.dp)
                    .size(220.dp, 32.dp)
                    .background(Color(0xFF1E4620)),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
            )
            Box(
                Modifier
                    .padding(top = 24.dp)
                    .size(220.dp, 56.dp)
                    // Hover and click are what this frame is for. A control that changes
                    // under the pointer is the difference between a window that was
                    // drawn and a window that is running.
                    .background(if (hovered) Color(0xFF66BB6A) else Color(0xFF2E7D32))
                    .hoverable(hover)
                    .clickable { clicks++ },
            ) {
                BasicText(
                    if (clicks == 0) "click me" else "clicked $clicks",
                    Modifier.padding(16.dp),
                    style = TextStyle(color = Color.White, fontSize = 18.sp),
                )
            }
        }
    }
}

/**
 * What was last dropped on the window, so a screenshot can show it arrived.
 *
 * Held beside the scene rather than in it, because what a drag carries reaches this side
 * before any node has asked for it: there is no drop target in the tree yet, and this
 * step is about the paths crossing at all.
 */
internal val spikeDroppedFiles = androidx.compose.runtime.mutableStateOf("")

private const val FRAME_SECONDS = 0.016
private const val FRAME_NANOS = 16_000_000L
