@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dioxus.compose.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene

// What a window of our own heard, and how it reaches a scene.
//
// One file for all three desktops, and nothing in it names any of them. Each window reads
// its own display server and fills in these fields; turning a filled-in record into
// something Compose understands is written once, here, so a window on a platform nobody
// working on it can run behaves the same way as the one that was tested.
//
// Apart from the windows themselves because those name a platform in every line, and this
// names none: no AppKit, no Win32, no Xlib, and nothing of GraalVM either, which is what
// lets the Kotlin/Native windows compile it through a symlink.

/**
 * What happened in the window, as the window recorded it.
 *
 * Plain numbers rather than a platform event. What Compose is given is built from these
 * fields, so nothing of AppKit, Win32 or Xlib reaches the scene and a window on any of
 * the three fills in the same record without the scene noticing which one it was.
 */
data class WindowEvent(
    val kind: Int,
    val x: Float,
    val y: Float,
    val buttons: Int,
    val modifiers: Int,
    val keyCode: Int,
    val codePoint: Int,
    /** What an input method produced, and empty for everything that is not text. */
    val text: String,
) {

    companion object {
        const val POINTER_MOVE = 1
        const val POINTER_DOWN = 2
        const val POINTER_UP = 3
        const val SCROLL = 4
        const val KEY_DOWN = 5
        const val KEY_UP = 6
        const val TEXT_COMMIT = 7
        const val TEXT_COMPOSE = 8
        const val RESIZE = 9
        const val FILES_ENTERED = 10
        const val FILES_DROPPED = 11
    }
}

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
        WindowEvent.KEY_DOWN, WindowEvent.KEY_UP -> sendKeyEvent(
            KeyEvent(
                key = if (win32) win32ComposeKey(event.keyCode) else composeKey(event.keyCode),
                type = if (event.kind == WindowEvent.KEY_DOWN) {
                    KeyEventType.KeyDown
                } else {
                    KeyEventType.KeyUp
                },
                codePoint = event.codePoint,
                isAltPressed = event.modifiers and (if (win32) 4 else MODIFIER_OPTION) != 0,
                isCtrlPressed = event.modifiers and (if (win32) 2 else MODIFIER_CONTROL) != 0,
                isMetaPressed = event.modifiers and (if (win32) 8 else MODIFIER_COMMAND) != 0,
                isShiftPressed = event.modifiers and (if (win32) 1 else MODIFIER_SHIFT) != 0,
            ),
        )

        WindowEvent.POINTER_MOVE -> sendPointerEvent(
            eventType = PointerEventType.Move,
            position = Offset(event.x, event.y),
            buttons = PointerButtons(isPrimaryPressed = event.buttons and 1 != 0),
        )

        WindowEvent.POINTER_DOWN -> sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(event.x, event.y),
            button = PointerButton.Primary,
            buttons = PointerButtons(isPrimaryPressed = true),
        )

        WindowEvent.POINTER_UP -> sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(event.x, event.y),
            button = PointerButton.Primary,
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

// From NSEvent.h. The bits a modifier flag word carries.
private const val MODIFIER_SHIFT = 1 shl 17
private const val MODIFIER_CONTROL = 1 shl 18
private const val MODIFIER_OPTION = 1 shl 19
private const val MODIFIER_COMMAND = 1 shl 20

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
internal fun NativeTextInput.receive(event: WindowEvent) {
    if (!isActive) return
    when (event.kind) {
        WindowEvent.TEXT_COMMIT -> commit(event.text)
        WindowEvent.TEXT_COMPOSE -> compose(event.text)
    }
}

