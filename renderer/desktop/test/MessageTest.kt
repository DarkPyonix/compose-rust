package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.foundation.MESSAGE_ACTION_TEST_TAG
import dev.darkpyonix.composerust.foundation.MESSAGE_CONTAINER_TEST_TAG
import dev.darkpyonix.composerust.foundation.MESSAGE_TEST_TAG
import dev.darkpyonix.composerust.foundation.MessageQueue
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MessageDuration
import dev.darkpyonix.composerust.protocol.MessageHorizontal
import dev.darkpyonix.composerust.protocol.MessageVertical
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ROOT = 1
private const val UNDO_HANDLER = 4242L

/** Material's short duration, which is what these tests wait out. */
private const val SHORT_MILLIS = 4_000L

/** The window the placement tests draw in, in dp. */
private const val WIDTH = 400f
private const val HEIGHT = 800f

/** Material's distance from the window's edges for a message, its `Md` space. */
private const val MATERIAL_INSET = 16f

private const val TOLERANCE = 0.5f

private fun themed(vararg records: Mutation) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(ROOT, WidgetKind.Text),
    Mutation.SetProp(ROOT, PropertyKind.Text, PropertyValue.Text("the screen")),
) + records

private const val BAR = 2
private const val BODY = 3

/**
 * A page that fills the window: a column, perhaps opening with a top bar, then a line of
 * text that takes the rest.
 */
private fun fullWindow(vararg records: Mutation, withBar: Boolean = false) = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(ROOT, WidgetKind.Column))
    add(Mutation.SetModifier(ROOT, 0, dev.darkpyonix.composerust.protocol.Modifier.FillMaxWidth))
    add(Mutation.SetModifier(ROOT, 1, dev.darkpyonix.composerust.protocol.Modifier.FillMaxHeight))
    if (withBar) {
        add(Mutation.Create(BAR, WidgetKind.TopAppBar))
        add(Mutation.SetProp(BAR, PropertyKind.Text, PropertyValue.Text("Inbox")))
        add(Mutation.Insert(ROOT, BAR, 0))
    }
    add(Mutation.Create(BODY, WidgetKind.Text))
    add(Mutation.SetProp(BODY, PropertyKind.Text, PropertyValue.Text("the screen")))
    add(Mutation.Insert(ROOT, BODY, if (withBar) 1 else 0))
    addAll(records)
}

@OptIn(ExperimentalTestApi::class)
class MessageTest {
    private val frames = FrameRequestSource()

