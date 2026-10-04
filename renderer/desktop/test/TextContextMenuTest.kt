package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.darkpyonix.composerust.ui.platform.TextCommand
import dev.darkpyonix.composerust.ui.platform.perform
import dev.darkpyonix.composerust.ui.platform.textContextMenu
import dev.darkpyonix.composerust.ui.platform.textMenuSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The text context menu both macOS windows draw, as data.
 *
 * Right-click menu copy and the keyboard shortcut have to mean the same thing, and the
 * native window and the Kotlin/Native window have to offer the same rows.
 */
class TextContextMenuTest {

    @Test
    fun fr33_6_the_menu_offers_cut_copy_paste_and_select_all_in_the_platform_order() {
        val rows = textContextMenu(clipboardHasText = true)
        assertEquals(
            listOf(TextCommand.Cut, TextCommand.Copy, TextCommand.Paste, null, TextCommand.SelectAll),
            rows.map { it.command },
        )
        assertTrue(rows.all { it.enabled })
    }

    @Test
    fun fr5_paste_is_greyed_out_while_the_clipboard_holds_no_text() {
        val paste = textContextMenu(clipboardHasText = false).single { it.command == TextCommand.Paste }
        assertFalse(paste.enabled)
        assertTrue(
            textContextMenu(clipboardHasText = false)
                .filter { it.command != TextCommand.Paste }
                .all { it.enabled },
            "only paste can be judged from outside the field",
        )
    }

    @Test
    fun fr33_6_choosing_copy_presses_command_c_down_then_up() {
        val sent = ArrayList<KeyEvent>()
        TextCommand.Copy.perform { sent.add(it) }
        assertEquals(listOf(KeyEventType.KeyDown, KeyEventType.KeyUp), sent.map { it.type })
        assertTrue(sent.all { it.key == Key.C && it.isMetaPressed && !it.isCtrlPressed })
    }

    @Test
    fun fr5_every_command_stands_for_its_own_key_and_other_platforms_hold_control() {
        val keys = mapOf(
            TextCommand.Cut to Key.X,
            TextCommand.Copy to Key.C,
            TextCommand.Paste to Key.V,
            TextCommand.SelectAll to Key.A,
        )
        for ((command, key) in keys) {
            val sent = ArrayList<KeyEvent>()
            command.perform(commandKey = false) { sent.add(it) }
            assertTrue(sent.all { it.key == key && it.isCtrlPressed && !it.isMetaPressed }, "$command")
        }
    }

    @Test
    fun fr5_the_description_the_native_window_builds_from_round_trips_the_rows() {
        val spec = textMenuSpec(textContextMenu(clipboardHasText = true))
        assertEquals("1\tCut\n2\tCopy\n3\tPaste\n-\n4\tSelect All", spec)
        for (command in TextCommand.values()) {
            assertEquals(command, TextCommand.fromId(command.id))
        }
        assertNull(TextCommand.fromId(99), "a number nobody sent is not a command")
    }
}
