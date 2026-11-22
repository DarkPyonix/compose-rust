package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * TRIAL (memory under 100 MB): collector settings and an idle trim, driven by environment
 * variables so one build can be measured with several settings. Not for merging as it is.
 *
 * `DXC_GC_UTILIZATION` (0.1..0.95): how full the heap is allowed to be after a collection
 * before the next target is set. `DXC_GC_MIN_HEAP_MB`: the smallest target.
 * `DXC_IDLE_TRIM_SECONDS`: seconds without a drawn frame after which two collections run,
 * the second so the pages the first one emptied are given back.
 */
@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
internal class HeapTrim(private val nanoTime: () -> Long = System::nanoTime) {
    private val idleNanos: Long =
        ((System.getenv("DXC_IDLE_TRIM_SECONDS")?.toDoubleOrNull() ?: 0.0) * 1e9).toLong()
    private var lastFrame = nanoTime()
    private var stage = 0

    init {
        System.getenv("DXC_GC_UTILIZATION")?.toDoubleOrNull()?.let { GC.targetHeapUtilization = it }
        System.getenv("DXC_GC_MIN_HEAP_MB")?.toLongOrNull()?.let { GC.minHeapBytes = it * 1048576L }
        System.err.println(
            "compose-rust: heap-trim utilization=${GC.targetHeapUtilization} " +
                "min_heap_mb=${GC.minHeapBytes / 1048576L} max_heap_mb=${GC.maxHeapBytes / 1048576L} " +
                "target_mb=${GC.targetHeapBytes / 1048576L} idle_trim_s=${idleNanos / 1e9}",
        )
    }

    fun onFrameDrawn() {
        lastFrame = nanoTime()
        stage = 0
    }

    private val gcLog = System.getenv("DXC_GC_LOG") == "1"
    private var lastLog = nanoTime()
    private var lastEpoch = 0L
    private var pauseNanos = 0L

    fun tick() {
        if (gcLog) {
            val info = GC.lastGCInfo
            if (info != null && info.epoch != lastEpoch) {
                lastEpoch = info.epoch
                pauseNanos += (info.firstPauseEndTimeNs - info.firstPauseStartTimeNs) +
                    ((info.secondPauseEndTimeNs ?: 0L) - (info.secondPauseStartTimeNs ?: 0L))
            }
            val now = nanoTime()
            if (now - lastLog >= 5_000_000_000L) {
                lastLog = now
                System.err.println(
                    "compose-rust: heap-trim gc epoch=$lastEpoch seen_pause_ms=${pauseNanos / 1_000_000L} " +
                        "last_gc_ms=${info?.let { (it.endTimeNs - it.startTimeNs) / 1_000_000L }} " +
                        "target_mb=${GC.targetHeapBytes / 1048576L}",
                )
            }
        }
        if (idleNanos <= 0L || stage >= 2) return
        val idle = nanoTime() - lastFrame
        if (idle >= idleNanos + stage * 1_000_000_000L) {
            GC.collect()
            stage++
            val info = GC.lastGCInfo
            val live = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
            System.err.println("compose-rust: heap-trim collect stage=$stage heap_after_mb=${live / 1048576L}")
        }
    }
}

/**
 * TRIAL: `DXC_WINDOW_SRGB=1` gives the window the sRGB colour space; `DXC_DRAWABLES=n` caps the
 * Metal layer's drawables; `DXC_IDLE_PURGE_SECONDS=n` empties Skia's GPU cache after n seconds
 * without a frame; `DXC_METAL_LOG=1` prints what Metal has allocated every 5 seconds.
 */
internal class WindowTrials(private val window: org.thisisthepy.compose.window.macos.MacosWindow) {
    private val purgeNanos = ((System.getenv("DXC_IDLE_PURGE_SECONDS")?.toDoubleOrNull() ?: 0.0) * 1e9).toLong()
    private val metalLog = System.getenv("DXC_METAL_LOG") == "1"
    private var lastFrame = System.nanoTime()
    private var purged = false
    private var lastLog = System.nanoTime()

    init {
        if (System.getenv("DXC_WINDOW_SRGB") == "1") {
            window.window.colorSpace = platform.AppKit.NSColorSpace.sRGBColorSpace
            System.err.println("compose-rust: heap-trim window colour space sRGB")
        }
        System.getenv("DXC_DRAWABLES")?.toIntOrNull()?.let { count ->
            window.metalSurface.layer.maximumDrawableCount = count.toULong()
            System.err.println("compose-rust: heap-trim drawables=${window.metalSurface.layer.maximumDrawableCount}")
        }
    }

    fun onFrameDrawn() {
        lastFrame = System.nanoTime()
        purged = false
    }

    fun tick() {
        val now = System.nanoTime()
        if (metalLog && now - lastLog >= 5_000_000_000L) {
            lastLog = now
            System.err.println(
                "compose-rust: heap-trim metal_allocated_mb=${window.metalSurface.allocatedBytes / 1048576L} " +
                    "cache_limit_mb=${window.metalSurface.cacheLimitBytes / 1048576L}",
            )
        }
        if (purgeNanos > 0L && !purged && now - lastFrame >= purgeNanos) {
            purged = true
            val limit = window.metalSurface.cacheLimitBytes
            val before = window.metalSurface.allocatedBytes
            window.metalSurface.cacheLimitBytes = 0L
            window.metalSurface.cacheLimitBytes = limit
            System.err.println(
                "compose-rust: heap-trim purge metal_before_mb=${before / 1048576L} " +
                    "metal_after_mb=${window.metalSurface.allocatedBytes / 1048576L}",
            )
        }
    }
}
