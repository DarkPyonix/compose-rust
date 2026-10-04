@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import x11.ControlMask
import x11.ShiftMask
import x11.XK_BackSpace
import x11.XK_Return
import x11.XK_a

/**
 * What an input method says, and what the window then hears.
 *
 * The XIM calls themselves need a display server and an input method daemon, and what they do
 * with them is for the headless check and for a person with a Korean keyboard. What can be
 * asserted here is everything after the callback has fired: the edits to the composing run, the
 * events they become, and the decision about which of a key's two meanings is sent. The fake is
 * a list, because that is all the window gives the session.
 */
class InputMethodTest {

    private fun session(): Pair<ImeSession, MutableList<WindowEvent>> {
        val heard = mutableListOf<WindowEvent>()
        return ImeSession { heard += it } to heard
    }

    private val composeKinds = WindowEvent.TEXT_COMPOSE
    private val commitKind = WindowEvent.TEXT_COMMIT

    private fun List<WindowEvent>.summary() = map { it.kind to it.text }

    /**
     * Typing the three letters of one syllable, the way ibus-hangul and fcitx5-hangul drive the
     * preedit callbacks: each key redraws the whole run, and finishing the syllable clears the
     * run and commits it.
     */
    @Test
    fun fr5_a_hangul_syllable_is_composed_in_the_field_and_then_committed() {
        val (ime, heard) = session()
        ime.preeditStart()
        ime.preeditDraw(0, 0, "ㅇ", 1) // ieung
        ime.preeditDraw(0, 1, "아", 1) // a
        ime.preeditDraw(0, 1, "안", 1) // an
        ime.commit("안")
        assertEquals(
            listOf(
                composeKinds to "ㅇ",
                composeKinds to "아",
                composeKinds to "안",
                composeKinds to "",
                commitKind to "안",
            ),
            heard.summary(),
            "each key marks the run again, and the commit takes the mark off before it types",
        )
    }

    @Test
    fun fr5_a_syllable_is_split_when_the_next_consonant_starts_a_new_one() {
        val (ime, heard) = session()
        ime.preeditDraw(0, 0, "안", 1)
        // The input method finishes the first syllable and starts the second in one step: it
        // commits "an" and the run becomes the next consonant.
        ime.commit("안")
        ime.preeditDraw(0, 0, "ㄴ", 1)
        assertEquals(
            listOf(
                composeKinds to "안",
                composeKinds to "",
                commitKind to "안",
                composeKinds to "ㄴ",
            ),
            heard.summary(),
        )
        assertTrue(ime.composing)
    }

    @Test
    fun fr5_backspace_during_composition_removes_a_jamo_and_not_a_syllable() {
        val (ime, heard) = session()
        ime.preeditDraw(0, 0, "안", 1)
        // Backspace: the input method redraws the syllable one jamo shorter.
        ime.preeditDraw(0, 1, "아", 1)
        ime.preeditDraw(0, 1, "ㅇ", 1)
        ime.preeditDraw(0, 1, "", 0)
        assertEquals(
            listOf("안", "아", "ㅇ", ""),
            heard.map { it.text },
        )
        assertFalse(ime.composing, "an empty run is not a composition")
    }

    @Test
    fun fr5_a_draw_edits_the_middle_of_the_run_by_character_and_not_by_byte() {
        val buffer = PreeditBuffer()
        buffer.replace(0, 0, "한글", 2)
        assertEquals("한글", buffer.text)
        // Replace the second character only. A byte offset would land inside the first.
        buffer.replace(1, 1, "기", 2)
        assertEquals("한기", buffer.text)
        buffer.replace(0, 1, "", 1)
        assertEquals("기", buffer.text)
        assertEquals(1, buffer.caret)
    }

    @Test
    fun fr5_a_character_outside_the_basic_plane_counts_as_one() {
        val buffer = PreeditBuffer()
        // U+1F600 is two UTF-16 units and one character. The protocol counts characters.
        buffer.replace(0, 0, "a😀b", 3)
        buffer.replace(1, 1, "c", 2)
        assertEquals("acb", buffer.text)
        assertEquals(listOf(0x61, 0x1F600, 0x62), codePointsOf("a😀b"))
    }

    @Test
    fun fr5_an_edit_past_the_end_appends_rather_than_failing() {
        val buffer = PreeditBuffer()
        buffer.replace(0, 0, "ab", 2)
        buffer.replace(9, 4, "c", 3)
        assertEquals("abc", buffer.text)
    }

    @Test
    fun fr5_backing_out_of_a_composition_clears_the_mark_without_typing_anything() {
        val (ime, heard) = session()
        ime.preeditDraw(0, 0, "ㅇ", 1)
        ime.preeditDone()
        assertEquals(listOf(composeKinds to "ㅇ", composeKinds to ""), heard.summary())
        // A second done with nothing composing says nothing.
        ime.preeditDone()
        assertEquals(2, heard.size)
    }

