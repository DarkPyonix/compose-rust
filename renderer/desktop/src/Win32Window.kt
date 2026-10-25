@file:JvmName("Win32Window")
@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.graalvm.windows.SWAPCHAIN_FORMAT
import org.thisisthepy.compose.window.graalvm.windows.Win32Frames
import org.thisisthepy.compose.window.graalvm.windows.Win32GraalWindow
import org.thisisthepy.compose.window.graalvm.windows.openWin32Window
import org.thisisthepy.compose.window.graalvm.windows.describeTo

// The scene on top of the Windows window, and nothing under it.
//
// The window itself (the C calls, the swapchain, the event drain, the frame door and the
// upcall a resize draws through) is the Compose fork's `graalvm-windows` module, the Windows
// pair of the AppKit and X11 windows. This file wires a Compose scene and the Host to it: it
// opens the window, runs the frame loop and paints into the buffers the window hands over.
// The frame's time comes from the window, which reads a monotonic clock.
// The C side is the fork's `extended/window/windows-shared/c/win32_window.c`, compiled into the
// image by build-native-windows.ps1 from the pinned fork commit.

/**
 * Draws the application into a window of our own, and holds it there until it closes.
 *
 * The Windows half of the step everything after it rests on: a scene that Compose
 * composed, painted by Skia into a swapchain buffer Direct3D gave us, reaching the screen
 * with no toolkit anywhere between.
 *
 * The window every Windows run of the native image opens. [autoExitMillis], when given, closes
 * it after that long, for the smoke runs.
 */
