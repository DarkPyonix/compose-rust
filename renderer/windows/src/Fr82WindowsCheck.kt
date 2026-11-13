@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.lang.System
import kotlin.math.roundToInt
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.PixelGeometry
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BitBlt
import platform.windows.ClientToScreen
import platform.windows.CreateCompatibleBitmap
import platform.windows.CreateCompatibleDC
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.GetClientRect
import platform.windows.GetDC
import platform.windows.GetDIBits
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryW
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.ReleaseDC
import platform.windows.SelectObject

// The HiDPI check on Windows (see HiDpiCheck.kt for what it checks and in what order), run by
// the renderer itself in a real window drawn through Direct3D 12, as Fr53WindowsCheck.kt runs
// its checks, and for the same reason: the window is C that only the renderer's MSVC link has.
// `.github/scripts/hidpi-windows-check.sh` starts it at each display scale with
// `COMPOSE_RUST_FR82_CHECK=1 hidpi.exe --check`, changes the scale while it runs, and reads the
// lines it prints.
//
// What is read from the system is read here with the calls a person would use to check it by
// hand: GetDpiForWindow for the scale, GetClientRect for the client area, and the composed
// desktop, after DwmFlush, for what is on the screen.

/**
 * Runs the check when [FR82_CHECK_ENV] is set, in place of the application. Null when not
 * asked, true when every check passed and false otherwise.
 */
internal fun runFr82WindowsChecksIfAsked(): Boolean? {
    if (System.getenv(FR82_CHECK_ENV) == null) return null
    // Clear of the screen's corner and of the taskbar at every scale the check runs at.
    configurePosition(1, WINDOW_AT, WINDOW_AT)
    val window = Win32Window.open(HIDPI_TITLE, HiDpiScene.WIDTH_DP, HiDpiScene.HEIGHT_DP, systemChrome = true)
    if (window == null) {
        System.err.println(
            "compose-rust: fr82 checks: FAILED no window opened; a machine without a graphics card needs DXC_D3D12_WARP=1",
        )
        return false
    }
    try {
        window.keepFrames = true
        window.setContent { HiDpiMarkerScene() }
        window.begin()
        return runHiDpiChecks(Win32HiDpiWindow(window), fr82ScaleChanges())
    } finally {
        window.close()
    }
}

private class Win32HiDpiWindow(private val window: Win32Window) : HiDpiWindow {
    private var busy = true

    override fun step() {
        busy = window.step(busy)
    }

    override fun systemScale(): Float = dpiForWindow(window.handle) / BASE_DPI

    override fun clientSize(): IntSize = clientRect(window.handle).second

    override val frames: Int get() = window.framesDrawn

    override val surfaceSize: IntSize get() = window.drawnSize

    override val lastFrame: HiDpiImage? get() = window.lastFrame

    override val density: Density get() = window.sceneDensity

    override val densityChanges: Int get() = window.densityChanges

    /** The swapchain is made with horizontal RGB subpixels, as Win32Window.drawFrame says. */
    override val geometry: PixelGeometry get() = PixelGeometry.RGB_H

    override fun capture(): HiDpiImage? = captureClient(window.handle)

    override fun platformChecks(scale: Float): List<HiDpiVerdict> =
        listOf(fr82_windows_process_is_per_monitor_v2_before_the_first_window(window.handle, scale))
}

/**
 * The window was made by a process that is per-monitor v2 DPI aware, with no manifest needed
 * for it, and its DPI is the monitor's: 192 at 200%.
 */
@Suppress("FunctionName")
private fun fr82_windows_process_is_per_monitor_v2_before_the_first_window(handle: COpaquePointer, scale: Float): HiDpiVerdict {
    val name = "fr82_windows_process_is_per_monitor_v2_before_the_first_window"
    val contextOf = windowDpiAwarenessContext
    val awarenessOf = awarenessFromContext
    val equal = contextsEqual
    if (contextOf == null || awarenessOf == null || equal == null) {
        return HiDpiVerdict(name, false, "user32 has no per-monitor DPI awareness calls")
    }
    val context = contextOf(handle)
    val awareness = awarenessOf(context)
    val v2 = equal(context, PER_MONITOR_AWARE_V2) != 0
    val dpi = dpiForWindow(handle)
    val wanted = (BASE_DPI * scale).roundToInt()
    return HiDpiVerdict(
        name,
        awareness == DPI_AWARENESS_PER_MONITOR_AWARE && v2 && dpi == wanted,
        "awareness $awareness (per-monitor is $DPI_AWARENESS_PER_MONITOR_AWARE), " +
            "per-monitor v2: ${if (v2) "yes" else "no"}, GetDpiForWindow $dpi (at ${two(scale)} it is $wanted)",
    )
}

