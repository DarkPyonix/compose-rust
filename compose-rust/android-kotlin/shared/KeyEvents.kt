package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Key as ProtocolKey
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import dev.darkpyonix.composerust.runtime.EventDispatcher

/**
 * Key routing to the Host.
 *
 * The Host handler runs synchronously on this call stack and its result decides whether the
 * key is consumed, the same model as `PointerInputChange.consume()`.
 *
 * **A key event is never sent while an IME composition is in progress.** Enter during a
 * Korean composition means "commit the composition", not "submit": dispatching it would let
 * a Host handler clear the field and the syllable being composed would vanish.
 */

/**
 * The protocol key for a Compose key. The schema names Enter and the keys menus, lists and
 * overlays act on; every other key is `Other`, and the event's `code` and `text` say which
 * one it was.
 */
internal fun protocolKey(key: Key): ProtocolKey = when (key) {
    Key.Enter, Key.NumPadEnter -> ProtocolKey.Enter
    Key.Escape -> ProtocolKey.Escape
    Key.Tab -> ProtocolKey.Tab
    Key.Backspace -> ProtocolKey.Backspace
    Key.Delete -> ProtocolKey.Delete
    Key.DirectionUp -> ProtocolKey.ArrowUp
    Key.DirectionDown -> ProtocolKey.ArrowDown
    Key.DirectionLeft -> ProtocolKey.ArrowLeft
    Key.DirectionRight -> ProtocolKey.ArrowRight
    Key.MoveHome -> ProtocolKey.Home
    Key.MoveEnd -> ProtocolKey.End
    Key.PageUp -> ProtocolKey.PageUp
    Key.PageDown -> ProtocolKey.PageDown
    else -> ProtocolKey.Other
}

/** What a physical key is called on the wire: its USB HID usage and its DOM `key` string. */
internal class KeyName(val code: Int, val text: String)

private val namedKeys: Map<Key, KeyName> = buildMap {
    // USB HID usage IDs of the keyboard page (0x07); the DOM `key` value is the second word.
    fun name(key: Key, code: Int, text: String) {
        put(key, KeyName(code, text))
    }
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { index, key -> name(key, 0x04 + index, ('a' + index).toString()) }
    val digits = listOf(
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
        Key.Zero,
    )
    digits.forEachIndexed { index, key -> name(key, 0x1E + index, ((index + 1) % 10).toString()) }
    val functions = listOf(
        Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11,
        Key.F12,
    )
    functions.forEachIndexed { index, key -> name(key, 0x3A + index, "F${index + 1}") }
    name(Key.Enter, 0x28, "Enter")
    name(Key.Escape, 0x29, "Escape")
    name(Key.Backspace, 0x2A, "Backspace")
    name(Key.Tab, 0x2B, "Tab")
    name(Key.Spacebar, 0x2C, " ")
    name(Key.Minus, 0x2D, "-")
    name(Key.Equals, 0x2E, "=")
    name(Key.LeftBracket, 0x2F, "[")
    name(Key.RightBracket, 0x30, "]")
    name(Key.Backslash, 0x31, "\\")
    name(Key.Semicolon, 0x33, ";")
    name(Key.Apostrophe, 0x34, "'")
    name(Key.Grave, 0x35, "`")
    name(Key.Comma, 0x36, ",")
    name(Key.Period, 0x37, ".")
    name(Key.Slash, 0x38, "/")
    name(Key.CapsLock, 0x39, "CapsLock")
    name(Key.Insert, 0x49, "Insert")
    name(Key.MoveHome, 0x4A, "Home")
    name(Key.PageUp, 0x4B, "PageUp")
    name(Key.Delete, 0x4C, "Delete")
    name(Key.MoveEnd, 0x4D, "End")
    name(Key.PageDown, 0x4E, "PageDown")
    name(Key.DirectionRight, 0x4F, "ArrowRight")
    name(Key.DirectionLeft, 0x50, "ArrowLeft")
    name(Key.DirectionDown, 0x51, "ArrowDown")
    name(Key.DirectionUp, 0x52, "ArrowUp")
    name(Key.NumPadEnter, 0x58, "Enter")
    name(Key.CtrlLeft, 0xE0, "Control")
    name(Key.ShiftLeft, 0xE1, "Shift")
    name(Key.AltLeft, 0xE2, "Alt")
    name(Key.MetaLeft, 0xE3, "Meta")
    name(Key.CtrlRight, 0xE4, "Control")
    name(Key.ShiftRight, 0xE5, "Shift")
    name(Key.AltRight, 0xE6, "Alt")
    name(Key.MetaRight, 0xE7, "Meta")
}

