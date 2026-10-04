@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import x11.Button1Mask
import x11.Button2Mask
import x11.Button3Mask
import x11.ControlMask
import x11.KeySym
import x11.Mod1Mask
import x11.Mod4Mask
import x11.ShiftMask
import x11.XC_crosshair
import x11.XC_hand2
import x11.XC_left_ptr
import x11.XC_sb_h_double_arrow
import x11.XC_sb_v_double_arrow
import x11.XC_xterm
import x11.XK_BackSpace
import x11.XK_Delete
import x11.XK_Down
import x11.XK_End
import x11.XK_Escape
import x11.XK_Home
import x11.XK_ISO_Left_Tab
import x11.XK_KP_Enter
import x11.XK_Left
import x11.XK_Next
import x11.XK_Prior
import x11.XK_Return
import x11.XK_Right
import x11.XK_Tab
import x11.XK_Up
import x11.XK_space

// What this display server calls a thing, turned into what the shared reader calls it.
//
// Apart from the window, because these are the part of it that can be wrong without anything
// looking wrong. A key mapped to the number of a different key still reaches Compose and still
// does something; a modifier bit in the wrong place is a keyboard shortcut that silently means
// something else. Both are tables, and a table is checkable.

/** The buttons the shared reader knows about, in the bits it reads them from. */
internal fun buttonsOf(state: UInt): Int {
    // Compared as Int, which is the width the masks come across as. Widening them to Long
    // first looks harmless and does not compile: a mask is a constant of the header's own
    // type, not a number this code chose.
    val bits = state.toInt()
    return (if (bits and Button1Mask != 0) 1 else 0) or
        (if (bits and Button3Mask != 0) 2 else 0) or
        (if (bits and Button2Mask != 0) 4 else 0)
}

/**
 * The four modifier bits the shared reader uses, which are the ones an NSEvent carries.
 *
 * Translated here so that the same reader is given the same meaning on every desktop, rather
 * than each one teaching it another numbering. Super is the key a Linux desktop puts where
 * macOS puts Command, which is why it lands in Command's bit: a screen that binds one binds
 * the other, and neither platform has both.
 */
internal fun modifiersOf(state: UInt): Int {
    val bits = state.toInt()
    return (if (bits and ShiftMask != 0) MODIFIER_SHIFT else 0) or
        (if (bits and ControlMask != 0) MODIFIER_CONTROL else 0) or
        (if (bits and Mod1Mask != 0) MODIFIER_ALT else 0) or
        (if (bits and Mod4Mask != 0) MODIFIER_SUPER else 0)
}

/**
 * The shared reader's number for a key this server named with a keysym.
 *
 * The numbers are not this platform's, and that is deliberate: they are the ones `composeKey`
 * reads, which every desktop translates into before the scene sees them, so the table from a
 * number to a Compose key is written once rather than three times. Minus one means a key with no
 * meaning of its own (zero is the letter A in that numbering), which is most of them: a key that types a character carries the character
 * beside it and the scene reads that instead.
 */
internal fun platformKey(keysym: KeySym): Int = when (keysym.toInt()) {
    XK_Return, XK_KP_Enter -> PLATFORM_ENTER
    XK_Tab, XK_ISO_Left_Tab -> PLATFORM_TAB
    XK_space -> PLATFORM_SPACE
    XK_BackSpace -> PLATFORM_BACKSPACE
    XK_Escape -> PLATFORM_ESCAPE
    XK_Delete -> PLATFORM_DELETE
    XK_Left -> PLATFORM_LEFT
    XK_Right -> PLATFORM_RIGHT
    XK_Down -> PLATFORM_DOWN
    XK_Up -> PLATFORM_UP
    XK_Home -> PLATFORM_HOME
    XK_End -> PLATFORM_END
    XK_Prior -> PLATFORM_PAGE_UP
    XK_Next -> PLATFORM_PAGE_DOWN
    else -> -1
}

/**
 * This server's name for a pointer shape, from the number the scene asked with.
 *
 * A shape this does not have becomes the arrow, which is what a pointer over something
 * unremarkable looks like anyway.
 */
internal fun cursorFont(shape: Int): Int =
    CURSOR_FONTS[if (shape in CURSOR_FONTS.indices) shape else CURSOR_ARROW]

/**
 * This server's name for each shape, in the order the shared side numbers them: arrow, hand,
 * text, crosshair, and the two resize arrows.
 */
private val CURSOR_FONTS = intArrayOf(
    XC_left_ptr, XC_hand2, XC_xterm, XC_crosshair,
    XC_sb_h_double_arrow, XC_sb_v_double_arrow,
)

/** How many shapes both sides agree a pointer may take. */
internal const val CURSOR_SHAPES = 6

internal const val CURSOR_ARROW = 0
internal const val CURSOR_HAND = 1
internal const val CURSOR_TEXT = 2
internal const val CURSOR_CROSSHAIR = 3

/** From NSEvent.h. The bits the shared reader reads a modifier word's meaning out of. */
private const val MODIFIER_SHIFT = 1 shl 17
private const val MODIFIER_CONTROL = 1 shl 18
private const val MODIFIER_ALT = 1 shl 19
private const val MODIFIER_SUPER = 1 shl 20

// The shared numbering, which is AppKit's. `composeKey` in the interpreter's own sources is the
// other end of each of these.
private const val PLATFORM_ENTER = 0x24
private const val PLATFORM_TAB = 0x30
private const val PLATFORM_SPACE = 0x31
private const val PLATFORM_BACKSPACE = 0x33
private const val PLATFORM_ESCAPE = 0x35
private const val PLATFORM_DELETE = 0x75
private const val PLATFORM_LEFT = 0x7B
private const val PLATFORM_RIGHT = 0x7C
private const val PLATFORM_DOWN = 0x7D
private const val PLATFORM_UP = 0x7E
private const val PLATFORM_HOME = 0x73
private const val PLATFORM_END = 0x77
private const val PLATFORM_PAGE_UP = 0x74
private const val PLATFORM_PAGE_DOWN = 0x79

/** A wheel arrives as a press and a release of a button that does not exist. */
internal const val WHEEL_UP = 4
internal const val WHEEL_DOWN = 5
internal const val WHEEL_LEFT = 6
internal const val WHEEL_RIGHT = 7
internal val SCROLL_BUTTONS = WHEEL_UP..WHEEL_RIGHT

/** How far one notch of the wheel travels, in the lines every other application moves. */
internal const val SCROLL_LINES = 3.0f
