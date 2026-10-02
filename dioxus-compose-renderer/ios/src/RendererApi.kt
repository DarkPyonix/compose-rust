package dioxus.compose.ui.platform

import dioxus.compose.ui.platform.IosHostConnection
import platform.Foundation.NSThread

/**
 * What the C entry points call. See `staticlib/src/IosEntryPoints.kt` for the
 * symbols themselves and for why they live in a module of their own.
 *
 * The status codes are the desktop shim's codes (`desktop/c/renderer_entry.c`), so a Host
 * reads the same number for the same mistake on both platforms.
 */
object RendererApi {
    const val RUN_OK = 0
    const val RUN_FAILED = 1
    const val RUN_ALREADY_RUNNING = -2
    const val RUN_NOT_MAIN_THREAD = -4

    private var started = false

    /**
     * Blocks for the lifetime of the process (see `runRenderer`).
     *
     * Must be called on the process main thread: `UIApplicationMain` installs the main run
     * loop there and Compose for iOS composes on it. Nothing may unwind into C: a Kotlin
     * exception crossing the boundary is undefined behaviour, so failures come back as a
     * status code.
     */
    fun run(): Int =
        try {
            when {
                !NSThread.isMainThread -> RUN_NOT_MAIN_THREAD
                started -> RUN_ALREADY_RUNNING
                else -> {
                    started = true
                    runRenderer { IosHostConnection() }
                }
            }
        } catch (error: Throwable) {
            println("dioxus-compose: renderer run failed: ${error.message}")
            RUN_FAILED
        }

    /** Thread-safe, because Host worker threads call it: requests coalesce into one
     *  `render_frame` per frame. */
    fun requestFrame() = FrameRequests.request()
}
