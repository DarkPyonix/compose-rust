package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.darkpyonix.composerust.ui.platform.MacModifiers
import dev.darkpyonix.composerust.ui.platform.editingKeyEvents
import dev.darkpyonix.composerust.ui.platform.insertableText
import dev.darkpyonix.composerust.ui.platform.isEditingShortcut
import dev.darkpyonix.composerust.ui.platform.macKeyEvent
import dev.darkpyonix.composerust.ui.platform.reachesInputMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How an AppKit key reaches Compose, on both macOS windows.
 *
 * The same file runs in the native image's tests and, through a symlink, in the
 * Kotlin/Native renderer's, because both windows call the functions it holds. A key that
 * the two paths answered differently is what this is here to stop.
 */
class MacKeysTest {
    private val command = MacModifiers.COMMAND
    private val shift = MacModifiers.SHIFT
    private val control = MacModifiers.CONTROL
    private val option = MacModifiers.OPTION

    @Test
    fun fr5_command_letters_arrive_as_meta_for_the_editing_shortcuts() {
        // Key number, then what it must be. Compose's macOS mapping matches copy, paste,
        // cut, select all and undo on these keys with `isMetaPressed`.
        val shortcuts = listOf(0x00 to Key.A, 0x08 to Key.C, 0x09 to Key.V, 0x07 to Key.X, 0x06 to Key.Z)
        for ((code, key) in shortcuts) {
            val event = macKeyEvent(code, command, KeyEventType.KeyDown)
            assertEquals(key, event.key)
            assertTrue(event.isMetaPressed, "Command ${event.key} arrived without Command held")
            assertFalse(event.isCtrlPressed, "Command is not Control on this platform")
            assertFalse(event.isAltPressed)
            assertFalse(event.isShiftPressed)
        }
        val redo = macKeyEvent(0x06, command or shift, KeyEventType.KeyDown)
        assertTrue(redo.isMetaPressed && redo.isShiftPressed, "Shift Command Z is redo")
    }

    @Test
    fun fr5_control_a_arrives_as_control_a_which_compose_reads_as_line_start() {
        val event = macKeyEvent(0x00, control, KeyEventType.KeyDown)
        assertEquals(Key.A, event.key)
        assertTrue(event.isCtrlPressed)
        assertFalse(event.isMetaPressed)
    }

    @Test
    fun fr5_the_editing_shortcuts_are_claimed_before_the_menu_bar() {
        for (code in listOf(0x00, 0x08, 0x09, 0x07, 0x06)) {
            assertTrue(isEditingShortcut(code, command), "Command key 0x${code.toString(16)}")
        }
        assertTrue(isEditingShortcut(0x06, command or shift), "Shift Command Z is redo")
        assertFalse(isEditingShortcut(0x0C, command), "Command Q is the menu's, to quit")
        assertFalse(isEditingShortcut(0x04, command), "Command H is the menu's, to hide")
        assertFalse(isEditingShortcut(0x00, 0L), "A on its own types a letter")
        assertFalse(isEditingShortcut(0x00, control), "Control A is not a shortcut to claim")
        assertFalse(isEditingShortcut(0x00, command or option))
    }

    @Test
    fun fr5_command_keys_never_reach_the_input_method() {
        assertFalse(reachesInputMethod(command), "an input method shown Command A types an a")
        assertFalse(reachesInputMethod(command or shift))
        assertTrue(reachesInputMethod(0L), "letters are the input method's to type")
        assertTrue(reachesInputMethod(shift))
        assertTrue(reachesInputMethod(control), "Control keys go through the key bindings")
    }

    @Test
    fun fr5_insert_text_never_inserts_a_control_character() {
        assertEquals("", insertableText("\u0001"), "Control A typed a box into the field")
        assertEquals("", insertableText("\u001b"))
        assertEquals("", insertableText("\u007f"))
        assertEquals("ab", insertableText("a\u0001b"))
        assertEquals("한글", insertableText("한글"), "composed text is untouched")
        assertEquals("é", insertableText("é"), "combining marks are not control")
    }

    @Test
    fun fr5_appkit_editing_commands_become_the_compose_keys_that_mean_them() {
        assertKeys(editingKeyEvents("moveToBeginningOfLine:"), Key.A, ctrl = true)
        assertKeys(editingKeyEvents("moveToEndOfLine:"), Key.E, ctrl = true)
        assertKeys(editingKeyEvents("selectAll:"), Key.A, meta = true)
        assertKeys(editingKeyEvents("copy:"), Key.C, meta = true)
        assertKeys(editingKeyEvents("cut:"), Key.X, meta = true)
        assertKeys(editingKeyEvents("paste:"), Key.V, meta = true)
        assertKeys(editingKeyEvents("undo:"), Key.Z, meta = true)
        assertKeys(editingKeyEvents("redo:"), Key.Z, meta = true, shift = true)
        assertNull(editingKeyEvents("noop:"), "a command nobody maps does nothing")
        assertNull(editingKeyEvents("insertText:"))
    }

    private fun assertKeys(
        events: List<KeyEvent>?,
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
    ) {
        requireNotNull(events)
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), events.map { it.type })
        for (event in events) {
            assertEquals(key, event.key)
            assertEquals(meta, event.isMetaPressed, "Command on $key")
            assertEquals(ctrl, event.isCtrlPressed, "Control on $key")
            assertEquals(shift, event.isShiftPressed, "Shift on $key")
            assertFalse(event.isAltPressed)
        }
    }
}
