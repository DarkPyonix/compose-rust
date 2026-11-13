package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
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
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LIST = 1
private const val CONTENT = 2
private const val HANDLER = 7L

private fun scrollTo(
    node: Int,
    x: Double? = null,
    y: Double? = null,
    item: Int = 0,
    animate: Boolean = false,
): Mutation.ScrollTo {
    var flags = 0
    if (animate) flags = flags or ScrollWire.TO_ANIMATE
    if (x != null) flags = flags or ScrollWire.TO_X
    if (y != null) flags = flags or ScrollWire.TO_Y
    return Mutation.ScrollTo(node, item, flags, x ?: 0.0, y ?: 0.0)
}

private fun intoView(
    node: Int,
    kind: Int,
    target: Int = 0,
    alignX: Int = ScrollWire.ALIGN_KEEP,
    alignY: Int = ScrollWire.ALIGN_KEEP,
    x: Double = 0.0,
    y: Double = 0.0,
    width: Double = 0.0,
    height: Double = 0.0,
    flags: Int = 0,
) = Mutation.ScrollIntoView(node, target, kind, alignX, alignY, flags, x, y, width, height)

/**
 * A column that scrolls: a viewport 200 by 300 over content 200 by 1000, both in pixels at
 * the density the tests run at.
 */
private fun scrollColumn(handler: Boolean = true, vararg more: Mutation): List<Mutation> = buildList {
    add(Mutation.Create(LIST, WidgetKind.ScrollColumn))
    add(Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(200f, 300f)))
    if (handler) add(Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)))
    add(Mutation.Create(CONTENT, WidgetKind.Box))
    add(Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Size(200f, 1000f)))
    add(Mutation.Insert(LIST, CONTENT, 0))
    addAll(more)
}

private class Screen(val connection: FakeHostConnection, val host: ComposeRustHost) {
    val reports: List<HostEvent.ScrollChanged>
        get() = connection.events.filterIsInstance<HostEvent.ScrollChanged>()

    val errors: List<HostEvent.ProtocolError>
        get() = connection.events.filterIsInstance<HostEvent.ProtocolError>()
}

private val HostEvent.ScrollChanged.settled: Boolean get() = flags and ScrollWire.CHANGED_SETTLED != 0

/** Draws [batch] at one pixel to the dp, so the sizes above are pixels. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.screen(batch: List<Mutation>, density: () -> Float = { 1f }): Screen {
    val connection = FakeHostConnection(batch)
    lateinit var host: ComposeRustHost
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(density())) {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
    }
    waitForIdle()
    return Screen(connection, host)
}

/** Sends one more batch the way a frame does, and lets what it starts finish. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.send(screen: Screen, vararg mutations: Mutation, idle: Boolean = true) {
    runOnIdle {
        screen.connection.scheduleFrame(mutations.toList())
        screen.host.renderFrame(0L)
    }
    if (idle) waitForIdle()
}

/** A scroll container takes requests from the Host and reports where it is, in pixels. */
@OptIn(ExperimentalTestApi::class)
class ScrollWireTest {
    @Test
    fun fr80_scroll_to_clamps_to_the_range_and_reports_it() = runComposeUiTest {
        val screen = screen(scrollColumn())
        val first = screen.reports.single()
        assertEquals(0.0, first.yPx)
        assertEquals(700.0, first.maxYPx)
        assertEquals(300.0, first.viewportHeightPx)
        assertEquals(200.0, first.viewportWidthPx)
        assertEquals(0.0, first.maxXPx)
        assertEquals(
            ScrollWire.CHANGED_SETTLED or ScrollWire.CHANGED_CAN_SCROLL_FORWARD,
            first.flags,
            "at rest at the top, with further to go",
        )

        send(screen, scrollTo(LIST, y = 5000.0))
        val end = screen.reports.last()
        assertEquals(2, screen.reports.size)
        assertEquals(700.0, end.yPx)
        assertEquals(700.0, end.maxYPx)
        assertEquals(300.0, end.viewportHeightPx)
        assertEquals(ScrollWire.CHANGED_SETTLED or ScrollWire.CHANGED_CAN_SCROLL_BACKWARD, end.flags)
        assertEquals(HANDLER, end.handlerId)
        assertEquals(LIST, end.nodeId)

        send(screen, scrollTo(LIST, y = -10.0))
        assertEquals(0.0, screen.reports.last().yPx)
        assertEquals(3, screen.reports.size)
        assertTrue(screen.errors.isEmpty(), "out of range is not an error: ${screen.errors}")

        // Already there: nothing moves and nothing is said.
        send(screen, scrollTo(LIST, y = 0.0))
        assertEquals(3, screen.reports.size)
    }

