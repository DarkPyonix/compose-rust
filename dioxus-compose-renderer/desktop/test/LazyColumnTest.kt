package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.foundation.requestedRange
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.foundation.LAZY_COLUMN_BUFFER

private const val ROW = 2
private const val LIST = 1
private const val DETAIL = 3
private const val RANGE_HANDLER = 91L
private const val ITEM_COUNT = 10_000

/** A list short enough that its items do not by themselves reach the bottom of the window. */
private const val SHORT_COUNT = 3

/**
 * Short enough that a list which walks its whole collection finishes walking rather than
 * running until the test times out, so the failure is a readable count and not a hang.
 */
private const val EMPTY_ITEM_COUNT = 200

/** First node id of the materialised window; two nodes per item (Box wrapper plus Text). */
private const val FIRST_ITEM_NODE = 100

/**
 * What the Host answers to a `RangeRequested`: exactly `count` items starting at `start`,
 * replacing whatever window was materialised before.
 */
private fun window(
    start: Int,
    count: Int,
    previousCount: Int,
    empty: Boolean = false,
): List<Mutation> = buildList {
    for (offset in 0 until previousCount) {
        add(Mutation.Remove(FIRST_ITEM_NODE + offset * 2))
    }
    for (offset in 0 until count) {
        val box = FIRST_ITEM_NODE + offset * 2
        val text = box + 1
        val index = start + offset
        add(Mutation.Create(box, WidgetKind.Box))
        add(Mutation.SetProp(box, PropertyKind.ItemKey, PropertyValue.Text("key-$index")))
        if (empty) {
            add(Mutation.Create(text, WidgetKind.Box))
        } else {
            add(Mutation.Create(text, WidgetKind.Text))
            add(Mutation.SetProp(text, PropertyKind.Text, PropertyValue.Text("message $index")))
        }
        add(Mutation.Insert(box, text, 0))
        add(Mutation.Insert(LIST, box, offset))
    }
}

/**
 * The same list with no size of its own, sitting inside a Row that fills the window. The
 * height has to come from the Row, which is the list and detail split's shape.
 */
private fun lazyListInRow() = listOf(
    Mutation.Create(ROW, WidgetKind.Row),
    Mutation.SetModifier(ROW, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(ROW, 1, ProtocolModifier.FillMaxHeight),
    Mutation.Create(LIST, WidgetKind.LazyColumn),
    Mutation.SetProp(LIST, PropertyKind.ItemCount, PropertyValue.Integer(SHORT_COUNT.toLong())),
    Mutation.SetProp(LIST, PropertyKind.OnRangeRequested, PropertyValue.Integer(RANGE_HANDLER)),
    Mutation.Insert(ROW, LIST, 0),
)

/**
 * The list and detail split: two panes sharing the Row's width by weight, the list pane
 * taking one share of it. A weight in a Row decides a pane's width and says nothing about
 * its height, which still has to come from the Row.
 */
private fun weightedLazyListInRow() = listOf(
    Mutation.Create(ROW, WidgetKind.Row),
    Mutation.SetModifier(ROW, 0, ProtocolModifier.FillMaxWidth),
    Mutation.SetModifier(ROW, 1, ProtocolModifier.FillMaxHeight),
    Mutation.Create(LIST, WidgetKind.LazyColumn),
    Mutation.SetModifier(LIST, 0, ProtocolModifier.Weight(1f)),
    Mutation.SetProp(LIST, PropertyKind.ItemCount, PropertyValue.Integer(SHORT_COUNT.toLong())),
    Mutation.SetProp(LIST, PropertyKind.OnRangeRequested, PropertyValue.Integer(RANGE_HANDLER)),
    Mutation.Insert(ROW, LIST, 0),
    Mutation.Create(DETAIL, WidgetKind.Box),
    Mutation.SetModifier(DETAIL, 0, ProtocolModifier.Weight(2f)),
    Mutation.Insert(ROW, DETAIL, 1),
)

private fun lazyList(itemCount: Int = ITEM_COUNT) = listOf(
    Mutation.Create(LIST, WidgetKind.LazyColumn),
    Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(200f, 200f)),
    Mutation.SetProp(LIST, PropertyKind.ItemCount, PropertyValue.Integer(itemCount.toLong())),
    Mutation.SetProp(LIST, PropertyKind.OnRangeRequested, PropertyValue.Integer(RANGE_HANDLER)),
)

@OptIn(ExperimentalTestApi::class)
class LazyColumnTest {
    /**
     * The Renderer asks for the visible range plus its own buffer, and the number of
     * nodes that exist is proportional to that window, not to the 10,000 item list.
     */
    @Test
    fun fr8_the_visible_range_is_requested_and_only_that_window_exists() = runComposeUiTest {
        val connection = FakeHostConnection(lazyList())
        var materialised = 0
        connection.respondWith { event ->
            if (event is HostEvent.RangeRequested) {
                val batch = window(event.start, event.count, materialised)
                materialised = event.count
                HostResponse(batch)
            } else {
                HostResponse()
            }
        }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        waitForIdle()

        val requests = connection.events.filterIsInstance<HostEvent.RangeRequested>()
        assertTrue(requests.isNotEmpty(), "the Renderer must ask for a range: ${connection.events}")
        val request = requests.last()
        assertEquals(LIST, request.nodeId)
        assertEquals(RANGE_HANDLER, request.handlerId)
        assertEquals(0, request.start, "the list starts at the top")
        assertTrue(
            request.count in 1 until ITEM_COUNT / 10,
            "a window of ${request.count} is not proportional to the visible range",
        )
        assertTrue(
            host.table.node(LIST)!!.children.size <= request.count,
            "the Host materialised more than the window it was asked for",
        )
    }

