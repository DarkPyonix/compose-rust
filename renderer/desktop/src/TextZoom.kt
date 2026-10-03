package dev.darkpyonix.composerust.ui.platform

// The text size control macOS does not have.
//
// Windows has a text size in its accessibility settings and Linux desktops have a text
// scaling factor, and a window reads those. macOS offers nothing an application can read:
// what the system calls text size is per application, in the few of Apple's that have it,
// and the reader asks each one with Command and plus or minus. So this window answers
// those keys the way they are answered everywhere else on the platform, by zooming its own
// text, and that zoom is the font scale the window hands its scene.
//
// Shared by both macOS windows (the native image's and the Kotlin/Native one) through a
// symlink, so the keys and the steps are the same whichever one an application runs.

/** What one of the zoom shortcuts asks for. */
enum class ZoomShortcut { In, Out, Reset }

/**
 * Which zoom shortcut a key press is, or null when it is not one.
 *
 * Command with plus (or the equals key it shares a key with), Command with minus, and
 * Command with zero. Read from the character the key types, so the shortcut follows the
 * reader's keyboard layout rather than a key's position; [keyCode] is the fallback for a
 * press that carried no character, using the US layout's positions and the keypad.
 *
 * Only with Command alone, apart from Shift: Control or Option with the same keys is
 * something else the application may have bound.
 */
fun zoomShortcut(
    character: Int,
    keyCode: Int,
    command: Boolean,
    control: Boolean,
    option: Boolean,
): ZoomShortcut? {
    if (!command || control || option) return null
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
 * [zoomShortcut] for a key press as macOS describes it: its character, its virtual key
 * code, and the modifier flags of `NSEvent`.
 */
fun macZoomShortcut(character: Int, keyCode: Int, modifierFlags: Long): ZoomShortcut? =
    zoomShortcut(
        character = character,
        keyCode = keyCode,
        command = modifierFlags and MAC_FLAG_COMMAND != 0L,
        control = modifierFlags and MAC_FLAG_CONTROL != 0L,
        option = modifierFlags and MAC_FLAG_OPTION != 0L,
    )

/**
 * The zoom a macOS window's text is drawn at, stepped by the shortcuts.
 *
 * The steps are the ones a browser takes, so a reader who knows how far two presses go in
 * one knows it here. One process has one window, so the zoom belongs to the window.
 */
class TextZoom {

    /** The font scale the window hands its scene. */
    var fontScale: Float = 1f
        private set

    /** Takes one step, and answers whether the zoom changed. */
    fun apply(shortcut: ZoomShortcut): Boolean {
        val next = when (shortcut) {
            ZoomShortcut.In -> ZOOM_STEPS.firstOrNull { it > fontScale + EPSILON } ?: fontScale
            ZoomShortcut.Out -> ZOOM_STEPS.lastOrNull { it < fontScale - EPSILON } ?: fontScale
            ZoomShortcut.Reset -> 1f
        }
        if (next == fontScale) return false
        fontScale = next
        return true
    }

    private companion object {
        val ZOOM_STEPS = floatArrayOf(
            0.5f, 0.67f, 0.75f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f,
        )
        const val EPSILON = 0.001f
    }
}

// From NSEvent.h. The bits a modifier flag word carries.
private const val MAC_FLAG_CONTROL = 1L shl 18
private const val MAC_FLAG_OPTION = 1L shl 19
private const val MAC_FLAG_COMMAND = 1L shl 20

// Virtual key codes from the Carbon `Events.h`, which a key press on macOS carries
// whatever the layout.
private const val MAC_KEY_EQUAL = 0x18
private const val MAC_KEY_MINUS = 0x1B
private const val MAC_KEY_ZERO = 0x1D
private const val MAC_KEY_KEYPAD_PLUS = 0x45
private const val MAC_KEY_KEYPAD_MINUS = 0x4E
private const val MAC_KEY_KEYPAD_ZERO = 0x52
