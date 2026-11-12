package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.EveryDpReport
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NODE = 1
private const val TOKEN = 7

/**
 * A node's own size report: the first measurement always goes out, and a node that asked
 * for every whole dp change hears a resize that stays inside one class.
 */
@OptIn(ExperimentalTestApi::class)
class ObserveSizeTest {
    private val frames = FrameRequestSource()

    private fun observed(everyDp: Boolean) = FakeHostConnection(
        listOf(
            Mutation.Create(NODE, WidgetKind.Text),
            Mutation.SetProp(NODE, PropertyKind.Text, PropertyValue.Text("hello")),
            // Filling the space, so the node is as large as the content area the test sets.
            Mutation.SetModifier(NODE, 1, ProtocolModifier.FillMaxWidth),
            Mutation.SetModifier(NODE, 2, ProtocolModifier.FillMaxHeight),
            Mutation.SetModifier(NODE, 3, ProtocolModifier.ObserveSize(TOKEN, everyDp)),
        ),
    )

    private fun FakeHostConnection.sizes() =
        events.filterIsInstance<HostEvent.WindowSizeChanged>().filter { it.nodeId == NODE }

    /**
     * A node that opens phone-sized is in the class a Host assumes before it hears
     * anything, and it still reports once, so an app that lays out by the node's size has
     * a size to start from. Either kind of subscription does.
     */
    @Test
    fun fr28_first_measurement_always_reports() {
        for (everyDp in listOf(false, true)) {
            runComposeUiTest {
                val connection = observed(everyDp)
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frames,
                        LocalDensity provides Density(1f),
                    ) {
                        ComposeRustContent(
                            rememberComposeRustHost(connection),
                            Modifier.requiredSize(300.dp, 300.dp),
                        )
                    }
                }
                waitForIdle()
                val sizes = connection.sizes()
                assertEquals(1, sizes.size, "everyDp=$everyDp: $sizes")
                assertEquals(WindowSizeClass.Compact, sizes.single().sizeClass)
                assertEquals(300f, sizes.single().widthDp)
            }
        }
    }

    /**
     * 520 dp and 580 dp are both compact. A node that asked for every dp hears the resize,
     * once, with the size it ended at; a node that did not hears nothing.
     */
    @Test
    fun fr28_every_dp_node_reports_a_resize_inside_one_class() {
        for (everyDp in listOf(true, false)) {
            runComposeUiTest {
                val connection = observed(everyDp)
                var width by mutableStateOf(520.dp)
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides frames,
                        LocalDensity provides Density(1f),
                    ) {
                        ComposeRustContent(
                            rememberComposeRustHost(connection),
                            Modifier.requiredSize(width, 300.dp),
                        )
                    }
                }
                waitForIdle()
                val before = connection.sizes().size
                assertEquals(1, before)

                width = 580.dp
                waitForIdle()
                val after = connection.sizes()
                if (everyDp) {
                    assertEquals(before + 1, after.size, "$after")
                    assertEquals(580f, after.last().widthDp)
                    assertEquals(WindowSizeClass.Compact, after.last().sizeClass)
                } else {
                    assertEquals(before, after.size, "$after")
                }

                // Under half a dp is not a whole dp change.
                width = 580.3.dp
                waitForIdle()
                assertEquals(after.size, connection.sizes().size)
            }
        }
    }

    /**
     * Several measurements before one draw are one event carrying the last of them, which
     * is what a frame that lays out more than once looks like.
     */
    @Test
    fun fr28_every_dp_coalesces_measurements_within_a_frame() {
        val sent = mutableListOf<HostEvent>()
        val dispatcher = EventDispatcher { sent += it; false }
        val report = EveryDpReport()

        report.widthDp = 520f
        report.heightDp = 300f
        report.measured = true
        report.flush(NODE, dispatcher)
        assertEquals(1, sent.size)

        for (width in listOf(540f, 560f, 580f)) {
            report.widthDp = width
            report.measured = true
        }
        report.flush(NODE, dispatcher)
        assertEquals(2, sent.size)
        assertEquals(580f, (sent.last() as HostEvent.WindowSizeChanged).widthDp)

        // A draw with nothing measured since sends nothing.
        report.flush(NODE, dispatcher)
        assertEquals(2, sent.size)

        // A measurement that rounds to the size already sent sends nothing either.
        report.widthDp = 580.4f
        report.measured = true
        report.flush(NODE, dispatcher)
        assertEquals(2, sent.size)
    }
}
