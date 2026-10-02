package dioxus.compose.test

import androidx.compose.ui.input.key.Key
import dioxus.compose.ui.platform.win32ComposeKey
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
        assertEquals(Key.Unknown, win32ComposeKey(0x41))
    }
}