/**
 * The HID usage and DOM `key` string of a key press. A letter is upper case while Shift is
 * held, as the DOM reports it. A key the table does not know keeps a zero usage and the
 * character it typed, or `"Unidentified"` when it typed none.
 */
internal fun keyName(key: Key, shift: Boolean, codePoint: Int = 0): KeyName {
    val known = namedKeys[key]
    if (known != null) {
        val isLetter = known.text.length == 1 && known.text[0] in 'a'..'z'
        return if (isLetter && shift) KeyName(known.code, known.text.uppercase()) else known
    }
    val typed = if (codePoint > 0x1F && codePoint != 0x7F) {
        codePointToString(codePoint)
    } else {
        "Unidentified"
    }
    return KeyName(0, typed)
}

private fun codePointToString(codePoint: Int): String =
    if (codePoint < 0x10000) {
        codePoint.toChar().toString()
    } else {
        val offset = codePoint - 0x10000
        charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar())
            .concatToString()
    }

/**
 * Which keys are held down at one place that listens for keys.
 *
 * A key-down for a key that has not been released is the platform repeating it. Compose's
 * key event carries no repeat flag, so the renderer tells repeats from first presses by
 * watching the releases.
 */
internal class HeldKeys {
    private val held = HashSet<Long>()

    /** Notes one event and says whether it is a key-down repeating a held key. */
    fun observe(type: KeyEventType, key: Key): Boolean = when (type) {
        KeyEventType.KeyDown -> !held.add(key.keyCode)
        KeyEventType.KeyUp -> {
            held.remove(key.keyCode)
            false
        }
        else -> false
    }

    fun clear() = held.clear()
}

/**
 * Whether a key press may be offered to the Host at all.
 *
 * Key-down only (a Host handler is `on_key_down`) and **never while an IME composition is
 * in progress**. Kept free of Compose types so the rule that protects a Korean composition
 * can be asserted directly, the way [shouldSubmitOnEnter] is.
 */
internal fun shouldDispatchKeyDown(
    composing: Boolean,
    isKeyDown: Boolean,
    key: ProtocolKey?,
): Boolean = !composing && isKeyDown && key != null

/** [shouldDispatchKeyDown] applied to a Compose key event. */
internal fun isDispatchableKeyDown(event: ComposeKeyEvent, composing: Boolean): Boolean =
    shouldDispatchKeyDown(
        composing = composing,
        isKeyDown = event.type == KeyEventType.KeyDown,
        key = protocolKey(event.key),
    )

/** Whether the press is an Enter key-down outside a composition, the one that can submit. */
internal fun isEnterKeyDown(event: ComposeKeyEvent, composing: Boolean): Boolean =
    isDispatchableKeyDown(event, composing) && protocolKey(event.key) == ProtocolKey.Enter

/**
 * Sends one key press to the Host and returns its consumption result.
 *
 * Returns false without calling the Host whenever [isDispatchableKeyDown] says no, so a
 * composition keystroke never reaches Rust. [held] sees every event, key-ups included, so
 * a repeated key-down is marked as one.
 */
