package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.lang.System
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import org.jetbrains.skia.PixelGeometry
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.X11Scale
import x11.Atom
import x11.Display
import x11.Window
import x11.XCloseDisplay
import x11.XDefaultScreen
import x11.XErrorEvent
import x11.XFetchName
import x11.XFree
import x11.XGetImage
import x11.XGetWindowAttributes
import x11.XGetWindowProperty
import x11.XInternAtom
import x11.XOpenDisplay
import x11.XQueryTree
import x11.XRootWindow
import x11.XSetErrorHandler
import x11.XSync
import x11.XWindowAttributes

// The HiDPI check on Linux (see HiDpiCheck.kt for what it checks and in what order), run by the
// renderer itself in its real X11 window, drawn through GLX. `.github/scripts/hidpi-linux-check.sh`
// starts it under Xvfb at each `Xft.dpi` with `COMPOSE_RUST_FR82_CHECK=1 hidpi --check`, changes
// `Xft.dpi` with xrdb while it runs, and reads the lines it prints.
//
// What is read from the system is read here on an X connection of the check's own, with the
// calls a person would use to check it by hand: `Xft.dpi` from the root window's resources
// for the scale, the window's attributes for its size, and XGetImage for what is on the screen.

/**
 * Runs the check when [FR82_CHECK_ENV] is set, in place of the application. Null when not
 * asked, true when every check passed and false otherwise. [work] is the dispatcher installed
 * as `Dispatchers.Main`, which the window's scene runs its work on.
 */
internal fun runFr82LinuxChecksIfAsked(work: FrameDispatcher): Boolean? {
    if (System.getenv(FR82_CHECK_ENV) == null) return null
    val window = LinuxWindow.open(
        WindowConfig(title = HIDPI_TITLE, width = HiDpiScene.WIDTH_DP, height = HiDpiScene.HEIGHT_DP, x = 0, y = 0),
        work,
    )
    if (window == null) {
        System.err.println("compose-rust: fr82 checks: FAILED no X11 window opened; check DISPLAY")
        return false
    }
    try {
        window.keepFrames = true
        window.setContent { HiDpiMarkerScene() }
        // The window exists on the server once it has been pumped; until then it has no name
        // to be found by.
        repeat(FIND_TURNS) { window.stepOnce() }
        val probe = X11Probe.find(HIDPI_TITLE)
        if (probe == null) {
            System.err.println("compose-rust: fr82 checks: FAILED the window named \"$HIDPI_TITLE\" is not on the X server")
            return false
        }
        try {
            return runHiDpiChecks(LinuxHiDpiWindow(window, probe), fr82ScaleChanges())
        } finally {
            probe.close()
        }
    } finally {
        window.close()
    }
}

private class LinuxHiDpiWindow(private val window: LinuxWindow, private val probe: X11Probe) : HiDpiWindow {
    override fun step() = window.stepOnce()

    override fun systemScale(): Float = probe.scale()

    override fun clientSize(): IntSize = probe.clientSize()

    override val frames: Int get() = window.framesDrawn

    override val surfaceSize: IntSize get() = window.drawnSize

    override val lastFrame: HiDpiImage? get() = window.lastFrame

    override val density: Density get() = window.sceneDensity

    override val densityChanges: Int get() = window.densityChanges

    /** The window's GL surface is made with horizontal RGB subpixels (the fork's GlSurface). */
    override val geometry: PixelGeometry get() = PixelGeometry.RGB_H

    override fun capture(): HiDpiImage? = probe.capture()
}

