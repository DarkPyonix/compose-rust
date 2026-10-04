package dev.darkpyonix.composerust.ui.platform

// What an X11 server calls a key and a modifier, turned into the numbering the shared reader
// (`composeKey`) understands. The numbering is AppKit's: letters and digits by where they sit
// on a board, special keys by name.
//
// One table for both X11 windows. The window that links the native image hands the keysym
// over untouched, and the Kotlin/Native window reads it from Xlib; both then ask here, so a
// key can no longer mean one thing on one path and another on the other. Plain integers
// rather than Xlib's constants, because this file is compiled for the native image and for
// Kotlin/Native and only the second has the X11 headers. The values are the ones in
// `keysymdef.h` and `X.h`, and the test names the ones that matter.

/** A key with no meaning of its own. Zero is the A key in this numbering, so it cannot be zero. */
internal const val NO_KEY = -1

/** The key number for an X11 keysym, or [NO_KEY] where the key only types a character. */
internal fun x11KeyNumber(keysym: Long): Int {
    if (keysym in XK_LOWER_A..XK_LOWER_Z) return X11_LETTERS[(keysym - XK_LOWER_A).toInt()]
    if (keysym in XK_UPPER_A..XK_UPPER_Z) return X11_LETTERS[(keysym - XK_UPPER_A).toInt()]
    if (keysym in XK_ZERO..XK_NINE) return X11_DIGITS[(keysym - XK_ZERO).toInt()]
    return when (keysym) {
        XK_RETURN, XK_KP_ENTER -> 0x24
        XK_TAB, XK_ISO_LEFT_TAB -> 0x30
        XK_SPACE -> 0x31
        XK_BACKSPACE -> 0x33
        XK_ESCAPE -> 0x35
        XK_DELETE -> 0x75
        XK_LEFT -> 0x7B
        XK_RIGHT -> 0x7C
        XK_DOWN -> 0x7D
        XK_UP -> 0x7E
        XK_HOME -> 0x73
        XK_END -> 0x77
        XK_PRIOR -> 0x74
        XK_NEXT -> 0x79
        else -> NO_KEY
    }
}

/**
 * What the X11 window's records become for the scene.
 *
 * The window records what the server and the input method said as it was said: a key as a
 * keysym, a state word and the text it typed, and the preedit callbacks as they came. The two
 * windows on X11 then turn that into events with the same code, `keyEventsFor` and [ImeSession],
 * so the key and the composition mean the same on the native image and on Kotlin/Native.
 *
 * [resetInput] ends the input method's composition and answers what it kept. It is asked when
 * a button goes down while something is being composed, because a click ends the composition
 * and keeps what was typed, which is what the other desktops' input methods do.
 */
internal class X11Events(private val resetInput: () -> String) {
    private val pending = ArrayList<WindowEvent>()
    private val ime = ImeSession { pending += it }

    fun heard(raw: WindowEvent): List<WindowEvent> {
        pending.clear()
        when (raw.kind) {
            WindowEvent.KEY_DOWN, WindowEvent.KEY_UP -> pending += keyEventsFor(
                press = raw.kind == WindowEvent.KEY_DOWN,
                state = raw.modifiers,
                keysym = raw.keyCode.toLong() and 0xFFFFFFFFL,
                text = raw.text,
            )
            WindowEvent.PREEDIT_START -> ime.preeditStart()
            WindowEvent.PREEDIT_DRAW -> ime.preeditDraw(
                first = raw.keyCode,
                length = raw.codePoint,
                text = raw.text,
                caret = raw.x.toInt(),
            )
            WindowEvent.PREEDIT_DONE -> ime.preeditDone()
            else -> {
                if (raw.kind == WindowEvent.POINTER_DOWN && ime.composing) {
                    val kept = resetInput()
                    if (kept.isNotEmpty()) ime.commit(kept) else ime.preeditDone()
                }
                pending += raw.copy(modifiers = x11Modifiers(raw.modifiers))
            }
        }
        return ArrayList(pending)
    }
}

/**
 * The four modifier bits the shared reader uses, which are the ones an NSEvent carries, from
 * the state word an X11 event carries. Super is the key a Linux desktop puts where macOS puts
 * Command, so it lands in Command's bit.
 */
internal fun x11Modifiers(state: Int): Int =
    (if (state and X11_SHIFT_MASK != 0) 1 shl 17 else 0) or
        (if (state and X11_CONTROL_MASK != 0) 1 shl 18 else 0) or
        (if (state and X11_MOD1_MASK != 0) 1 shl 19 else 0) or
        (if (state and X11_MOD4_MASK != 0) 1 shl 20 else 0)

/** Whether the state word says a command is being typed rather than text: control, alt or super. */
internal fun x11IsShortcut(state: Int): Boolean =
    state and (X11_CONTROL_MASK or X11_MOD1_MASK or X11_MOD4_MASK) != 0

internal const val X11_SHIFT_MASK = 1 shl 0
internal const val X11_CONTROL_MASK = 1 shl 2
internal const val X11_MOD1_MASK = 1 shl 3
internal const val X11_MOD4_MASK = 1 shl 6

private const val XK_LOWER_A = 0x61L
private const val XK_LOWER_Z = 0x7AL
private const val XK_UPPER_A = 0x41L
private const val XK_UPPER_Z = 0x5AL
private const val XK_ZERO = 0x30L
private const val XK_NINE = 0x39L
private const val XK_SPACE = 0x20L
private const val XK_BACKSPACE = 0xFF08L
private const val XK_TAB = 0xFF09L
private const val XK_RETURN = 0xFF0DL
private const val XK_ESCAPE = 0xFF1BL
private const val XK_HOME = 0xFF50L
private const val XK_LEFT = 0xFF51L
private const val XK_UP = 0xFF52L
private const val XK_RIGHT = 0xFF53L
private const val XK_DOWN = 0xFF54L
private const val XK_PRIOR = 0xFF55L
private const val XK_NEXT = 0xFF56L
private const val XK_END = 0xFF57L
private const val XK_KP_ENTER = 0xFF8DL
private const val XK_DELETE = 0xFFFFL
private const val XK_ISO_LEFT_TAB = 0xFE20L

// a to z, then 0 to 9, each as the board numbers them.
private val X11_LETTERS = intArrayOf(
    0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
    0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06,
)
private val X11_DIGITS = intArrayOf(0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19)
