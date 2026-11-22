@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import dev.darkpyonix.composerust.protocol.Chrome
import java.lang.System
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.windows.Win32Loop
import platform.windows.BITMAPINFO
import platform.windows.BI_RGB
import platform.windows.BitBlt
import platform.windows.ClientToScreen
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.DIB_RGB_COLORS
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.GetDC
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.ReleaseDC
import platform.windows.SRCCOPY
import platform.windows.SelectObject
import platform.windows.SetForegroundWindow
import platform.windows.GetClientRect

// FR-82's checks through the real Windows window: the diagnostic scene drawn by the renderer in
// a window of its own, judged against what the screen shows. Here and not in a test source set
// for the reason Fr53WindowsCheck.kt gives: only the renderer is linked with the MSVC window.
// CI runs them once per display scale (100, 125, 150 and 200 %, set with
// .github/scripts/set-display-scale.c), with FR82_EXPECT_DPI naming the DPI it set.

/** Setting this runs the checks: `COMPOSE_RUST_FR82_WINDOWS_CHECK=1 consumer.exe --self-check`. */
internal const val FR82_WINDOWS_CHECK_ENV = "COMPOSE_RUST_FR82_WINDOWS_CHECK"

/** The window's DPI the run expects (96, 120, 144, 192), or unset to take whatever it is. */
internal const val FR82_EXPECT_DPI_ENV = "FR82_EXPECT_DPI"

/**
 * Runs the checks when [FR82_WINDOWS_CHECK_ENV] is set. Null when not asked, true when every
 * one passed. Each result is a line on standard error, `compose-rust: <name>: ok`,
 * `compose-rust: <name>: FAILED <why>` or `compose-rust: <name>: skipped <why>`.
 */
internal fun runFr82WindowsChecksIfAsked(): Boolean? {
    val asked = System.getenv(FR82_WINDOWS_CHECK_ENV) ?: return null
    if (asked == "live") return runLiveCheck()
    val frame = try {
        Fr82Window.open().use { it.frame() }
    } catch (error: Throwable) {
        System.err.println("compose-rust: fr82 windows checks: FAILED the scene could not be drawn and captured: ${error.message}")
        return false
    }
    System.err.println(
        "compose-rust: fr82 windows scale ${frame.scale} dpi ${frame.dpi} client ${frame.clientWidth}x${frame.clientHeight} " +
            "surface ${frame.surfaceWidth}x${frame.surfaceHeight} density ${frame.density}",
    )
    val checks: List<Pair<String, () -> String?>> = listOf(
        "fr82_windows_process_is_per_monitor_v2_before_the_first_window" to { perMonitorV2(frame) },
        "fr82_surface_is_device_pixels_at_every_scale" to { surfaceIsDevicePixels(frame) },
        "fr82_screen_matches_the_drawn_frame" to { screenMatchesDrawnFrame(frame) },
        "fr82_one_dp_line_is_whole_device_pixels_at_integer_scales" to { oneDpLines(frame) },
        "fr82_curved_edges_are_antialiased" to { curvedEdges(frame) },
        "fr82_glyph_edges_are_not_blurred" to { glyphEdges(frame) },
        "fr82_fractional_scale_matches_offscreen_compose" to { fractionalMatchesOffscreen(frame) },
        // Run on its own, with the scale tool (COMPOSE_RUST_FR82_WINDOWS_CHECK=live).
        "fr82_scale_change_redraws_at_the_new_scale" to { "${SKIP}run with $FR82_WINDOWS_CHECK_ENV=live" },
    )
    var passed = true
    for ((name, body) in checks) {
        val answer = try {
            body()
        } catch (error: Throwable) {
            "threw ${error.message}"
        }
        when {
            answer == null -> System.err.println("compose-rust: $name: ok")
            answer.startsWith(SKIP) -> System.err.println("compose-rust: $name: skipped ${answer.removePrefix(SKIP)}")
            else -> {
                passed = false
                System.err.println("compose-rust: $name: FAILED $answer")
            }
        }
    }
    System.err.println(if (passed) "compose-rust fr82 windows checks: ok" else "compose-rust fr82 windows checks: FAILED")
    return passed
}

private const val SKIP = "skip: "

/**
 * One frame of the diagnostic scene: what the renderer drew (read back from the swapchain's
 * buffer before it was presented) and what the screen showed in the client area afterwards
 * (the composed desktop, copied with BitBlt). Both BGRA, rows unpadded.
 */
private class Fr82Frame(
    val dpi: Int,
    val awarenessIsPerMonitorV2: Boolean?,
    val clientWidth: Int,
    val clientHeight: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val density: Float,
    val drawn: ByteArray,
    val screen: ByteArray,
) {
    val scale: Float get() = dpi / 96f
}

