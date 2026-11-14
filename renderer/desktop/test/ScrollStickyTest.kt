package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
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
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import dev.darkpyonix.composerust.protocol.Paint
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ScrollStickyTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr89_sticky_child_stays_pinned_during_scroll_row() =
        runComposeUiTest {
            val row = 1
            val childCount = 10
            val tileWidth = 100f
            val mutations = buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(row, WidgetKind.ScrollRow))
                add(Mutation.SetModifier(row, 0, ProtocolModifier.Size(200f, 60f)))
                for (i in 0 until childCount) {
                    val childId = 10 + i
                    val textId = 100 + i
                    add(Mutation.Create(childId, WidgetKind.Box))
                    add(Mutation.SetModifier(childId, 0, ProtocolModifier.Width(tileWidth)))
                    if (i == 0) {
                        add(Mutation.SetModifier(childId, 1, ProtocolModifier.Sticky))
                    }
                    add(Mutation.Create(textId, WidgetKind.Text))
                    add(Mutation.SetProp(textId, PropertyKind.Text, PropertyValue.Text("item $i")))
                    add(Mutation.Insert(childId, textId, 0))
                    add(Mutation.Insert(row, childId, i))
                }
            }

            val connection = FakeHostConnection(mutations)
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()

            val viewport = onNodeWithTag(nodeTestTag(row)).getUnclippedBoundsInRoot()
            val child0AtRest = onNodeWithTag(nodeTestTag(10)).getUnclippedBoundsInRoot()
            val child1AtRest = onNodeWithTag(nodeTestTag(11)).getUnclippedBoundsInRoot()

            assertTrue(
                abs(child0AtRest.left.value - viewport.left.value) < 1f,
                "child 0 starts at viewport left",
            )
            assertTrue(
                child1AtRest.left.value >= child0AtRest.right.value - 1f,
                "child 1 starts to the right of child 0",
            )

            onNodeWithTag(nodeTestTag(row))
                .performScrollToNode(hasTestTag(nodeTestTag(10 + childCount - 1)))
            waitForIdle()

            val child0Scrolled = onNodeWithTag(nodeTestTag(10)).getUnclippedBoundsInRoot()
            val child1Scrolled = onNodeWithTag(nodeTestTag(11)).getUnclippedBoundsInRoot()

            assertTrue(
                abs(child0Scrolled.left.value - viewport.left.value) < 1f,
                "child 0 stays pinned at viewport left ($child0Scrolled vs $viewport)",
            )
            assertTrue(
                child1Scrolled.left.value < child1AtRest.left.value,
                "child 1 scrolled left ($child1Scrolled vs $child1AtRest)",
            )
        }

    @Test
    fun fr89_sticky_child_stays_pinned_during_scroll_column() =
        runComposeUiTest {
            val col = 1
            val childCount = 10
            val tileHeight = 100f
            val mutations = buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(col, WidgetKind.ScrollColumn))
                add(Mutation.SetModifier(col, 0, ProtocolModifier.Size(200f, 200f)))
                for (i in 0 until childCount) {
                    val childId = 10 + i
                    val textId = 100 + i
                    add(Mutation.Create(childId, WidgetKind.Box))
                    add(Mutation.SetModifier(childId, 0, ProtocolModifier.Height(tileHeight)))
                    if (i == 0) {
                        add(Mutation.SetModifier(childId, 1, ProtocolModifier.Sticky))
                    }
                    add(Mutation.Create(textId, WidgetKind.Text))
                    add(Mutation.SetProp(textId, PropertyKind.Text, PropertyValue.Text("item $i")))
                    add(Mutation.Insert(childId, textId, 0))
                    add(Mutation.Insert(col, childId, i))
                }
            }

            val connection = FakeHostConnection(mutations)
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()

            val viewport = onNodeWithTag(nodeTestTag(col)).getUnclippedBoundsInRoot()
            val child0AtRest = onNodeWithTag(nodeTestTag(10)).getUnclippedBoundsInRoot()
            val child1AtRest = onNodeWithTag(nodeTestTag(11)).getUnclippedBoundsInRoot()

            assertTrue(
                abs(child0AtRest.top.value - viewport.top.value) < 1f,
                "child 0 starts at viewport top",
            )
            assertTrue(
                child1AtRest.top.value >= child0AtRest.bottom.value - 1f,
                "child 1 starts below child 0",
            )

            onNodeWithTag(nodeTestTag(col))
                .performScrollToNode(hasTestTag(nodeTestTag(10 + childCount - 1)))
            waitForIdle()

            val child0Scrolled = onNodeWithTag(nodeTestTag(10)).getUnclippedBoundsInRoot()
            val child1Scrolled = onNodeWithTag(nodeTestTag(11)).getUnclippedBoundsInRoot()

            assertTrue(
                abs(child0Scrolled.top.value - viewport.top.value) < 1f,
                "child 0 stays pinned at viewport top ($child0Scrolled vs $viewport)",
            )
            assertTrue(
                child1Scrolled.top.value < child1AtRest.top.value,
                "child 1 scrolled up ($child1Scrolled vs $child1AtRest)",
            )
        }

    /**
     * Ember's diff view: each line is a row inside the scrolling container, and the cell at the
     * start of each line is sticky and has a background of its own. Scrolled to the end, the
     * cell of every line is at the viewport's left edge, and what is drawn there is the cell's
     * own background, not the text that scrolled under it: the background moves with the cell.
     */
    @Test
    fun fr89_sticky_cell_in_a_nested_line_keeps_its_background() =
        runComposeUiTest {
            val scroller = 1
            val lines = 2
            val red = 0xFFFF0000.toInt()
            val mutations = buildList {
                add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
                add(Mutation.Create(scroller, WidgetKind.ScrollRow))
                add(Mutation.SetModifier(scroller, 0, ProtocolModifier.Size(200f, 80f)))
                add(Mutation.Create(2, WidgetKind.Column))
                for (line in 0 until lines) {
                    val row = 10 + line * 10
                    add(Mutation.Create(row, WidgetKind.Row))
                    add(Mutation.Create(row + 1, WidgetKind.Box))
                    add(Mutation.SetModifier(row + 1, 0, ProtocolModifier.Size(40f, 30f)))
                    add(Mutation.SetModifier(row + 1, 1, ProtocolModifier.Background(Paint.Literal(red))))
                    add(Mutation.SetModifier(row + 1, 2, ProtocolModifier.Sticky))
                    add(Mutation.Create(row + 2, WidgetKind.Box))
                    add(Mutation.SetModifier(row + 2, 0, ProtocolModifier.Size(600f, 30f)))
                    add(Mutation.Insert(row, row + 1, 0))
                    add(Mutation.Insert(row, row + 2, 1))
                    add(Mutation.Insert(2, row, line))
                }
                add(Mutation.Insert(scroller, 2, 0))
            }
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations)))
                }
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(scroller)).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
            onNodeWithTag(nodeTestTag(scroller)).performTouchInput { swipeLeft(startX = right - 1f, endX = left + 1f) }
            waitForIdle()

            val viewport = onNodeWithTag(nodeTestTag(scroller)).getUnclippedBoundsInRoot()
            val tail = onNodeWithTag(nodeTestTag(12)).getUnclippedBoundsInRoot()
            assertTrue(tail.left.value < viewport.left.value - 100f, "the line scrolled: $tail")
            val pixels = onRoot().captureToImage().toPixelMap()
            for (line in 0 until lines) {
                val cell = onNodeWithTag(nodeTestTag(11 + line * 10)).getUnclippedBoundsInRoot()
                assertTrue(abs(cell.left.value - viewport.left.value) < 1f, "line $line: the cell is pinned ($cell vs $viewport)")
                val colour = pixels[(cell.left.value + 5f).toInt(), ((cell.top.value + cell.bottom.value) / 2f).toInt()]
                assertTrue(
                    colour.red > 0.9f && colour.green < 0.1f && colour.blue < 0.1f,
                    "line $line: the pinned cell draws its own background: $colour",
                )
            }
        }
}
