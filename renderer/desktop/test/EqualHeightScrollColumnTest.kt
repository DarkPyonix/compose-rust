package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A weighted Row with equal_height(true) measures a child holding a fixed-height ScrollColumn
 * at that definite height instead of 0.
 */
@OptIn(ExperimentalTestApi::class)
class EqualHeightScrollColumnTest {
    @Test
    fun equal_height_row_uses_fixed_height_scroll_column() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(400f, 500f)),
            Mutation.Create(2, WidgetKind.Row),
            Mutation.SetProp(2, PropertyKind.Collapsible, PropertyValue.Bool(true)),
            Mutation.SetModifier(2, 0, ProtocolModifier.FillMaxWidth),
            // Left child: 50dp wide, fill_max_height, 100dp spacer inside.
            Mutation.Create(3, WidgetKind.Column),
            Mutation.SetModifier(3, 0, ProtocolModifier.Width(50f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.FillMaxHeight),
            Mutation.Create(4, WidgetKind.Spacer),
            Mutation.SetModifier(4, 0, ProtocolModifier.Height(100f)),
            Mutation.Insert(3, 4, 0),
            // Right child: weight(1) Box holding a 320dp ScrollColumn.
            Mutation.Create(5, WidgetKind.Box),
            Mutation.SetModifier(5, 0, ProtocolModifier.Weight(1f)),
            Mutation.Create(6, WidgetKind.ScrollColumn),
            Mutation.SetModifier(6, 0, ProtocolModifier.Height(320f)),
            Mutation.Create(7, WidgetKind.Spacer),
            Mutation.SetModifier(7, 0, ProtocolModifier.Height(600f)),
            Mutation.Insert(6, 7, 0),
            Mutation.Insert(5, 6, 0),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(2, 5, 1),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()

        val rowBounds = onNodeWithTag(nodeTestTag(2)).getBoundsInRoot()
        val leftBounds = onNodeWithTag(nodeTestTag(3)).getBoundsInRoot()
        assertEquals(320.dp, rowBounds.bottom - rowBounds.top)
        assertEquals(320.dp, leftBounds.bottom - leftBounds.top)
    }
}