    @Test
    fun fr5_committing_nothing_types_nothing() {
        val (ime, heard) = session()
        ime.commit("")
        assertTrue(heard.isEmpty())
    }

    /** What a person typing in English gets: the same key event it always was. */
    @Test
    fun fr5_a_plain_ascii_key_keeps_carrying_its_character() {
        val events = keyEventsFor(true, 0u, XK_a.toULong(), "a")
        assertEquals(1, events.size)
        assertEquals(WindowEvent.KEY_DOWN, events[0].kind)
        assertEquals('a'.code, events[0].codePoint)
        assertEquals("", events[0].text)
    }

    /** A committed word has no key behind it, so it is text and only text. */
    @Test
    fun fr5_a_commit_the_input_method_forwards_is_text_without_a_key() {
        val events = keyEventsFor(true, 0u, 0UL, "한글")
        assertEquals(listOf(commitKind to "한글"), events.summary())
    }

    @Test
    fun fr5_a_key_with_a_non_ascii_character_is_committed_and_also_a_key() {
        val events = keyEventsFor(true, 0u, XK_a.toULong(), "é")
        assertEquals(WindowEvent.KEY_DOWN, events[0].kind)
        assertEquals(0, events[0].codePoint, "the character is text, not part of the key")
        assertEquals(commitKind to "é", events[1].kind to events[1].text)
    }

    @Test
    fun fr5_a_control_character_is_a_key_and_never_text() {
        val enter = keyEventsFor(true, 0u, XK_Return.toULong(), "\r")
        assertEquals(1, enter.size)
        assertEquals(0, enter[0].codePoint)
        val backspace = keyEventsFor(true, 0u, XK_BackSpace.toULong(), "\u0008")
        assertEquals(1, backspace.size)
        assertEquals(0, backspace[0].codePoint)
        val delete = keyEventsFor(true, 0u, 0UL, "\u007F")
        assertTrue(delete.none { it.kind == commitKind })
    }

    @Test
    fun fr5_a_shortcut_never_commits_text() {
        val events = keyEventsFor(true, ControlMask.toUInt(), XK_a.toULong(), "é")
        assertEquals(listOf(WindowEvent.KEY_DOWN), events.map { it.kind })
    }

    @Test
    fun fr5_a_release_is_a_release_and_carries_no_text() {
        val events = keyEventsFor(false, ShiftMask.toUInt(), XK_a.toULong(), "A")
        assertEquals(listOf(WindowEvent.KEY_UP), events.map { it.kind })
        assertEquals("", events[0].text)
    }

    @Test
    fun fr5_the_candidate_window_goes_under_the_caret() {
        assertEquals(40 to 66, candidateSpot(Rect(40f, 50f, 42f, 66f), 1f))
        assertEquals(80 to 132, candidateSpot(Rect(40f, 50f, 42f, 66f), 2f))
    }

    @Test
    fun fr5_a_field_that_is_not_laid_out_gives_the_input_method_no_position() {
        assertNull(candidateSpot(null, 1f))
        assertNull(candidateSpot(Rect.Zero, 1f))
    }

    private val callbacks = 0x0002L
    private val nothing = 0x0008L
    private val none = 0x0010L
    private val statusNothing = 0x0400L
    private val statusNone = 0x0800L
    private val area = 0x0001L

    private fun choose(vararg offered: Long) =
        chooseInputStyle(offered.toList(), callbacks, nothing, none, statusNothing, statusNone)

    /** The style that puts the composition in the field is asked for whenever it is offered. */
    @Test
    fun fr5_preedit_callbacks_are_preferred_over_the_input_method_drawing_its_own_window() {
        assertEquals(
            callbacks or statusNothing,
            choose(nothing or statusNothing, callbacks or statusNothing, area or statusNothing),
        )
        assertEquals(callbacks or statusNone, choose(callbacks or statusNone, nothing or statusNothing))
    }

    @Test
    fun fr5_an_input_method_without_callbacks_is_still_typed_through() {
        assertEquals(nothing or statusNothing, choose(nothing or statusNothing, area or statusNothing))
        assertEquals(none or statusNone, choose(none or statusNone))
    }

    @Test
    fun fr5_an_input_method_that_offers_no_style_this_window_can_give_is_refused() {
        assertNull(choose(area or statusNothing))
        assertNull(choose())
    }

    @Test
    fun fr5_only_a_utf8_locale_can_carry_hangul() {
        assertTrue(isUtf8Locale("ko_KR.UTF-8"))
        assertTrue(isUtf8Locale("C.utf8"))
        assertTrue(isUtf8Locale("en_US.UTF-8@euro"))
        assertFalse(isUtf8Locale("C"))
        assertFalse(isUtf8Locale("ko_KR.eucKR"))
        assertFalse(isUtf8Locale(null))
    }
}
