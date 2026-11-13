@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.lang.System

// How a key pressed in an AppKit window reaches Compose, written once for both macOS
// windows: the one the native image opens through C and the Kotlin/Native one. Each of
// them reads its `NSEvent` and asks the functions here what to do with it, so the two
// cannot answer the same key differently.

/** The bits of `NSEvent.modifierFlags`, from NSEvent.h. */
internal object MacModifiers {
    const val SHIFT: Long = 1L shl 17
    const val CONTROL: Long = 1L shl 18
    const val OPTION: Long = 1L shl 19
    const val COMMAND: Long = 1L shl 20
}

/**
 * The Compose key event an AppKit key event is.
 *
 * Built from parts rather than converted, because what converts a platform key event is
 * internal to Compose. The modifiers are what Compose's macOS key mapping reads: Command
 * is `isMetaPressed`, and copy, paste, select all and undo are matched against it.
 */
internal fun macKeyEvent(
    keyCode: Int,
    modifierFlags: Long,
    type: KeyEventType,
    codePoint: Int = 0,
): KeyEvent = KeyEvent(
    key = composeKey(keyCode),
    type = type,
    codePoint = codePoint,
    isAltPressed = modifierFlags and MacModifiers.OPTION != 0L,
    isCtrlPressed = modifierFlags and MacModifiers.CONTROL != 0L,
    isMetaPressed = modifierFlags and MacModifiers.COMMAND != 0L,
    isShiftPressed = modifierFlags and MacModifiers.SHIFT != 0L,
)

/**
 * Whether the input method is shown this key as well as the scene.
 *
 * Not when Command is held. A Command key is a shortcut and never types anything, and an
 * input method shown one does things nobody asked for: one that is composing commits the
 * syllable, and one that is not can answer with the bare letter.
 */
internal fun reachesInputMethod(modifierFlags: Long): Boolean =
    modifierFlags and MacModifiers.COMMAND == 0L

/**
 * Whether this key is one of the text editing shortcuts: Command with A, C, V, X or Z,
 * and Shift as well for redo.
 *
 * These are claimed by the view before the menu bar sees them. The menu bar has an Edit
 * menu holding the same keys, and AppKit offers a key equivalent to the menu bar first:
 * the item's action goes looking for a responder that implements `selectAll:`, and
 * the key never arrives as a key. That is how Command A came to do nothing.
 */
internal fun isEditingShortcut(keyCode: Int, modifierFlags: Long): Boolean {
    if (modifierFlags and MacModifiers.COMMAND == 0L) return false
    if (modifierFlags and (MacModifiers.CONTROL or MacModifiers.OPTION) != 0L) return false
    return composeKey(keyCode) in EDITING_KEYS
}

private val EDITING_KEYS = setOf(Key.A, Key.C, Key.V, Key.X, Key.Z)

/**
 * The Compose key events an AppKit editing command stands for, press and release, or
 * null for a command this does not carry.
 *
 * AppKit names an editing action by a selector: the Edit menu sends `copy:`, a key
 * binding turns Control A into `moveToBeginningOfLine:`. The editor is Compose's, and the
 * way into it is the key that means the same thing on this platform, so each selector
 * becomes that key. Compose's macOS mapping is what decides what the key then does,
 * which keeps one table of what keys mean rather than two.
 */
internal fun editingKeyEvents(selector: String): List<KeyEvent>? {
    val (key, flags) = SELECTOR_KEYS[selector] ?: return null
    val code = MAC_KEY_CODES.getValue(key)
    return listOf(
        macKeyEvent(code, flags, KeyEventType.KeyDown),
        macKeyEvent(code, flags, KeyEventType.KeyUp),
    )
}

private val COMMAND = MacModifiers.COMMAND
private val SHIFT = MacModifiers.SHIFT
private val CONTROL = MacModifiers.CONTROL

