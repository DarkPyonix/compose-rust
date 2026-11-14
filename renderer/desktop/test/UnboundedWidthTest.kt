package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PARENT = 1
private const val CELL = 2
private const val CONTENT = 3
private const val SIBLING = 4
private const val TOKEN = 77

@OptIn(ExperimentalTestApi::class)
class UnboundedWidthTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr87_unbounded_width_reports_natural_size() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(PARENT, WidgetKind.Box),
                Mutation.SetModifier(PARENT, 0, ProtocolModifier.Width(50f)),
                Mutation.Create(CELL, WidgetKind.Box),
                Mutation.SetModifier(CELL, 0, ProtocolModifier.UnboundedWidth),
                Mutation.SetModifier(CELL, 1, ProtocolModifier.ObserveSize(TOKEN, true)),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Size(120f, 30f)),
                Mutation.Insert(CELL, CONTENT, 0),
                Mutation.Insert(PARENT, CELL, 0),
            ),
        )

        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        val sizes = connection.events
            .filterIsInstance<HostEvent.WindowSizeChanged>()
            .filter { it.nodeId == CELL }

        assertTrue(sizes.isNotEmpty(), "expected size change events for CELL")
        val last = sizes.last()
        assertEquals(120f, last.widthDp, "CELL should report its unconstrained content width (120dp)")
    }

    @Test
    fun fr87_unbounded_width_layout_clamped_to_parent() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(PARENT, WidgetKind.Row),
                Mutation.Create(CELL, WidgetKind.Box),
                Mutation.SetModifier(CELL, 0, ProtocolModifier.Width(50f)),
                Mutation.SetModifier(CELL, 1, ProtocolModifier.UnboundedWidth),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Size(120f, 30f)),
                Mutation.Create(SIBLING, WidgetKind.Box),
                Mutation.SetModifier(SIBLING, 0, ProtocolModifier.Size(40f, 30f)),
                Mutation.Insert(CELL, CONTENT, 0),
                Mutation.Insert(PARENT, CELL, 0),
                Mutation.Insert(PARENT, SIBLING, 1),
            ),
        )

        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        val parentSize = onNodeWithTag(nodeTestTag(PARENT)).fetchSemanticsNode().size
        assertEquals(90, parentSize.width, "PARENT row width should be 50dp + 40dp = 90dp")
    }
}