/**
 * A window showing the diagnostic scene, opened once and measured as often as a check asks:
 * [frame] draws a frame, reads it back and copies the screen. [turnFor] serves it without
 * input, which is how a scale change has to be followed.
 */
private class Fr82Window private constructor(val window: Win32Window) : AutoCloseable {
    /** The densities the scene was drawn at, in the order they came. */
    val densities = ArrayList<Float>()

    /** Serves the window for [millis], or until [until] answers true: the pump, then its turn. */
    fun turnFor(millis: Long, until: () -> Boolean = { false }) {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow().inWholeMilliseconds < millis && !until()) {
            Win32Loop.pump(0.016)
            window.turn()
            val density = window.drawnDensity
            if (densities.lastOrNull() != density) densities += density
        }
    }

    fun frame(): Fr82Frame {
        var drawn: ByteArray? = null
        var width = 0
        var height = 0
        window.readBack = { pixels, w, h ->
            drawn = pixels
            width = w
            height = h
        }
        window.draw()
        turnFor(500)
        val pixels = checkNotNull(drawn) { "the frame could not be read back" }
        val client = clientSize(window.handle)
        return Fr82Frame(
            dpi = (window.pixelsPerPoint() * 96f).roundToInt(),
            awarenessIsPerMonitorV2 = windowIsPerMonitorV2(window.handle),
            clientWidth = client.first,
            clientHeight = client.second,
            surfaceWidth = width,
            surfaceHeight = height,
            density = window.drawnDensity,
            drawn = pixels,
            screen = captureClient(window.handle, client.first, client.second),
        )
    }

    override fun close() {
        window.destroy()
        window.release()
    }

    companion object {
        fun open(): Fr82Window {
            val start = TimeSource.Monotonic.markNow()
            val window = checkNotNull(
                Win32Window.openOf(
                    PlatformWindowConfig(
                        nodeId = 1,
                        title = "fr82",
                        width = FR82_SCENE_WIDTH_DP + 40f,
                        height = FR82_SCENE_HEIGHT_DP + 40f,
                        resizable = false,
                        chrome = Chrome.System,
                    ),
                ) { start.elapsedNow().inWholeNanoseconds },
            ) { "no window (no Direct3D 12 adapter)" }
            window.setContent {
                Box(Modifier.fillMaxSize().background(Color.White)) { Fr82DiagnosticScene() }
            }
            SetForegroundWindow(window.handle.reinterpret())
            return Fr82Window(window).also { it.turnFor(1_000) }
        }
    }
}

/** The client area's size, in device pixels. */
private fun clientSize(handle: COpaquePointer): Pair<Int, Int> = memScoped {
    val rect = alloc<RECT>()
    GetClientRect(handle.reinterpret(), rect.ptr)
    (rect.right - rect.left) to (rect.bottom - rect.top)
}

/** What the screen shows in the client area now: the composed desktop, top-down BGRA. */
private fun captureClient(handle: COpaquePointer, width: Int, height: Int): ByteArray = memScoped {
    val origin = alloc<POINT>()
    origin.x = 0
    origin.y = 0
    ClientToScreen(handle.reinterpret(), origin.ptr)
    val screen = GetDC(null)
    val memory = CreateCompatibleDC(screen)
    val info = alloc<BITMAPINFO>()
    info.bmiHeader.biSize = 40u
    info.bmiHeader.biWidth = width
    info.bmiHeader.biHeight = -height
    info.bmiHeader.biPlanes = 1u.toUShort()
    info.bmiHeader.biBitCount = 32u.toUShort()
    info.bmiHeader.biCompression = BI_RGB.toUInt()
    val bits = alloc<COpaquePointerVar>()
    val bitmap = CreateDIBSection(memory, info.ptr, DIB_RGB_COLORS.toUInt(), bits.ptr, null, 0u)
    val old = SelectObject(memory, bitmap)
    try {
        check(BitBlt(memory, 0, 0, width, height, screen, origin.x, origin.y, SRCCOPY.toUInt()) != 0) {
            "BitBlt from the screen failed"
        }
        checkNotNull(bits.value).reinterpret<ByteVar>().readBytes(width * height * 4)
    } finally {
        SelectObject(memory, old)
        DeleteObject(bitmap)
        DeleteDC(memory)
        ReleaseDC(null, screen)
    }
}

/**
 * Whether the window's DPI awareness is per-monitor v2, or null where the system has no such
 * call. Looked up at run time: the toolchain's headers may not declare Windows 10's calls.
 */
