package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Snap
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val STRIP = 1
private const val FIRST_CHILD = 10

/** The width of the strip and of each of its children, in pixels at one pixel to the dp. */
private const val PAGE = 390f
private const val CHILDREN = 3

private fun child(index: Int) = FIRST_CHILD + index

/**
 * A strip one child wide holding three children of its own width, so every child start is
 * a page of the strip. [snap] is the `Snap` tag, or null to send none.
 */
private fun strip(widget: WidgetKind, snap: Snap?): List<Mutation> = buildList {
    val vertical = widget == WidgetKind.ScrollColumn
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)))
    add(Mutation.Create(STRIP, widget))
    add(Mutation.SetModifier(STRIP, 0, if (vertical) ProtocolModifier.Size(100f, PAGE) else ProtocolModifier.Size(PAGE, 100f)))
    if (snap != null) {
        add(Mutation.SetProp(STRIP, PropertyKind.Snap, PropertyValue.Integer((snap.ordinal + 1).toLong())))
    }
    for (index in 0 until CHILDREN) {
        add(Mutation.Create(child(index), WidgetKind.Box))
        add(
            Mutation.SetModifier(
                child(index),
                0,
                if (vertical) ProtocolModifier.Size(100f, PAGE) else ProtocolModifier.Size(PAGE, 100f),
            ),
        )
        add(Mutation.Insert(STRIP, child(index), index))
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.show(batch: List<Mutation>): FakeHostConnection {
    val connection = FakeHostConnection(batch)
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            ComposeRustContent(rememberComposeRustHost(connection))
        }
    }
    waitForIdle()
    return connection
}

/**
 * Drags the strip toward its end by [fraction] of a page and lets go once the finger has
 * stood still, so the release carries no fling velocity and only the drag decides.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.dragAndRelease(fraction: Float, vertical: Boolean) {
    val distance = PAGE * fraction
    val steps = 10
    onNodeWithTag(nodeTestTag(STRIP)).performTouchInput {
        val start = if (vertical) Offset(50f, PAGE - 20f) else Offset(PAGE - 20f, 50f)
        down(start)
        repeat(steps) {
            val step = -distance / steps
            moveBy(if (vertical) Offset(0f, step) else Offset(step, 0f), delayMillis = 16)
        }
        // Standing still for longer than the velocity tracker looks back.
        advanceEventTime(300)
        up()
    }
    waitForIdle()
}

/** Where [index]'s start edge sits relative to the start of the strip, in pixels. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.startOf(index: Int, vertical: Boolean): Float {
    val strip = onNodeWithTag(nodeTestTag(STRIP)).getUnclippedBoundsInRoot()
    val item = onNodeWithTag(nodeTestTag(child(index))).getUnclippedBoundsInRoot()
    return if (vertical) (item.top - strip.top).value else (item.left - strip.left).value
}

/** A scroll container with `Snap.ItemStart` comes to rest on a child's start edge. */
@OptIn(ExperimentalTestApi::class)
class ScrollSnapTest {
    @Test
    fun fr62_scroll_row_snaps_to_item_start() {
        // Past half of the first child: the strip goes on to the second child's start.
        runComposeUiTest {
            show(strip(WidgetKind.ScrollRow, Snap.ItemStart))
            dragAndRelease(0.7f, vertical = false)
            val second = startOf(1, vertical = false)
            assertTrue(abs(second) < 1f, "a drag past half of the first child stopped with the second at $second")
        }
        // Less than half: the strip comes back to the first child.
        runComposeUiTest {
            show(strip(WidgetKind.ScrollRow, Snap.ItemStart))
            dragAndRelease(0.3f, vertical = false)
            val first = startOf(0, vertical = false)
            assertTrue(abs(first) < 1f, "a drag less than half of the first child stopped with the first at $first")
        }
    }

    @Test
    fun fr62_scroll_column_snaps_to_item_start() {
        runComposeUiTest {
            show(strip(WidgetKind.ScrollColumn, Snap.ItemStart))
            dragAndRelease(0.7f, vertical = true)
            val second = startOf(1, vertical = true)
            assertTrue(abs(second) < 1f, "a column dragged past half stopped with the second child at $second")
        }
        runComposeUiTest {
            show(strip(WidgetKind.ScrollColumn, Snap.ItemStart))
            dragAndRelease(0.3f, vertical = true)
            val first = startOf(0, vertical = true)
            assertTrue(abs(first) < 1f, "a column dragged less than half stopped with the first child at $first")
        }
    }

    /**
     * Without the property the strip scrolls as it always has: it stops where the drag left
     * it, between two children.
     */
    @Test
    fun fr62_snap_none_leaves_the_scroll_where_the_drag_left_it() = runComposeUiTest {
        val connection = show(strip(WidgetKind.ScrollRow, snap = null))
        dragAndRelease(0.3f, vertical = false)
        val first = startOf(0, vertical = false)
        assertTrue(
            first < -PAGE * 0.15f && first > -PAGE * 0.5f,
            "a free strip dragged by a third of a page stopped with its first child at $first",
        )
        assertEquals(emptyList(), connection.errors())
    }

    /** The lazy containers scroll by item through their own state, so they refuse it. */
    @Test
    fun fr62_snap_belongs_to_the_plain_scroll_containers() {
        assertTrue(NodeTable.supportsProperty(WidgetKind.ScrollRow, PropertyKind.Snap))
        assertTrue(NodeTable.supportsProperty(WidgetKind.ScrollColumn, PropertyKind.Snap))
        assertFalse(NodeTable.supportsProperty(WidgetKind.LazyRow, PropertyKind.Snap))
        assertFalse(NodeTable.supportsProperty(WidgetKind.Row, PropertyKind.Snap))
    }
}

private fun FakeHostConnection.errors(): List<Any> =
    events.filterIsInstance<dev.darkpyonix.composerust.protocol.HostEvent.ProtocolError>()
