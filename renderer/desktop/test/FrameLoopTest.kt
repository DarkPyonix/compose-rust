package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import androidx.compose.runtime.CompositionLocalProvider
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag

private const val LABEL = 1

/** The frame loop: however many requests a worker makes, one `render_frame` call follows. */
@OptIn(ExperimentalTestApi::class)
class FrameLoopTest {
    // Private to this class rather than the process-wide counter: two hosts alive at
    // once in the same test process would otherwise drive each other's frame loops, and a
    // request left behind by one test can keep another's composition from going idle.
    private val frames = FrameRequestSource()


    @Test
    fun pr3_frame_request_applies_exactly_one_frame_batch() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("initial")),
            ),
        )
        connection.scheduleFrame(
            listOf(Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("first frame"))),
        )
        connection.scheduleFrame(
            listOf(Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("second frame"))),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberComposeRustHost(connection))
            }
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(LABEL)).assertTextEquals("initial")

        frames.request()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        // Only the first queued batch is consumed: one request, one frame.
        onNodeWithTag(nodeTestTag(LABEL)).assertTextEquals("first frame")
    }
}
