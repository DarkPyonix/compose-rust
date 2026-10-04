package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import dev.darkpyonix.composerust.ui.platform.composeKey
import dev.darkpyonix.composerust.ui.platform.win32ComposeKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Control with C, V, X and Z has to reach Compose as those keys on every desktop.
 *
 * A key the table does not know used to arrive as key number zero, which is A on the board
 * the table is written for, so on the X11 window every one of these selected everything.
 * The X11 half is `X11KeysTest`, which checks the table both X11 windows read; this is the
 * other half, what those numbers mean.
 */
class ShortcutKeysTest {

    @Test
    fun nfr14_the_clipboard_shortcuts_are_their_own_keys_on_the_shared_board() {
        assertEquals(Key.C, composeKey(0x08))
        assertEquals(Key.V, composeKey(0x09))
        assertEquals(Key.X, composeKey(0x07))
        assertEquals(Key.Z, composeKey(0x06))
        assertEquals(Key.A, composeKey(0x00))
    }

    @Test
    fun nfr14_a_key_with_no_number_is_not_a_letter() {
        assertEquals(Key.Unknown, composeKey(-1))
    }

    @Test
    fun nfr14_the_clipboard_shortcuts_are_their_own_keys_in_a_windows_virtual_key() {
        assertEquals(Key.C, win32ComposeKey('C'.code))
        assertEquals(Key.V, win32ComposeKey('V'.code))
        assertEquals(Key.X, win32ComposeKey('X'.code))
        assertEquals(Key.Z, win32ComposeKey('Z'.code))
        assertEquals(Key.Five, win32ComposeKey('5'.code))
    }

    @Test
    fun nfr14_digits_are_digits_on_the_shared_board() {
        assertEquals(Key.One, composeKey(0x12))
        assertEquals(Key.Zero, composeKey(0x1D))
    }
}
