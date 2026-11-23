package dev.darkpyonix.composerust.ui.platform

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer

/**
 * How full the collector lets the heap be after a collection: the next collection is due
 * when the heap reaches what was in use divided by this.
 *
 * Kotlin/Native's default is 0.5, twice what is in use. What the collector counts as in use
 * is already more than the objects themselves, because it does not move objects and a page
 * with one object in it counts: in an application of this renderer it gave 44 MB for 20 MB
 * of objects. Twice that again let the heap reach 69 to 87 MB while screens were opened, and
 * pages reached on the way are not given back (#939).
 *
 * Measured on Ember (macOS arm64, 1440x900, 32 screen switches in one run, 2026-11-21):
 *
 * | value | heap pages after the switches | process footprint | process CPU | collections |
 * |---|---|---|---|---|
 * | 0.5 | 69 to 87 MB | about 184 MB | 19.2 s | 78 |
 * | 0.8 | about 53 MB | about 166 MB | 23.6 s | 306 |
 * | 0.9 | about 47 MB | about 158 MB | 33.9 s | 816 |
 *
 * 0.8 is the value because the last 8 MB that 0.9 gives cost three quarters more CPU on every
 * screen switch, which is battery and heat on a laptop, against a quarter more at 0.8
 * (leader, 2026-11-21). The time the main thread is paused stays about 55 ms in total over the
 * run at either value: the work is on the collector's own thread.
 */
internal const val RENDERER_HEAP_UTILIZATION = 0.8

/**
 * How long the renderer must stay quiet after the last drawn frame before an idle heap trim
 * runs (#1043).
 */
internal const val IDLE_TRIM_DELAY_MS: Long = 5_000L

/**
 * Tells the collector how much room this renderer's heap is given. Called once, before the
 * renderer allocates anything that lasts.
 */
@OptIn(NativeRuntimeApi::class)
internal fun configureRendererHeap() {
    GC.targetHeapUtilization = RENDERER_HEAP_UTILIZATION
}

/**
 * A cancellable handle for a scheduled one-shot idle trim.
 */
internal fun interface IdleTrimHandle {
    fun cancel()
}

/**
 * Schedules a one-shot callback after [delayMs] milliseconds.
 *
 * Production uses an [NSTimer] on [NSRunLoop.mainRunLoop]; tests supply a fake clock so
 * debounce and firing behaviour are checked deterministically without sleeping.
 */
internal fun interface IdleTrimScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): IdleTrimHandle
}

/**
 * Schedules one-shot idle trim timers on AppKit's main run loop so [IdleHeapTrimmer] runs on
 * the same thread that draws frames and processes input.
 */
internal object MainRunLoopIdleTrimScheduler : IdleTrimScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): IdleTrimHandle {
        val timer = NSTimer.timerWithTimeInterval(
            interval = delayMs.toDouble() / 1000.0,
            repeats = false,
        ) { _: NSTimer? ->
            action()
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        return IdleTrimHandle { timer.invalidate() }
    }
}

/**
 * Debounces renderer activity and runs a single heap trim after [delayMs] of quiet (#1043).
 *
 * Each [noteActivity] call cancels any pending timer and schedules one fresh timer for
 * [delayMs]. When the timer fires with no intervening activity, [onTrim] runs once and no
 * further trim is scheduled until another [noteActivity] call arrives.
 */
internal class IdleHeapTrimmer(
    private val delayMs: Long = IDLE_TRIM_DELAY_MS,
    private val scheduler: IdleTrimScheduler = MainRunLoopIdleTrimScheduler,
    private val onTrim: () -> Unit = ::trimRendererHeap,
) {
    init {
        require(delayMs > 0L) { "idle trim delay must be positive, got $delayMs" }
    }

    private var pending: IdleTrimHandle? = null
    private var generation: Long = 0L

    /** Records that a frame was drawn or user input arrived, pushing the idle trim back. */
    fun noteActivity() {
        pending?.cancel()
        val token = ++generation
        pending = scheduler.schedule(delayMs) {
            if (token == generation) {
                pending = null
                onTrim()
            }
        }
    }

    /** Cancels any pending idle trim without scheduling a new one. */
    fun cancel() {
        generation++
        pending?.cancel()
        pending = null
    }
}

/** Shared idle trimmer used by the macOS renderer's frame callback. */
internal val rendererIdleTrimmer = IdleHeapTrimmer()

private typealias MallocZonePressureReliefFn =
    CFunction<(CPointer<ByteVar>?, ULong) -> ULong>

/**
 * Resolves `malloc_zone_pressure_relief` once from the running process image.
 *
 * Kotlin/Native's macOS `platform.darwin` and `platform.posix` klibs do not bind
 * `malloc_zone_pressure_relief`, even though `libSystem` exports it on macOS 10.7+.
 * Resolving it through [hostSymbol] (`dlsym` on the process image) avoids a custom cinterop
 * `.def` file while calling the system symbol directly.
 */
private val mallocZonePressureReliefFn: CPointer<MallocZonePressureReliefFn>? by lazy {
    hostSymbol("malloc_zone_pressure_relief")?.reinterpret()
}

/**
 * Advises Darwin's malloc zones to release empty regions back to the OS (`munmap` /
 * `MADV_FREE_REUSABLE`). Passing `null` inspects all zones and `0uL` asks for maximal relief.
 *
 * Returns the number of bytes the allocator reported releasing, or `0uL` if the symbol is
 * unavailable.
 */
internal fun releaseMallocPressure(): ULong {
    val fn = mallocZonePressureReliefFn ?: return 0uL
    return fn(null, 0uL)
}

/**
 * Runs two full Kotlin/Native GC cycles and asks macOS malloc zones to return empty pages
 * to the OS (#1043).
 *
 * Why two `GC.collect()` calls: the first cycle marks live objects, sweeps dead ones and
 * runs finalizers / clears weak references; Kotlin/Native's allocator can still hold pages
 * emptied during that sweep or referenced by finalized wrappers until a second cycle finishes
 * sweeping and releases those empty heap pages back to the system allocator. Calling
 * [releaseMallocPressure] immediately afterwards returns freed `MALLOC_LARGE (empty)` regions
 * (such as the startup font/batch buffers) to the OS promptly instead of waiting for system
 * memory pressure.
 */
@OptIn(NativeRuntimeApi::class)
internal fun trimRendererHeap() {
    GC.collect()
    GC.collect()
    releaseMallocPressure()
}
