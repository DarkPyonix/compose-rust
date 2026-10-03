@file:JvmName("RendererEntryPoints")

package dioxus.compose.ui.platform

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import dioxus.compose.ui.platform.NativeHostConnection
import dioxus.compose.ui.platform.runAppKitSpike
import dioxus.compose.ui.platform.runWin32Window
import dioxus.compose.ui.platform.runX11Window
import org.graalvm.nativeimage.c.function.CFunction

// C entry points of the renderer shared library.
//
// The public symbols `dioxus_compose_renderer_run` and `dioxus_compose_renderer_request_frame`
// take no isolate argument; the C shim in `c/renderer_entry.c` owns the isolate and calls
// these `_impl` functions with the current isolate thread.
//
// These are top-level functions because @CEntryPoint needs genuinely static methods: an
// `object` with @JvmStatic compiles to a static bridge that passes the word-typed isolate
// thread on to an instance method, which native-image rejects.
//
// Word-typed parameters are declared nullable for the same reason: Kotlin inserts a
// `checkNotNullParameter` call for every non-null reference parameter, and that call would
// pass the word value as an Object.

// Implemented in the C shim this library is entered through, which is the only code that
// can put a layer behind the window: the window belongs to it. Declared here rather than
// beside the renderer so that a development run on a JVM, which has no shim and no such
// window, never loads a GraalVM type.
@CFunction("dxc_set_window_material")
private external fun setWindowMaterial(asked: Int)

@CEntryPoint(name = "dioxus_compose_renderer_run_impl")
fun rendererRun(thread: IsolateThread?, libraryDir: CCharPointer?): Int =
    try {
        configureRuntimeLayout(CTypeConversion.toJavaString(libraryDir))
        // The window of our own, while it is being built. Off unless asked for: every
        // sample and every test still rides the toolkit's path until this one can carry
        // them.
        //
        // One per platform, and each asked for by name. The C files behind them answer
        // for the same symbols, so the one compiled into an image is the one any of them
        // would reach: asking for the Windows window on a Mac would open an AppKit window
        // and read its view as a Direct3D device. Which platform this is decides, rather
        // than which variable was set.
        val platform = System.getProperty("os.name", "")
        // The notification centre, before the Host starts: its first batch may already post
        // one. macOS and Windows reach theirs through the C this image was linked with;
        // Linux speaks to the notification daemon over the session bus. A development run on
        // a JVM never comes through here and keeps the default, which shows nothing and says
        // so.
        Notifications.platform = when {
            platform.startsWith("Mac") || platform.startsWith("Windows") ->
                NativeDesktopNotifications()
            platform.startsWith("Linux") -> DBusNotifications(
                open = { JvmBusConnection.open(wake = FrameRequests::request) },
                bringToFront = ::bringAwtWindowToFront,
                applicationName = JvmBusConnection.applicationName(),
            )
            else -> UnsupportedNotifications
        }
        if (System.getenv("DXC_APPKIT_WINDOW") != null && platform.startsWith("Mac")) {
            // Before anything else on this path. The toolkit, if it is ever woken, asks
            // the main thread to run the application, and this thread is the one drawing
            // the frames: that request is delivered on the first frame and never comes
            // back. Saying up front that there is no display to open keeps the toolkit
            // from asking, and nothing on this path wants one.
            System.setProperty("java.awt.headless", "true")
            runAppKitSpike()
            return@rendererRun 0
        }
        if (System.getenv("DXC_WIN32_WINDOW") != null && platform.startsWith("Windows")) {
            runWin32Window()
            return@rendererRun 0
        }
        if (System.getenv("DXC_X11_WINDOW") != null && platform.startsWith("Linux")) {
            runX11Window()
            return@rendererRun 0
        }
        dioxus.compose.ui.node.platformWindowMaterial = { asked ->
            setWindowMaterial(if (asked) 1 else 0)
        }
        // And the other half of that: whether asking actually put anything there. This
        // build's C entry does, on macOS, which is what the line above calls. The
        // Kotlin/Native renderer opens its own window and has no effect view to put under
        // it, so it leaves the default alone and its design system draws for an opaque
        // window rather than for a desktop that never shows through.
        dioxus.compose.runtime.platformBacksWindowWithMaterial = { platform.startsWith("Mac") }
        // Lets automated smoke tests close the window; unset in normal use.
        runRenderer(System.getenv("DIOXUS_COMPOSE_AUTOEXIT_MS")?.toLongOrNull()) {
            NativeHostConnection()
        }
        0
    } catch (t: Throwable) {
        // Nothing may unwind across the C boundary: a Kotlin exception crossing into C is
        // undefined behaviour, and a protocol error must never abort the process. Report it
        // as a non-zero status instead.
        t.printStackTrace()
        1
    }

@CEntryPoint(name = "dioxus_compose_renderer_request_frame_impl")
fun rendererRequestFrame(thread: IsolateThread?) {
    FrameRequests.request()
}

/**
 * Brings the application's window up for a press on a notification's body: back from
 * being minimised, and in front of the others.
 *
 * On the toolkit's thread, because that is the only thread a toolkit window may be touched
 * from, and a press is reported from wherever the bus was read.
 */
private fun bringAwtWindowToFront() {
    java.awt.EventQueue.invokeLater {
        val window = java.awt.Window.getWindows().firstOrNull { it.isVisible } ?: return@invokeLater
        if (window is java.awt.Frame) {
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        window.toFront()
        window.requestFocus()
    }
}