internal fun dispatchKeyDown(
    nodeId: Int,
    handlerId: Long,
    event: ComposeKeyEvent,
    composing: Boolean,
    dispatcher: EventDispatcher,
    held: HeldKeys = HeldKeys(),
): Boolean {
    val repeat = held.observe(event.type, event.key)
    if (!isDispatchableKeyDown(event, composing)) return false
    val name = keyName(event.key, event.isShiftPressed, event.utf16CodePoint)
    return dispatcher.dispatch(
        HostEvent.KeyDown(
            nodeId = nodeId,
            handlerId = handlerId,
            key = protocolKey(event.key),
            shiftKey = event.isShiftPressed,
            ctrlKey = event.isCtrlPressed,
            altKey = event.isAltPressed,
            metaKey = event.isMetaPressed,
            repeat = repeat,
            code = name.code,
            text = name.text,
        ),
    )
}

/**
 * Whether a key release may be offered to the Host at all.
 *
 * Key-up only, and never while an IME composition is in progress.
 */
internal fun shouldDispatchKeyUp(
    composing: Boolean,
    isKeyUp: Boolean,
    key: ProtocolKey?,
): Boolean = !composing && isKeyUp && key != null

/** [shouldDispatchKeyUp] applied to a Compose key event. */
internal fun isDispatchableKeyUp(event: ComposeKeyEvent, composing: Boolean): Boolean =
    shouldDispatchKeyUp(
        composing = composing,
        isKeyUp = event.type == KeyEventType.KeyUp,
        key = protocolKey(event.key),
    )

/**
 * Sends one key release to the Host and returns its consumption result.
 *
 * Returns false without calling the Host whenever [isDispatchableKeyUp] says no, so a
 * composition keystroke never reaches Rust.
 */
internal fun dispatchKeyUp(
    nodeId: Int,
    handlerId: Long,
    event: ComposeKeyEvent,
    composing: Boolean,
    dispatcher: EventDispatcher,
): Boolean {
    if (!isDispatchableKeyUp(event, composing)) return false
    val name = keyName(event.key, event.isShiftPressed, event.utf16CodePoint)
    return dispatcher.dispatch(
        HostEvent.KeyUp(
            nodeId = nodeId,
            handlerId = handlerId,
            key = protocolKey(event.key),
            shiftKey = event.isShiftPressed,
            ctrlKey = event.isCtrlPressed,
            altKey = event.isAltPressed,
            metaKey = event.isMetaPressed,
            repeat = false,
            code = name.code,
            text = name.text,
        ),
    )
}

/**
 * `Modifier.onKeyEvent` for a widget that owns no text composition.
 *
 * `onKeyEvent` rather than `onPreviewKeyEvent`: preview is for interception, and a widget
 * without an editor has nothing to intercept. The TextField is the exception and wires its
 * own preview handler, because Enter has to be answered before the editor inserts a newline.
 */
internal fun Modifier.hostKeyEvents(
    nodeId: Int,
    keyDownHandler: Long?,
    keyUpHandler: Long?,
    dispatcher: EventDispatcher,
): Modifier = composed {
    val held = remember { HeldKeys() }
    onKeyEvent { event ->
        // The key-down dispatch sees every event, key-ups included, so it can tell a repeat
        // from a first press; it sends nothing for an event that is not a key-down.
        val consumed = keyDownHandler != null &&
            dispatchKeyDown(nodeId, keyDownHandler, event, composing = false, dispatcher = dispatcher, held = held)
        when {
            consumed -> true
            keyUpHandler != null && isDispatchableKeyUp(event, composing = false) ->
                dispatchKeyUp(nodeId, keyUpHandler, event, composing = false, dispatcher = dispatcher)
            else -> false
        }
    }
}

internal fun Modifier.hostKeyEvents(
    nodeId: Int,
    handlerId: Long,
    dispatcher: EventDispatcher,
): Modifier = hostKeyEvents(
    nodeId = nodeId,
    keyDownHandler = handlerId,
    keyUpHandler = null,
    dispatcher = dispatcher,
)