    /** Draws `records` in a 400 by 800 dp window and answers the message's bounds in dp. */
    private fun messageBounds(records: List<Mutation>, also: (androidx.compose.ui.test.ComposeUiTest.() -> Unit)? = null): List<Float> {
        var bounds = emptyList<Float>()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(records)),
                        Modifier.requiredSize(WIDTH.dp, HEIGHT.dp),
                    )
                }
            }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            // Measured against the window's own corner, which is where the root sits.
            val window = onNodeWithTag(nodeTestTag(ROOT)).getUnclippedBoundsInRoot()
            val box = onNodeWithTag(MESSAGE_CONTAINER_TEST_TAG).getUnclippedBoundsInRoot()
            bounds = listOf(
                (box.left - window.left).value,
                (box.top - window.top).value,
                (box.right - window.left).value,
                (box.bottom - window.top).value,
            )
            also?.invoke(this)
        }
        return bounds
    }

    private fun assertNear(expected: Float, actual: Float, what: String) {
        kotlin.test.assertTrue(
            kotlin.math.abs(expected - actual) <= TOLERANCE,
            "$what: expected $expected dp, was $actual dp",
        )
    }

    /**
     * A message placed at the bottom centre with an offset of 96 is drawn 96 dp above the
     * bottom of the safe area and centred across the window.
     */
    @Test
    fun fr21_message_placement_bottom_center() {
        val (left, _, right, bottom) = messageBounds(
            fullWindow(
                Mutation.ShowMessage(
                    0L, "Copied", "", MessageDuration.Short,
                    MessageVertical.Bottom, MessageHorizontal.Center, 96f,
                ),
            ),
        )
        assertNear(HEIGHT - 96f, bottom, "the message's bottom edge")
        assertNear(WIDTH / 2f, (left + right) / 2f, "the message's centre")
    }

    /**
     * A message placed at the top end is drawn in the top right corner, below the top bar
     * the page opens with rather than over it.
     */
    @Test
    fun fr21_message_placement_top_end() {
        var barBottom = 0f
        val (_, top, right, _) = messageBounds(
            fullWindow(
                Mutation.ShowMessage(
                    0L, "Synced", "", MessageDuration.Short,
                    MessageVertical.Top, MessageHorizontal.End, null,
                ),
                withBar = true,
            ),
        ) {
            val window = onNodeWithTag(nodeTestTag(ROOT)).getUnclippedBoundsInRoot()
            barBottom = (onNodeWithTag(nodeTestTag(BAR)).getUnclippedBoundsInRoot().bottom - window.top).value
        }
        kotlin.test.assertTrue(barBottom > 0f, "the bar must have a height, was $barBottom")
        assertNear(barBottom + MATERIAL_INSET, top, "the message's top edge")
        assertNear(WIDTH - MATERIAL_INSET, right, "the message's right edge")
    }

    /**
     * A message that names no place is where it always was: Material's bottom start corner,
     * its inset from both edges, with a top bar on the page or not.
     */
    @Test
    fun fr21_a_message_without_a_placement_is_where_it_always_was() {
        for (withBar in listOf(false, true)) {
            val (left, _, _, bottom) = messageBounds(
                fullWindow(Mutation.ShowMessage(0L, "Saved", "", MessageDuration.Short), withBar = withBar),
            )
            assertNear(MATERIAL_INSET, left, "the message's left edge (bar: $withBar)")
            assertNear(HEIGHT - MATERIAL_INSET, bottom, "the message's bottom edge (bar: $withBar)")
        }
    }

    /**
     * Two messages in one batch are two messages, said one after the other.
     *
     * Replacing the first with the second would take away the answer to something the user
     * has just done, and showing both at once would cover the screen they are about. So the
     * second waits, and the clock that decides when it stops waiting is on this side.
     */
    @Test
    fun fr21_two_messages_in_one_batch_are_shown_one_at_a_time_in_order() = runComposeUiTest {
        // The message's lifetime is a delay, so the test drives the clock rather than
        // letting it run: autoAdvance would expire the first one before it could be read.
        mainClock.autoAdvance = false
        val batch = themed(
            Mutation.ShowMessage(0L, "first", "", MessageDuration.Short),
            Mutation.ShowMessage(0L, "second", "", MessageDuration.Short),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)))
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(MESSAGE_TEST_TAG).assertTextEquals("first")

        // The first one's time runs out, and the one behind it takes its place.
        mainClock.advanceTimeBy(SHORT_MILLIS)
        mainClock.advanceTimeByFrame()
        onNodeWithTag(MESSAGE_TEST_TAG).assertTextEquals("second")

        // And when that one's time runs out there is nothing left to say.
        mainClock.advanceTimeBy(SHORT_MILLIS)
        mainClock.advanceTimeByFrame()
        onNodeWithTag(MESSAGE_TEST_TAG).assertDoesNotExist()
    }

    /**
     * Pressing the action reports the "no node" id, because a message owns no node. The
     * message goes away when it is pressed: the thing it offered has been done.
     */
    @Test
    fun fr21_pressing_a_message_action_reports_it_against_no_node() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(
            themed(Mutation.ShowMessage(UNDO_HANDLER, "Task deleted", "Undo", MessageDuration.Long)),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(MESSAGE_TEST_TAG).assertTextEquals("Task deleted")

        onNodeWithTag(MESSAGE_ACTION_TEST_TAG).performClick()
        mainClock.advanceTimeByFrame()

        val clicks = connection.events.filterIsInstance<HostEvent.Clicked>()
        assertEquals(1, clicks.size, "the action must report once, not ${connection.events}")
        assertEquals(0, clicks[0].nodeId, "a message owns no node")
        assertEquals(UNDO_HANDLER, clicks[0].handlerId)
        onNodeWithTag(MESSAGE_TEST_TAG).assertDoesNotExist()
    }

    /**
     * The line is finite, and what it drops is the message that has waited longest without
     * being read, never the one on screen.
     */
    @Test
    fun fr21_the_line_of_messages_is_finite_and_drops_the_longest_waiting() {
        val queue = MessageQueue()
        repeat(MessageQueue.CAPACITY + 3) { index ->
            queue.post(0L, "message $index", "", MessageDuration.Short)
        }
        assertEquals(MessageQueue.CAPACITY, queue.size)
        // The one being read is still the one that was said first.
        assertEquals("message 0", queue.current?.text)

        // Emptying the line hands them over in the order they were said, minus the three
        // that were pushed out from behind the one on screen.
        val said = mutableListOf<String>()
        while (true) {
            val current = queue.current ?: break
            said += current.text
            queue.dismiss(current)
        }
        assertEquals(
            listOf("message 0", "message 4", "message 5", "message 6", "message 7",
                "message 8", "message 9", "message 10"),
            said,
        )
        assertNull(queue.current)
    }
}
