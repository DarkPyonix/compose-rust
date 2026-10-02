package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.foundation.MESSAGE_ACTION_TEST_TAG
import dioxus.compose.foundation.MESSAGE_TEST_TAG
import dioxus.compose.foundation.MessageQueue
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.MessageDuration
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val ROOT = 1
private const val UNDO_HANDLER = 4242L

/** Material's short duration, which is what these tests wait out. */
private const val SHORT_MILLIS = 4_000L

private fun themed(vararg records: Mutation) = listOf(
    Mutation.SetTheme(
        Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
    ),
    Mutation.Create(ROOT, WidgetKind.Text),
    Mutation.SetProp(ROOT, PropertyKind.Text, PropertyValue.Text("the screen")),
) + records

@OptIn(ExperimentalTestApi::class)
class MessageTest {
    private val frames = FrameRequestSource()

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
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch)))
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
                DioxusContent(rememberDioxusHost(connection))
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
