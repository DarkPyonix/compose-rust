@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import org.thisisthepy.compose.window.TextScale
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

// Where the pointer last was in each scene, so a scroll lands there. Kept per scene because
// a scroll in a second window before any move in it must not land at the first window's
// pointer. A plain map, because this file is compiled by Kotlin/Native as well, which has no
// weak map: each window takes its scene out with `forgetPointer` when it closes the scene.
private val lastPointerPositions = mutableMapOf<ComposeScene, Offset>()

/**
 * Drops what this file remembers about the scene. A window calls it as it closes the scene,
 * so a closed scene is not kept alive by the pointer's last place.
 */
internal fun ComposeScene.forgetPointer() {
    lastPointerPositions.remove(this)
}

/**
 * Hands one thing the window heard to the scene. Answers whether a key was consumed by
 * something in the scene, which is how a window knows a zoom shortcut was the application's
 * to keep.
 *
 * Shared by every desktop for the same reason the scene's content is. They all record an
 * event into the same fields, so turning one into something Compose understands is
 * written once.
 *
 * The pointer's place arrives from the top left of the content, in whatever unit that
 * platform's scene measures in, so nothing is converted here beyond naming which kind of
 * event it was.
 */
internal fun ComposeScene.receive(event: WindowEvent, win32: Boolean = false): Boolean {
    var keyConsumed = false
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
            if (event.kind == WindowEvent.KEY_DOWN) keyConsumed = consumed
            if (!win32) KeyLog.compose(key, consumed)
        }

        WindowEvent.EDIT_COMMAND -> {
            val keys = editingKeyEvents(event.text)
            KeyLog.command(event.text, keys != null)
            keys?.forEach { sendKeyEvent(it) }
        }

        WindowEvent.POINTER_MOVE -> {
            val pos = Offset(event.x, event.y)
            lastPointerPositions[this] = pos
            sendPointerEvent(
                eventType = PointerEventType.Move,
                position = pos,
                buttons = pointerButtonsOf(event.buttons),
                keyboardModifiers = keyboardModifiersOf(event.modifiers, win32),
            )
        }

        WindowEvent.POINTER_DOWN -> {
            val pos = Offset(event.x, event.y)
            lastPointerPositions[this] = pos
            val button = pressedButton(event)
            val mask = if (event.buttons != 0) event.buttons else 1
            sendPointerEvent(
                eventType = PointerEventType.Press,
                position = pos,
                button = button,
                buttons = pointerButtonsOf(mask),
                keyboardModifiers = keyboardModifiersOf(event.modifiers, win32),
            )
        }

        WindowEvent.POINTER_UP -> {
            val pos = Offset(event.x, event.y)
            lastPointerPositions[this] = pos
            sendPointerEvent(
                eventType = PointerEventType.Release,
                position = pos,
                button = pressedButton(event),
                buttons = PointerButtons(isPrimaryPressed = false),
                keyboardModifiers = keyboardModifiersOf(event.modifiers, win32),
            )
        }

        // The wheel's travel arrives where a position usually is, because a scroll
        // happens wherever the pointer already was in this window.
        WindowEvent.SCROLL -> sendPointerEvent(
            eventType = PointerEventType.Scroll,
            position = lastPointerPositions[this] ?: Offset.Zero,
            scrollDelta = Offset(event.x, event.y),
            keyboardModifiers = keyboardModifiersOf(event.modifiers, win32),
        )
    }
    return keyConsumed
}


/**
 * The keyboard modifiers a window recorded with a pointer event. The Windows window
 * records them as 1 Shift, 2 Ctrl, 4 Alt, 8 Win, as it does for keys; the X11 window and AppKit
 * as AppKit's flag bits (Shift 1 shl 17, Control 18, Option 19, Command 20).
 */
internal fun keyboardModifiersOf(modifiers: Int, win32: Boolean): PointerKeyboardModifiers =
    if (win32) {
        PointerKeyboardModifiers(
            isShiftPressed = modifiers and 1 != 0,
            isCtrlPressed = modifiers and 2 != 0,
            isAltPressed = modifiers and 4 != 0,
            isMetaPressed = modifiers and 8 != 0,
        )
    } else {
        PointerKeyboardModifiers(
            isShiftPressed = modifiers and (1 shl 17) != 0,
            isCtrlPressed = modifiers and (1 shl 18) != 0,
            isAltPressed = modifiers and (1 shl 19) != 0,
            isMetaPressed = modifiers and (1 shl 20) != 0,
        )
    }

internal fun pointerButtonsOf(mask: Int): PointerButtons = PointerButtons(
    isPrimaryPressed = mask and 1 != 0,
    isSecondaryPressed = mask and WindowEvent.SECONDARY_BUTTON != 0,
    isTertiaryPressed = mask and 4 != 0,
    isBackPressed = mask and 8 != 0,
    isForwardPressed = mask and 16 != 0,
)

private fun pressedButton(event: WindowEvent): PointerButton = when {
    event.buttons and WindowEvent.SECONDARY_BUTTON != 0 -> PointerButton.Secondary
    event.buttons and 4 != 0 -> PointerButton.Tertiary
    event.buttons and 8 != 0 -> PointerButton.Back
    event.buttons and 16 != 0 -> PointerButton.Forward
    else -> PointerButton.Primary
}

