package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ZoomShortcut

// The keys that zoom a window: Command on macOS and Control on Windows and Linux, with plus
// (or the equals key it shares a key with), minus, or zero. The keypad's plus, minus and
// zero count too.
//
// Read from the character the key types, so the shortcut follows the reader's keyboard
// layout rather than a key's position. Each desktop records a key press its own way, so
// each has its own reading here, and all of them come to [zoomShortcut].
//
// Shared by the desktop windows through a symlink. Which one a press is, is decided here;
// whether it zooms is decided after the scene has had the key, because a key the
// application consumed is the application's.

/**
 * Which zoom shortcut a key press is, or null when it is not one.
 *
 * [primary] is the platform's command modifier (Command on macOS, Control elsewhere).
 * [other] is any modifier besides it and Shift: Control or Option with Command on macOS,
 * Alt or the logo key with Control elsewhere. Those combinations are something else the
 * application may have bound. [keyCode] is consulted only for a press that carried no
 * character, and only on macOS, where the virtual key codes are the US layout's positions.
 */
fun zoomShortcut(character: Int, primary: Boolean, other: Boolean, keyCode: Int = -1): ZoomShortcut? {
    if (!primary || other) return null
    return when (character) {
        '='.code, '+'.code -> ZoomShortcut.In
        '-'.code, '_'.code -> ZoomShortcut.Out
        '0'.code -> ZoomShortcut.Reset
        0 -> when (keyCode) {
            MAC_KEY_EQUAL, MAC_KEY_KEYPAD_PLUS -> ZoomShortcut.In
            MAC_KEY_MINUS, MAC_KEY_KEYPAD_MINUS -> ZoomShortcut.Out
            MAC_KEY_ZERO, MAC_KEY_KEYPAD_ZERO -> ZoomShortcut.Reset
            else -> null
        }
        else -> null
    }
}

/**
 * A key press as macOS describes it: its character, its virtual key code, and the
 * modifier flags of `NSEvent`. Command is the zoom modifier.
 */
fun macZoomShortcut(character: Int, keyCode: Int, modifierFlags: Long): ZoomShortcut? =
    zoomShortcut(
        character = character,
        primary = modifierFlags and FLAG_COMMAND != 0L,
        other = modifierFlags and (FLAG_CONTROL or FLAG_OPTION) != 0L,
        keyCode = keyCode,
    )

/**
 * A key press as the X11 windows record it: the character, and the modifier word in the
 * same bit positions macOS uses, which is what both X11 windows translate the server's
 * state into. Control is the zoom modifier.
 */
fun x11ZoomShortcut(character: Int, modifiers: Long): ZoomShortcut? =
    zoomShortcut(
        character = character,
        primary = modifiers and FLAG_CONTROL != 0L,
        other = modifiers and (FLAG_OPTION or FLAG_COMMAND) != 0L,
    )

/**
 * A key press as the Win32 window records it: the character the key carries unshifted, and
 * its own modifier word (1 Shift, 2 Control, 4 Alt, 8 the Windows key). Control is the zoom
 * modifier.
 */
fun win32ZoomShortcut(character: Int, modifiers: Int): ZoomShortcut? =
    zoomShortcut(
        character = character,
        primary = modifiers and WIN32_CONTROL != 0,
        other = modifiers and (WIN32_ALT or WIN32_LOGO) != 0,
    )

// From NSEvent.h. The bits a modifier flag word carries; the X11 windows use the same.
private const val FLAG_CONTROL = 1L shl 18
private const val FLAG_OPTION = 1L shl 19
private const val FLAG_COMMAND = 1L shl 20

private const val WIN32_CONTROL = 2
private const val WIN32_ALT = 4
private const val WIN32_LOGO = 8

// Virtual key codes from the Carbon `Events.h`, which a key press on macOS carries whatever
// the layout.
private const val MAC_KEY_EQUAL = 0x18
private const val MAC_KEY_MINUS = 0x1B
private const val MAC_KEY_ZERO = 0x1D
private const val MAC_KEY_KEYPAD_PLUS = 0x45
private const val MAC_KEY_KEYPAD_MINUS = 0x4E
private const val MAC_KEY_KEYPAD_ZERO = 0x52