internal fun runWin32Window(autoExitMillis: Long? = null) {
    // The Host is started before there is a window, because what the window should look
    // like is in its first batch and a window cannot be told afterwards. Started on this
    // thread, which is the one every later call to it is made from: the boundary is a
    // direct call on one thread and the Host keeps its state there.
    val host = dev.darkpyonix.composerust.runtime.ComposeRustHost(NativeHostConnection())
    host.start()
    val asked = host.table.window
    val window = openWin32Window(
        asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        if (asked != null && asked.width > 0) asked.width else 520,
        if (asked != null && asked.height > 0) asked.height else 360,
    )
    if (window == null) {
        System.err.println(
            "compose-rust: this machine has no Direct3D 12 adapter. Where there is no graphics " +
                "card at all, set DXC_D3D12_WARP=1 to draw with the software one.",
        )
        host.shutdown()
        return
    }
    // Resize frames on the CPU, copied into the window before the resize returns, unless
    // asked not to (DXC_RASTER_RESIZE=0). The same choice, in the same C, as the
    // Kotlin/Native renderer makes.
    window.setRasterResize(System.getenv("DXC_RASTER_RESIZE") != "0")
    val context = org.jetbrains.skia.DirectContext.makeDirect3D(
        window.adapter,
        window.device,
        window.queue,
    )
    val measured = window.measure()
    WindowStartReport.windowOpened(measured.width, measured.height, measured.scale)

    val report = System.getenv("DXC_REPORT_INPUT") != null
    // Held rather than measured once. The window is resizable, and what the scene is told
    // about the window it sits in is read from here.
    var size = IntSize(measured.width, measured.height)
    val textInput = NativeTextInput()
    // Made-up input for measuring without a hand (DXC_SYNTH); unset, nothing here runs.
    val synthetic = System.getenv("DXC_SYNTH")?.let { SyntheticInput(it) }
    // What the window would tell a reader who cannot see it, read after each frame that
    // painted and handed on when it has changed. Where it goes is UI Automation, which
    // the window answers for rather than this file.
    val semantics = NativeSemantics { elements ->
        if (report) {
            System.err.println("compose-rust: the window has ${elements.size} things to say")
        }
        synthetic?.noteElements(elements)
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
        platformContext = NativePlatformContext({ size }, textInput, semantics, window::setCursor),
    )
    // The application's own tree, drawn by the same interpreter the toolkit path uses.
    // Nothing in it knows which of the two it is running on, which is the point.
    // The window's own clipboard (dxc_native_clipboard_read and _write), as the AppKit and X11
    // windows have through NativeWindowContent: Compose's desktop default is the toolkit's, and
    // this image carries no toolkit.
    @Suppress("DEPRECATION")
    scene.setContent {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalClipboard provides WindowClipboard,
            androidx.compose.ui.platform.LocalClipboardManager provides WindowClipboardManagerInstance,
        ) {
            dev.darkpyonix.composerust.runtime.ComposeRustContent(host)
        }
    }
    WindowStartReport.sceneCreated(size.width, size.height, measured.scale)

    // What a frame is, wherever the ask comes from. The loop below is one caller and the
    // window's own resize handling is the other, and they draw the same frame.
    var painted = false
    var drew = false
    Win32Frames.paint = {
        // The scene's own work first. A list that asked for rows on the last frame wants
        // them in hand before this one is measured, and during a drag of the window's
        // edge this is the only place that runs at all. Compose's main-thread work first,
        // as the AppKit and X11 windows do: it runs here, once a frame, not on Swing's queue.
        FrameMainDispatcher.runPending()
        work.runPending()
        val nanos = window.frameTimeNanos()
        val begun = System.nanoTime()
        LatencyTrace.mark("draw begin (invalidated=${scene.hasInvalidations()})")
        val at = if (window.rasterFrame()) drawRasterFrame(window, scene, nanos) else drawFrame(window, context, scene, nanos)
        if (at != null) {
            size = at
            painted = true
            drew = true
            WindowStartReport.frameDrawn(at.width, at.height)
            LatencyTrace.mark("draw end ${at.width}x${at.height}")
        }
        LatencyTrace.frameDrawn(System.nanoTime() - begun)
    }
    window.registerFrameCallback()
    val started = System.nanoTime()

    try {
        // Rests only when the last turn found nothing to do. A frame that drew has
        // already waited for the screen inside `Present`, and waiting again on top of
        // that would halve the rate of anything that animates.
        var busy = true
        while (!window.closed()) {
            if (autoExitMillis != null && (System.nanoTime() - started) / 1_000_000 >= autoExitMillis) {
                break
            }
            // Cleared before the window is given its turn rather than after. A drag of an
            // edge draws its frames from inside that turn, and a turn that forgot them
            // would be a window that said nothing about itself for the length of a drag,
            // which is exactly when everything in it has moved.
            drew = false
            // The window's own turn, before anything is read from it. This thread is the
            // one Windows delivers to, so the messages of this frame arrive here or not
            // at all.
            window.pump(if (busy) 0.0 else FRAME_SECONDS)
            LatencyTrace.mark("loop turn")
            FrameMainDispatcher.runPending()
            work.runPending()
            var heard = false
            // DXC_SYNTH's type and click modes, as window events. Its resize and keys modes
            // are not wired here: the probe drives real drags from outside the process.
            for (event in synthetic?.due(System.nanoTime(), size) ?: emptyList()) {
                scene.receive(event, win32 = true)
                textInput.receive(event)
                LatencyTrace.mark("synthetic ${event.kind} sent")
            }
            for (event in window.drainEvents()) {
                if (report && event.kind != WindowEvent.POINTER_MOVE) {
                    System.err.println("compose-rust: window heard $event")
                }
                if (LatencyTrace.enabled && event.kind != WindowEvent.POINTER_MOVE) {
                    LatencyTrace.mark("window heard ${event.kind}")
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
        LatencyTrace.summary()
    } finally {
        // In this order. A message dispatched after the scene has closed would otherwise
        // be a frame drawn into it.
        Win32Frames.paint = null
        window.forgetFrameCallback()
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
    window: Win32GraalWindow,
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

/**
 * One resize frame on the CPU: the same scene, drawn by Skia's raster backend into the
 * pixels the C side hands over (its DIB: BGRA, top-down), with the GPU frame's colour
 * space and pixel geometry so the switch between the two is not visible, then copied into
 * the window before the resize returns. Null where the C side had no pixels to give.
 */
private fun drawRasterFrame(window: Win32GraalWindow, scene: ComposeScene, nanos: Long): IntSize? {
    val target = window.rasterBegin() ?: return null
    val measured = window.measure()
    val fitted = IntSize(target.width, target.height)
    val density = Density(measured.scale)
    if (scene.size != fitted || scene.density != density) {
        scene.density = density
        scene.size = fitted
    }
    val surface = org.jetbrains.skia.Surface.makeRasterDirect(
        org.jetbrains.skia.ImageInfo(
            target.width,
            target.height,
            org.jetbrains.skia.ColorType.BGRA_8888,
            org.jetbrains.skia.ColorAlphaType.PREMUL,
            org.jetbrains.skia.ColorSpace.sRGB,
        ),
        target.address,
        target.rowBytes,
        org.jetbrains.skia.SurfaceProps(org.jetbrains.skia.PixelGeometry.RGB_H),
    )
    scene.render(surface.canvas.asComposeCanvas(), nanos)
    surface.close()
    window.rasterEnd()
    return fitted
}

private const val FRAME_SECONDS = 0.016
