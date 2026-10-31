package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Row whose children ask for `FillMaxHeight` sizes itself to its tallest child so short
 * cards and vertical dividers match their tallest sibling without stretching to the window.
 */
@OptIn(ExperimentalTestApi::class)
class RowIntrinsicSizeTest {

    @Test
    fun fr_61_row_children_with_fill_max_height_match_tallest_sibling() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            // Outer parent with plenty of height (300 dp) so an unbounded fillMaxHeight would
            // stretch to 300 dp if the Row did not use IntrinsicSize.Min.
            Mutation.Create(1, WidgetKind.Column),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(240f, 300f)),
            Mutation.Create(2, WidgetKind.Row),
            // Tall sibling: 80 dp high.
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(60f, 80f)),
            // Short sibling with FillMaxHeight: should match 80 dp, not 300 dp.
            Mutation.Create(4, WidgetKind.Box),
            Mutation.SetModifier(4, 0, ProtocolModifier.Width(60f)),
            Mutation.SetModifier(4, 1, ProtocolModifier.FillMaxHeight),
            // Vertical divider with FillMaxHeight: should also match 80 dp.
            Mutation.Create(5, WidgetKind.Divider),
            Mutation.SetProp(5, PropertyKind.Vertical, PropertyValue.Bool(true)),
            Mutation.SetModifier(5, 0, ProtocolModifier.FillMaxHeight),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(2, 5, 1),
            Mutation.Insert(2, 4, 2),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val tallHeight = onNodeWithTag(nodeTestTag(3)).fetchSemanticsNode().size.height
        val shortHeight = onNodeWithTag(nodeTestTag(4)).fetchSemanticsNode().size.height
        val dividerHeight = onNodeWithTag(nodeTestTag(5)).fetchSemanticsNode().size.height
        assertEquals(tallHeight, shortHeight, "short child with FillMaxHeight matches tallest sibling")
        assertEquals(tallHeight, dividerHeight, "vertical divider with FillMaxHeight matches tallest sibling")
    }
}
