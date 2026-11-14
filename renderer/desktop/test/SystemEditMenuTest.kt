@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.ui.platform.FocusedTextField
import dev.darkpyonix.composerust.ui.platform.SystemEditMenuGate
import dev.darkpyonix.composerust.ui.platform.leaveTextMenuToWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val FIELD = "field"

/**
 * The text edit menu a window puts up itself, where Compose draws its own and has no place to
 * hand it to the system (Kotlin/Native on Windows): when the window's is on, and that
 * Compose's stays shut while it is. One right click, one menu.
 */
class SystemEditMenuTest {

    /** What the window was told, in order: null for off. */
    private val told = mutableListOf<FocusedTextField?>()

    private fun gate(systemMenu: Boolean = true) = SystemEditMenuGate(systemMenu) { told += it }

    @Test
    fun fr33_1_windows_menu_is_on_only_while_a_field_has_focus() {
        val gate = gate()
        gate.update(focused = false)
        assertFalse(gate.armed)
        gate.update(focused = true, hasSelection = false, hasText = true)
        assertTrue(gate.armed, "a focused field puts the system's menu up on a right click")
        gate.update(focused = false)
        assertFalse(gate.armed, "with no field focused there is no text to edit, and no menu")
        assertEquals(listOf(null, FocusedTextField(hasSelection = false, editable = true, hasText = true), null), told)
    }

    @Test
    fun fr33_1_windows_menu_greys_by_what_the_field_allows() {
        val gate = gate()
        gate.update(focused = true, hasSelection = true, hasText = true)
        gate.update(focused = true, hasSelection = false, hasText = true)
        gate.update(focused = true, hasSelection = false, hasText = false)
        assertEquals(
            listOf(
                FocusedTextField(hasSelection = true, editable = true, hasText = true),
                FocusedTextField(hasSelection = false, editable = true, hasText = true),
                FocusedTextField(hasSelection = false, editable = true, hasText = false),
            ),
            told,
            "each change of what Cut, Copy and Select All can do reaches the window",
        )
    }

    @Test
    fun fr33_1_windows_window_is_told_only_a_change() {
        val gate = gate()
        repeat(3) { gate.update(focused = true, hasText = true) }
        repeat(3) { gate.update(focused = false) }
        assertEquals(2, told.size, "the window is told once per change, not once per turn: $told")
    }

    @Test
    fun fr33_1_windows_drawn_menu_override_keeps_the_window_menu_off() {
        val gate = gate(systemMenu = false)
        gate.update(focused = true, hasSelection = true, hasText = true)
        assertFalse(gate.armed, "an application that draws its own menu gets no second one from the window")
        assertEquals(listOf<FocusedTextField?>(null), told)
    }

    @Test
    fun fr33_1_windows_app_menu_turns_the_window_menu_off_for_that_press() {
        val gate = gate()
        gate.update(focused = true, hasText = true)
        assertTrue(gate.armed)
        // The application consumed the right click: its own menu is the one for this press.
        gate.contextMenuTaken()
        assertFalse(gate.armed, "the window's menu stands back for a press the application took")
        // Turns of the window while the button is still held keep it off.
        gate.update(focused = true, hasText = true)
        assertFalse(gate.armed)
        gate.pointerReleased()
        assertTrue(gate.armed, "the next right click puts the system's menu up again")
        assertEquals(3, told.size, "on, off for the press, on again: $told")
    }

    @Test
    fun fr33_1_windows_armed_window_menu_keeps_compose_menu_shut() {
        listOf(true, false).forEach { armed ->
            val requests = mutableListOf<Int>()
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalContextMenuRepresentation provides Recording(requests)) {
                        val state = rememberTextFieldState("hello world")
                        BasicTextField(state, Modifier.testTag(FIELD).leaveTextMenuToWindow { armed })
                    }
                }
                onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
                waitForIdle()
            }
            assertEquals(
                if (armed) 0 else 1,
                requests.size,
                if (armed) "the window's menu is the one: Compose's must not open beside it" else "with the window's off, Compose's menu is the one",
            )
        }
    }

    @Test
    fun fr33_1_windows_application_still_hears_the_press_compose_menu_was_kept_from() = runComposeUiTest {
        var heard = 0
        setContent {
            Box(
                Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) heard++
                        }
                    }
                },
            ) {
                val state = rememberTextFieldState("hello world")
                BasicTextField(state, Modifier.testTag(FIELD).leaveTextMenuToWindow { true })
            }
        }
        onNodeWithTag(FIELD).performMouseInput { rightClick(center) }
        waitForIdle()
        assertEquals(1, heard, "a node of the application around the field still hears the right click")
    }

    /** Counts the menus Compose asked to show. */
    private class Recording(private val requests: MutableList<Int>) : ContextMenuRepresentation {
        @Composable
        override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
            val status = state.status
            if (status !is ContextMenuState.Status.Open) return
            LaunchedEffect(status) {
                requests += items().size
                state.status = ContextMenuState.Status.Closed
            }
        }
    }
}
