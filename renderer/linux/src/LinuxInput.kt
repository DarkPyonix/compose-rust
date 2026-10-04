@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import x11.Button1Mask
import x11.Button2Mask
import x11.Button3Mask
import x11.KeySym
import x11.XC_crosshair
import x11.XC_hand2
import x11.XC_left_ptr
import x11.XC_sb_h_double_arrow
import x11.XC_sb_v_double_arrow
import x11.XC_xterm

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
internal fun modifiersOf(state: UInt): Int = x11Modifiers(state.toInt())

/**
 * The shared reader's number for a key this server named with a keysym.
 *
 * The numbers are not this platform's, and that is deliberate: they are the ones `composeKey`
 * reads, which every desktop translates into before the scene sees them, so the table from a
 * number to a Compose key is written once rather than three times. Minus one means a key with no
 * meaning of its own (zero is the letter A in that numbering), which is most of them: a key that types a character carries the character
 * beside it and the scene reads that instead.
 */
internal fun platformKey(keysym: KeySym): Int = x11KeyNumber(keysym.toLong())

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

// The shared numbering, which is AppKit's. `composeKey` in the interpreter's own sources is the
// other end of each of these.

/** A wheel arrives as a press and a release of a button that does not exist. */
internal const val WHEEL_UP = 4
internal const val WHEEL_DOWN = 5
internal const val WHEEL_LEFT = 6
internal const val WHEEL_RIGHT = 7
internal val SCROLL_BUTTONS = WHEEL_UP..WHEEL_RIGHT

/** How far one notch of the wheel travels, in the lines every other application moves. */
internal const val SCROLL_LINES = 3.0f
