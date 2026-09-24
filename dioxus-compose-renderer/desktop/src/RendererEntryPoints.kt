@file:JvmName("RendererEntryPoints")

package dioxus.compose.ui.platform

import org.graalvm.nativeimage.IsolateThread
import org.graalvm.nativeimage.c.function.CEntryPoint
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CTypeConversion
import dioxus.compose.ui.platform.NativeHostConnection
import dioxus.compose.ui.platform.runAppKitSpike
import dioxus.compose.ui.platform.runWin32Spike
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
        // One per platform, and each asked for by name. The two C files behind them
        // export the same five symbols, so the one compiled into an image is the one
        // either would reach: asking for the Windows window on a Mac would open an AppKit
        // window and read its view as a Direct3D device. Which platform this is decides,
        // rather than which variable was set.
        // Measuring what the toolkit costs. This build can only open the window it opens
        // for itself, so nothing of the toolkit is reachable from here and none of it is
        // compiled into the image.
        runAppKitSpike()
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
