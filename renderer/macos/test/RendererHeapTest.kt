package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.RENDERER_HEAP_UTILIZATION
import dev.darkpyonix.composerust.ui.platform.configureRendererHeap
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The room the collector gives this renderer's heap (#939).
 *
 * Left at Kotlin/Native's default, the heap is allowed to reach twice what the collector
 * counts as in use before the next collection, and an application that opened eight screens
 * four times held 69 to 87 MB of heap pages for 20 MB of objects.
 */
@OptIn(NativeRuntimeApi::class)
class RendererHeapTest {
    private var before = 0.0

    @BeforeTest
    fun remember() {
        before = GC.targetHeapUtilization
    }

    @AfterTest
    fun restore() {
        GC.targetHeapUtilization = before
    }

    @Test
    fun nfr3_the_renderer_tells_the_collector_how_full_its_heap_may_be() {
        GC.targetHeapUtilization = 0.5
        configureRendererHeap()
        assertEquals(
            RENDERER_HEAP_UTILIZATION,
            GC.targetHeapUtilization,
            "the renderer starts without telling the collector its target, so the heap is " +
                "allowed to grow to twice what is in use before a collection is due",
        )
    }

    @Test
    fun nfr3_the_heap_is_not_given_twice_what_is_in_use() {
        assertTrue(
            RENDERER_HEAP_UTILIZATION > 0.5 && RENDERER_HEAP_UTILIZATION < 1.0,
            "the target must leave less room than the default 0.5 and still leave some, " +
                "or every allocation would be due a collection; it is $RENDERER_HEAP_UTILIZATION",
        )
    }
}
