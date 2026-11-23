package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.IDLE_TRIM_DELAY_MS
import dev.darkpyonix.composerust.ui.platform.IdleHeapTrimmer
import dev.darkpyonix.composerust.ui.platform.IdleTrimHandle
import dev.darkpyonix.composerust.ui.platform.IdleTrimScheduler
import dev.darkpyonix.composerust.ui.platform.RENDERER_HEAP_UTILIZATION
import dev.darkpyonix.composerust.ui.platform.configureRendererHeap
import dev.darkpyonix.composerust.ui.platform.trimRendererHeap
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The room the collector gives this renderer's heap (#939) and the debounced idle trim that
 * returns empty pages to the OS after quiet periods (#1043).
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

    @Test
    fun nfr3_idle_trim_does_not_fire_before_delay_elapses() {
        val clock = FakeIdleTrimScheduler()
        var trims = 0
        val trimmer = IdleHeapTrimmer(
            delayMs = IDLE_TRIM_DELAY_MS,
            scheduler = clock,
            onTrim = { trims++ },
        )

        trimmer.noteActivity()
        clock.advanceBy(IDLE_TRIM_DELAY_MS - 1L)
        assertEquals(
            0,
            trims,
            "idle trim must not fire before the full quiet delay ($IDLE_TRIM_DELAY_MS ms) has elapsed",
        )

        clock.advanceBy(1L)
        assertEquals(
            1,
            trims,
            "idle trim must fire once the quiet delay ($IDLE_TRIM_DELAY_MS ms) elapses",
        )
    }

    @Test
    fun nfr3_repeated_activity_debounces_and_cancels_previous_idle_timer() {
        val clock = FakeIdleTrimScheduler()
        var trims = 0
        val trimmer = IdleHeapTrimmer(
            delayMs = IDLE_TRIM_DELAY_MS,
            scheduler = clock,
            onTrim = { trims++ },
        )

        trimmer.noteActivity()
        repeat(5) {
            clock.advanceBy(IDLE_TRIM_DELAY_MS - 1_000L)
            trimmer.noteActivity()
        }

        assertEquals(
            0,
            trims,
            "idle trim must never run while activity keeps arriving before the deadline",
        )
        assertEquals(
            5,
            clock.cancelledCount,
            "each new activity call must cancel the previously scheduled idle timer",
        )

        clock.advanceBy(IDLE_TRIM_DELAY_MS - 1L)
        assertEquals(0, trims, "deadline must be measured from the latest activity call")

        clock.advanceBy(1L)
        assertEquals(1, trims, "idle trim must fire once after the final activity goes quiet")
    }

    @Test
    fun nfr3_idle_trim_fires_once_per_quiet_period_and_waits_for_new_activity() {
        val clock = FakeIdleTrimScheduler()
        var trims = 0
        val trimmer = IdleHeapTrimmer(
            delayMs = IDLE_TRIM_DELAY_MS,
            scheduler = clock,
            onTrim = { trims++ },
        )

        trimmer.noteActivity()
        clock.advanceBy(IDLE_TRIM_DELAY_MS)
        assertEquals(1, trims)

        // Sustained inactivity must not keep re-running GC.
        clock.advanceBy(IDLE_TRIM_DELAY_MS * 10L)
        assertEquals(
            1,
            trims,
            "idle trim must not re-fire during continued inactivity without new activity",
        )

        // New activity starts a fresh quiet period.
        trimmer.noteActivity()
        clock.advanceBy(IDLE_TRIM_DELAY_MS - 1L)
        assertEquals(1, trims)
        clock.advanceBy(1L)
        assertEquals(2, trims)
    }

    @Test
    fun nfr3_stale_timer_callback_after_debounce_or_cancel_is_ignored() {
        val scheduled = mutableListOf<() -> Unit>()
        var trims = 0
        val trimmer = IdleHeapTrimmer(
            delayMs = IDLE_TRIM_DELAY_MS,
            scheduler = IdleTrimScheduler { _, action ->
                scheduled += action
                IdleTrimHandle {}
            },
            onTrim = { trims++ },
        )

        trimmer.noteActivity()
        trimmer.noteActivity()
        // Even if the first timer fires late, the generation check suppresses it.
        scheduled[0]()
        assertEquals(0, trims, "a stale callback from a superseded timer must not run onTrim")

        // Explicit cancel suppresses the currently scheduled timer too.
        trimmer.cancel()
        scheduled[1]()
        assertEquals(0, trims, "cancel() must prevent a pending timer callback from running onTrim")
    }

    @Test
    fun nfr3_trim_renderer_heap_collects_and_releases_malloc_pressure() {
        assertTrue(IDLE_TRIM_DELAY_MS > 0L, "default idle trim delay must be positive")
        trimRendererHeap()
        assertNotNull(
            GC.lastGCInfo,
            "trimRendererHeap must run a collection so empty pages can be returned to the OS",
        )
    }
}

private class FakeIdleTrimScheduler : IdleTrimScheduler {
    private data class Entry(
        val deadlineMs: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
        var fired: Boolean = false,
    )

    private var nowMs: Long = 0L
    private val entries = mutableListOf<Entry>()
    var cancelledCount: Int = 0
        private set

    override fun schedule(delayMs: Long, action: () -> Unit): IdleTrimHandle {
        val entry = Entry(deadlineMs = nowMs + delayMs, action = action)
        entries += entry
        return IdleTrimHandle {
            if (!entry.cancelled && !entry.fired) {
                entry.cancelled = true
                cancelledCount++
            }
        }
    }

    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0L)
        nowMs += deltaMs
        while (true) {
            val next = entries
                .filter { !it.cancelled && !it.fired && it.deadlineMs <= nowMs }
                .minByOrNull { it.deadlineMs }
                ?: break
            next.fired = true
            next.action()
        }
    }
}
