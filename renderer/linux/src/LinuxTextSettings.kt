@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import platform.posix.S_IRWXU
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.readlink
import x11.Atom
import x11.ClientMessage
import x11.DestroyNotify
import x11.Display
import x11.PropertyChangeMask
import x11.PropertyNotify
import x11.StructureNotifyMask
import x11.Window
import x11.XDefaultScreen
import x11.XErrorEvent
import x11.XEvent
import x11.XFree
import x11.XGetSelectionOwner
import x11.XGetWindowProperty
import x11.XInternAtom
import x11.XNextEvent
import x11.XOpenDisplay
import x11.XPending
import x11.XRootWindow
import x11.XSelectInput
import x11.XSetErrorHandler
import x11.XSync

/**
 * The reader's text size for this window, read from what the desktop publishes to X clients.
 *
 * The same reading the native image's window does, through Xlib directly rather than through
 * C of ours; what the bytes mean is [linuxTextScale]'s, shared with that window.
 */
internal fun nativeLinuxTextScale(): () -> Float {
    val watch = X11TextSettingsWatch()
    return linuxTextScale(
        LinuxTextSettings(
            serial = watch::serial,
            xsettings = watch::xsettings,
            resources = { watch.resources()?.decodeToString() },
            readFile = ::readTextFile,
            environment = { name -> getenv(name)?.toKString() },
        ),
    )
}

/**
 * Listens for the desktop's text settings changing, on an X connection of its own.
 *
 * GNOME's settings daemon keeps its settings in a property of the window that owns the
 * XSETTINGS selection, and KDE writes its font DPI into the root window's resource database.
 * Both are properties, so a change to either is an event: this selects for them and counts
 * them, and the window asks for the count once a turn.
 *
 * Its own connection rather than the window's, so the window's event loop is left exactly
 * as it is and nothing here depends on the order the window reads its own events in.
 */
private class X11TextSettingsWatch {

    private val display: CPointer<Display>? = XOpenDisplay(null)

    private val root: Window
    private val selection: Atom
    private val settingsProperty: Atom
    private val manager: Atom
    private val resourceManager: Atom
    private var owner: Window = NONE
    private var count = 0

    init {
        val display = display
        if (display == null) {
            root = NONE
            selection = NONE
            settingsProperty = NONE
            manager = NONE
            resourceManager = NONE
        } else {
            val screen = XDefaultScreen(display)
            root = XRootWindow(display, screen)
            selection = XInternAtom(display, "_XSETTINGS_S$screen", 0)
            settingsProperty = XInternAtom(display, "_XSETTINGS_SETTINGS", 0)
            manager = XInternAtom(display, "MANAGER", 0)
            // By name rather than as the predefined number: the predefined atom is a cast in
            // a macro, which is not something a Kotlin binding can carry.
            resourceManager = XInternAtom(display, "RESOURCE_MANAGER", 0)
            // The resource database changes on the root window, and a new settings manager is
            // announced there with a MANAGER message.
            XSelectInput(display, root, PropertyChangeMask or StructureNotifyMask)
            watchOwner(display)
            count = 1
        }
    }

