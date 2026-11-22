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

    // TRIAL (root cause, 2026-11-22). `DXC_GPU_LOG=1`: one line per drawn frame in which Metal's
    // allocation moved by 64 KB or more, or in which a blurred layer was drawn with a different
    // count, with Skia's CPU caches. `DXC_GPU_PROBE_FILE=<dir>`: when a file appears in the directory,
    // its name is a label and it is removed; Metal is read, Skia's GPU cache is emptied (limit 0 and back), and Metal is
    // read again at once and half a second later. `DXC_BACKDROP=none|inactive`: the window's
    // NSVisualEffectView is taken out (its subviews moved to a plain view that answers the same
    // accessibility children) or set inactive.
    private val gpuLog = System.getenv("DXC_GPU_LOG") == "1"
    private val probeFile = System.getenv("DXC_GPU_PROBE_FILE")
    private val backdropMode = System.getenv("DXC_BACKDROP")
    private val origin = System.nanoTime()
    private var frames = 0L
    private var loggedMetal = -1L
    private var loggedBlur = ""
    private var blurFrames = 0L
    private var probeLabel: String? = null
    private var probeAgainAt = 0L
    private var backdropDone = false

    private fun ms() = (System.nanoTime() - origin) / 1_000_000L
    private fun skiaCpu() =
        "skia_cpu_kb=${org.jetbrains.skia.Graphics.resourceCacheTotalUsed.toLong() / 1024L} " +
            "font_cache_kb=${org.jetbrains.skia.Graphics.fontCacheUsed.toLong() / 1024L}"

    // `DXC_PURGE_ALL_SECONDS=n`: Graphics.purgeAllCaches() once n seconds after the last frame.
    private val filterPurgeNanos =
        ((System.getenv("DXC_PURGE_ALL_SECONDS")?.toDoubleOrNull() ?: 0.0) * 1e9).toLong()
    private var filtersPurged = false

    init {
        // Before the run loop turns, so the first layer commit already sees the change.
        applyBackdrop()
        if (System.getenv("DXC_NO_TITLE") == "1") {
            window.window.setTitle("")
            window.window.titleVisibility = platform.AppKit.NSWindowTitleHidden
            System.err.println("compose-rust: trial title emptied and hidden")
        }
    }

    fun onFrameDrawn() {
        lastFrame = System.nanoTime()
        purged = false
        filtersPurged = false
        if (!gpuLog) {
            dev.darkpyonix.composerust.design.BlurTrial.reset()
            return
        }
        frames++
        val metal = window.metalSurface.allocatedBytes
        val blurred = dev.darkpyonix.composerust.design.BlurTrial.any()
        if (blurred) blurFrames++
        val blur = dev.darkpyonix.composerust.design.BlurTrial.summary()
        val moved = loggedMetal < 0 || kotlin.math.abs(metal - loggedMetal) >= 65536L
        if (moved || blur != loggedBlur) {
            System.err.println(
                "compose-rust: gpu frame=$frames t_ms=${ms()} metal_kb=${metal / 1024L} " +
                    "delta_kb=${if (loggedMetal < 0) 0 else (metal - loggedMetal) / 1024L} " +
                    "drawable=${window.metalSurface.widthInPixels}x${window.metalSurface.heightInPixels} " +
                    "${skiaCpu()} blur_frames=$blurFrames $blur",
            )
            loggedMetal = metal
            loggedBlur = blur
        }
        dev.darkpyonix.composerust.design.BlurTrial.reset()
    }

    private fun applyBackdrop() {
        if (backdropDone || backdropMode == null) return
        backdropDone = true
        val content = window.window.contentView ?: return
        val effect = content as? platform.AppKit.NSVisualEffectView
        if (effect == null) {
            System.err.println("compose-rust: trial backdrop content view is not an effect view")
            return
        }
        when (backdropMode) {
            "inactive" -> {
                effect.state = platform.AppKit.NSVisualEffectState.NSVisualEffectStateInactive
                System.err.println("compose-rust: trial backdrop inactive")
            }
            "none" -> {
                val plain = TrialPlainContent(effect)
                plain.autoresizingMask = effect.autoresizingMask
                val responder = window.window.firstResponder
                val children = effect.subviews.map { it as platform.AppKit.NSView }
                window.window.contentView = plain
                for (child in children) {
                    child.removeFromSuperview()
                    plain.addSubview(child)
                }
                if (responder != null) window.window.makeFirstResponder(responder)
                System.err.println("compose-rust: trial backdrop removed, moved ${children.size} subviews")
            }
        }
    }

    private fun probe(now: Long) {
        val path = probeFile ?: return
        if (probeLabel != null && probeAgainAt > 0L && now >= probeAgainAt) {
            System.err.println(
                "compose-rust: gpu probe label=$probeLabel stage=after500ms t_ms=${ms()} " +
                    "metal_kb=${window.metalSurface.allocatedBytes / 1024L} frames=$frames",
            )
            probeLabel = null
            probeAgainAt = 0L
        }
        val files = platform.Foundation.NSFileManager.defaultManager
        val names = files.contentsOfDirectoryAtPath(path, error = null) ?: return
        val name = names.firstOrNull() as? String ?: return
        files.removeItemAtPath("$path/$name", error = null)
        val label = name
        val before = window.metalSurface.allocatedBytes
        val limit = window.metalSurface.cacheLimitBytes
        window.metalSurface.cacheLimitBytes = 0L
        window.metalSurface.cacheLimitBytes = limit
        val after = window.metalSurface.allocatedBytes
        // Then every Skia CPU-side cache, which includes the image filter cache: a filter's
        // GPU result held there is referenced, so the GPU cache limit cannot free it.
        org.jetbrains.skia.Graphics.purgeAllCaches()
        val afterAll = window.metalSurface.allocatedBytes
        System.err.println(
            "compose-rust: gpu probe label=$label stage=purge t_ms=${ms()} metal_before_kb=${before / 1024L} " +
                "metal_after_kb=${after / 1024L} freed_kb=${(before - after) / 1024L} limit_mb=${limit / 1048576L} " +
                "metal_after_all_kb=${afterAll / 1024L} freed_all_kb=${(after - afterAll) / 1024L} " +
                "frames=$frames ${skiaCpu()}",
        )
        probeLabel = label
        probeAgainAt = now + 500_000_000L
    }

    fun tick() {
        val now = System.nanoTime()
        applyBackdrop()
        probe(now)
        if (metalLog && now - lastLog >= 5_000_000_000L) {
            lastLog = now
            System.err.println(
                "compose-rust: heap-trim metal_allocated_mb=${window.metalSurface.allocatedBytes / 1048576L} " +
                    "cache_limit_mb=${window.metalSurface.cacheLimitBytes / 1048576L}",
            )
        }
        if (filterPurgeNanos > 0L && !filtersPurged && now - lastFrame >= filterPurgeNanos) {
            filtersPurged = true
            val before = window.metalSurface.allocatedBytes
            org.jetbrains.skia.Graphics.purgeAllCaches()
            if (gpuLog) {
                System.err.println(
                    "compose-rust: gpu idle purge_all t_ms=${ms()} metal_before_kb=${before / 1024L} " +
                        "metal_after_kb=${window.metalSurface.allocatedBytes / 1024L}",
                )
            }
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

/** TRIAL: the plain content view `DXC_BACKDROP=none` puts where the effect view was. */
private class TrialPlainContent(private val effect: platform.AppKit.NSView) :
    platform.AppKit.NSView(effect.frame) {
    override fun isAccessibilityElement(): Boolean = true
    override fun accessibilityRole(): String? = effect.accessibilityRole()
    override fun accessibilityChildren(): List<*>? = effect.accessibilityChildren()
}