    /**
     * A list that is given no height of its own fills the height it is offered. A Row hands
     * its children the full height it has, and a short list has to take it rather than
     * shrink to the handful of items that happen to be materialised, or the list and detail
     * split has a list pane a few rows tall with the pane's background showing under it.
     */
    @Test
    fun fr8_a_list_with_no_height_of_its_own_fills_the_height_it_is_offered() = runComposeUiTest {
        val connection = FakeHostConnection(lazyListInRow())
        var materialised = 0
        connection.respondWith { event ->
            if (event is HostEvent.RangeRequested) {
                val batch = window(event.start, event.count, materialised)
                materialised = event.count
                HostResponse(batch)
            } else {
                HostResponse()
            }
        }
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        val offered = onNodeWithTag(nodeTestTag(ROW)).getUnclippedBoundsInRoot().height
        val taken = onNodeWithTag(nodeTestTag(LIST)).getUnclippedBoundsInRoot().height
        assertTrue(
            taken.value >= offered.value - 0.5f,
            "the list took $taken of the $offered it was offered; it shrank to its items",
        )
    }

    /**
     * The list pane of a list and detail split. Its weight is its share of the Row's width,
     * so nothing has yet said how tall it is, and it still has to fill the height the Row
     * offers rather than shrink to the handful of rows the Host has sent so far.
     */
    @Test
    fun fr8_a_weighted_list_in_a_row_fills_the_height_it_is_offered() = runComposeUiTest {
        val connection = FakeHostConnection(weightedLazyListInRow())
        var materialised = 0
        connection.respondWith { event ->
            if (event is HostEvent.RangeRequested) {
                val batch = window(event.start, event.count, materialised)
                materialised = event.count
                HostResponse(batch)
            } else {
                HostResponse()
            }
        }
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        val offered = onNodeWithTag(nodeTestTag(ROW)).getUnclippedBoundsInRoot().height
        val taken = onNodeWithTag(nodeTestTag(LIST)).getUnclippedBoundsInRoot().height
        assertTrue(
            taken.value >= offered.value - 0.5f,
            "the list took $taken of the $offered it was offered; its weight is a width",
        )
    }

    /**
     * An item that measures nothing is a gap in the list, not the end of it. A zero size
     * child used to leave the whole list blank even though the nodes existed and the range
     * had been asked for.
     */
    @Test
    fun fr8_an_item_that_measures_nothing_does_not_stop_the_list_drawing() = runComposeUiTest {
        val connection = FakeHostConnection(lazyList(EMPTY_ITEM_COUNT))
        var materialised = 0
        connection.respondWith { event ->
            if (event is HostEvent.RangeRequested) {
                val batch = window(event.start, event.count, materialised, empty = true)
                materialised = event.count
                HostResponse(batch)
            } else {
                HostResponse()
            }
        }
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        val requests = connection.events.filterIsInstance<HostEvent.RangeRequested>()
        assertTrue(requests.isNotEmpty(), "the Renderer must ask for a range: ${connection.events}")
        assertTrue(
            requests.last().count < EMPTY_ITEM_COUNT / 2,
            "empty items made the list ask for ${requests.last().count} of $EMPTY_ITEM_COUNT",
        )
        assertTrue(
            requests.size < 40,
            "the list asked ${requests.size} times over; it never settled on a window: " +
                requests.map { it.start to it.count },
        )
        onNodeWithTag(nodeTestTag(LIST)).assertIsDisplayed()
    }

    /** The first materialised child is the item at the requested `start`. */
    @Test
    fun fr8_the_window_is_drawn_at_the_requested_global_offset() = runComposeUiTest {
        val connection = FakeHostConnection(lazyList())
        var materialised = 0
        connection.respondWith { event ->
            if (event is HostEvent.RangeRequested) {
                val batch = window(event.start, event.count, materialised)
                materialised = event.count
                HostResponse(batch)
            } else {
                HostResponse()
            }
        }
        setContent { DioxusContent(rememberDioxusHost(connection)) }
        waitForIdle()

        val start = connection.events.filterIsInstance<HostEvent.RangeRequested>().last().start
        onNodeWithTag(nodeTestTag(FIRST_ITEM_NODE + 1)).assertTextEquals("message $start")
    }

    /** The requested window is the visible range widened by the Renderer's buffer. */
    @Test
    fun fr8_the_request_widens_the_visible_range_by_the_buffer() {
        assertEquals(
            0 to 20 + LAZY_COLUMN_BUFFER,
            requestedRange(firstVisible = 0, visibleCount = 20, itemCount = ITEM_COUNT),
        )
        assertEquals(
            100 - LAZY_COLUMN_BUFFER to 20 + 2 * LAZY_COLUMN_BUFFER,
            requestedRange(firstVisible = 100, visibleCount = 20, itemCount = ITEM_COUNT),
        )
        // Clamped at both ends, and an empty list asks for nothing.
        assertEquals(
            ITEM_COUNT - 20 - LAZY_COLUMN_BUFFER to 20 + LAZY_COLUMN_BUFFER,
            requestedRange(firstVisible = ITEM_COUNT - 20, visibleCount = 20, itemCount = ITEM_COUNT),
        )
        assertEquals(0 to 0, requestedRange(firstVisible = 0, visibleCount = 20, itemCount = 0))
        assertEquals(0 to 0, requestedRange(firstVisible = 0, visibleCount = 0, itemCount = 10))
    }
}
