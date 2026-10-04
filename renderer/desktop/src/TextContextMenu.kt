@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType

// The text context menu of a window that has no toolkit, as data.
//
// One model for every window that draws its own menu: the GraalVM AppKit window shows it
// from C and the Kotlin/Native macOS window shows it from Kotlin, and both read the same
// list, the same labels, the same order and the same meaning for "Paste is greyed out".
// Nothing here names a platform, so it compiles on both through the symlink.
//
// An item acts by pressing the shortcut it is named after. The editor is Compose's and the
// key is the way in that every window already has, which also keeps an input method's
// composition where it was: no text is moved by the menu, and a key goes to the field that
// is focused exactly as it would from the keyboard.

/** What the menu offers, in the order the platform puts them. */
enum class TextCommand(val id: Int, val title: String, val key: Key) {
    Cut(1, "Cut", Key.X),
    Copy(2, "Copy", Key.C),
    Paste(3, "Paste", Key.V),
    SelectAll(4, "Select All", Key.A),
    ;

    companion object {
        /** The command a window reported by number, or null for a number nobody sent. */
        fun fromId(id: Int): TextCommand? = values().firstOrNull { it.id == id }
    }
}

/** One row of the menu. A null [command] is the line between the groups. */
data class TextMenuEntry(val command: TextCommand?, val enabled: Boolean = true)

/**
 * The rows of the menu.
 *
 * Paste is the one that is known to be pointless: with no text on the clipboard it would do
 * nothing, so it is greyed out. Cut and Copy cannot be judged from outside the field, which
 * owns the selection, and a Copy with nothing selected is ignored by the field.
 */
fun textContextMenu(clipboardHasText: Boolean): List<TextMenuEntry> = listOf(
    TextMenuEntry(TextCommand.Cut),
    TextMenuEntry(TextCommand.Copy),
    TextMenuEntry(TextCommand.Paste, enabled = clipboardHasText),
    TextMenuEntry(null),
    TextMenuEntry(TextCommand.SelectAll),
)

/** The key presses that stand for [command]: the key down and the key up, with the modifier held. */
internal fun TextCommand.keyPresses(commandKey: Boolean = true): List<KeyEvent> =
    listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).map { type ->
        KeyEvent(
            key = key,
            type = type,
            codePoint = 0,
            isAltPressed = false,
            // The shortcut modifier is Command on macOS and Control elsewhere.
            isCtrlPressed = !commandKey,
            isMetaPressed = commandKey,
            isShiftPressed = false,
        )
    }

/** Carries out [command] by sending its key presses to [send], which is the scene's key entry. */
internal fun TextCommand.perform(commandKey: Boolean = true, send: (KeyEvent) -> Unit) {
    for (press in keyPresses(commandKey)) send(press)
}

/**
 * The menu as a line of text a native window can build from: one row per line, the
 * command number and its title with a tab between them, and a single dash for the line
 * between groups.
 */
fun textMenuSpec(entries: List<TextMenuEntry>): String = entries.joinToString("\n") {
    it.command?.let { command -> "${command.id}\t${command.title}" } ?: "-"
}
