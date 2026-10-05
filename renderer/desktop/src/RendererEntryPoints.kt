@file:JvmName("RendererEntryPoints")

package dev.darkpyonix.composerust.ui.platform

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import dev.darkpyonix.composerust.ui.platform.NativeHostConnection
import dev.darkpyonix.composerust.ui.platform.bringNativeWindowToFront
import dev.darkpyonix.composerust.ui.platform.runAppKitWindow
import dev.darkpyonix.composerust.ui.platform.runWin32Window
import dev.darkpyonix.composerust.ui.platform.runX11Window
import org.graalvm.nativeimage.c.function.CFunction

// C entry points of the renderer shared library.
//
// The public symbols `compose_rust_renderer_run` and `compose_rust_renderer_request_frame`
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

@CEntryPoint(name = "compose_rust_renderer_run_impl")
fun rendererRun(thread: IsolateThread?, libraryDir: CCharPointer?): Int =
    try {
        configureRuntimeLayout(CTypeConversion.toJavaString(libraryDir))
        // Which window this platform opens. macOS and Linux open one of their own, made from
        // AppKit and Metal, or X11 and GLX, with no toolkit between; Windows still opens the
        // toolkit's. The C files behind each answer for the same symbols, so the one
        // compiled into an image is the one that can be reached: which platform this is
        // decides, and nothing is read from the environment.
        val platform = System.getProperty("os.name", "")
        val autoExitMillis =
            (System.getenv("COMPOSE_RUST_AUTOEXIT_MS") ?: System.getenv("DIOXUS_COMPOSE_AUTOEXIT_MS"))
                ?.toLongOrNull()
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
                bringToFront = ::bringNativeWindowToFront,
                applicationName = JvmBusConnection.applicationName(),
            )
            else -> UnsupportedNotifications
        }
        if (platform.startsWith("Mac")) {
            // Before anything else on this path. The toolkit, if it is ever woken, asks
            // the main thread to run the application, and this thread is the one drawing
            // the frames: that request is delivered on the first frame and never comes
            // back. Saying up front that there is no display to open keeps the toolkit
            // from asking, and nothing on this path wants one.
            System.setProperty("java.awt.headless", "true")
            // Compose's main dispatcher is kotlinx.coroutines' Dispatchers.Main, not Swing's queue.
            System.setProperty("compose.main.dispatcher", "coroutines")
            runAppKitWindow(autoExitMillis)
            return@rendererRun 0
        }
        if (System.getenv("DXC_WIN32_WINDOW") != null && platform.startsWith("Windows")) {
            runWin32Window()
            return@rendererRun 0
        }
        if (platform.startsWith("Linux")) {
            // As on macOS, there is no display for the toolkit to open, and saying so keeps
            // it from being woken.
            System.setProperty("java.awt.headless", "true")
            runX11Window(autoExitMillis)
            return@rendererRun 0
        }
        dev.darkpyonix.composerust.ui.node.platformWindowMaterial = { asked ->
            setWindowMaterial(if (asked) 1 else 0)
        }
        // Whether asking for a material actually put anything behind the window. The
        // toolkit's window cannot, on any platform that still opens one, so its design
        // system draws for an opaque window rather than for a desktop that never shows
        // through.
        dev.darkpyonix.composerust.runtime.platformBacksWindowWithMaterial = { false }
        // Lets automated smoke tests close the window; unset in normal use.
        runRenderer(autoExitMillis) {
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

@CEntryPoint(name = "compose_rust_renderer_request_frame_impl")
fun rendererRequestFrame(thread: IsolateThread?) {
    dev.darkpyonix.composerust.ui.platform.LatencyTrace.mark("request_frame")
    FrameRequests.request()
}
