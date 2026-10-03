package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.rememberStartedComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test

private const val LABEL = 1

@OptIn(ExperimentalTestApi::class)
class FrameRequestAtStartTest {
    // Private to this class rather than the process-wide counter, so no other test's host
    // can drive this one's frames.
    private val frames = FrameRequestSource()

    /**
     * Work the Host schedules while it starts is drawn without waiting for anything else.
     *
     * The platform starts the Host before there is a window, so the frame loop is not yet
     * listening when the Host's first render asks for a frame: a task spawned at startup, an
     * effect. The loop used to count requests from when it began listening, took that one
     * as already served, and left the work undrawn until some unrelated event came along.
     * The Kotlin/Native window on Linux, where none came, never drew it at all.
     */
    @Test
    fun pr3_a_frame_requested_while_the_host_starts_is_drawn() = runComposeUiTest {
        val fake = FakeHostConnection(
            listOf(
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("starting")),
            ),
        )
        fake.scheduleFrame(
            listOf(Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("drawn"))),
        )
        // A Host that asks for a frame from inside its own init, the way the Rust Host does
        // when its first render left work behind.
        val connection = object : HostConnection by fake {
            override fun init(onMutation: (Mutation) -> Unit) {
                fake.init(onMutation)
                frames.request()
            }
        }
        // Started outside composition, as every platform starts it.
        val host = ComposeRustHost(connection)
        host.start(frames)

        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                ComposeRustContent(rememberStartedComposeRustHost(host))
            }
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(LABEL)).assertTextEquals("drawn")
    }
}
