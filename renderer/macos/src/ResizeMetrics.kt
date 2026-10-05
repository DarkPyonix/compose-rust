@file:OptIn(
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.ExperimentalStdlibApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import kotlin.native.runtime.GC
import kotlinx.cinterop.toKString

/**
 * A measurement of what the collector does to a live resize, asked for with DXC_METRICS=1.
 *
 * The Kotlin/Native twin of the native image's GcExperiment. Three seconds in it reports
 * the heap, dirties it the way a session of use does, then takes the window through 100
 * sizes in one scripted drag, printing for every frame drawn during it how long it took and
 * how many collections ran, read from the runtime's own record of its last collection.
 */
internal object ResizeMetrics {
    val enabled: Boolean = platform.posix.getenv("DXC_METRICS")?.toKString() == "1"

    private val started = kotlin.time.TimeSource.Monotonic.markNow()
    private var done = false
    private var active = false
    private var frameStarted = kotlin.time.TimeSource.Monotonic.markNow()
    private var epochBefore = -1L
    private var collections = 0L
    private var pauseNanos = 0L
    private var lastEpoch = -1L

    /** True once, three seconds in. */
    fun due(): Boolean = enabled && !done && started.elapsedNow().inWholeMilliseconds >= 3_000

    fun run(phase: (String) -> Unit, resize: (from: Pair<Int, Int>, to: Pair<Int, Int>, steps: Int) -> Unit) {
        done = true
        observe()
        phase("start")
        dirty()
        observe()
        phase("dirtied")
        active = true
        resize(800 to 600, 1200 to 900, 50)
        observe()
        phase("mid-drag")
        resize(1200 to 900, 800 to 600, 50)
        active = false
        observe()
        phase("after-100-resizes")
    }

    fun frameBegin() {
        if (!active) return
        observe()
        frameStarted = kotlin.time.TimeSource.Monotonic.markNow()
        epochBefore = lastEpoch
    }

    fun frameEnd(width: Int, height: Int) {
        if (!active) return
        val millis = frameStarted.elapsedNow().inWholeMicroseconds / 1000.0
        val pause = observe()
        val ran = if (epochBefore < 0) 0 else lastEpoch - epochBefore
        System.err.println(
            "compose-rust: metrics frame ${width}x$height ms=${format(millis)} gcs=+$ran " +
                "gc_ms=+${format(pause / 1e6)}",
        )
    }

    /** What the heap and the collector have done so far, for a phase line. */
    fun summary(): String {
        val info = GC.lastGCInfo
        val heapBytes = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
        return "gcs=$collections gc_pause_ms=${format(pauseNanos / 1e6)} " +
            "heap_live_mb=${format(heapBytes / 1048576.0)}"
    }

    /** Takes in any collection that ran since the last look, and answers its pause in ns. */
    private fun observe(): Long {
        val info = GC.lastGCInfo ?: return 0
        if (info.epoch == lastEpoch) return 0
        val fresh = if (lastEpoch < 0) 1 else info.epoch - lastEpoch
        lastEpoch = info.epoch
        collections += fresh
        // A collection stops the world once to mark and may stop it again to finish.
        // Read through nullable locals so this holds whichever of them the runtime leaves unset.
        val firstStart: Long? = info.firstPauseStartTimeNs
        val firstEnd: Long? = info.firstPauseEndTimeNs
        val secondStart: Long? = info.secondPauseStartTimeNs
        val secondEnd: Long? = info.secondPauseEndTimeNs
        val pause = span(firstStart, firstEnd) + span(secondStart, secondEnd)
        pauseNanos += pause
        return pause
    }

    private fun span(start: Long?, end: Long?): Long =
        if (start != null && end != null && end > start) end - start else 0L

    private var sink: Any? = null

    private fun dirty() {
        val kept = ArrayList<ByteArray>()
        for (index in 0 until 300_000) {
            val bytes = ByteArray(1024)
            sink = bytes
            if (index % 40 == 0) kept.add(bytes)
            if (kept.size > 4_000) kept.subList(0, 2_000).clear()
        }
        sink = kept.size
    }

    private fun format(value: Double): String {
        val hundredths = kotlin.math.round(value * 100).toLong()
        val whole = hundredths / 100
        val part = kotlin.math.abs(hundredths % 100)
        return "$whole.${part.toString().padStart(2, '0')}"
    }
}
