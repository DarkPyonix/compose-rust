package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.HorizontalAlignment
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.VerticalAlignment
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FlowRowTest {
    private val row = 1
    private val widths = listOf(64f, 120f, 48f, 96f, 150f, 40f, 88f, 72f)
    private val chipHeight = 32f

    private fun records(verticalSpacing: Float): List<Mutation> = buildList {
        add(Mutation.Create(row, WidgetKind.FlowRow))
        add(Mutation.SetModifier(row, 0, ProtocolModifier.Width(390f)))
        add(Mutation.SetProp(row, PropertyKind.HorizontalSpacing, PropertyValue.Float(8f)))
        add(Mutation.SetProp(row, PropertyKind.VerticalSpacing, PropertyValue.Float(verticalSpacing)))
        widths.forEachIndexed { index, width ->
            val chip = 10 + index
            add(Mutation.Create(chip, WidgetKind.Box))
            add(Mutation.SetModifier(chip, 0, ProtocolModifier.Size(width, chipHeight)))
            add(Mutation.Insert(row, chip, index))
        }
    }

    @Test
    fun fr76_flow_row_wraps_by_measured_width() = runComposeUiTest {
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(records(8f)))) }
        waitForIdle()

        val rowBounds = onNodeWithTag(nodeTestTag(row)).getUnclippedBoundsInRoot()
        assertEquals(390f, (rowBounds.right - rowBounds.left).value, 0.5f)
        val chips = widths.indices.map { onNodeWithTag(nodeTestTag(10 + it)).getUnclippedBoundsInRoot() }
        chips.forEachIndexed { index, chip ->
            assertTrue(
                chip.left >= rowBounds.left && chip.right.value <= rowBounds.right.value + 0.5f,
                "chip $index lies outside the row: $chip in $rowBounds",
            )
        }
        // 64+8+120+8+48+8+96 = 352 fits in 390, and adding 8+150 does not, so the fifth chip
        // starts the second line at the row's start.
        val tops = chips.map { it.top.value }.distinct().sorted()
        assertTrue(tops.size >= 2, "eight chips totalling ${widths.sum()}dp must wrap at 390dp: $tops")
        assertEquals(chips[0].top.value, chips[3].top.value, 0.5f)
        assertTrue(chips[4].top.value > chips[3].top.value, "the fifth chip starts a new line")
        assertEquals(rowBounds.left.value, chips[4].left.value, 0.5f)
        // Inside a line the gap is the horizontal spacing.
        assertEquals(8f, (chips[1].left - chips[0].right).value, 0.5f)
    }

    @Test
    fun fr76_flow_row_spacing() = runComposeUiTest {
        val spacing = 14f
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(records(spacing)))) }
        waitForIdle()

        val chips = widths.indices.map { onNodeWithTag(nodeTestTag(10 + it)).getUnclippedBoundsInRoot() }
        val lineTops = chips.map { it.top.value }.distinct().sorted()
        assertTrue(lineTops.size >= 2, "the chips wrap: $lineTops")
        for (line in 1 until lineTops.size) {
            val gap = lineTops[line] - (lineTops[line - 1] + chipHeight)
            assertTrue(abs(gap - spacing) < 0.5f, "the gap between lines is $gap, not $spacing")
        }
    }

    /** Alignment and the per-line limit reach the layout: three per line, centred, bottomed. */
    @Test
    fun fr76_flow_row_alignment_and_max_items() = runComposeUiTest {
        val records = records(8f) + listOf(
            Mutation.SetProp(row, PropertyKind.MaxItemsInEachRow, PropertyValue.Integer(3)),
            Mutation.SetProp(
                row,
                PropertyKind.HorizontalAlignment,
                PropertyValue.Integer((HorizontalAlignment.End.ordinal + 1).toLong()),
            ),
            Mutation.SetProp(
                row,
                PropertyKind.VerticalAlignment,
                PropertyValue.Integer((VerticalAlignment.Bottom.ordinal + 1).toLong()),
            ),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(records))) }
        waitForIdle()

        val rowBounds = onNodeWithTag(nodeTestTag(row)).getUnclippedBoundsInRoot()
        val chips = widths.indices.map { onNodeWithTag(nodeTestTag(10 + it)).getUnclippedBoundsInRoot() }
        assertEquals(chips[0].top.value, chips[2].top.value, 0.5f)
        assertTrue(chips[3].top.value > chips[2].top.value, "a line holds at most three")
        assertEquals(rowBounds.right.value, chips[2].right.value, 0.5f)
    }
}
