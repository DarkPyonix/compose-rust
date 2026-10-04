package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import kotlin.math.roundToLong
import org.jetbrains.skiko.currentSystemTheme

/**
 * Timestamps along the path from a key press to the pixels, on request, and the numbers the
 * parity check reads.
 *
 * Off unless `DXC_REPORT_LATENCY` is set, because it prints a line per step. Each line
 * carries milliseconds on the monotonic clock, so two lines can be subtracted, and the
 * label says which step was reached: the window heard the key, the field changed, the Host
 * was called, it asked for a frame, the frame was rendered, the batch applied, the pixels
 * drawn.
 *
 * Shared by every window that draws a scene, so the same run on any of them ends in the same
 * lines. [summary] prints them: one `parity` line per measurement, `parity <name> <path> <value>
 * <unit>` with the path left to whoever reads the log, because the window does not know which
 * of the two builds it is in and a log is read next to the command that made it.
 */
internal object LatencyTrace {
    val enabled: Boolean = System.getenv("DXC_REPORT_LATENCY") != null

    fun mark(label: String) {
        if (enabled) {
            val millis = System.nanoTime() / 1_000.0 / 1_000.0
            System.err.println("compose-rust: latency t=${fixed(millis, 3)} $label")
        }
    }

    /** What is being measured now, so a drag of the window and a quiet window are counted apart. */
    var phase: String = "idle"

    private class Frames {
        var count = 0
        var total = 0L
        var worst = 0L
    }

    private val byPhase = LinkedHashMap<String, Frames>()
    private var inputSentAt = 0L
    private var inputSamples = 0
    private var inputTotal = 0L
    private var inputWorst = 0L

    /** Counts one drawn frame and how long it took, for [summary]. */
    fun frameDrawn(nanos: Long) {
        if (!enabled) return
        val frames = byPhase.getOrPut(phase) { Frames() }
        frames.count++
        frames.total += nanos
        if (nanos > frames.worst) frames.worst = nanos
        if (inputSentAt != 0L) {
            // From the input being handed to the scene to the frame it changed being drawn.
            val spent = System.nanoTime() - inputSentAt
            inputSentAt = 0L
            inputSamples++
            inputTotal += spent
            if (spent > inputWorst) inputWorst = spent
        }
    }

    /** Notes that a typed letter was just handed to the scene. The next frame drawn answers it. */
    fun inputSent() {
        if (enabled && inputSentAt == 0L) inputSentAt = System.nanoTime()
    }

    /** The lines at the end of a run: what each frame cost, per phase, and what an input cost. */
    fun summary() {
        if (!enabled) return
        System.err.println("compose-rust: parity system_theme ${currentSystemTheme.name.lowercase()}")
        var frames = 0
        var total = 0L
        var worst = 0L
        for ((_, one) in byPhase) {
            frames += one.count
            total += one.total
            if (one.worst > worst) worst = one.worst
        }
        if (frames > 0) {
            System.err.println(
                "compose-rust: latency frames=$frames average=${fixed(total / frames / 1.0e6, 2)}ms " +
                    "worst=${fixed(worst / 1.0e6, 2)}ms",
            )
        }
        for ((name, one) in byPhase) {
            System.err.println(
                "compose-rust: parity frame_${name}_avg_ms ${fixed(one.total / one.count / 1.0e6, 2)} " +
                    "frames=${one.count} worst_ms=${fixed(one.worst / 1.0e6, 2)}",
            )
        }
        if (inputSamples > 0) {
            System.err.println(
                "compose-rust: parity input_latency_avg_ms ${fixed(inputTotal / inputSamples / 1.0e6, 2)} " +
                    "samples=$inputSamples worst_ms=${fixed(inputWorst / 1.0e6, 2)}",
            )
        }
    }
}

/** [value] with [digits] places after the point, which Kotlin/Native has no `format` for. */
internal fun fixed(value: Double, digits: Int): String {
    var factor = 1L
    repeat(digits) { factor *= 10 }
    val scaled = (value.coerceAtLeast(0.0) * factor).roundToLong()
    return "${scaled / factor}.${(scaled % factor).toString().padStart(digits, '0')}"
}