private fun windowIsPerMonitorV2(handle: COpaquePointer): Boolean? {
    val user32 = GetModuleHandleW("user32.dll") ?: return null
    val contextOf = GetProcAddress(user32, "GetWindowDpiAwarenessContext")
        ?.reinterpret<CFunction<(COpaquePointer?) -> COpaquePointer?>>() ?: return null
    val equal = GetProcAddress(user32, "AreDpiAwarenessContextsEqual")
        ?.reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>() ?: return null
    // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 is ((DPI_AWARENESS_CONTEXT)-4).
    val perMonitorV2 = (-4L).toCPointer<kotlinx.cinterop.CPointed>()
    return equal(contextOf(handle), perMonitorV2) != 0
}

// ---- the checks: null passes, a sentence fails, SKIP + a sentence does not apply ----------

/** Criterion 7: per-monitor v2 before the first window, and the DPI the run set. */
private fun perMonitorV2(frame: Fr82Frame): String? {
    val aware = frame.awarenessIsPerMonitorV2 ?: return "the system has no GetWindowDpiAwarenessContext"
    if (!aware) return "the window's DPI awareness context is not per-monitor v2"
    val expected = System.getenv(FR82_EXPECT_DPI_ENV)?.toIntOrNull() ?: return null
    return if (frame.dpi == expected) null else "the window's DPI is ${frame.dpi}, the display was set to $expected"
}

/** Criterion 1: the surface is the client area in device pixels, and the density is the scale. */
private fun surfaceIsDevicePixels(frame: Fr82Frame): String? {
    if (frame.surfaceWidth != frame.clientWidth || frame.surfaceHeight != frame.clientHeight) {
        return "surface ${frame.surfaceWidth}x${frame.surfaceHeight} is not the client area ${frame.clientWidth}x${frame.clientHeight}"
    }
    if (abs(frame.density - frame.scale) > 0.001f) return "density ${frame.density} is not the scale ${frame.scale}"
    return null
}

/** Criterion 2: the screen shows the drawn frame: mean channel difference <= 1, over 8 on <= 0.1 %. */
private fun screenMatchesDrawnFrame(frame: Fr82Frame): String? {
    if (frame.drawn.size != frame.screen.size) return "drawn ${frame.drawn.size} bytes, captured ${frame.screen.size}"
    val (mean, share) = difference(frame.drawn, frame.screen)
    return if (within(mean, share)) null else "mean channel difference $mean, ${share * 100} % of pixels over 8"
}

/** Two BGRA pictures of one size: the mean channel difference, and the share of pixels over 8 on a channel. */
private fun difference(a: ByteArray, b: ByteArray): Pair<Double, Double> {
    var sum = 0L
    var over = 0
    val pixels = a.size / 4
    for (pixel in 0 until pixels) {
        var worst = 0
        for (channel in 0 until 3) {
            val index = pixel * 4 + channel
            val d = abs((a[index].toInt() and 0xFF) - (b[index].toInt() and 0xFF))
            sum += d
            if (d > worst) worst = d
        }
        if (worst > 8) over++
    }
    return sum.toDouble() / (pixels * 3) to over.toDouble() / pixels
}

/** Criterion 2's tolerance: mean channel difference at most 1, at most 0.1 % of pixels over 8. */
private fun within(mean: Double, share: Double): Boolean = mean <= 1.0 && share <= 0.001

/** The grey (green channel) of the screen's pixel at device pixel ([x], [y]). */
private fun Fr82Frame.grey(x: Int, y: Int): Int = screen[(y * clientWidth + x) * 4 + 1].toInt() and 0xFF

private fun Fr82Frame.px(dp: Float): Int = (dp * scale).roundToInt()

/** Criterion 3: at integer scales, a 1 dp bar is exactly `s` black device pixels with white on both sides. */
private fun oneDpLines(frame: Fr82Frame): String? {
    val s = frame.scale
    if (abs(s - s.roundToInt()) > 0.001f) return "${SKIP}scale $s is not an integer"
    val n = s.roundToInt()
    // (a) the vertical bar at x = 10 dp, read at y = 60 dp; (b) the horizontal bar at y = 10 dp, read at x = 80 dp.
    val row = frame.px(60f)
    val left = frame.px(10f)
    for (x in left until left + n) if (frame.grey(x, row) > 8) return "vertical bar pixel x=$x is ${frame.grey(x, row)}, not black"
    if (frame.grey(left - 1, row) < 247 || frame.grey(left + n, row) < 247) return "the vertical bar bleeds grey beside it"
    val column = frame.px(80f)
    val top = frame.px(10f)
    for (y in top until top + n) if (frame.grey(column, y) > 8) return "horizontal bar pixel y=$y is ${frame.grey(column, y)}, not black"
    if (frame.grey(column, top - 1) < 247 || frame.grey(column, top + n) < 247) return "the horizontal bar bleeds grey beside it"
    return null
}

