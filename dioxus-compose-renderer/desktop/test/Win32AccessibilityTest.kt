package dioxus.compose.test

import dioxus.compose.ui.platform.win32LabelBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Win32AccessibilityTest {
    @Test
    fun label_fits_the_native_record_without_losing_a_utf8_character() {
        val label = "a".repeat(94) + "한" + "b"
        val encoded = win32LabelBytes(label)

        assertEquals("a".repeat(94), encoded.toString(Charsets.UTF_8))
        assertTrue(encoded.size < 96)
        assertEquals("한b", win32LabelBytes("한b").toString(Charsets.UTF_8))
    }

    @Test
    fun label_uses_every_available_byte_when_the_boundary_is_valid() {
        val encoded = win32LabelBytes("a".repeat(95) + "한")

        assertEquals(95, encoded.size)
        assertEquals("a".repeat(95), encoded.toString(Charsets.UTF_8))
    }
}
