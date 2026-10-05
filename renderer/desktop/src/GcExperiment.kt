package dev.darkpyonix.composerust.ui.platform

import java.lang.management.ManagementFactory

/**
 * A measurement of what the collector does to a live resize, asked for with DXC_METRICS=1.
 *
 * Three seconds in it reports the heap, dirties it the way a session of use does (many
 * short-lived objects and some that live a while), then takes the window through 100 sizes
 * in one scripted drag, printing for every frame drawn during it how long it took and how
 * many collections ran. Run with DXC_SVM_OPTIONS="-XX:+PrintGC -XX:+VerboseGC" the
 * collector's own lines land between the frame lines, and with a heap option added
 * (-Xmn, -Xms, -Xmx) the same run tells whether the heap size changes what the frames cost.
 */
internal class GcExperiment private constructor() {
    private val started = System.nanoTime()
    private var done = false
    private var active = false
    private var frameStarted = 0L
    private var gcsBefore = 0L
    private var gcMillisBefore = 0L

    /** True once, three seconds in. */
    fun due(now: Long): Boolean = !done && now - started >= 3_000_000_000L

    /** Runs the whole measurement. [resize] takes the window from one size to another in points. */
    fun run(
        resize: (from: Pair<Int, Int>, to: Pair<Int, Int>, steps: Int) -> Unit,
        memory: (String) -> Unit = {},
    ) {
        done = true
        phase("start"); memory("start")
        dirty()
        phase("dirtied"); memory("dirtied")
        active = true
        resize(800 to 600, 1200 to 900, 50)
        resize(1200 to 900, 800 to 600, 50)
        active = false
        phase("after-100-resizes"); memory("after-100-resizes")
    }

    /** Around every frame drawn; prints only during the scripted drag. */
    fun frameBegin() {
        if (!active) return
        frameStarted = System.nanoTime()
        gcsBefore = gcCount()
        gcMillisBefore = gcMillis()
    }

    fun frameEnd(width: Int, height: Int) {
        if (!active) return
        val millis = (System.nanoTime() - frameStarted) / 1e6
        System.err.println(
            "compose-rust: metrics frame ${width}x$height ms=${"%.2f".format(millis)} " +
                "gcs=+${gcCount() - gcsBefore} gc_ms=+${gcMillis() - gcMillisBefore}",
        )
    }

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

    private fun phase(name: String) {
        val runtime = Runtime.getRuntime()
        val mb = 1024.0 * 1024.0
        System.err.println(
            "compose-rust: metrics phase $name gcs=${gcCount()} gc_ms=${gcMillis()} " +
                "heap_used_mb=${"%.1f".format((runtime.totalMemory() - runtime.freeMemory()) / mb)} " +
                "heap_committed_mb=${"%.1f".format(runtime.totalMemory() / mb)} " +
                "heap_max_mb=${"%.1f".format(runtime.maxMemory() / mb)}",
        )
    }

    private fun gcCount(): Long = try {
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionCount) }
    } catch (_: Throwable) {
        -1L
    }

    private fun gcMillis(): Long = try {
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionTime) }
    } catch (_: Throwable) {
        -1L
    }

    companion object {
        fun fromEnvironment(): GcExperiment? =
            if (System.getenv("DXC_METRICS") == "1") GcExperiment() else null
    }
}
