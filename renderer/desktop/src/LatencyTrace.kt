package dev.darkpyonix.composerust.ui.platform

/**
 * Timestamps along the path from a key press to the pixels, on request.
 *
 * Off unless `DXC_REPORT_LATENCY` is set, because it prints a line per step. Each line
 * carries milliseconds on the monotonic clock, so two lines can be subtracted, and the
 * label says which step was reached: the window heard the key, the field changed, the Host
 * was called, it asked for a frame, the frame was rendered, the batch applied, the pixels
 * drawn.
 */
internal object LatencyTrace {
    val enabled: Boolean = System.getenv("DXC_REPORT_LATENCY") != null

    fun mark(label: String) {
        if (enabled) {
            val millis = System.nanoTime() / 1_000.0 / 1_000.0
            System.err.println("compose-rust: latency t=${"%.3f".format(millis)} $label")
        }
    }

    private var frames = 0
    private var totalNanos = 0L
    private var worstNanos = 0L

    /** Counts one drawn frame and how long it took, for [summary]. */
    fun frameDrawn(nanos: Long) {
        if (!enabled) return
        frames++
        totalNanos += nanos
        if (nanos > worstNanos) worstNanos = nanos
    }

    /** One line at the end of a run: how many frames were drawn and what each cost. */
    fun summary() {
        if (enabled && frames > 0) {
            System.err.println(
                "compose-rust: latency frames=$frames average=${"%.2f".format(totalNanos / frames / 1.0e6)}ms " +
                    "worst=${"%.2f".format(worstNanos / 1.0e6)}ms",
            )
        }
    }
}