internal fun win32ComposeKey(virtualKey: Int): Key = when (virtualKey) {
    // The letters and digits, which are their own numbers in a virtual key. Without them
    // control with C, V, X or Z reaches Compose as a key it does not know.
    0x41 -> Key.A
    0x42 -> Key.B
    0x43 -> Key.C
    0x44 -> Key.D
    0x45 -> Key.E
    0x46 -> Key.F
    0x47 -> Key.G
    0x48 -> Key.H
    0x49 -> Key.I
    0x4A -> Key.J
    0x4B -> Key.K
    0x4C -> Key.L
    0x4D -> Key.M
    0x4E -> Key.N
    0x4F -> Key.O
    0x50 -> Key.P
    0x51 -> Key.Q
    0x52 -> Key.R
    0x53 -> Key.S
    0x54 -> Key.T
    0x55 -> Key.U
    0x56 -> Key.V
    0x57 -> Key.W
    0x58 -> Key.X
    0x59 -> Key.Y
    0x5A -> Key.Z
    0x30 -> Key.Zero
    0x31 -> Key.One
    0x32 -> Key.Two
    0x33 -> Key.Three
    0x34 -> Key.Four
    0x35 -> Key.Five
    0x36 -> Key.Six
    0x37 -> Key.Seven
    0x38 -> Key.Eight
    0x39 -> Key.Nine
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
    // VK_F1 to VK_F12 run in order, and VK_APPS is the context-menu key.
    0x70 -> Key.F1
    0x71 -> Key.F2
    0x72 -> Key.F3
    0x73 -> Key.F4
    0x74 -> Key.F5
    0x75 -> Key.F6
    0x76 -> Key.F7
    0x77 -> Key.F8
    0x78 -> Key.F9
    0x79 -> Key.F10
    0x7A -> Key.F11
    0x7B -> Key.F12
    0x5D -> Key.Menu
    // The punctuation keys, named by where they sit on a US layout (VK_OEM_*), as the HID
    // usage names them too. What they type on another layout arrives as text.
    0xBA -> Key.Semicolon
    0xBB -> Key.Equals
    0xBC -> Key.Comma
    0xBD -> Key.Minus
    0xBE -> Key.Period
    0xBF -> Key.Slash
    0xC0 -> Key.Grave
    0xDB -> Key.LeftBracket
    0xDC -> Key.Backslash
    0xDD -> Key.RightBracket
    0xDE -> Key.Apostrophe
    0x2D -> Key.Insert
    0x14 -> Key.CapsLock
    // WM_KEYDOWN names a modifier without its side (VK_SHIFT, VK_CONTROL, VK_MENU); the left
    // one stands for it. The sided codes are mapped for a window that hands them over.
    0x10 -> Key.ShiftLeft
    0x11 -> Key.CtrlLeft
    0x12 -> Key.AltLeft
    0xA0 -> Key.ShiftLeft
    0xA1 -> Key.ShiftRight
    0xA2 -> Key.CtrlLeft
    0xA3 -> Key.CtrlRight
    0xA4 -> Key.AltLeft
    0xA5 -> Key.AltRight
    0x5B -> Key.MetaLeft
    0x5C -> Key.MetaRight
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


/**
 * The operating system's text size one window draws at, which its scene gets as
 * `Density.fontScale`.
 *
 * Starts from what the window module read when the window opened and follows the
 * [WindowEvent.TEXT_SCALE_CHANGED] events it queues after that, so a reader who moves the
 * system's text size slider sees the text grow without restarting. The display's scale is
 * not part of it: [density] puts the two together where the window measures its buffer.
 * A platform with no such setting (macOS) reports 1 and never sends the event.
 */
internal class WindowTextScale(initial: Float) {

    /** The multiplier, 1 when the reader has not changed the system's setting. */
    var value: Float = TextScale.clamp(initial)
        private set

    /**
     * Takes the new text size from [event] when it is a text size change. Answers whether
     * the size changed, which is a frame to draw: nothing in the scene has invalidated, but
     * all of its text is about to be laid out again.
     */
    fun receive(event: WindowEvent): Boolean {
        if (!event.isTextScaleChanged) return false
        val next = TextScale.clamp(event.textScale)
        if (next == value) return false
        value = next
        return true
    }

    /** The Density a scene on a display of [scale] pixels per point is drawn at. */
    fun density(scale: Float): Density = Density(scale, value)
}

/**
 * The display scale one window is drawn at, followed from whatever the window last measured.
 *
 * A window dragged from a 1x screen to a 2x one keeps its size in points but changes its
 * backing scale, and nothing about that is a resize: AppKit may report it on its own, with
 * or without a size change, or only as a new measurement. So the window is measured on every
 * resize and every frame, and each measurement goes through [follow], which answers the new
 * Density and pixel size when the scale moved and null when it did not. The pixel size comes
 * from the same measurement, so the window's size in dp is what it was.
 */
internal class WindowBackingScale(initial: Float, private val textScale: WindowTextScale) {

    /** The scale the scene was last given. */
    var scale: Float = initial
        private set

    /** The Density a scene at the current scale is drawn at. */
    fun density(): Density = textScale.density(scale)

    /** What the scene is told when [follow] sees a new scale. */
    data class Change(val density: Density, val size: androidx.compose.ui.unit.IntSize)

    /**
     * Takes one measurement of the window. Answers the scene's new Density (with the system's
     * text size as its font scale) and its new size in pixels when the scale changed, which is
     * also a frame to draw; null when it did not.
     */
    fun follow(width: Int, height: Int, measuredScale: Float): Change? {
        if (measuredScale <= 0f || measuredScale == scale) return null
        scale = measuredScale
        return Change(textScale.density(measuredScale), androidx.compose.ui.unit.IntSize(width, height))
    }
}
