@file:OptIn(
    kotlin.native.runtime.NativeRuntimeApi::class,
    kotlin.ExperimentalStdlibApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.darkpyonix.composerust.ui.platform

import java.lang.System
import kotlin.native.runtime.GC
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.WindowEvent
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.Foundation.NSTimer

/**
 * The made-up input of a parity run on the Kotlin/Native macOS window: a click in the first
 * text field, five letters, the sizes a drag would pass through, and then the end of the run.
 * Asked for with `DXC_SYNTH`, the same variable and the same timeline every other window
 * reads, so the numbers it leaves in [LatencyTrace] and the `dxc resize:` line are
 * comparable. With `DXC_IDLE_SOAK_SECONDS`, it also records the settled footprint after the
 * input and after the requested seconds of rest. Unset, nothing here runs.
 *
 * Driven from a timer on the main run loop, which is where AppKit delivers everything else,
 * so the made-up input arrives the way a real one would. The window owns the scene; it is
 * handed the input and asked for its counts through the hooks it has for this.
 */
internal fun startParityRun(window: MacosWindow) {
    val what = System.getenv("DXC_SYNTH")
    val idleSeconds = System.getenv("DXC_IDLE_SOAK_SECONDS")?.toIntOrNull()?.takeIf { it > 0 }
    if (what == null && idleSeconds == null) return
    val input = what?.let { SyntheticInput(it) }
    val idleSoak = idleSeconds?.let { KnIdleSoakReport(it) }
    // The macOS window describes itself in its own element type, the same fields and roles as
    // the shared one the made-up input reads.
    if (input != null) {
        window.onElements = { elements ->
            input.noteElements(
                elements.map { AccessibleElement(it.role, it.x, it.y, it.width, it.height, it.label) },
            )
        }
        // Something has to be asking for the tree, or the field is never found: a reader
        // normally does, and nobody is reading this.
        window.listenForReader()
    }
    window.onFrameRendered = { nanos ->
        LatencyTrace.frameDrawn(nanos)
        idleSoak?.onFrameDrawn()
    }
    NSTimer.scheduledTimerWithTimeInterval(PARITY_TURN_SECONDS, repeats = true) { _ ->
        val now = System.nanoTime()
        if (input != null) {
            val measured = window.measure()
            for (event in input.due(now, androidx.compose.ui.unit.IntSize(measured.width, measured.height))) {
                if (event.kind == WindowEvent.TEXT_COMMIT) LatencyTrace.inputSent()
                LatencyTrace.mark("synthetic ${event.kind} sent")
                window.inject(event)
            }
            if (input.resizeDue(now)) {
                LatencyTrace.phase = "resize"
                window.scriptedResize(PARITY_RESIZE_WIDTH, PARITY_RESIZE_HEIGHT, PARITY_RESIZE_STEPS, PARITY_RESIZE_PAUSE_SECONDS)
                LatencyTrace.phase = "idle"
            }
            if (input.exitDue(now)) {
                LatencyTrace.summary()
                if (System.getenv("DXC_REPORT_RESIZE") != null) System.err.println(window.resizeStats.report())
                NSApplication.sharedApplication().terminate(null)
            }
        }
        if (idleSoak?.tick(now) == true) {
            LatencyTrace.summary()
            NSApplication.sharedApplication().terminate(null)
        }
    }
}

private class KnIdleSoakReport(private val seconds: Int) {
    private val openedAt = System.nanoTime()
    private var idleStartedAt = 0L
    private var lastSampledSecond = 0
    private var framesDrawn = 0L

    fun onFrameDrawn() {
        framesDrawn++
    }

    fun tick(now: Long): Boolean {
        if (idleStartedAt == 0L) {
            if ((now - openedAt) / 1_000_000_000L < 8L) return false
            GC.collect()
            idleStartedAt = System.nanoTime()
            log("start", 0, breakdown = true)
            return false
        }
        val elapsed = ((now - idleStartedAt) / 1_000_000_000L).toInt()
        if (elapsed >= seconds) {
            GC.collect()
            log("end", seconds, breakdown = true)
            printStderr("compose-rust: idle-soak done seconds=$seconds")
            return true
        }
        val step = (elapsed / 10) * 10
        if (step > lastSampledSecond) {
            lastSampledSecond = step
            log("sample", step, breakdown = false)
        }
        return false
    }

    private fun log(phase: String, elapsed: Int, breakdown: Boolean) {
        val (footprint, resident) = processMemory()
        val info = GC.lastGCInfo
        val live = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
        printStderr(
            "compose-rust: idle-soak phase $phase elapsed_s=$elapsed frames=$framesDrawn " +
                "footprint_mb=${mb(footprint)} resident_mb=${mb(resident)} " +
                "heap_used_mb=${mb(live)} heap_max_mb=${mb(GC.targetHeapBytes)} " +
                "gcs=${info?.epoch ?: 0} " +
                "skia_cpu_cache_mb=${mb(org.jetbrains.skia.Graphics.resourceCacheTotalUsed.toLong())}",
        )
        if (breakdown && System.getenv("DXC_METRICS_BREAKDOWN") == "1") {
            platform.posix.fflush(null)
            platform.posix.system(
                "/usr/bin/footprint ${platform.posix.getpid()} 2>/dev/null | " +
                    "sed 's/^/compose-rust: idle-soak breakdown elapsed_s=$elapsed $phase /' 1>&2",
            )
        }
    }

    private fun mb(bytes: Long): Long = if (bytes < 0) -1 else (bytes + 524288) / 1048576

    private fun processMemory(): Pair<Long, Long> = memScoped {
        val info = alloc<platform.darwin.task_vm_info_data_t>()
        val count = alloc<platform.darwin.mach_msg_type_number_tVar>()
        count.value = (sizeOf<platform.darwin.task_vm_info_data_t>() / 4).toUInt()
        // TASK_VM_INFO
        platform.darwin.task_info(platform.darwin.mach_task_self_, 22u, info.ptr.reinterpret(), count.ptr)
        info.phys_footprint.toLong() to info.resident_size.toLong()
    }

    private fun printStderr(line: String) {
        platform.posix.fputs(line + "\n", platform.posix.stderr)
        platform.posix.fflush(platform.posix.stderr)
    }
}

/** How often the made-up input looks at the clock. */
private const val PARITY_TURN_SECONDS = 0.016

// The scripted drag: the size every window is taken to, in the same steps.
private const val PARITY_RESIZE_WIDTH = 360.0
private const val PARITY_RESIZE_HEIGHT = 420.0
private const val PARITY_RESIZE_STEPS = 60
private const val PARITY_RESIZE_PAUSE_SECONDS = 0.008