/**
 * The pixels between white (>= 240) and black (<= 15) walking from ([x0], [y0]) by ([dx], [dy])
 * device pixels, and whether a mid value (16 to 239) was among them.
 */
private fun Fr82Frame.transition(x0: Int, y0: Int, dx: Int, dy: Int, steps: Int): Pair<Int, Boolean> {
    var x = x0
    var y = y0
    var inBetween = 0
    var midSeen = false
    var started = false
    repeat(steps) {
        val g = grey(x, y)
        if (g < 240) started = true
        if (started) {
            if (g <= 15) return inBetween to midSeen
            inBetween++
            if (g in 16..239) midSeen = true
        }
        x += dx
        y += dy
    }
    return inBetween to midSeen
}

/** Criterion 5: the circle's and the rounded clip's edges, crossed at 45 degrees, are antialiased. */
private fun curvedEdges(frame: Fr82Frame): String? {
    // (c) circle: centre (44, 44) dp, radius 20 dp. (d) rounded square: corner centre (88, 36) dp, radius 12 dp.
    // Walk in towards each centre along the diagonal from outside the shape.
    val circle = frame.transition(frame.px(44f - 20f), frame.px(44f - 20f), 1, 1, frame.px(20f))
    val corner = frame.transition(frame.px(76f), frame.px(24f), 1, 1, frame.px(12f))
    for ((name, found) in listOf("circle" to circle, "rounded clip" to corner)) {
        val (width, mid) = found
        if (!mid) return "the $name's edge has no mid-value pixel: not antialiased"
        // Along the diagonal one step is sqrt(2) device pixels across the edge: 1 to 2 px is 1 or 2 steps.
        if (width > 2) return "the $name's edge is $width diagonal steps wide: blurred"
    }
    return null
}

/** Criterion 5: the "Hg" left stem's edge is at most 2 device pixels wide. */
private fun glyphEdges(frame: Fr82Frame): String? {
    // The text sits at (24, 76) dp, 16 sp; its middle row is about 76 + 9 dp.
    val (width, _) = frame.transition(frame.px(24f), frame.px(85f), 1, 0, frame.px(12f))
    return if (width <= 2) null else "the glyph's edge is $width device pixels wide"
}

/**
 * The diagnostic scene drawn off screen by Compose at [density] into [width] by [height] device
 * pixels (Skia's raster backend), BGRA premultiplied, or null.
 */
private fun offscreen(width: Int, height: Int, density: Float): ByteArray? {
    val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(
        density = androidx.compose.ui.unit.Density(density),
        size = androidx.compose.ui.unit.IntSize(width, height),
        coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined,
    )
    try {
        scene.setContent { Box(Modifier.fillMaxSize().background(Color.White)) { Fr82DiagnosticScene() } }
        val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
        val surface = org.jetbrains.skia.Surface.makeRaster(info)
        scene.render(surface.canvas.asComposeCanvas(), 0L)
        scene.render(surface.canvas.asComposeCanvas(), 16_000_000L)
        val bitmap = org.jetbrains.skia.Bitmap()
        return try {
            if (!bitmap.allocPixels(info) || !surface.readPixels(bitmap, 0, 0)) null
            else bitmap.readPixels(info, info.minRowBytes, 0, 0)
        } finally {
            bitmap.close()
            surface.close()
        }
    } finally {
        scene.close()
    }
}

/** [pixels] (BGRA, [width] by [height]) stretched to [toWidth] by [toHeight] with linear sampling. */
private fun stretched(pixels: ByteArray, width: Int, height: Int, toWidth: Int, toHeight: Int): ByteArray? {
    val from = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
    val to = org.jetbrains.skia.ImageInfo(toWidth, toHeight, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL)
    val image = org.jetbrains.skia.Image.makeRaster(from, pixels, from.minRowBytes)
    val surface = org.jetbrains.skia.Surface.makeRaster(to)
    surface.canvas.drawImageRect(
        image,
        org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()),
        org.jetbrains.skia.Rect.makeWH(toWidth.toFloat(), toHeight.toFloat()),
        org.jetbrains.skia.SamplingMode.LINEAR,
        null,
        true,
    )
    val bitmap = org.jetbrains.skia.Bitmap()
    return try {
        if (!bitmap.allocPixels(to) || !surface.readPixels(bitmap, 0, 0)) null
        else bitmap.readPixels(to, to.minRowBytes, 0, 0)
    } finally {
        bitmap.close()
        image.close()
        surface.close()
    }
}