/** The check's own view of the window, on a connection of its own. */
private class X11Probe private constructor(
    private val display: CPointer<Display>,
    private val root: Window,
    private val window: Window,
    private val resourceManager: Atom,
) {
    /** `Xft.dpi` over 96, as the root window's resources say now; 1 where they name none. */
    fun scale(): Float {
        val dpi = X11Scale.dpiOf(resources()) ?: return 1f
        return if (dpi < X11Scale.BASE_DPI) 1f else (dpi / X11Scale.BASE_DPI).toFloat()
    }

    fun clientSize(): IntSize = trapped(display) {
        memScoped {
            val attributes = alloc<XWindowAttributes>()
            XGetWindowAttributes(display, window, attributes.ptr)
            IntSize(attributes.width, attributes.height)
        }
    } ?: IntSize.Zero

    /**
     * The window's pixels as the server has them. With no compositor that is what the screen
     * shows: a GLX window's buffer is put on the screen when it is presented, and this reads
     * the screen's copy back.
     */
    fun capture(): HiDpiImage? {
        val size = clientSize()
        if (size.width <= 0 || size.height <= 0) return null
        return trapped(display) {
            val image = XGetImage(display, window, 0, 0, size.width.toUInt(), size.height.toUInt(), ALL_PLANES, Z_PIXMAP)
                ?: return@trapped null
            try {
                val described = image.pointed
                val data = described.data
                // 32 bits a pixel with red in the third byte: blue, green, red, unused, which is
                // what a 24-bit TrueColor screen on a little-endian machine gives.
                if (data == null || described.bits_per_pixel != 32 || described.red_mask != 0xFF0000uL || described.byte_order != LSB_FIRST) {
                    System.err.println(
                        "compose-rust: fr82 the X image is ${described.bits_per_pixel} bits a pixel, red mask " +
                            "${described.red_mask.toString(16)}, byte order ${described.byte_order}, which this does not read",
                    )
                    null
                } else {
                    val bytes = data.readBytes(described.bytes_per_line * described.height)
                    HiDpiImage.fromBgra(bytes, described.width, described.height, described.bytes_per_line)
                }
            } finally {
                image.pointed.data?.let { XFree(it) }
                XFree(image)
            }
        }
    }

    fun close() {
        XCloseDisplay(display)
    }

    private fun resources(): String? = trapped(display) {
        memScoped {
            val type = alloc<ULongVar>()
            val format = alloc<IntVar>()
            val items = alloc<ULongVar>()
            val after = alloc<ULongVar>()
            val data = alloc<CPointerVar<UByteVar>>()
            val status = XGetWindowProperty(
                display, root, resourceManager, 0, Int.MAX_VALUE.toLong() / 4, 0, ANY_PROPERTY_TYPE,
                type.ptr, format.ptr, items.ptr, after.ptr, data.ptr,
            )
            val bytes = data.value
            try {
                if (status != SUCCESS || bytes == null || format.value != 8) null else bytes.readBytes(items.value.toInt()).decodeToString()
            } finally {
                if (bytes != null) XFree(bytes)
            }
        }
    }

    companion object {
        /** Opens a connection and finds the window called [title] under the root, or null. */
        fun find(title: String): X11Probe? {
            val display = XOpenDisplay(null) ?: return null
            val root = XRootWindow(display, XDefaultScreen(display))
            val window = trapped(display) { named(display, root, title) }
            if (window == null || window == NONE) {
                XCloseDisplay(display)
                return null
            }
            // By name rather than as the predefined number: the predefined atom is a cast in a
            // macro, which a Kotlin binding cannot carry.
            val resourceManager = XInternAtom(display, "RESOURCE_MANAGER", 0)
            return X11Probe(display, root, window, resourceManager)
        }

        /** The first window under [parent], depth first, whose name is [title]; [NONE] where there is none. */
        private fun named(display: CPointer<Display>, parent: Window, title: String): Window = memScoped {
            val name = alloc<CPointerVar<ByteVar>>()
            if (XFetchName(display, parent, name.ptr) != 0) {
                val text = name.value
                val found = text?.toKString() == title
                if (text != null) XFree(text)
                if (found) return parent
            }
            val rootOut = alloc<ULongVar>()
            val parentOut = alloc<ULongVar>()
            val children = alloc<CPointerVar<ULongVar>>()
            val count = alloc<UIntVar>()
            if (XQueryTree(display, parent, rootOut.ptr, parentOut.ptr, children.ptr, count.ptr) == 0) return NONE
            val list = children.value ?: return NONE
            try {
                for (index in 0 until count.value.toInt()) {
                    val found = named(display, list[index], title)
                    if (found != NONE) return found
                }
            } finally {
                XFree(list)
            }
            NONE
        }
    }
}

/**
 * Runs requests about a window, and answers null where one of them failed. Xlib's own answer
 * to a failed request is to end the process, so they run inside a handler that notes the error
 * instead, synchronised on both sides so the errors caught are these.
 */
private fun <T> trapped(display: CPointer<Display>, requests: () -> T): T? {
    XSync(display, 0)
    probeRequestFailed = false
    val previous = XSetErrorHandler(staticCFunction(::noteProbeError))
    val answer = try {
        requests()
    } finally {
        XSync(display, 0)
        XSetErrorHandler(previous)
    }
    return if (probeRequestFailed) null else answer
}

private var probeRequestFailed = false

@Suppress("UNUSED_PARAMETER")
private fun noteProbeError(display: CPointer<Display>?, error: CPointer<XErrorEvent>?): Int {
    probeRequestFailed = true
    return 0
}

/** Turns of the window's loop before it is looked for on the server. */
private const val FIND_TURNS = 10

private const val NONE: ULong = 0uL
private const val ANY_PROPERTY_TYPE: ULong = 0uL
private const val SUCCESS = 0

/** AllPlanes, which Xlib defines as a cast in a macro. */
private const val ALL_PLANES: ULong = ULong.MAX_VALUE

/** ZPixmap. */
private const val Z_PIXMAP = 2

/** LSBFirst. */
private const val LSB_FIRST = 0
