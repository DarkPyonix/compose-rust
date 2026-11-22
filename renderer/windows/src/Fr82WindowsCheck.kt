@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    if (System.getenv(FR82_WINDOWS_CHECK_ENV) == null) return null
    val frame = try {
        Fr82Frame.capture()
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

    companion object {
        fun capture(): Fr82Frame {
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
            try {
                window.setContent {
                    Box(Modifier.fillMaxSize().background(Color.White)) { Fr82DiagnosticScene() }
                }
                SetForegroundWindow(window.handle.reinterpret())
                turnFor(window, 1_000)
                var drawn: ByteArray? = null
                var width = 0
                var height = 0
                window.readBack = { pixels, w, h ->
                    drawn = pixels
                    width = w
                    height = h
                }
                window.draw()
                turnFor(window, 500)
                val pixels = checkNotNull(drawn) { "the frame could not be read back" }
                val client = clientSize(window.handle)
                val screen = captureClient(window.handle, client.first, client.second)
                return Fr82Frame(
                    dpi = (window.pixelsPerPoint() * 96f).roundToInt(),
                    awarenessIsPerMonitorV2 = windowIsPerMonitorV2(window.handle),
                    clientWidth = client.first,
                    clientHeight = client.second,
                    surfaceWidth = width,
                    surfaceHeight = height,
                    density = window.drawnDensity,
                    drawn = pixels,
                    screen = screen,
                )
            } finally {
                window.destroy()
                window.release()
            }
        }

        /** Serves the window for [millis]: the pump, then its turn, about once a frame. */
        private fun turnFor(window: Win32Window, millis: Long) {
            val until = TimeSource.Monotonic.markNow()
            while (until.elapsedNow().inWholeMilliseconds < millis) {
                Win32Loop.pump(0.016)
                window.turn()
            }
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
    var sum = 0L
    var over = 0
    val pixels = frame.drawn.size / 4
    for (pixel in 0 until pixels) {
        var worst = 0
        for (channel in 0 until 3) {
            val index = pixel * 4 + channel
            val difference = abs((frame.drawn[index].toInt() and 0xFF) - (frame.screen[index].toInt() and 0xFF))
            sum += difference
            if (difference > worst) worst = difference
        }
        if (worst > 8) over++
    }
    val mean = sum.toDouble() / (pixels * 3)
    val share = over.toDouble() / pixels
    return if (mean <= 1.0 && share <= 0.001) null else "mean channel difference $mean, ${share * 100} % of pixels over 8"
}

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
