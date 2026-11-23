package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertTrue

/** The modal bit of the `Semantics` state word. */
private const val MODAL = 1 shl 14

private fun button(id: Int, label: String, handler: Long): List<Mutation> = listOf(
    Mutation.Create(id, WidgetKind.Button),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)),
    Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handler)),
)

/** Focused nodes inside, or outside, the node tagged [tag]. */
private fun focusedWithin(tag: String): SemanticsMatcher = isFocused() and hasAnyAncestor(hasTestTag(tag))
private fun focusedOutside(tag: String): SemanticsMatcher =
    isFocused() and !hasAnyAncestor(hasTestTag(tag)) and !hasTestTag(tag)

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

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.showTree(connection: FakeHostConnection, frames: FrameRequestSource) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.send(connection: FakeHostConnection, frames: FrameRequestSource, vararg mutations: Mutation) {
        connection.scheduleFrame(mutations.toList())
        frames.request()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /** The text of the focused node inside [tag], or null when focus is on the overlay itself. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.focusedText(tag: String): String? {
        onAllNodes(focusedOutside(tag)).assertCountEquals(0)
        val inside = onAllNodes(focusedWithin(tag)).fetchSemanticsNodes()
        assertTrue(inside.size == 1, "focus is not inside $tag: ${inside.size} focused nodes there")
        return inside.single().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    }

    @Test
    fun fr71_stacked_modals_expose_only_the_topmost() = runComposeUiTest {
        val frames = FrameRequestSource()
        val connection = FakeHostConnection(
            buildList {
                add(Mutation.Create(1, WidgetKind.Column))
                add(Mutation.Insert(0, 1, 0))
                addAll(button(2, "Page", 10L))
                add(Mutation.Insert(1, 2, 0))
                add(Mutation.Create(3, WidgetKind.Sheet))
                add(Mutation.SetProp(3, PropertyKind.Open, PropertyValue.Bool(true)))
                add(Mutation.Insert(1, 3, 1))
                add(Mutation.Create(4, WidgetKind.Text))
                add(Mutation.SetProp(4, PropertyKind.Text, PropertyValue.Text("Sheet content")))
                add(Mutation.Insert(3, 4, 0))
                add(Mutation.Create(5, WidgetKind.Dialog))
                add(Mutation.SetProp(5, PropertyKind.Open, PropertyValue.Bool(false)))
                add(Mutation.Insert(3, 5, 1))
                addAll(button(6, "Dialog button", 20L))
                add(Mutation.Insert(5, 6, 0))
            },
        )
        showTree(connection, frames)

        // The sheet hides the page.
        onNodeWithText("Sheet content").assertIsDisplayed()
        onNodeWithText("Page").assertDoesNotExist()

        // A dialog over the sheet hides the sheet as well: only the topmost modal is read.
        send(connection, frames, Mutation.SetProp(5, PropertyKind.Open, PropertyValue.Bool(true)))
        onNodeWithText("Dialog button").assertIsDisplayed()
        onNodeWithText("Sheet content").assertDoesNotExist()
        onNodeWithText("Page").assertDoesNotExist()

        // Closing the dialog makes the sheet the topmost again; the page stays hidden.
        send(connection, frames, Mutation.SetProp(5, PropertyKind.Open, PropertyValue.Bool(false)))
        onNodeWithText("Dialog button").assertDoesNotExist()
        onNodeWithText("Sheet content").assertIsDisplayed()
        onNodeWithText("Page").assertDoesNotExist()
    }

    @Test
    fun fr71_dialog_tab_and_shift_tab_cycle_inside() = runComposeUiTest {
        val frames = FrameRequestSource()
        val dialog = 3
        val connection = FakeHostConnection(
            buildList {
                add(Mutation.Create(1, WidgetKind.Column))
                add(Mutation.Insert(0, 1, 0))
                addAll(button(2, "Behind", 10L))
                add(Mutation.Insert(1, 2, 0))
                add(Mutation.Create(dialog, WidgetKind.Dialog))
                add(Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(false)))
                add(Mutation.Insert(1, dialog, 1))
                addAll(button(4, "First", 20L))
                add(Mutation.Insert(dialog, 4, 0))
                addAll(button(5, "Second", 21L))
                add(Mutation.Insert(dialog, 5, 1))
            },
        )
        showTree(connection, frames)
        send(connection, frames, Mutation.SetProp(dialog, PropertyKind.Open, PropertyValue.Bool(true)))
        val tag = dialogTestTag(dialog)

        // Tab walks the dialog and comes round to the first button again, never leaving it.
        val forward = (1..6).map {
            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            focusedText(tag)
        }
        val second = forward.indexOf("Second")
        assertTrue(second >= 0, "Tab never reached the second button: $forward")
        assertTrue(forward.drop(second + 1).contains("First"), "Tab did not come round to the first button: $forward")

        // Shift+Tab goes the other way and stays inside too.
        val backward = (1..6).map {
            onAllNodes(isRoot()).onLast().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            waitForIdle()
            focusedText(tag)
        }
        assertTrue(backward.contains("First") && backward.contains("Second"), "Shift+Tab did not go round: $backward")
    }

    /** A page whose middle column carries the modal bit, between two buttons outside it. */
    private fun modalColumnTree(): List<Mutation> = buildList {
        add(Mutation.Create(1, WidgetKind.Column))
        add(Mutation.Insert(0, 1, 0))
        addAll(button(2, "Outside before", 10L))
        add(Mutation.Insert(1, 2, 0))
        add(Mutation.Create(3, WidgetKind.Column))
        add(Mutation.SetModifier(3, 0, ProtocolModifier.Semantics(SemanticsRole.Group, MODAL)))
        add(Mutation.Insert(1, 3, 1))
        addAll(button(4, "In A", 20L))
        add(Mutation.Insert(3, 4, 0))
        addAll(button(5, "In B", 21L))
        add(Mutation.Insert(3, 5, 1))
        addAll(button(6, "Outside after", 11L))
        add(Mutation.Insert(1, 6, 2))
    }

    @Test
    fun fr71_a_node_with_the_modal_bit_hides_everything_outside_it() = runComposeUiTest {
        val frames = FrameRequestSource()
        val connection = FakeHostConnection(modalColumnTree())
        showTree(connection, frames)

        onNodeWithText("In A").assertIsDisplayed()
        onNodeWithText("In B").assertIsDisplayed()
        onNodeWithText("Outside before").assertDoesNotExist()
        onNodeWithText("Outside after").assertDoesNotExist()

        // Taking the bit away brings the rest back.
        send(connection, frames, Mutation.SetModifier(3, 0, ProtocolModifier.Semantics(SemanticsRole.Group, 0)))
        onNodeWithText("Outside before").assertIsDisplayed()
        onNodeWithText("Outside after").assertIsDisplayed()
        onNodeWithText("In A").assertIsDisplayed()
    }

    @Test
    fun fr71_tab_does_not_leave_a_node_with_the_modal_bit() = runComposeUiTest {
        val frames = FrameRequestSource()
        val connection = FakeHostConnection(modalColumnTree())
        showTree(connection, frames)
        val tag = nodeTestTag(3)
        onNodeWithText("In A").requestFocus()
        waitForIdle()

        val forward = (1..4).map {
            onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            focusedText(tag)
        }
        assertTrue(forward.contains("In B"), "Tab never reached the second button inside: $forward")

        val backward = (1..4).map {
            onAllNodes(isRoot()).onLast().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            waitForIdle()
            focusedText(tag)
        }
        assertTrue(backward.contains("In A"), "Shift+Tab never reached the first button inside: $backward")
    }
}