    /** A number that changes whenever the desktop's text settings may have. */
    fun serial(): Int {
        val display = display ?: return 0
        if (XPending(display) == 0) return count
        memScoped {
            val event = alloc<XEvent>()
            while (XPending(display) > 0) {
                XNextEvent(display, event.ptr)
                when (event.type) {
                    PropertyNotify -> {
                        val window = event.xproperty.window
                        val atom = event.xproperty.atom
                        if ((window == root && atom == resourceManager) ||
                            (window == owner && atom == settingsProperty)
                        ) {
                            count++
                        }
                    }
                    ClientMessage -> {
                        if (event.xclient.message_type == manager &&
                            event.xclient.data.l[1].toULong() == selection
                        ) {
                            watchOwner(display)
                            count++
                        }
                    }
                    DestroyNotify -> {
                        if (event.xdestroywindow.window == owner) {
                            watchOwner(display)
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    /** The XSETTINGS manager's settings, raw, or null where no manager is running. */
    fun xsettings(): ByteArray? = property(owner, settingsProperty)

    /** The root window's resource database, or null where none was loaded. */
    fun resources(): ByteArray? = property(root, resourceManager)

    /**
     * Who holds the settings selection now, watched so that a change of a setting and the
     * manager going away are both heard.
     */
    private fun watchOwner(display: CPointer<Display>) {
        val found = trapped(display) {
            val owner = XGetSelectionOwner(display, selection)
            if (owner != NONE) {
                XSelectInput(display, owner, PropertyChangeMask or StructureNotifyMask)
            }
            owner
        }
        owner = found ?: NONE
    }

    private fun property(window: Window, property: Atom): ByteArray? {
        val display = display ?: return null
        if (window == NONE) return null
        return trapped(display) {
            memScoped {
                val type = alloc<ULongVar>()
                val format = alloc<IntVar>()
                val items = alloc<ULongVar>()
                val after = alloc<ULongVar>()
                val data = alloc<CPointerVar<UByteVar>>()
                val status = XGetWindowProperty(
                    display,
                    window,
                    property,
                    0,
                    Int.MAX_VALUE.toLong() / 4,
                    0,
                    ANY_PROPERTY_TYPE,
                    type.ptr,
                    format.ptr,
                    items.ptr,
                    after.ptr,
                    data.ptr,
                )
                val bytes = data.value
                try {
                    if (status != SUCCESS || type.value == NONE || bytes == null || format.value != 8) {
                        null
                    } else {
                        bytes.readBytes(items.value.toInt())
                    }
                } finally {
                    if (bytes != null) XFree(bytes)
                }
            }
        }
    }
}

/**
 * Runs requests about another process's window, and answers null where one of them failed.
 *
 * The settings manager can be destroyed between being found and being asked, and Xlib's own
 * answer to a request about a window that has gone is to end this process. So the requests
 * are made inside a handler that notes the error instead, synchronised on both sides so the
 * errors caught are these and only for as long as the requests take.
 */
private fun <T> trapped(display: CPointer<Display>, requests: () -> T): T? {
    XSync(display, 0)
    settingsRequestFailed = false
    val previous = XSetErrorHandler(staticCFunction(::noteSettingsError))
    val answer = try {
        requests()
    } finally {
        XSync(display, 0)
        XSetErrorHandler(previous)
    }
    return if (settingsRequestFailed) null else answer
}

private var settingsRequestFailed = false

@Suppress("UNUSED_PARAMETER")
private fun noteSettingsError(display: CPointer<Display>?, error: CPointer<XErrorEvent>?): Int {
    settingsRequestFailed = true
    return 0
}

/**
 * Where this window keeps the application's zoom level: a file in the application's own
 * configuration directory, named after its executable.
 */
internal fun nativeLinuxZoomLevelStore(): ZoomLevelStore {
    val path = zoomLevelPath(ConfigLayout.Linux, applicationIdentity(executablePath())) { name ->
        getenv(name)?.toKString()
    } ?: return ZoomLevelStore.None
    return ZoomLevelFile(path, read = ::readTextFile, write = ::writeTextFile)
}

/** The program this renderer is linked into, as the kernel names it. */
private fun executablePath(): String? = memScoped {
    val buffer = allocArray<ByteVar>(PATH_BYTES)
    val length = readlink("/proc/self/exe", buffer, (PATH_BYTES - 1).toULong()).toInt()
    if (length <= 0) return null
    buffer[length] = 0.toByte()
    buffer.toKString()
}

/**
 * Writes a whole text file, making each missing directory above it. Answers whether the
 * file was written.
 */
private fun writeTextFile(path: String, text: String): Boolean {
    var slash = path.indexOf('/', startIndex = 1)
    while (slash > 0) {
        // A directory that is already there answers with an error, which is the answer
        // wanted; one that cannot be made is found out when the file cannot be opened.
        mkdir(path.substring(0, slash), S_IRWXU.toUInt())
        slash = path.indexOf('/', startIndex = slash + 1)
    }
    val file = fopen(path, "w") ?: return false
    try {
        return fputs(text, file) >= 0
    } finally {
        fclose(file)
    }
}

/** A whole text file, or null where it cannot be read. */
private fun readTextFile(path: String): String? {
    val file = fopen(path, "r") ?: return null
    try {
        val bytes = ArrayList<Byte>()
        memScoped {
            val chunk = allocArray<ByteVar>(FILE_CHUNK)
            while (true) {
                val read = fread(chunk, 1uL, FILE_CHUNK.toULong(), file).toInt()
                if (read <= 0) break
                for (index in 0 until read) bytes.add(chunk[index])
            }
        }
        return bytes.toByteArray().decodeToString()
    } finally {
        fclose(file)
    }
}

private const val NONE: ULong = 0uL
private const val ANY_PROPERTY_TYPE: ULong = 0uL
private const val SUCCESS = 0
private const val FILE_CHUNK = 4096
private const val PATH_BYTES = 4096