/** The window's DPI as the system says it now, 96 being a scale of 1. */
private fun dpiForWindow(handle: COpaquePointer): Int = getDpiForWindow?.invoke(handle) ?: 0

/** Where the client area's corner is on the screen, and its size, in pixels. */
private fun clientRect(handle: COpaquePointer): Pair<Pair<Int, Int>, IntSize> = memScoped {
    val rect = alloc<RECT>()
    GetClientRect(handle.reinterpret(), rect.ptr)
    val origin = alloc<POINT>()
    origin.x = 0
    origin.y = 0
    ClientToScreen(handle.reinterpret(), origin.ptr)
    (origin.x to origin.y) to IntSize(rect.right - rect.left, rect.bottom - rect.top)
}

/**
 * The window's client area as the screen shows it: the desktop DWM composed, copied once DWM
 * has composed everything presented so far. Null where it could not be read.
 */
private fun captureClient(handle: COpaquePointer): HiDpiImage? {
    // Twice: the first returns once the composition that was under way is done, the second
    // once the one that took the last present has been shown.
    dwmFlush?.invoke()
    dwmFlush?.invoke()
    val (origin, size) = clientRect(handle)
    val width = size.width
    val height = size.height
    if (width <= 0 || height <= 0) return null
    val screen = GetDC(null) ?: return null
    val dc = CreateCompatibleDC(screen)
    val bitmap = CreateCompatibleBitmap(screen, width, height)
    try {
        if (dc == null || bitmap == null) return null
        val previous = SelectObject(dc, bitmap)
        val copied = BitBlt(dc, 0, 0, width, height, screen, origin.first, origin.second, SRCCOPY or CAPTUREBLT) != 0
        // A bitmap is read out once nothing has it selected.
        SelectObject(dc, previous)
        if (!copied) return null
        val bytes = ByteArray(width * height * 4)
        val rows = memScoped {
            val info = alloc<BITMAPINFO>()
            info.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            info.bmiHeader.biWidth = width
            // Negative: rows from the top, as every other picture here has them.
            info.bmiHeader.biHeight = -height
            info.bmiHeader.biPlanes = 1u
            info.bmiHeader.biBitCount = 32u
            info.bmiHeader.biCompression = BI_RGB
            info.bmiHeader.biSizeImage = 0u
            info.bmiHeader.biXPelsPerMeter = 0
            info.bmiHeader.biYPelsPerMeter = 0
            info.bmiHeader.biClrUsed = 0u
            info.bmiHeader.biClrImportant = 0u
            bytes.usePinned { pinned ->
                GetDIBits(dc, bitmap, 0u, height.toUInt(), pinned.addressOf(0), info.ptr, DIB_RGB_COLORS)
            }
        }
        if (rows != height) return null
        return HiDpiImage.fromBgra(bytes, width, height)
    } finally {
        if (bitmap != null) DeleteObject(bitmap)
        if (dc != null) DeleteDC(dc)
        ReleaseDC(null, screen)
    }
}

// Looked up at run time, as win32_window.c looks DwmFlush up: the renderer then links nothing
// new, and a system without one of them answers null here rather than failing to load.

private fun user32(name: String): COpaquePointer? {
    val module = GetModuleHandleW("user32.dll") ?: LoadLibraryW("user32.dll") ?: return null
    return GetProcAddress(module, name)
}

private val getDpiForWindow: CPointer<CFunction<(COpaquePointer?) -> Int>>? by lazy {
    user32("GetDpiForWindow")?.reinterpret()
}

private val windowDpiAwarenessContext: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? by lazy {
    user32("GetWindowDpiAwarenessContext")?.reinterpret()
}

private val awarenessFromContext: CPointer<CFunction<(COpaquePointer?) -> Int>>? by lazy {
    user32("GetAwarenessFromDpiAwarenessContext")?.reinterpret()
}

private val contextsEqual: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>? by lazy {
    user32("AreDpiAwarenessContextsEqual")?.reinterpret()
}

private val dwmFlush: CPointer<CFunction<() -> Int>>? by lazy {
    val module = LoadLibraryW("dwmapi.dll") ?: return@lazy null
    GetProcAddress(module, "DwmFlush")?.reinterpret()
}

/** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, which the headers define as the handle -4. */
private val PER_MONITOR_AWARE_V2: COpaquePointer? = (-4L).toCPointer<CPointed>()

/** DPI_AWARENESS_PER_MONITOR_AWARE. */
private const val DPI_AWARENESS_PER_MONITOR_AWARE = 2

private const val BASE_DPI = 96f

/** Where the window opens, in points from the screen's corner. */
private const val WINDOW_AT = 40

private const val SRCCOPY: UInt = 0x00CC0020u
private const val CAPTUREBLT: UInt = 0x40000000u
private const val BI_RGB: UInt = 0u
private const val DIB_RGB_COLORS: UInt = 0u