private val SELECTOR_KEYS: Map<String, Pair<Key, Long>> = mapOf(
    "selectAll:" to (Key.A to COMMAND),
    "copy:" to (Key.C to COMMAND),
    "cut:" to (Key.X to COMMAND),
    "paste:" to (Key.V to COMMAND),
    "undo:" to (Key.Z to COMMAND),
    "redo:" to (Key.Z to (COMMAND or SHIFT)),
    // The Emacs bindings every text view on this platform has. Compose's macOS mapping
    // gives the Control letters the same meanings, so the selector becomes that letter.
    "moveToBeginningOfLine:" to (Key.A to CONTROL),
    "moveToEndOfLine:" to (Key.E to CONTROL),
    "moveToBeginningOfLineAndModifySelection:" to (Key.A to (CONTROL or SHIFT)),
    "moveToEndOfLineAndModifySelection:" to (Key.E to (CONTROL or SHIFT)),
    "moveToBeginningOfParagraph:" to (Key.A to CONTROL),
    "moveToEndOfParagraph:" to (Key.E to CONTROL),
    "deleteToEndOfParagraph:" to (Key.K to CONTROL),
    "deleteToEndOfLine:" to (Key.K to CONTROL),
    "moveUp:" to (Key.DirectionUp to 0L),
    "moveDown:" to (Key.DirectionDown to 0L),
    "moveLeft:" to (Key.DirectionLeft to 0L),
    "moveRight:" to (Key.DirectionRight to 0L),
    "deleteBackward:" to (Key.Backspace to 0L),
    "deleteForward:" to (Key.Delete to 0L),
)

/** Where each key the table above names sits on the board, which is what an event carries. */
private val MAC_KEY_CODES: Map<Key, Int> = mapOf(
    Key.A to 0x00,
    Key.C to 0x08,
    Key.E to 0x0E,
    Key.K to 0x28,
    Key.V to 0x09,
    Key.X to 0x07,
    Key.Z to 0x06,
    Key.DirectionUp to 0x7E,
    Key.DirectionDown to 0x7D,
    Key.DirectionLeft to 0x7B,
    Key.DirectionRight to 0x7C,
    Key.Backspace to 0x33,
    Key.Delete to 0x75,
)

/**
 * What of an input method's text goes into a field.
 *
 * Everything but the control characters. A Control letter has a character of its own,
 * Control A is U+0001, and an input method that has no binding for it can hand that over
 * as text. It is never text anybody meant to type: a field that took it drew a box where
 * the caret was. Line breaks and tabs are keys, and arrive as keys, so nothing is lost.
 */
internal fun insertableText(text: String): String {
    if (text.none { it.isMacControl() }) return text
    return text.filterNot { it.isMacControl() }
}

private fun Char.isMacControl(): Boolean = code < 0x20 || code == 0x7F

/**
 * A line per key, behind `DXC_KEY_LOG=1`, so that what a window heard and what Compose
 * was given can be read side by side on the machine that showed the problem.
 */
internal object KeyLog {
    val enabled: Boolean = System.getenv("DXC_KEY_LOG") == "1"

    fun platform(keyCode: Int, modifierFlags: Long, characters: String?, down: Boolean) {
        if (!enabled) return
        line(
            "nsevent ${if (down) "down" else "up"} keyCode=0x${keyCode.toString(16)} " +
                "modifiers=${modifierNames(modifierFlags)} characters=${visible(characters)}",
        )
    }

    fun compose(event: KeyEvent, consumed: Boolean? = null) {
        if (!enabled) return
        val modifiers = buildList {
            if (event.isMetaPressed) add("meta")
            if (event.isCtrlPressed) add("ctrl")
            if (event.isAltPressed) add("alt")
            if (event.isShiftPressed) add("shift")
        }.joinToString("+").ifEmpty { "none" }
        val outcome = if (consumed == null) "" else " consumed=$consumed"
        line("compose ${event.type} key=${event.key} modifiers=$modifiers$outcome")
    }

    fun insertText(text: String, inserted: String) {
        if (!enabled) return
        line("insertText ${visible(text)} inserted=${visible(inserted)}")
    }

    fun command(selector: String, handled: Boolean) {
        if (!enabled) return
        line("doCommandBySelector $selector mapped=$handled")
    }

    fun menu(message: String) {
        if (!enabled) return
        line("menu $message")
    }

    fun line(text: String) = System.err.println("compose-rust key: $text")

    private fun modifierNames(flags: Long): String = buildList {
        if (flags and MacModifiers.COMMAND != 0L) add("command")
        if (flags and MacModifiers.CONTROL != 0L) add("control")
        if (flags and MacModifiers.OPTION != 0L) add("option")
        if (flags and MacModifiers.SHIFT != 0L) add("shift")
    }.joinToString("+").ifEmpty { "none" }

    // Control characters written as their code, so that a U+0001 shows up as itself in
    // the log rather than as nothing.
    private fun visible(text: String?): String {
        if (text == null) return "null"
        return "\"" + text.map {
            if (it.isMacControl()) "\\u" + it.code.toString(16).padStart(4, '0') else it.toString()
        }.joinToString("") + "\""
    }
}
