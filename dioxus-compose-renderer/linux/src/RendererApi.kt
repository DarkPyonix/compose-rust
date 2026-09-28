package dioxus.compose.ui.platform

/**
 * What the C entry points call. See `staticlib-linux/src/LinuxEntryPoints.kt` for the symbols
 * themselves and for why they live in a module of their own.
 *
 * The status codes are the desktop shim's codes (`desktop/c/renderer_entry.c`), so a Host reads
 * the same number for the same mistake on every platform.
 *
 * Written again rather than shared with the iOS one, because the thread rule is the opposite
 * shape and the check that enforces it there is Foundation's:
 *
 * - On iOS, `UIApplicationMain` must be called on the process main thread, it installs the main
 *   run loop there, and it never returns. `RendererApi.run` refuses anything else.
 * - On macOS, the shim keeps the process main thread for `NSApplication` and the renderer runs on
 *   a secondary one.
 * - Here there is no such rule to enforce. X11 has no thread that owns the display server: a
 *   connection belongs to whoever opened it, and this renderer opens one, reads its events and
 *   draws its frames all on the thread that called in. That thread is also the one the Host keeps
 *   its state on, which is what makes a lock unnecessary on either side. So `RUN_NOT_MAIN_THREAD`
 *   is a code this platform never returns, rather than a check that was forgotten.
 */
object RendererApi {
    const val RUN_OK = 0
    const val RUN_FAILED = 1
    const val RUN_ALREADY_RUNNING = -2
    const val RUN_NOT_MAIN_THREAD = -4

    private var started = false

    /**
     * Blocks until the window closes (see `runRenderer`).
     *
     * Nothing may unwind into C: a Kotlin exception crossing the boundary is undefined behaviour,
     * so failures come back as a status code.
     */
    fun run(): Int =
        try {
            if (started) {
                RUN_ALREADY_RUNNING
            } else {
                started = true
                runRenderer { IosHostConnection() }
            }
        } catch (error: Throwable) {
            java.lang.System.err.println("dioxus-compose: renderer run failed: ${error.message}")
            RUN_FAILED
        }

    /**
     * Thread-safe, because Host worker threads call it: requests coalesce into one
     * `render_frame` per frame.
     */
    fun requestFrame() = FrameRequests.request()
}
