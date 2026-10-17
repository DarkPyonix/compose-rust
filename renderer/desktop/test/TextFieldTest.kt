package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.foundation.shouldSubmitOnEnter
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag

private const val FIELD = 1
private const val CHANGE_HANDLER = 21L

private fun field(vararg extra: Mutation) = listOf(
    Mutation.Create(FIELD, WidgetKind.TextField),
    Mutation.SetProp(FIELD, PropertyKind.OnValueChange, PropertyValue.Integer(CHANGE_HANDLER)),
    *extra,
)

/**
 * How dark the darkest ink in a captured field is, from nothing to 255.
 *
 * The darkest pixel rather than an average: what is being compared is the colour the
 * letters are set in, and most of a field is the space around them.
 */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.darkestInk(): Int {
    val pixels = onNodeWithTag(nodeTestTag(FIELD)).captureToImage().toPixelMap()
    var darkest = 255
    for (y in 0 until pixels.height) {
        for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            val level = ((pixel.red + pixel.green + pixel.blue) / 3f * 255f).toInt()
            if (level < darkest) darkest = level
        }
    }
    return darkest
}

@OptIn(ExperimentalTestApi::class)
class TextFieldTest {
    /**
     * A placeholder is not text, and it is not set as though it were.
     *
     * It was. The word in an empty composer came out in exactly the colour a real label
     * beside it was set in, so nothing on screen said which of the two would disappear the
     * moment you started typing.
     */
    @Test
    fun fr5_a_placeholder_is_quieter_than_what_replaces_it() = runComposeUiTest {
        val hint = FakeHostConnection(
            field(Mutation.SetProp(FIELD, PropertyKind.Placeholder, PropertyValue.Text("Message"))),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(hint)) }
        waitForIdle()
        val placeholder = darkestInk()

        val typed = FakeHostConnection(
            field(Mutation.SetProp(FIELD, PropertyKind.Text, PropertyValue.Text("Message"))),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(typed)) }
        waitForIdle()
        val text = darkestInk()

        assertTrue(
            placeholder > text,
            "the placeholder's ink is $placeholder and the text that replaces it is $text, " +
                "so an empty field looks like a field with something in it",
        )
    }

    @Test
    fun fr5_editing_value_stays_in_the_renderer_and_notifies_the_host() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }

        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("hi")
        waitForIdle()
        waitForIdle()

        onNodeWithTag(nodeTestTag(FIELD)).assertTextEquals("hi")
        val changes = connection.events.filterIsInstance<HostEvent.TextChanged>()
        assertEquals("hi", changes.lastOrNull()?.text, "events were ${connection.events}")
    }

    @Test
    fun fr5_an_edit_reaches_the_host_on_the_next_frame() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        mainClock.autoAdvance = false

        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("h")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        // One frame or two, with no timer in between.
        val changes = connection.events.filterIsInstance<HostEvent.TextChanged>()
        assertEquals(listOf("h"), changes.map { it.text }, "events were ${connection.events}")
    }

    @Test
    fun fr5_the_text_changed_for_an_edit_is_dispatched_in_the_frame_that_saw_it() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        mainClock.autoAdvance = false

        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("h")
        mainClock.advanceTimeByFrame()

        // One frame, not the one after it: the send is made inside the frame callback, so
        // the Host's reply is applied before the next frame is composed.
        assertEquals(
            listOf("h"),
            connection.events.filterIsInstance<HostEvent.TextChanged>().map { it.text },
            "events were ${connection.events}",
        )
    }

    @Test
    fun fr5_edits_within_one_frame_are_one_event_with_the_latest_text() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        mainClock.autoAdvance = false

        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("a")
        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("b")
        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("c")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(
            listOf("abc"),
            connection.events.filterIsInstance<HostEvent.TextChanged>().map { it.text },
            "events were ${connection.events}",
        )
    }

    @Test
    fun fr5_an_edit_after_the_last_send_is_sent_too() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        setContent { ComposeRustContent(rememberComposeRustHost(connection)) }
        mainClock.autoAdvance = false

        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("a")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithTag(nodeTestTag(FIELD)).performTextInput("b")
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(
            listOf("a", "ab"),
            connection.events.filterIsInstance<HostEvent.TextChanged>().map { it.text },
            "events were ${connection.events}",
        )
    }

    @Test
    fun fr5_host_set_text_replaces_the_value_when_no_composition_is_active() = runComposeUiTest {
        val connection = FakeHostConnection(field())
        connection.respondWith {
            HostResponse(listOf(Mutation.SetText(FIELD, "from host", -1, -1)))
        }
        lateinit var host: ComposeRustHost
        setContent { host = rememberComposeRustHost(connection) ; ComposeRustContent(host) }

        host.dispatch(HostEvent.Clicked(FIELD, 0))
        waitForIdle()

        onNodeWithTag(nodeTestTag(FIELD)).assertTextEquals("from host")
    }

    @Test
    fun fr5_enter_during_composition_only_commits_the_composition() {
        // An Enter press while the IME is composing must never reach the Host: it means
        // "commit the composition". Driving a real IME is a manual check; this
        // pins the decision the key handler makes.
        assertFalse(
            shouldSubmitOnEnter(
                composing = true,
                multiline = false,
                shiftPressed = false,
                hasSubmitHandler = true,
            ),
        )
        assertTrue(
            shouldSubmitOnEnter(
                composing = false,
                multiline = false,
                shiftPressed = false,
                hasSubmitHandler = true,
            ),
        )
    }

    @Test
    fun fr5_shift_enter_in_a_multiline_field_is_a_newline_not_a_submit() {
        assertFalse(
            shouldSubmitOnEnter(
                composing = false,
                multiline = true,
                shiftPressed = true,
                hasSubmitHandler = true,
            ),
        )
        assertTrue(
            shouldSubmitOnEnter(
                composing = false,
                multiline = true,
                shiftPressed = false,
                hasSubmitHandler = true,
            ),
        )
    }
}
