@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.thisisthepy.compose.window.NO_KEY
import org.thisisthepy.compose.window.x11KeyNumber

/**
 * What the scene is given for a keysym the display server handed over.
 *
 * The window module turns a keysym into the shared key number, and this renderer turns that
 * number into a Compose key. Each is a table and they are written in different files, so a pair
 * that agree with themselves while disagreeing with each other is the defect these catch: the
 * key still reaches the scene, it still does something, and what it does is what a different
 * key means. The keysyms are fixed by the X11 protocol, so they are written here as numbers.
 */
class LinuxComposeKeyTest {

    private fun number(keysym: Long): Int = x11KeyNumber(keysym)

    /** A key with a meaning of its own arrives at the scene as that meaning. */
    @Test
    fun nfr9_a_named_key_reaches_the_scene_as_the_key_it_is() {
        assertEquals(Key.Enter, composeKey(number(XK_RETURN)))
        assertEquals(Key.Tab, composeKey(number(XK_TAB)))
        assertEquals(Key.Spacebar, composeKey(number(XK_SPACE)))
        assertEquals(Key.Backspace, composeKey(number(XK_BACKSPACE)))
        assertEquals(Key.DirectionLeft, composeKey(number(XK_LEFT)))
        assertEquals(Key.DirectionDown, composeKey(number(XK_DOWN)))
    }

    /**
     * The keypad's Enter is Enter, and shift-Tab is still Tab.
     *
     * Two keysyms for one key, and both have been the reason a form could not be left: a numeric
     * keypad's Return is a different keysym from the main one, and a Tab held with shift arrives
     * as `ISO_Left_Tab` rather than as Tab with a modifier.
     */
    @Test
    fun the_second_keysym_for_a_key_means_the_same_key() {
        assertEquals(number(XK_RETURN), number(XK_KP_ENTER), "the keypad's Enter is Enter")
        assertEquals(number(XK_TAB), number(XK_ISO_LEFT_TAB), "a Tab held with shift is still a Tab")
    }

    /**
     * A letter key reaches the scene as its own letter, in either case.
     *
     * The fork's X11 window numbers letters the way an AppKit keyboard does, so Control with a
     * letter is a shortcut Compose recognises. The character still travels beside the key, and
     * Compose types from that, not from the key.
     */
    @Test
    fun a_letter_key_reaches_the_scene_as_its_letter() {
        assertEquals(Key.A, composeKey(number(XK_LOWER_A)))
        assertEquals(Key.A, composeKey(number(XK_LOWER_A - 0x20)))
    }

    /** A key the window gives no number to claims no meaning rather than a wrong one. */
    @Test
    fun a_key_with_no_number_claims_no_meaning() {
        // F1, which the window does not number.
        assertEquals(NO_KEY, number(0xFFBEL))
        assertEquals(Key.Unknown, composeKey(NO_KEY))
    }

    /** No two named keys share a number, or one of them does what the other means. */
    @Test
    fun no_two_named_keys_share_a_number() {
        val named = listOf(XK_RETURN, XK_TAB, XK_SPACE, XK_BACKSPACE, XK_LEFT, XK_DOWN).map(::number)
        assertEquals(named.size, named.toSet().size, "two keys were given the same number")
        assertNotEquals(Key.Unknown, composeKey(named.first()))
    }

    private companion object {
        const val XK_SPACE = 0x20L
        const val XK_LOWER_A = 0x61L
        const val XK_BACKSPACE = 0xff08L
        const val XK_TAB = 0xff09L
        const val XK_RETURN = 0xff0dL
        const val XK_LEFT = 0xff51L
        const val XK_DOWN = 0xff54L
        const val XK_KP_ENTER = 0xff8dL
        const val XK_ISO_LEFT_TAB = 0xfe20L
    }
}
