@file:JvmName("RendererEntryPoints")

package dev.darkpyonix.composerust.ui.platform

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CTypeConversion
import dev.darkpyonix.composerust.ui.platform.NativeHostConnection
import dev.darkpyonix.composerust.ui.platform.bringNativeWindowToFront
import dev.darkpyonix.composerust.ui.platform.runAppKitWindow
import dev.darkpyonix.composerust.ui.platform.runWin32Window
import dev.darkpyonix.composerust.ui.platform.runX11Window

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
        // Which window this platform opens. Each desktop opens one of its own, made from
        // the platform's windowing and graphics APIs with no toolkit between: AppKit and
        // Metal, Win32 and Direct3D 12, X11 and GLX. The C files behind each answer for the
        // same symbols, so the one compiled into an image is the one that can be reached:
        // which platform this is decides, and nothing is read from the environment.
        val platform = System.getProperty("os.name", "")
        // Linux still opens the toolkit's window unless asked for the X11 one: that window
        // is finished but its typing, clipboard and input method have not been checked on a
        // real desktop, so it is not the default yet.
        val linuxOwnWindow = platform.startsWith("Linux") && System.getenv("DXC_X11_WINDOW") != null
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
                bringToFront = if (linuxOwnWindow) ::bringNativeWindowToFront else ::bringAwtWindowToFront,
                applicationName = JvmBusConnection.applicationName(),
            )
            else -> UnsupportedNotifications
        }
        // A window of our own means there is no display for the toolkit to open. Saying so up
        // front keeps it from being woken: on macOS it would ask the main thread to run the
        // application, and this thread is the one drawing the frames, so that request would
        // be delivered on the first frame and never come back.
        if (!platform.startsWith("Linux") || linuxOwnWindow) {
            System.setProperty("java.awt.headless", "true")
        }
        when {
            platform.startsWith("Mac") -> {
                runAppKitWindow(autoExitMillis)
                return@rendererRun 0
            }
            platform.startsWith("Windows") -> {
                runWin32Window(autoExitMillis)
                return@rendererRun 0
            }
            linuxOwnWindow -> {
                runX11Window(autoExitMillis)
                return@rendererRun 0
            }
        }
        dev.darkpyonix.composerust.ui.node.platformWindowMaterial = { asked ->
            setWindowMaterial(if (asked) 1 else 0)
        }
        dev.darkpyonix.composerust.runtime.platformBacksWindowWithMaterial = { false }
        // The toolkit's window, which Linux still opens by default.
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
    FrameRequests.request()
}
