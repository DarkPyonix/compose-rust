@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.darkpyonix.composerust.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import dev.darkpyonix.composerust.ui.platform.MacModifiers
import dev.darkpyonix.composerust.ui.platform.NativeTextInput
import dev.darkpyonix.composerust.ui.platform.editingKeyEvents
import dev.darkpyonix.composerust.ui.platform.insertableText
import dev.darkpyonix.composerust.ui.platform.isEditingShortcut
import dev.darkpyonix.composerust.ui.platform.macKeyEvent
import dev.darkpyonix.composerust.ui.platform.reachesInputMethod
import dev.darkpyonix.composerust.ui.platform.receive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.thisisthepy.compose.window.WindowEvent
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

    @Test
    fun fr5_native_text_input_drops_control_characters_on_macos() = runBlocking {
        val committed = mutableListOf<String>()
        val request = object : PlatformTextInputMethodRequest {
            override val value: () -> TextFieldValue = { TextFieldValue("") }
            override val state: TextEditorState = object : TextEditorState {
                override val length: Int get() = 0
                override fun get(index: Int): Char = throw IndexOutOfBoundsException()
                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = ""
                override val selection: TextRange get() = TextRange.Zero
                override val composition: TextRange? get() = null
            }
            override val imeOptions: ImeOptions = ImeOptions.Default
            override val onEditCommand: (List<EditCommand>) -> Unit = { commands ->
                for (command in commands) {
                    if (command is CommitTextCommand) committed += command.text
                }
            }
            override val onImeAction: ((ImeAction) -> Unit)? = null
            override val textLayoutResult: () -> TextLayoutResult = { error("unused") }
            override val focusedRectInRoot: () -> Rect = { Rect.Zero }
            override val textFieldRectInRoot: () -> Rect = { Rect.Zero }
            override val textClippingRectInRoot: () -> Rect = { Rect.Zero }
            override val unclippedTextOffsetInRoot: () -> Offset = { Offset.Zero }
            override val editText: ((TextEditingScope) -> Unit) -> Unit = {}
        }
        val textInput = NativeTextInput()
        val session = launch(start = CoroutineStart.UNDISPATCHED) { textInput.run(request) }
        try {
            assertTrue(textInput.isActive)
            textInput.receive(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "\u0001"), macos = true)
            assertEquals(emptyList(), committed, "control characters must never be committed on macOS")
            textInput.receive(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "a"), macos = true)
            textInput.receive(WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, "b\u0001c"), macos = true)
            assertEquals(listOf("a", "bc"), committed)
        } finally {
            session.cancel()
        }
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
