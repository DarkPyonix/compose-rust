package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.ui.platform.WindowEvent
import dev.darkpyonix.composerust.ui.platform.X11Events
import dev.darkpyonix.composerust.ui.platform.X11_CONTROL_MASK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the X11 window's records become, which is the same code the Kotlin/Native window runs
 * on what it reads from Xlib. A composition and a key are checked here with no display and no
 * input method.
 */
class X11EventsTest {
    private fun record(
        kind: Int,
        keyCode: Int = 0,
        modifiers: Int = 0,
        codePoint: Int = 0,
        x: Float = 0f,
        text: String = "",
    ) = WindowEvent(kind, x, 0f, 0, modifiers, keyCode, codePoint, text)

    private fun events(reset: () -> String = { "" }) = X11Events(reset)

    @Test
    fun a_plain_letter_is_one_key_event_carrying_its_character() {
        val heard = events().heard(record(WindowEvent.KEY_DOWN, keyCode = 'a'.code, text = "a"))
        assertEquals(1, heard.size)
        assertEquals(WindowEvent.KEY_DOWN, heard[0].kind)
        assertEquals('a'.code, heard[0].codePoint)
        assertEquals(0x00, heard[0].keyCode, "a is the A key on the shared board")
    }

    @Test
    fun control_with_c_is_the_c_key_and_types_nothing() {
        val heard = events().heard(
            record(WindowEvent.KEY_DOWN, keyCode = 'c'.code, modifiers = X11_CONTROL_MASK, text = "\u0003"),
        )
        assertEquals(1, heard.size)
        assertEquals(0x08, heard[0].keyCode)
        assertEquals(1 shl 18, heard[0].modifiers)
        assertEquals("", heard[0].text)
    }

    @Test
    fun a_committed_word_is_text_with_no_key_behind_it() {
        val heard = events().heard(record(WindowEvent.KEY_DOWN, keyCode = 0, text = "한글"))
        assertEquals(listOf(WindowEvent.TEXT_COMMIT to "한글"), heard.map { it.kind to it.text })
    }

    @Test
    fun a_composition_is_handed_over_as_the_whole_run_every_time() {
        val x11 = events()
        assertTrue(x11.heard(record(WindowEvent.PREEDIT_START)).isEmpty())
        val first = x11.heard(record(WindowEvent.PREEDIT_DRAW, keyCode = 0, codePoint = 0, x = 1f, text = "ㅎ"))
        assertEquals(listOf(WindowEvent.TEXT_COMPOSE to "ㅎ"), first.map { it.kind to it.text })
        val second = x11.heard(record(WindowEvent.PREEDIT_DRAW, keyCode = 0, codePoint = 1, x = 1f, text = "하"))
        assertEquals(listOf(WindowEvent.TEXT_COMPOSE to "하"), second.map { it.kind to it.text })
        val third = x11.heard(record(WindowEvent.PREEDIT_DRAW, keyCode = 1, codePoint = 0, x = 2f, text = "ㄴ"))
        assertEquals(listOf(WindowEvent.TEXT_COMPOSE to "하ㄴ"), third.map { it.kind to it.text })
        val done = x11.heard(record(WindowEvent.PREEDIT_DONE))
        assertEquals(listOf(WindowEvent.TEXT_COMPOSE to ""), done.map { it.kind to it.text })
    }

    @Test
    fun a_click_while_composing_ends_the_composition_and_keeps_what_was_typed() {
        var resets = 0
        val x11 = events { resets++; "하" }
        x11.heard(record(WindowEvent.PREEDIT_START))
        x11.heard(record(WindowEvent.PREEDIT_DRAW, text = "하", x = 1f))
        val heard = x11.heard(record(WindowEvent.POINTER_DOWN, x = 10f))
        assertEquals(1, resets)
        assertEquals(
            listOf(WindowEvent.TEXT_COMPOSE, WindowEvent.TEXT_COMMIT, WindowEvent.POINTER_DOWN),
            heard.map { it.kind },
        )
        assertEquals("하", heard[1].text)
    }

    @Test
    fun a_click_with_nothing_composing_asks_nothing_of_the_input_method() {
        var resets = 0
        val heard = events { resets++; "x" }.heard(record(WindowEvent.POINTER_DOWN, x = 10f))
        assertEquals(0, resets)
        assertEquals(listOf(WindowEvent.POINTER_DOWN), heard.map { it.kind })
    }

    @Test
    fun a_pointer_record_has_its_modifiers_in_the_shared_bits() {
        val heard = events().heard(record(WindowEvent.POINTER_MOVE, modifiers = X11_CONTROL_MASK))
        assertEquals(1 shl 18, heard[0].modifiers)
    }
}
