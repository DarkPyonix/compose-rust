package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.node.RenderNodeObserver
import dioxus.compose.ui.node.nodeTestTag

private const val COLUMN = 1
private const val STREAM = 2
private const val SIBLING = 3
private const val TICK_HANDLER = 55L

private fun streamingColumn() = listOf(
    Mutation.Create(COLUMN, WidgetKind.Column),
    Mutation.Create(STREAM, WidgetKind.Text),
    Mutation.SetProp(STREAM, PropertyKind.Text, PropertyValue.Text("")),
    Mutation.Insert(COLUMN, STREAM, 0),
    Mutation.Create(SIBLING, WidgetKind.Text),
    Mutation.SetProp(SIBLING, PropertyKind.Text, PropertyValue.Text("sibling")),
    Mutation.Insert(COLUMN, SIBLING, 1),
)

@OptIn(ExperimentalTestApi::class)
class StreamingTextTest {
    @AfterTest
    fun clearObserver() {
        RenderNodeObserver.onCompose = null
    }

    /**
     * `AppendText` grows a Text node by its tail. Only that node recomposes, so the
     * rest of the screen is not laid out again on every streamed token.
     */
    @Test
    fun fr9_append_text_grows_the_node_without_recomposing_anything_else() = runComposeUiTest {
        val compositions = mutableMapOf<Int, Int>()
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
        val tokens = ArrayDeque(listOf("Hello", ", ", "world"))
        val connection = FakeHostConnection(streamingColumn())
        connection.respondWith { event ->
            if (event is HostEvent.Clicked && tokens.isNotEmpty()) {
                HostResponse(listOf(Mutation.AppendText(STREAM, tokens.removeFirst())))
            } else {
                HostResponse()
            }
        }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        waitForIdle()
        val before = compositions.toMap()

        repeat(3) {
            host.dispatch(HostEvent.Clicked(STREAM, TICK_HANDLER))
            waitForIdle()
        }

        onNodeWithTag(nodeTestTag(STREAM)).assertTextEquals("Hello, world")
        assertEquals(
            (before[STREAM] ?: 0) + 3,
            compositions[STREAM],
            "the streaming Text recomposes once per appended tail",
        )
        assertEquals(
            before[SIBLING],
            compositions[SIBLING],
            "a streamed tail must not recompose a sibling",
        )
        assertEquals(
            before[COLUMN],
            compositions[COLUMN],
            "a streamed tail must not recompose the parent",
        )
    }

    /** The batch carries the tail only, so the cost does not grow with the text. */
    @Test
    fun fr9_only_the_tail_is_sent_and_the_node_keeps_what_it_had() = runComposeUiTest {
        val connection = FakeHostConnection(
            streamingColumn() + Mutation.AppendText(STREAM, "first"),
        )
        connection.respondWith { HostResponse(listOf(Mutation.AppendText(STREAM, "-second"))) }
        lateinit var host: DioxusHost
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        waitForIdle()

        onNodeWithTag(nodeTestTag(STREAM)).assertTextEquals("first")

        host.dispatch(HostEvent.Clicked(STREAM, TICK_HANDLER))
        waitForIdle()

        onNodeWithTag(nodeTestTag(STREAM)).assertTextEquals("first-second")
    }
}