    @Test
    fun fr80_scroll_to_moves_only_the_axes_it_names() = runComposeUiTest {
        val editor = 3
        val text = (0 until 200).joinToString("\n") { line -> "line $line " + "x".repeat(400) }
        val screen = screen(
            listOf(
                Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
                Mutation.Create(editor, WidgetKind.CodeEditor),
                Mutation.SetModifier(editor, 0, ProtocolModifier.Size(300f, 200f)),
                Mutation.SetProp(editor, PropertyKind.Text, PropertyValue.Text(text)),
                Mutation.SetProp(editor, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)),
            ),
        )
        val first = screen.reports.last()
        assertEquals(0.0, first.xPx)
        assertEquals(0.0, first.yPx)
        assertTrue(first.maxXPx > 100.0 && first.maxYPx > 100.0, "the editor scrolls both ways: $first")

        send(screen, scrollTo(editor, x = 60.0))
        assertEquals(60.0, screen.reports.last().xPx)
        assertEquals(0.0, screen.reports.last().yPx)

        // Only y is named. The x the record carries is not applied.
        send(screen, scrollTo(editor, y = 40.0))
        assertEquals(60.0, screen.reports.last().xPx, "x was not named and must not move")
        assertEquals(40.0, screen.reports.last().yPx)

        send(screen, scrollTo(editor, x = 0.0))
        assertEquals(0.0, screen.reports.last().xPx)
        assertEquals(40.0, screen.reports.last().yPx, "y was not named and must not move")
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }

    @Test
    fun fr80_scroll_to_in_the_creating_batch_applies_after_first_layout() = runComposeUiTest {
        val screen = screen(scrollColumn(true, scrollTo(LIST, y = 400.0)))
        assertEquals(400.0, screen.reports.first().yPx, "the first report is where the request put it")
        assertEquals(1, screen.reports.size)
        assertEquals((-400).toFloat(), onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value)
    }

    /** Past the end in the creating batch is kept inside the range the first layout found. */
    @Test
    fun fr80_scroll_to_in_the_creating_batch_is_kept_in_range() = runComposeUiTest {
        val far = screen(scrollColumn(true, scrollTo(LIST, y = 9000.0)))
        assertEquals(700.0, far.reports.first().yPx)
        assertEquals(1, far.reports.size)
    }

    @Test
    fun fr80_animated_scroll_reports_once_per_frame_then_settles() = runComposeUiTest {
        val screen = screen(scrollColumn())
        val before = screen.reports.size
        mainClock.autoAdvance = false
        send(screen, scrollTo(LIST, y = 500.0, animate = true), idle = false)
        var frames = 0
        while (frames < 400) {
            val seen = screen.reports.size
            mainClock.advanceTimeByFrame()
            frames++
            assertTrue(screen.reports.size - seen <= 1, "more than one report in frame $frames")
            if (screen.reports.lastOrNull()?.let { it.settled && screen.reports.size > before } == true) break
        }
        // A few more frames say nothing new.
        val done = screen.reports.size
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertEquals(done, screen.reports.size, "nothing is reported after the movement ended")

        val moved = screen.reports.drop(before)
        assertTrue(moved.size >= 3, "an animation is reported on its way: $moved")
        assertTrue(moved.dropLast(1).none { it.settled }, "only the last report is settled: $moved")
        assertTrue(moved.last().settled)
        assertEquals(500.0, moved.last().yPx)
        assertEquals(moved.map { it.yPx }.sorted(), moved.map { it.yPx }, "it only moves towards the target")
    }

    @Test
    fun fr80_scroll_into_view_aligns_start_center_end_and_nearest() = runComposeUiTest {
        val screen = screen(scrollColumn())
        fun rect(align: Int) =
            intoView(LIST, ScrollWire.TARGET_CONTENT, alignY = align, y = 600.0, width = 10.0, height = 40.0)

        send(screen, rect(ScrollWire.ALIGN_START))
        assertEquals(600.0, screen.reports.last().yPx)
        send(screen, rect(ScrollWire.ALIGN_CENTER))
        assertEquals(470.0, screen.reports.last().yPx)
        send(screen, rect(ScrollWire.ALIGN_END))
        assertEquals(340.0, screen.reports.last().yPx)

        send(screen, scrollTo(LIST, y = 0.0))
        send(screen, rect(ScrollWire.ALIGN_NEAREST))
        assertEquals(340.0, screen.reports.last().yPx, "the smallest move that shows it")

        // Now it is in view: nearest does not move it, and nothing is reported.
        val count = screen.reports.size
        send(screen, rect(ScrollWire.ALIGN_NEAREST))
        assertEquals(count, screen.reports.size)

        // From below, the smallest move brings its top to the top.
        send(screen, scrollTo(LIST, y = 700.0))
        send(screen, rect(ScrollWire.ALIGN_NEAREST))
        assertEquals(600.0, screen.reports.last().yPx)

        // An axis the request keeps does not move.
        val kept = screen.reports.size
        send(screen, intoView(LIST, ScrollWire.TARGET_CONTENT, alignY = ScrollWire.ALIGN_KEEP, y = 0.0, height = 40.0))
        assertEquals(kept, screen.reports.size)
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }

    @Test
    fun fr80_scroll_into_view_brings_a_descendant_node_into_view() = runComposeUiTest {
        val column = 3
        val above = 4
        val target = 5
        val below = 6
        val screen = screen(
            listOf(
                Mutation.Create(LIST, WidgetKind.ScrollColumn),
                Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(200f, 300f)),
                Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)),
                Mutation.Create(column, WidgetKind.Column),
                Mutation.Insert(LIST, column, 0),
                Mutation.Create(above, WidgetKind.Box),
                Mutation.SetModifier(above, 0, ProtocolModifier.Size(200f, 600f)),
                Mutation.Insert(column, above, 0),
                Mutation.Create(target, WidgetKind.Box),
                Mutation.SetModifier(target, 0, ProtocolModifier.Size(200f, 100f)),
                Mutation.Insert(column, target, 1),
                Mutation.Create(below, WidgetKind.Box),
                Mutation.SetModifier(below, 0, ProtocolModifier.Size(200f, 300f)),
                Mutation.Insert(column, below, 2),
            ),
        )
        assertEquals(700.0, screen.reports.last().maxYPx)

        // The whole node, which sits at 600 to 700 of the content.
        send(screen, intoView(LIST, ScrollWire.TARGET_NODE, target, alignY = ScrollWire.ALIGN_NEAREST))
        assertEquals(400.0, screen.reports.last().yPx, "its bottom edge comes to the bottom of the viewport")
        val bounds = onNodeWithTag(nodeTestTag(target)).getUnclippedBoundsInRoot()
        assertEquals(200f, bounds.top.value)
        assertEquals(300f, bounds.bottom.value)

        send(screen, intoView(LIST, ScrollWire.TARGET_NODE, target, alignY = ScrollWire.ALIGN_START))
        assertEquals(600.0, screen.reports.last().yPx)

        // A rectangle inside the node, measured from the node's top left.
        send(screen, scrollTo(LIST, y = 0.0))
        send(
            screen,
            intoView(LIST, ScrollWire.TARGET_NODE, target, alignY = ScrollWire.ALIGN_START, y = 50.0, width = 10.0, height = 10.0),
        )
        assertEquals(650.0, screen.reports.last().yPx)
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }

    @Test
    fun fr80_lazy_list_scroll_into_view_reaches_an_unseen_item() = runComposeUiTest {
        val screen = screen(
            listOf(
                Mutation.Create(LIST, WidgetKind.LazyColumn),
                Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(200f, 200f)),
                Mutation.SetProp(LIST, PropertyKind.ItemCount, PropertyValue.Integer(1000L)),
                Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)),
            ),
        )
        val first = screen.reports.last()
        assertEquals(0, first.item)
        assertEquals(-1.0, first.maxYPx, "a lazy list does not know how long it is")
        assertEquals(200.0, first.viewportHeightPx)

        send(screen, intoView(LIST, ScrollWire.TARGET_ITEM, 500, alignY = ScrollWire.ALIGN_START))
        val there = screen.reports.last()
        assertEquals(500, there.item)
        assertEquals(0.0, there.yPx)
        assertEquals(-1.0, there.maxYPx)
        assertTrue(there.settled)

        // An offset measured from an item, the way `scrollToItem` takes one.
        send(screen, scrollTo(LIST, y = 10.0, item = 20))
        assertEquals(20, screen.reports.last().item)
        assertEquals(10.0, screen.reports.last().yPx)

        // The end edge: the item's last pixel on the viewport's last pixel.
        send(screen, intoView(LIST, ScrollWire.TARGET_ITEM, 700, alignY = ScrollWire.ALIGN_END))
        val bottom = onNodeWithTag(nodeTestTag(LIST)).getUnclippedBoundsInRoot()
        val report = screen.reports.last()
        assertTrue(report.item in 690..700, "item 700 ends the viewport: $report")
        assertEquals(200f, bottom.bottom.value - bottom.top.value)
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }

    @Test
    fun fr80_only_nodes_with_a_handler_report() = runComposeUiTest {
        val screen = screen(scrollColumn(handler = false))
        send(screen, scrollTo(LIST, y = 300.0))
        // The request is honoured without a handler.
        assertEquals(-300f, onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value)
        assertTrue(screen.reports.isEmpty(), "nobody listens, so nothing is sent: ${screen.reports}")
        assertTrue(screen.connection.nodeEvents.isEmpty(), "${screen.connection.nodeEvents}")

        // Attaching a handler says where the container is, and removing it stops the reports.
        send(screen, Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)))
        assertEquals(listOf(300.0), screen.reports.map { it.yPx })
        send(screen, Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.None))
        send(screen, scrollTo(LIST, y = 100.0))
        assertEquals(-100f, onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value)
        assertEquals(1, screen.reports.size, "no report after the handler was removed")
    }

    @Test
    fun fr80_report_is_at_most_once_per_frame() = runComposeUiTest {
        val screen = screen(scrollColumn())
        val before = screen.reports.size
        mainClock.autoAdvance = false
        val list = onNodeWithTag(nodeTestTag(LIST))
        // Past the touch slop, so the container is being dragged.
        list.performTouchInput {
            down(Offset(100f, 250f))
            moveBy(Offset(0f, -60f), delayMillis = 0)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val dragging = screen.reports.size
        repeat(10) { frame ->
            val seen = screen.reports.size
            // Three moves between two frames.
            repeat(3) { list.performTouchInput { moveBy(Offset(0f, -5f), delayMillis = 0) } }
            mainClock.advanceTimeByFrame()
            // Where the three moves left the content, once the frame has laid it out.
            val offset = -onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value
            val sent = screen.reports.drop(seen)
            assertTrue(sent.size <= 1, "frame $frame sent ${sent.size} reports")
            sent.singleOrNull()?.let { report ->
                assertEquals(offset.roundToInt().toDouble(), report.yPx, "frame $frame reports its last value")
                assertTrue(!report.settled, "a drag in progress has not settled")
            }
        }
        val during = screen.reports.size - dragging
        assertTrue(during in 1..10, "ten frames of dragging sent $during reports")
        assertTrue(screen.reports.size > before)
        list.performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(screen.reports.last().settled, "letting go ends with a settled report")
    }

    @Test
    fun fr80_request_during_a_drag_is_ignored() = runComposeUiTest {
        val screen = screen(scrollColumn())
        mainClock.autoAdvance = false
        val list = onNodeWithTag(nodeTestTag(LIST))
        list.performTouchInput {
            down(Offset(100f, 250f))
            moveBy(Offset(0f, -60f), delayMillis = 0)
            moveBy(Offset(0f, -40f), delayMillis = 0)
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val dragged = -onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value
        assertTrue(dragged > 0f && dragged < 150f, "the drag moved the content by $dragged")

        send(screen, scrollTo(LIST, y = 600.0), idle = false)
        repeat(5) { mainClock.advanceTimeByFrame() }
        val now = -onNodeWithTag(nodeTestTag(CONTENT)).getUnclippedBoundsInRoot().top.value
        assertEquals(dragged, now, "the request must not move a container the user is dragging")
        assertEquals(dragged.roundToInt().toDouble(), screen.reports.last().yPx, "the report says where the drag put it")
        assertTrue(screen.reports.none { it.yPx == 600.0 })
        assertTrue(screen.errors.isEmpty(), "an ignored request is not an error: ${screen.errors}")

        list.performTouchInput { up() }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(screen.reports.none { it.yPx == 600.0 }, "the dropped request is not applied later")
    }

    @Test
    fun fr80_density_change_keeps_the_position_and_reports_new_pixels() = runComposeUiTest {
        var density by mutableFloatStateOf(2f)
        // 100 by 150 dp over 100 by 500 dp: 200 by 300 pixels over 1000 at a density of 2.
        val screen = screen(
            listOf(
                Mutation.Create(LIST, WidgetKind.ScrollColumn),
                Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(100f, 150f)),
                Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(HANDLER)),
                Mutation.Create(CONTENT, WidgetKind.Box),
                Mutation.SetModifier(CONTENT, 0, ProtocolModifier.Size(100f, 500f)),
                Mutation.Insert(LIST, CONTENT, 0),
                scrollTo(LIST, y = 400.0),
            ),
        ) { density }
        val before = screen.reports.last()
        assertEquals(400.0, before.yPx)
        assertEquals(700.0, before.maxYPx)
        assertEquals(300.0, before.viewportHeightPx)
        val count = screen.reports.size

        density = 1f
        waitForIdle()
        val after = screen.reports.drop(count)
        assertEquals(1, after.size, "one report for the new pixels: $after")
        assertEquals(200.0, after.single().yPx)
        assertEquals(350.0, after.single().maxYPx)
        assertEquals(150.0, after.single().viewportHeightPx)

        // And back up: the same place again, not the place the old range would allow.
        density = 2f
        waitForIdle()
        assertEquals(400.0, screen.reports.last().yPx)
        assertEquals(700.0, screen.reports.last().maxYPx)
    }

    @Test
    fun fr80_unknown_node_is_a_protocol_error() = runComposeUiTest {
        val screen = screen(scrollColumn())
        send(
            screen,
            scrollTo(99, y = 10.0),
            intoView(98, ScrollWire.TARGET_CONTENT, alignY = ScrollWire.ALIGN_START),
            // The rest of the batch still applies.
            scrollTo(LIST, y = 120.0),
        )
        assertEquals(listOf(TableError.UNKNOWN_NODE, TableError.UNKNOWN_NODE), screen.errors.map { it.code })
        assertTrue(screen.errors[0].message.contains("99"), screen.errors[0].message)
        assertEquals(120.0, screen.reports.last().yPx)
    }

    @Test
    fun fr80_non_scroll_node_is_a_protocol_error() = runComposeUiTest {
        val screen = screen(scrollColumn())
        send(
            screen,
            scrollTo(CONTENT, y = 10.0),
            intoView(CONTENT, ScrollWire.TARGET_CONTENT, alignY = ScrollWire.ALIGN_START),
            scrollTo(LIST, y = 120.0),
        )
        assertEquals(listOf(TableError.NOT_SCROLLABLE, TableError.NOT_SCROLLABLE), screen.errors.map { it.code })
        assertEquals(19, TableError.NOT_SCROLLABLE)
        assertEquals(120.0, screen.reports.last().yPx)

        // The handler belongs to the widgets that scroll.
        send(screen, Mutation.SetProp(CONTENT, PropertyKind.OnScrollChanged, PropertyValue.Integer(9L)))
        assertEquals(TableError.UNSUPPORTED_PROPERTY, screen.errors.last().code)
    }

    @Test
    fun fr80_invalid_scroll_values_are_protocol_errors() = runComposeUiTest {
        val lazy = 10
        val outside = 11
        val screen = screen(
            scrollColumn(
                true,
                Mutation.Create(lazy, WidgetKind.LazyColumn),
                Mutation.SetModifier(lazy, 0, ProtocolModifier.Size(200f, 100f)),
                Mutation.SetProp(lazy, PropertyKind.ItemCount, PropertyValue.Integer(50L)),
                Mutation.Create(outside, WidgetKind.Box),
            ),
        )
        val content = ScrollWire.TARGET_CONTENT
        val start = ScrollWire.ALIGN_START
        val bad = listOf(
            "an offset that is not a number" to scrollTo(LIST, y = Double.NaN),
            "an infinite offset" to scrollTo(LIST, x = Double.POSITIVE_INFINITY),
            "neither axis" to Mutation.ScrollTo(LIST, 0, ScrollWire.TO_ANIMATE, 0.0, 0.0),
            "an unknown flag bit" to Mutation.ScrollTo(LIST, 0, ScrollWire.TO_Y or 8, 0.0, 0.0),
            "an item outside a lazy list" to scrollTo(LIST, y = 0.0, item = 3),
            "a rectangle that is not a number" to intoView(LIST, content, alignY = start, y = Double.NaN),
            "an infinite rectangle" to intoView(LIST, content, alignY = start, width = Double.NEGATIVE_INFINITY),
            "a negative width" to intoView(LIST, content, alignY = start, width = -1.0),
            "a negative height" to intoView(LIST, content, alignY = start, height = -0.5),
            "an unknown alignment" to intoView(LIST, content, alignY = 5),
            "an unknown alignment on x" to intoView(LIST, content, alignX = 9, alignY = start),
            "an unknown target kind" to intoView(LIST, 3, alignY = start),
            "an unknown flag bit" to intoView(LIST, content, alignY = start, flags = 2),
            "an item outside a lazy list" to intoView(LIST, ScrollWire.TARGET_ITEM, 4, alignY = start),
            "a content rectangle in a lazy list" to intoView(lazy, content, alignY = start),
            "a node outside the container" to intoView(LIST, ScrollWire.TARGET_NODE, outside, alignY = start),
            "a node that does not exist" to intoView(LIST, ScrollWire.TARGET_NODE, 77, alignY = start),
            "the container as its own target" to intoView(LIST, ScrollWire.TARGET_NODE, LIST, alignY = start),
        )
        for ((what, mutation) in bad) {
            val seen = screen.errors.size
            val reports = screen.reports.size
            send(screen, mutation)
            val errors = screen.errors.drop(seen)
            assertEquals(listOf(TableError.INVALID_SCROLL), errors.map { it.code }, what)
            assertEquals(reports, screen.reports.size, "$what must not move the container")
        }
        assertEquals(20, TableError.INVALID_SCROLL)

        // One bad record costs that record: the ones around it in the batch apply, and the
        // renderer goes on.
        val seen = screen.errors.size
        send(screen, scrollTo(LIST, y = Double.NaN), scrollTo(LIST, y = 250.0))
        assertEquals(1, screen.errors.size - seen)
        assertEquals(250.0, screen.reports.last().yPx)
    }

    @Test
    fun fr80_stated_scroll_and_scroll_to_apply_in_batch_order() = runComposeUiTest {
        val screen = screen(scrollColumn())
        // The node's own move first, then the request: the request is where it ends.
        send(
            screen,
            Mutation.SetProp(LIST, PropertyKind.Value, PropertyValue.Float(100f)),
            Mutation.SetProp(LIST, PropertyKind.Count, PropertyValue.Integer(1L)),
            scrollTo(LIST, y = 300.0),
        )
        assertEquals(300.0, screen.reports.last().yPx)

        // The request first, then the node's own move: that move is where it ends.
        send(
            screen,
            scrollTo(LIST, y = 50.0),
            Mutation.SetProp(LIST, PropertyKind.Value, PropertyValue.Float(200f)),
            Mutation.SetProp(LIST, PropertyKind.Count, PropertyValue.Integer(2L)),
        )
        assertEquals(200.0, screen.reports.last().yPx)

        // Several requests in one batch end where the last one says.
        send(screen, scrollTo(LIST, y = 10.0), scrollTo(LIST, y = 20.0), scrollTo(LIST, y = 30.0))
        assertEquals(30.0, screen.reports.last().yPx)
    }

    @Test
    fun fr80_every_scroll_container_takes_a_request_and_reports() = runComposeUiTest {
        val row = 20
        val wide = 21
        val lazyRow = 22
        val grid = 23
        val screen = screen(
            listOf(
                Mutation.Create(row, WidgetKind.ScrollRow),
                Mutation.SetModifier(row, 0, ProtocolModifier.Size(300f, 50f)),
                Mutation.SetProp(row, PropertyKind.OnScrollChanged, PropertyValue.Integer(1L)),
                Mutation.Create(wide, WidgetKind.Box),
                Mutation.SetModifier(wide, 0, ProtocolModifier.Size(900f, 50f)),
                Mutation.Insert(row, wide, 0),
                Mutation.Create(lazyRow, WidgetKind.LazyRow),
                Mutation.SetModifier(lazyRow, 0, ProtocolModifier.Size(300f, 50f)),
                Mutation.SetProp(lazyRow, PropertyKind.ItemCount, PropertyValue.Integer(500L)),
                Mutation.SetProp(lazyRow, PropertyKind.OnScrollChanged, PropertyValue.Integer(2L)),
                Mutation.Create(grid, WidgetKind.LazyGrid),
                Mutation.SetModifier(grid, 0, ProtocolModifier.Size(300f, 100f)),
                Mutation.SetProp(grid, PropertyKind.ItemCount, PropertyValue.Integer(500L)),
                Mutation.SetProp(grid, PropertyKind.Columns, PropertyValue.Integer(2L)),
                Mutation.SetProp(grid, PropertyKind.OnScrollChanged, PropertyValue.Integer(3L)),
            ),
        )
        fun last(node: Int) = screen.reports.last { it.nodeId == node }

        assertEquals(600.0, last(row).maxXPx)
        assertEquals(50.0, last(row).viewportHeightPx)
        send(screen, scrollTo(row, x = 250.0, y = 40.0))
        assertEquals(250.0, last(row).xPx)
        assertEquals(0.0, last(row).yPx, "a row does not scroll down")
        send(screen, intoView(row, ScrollWire.TARGET_CONTENT, alignX = ScrollWire.ALIGN_END, x = 800.0, width = 100.0))
        assertEquals(600.0, last(row).xPx)

        assertEquals(-1.0, last(lazyRow).maxXPx)
        assertEquals(0.0, last(lazyRow).maxYPx)
        send(screen, intoView(lazyRow, ScrollWire.TARGET_ITEM, 300, alignX = ScrollWire.ALIGN_START))
        assertEquals(300, last(lazyRow).item)
        assertEquals(0.0, last(lazyRow).xPx)
        send(screen, scrollTo(lazyRow, x = 5.0, item = 40))
        assertEquals(40, last(lazyRow).item)
        assertEquals(5.0, last(lazyRow).xPx)

        assertEquals(-1.0, last(grid).maxYPx)
        send(screen, intoView(grid, ScrollWire.TARGET_ITEM, 200, alignY = ScrollWire.ALIGN_START))
        assertEquals(200, last(grid).item)
        send(screen, scrollTo(grid, y = 0.0, item = 0))
        assertEquals(0, last(grid).item)
        assertTrue(screen.errors.isEmpty(), "${screen.errors}")
    }
}
