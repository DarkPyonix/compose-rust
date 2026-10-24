package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.runtime.RendererMeasure
import java.nio.ByteBuffer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer

/**
 * What the C entry points call. See `staticlib-windows/src/WindowsEntryPoints.kt` for the
 * symbols themselves and for why they live in a module of their own.
 *
 * The status codes are the desktop shim's codes (`desktop/c/renderer_entry.c`), so a Host
 * reads the same number for the same mistake on every platform.
 *
 * Windows has no main thread to insist on. A window may be created on any thread, and the
 * thread that creates it is the one its messages are delivered to, so this renderer opens
 * its window, reads its messages and draws its frames on the thread that called in. That is
 * also the thread the Host keeps its state on, which is what makes a lock unnecessary on
 * either side, and why `RUN_NOT_MAIN_THREAD` is a code this platform never returns.
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
     * Nothing may unwind into C: a Kotlin exception crossing the boundary is undefined
     * behaviour, so failures come back as a status code.
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
            java.lang.System.err.println("compose-rust: renderer run failed: ${error.message}")
            RUN_FAILED
        }

    /**
     * Thread-safe, because Host worker threads call it: requests coalesce into one
     * `render_frame` per frame.
     */
    fun requestFrame() = FrameRequests.request()

    /**
     * Measures for the Host, inside a call this side made into it. Anything unreadable, and
     * anything that throws, comes back as `CALL_UNREADABLE` rather than crossing the boundary.
     */
    fun measure(
        requests: CPointer<ByteVar>?,
        length: Int,
        count: Int,
        results: CPointer<ByteVar>?,
    ): Int {
        if (requests == null || results == null) return MeasureRecords.CALL_UNREADABLE
        if (count < 0 || length < 0 || count > Int.MAX_VALUE / MeasureRecords.RESULT_LENGTH) {
            return MeasureRecords.CALL_UNREADABLE
        }
        return try {
            RendererMeasure.measure(
                ByteBuffer.wrapPointer(requests, length),
                count,
                ByteBuffer.wrapPointer(results, count * MeasureRecords.RESULT_LENGTH),
            )
        } catch (error: Throwable) {
            MeasureRecords.CALL_UNREADABLE
        }
    }
}
