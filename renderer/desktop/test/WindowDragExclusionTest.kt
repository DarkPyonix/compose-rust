package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalWindowDrag
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals

private const val REGION = 1
private const val CHILD = 2
private const val LABEL = 3

/** A `WindowDrag(false)` node inside a `WindowDrag(true)` region keeps the window still. */
@OptIn(ExperimentalTestApi::class)
class WindowDragExclusionTest {
    private val frames = FrameRequestSource()

    private fun mutations(childClickable: Boolean) = buildList {
        add(Mutation.Create(REGION, WidgetKind.Box))
        add(Mutation.SetModifier(REGION, 0, ProtocolModifier.WindowDrag(true)))
        add(Mutation.SetModifier(REGION, 1, ProtocolModifier.Size(150f, 150f)))
        add(Mutation.Create(CHILD, WidgetKind.Box))
        add(Mutation.SetModifier(CHILD, 0, ProtocolModifier.WindowDrag(false)))
        if (childClickable) add(Mutation.SetProp(CHILD, PropertyKind.OnClick, PropertyValue.Integer(1L)))
        add(Mutation.Create(LABEL, WidgetKind.Text))
        add(Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("no drag")))
        add(Mutation.Insert(CHILD, LABEL, 0))
        add(Mutation.Insert(REGION, CHILD, 0))
    }

    @Test
    fun fr19_no_drag_child_does_not_start_the_window_drag() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(mutations(childClickable = true))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp))
            }
        }
        onNodeWithText("no drag", useUnmergedTree = true).performMouseInput {
            moveTo(center); press(); moveBy(Offset(30f, 0f)); release()
        }
        assertEquals(0, drags)
        onNodeWithText("no drag", useUnmergedTree = true).performMouseInput { moveTo(center); press(); release() }
        assertEquals(0, drags)
        assertEquals(1, connection.events.filterIsInstance<HostEvent.Clicked>().size)
    }

    /** A control case: it also passes without the fix. The regression test is the one above. */
    @Test
    fun fr19_control_press_on_the_empty_drag_region_still_starts_the_window_drag() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(mutations(childClickable = false))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp))
            }
        }
        // The child is as small as its label, so the far corner of the 150 by 150 region is empty.
        onRoot().performMouseInput { moveTo(Offset(140f, 140f)); press(); release() }
        assertEquals(1, drags)
    }

    @Test
    fun fr19_a_press_that_starts_in_the_region_keeps_dragging_over_a_non_drag_child() = runComposeUiTest {
        var drags = 0
        val connection = FakeHostConnection(mutations(childClickable = false))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalWindowDrag provides { drags++ }) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(200.dp, 200.dp))
            }
        }
        // The press starts on the empty part of the region, then moves over the child. The child
        // never saw the down, so nothing is marked and the drag goes on.
        onRoot().performMouseInput {
            moveTo(Offset(140f, 140f)); press(); moveTo(Offset(10f, 10f)); release()
        }
        assertEquals(1, drags)
    }
}
