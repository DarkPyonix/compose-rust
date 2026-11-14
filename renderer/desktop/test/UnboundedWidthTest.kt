package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
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
private const val PAGE_ARGB = 0xFF102030.toInt()
private const val CONTENT_ARGB = 0xFF20A040.toInt()

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

    /**
     * The row every case below lays out: 90dp wide, a weighted cell holding 120dp of content,
     * and a 40dp sibling after it. The sibling is measured first, so the cell's slot is the
     * 50dp that is left, and that width comes from the row and from nothing the cell says.
     * The indices are the slots the Host's writer gives each modifier (the weight 0, the
     * width 3, where the fixed sizes stand here, the background 14, the size report 23, the
     * unbounded width 37), so the size report comes ahead of the modifier in the list, as it
     * does on a real node.
     */
    private fun weightedCellRow(unbounded: Boolean): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
        add(Mutation.Create(PARENT, WidgetKind.Row))
        add(Mutation.SetModifier(PARENT, 3, ProtocolModifier.Size(90f, 30f)))
        add(Mutation.SetModifier(PARENT, 14, ProtocolModifier.Background(Paint.Literal(PAGE_ARGB))))
        add(Mutation.Create(CELL, WidgetKind.Box))
        add(Mutation.SetModifier(CELL, 0, ProtocolModifier.Weight(1f)))
        add(Mutation.SetModifier(CELL, 23, ProtocolModifier.ObserveSize(TOKEN, true)))
        if (unbounded) add(Mutation.SetModifier(CELL, 37, ProtocolModifier.UnboundedWidth))
        add(Mutation.Create(CONTENT, WidgetKind.Box))
        add(Mutation.SetModifier(CONTENT, 3, ProtocolModifier.Size(120f, 30f)))
        add(Mutation.SetModifier(CONTENT, 14, ProtocolModifier.Background(Paint.Literal(CONTENT_ARGB))))
        add(Mutation.Create(SIBLING, WidgetKind.Box))
        add(Mutation.SetModifier(SIBLING, 3, ProtocolModifier.Size(40f, 30f)))
        add(Mutation.Insert(CELL, CONTENT, 0))
        add(Mutation.Insert(PARENT, CELL, 0))
        add(Mutation.Insert(PARENT, SIBLING, 1))
    }

    private fun lastCellWidth(connection: FakeHostConnection): Float? =
        connection.events
            .filterIsInstance<HostEvent.WindowSizeChanged>()
            .lastOrNull { it.nodeId == CELL }
            ?.widthDp

    @Test
    fun fr87_unbounded_width_layout_clamped_to_parent() = runComposeUiTest {
        val connection = FakeHostConnection(weightedCellRow(unbounded = true))

        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        val row = onNodeWithTag(nodeTestTag(PARENT)).fetchSemanticsNode().boundsInRoot
        val cell = onNodeWithTag(nodeTestTag(CELL)).fetchSemanticsNode().boundsInRoot
        val sibling = onNodeWithTag(nodeTestTag(SIBLING)).fetchSemanticsNode().boundsInRoot
        assertEquals(90f, row.width, "the row keeps the width it was given")
        // A cell that handed its 120dp on to the row would push the sibling to 120.
        assertEquals(50f, sibling.left - row.left, "the sibling starts where the cell's 50dp slot ends")
        assertEquals(40f, sibling.width, "the sibling keeps its own width")
        assertEquals(0f, cell.left - row.left, "the cell starts at the row's left edge")
        assertEquals(50f, cell.width, "the cell takes the 50dp the row left for it and no more")
        // And the size it reports is still the one measured without a maximum.
        assertEquals(120f, lastCellWidth(connection), "the cell reports its content's own width")
    }

    /**
     * The same row without the modifier, so the case above is known to be about the modifier:
     * here the content is squeezed into the slot and 50 is what the cell reports.
     */
    @Test
    fun fr87_without_unbounded_width_the_cell_reports_its_slot() = runComposeUiTest {
        val connection = FakeHostConnection(weightedCellRow(unbounded = false))

        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        assertEquals(50f, lastCellWidth(connection), "a cell measured inside its slot reports the slot")
    }

    @Test
    fun fr87_unbounded_width_content_is_clipped_to_the_slot() = runComposeUiTest {
        val connection = FakeHostConnection(weightedCellRow(unbounded = true))

        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()

        // The row, with the cell over its first 50 pixels. The sibling has no background, so
        // past the slot the row's own colour shows unless the 120dp content was drawn there.
        val pixels = onNodeWithTag(nodeTestTag(PARENT)).captureToImage().toPixelMap()
        assertEquals(90, pixels.width, "the row is 90 pixels wide at a density of one")
        for (x in listOf(1, 25, 48)) {
            assertEquals(Color(CONTENT_ARGB), pixels[x, 15], "($x, 15) is inside the cell's slot")
        }
        for (x in listOf(51, 70, 88)) {
            assertEquals(Color(PAGE_ARGB), pixels[x, 15], "($x, 15) is past the slot's right edge, where the content must not be drawn")
        }
    }
}
