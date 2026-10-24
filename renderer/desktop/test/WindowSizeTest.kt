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
import dev.darkpyonix.composerust.protocol.WindowHeightClass
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.WindowSizeReporter
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.runtime.windowHeightClassOf
import dev.darkpyonix.composerust.runtime.windowSizeClassOf
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LABEL = 1

/** The token the Host would have handed out for a node it wants the size of. */
private const val SIZE_TOKEN = 7

/** Recording dispatcher: the Host is not involved in what the reporter decides to send. */
private class RecordingDispatcher : EventDispatcher {
    val events = mutableListOf<HostEvent>()

    override fun dispatch(event: HostEvent): Boolean {
        events += event
        return false
    }
}

/**
 * The window size class: which class a width belongs to, and the rule that the Host hears
 * about it only when the class changes.
 */
@OptIn(ExperimentalTestApi::class)
class WindowSizeTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr20_size_classes_split_at_six_hundred_and_eight_hundred_and_forty_dp() {
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(0f))
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(599.9f))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(600f))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(839.9f))
        assertEquals(WindowSizeClass.Expanded, windowSizeClassOf(840f))
    }

    @Test
    fun fr20_height_classes_split_at_four_hundred_and_eighty_and_nine_hundred_dp() {
        assertEquals(WindowHeightClass.Compact, windowHeightClassOf(0f))
        assertEquals(WindowHeightClass.Compact, windowHeightClassOf(479.9f))
        assertEquals(WindowHeightClass.Medium, windowHeightClassOf(480f))
        assertEquals(WindowHeightClass.Medium, windowHeightClassOf(899.9f))
        assertEquals(WindowHeightClass.Expanded, windowHeightClassOf(900f))
    }

    @Test
    fun fr20_crossing_a_boundary_sends_one_event_and_resizing_within_a_class_sends_none() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        // A window that opens small in both axes says nothing: the Host already assumes
        // Compact for each. Nor does any width from 300dp to 599dp at the same height,
        // all of them the same pair of classes.
        for (widthDp in 300..599) {
            reporter.report(widthDp.toFloat(), 400f, dispatcher)
        }
        assertEquals(0, dispatcher.events.size)

        // 599dp to 601dp crosses the boundary: exactly one event.
        assertTrue(reporter.report(601f, 400f, dispatcher))
        assertEquals(1, dispatcher.events.size)
        val event = dispatcher.events[0] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Medium, event.sizeClass)
        assertEquals(WindowHeightClass.Compact, event.heightClass)
        assertEquals(601f, event.widthDp)
        assertEquals(400f, event.heightDp)
        assertEquals(0, event.nodeId)
        assertEquals(0L, event.handlerId)

        // And back down is another single event, not a stream of them.
        for (widthDp in 600 downTo 300) {
            reporter.report(widthDp.toFloat(), 400f, dispatcher)
        }
        assertEquals(2, dispatcher.events.size)
        assertEquals(
            WindowSizeClass.Compact,
            (dispatcher.events[1] as HostEvent.WindowSizeChanged).sizeClass,
        )
    }

    @Test
    fun fr20_crossing_a_height_boundary_sends_one_event_and_the_two_axes_share_it() {
        val dispatcher = RecordingDispatcher()
        val reporter = WindowSizeReporter()

        // Growing from 300dp to 479dp tall stays in one class, so nothing is sent.
        for (heightDp in 300..479) {
            reporter.report(400f, heightDp.toFloat(), dispatcher)
        }
        assertEquals(0, dispatcher.events.size)

        assertTrue(reporter.report(400f, 480f, dispatcher))
        assertEquals(1, dispatcher.events.size)
        val taller = dispatcher.events[0] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Compact, taller.sizeClass)
        assertEquals(WindowHeightClass.Medium, taller.heightClass)

        // Both axes crossing at once is still one event, because one record holds both.
        assertTrue(reporter.report(900f, 900f, dispatcher))
        assertEquals(2, dispatcher.events.size)
        val both = dispatcher.events[1] as HostEvent.WindowSizeChanged
        assertEquals(WindowSizeClass.Expanded, both.sizeClass)
        assertEquals(WindowHeightClass.Expanded, both.heightClass)
    }

    /**
     * A node that was asked to report its size answers on both axes, in one event.
     *
     * The same record the window uses, so the same rule: a resize that stays inside both
     * classes says nothing, and a resize that leaves either one says it once. This is the
     * node path rather than the root path, and it had its own copy of the remembering,
     * which is how it came to remember only the width.
     */
    @Test
    fun fr28_an_observed_node_reports_both_classes_in_one_event() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
                // Filling the space, or the node measures to the width of the word and a
                // window that changed size would not change the node's size at all.
                Mutation.SetModifier(LABEL, 1, ProtocolModifier.FillMaxWidth),
                Mutation.SetModifier(LABEL, 2, ProtocolModifier.FillMaxHeight),
                Mutation.SetModifier(LABEL, 3, ProtocolModifier.ObserveSize(SIZE_TOKEN)),
            ),
        )
        var height by mutableStateOf(300.dp)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(400.dp, height),
                )
            }
        }
        waitForIdle()
        val sizes = {
            connection.events
                .filterIsInstance<HostEvent.WindowSizeChanged>()
                .filter { it.nodeId == LABEL }
        }
        val before = sizes().size

        // Taller, and across the first height boundary. The width has not moved, so an
        // event here can only have come from the height.
        height = 500.dp
        waitForIdle()
        assertEquals(before + 1, sizes().size)
        val taller = sizes().last()
        assertEquals(WindowSizeClass.Compact, taller.sizeClass)
        assertEquals(WindowHeightClass.Medium, taller.heightClass)

        // Taller again, inside the same class, so nothing is sent.
        height = 700.dp
        waitForIdle()
        assertEquals(before + 1, sizes().size)
    }

    @Test
    fun fr20_root_content_reports_its_measured_size_to_the_host() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("hello")),
            ),
        )
        var width by mutableStateOf(500.dp)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                // Pinned so the test asserts on dp, not on whatever density the machine
                // running it happens to have.
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(width, 400.dp),
                )
            }
        }
        waitForIdle()
        val sizes = { connection.events.filterIsInstance<HostEvent.WindowSizeChanged>() }
        // Compact in both axes is what the Host already assumes, so nothing was sent.
        assertEquals(0, sizes().size)

        // Still Compact: the window changed size and the Host is not told.
        width = 560.dp
        waitForIdle()
        assertEquals(0, sizes().size)

        width = 700.dp
        waitForIdle()
        assertEquals(1, sizes().size)
        assertEquals(WindowSizeClass.Medium, sizes()[0].sizeClass)
        assertEquals(WindowHeightClass.Compact, sizes()[0].heightClass)
        assertEquals(700f, sizes()[0].widthDp)

        width = 900.dp
        waitForIdle()
        assertEquals(2, sizes().size)
        assertEquals(WindowSizeClass.Expanded, sizes()[1].sizeClass)
    }
}
