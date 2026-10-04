package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import dev.darkpyonix.composerust.ui.platform.NO_KEY
import dev.darkpyonix.composerust.ui.platform.X11_CONTROL_MASK
import dev.darkpyonix.composerust.ui.platform.X11_MOD1_MASK
import dev.darkpyonix.composerust.ui.platform.X11_MOD4_MASK
import dev.darkpyonix.composerust.ui.platform.X11_SHIFT_MASK
import dev.darkpyonix.composerust.ui.platform.composeKey
import dev.darkpyonix.composerust.ui.platform.x11IsShortcut
import dev.darkpyonix.composerust.ui.platform.x11KeyNumber
import dev.darkpyonix.composerust.ui.platform.x11Modifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The X11 key table both X11 windows read. A shortcut is matched by which key it is, so each
 * letter a shortcut uses has to arrive as that letter on every path.
 */
class X11KeysTest {
    private fun key(keysym: Long) = composeKey(x11KeyNumber(keysym))

    @Test
    fun the_editing_shortcut_letters_arrive_as_themselves() {
        assertEquals(Key.C, key('c'.code.toLong()))
        assertEquals(Key.C, key('C'.code.toLong()))
        assertEquals(Key.V, key('v'.code.toLong()))
        assertEquals(Key.X, key('x'.code.toLong()))
        assertEquals(Key.Z, key('z'.code.toLong()))
        assertEquals(Key.A, key('a'.code.toLong()))
    }

    @Test
    fun every_letter_and_digit_has_its_own_key() {
        val letters = ('a'..'z').map { x11KeyNumber(it.code.toLong()) }
        assertEquals(26, letters.toSet().size)
        assertEquals(10, ('0'..'9').map { x11KeyNumber(it.code.toLong()) }.toSet().size)
        assertEquals(Key.Five, key('5'.code.toLong()))
        assertEquals(Key.Zero, key('0'.code.toLong()))
    }

    @Test
    fun named_keys_and_their_second_keysyms() {
        assertEquals(Key.Enter, key(0xFF0DL))
        assertEquals(Key.Enter, key(0xFF8DL))
        assertEquals(Key.Tab, key(0xFF09L))
        assertEquals(Key.Tab, key(0xFE20L))
        assertEquals(Key.Spacebar, key(0x20L))
        assertEquals(Key.Backspace, key(0xFF08L))
        assertEquals(Key.Escape, key(0xFF1BL))
        assertEquals(Key.Delete, key(0xFFFFL))
        assertEquals(Key.DirectionLeft, key(0xFF51L))
        assertEquals(Key.DirectionUp, key(0xFF52L))
        assertEquals(Key.DirectionRight, key(0xFF53L))
        assertEquals(Key.DirectionDown, key(0xFF54L))
        assertEquals(Key.MoveHome, key(0xFF50L))
        assertEquals(Key.MoveEnd, key(0xFF57L))
        assertEquals(Key.PageUp, key(0xFF55L))
        assertEquals(Key.PageDown, key(0xFF56L))
    }

    @Test
    fun a_key_with_no_meaning_is_minus_one_never_zero() {
        // Zero is the A key, so an unknown key answering zero arrived as control with A.
        assertEquals(NO_KEY, x11KeyNumber(0xFD00L))
        assertEquals(NO_KEY, x11KeyNumber(0xFFBEL))
        assertNotEquals(0, x11KeyNumber(0xFFBEL))
        assertEquals(Key.Unknown, key(0xFFBEL))
    }

    @Test
    fun each_modifier_lands_in_its_own_bit() {
        val shift = 1 shl 17
        val control = 1 shl 18
        val option = 1 shl 19
        val command = 1 shl 20
        assertEquals(shift, x11Modifiers(X11_SHIFT_MASK))
        assertEquals(control, x11Modifiers(X11_CONTROL_MASK))
        assertEquals(option, x11Modifiers(X11_MOD1_MASK))
        assertEquals(command, x11Modifiers(X11_MOD4_MASK))
        assertEquals(control or shift, x11Modifiers(X11_CONTROL_MASK or X11_SHIFT_MASK))
        assertEquals(0, x11Modifiers(0))
    }

    @Test
    fun control_alt_and_super_make_a_shortcut_and_shift_does_not() {
        assertEquals(true, x11IsShortcut(X11_CONTROL_MASK))
        assertEquals(true, x11IsShortcut(X11_MOD1_MASK))
        assertEquals(true, x11IsShortcut(X11_MOD4_MASK))
        assertEquals(false, x11IsShortcut(X11_SHIFT_MASK))
    }
}
