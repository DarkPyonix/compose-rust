package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.keyName
import dev.darkpyonix.composerust.ui.platform.win32ComposeKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On the Windows window every key reaches the Host with its USB HID usage `code` and
 * its DOM `key` text. The Windows window hands over the virtual key of WM_KEYDOWN; before this
 * the punctuation keys (VK_OEM_*), Insert, Caps Lock and the modifiers became `Key.Unknown`
 * and reached the Host with code 0.
 */
class Win32KeyCodeTest {

    /** Virtual key, HID usage, DOM key text (unshifted, US layout). */
    private val keys = listOf(
        Triple(0xBA, 0x33, ";"), // VK_OEM_1
        Triple(0xBB, 0x2E, "="), // VK_OEM_PLUS
        Triple(0xBC, 0x36, ","), // VK_OEM_COMMA
        Triple(0xBD, 0x2D, "-"), // VK_OEM_MINUS
        Triple(0xBE, 0x37, "."), // VK_OEM_PERIOD
        Triple(0xBF, 0x38, "/"), // VK_OEM_2
        Triple(0xC0, 0x35, "`"), // VK_OEM_3
        Triple(0xDB, 0x2F, "["), // VK_OEM_4
        Triple(0xDC, 0x31, "\\"), // VK_OEM_5
        Triple(0xDD, 0x30, "]"), // VK_OEM_6
        Triple(0xDE, 0x34, "'"), // VK_OEM_7
        Triple(0x2D, 0x49, "Insert"), // VK_INSERT
        Triple(0x14, 0x39, "CapsLock"), // VK_CAPITAL
        // WM_KEYDOWN names a modifier without its side; the left one's usage stands for it.
        Triple(0x10, 0xE1, "Shift"), // VK_SHIFT
        Triple(0x11, 0xE0, "Control"), // VK_CONTROL
        Triple(0x12, 0xE2, "Alt"), // VK_MENU
        Triple(0xA0, 0xE1, "Shift"), // VK_LSHIFT
        Triple(0xA1, 0xE5, "Shift"), // VK_RSHIFT
        Triple(0xA2, 0xE0, "Control"), // VK_LCONTROL
        Triple(0xA3, 0xE4, "Control"), // VK_RCONTROL
        Triple(0xA4, 0xE2, "Alt"), // VK_LMENU
        Triple(0xA5, 0xE6, "Alt"), // VK_RMENU
        Triple(0x5B, 0xE3, "Meta"), // VK_LWIN
        Triple(0x5C, 0xE7, "Meta"), // VK_RWIN
    )

    @Test
    fun fr12_1_win32_punctuation_insert_caps_lock_and_modifiers_have_their_hid_code() {
        val wrong = keys.mapNotNull { (virtualKey, code, text) ->
            val name = keyName(win32ComposeKey(virtualKey), shift = false)
            if (name.code == code && name.text == text) {
                null
            } else {
                "VK 0x%02X: code 0x%02X text \"%s\", expected 0x%02X \"%s\"".format(virtualKey, name.code, name.text, code, text)
            }
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun fr12_1_win32_keys_already_mapped_keep_their_code() {
        // Letters, digits, Enter and the arrows were right before; they stay right.
        assertEquals(0x04, keyName(win32ComposeKey(0x41), shift = false).code)
        assertEquals(0x27, keyName(win32ComposeKey(0x30), shift = false).code)
        assertEquals(0x28, keyName(win32ComposeKey(0x0D), shift = false).code)
        assertEquals(0x50, keyName(win32ComposeKey(0x25), shift = false).code)
    }
}
