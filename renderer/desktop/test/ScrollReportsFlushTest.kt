package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.ScrollPort
import dev.darkpyonix.composerust.foundation.ScrollReports
import dev.darkpyonix.composerust.foundation.ScrollSample
import dev.darkpyonix.composerust.protocol.HostEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One frame's scroll reports go out one after another, and the Host's answer to one of them
 * can remove a container whose report comes later in the same frame. The removed one sends
 * nothing: the Host has forgotten its handler and refuses the event.
 */
class ScrollReportsFlushTest {
    private fun port(nodeId: Int, handler: Long): ScrollPort = ScrollPort(nodeId).apply {
        handlerId = handler
        sampler = {
            ScrollSample(
                item = 0, x = 0, y = 40, maxX = -1, maxY = 400,
                viewportWidth = 100, viewportHeight = 100,
                canScrollBackward = true, canScrollForward = true, moving = false,
            )
        }
    }

    private fun handlersOf(events: List<HostEvent>): List<Long> =
        events.filterIsInstance<HostEvent.ScrollChanged>().map { it.handlerId }

    @Test
    fun fr80_a_container_removed_by_an_earlier_report_in_the_flush_sends_nothing() {
        val reports = ScrollReports()
        val first = port(nodeId = 10, handler = 1)
        val second = port(nodeId = 11, handler = 2)
        reports.changed(first)
        reports.changed(second)

        val sent = ArrayList<HostEvent>()
        reports.flush { event ->
            sent += event
            // The Host's answer to the first report removes the second container, which the
            // node table tells the reports as it removes the node.
            if (event is HostEvent.ScrollChanged && event.handlerId == 1L) reports.forget(second)
        }

        assertEquals(listOf(1L), handlersOf(sent))
    }

    @Test
    fun fr80_every_container_still_on_screen_reports_in_the_flush() {
        val reports = ScrollReports()
        val first = port(nodeId = 10, handler = 1)
        val second = port(nodeId = 11, handler = 2)
        reports.changed(first)
        reports.changed(second)

        val sent = ArrayList<HostEvent>()
        reports.flush { sent += it }

        assertEquals(listOf(1L, 2L), handlersOf(sent))
    }
}
