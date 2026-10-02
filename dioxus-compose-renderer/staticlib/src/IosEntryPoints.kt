package dioxus.compose.ui.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

/**
 * C entry points of the renderer static library on iOS.
 *
 * The desktop renderer is a GraalVM native image, so its entry points are `@CEntryPoint` and
 * a C shim (`desktop/c/renderer_entry.c`) owns the isolate and hides the `IsolateThread*`
 * argument from the Host. Kotlin/Native has no isolate, so there is nothing to hide and
 * **there is no shim on iOS**: `@CName` puts the public symbol straight on the Kotlin
 * function, and the Host sees the same two argument-free symbols on both platforms.
 *
 * What the desktop shim does, and what replaces it here:
 *
 * | Desktop shim job | iOS |
 * |---|---|
 * | create the isolate, attach worker threads to it | not needed: the Kotlin/Native runtime initialises thread state on a foreign thread's first call into Kotlin |
 * | expose argument-free symbols | `@CName` does it directly |
 * | refuse to run off the main thread | `NSThread.isMainThread` in `RendererApi.run` |
 * | find the library directory for AWT and Skiko | not needed: Skia is linked into the image and there is no `java.home` |
 * | run Compose on a secondary thread, keeping the main thread for AppKit | inverted on iOS, see below |
 *
 * **The main thread rule has the opposite shape to macOS's.** On macOS the renderer runs on
 * a secondary thread and the shim keeps the process main thread for `NSApplication`, because
 * AWT would otherwise re-enter `[NSApp run]` and never hand control back.
 * On iOS the rule is stricter and simpler: `UIApplicationMain` must be called on the process
 * main thread, it installs the main run loop there, and it never returns. Compose for iOS
 * composes and draws on that thread, so the VirtualDom and every `dioxus_compose_host_*`
 * call run there too, which is also why neither side needs a lock. Called anywhere else,
 * `run` returns `RUN_NOT_MAIN_THREAD`
 * without starting anything, exactly as on macOS.
 *
 * **Memory model.** Kotlin/Native's current memory model has no freezing and no per-thread
 * object ownership, so `request_frame` may touch `FrameRequests` from a Host worker thread
 * without hopping to the main queue. The batch arena is Host memory, read through
 * `CPointer` inside the call that produced it, so no Kotlin object refers to it after
 * the call returns and the Kotlin garbage collector never sees it.
 */

@OptIn(ExperimentalNativeApi::class)
@CName("dioxus_compose_renderer_run")
fun rendererRun(): Int = RendererApi.run()

@OptIn(ExperimentalNativeApi::class)
@CName("dioxus_compose_renderer_request_frame")
fun rendererRequestFrame() = RendererApi.requestFrame()
