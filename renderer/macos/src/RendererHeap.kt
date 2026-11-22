package dev.darkpyonix.composerust.ui.platform

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

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
 * Tells the collector how much room this renderer's heap is given. Called once, before the
 * renderer allocates anything that lasts.
 */
@OptIn(NativeRuntimeApi::class)
internal fun configureRendererHeap() {
    GC.targetHeapUtilization = RENDERER_HEAP_UTILIZATION
}
