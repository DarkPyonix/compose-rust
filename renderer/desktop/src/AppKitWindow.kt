@file:JvmName("AppKitWindow")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

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
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.graalvm.macos.NativeWindow
import org.thisisthepy.compose.window.graalvm.macos.describeTo
import org.thisisthepy.compose.window.graalvm.macos.drainWindowEvents
import org.thisisthepy.compose.window.graalvm.macos.installApplicationMenu
import org.thisisthepy.compose.window.graalvm.macos.isWindowClosed
import org.thisisthepy.compose.window.graalvm.macos.openNativeWindow
import org.thisisthepy.compose.window.graalvm.macos.pumpWindowEvents
import org.thisisthepy.compose.window.graalvm.macos.setPointerShape
import org.thisisthepy.compose.window.graalvm.macos.AccessibleElement as AppKitElement

// A window that is ours, drawn into with Skia and with no toolkit in between.
//
// The window itself, its C side and the calls that reach it are the Compose fork's
// `extended/window/graalvm/graalvm-macos` module. What is here is the Compose half: the
// scene the window is drawn from, the events it hears handed to that scene, and the loop.
//
// The scene this drives is Compose's own, reached through an interface the library marks
// as being for its own modules. There is no other way in: the supported entry builds a
// toolkit window, and a toolkit window is the thing being removed. Kept to the window
// files and pinned to the version in the module file, which is what the rule about
// unstable APIs asks for. A Compose upgrade changes these files or it changes nothing.

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
    val host = dev.darkpyonix.composerust.runtime.ComposeRustHost(NativeHostConnection())
    host.start()
    val asked = host.table.window
    val window = openNativeWindow(
        asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        if (asked != null && asked.width > 0) asked.width else 520,
        if (asked != null && asked.height > 0) asked.height else 360,
    )
    if (window == null) {
        System.err.println("compose-rust: this machine has no Metal device")
        return
    }
    val context = org.jetbrains.skia.DirectContext.makeMetal(window.device, window.queue)
    val measured = window.measure()
    System.err.println(
        "compose-rust: a window of our own, ${measured.width}x${measured.height} " +
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
            System.err.println("compose-rust: the window has ${elements.size} things to say")
        }
        window.describeTo(elements.map { AppKitElement(it.role, it.x, it.y, it.width, it.height, it.label) })
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
        platformContext = NativePlatformContext({ size }, textInput, semantics, ::setPointerShape),
    )
    // The application's own tree, drawn by the same interpreter the toolkit path uses.
    // Nothing in it knows which of the two it is running on, which is the point.
    scene.setContent { dev.darkpyonix.composerust.runtime.ComposeRustContent(host) }
    installApplicationMenu(asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust")

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
                    System.err.println("compose-rust: window heard $event")
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
