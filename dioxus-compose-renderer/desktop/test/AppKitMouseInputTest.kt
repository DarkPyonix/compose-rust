package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppKitMouseInputTest {
    @Test
    fun mouse_press_and_release_on_a_button_reach_the_host_once() = runComposeUiTest {
        val button = 1
        val label = 2
        val handler = 41L
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(button, WidgetKind.Button),
                Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(handler)),
                Mutation.Create(label, WidgetKind.Text),
                Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("Press me")),
                Mutation.Insert(button, label, 0),
            ),
        )
        setContent { DioxusContent(rememberDioxusHost(connection)) }

        onNodeWithTag(nodeTestTag(button)).performMouseInput { click() }
        waitForIdle()

        assertEquals(
            listOf(HostEvent.Clicked(button, handler)),
            connection.events.filterIsInstance<HostEvent.Clicked>(),
        )
    }
}
