package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ModalAccessibilityTest {
    @Test
    fun fr71_modal_hides_background_from_accessibility_tree() = runComposeUiTest {
        val frames = FrameRequestSource()
        val initial = listOf(
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Insert(0, 1, 0),
            Mutation.Create(2, WidgetKind.Button),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("Behind Modal")),
            Mutation.SetProp(2, PropertyKind.OnClick, PropertyValue.Integer(10L)),
            Mutation.Insert(1, 2, 0),
            Mutation.Create(3, WidgetKind.Dialog),
            Mutation.SetProp(3, PropertyKind.Open, PropertyValue.Bool(false)),
            Mutation.Insert(1, 3, 1),
            Mutation.Create(4, WidgetKind.Button),
            Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("Inside Modal")),
            Mutation.SetProp(4, PropertyKind.OnClick, PropertyValue.Integer(20L)),
            Mutation.Insert(3, 4, 0),
        )
        val connection = FakeHostConnection(initial)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        // Before the modal opens, the background button is exposed in the semantics tree.
        onNodeWithText("Behind Modal").assertIsDisplayed()
        onNodeWithText("Inside Modal").assertDoesNotExist()

        // Open the modal dialog: background is hidden from accessibility, modal content is exposed.
        connection.scheduleFrame(listOf(Mutation.SetProp(3, PropertyKind.Open, PropertyValue.Bool(true))))
        frames.request()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        onNodeWithText("Inside Modal").assertIsDisplayed()
        onNodeWithText("Behind Modal").assertDoesNotExist()

        // Close the modal dialog: background is exposed again.
        connection.scheduleFrame(listOf(Mutation.SetProp(3, PropertyKind.Open, PropertyValue.Bool(false))))
        frames.request()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        onNodeWithText("Behind Modal").assertIsDisplayed()
        onNodeWithText("Inside Modal").assertDoesNotExist()
    }
}
