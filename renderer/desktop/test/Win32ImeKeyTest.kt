package dev.darkpyonix.composerust.test

import androidx.compose.ui.input.key.Key
import dev.darkpyonix.composerust.ui.platform.win32ComposeKey
import kotlin.test.Test
import kotlin.test.assertEquals

class Win32ImeKeyTest {
    @Test
    fun editingKeysUseWindowsVirtualKeyNumbers() {
        assertEquals(Key.Enter, win32ComposeKey(0x0D))
        assertEquals(Key.Backspace, win32ComposeKey(0x08))
        assertEquals(Key.Escape, win32ComposeKey(0x1B))
        assertEquals(Key.DirectionLeft, win32ComposeKey(0x25))
        assertEquals(Key.DirectionRight, win32ComposeKey(0x27))
        assertEquals(Key.Delete, win32ComposeKey(0x2E))
        // The letters are their own virtual keys, so control with C reaches Compose as C.
        assertEquals(Key.A, win32ComposeKey(0x41))
        assertEquals(Key.Unknown, win32ComposeKey(0x07))
    }

    @Test
    fun nfr14_the_clipboard_shortcuts_reach_compose_as_their_letters() {
        assertEquals(Key.A, win32ComposeKey(0x41))
        assertEquals(Key.C, win32ComposeKey(0x43))
        assertEquals(Key.V, win32ComposeKey(0x56))
        assertEquals(Key.X, win32ComposeKey(0x58))
        assertEquals(Key.Z, win32ComposeKey(0x5A))
        assertEquals(Key.Zero, win32ComposeKey(0x30))
        assertEquals(Key.Nine, win32ComposeKey(0x39))
    }
}
