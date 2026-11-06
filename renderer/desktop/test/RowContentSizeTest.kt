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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RowContentSizeTest {
    @Test
    fun fr70_unweighted_containers_wrap_their_content() = runComposeUiTest {
        val batch = buildList {
            add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
            add(Mutation.Create(1, WidgetKind.Column))
            listOf(WidgetKind.Box, WidgetKind.Row, WidgetKind.Column).forEachIndexed { index, kind ->
                val row = 10 + index * 10
                add(Mutation.Create(row, WidgetKind.Row))
                add(Mutation.SetModifier(row, 0, ProtocolModifier.Width(300f)))
                add(Mutation.Create(row + 1, WidgetKind.Column))
                add(Mutation.SetModifier(row + 1, 0, ProtocolModifier.Weight(1f)))
                add(Mutation.Create(row + 2, kind))
                add(Mutation.Create(row + 3, WidgetKind.Text))
                add(Mutation.SetProp(row + 3, PropertyKind.Text, PropertyValue.Text("Switch")))
                add(Mutation.Insert(row + 2, row + 3, 0))
                add(Mutation.Insert(row, row + 1, 0))
                add(Mutation.Insert(row, row + 2, 1))
                add(Mutation.Insert(1, row, index))
            }
        }
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        for (row in listOf(10, 20, 30)) {
            val container = onNodeWithTag(nodeTestTag(row + 2)).fetchSemanticsNode().size.width
            val text = onNodeWithTag(nodeTestTag(row + 3)).fetchSemanticsNode().size.width
            val width = onNodeWithTag(nodeTestTag(row)).fetchSemanticsNode().size.width
            assertEquals(width, onNodeWithTag(nodeTestTag(row + 1)).fetchSemanticsNode().size.width + container)
            assertEquals(text, container, "an unweighted container wraps its content")
            assertTrue(container < width)
        }
    }

    @Test
    fun fr70_weighted_text_keeps_room_for_the_longest_word() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Create(2, WidgetKind.Row),
            Mutation.SetModifier(2, 0, ProtocolModifier.Width(300f)),
            Mutation.Create(3, WidgetKind.Column),
            Mutation.SetModifier(3, 0, ProtocolModifier.Weight(1f)),
            Mutation.Create(4, WidgetKind.Text),
            Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("가나다라마바사 다른 문장")),
            Mutation.Create(5, WidgetKind.Box),
            Mutation.SetModifier(5, 0, ProtocolModifier.Width(290f)),
            Mutation.Create(6, WidgetKind.Text),
            Mutation.SetProp(6, PropertyKind.Text, PropertyValue.Text("가나다라마바사")),
            Mutation.Insert(3, 4, 0),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(2, 5, 1),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 6, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val columnWidth = onNodeWithTag(nodeTestTag(3)).fetchSemanticsNode().size.width
        val wordWidth = onNodeWithTag(nodeTestTag(6)).fetchSemanticsNode().size.width
        assertTrue(columnWidth >= wordWidth, "weighted text has room for its longest word")
    }
    @Test
    fun fr70_weighted_column_child_keeps_minimum_intrinsic_height() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Column),
            Mutation.SetModifier(1, 0, ProtocolModifier.Height(300f)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Weight(1f)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Height(80f)),
            Mutation.Create(4, WidgetKind.Box),
            Mutation.SetModifier(4, 0, ProtocolModifier.Height(290f)),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 4, 1),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val weighted = onNodeWithTag(nodeTestTag(2)).fetchSemanticsNode().size.height
        val content = onNodeWithTag(nodeTestTag(3)).fetchSemanticsNode().size.height
        assertEquals(content, weighted, "weighted child retains its intrinsic height")
    }

    @Test
    fun fr70_weighted_children_share_remaining_space() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Row),
            Mutation.SetModifier(1, 0, ProtocolModifier.Width(300f)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Weight(1f)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Weight(2f)),
            Mutation.Create(4, WidgetKind.Box),
            Mutation.SetModifier(4, 0, ProtocolModifier.Width(60f)),
            Mutation.Insert(1, 2, 0),
            Mutation.Insert(1, 3, 1),
            Mutation.Insert(1, 4, 2),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val first = onNodeWithTag(nodeTestTag(2)).fetchSemanticsNode().size.width
        val second = onNodeWithTag(nodeTestTag(3)).fetchSemanticsNode().size.width
        val fixed = onNodeWithTag(nodeTestTag(4)).fetchSemanticsNode().size.width
        val row = onNodeWithTag(nodeTestTag(1)).fetchSemanticsNode().size.width
        assertEquals(row - fixed, first + second)
        assertTrue(kotlin.math.abs(second - 2 * first) <= 2)
    }

}
