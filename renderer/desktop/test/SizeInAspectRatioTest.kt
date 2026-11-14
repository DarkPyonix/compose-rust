package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SizeInAspectRatioTest {

    @Test
    fun fr84_size_in_and_aspect_ratio_applied() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Column),

            // Node 2: sizeIn with min 60x60, max 120x120. Child 3 has size 20x20. Node 2 expands to at least 60x60.
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.SizeIn(60f, 120f, 60f, 120f)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(20f, 20f)),
            Mutation.Insert(2, 3, 0),

            // Node 4: sizeIn with min 60x60, max 120x120. Child 5 has size 200x200. Node 4 is clamped to 120x120.
            Mutation.Create(4, WidgetKind.Box),
            Mutation.SetModifier(4, 0, ProtocolModifier.SizeIn(60f, 120f, 60f, 120f)),
            Mutation.Create(5, WidgetKind.Box),
            Mutation.SetModifier(5, 0, ProtocolModifier.Size(200f, 200f)),
            Mutation.Insert(4, 5, 0),

            // Node 6: fixed width 120dp, aspect ratio 2.0 (width/height = 2.0). Height becomes 60dp.
            Mutation.Create(6, WidgetKind.Box),
            Mutation.SetModifier(6, 0, ProtocolModifier.Width(120f)),
            Mutation.SetModifier(6, 1, ProtocolModifier.AspectRatio(2f, false)),

            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 4, 1),
            Mutation.Insert(1, 6, 2),
        )

        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val node2 = onNodeWithTag(nodeTestTag(2)).fetchSemanticsNode().size
        val node4 = onNodeWithTag(nodeTestTag(4)).fetchSemanticsNode().size
        val node6 = onNodeWithTag(nodeTestTag(6)).fetchSemanticsNode().size

        assertEquals(node4.width, 2 * node2.width, "node 4 (120dp) should be twice node 2 (60dp)")
        assertEquals(node4.height, 2 * node2.height, "node 4 (120dp) should be twice node 2 (60dp)")
        assertEquals(node4.width, node6.width, "node 4 and node 6 both have 120dp width")
        assertEquals(node6.width, 2 * node6.height, "node 6 with ratio 2f should have width twice height")
    }
}
