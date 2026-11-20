@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.MacosWindowBackend
import dev.darkpyonix.composerust.ui.platform.PlatformWindowConfig
import dev.darkpyonix.composerust.ui.platform.PlatformWindowListener
import dev.darkpyonix.composerust.ui.platform.ScreenBounds
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AppKit.NSApplication
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.posix.RUSAGE_SELF
import platform.posix.getrusage
import platform.posix.rusage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class StaticScrollHost(private val batch: List<Mutation>) : HostConnection {
    override fun init(onMutation: (Mutation) -> Unit) {
        batch.forEach(onMutation)
    }

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long = 0

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {}

    override fun shutdown() {}
}

private object MultiWindowNoopListener : PlatformWindowListener {
    override fun onCloseRequested() {}
    override fun onFocusChanged(focused: Boolean) {}
    override fun onMoved(bounds: ScreenBounds) {}
    override fun onFollowEnded(cancelled: Boolean) {}
}

/**
 * Opening and closing 20 windows in one process must not leak per-window GPU/Skia contexts
 * or slow down later windows relative to early ones.
 */
class MultiWindowLeakTest {
    private fun spinLoop(seconds: Double = 0.02) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    private fun peakRssBytes(): Long = memScoped {
        val usage = alloc<rusage>()
        getrusage(RUSAGE_SELF, usage.ptr)
        usage.ru_maxrss
    }

    private fun scrollingPageBatch(): List<Mutation> = buildList {
        add(Mutation.Create(1, WidgetKind.ScrollColumn))
        add(Mutation.SetModifier(1, 0, ProtocolModifier.FillMaxWidth))
        add(Mutation.SetModifier(1, 1, ProtocolModifier.FillMaxHeight))
        add(Mutation.Insert(0, 1, -1))
        for (i in 0 until 30) {
            val id = 10 + i
            add(Mutation.Create(id, WidgetKind.Text))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text("Row $i in scrolling page")))
            add(Mutation.Insert(1, id, -1))
        }
    }

    @Test
    fun nfr9_twenty_windows_keep_frame_time_and_memory_bounded() {
        NSApplication.sharedApplication()
        val windowCount = 20
        val batch = scrollingPageBatch()
        val windowMediansMs = ArrayList<Double>(windowCount)
        var rssAfterWarmup = 0L

        for (i in 0 until windowCount) {
            val host = ComposeRustHost(StaticScrollHost(batch))
            host.start(FrameRequestSource())
            val frameNanos = ArrayList<Long>(8)
            val backend = MacosWindowBackend(scene = { _, window, _, _ ->
                window.onFrameRendered = { nanos -> frameNanos += nanos }
                { ComposeRustContent(host) }
            })
            val opened = assertNotNull(
                backend.open(
                    PlatformWindowConfig(nodeId = 0, title = "leak-check-$i", width = 420f, height = 360f),
                    MultiWindowNoopListener,
                ),
            )
            try {
                var spins = 0
                while (frameNanos.size < 10 && spins < 200) {
                    backend.requestFrameAll()
                    spinLoop(0.005)
                    spins++
                }
                assertTrue(frameNanos.size >= 4, "window $i rendered only ${frameNanos.size} frames")
                // Skip the first 2 frames (initial composition and layout) so we compare steady-state frames.
                val steady = frameNanos.drop(2).sorted()
                val medianMs = steady[steady.size / 2] / 1_000_000.0
                windowMediansMs += medianMs
            } finally {
                opened.close()
                // Pump the main queue so the deferred window.host?.close() and window.close() run.
                spinLoop(0.03)
            }
            if (i == 3) {
                rssAfterWarmup = peakRssBytes()
            }
        }

        val rssAtEnd = peakRssBytes()
        val firstMedians = windowMediansMs.subList(2, 6).sorted()
        val lastMedians = windowMediansMs.subList(windowCount - 4, windowCount).sorted()
        val earlyMs = firstMedians[firstMedians.size / 2]
        val lateMs = lastMedians[lastMedians.size / 2]
        val rssGrowthMb = (rssAtEnd - rssAfterWarmup) / (1024.0 * 1024.0)

        println(
            "nfr9 20-window medians (ms): all=${windowMediansMs.map { ((it * 100).toInt() / 100.0) }} " +
                "early=${earlyMs}ms late=${lateMs}ms " +
                "rssWarmup=${rssAfterWarmup / (1024 * 1024)}MB rssEnd=${rssAtEnd / (1024 * 1024)}MB " +
                "growth=${rssGrowthMb}MB",
        )

        val frameCeilingMs = maxOf(earlyMs * 1.5, earlyMs + 1.5)
        assertTrue(
            lateMs <= frameCeilingMs,
            "last windows slowed down: early=${earlyMs}ms, late=${lateMs}ms (ceiling=${frameCeilingMs}ms)",
        )
        assertTrue(
            rssGrowthMb < 120.0,
            "resident memory grew by ${rssGrowthMb}MB across windows 4..20 (expected < 120MB)",
        )
    }
}