/**
 * Criterion 4: at a fractional scale the screen is what Compose draws off screen at that
 * density, within criterion 2's tolerance; and the same scene drawn at density 1 and stretched
 * is not, which shows the comparison can tell a stretched picture.
 */
private fun fractionalMatchesOffscreen(frame: Fr82Frame): String? {
    val s = frame.scale
    if (abs(s - s.roundToInt()) <= 0.001f) return "${SKIP}scale $s is an integer"
    val w = frame.clientWidth
    val h = frame.clientHeight
    val direct = offscreen(w, h, s) ?: return "the scene could not be drawn off screen"
    val (mean, share) = difference(direct, frame.screen)
    if (!within(mean, share)) return "the screen differs from Compose drawn off screen at $s: mean $mean, ${share * 100} % over 8"
    val small = offscreen((w / s).roundToInt(), (h / s).roundToInt(), 1f) ?: return "the scene could not be drawn at density 1"
    val upscaled = stretched(small, (w / s).roundToInt(), (h / s).roundToInt(), w, h) ?: return "the stretched scene could not be made"
    val (stretchedMean, stretchedShare) = difference(upscaled, frame.screen)
    return if (!within(stretchedMean, stretchedShare)) null
    else "a stretched picture passes the tolerance too (mean $stretchedMean), so the comparison cannot tell"
}

/** The scale tool FR82_SCALE_TOOL names, run to set the display to [percent]. Answers whether it did. */
private fun setScale(percent: Int): Boolean {
    val tool = System.getenv("FR82_SCALE_TOOL") ?: return false
    return platform.posix.system("\"\"$tool\" $percent --fit\"") == 0
}

/**
 * Criterion 6, run on its own (COMPOSE_RUST_FR82_WINDOWS_CHECK=live, display at 200 %, the scale
 * tool in FR82_SCALE_TOOL): the display goes to 125 % while the window is open, and with no input
 * the window draws at the new scale within 2 s, once, at the same size in dp, and criteria 1, 2
 * and 4 hold there; back to 200 %, criteria 1, 2 and 3 hold. The density the scene is drawn at
 * stands for DensityChanged, which the renderer sends from that density (DensityChangedTest).
 */
private fun runLiveCheck(): Boolean {
    val name = "fr82_scale_change_redraws_at_the_new_scale"
    val answer = try {
        Fr82Window.open().use { open ->
            val before = open.frame()
            if (before.dpi != 192) return@use "the run did not start at 200 % (dpi ${before.dpi})"
            val dpWidth = before.clientWidth / before.scale
            open.densities.clear()
            open.densities += before.density
            if (!setScale(125)) return@use "the display did not go to 125 %"
            open.turnFor(2_000) { abs(open.window.drawnDensity - 1.25f) < 0.001f }
            if (abs(open.window.drawnDensity - 1.25f) >= 0.001f) return@use "no frame at 1.25 within 2 s (density ${open.window.drawnDensity})"
            if (open.densities.count { abs(it - 1.25f) < 0.001f } != 1) return@use "the density went ${open.densities}"
            val at125 = open.frame()
            val dpAfter = at125.clientWidth / at125.scale
            if (abs(dpAfter - dpWidth) > 1f) return@use "the window was $dpWidth dp wide and is $dpAfter dp"
            surfaceIsDevicePixels(at125)?.let { return@use "at 125 %: $it" }
            screenMatchesDrawnFrame(at125)?.let { return@use "at 125 %: $it" }
            fractionalMatchesOffscreen(at125)?.takeUnless { it.startsWith(SKIP) }?.let { return@use "at 125 %: $it" }
            if (!setScale(200)) return@use "the display did not go back to 200 %"
            open.turnFor(2_000) { abs(open.window.drawnDensity - 2f) < 0.001f }
            val at200 = open.frame()
            surfaceIsDevicePixels(at200)?.let { return@use "back at 200 %: $it" }
            screenMatchesDrawnFrame(at200)?.let { return@use "back at 200 %: $it" }
            oneDpLines(at200)?.takeUnless { it.startsWith(SKIP) }?.let { return@use "back at 200 %: $it" }
            null
        }
    } catch (error: Throwable) {
        "threw ${error.message}"
    }
    if (answer == null) System.err.println("compose-rust: $name: ok") else System.err.println("compose-rust: $name: FAILED $answer")
    return answer == null
}
