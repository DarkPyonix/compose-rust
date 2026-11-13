package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PAGE = 1
private const val BEFORE = 2
private const val GROUP = 3
private const val FIRST = 4
private const val SECOND = 5
private const val THIRD = 6
private const val AFTER = 7

private const val CLICK = 900L

/** The handler each radio button reports its change to: its own, so a report names it. */
private fun handlerOf(radio: Int) = 100L + radio

/**
 * A page with a button, a group of three radio buttons with `selected` selected (0 for
 * none), and another button, in `system`.
 */
private fun page(selected: Int, system: DesignSystem = DesignSystem.Material3) = buildList {
    add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(PAGE, WidgetKind.Column))
    for ((index, button) in listOf(BEFORE, AFTER).withIndex()) {
        add(Mutation.Create(button, WidgetKind.Button))
        add(Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text(if (index == 0) "Before" else "After")))
        add(Mutation.SetProp(button, PropertyKind.OnClick, PropertyValue.Integer(CLICK)))
    }
    add(Mutation.Create(GROUP, WidgetKind.Column))
    add(Mutation.SetProp(GROUP, PropertyKind.RadioGroup, PropertyValue.Bool(true)))
    for ((index, radio) in listOf(FIRST, SECOND, THIRD).withIndex()) {
        add(Mutation.Create(radio, WidgetKind.RadioButton))
        add(Mutation.SetProp(radio, PropertyKind.Checked, PropertyValue.Bool(radio == selected)))
        add(Mutation.SetProp(radio, PropertyKind.OnValueChange, PropertyValue.Integer(handlerOf(radio))))
        add(Mutation.Insert(GROUP, radio, index))
    }
    add(Mutation.Insert(PAGE, BEFORE, 0))
    add(Mutation.Insert(PAGE, GROUP, 1))
    add(Mutation.Insert(PAGE, AFTER, 2))
}

/**
 * A `Column` or `Row` that is a radio group is one Tab stop, its arrow keys move focus
 * along its radio buttons and ask the Host to select each one once, and assistive
 * technology hears it as a group of selectable things.
 */
@OptIn(ExperimentalTestApi::class)
class RadioGroupTest {

    @Test
    fun fr67_radio_group_arrow_keys_move_the_selection() {
        // Every design system: the library's radio button and the drawn one take the
        // group's focus the same way.
        for (system in DesignSystem.entries) runComposeUiTest {
            val connection = FakeHostConnection(page(selected = SECOND, system = system))
            setContent {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(400.dp, 400.dp))
            }
            waitForIdle()
            onNodeWithTag(nodeTestTag(BEFORE)).requestFocus()
            waitForIdle()

            // Tab stops at the group once, on the selected button, and the next Tab leaves.
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(SECOND)).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Tab) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(AFTER)).assertIsFocused()
            onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
            waitForIdle()
            onNodeWithTag(nodeTestTag(SECOND)).assertIsFocused()

            // Down moves focus to the next button and reports it once.
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(THIRD)).assertIsFocused()
            // Down at the end wraps to the first; Up and Left go back the other way.
            onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(FIRST)).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(THIRD)).assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            waitForIdle()
            onNodeWithTag(nodeTestTag(SECOND)).assertIsFocused()

            assertEquals(
                listOf(
                    HostEvent.ValueChanged(THIRD, handlerOf(THIRD), 1.0),
                    HostEvent.ValueChanged(FIRST, handlerOf(FIRST), 1.0),
                    HostEvent.ValueChanged(THIRD, handlerOf(THIRD), 1.0),
                    HostEvent.ValueChanged(SECOND, handlerOf(SECOND), 1.0),
                ),
                connection.nodeEvents.filterIsInstance<HostEvent.ValueChanged>(),
                "$system: each move reports the button it lands on, once",
            )
            // Nothing was clicked along the way.
            assertEquals(emptyList<HostEvent.Clicked>(), connection.nodeEvents.filterIsInstance<HostEvent.Clicked>(), "$system")

            onNodeWithTag(nodeTestTag(GROUP))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        }
    }

    /** With nothing selected the group's one stop is its first button. */
    @Test
    fun fr67_radio_group_with_nothing_selected_stops_at_the_first() = runComposeUiTest {
        val connection = FakeHostConnection(page(selected = 0))
        setContent {
            ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(400.dp, 400.dp))
        }
        waitForIdle()
        onNodeWithTag(nodeTestTag(BEFORE)).requestFocus()
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(FIRST)).assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        onNodeWithTag(nodeTestTag(AFTER)).assertIsFocused()
    }
}
