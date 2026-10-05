@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import org.thisisthepy.compose.window.WindowEvent

// What a window of our own heard, and how it reaches a scene.
//
// One file for all three desktops, and nothing in it names any of them. Each window reads
// its own display server and fills in these fields; turning a filled-in record into
// something Compose understands is written once, here, so a window on a platform nobody
// working on it can run behaves the same way as the one that was tested.
//
// The event record itself is the window module's `WindowEvent`. This file is apart from
// the windows because those name a platform in every line and this names none: no AppKit,
// no Win32, no Xlib and nothing of GraalVM, which is what lets the Kotlin/Native windows
// compile it through a symlink.

/**
 * Hands one thing the window heard to the scene.
 *
 * Shared by every desktop for the same reason the scene's content is. They all record an
 * event into the same fields, so turning one into something Compose understands is
 * written once.
 *
 * The pointer's place arrives from the top left of the content, in whatever unit that
 * platform's scene measures in, so nothing is converted here beyond naming which kind of
 * event it was.
 */
internal fun ComposeScene.receive(event: WindowEvent, win32: Boolean = false) {
    when (event.kind) {
        // Built from parts rather than from a platform event. The toolkit's own key
        // event is what the supported path converts, and there is none here to convert.
        WindowEvent.KEY_DOWN, WindowEvent.KEY_UP -> {
            val type = if (event.kind == WindowEvent.KEY_DOWN) {
                KeyEventType.KeyDown
            } else {
                KeyEventType.KeyUp
            }
            val key = if (win32) {
                KeyEvent(
                    key = win32ComposeKey(event.keyCode),
                    type = type,
                    codePoint = event.codePoint,
                    isAltPressed = event.modifiers and 4 != 0,
                    isCtrlPressed = event.modifiers and 2 != 0,
                    isMetaPressed = event.modifiers and 8 != 0,
                    isShiftPressed = event.modifiers and 1 != 0,
                )
            } else {
                // The modifiers are AppKit's flag word, which the X11 window fills in
                // with the same bits.
                macKeyEvent(event.keyCode, event.modifiers.toLong() and 0xFFFFFFFFL, type, event.codePoint)
            }
            val consumed = sendKeyEvent(key)
            if (!win32) KeyLog.compose(key, consumed)
        }

        WindowEvent.EDIT_COMMAND -> {
            val keys = editingKeyEvents(event.text)
            KeyLog.command(event.text, keys != null)
            keys?.forEach { sendKeyEvent(it) }
        }

        WindowEvent.POINTER_MOVE -> sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(event.x, event.y),
            buttons = PointerButtons(isPrimaryPressed = event.buttons and 1 != 0),
        )

        WindowEvent.POINTER_DOWN -> sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(event.x, event.y),
            button = pressedButton(event),
            buttons = PointerButtons(
                isPrimaryPressed = !event.isSecondary,
                isSecondaryPressed = event.isSecondary,
            ),
        )

        WindowEvent.POINTER_UP -> sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(event.x, event.y),
            button = pressedButton(event),
            buttons = PointerButtons(isPrimaryPressed = false),
        )

        // The wheel's travel arrives where a position usually is, because a scroll
        // happens wherever the pointer already was.
        WindowEvent.SCROLL -> sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = Offset.Zero,
            scrollDelta = Offset(event.x, event.y),
        )
    }
}


private val WindowEvent.isSecondary: Boolean
    get() = buttons and WindowEvent.SECONDARY_BUTTON != 0

private fun pressedButton(event: WindowEvent): PointerButton =
    if (event.isSecondary) PointerButton.Secondary else PointerButton.Primary

internal fun win32ComposeKey(virtualKey: Int): Key = when (virtualKey) {
    0x0D -> Key.Enter
    0x09 -> Key.Tab
    0x20 -> Key.Spacebar
    0x08 -> Key.Backspace
    0x1B -> Key.Escape
    0x2E -> Key.Delete
    0x25 -> Key.DirectionLeft
    0x27 -> Key.DirectionRight
    0x28 -> Key.DirectionDown
    0x26 -> Key.DirectionUp
    0x24 -> Key.MoveHome
    0x23 -> Key.MoveEnd
    0x21 -> Key.PageUp
    0x22 -> Key.PageDown
    else -> Key.Unknown
}

/**
 * Puts what the input method produced into the field that asked to be typed into.
 *
 * Text does not arrive in Compose through key events. A focused field opens a session and
 * waits to be handed text, and what hands it over is the input method: `insertText` for a
 * letter that is finished and `setMarkedText` while a syllable is still being built.
 *
 * The keys themselves went to the scene already and are read there as keys: arrows, Enter
 * and backspace. Nothing is committed from a key's character, because a key that types
 * one has already produced it through the path above and doing both would type it twice.
 */
internal fun NativeTextInput.receive(event: WindowEvent, macos: Boolean = false) {
    if (macos && event.kind == WindowEvent.TEXT_COMMIT) {
        // AppKit's input methods can hand over a Control letter's own character as text,
        // and a field that takes it draws a box. Dropped here, as the Kotlin/Native window
        // drops it in its `insertText`.
        val inserted = insertableText(event.text)
        KeyLog.insertText(event.text, inserted)
        if (isActive && inserted.isNotEmpty()) commit(inserted)
        return
    }
    if (!isActive) return
    when (event.kind) {
        WindowEvent.TEXT_COMMIT -> commit(event.text)
        WindowEvent.TEXT_COMPOSE -> compose(event.text)
    }
}

