package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Rect

// What an input method says, turned into what the shared reader hears.
//
// One model for both X11 windows. The window that links the native image gets the input
// method's callbacks in C and the Kotlin/Native window gets them from Xlib; each hands what
// it was told to this file as plain numbers and text, so a preedit edit applied at the
// wrong offset, or a commit dropped when a syllable is finished, can be wrong in one place
// only and is checked by tests that need no input method daemon.
//
// Nothing here reaches Rust. What an input method produces is a `WindowEvent` of the kinds
// the window already has, recorded into the same log as a pointer move, and it ends in the
// text input session Compose opened for the focused field.

/**
 * The text an input method is still composing, kept the way X's preedit callbacks describe
 * it: as edits to a run of characters.
 *
 * The draw callback does not send the whole string. It says "replace [length] characters
 * from [first] with this", so a client has to hold the run to apply that to. Counted in code
 * points and not in UTF-16 units, because that is what the protocol counts: a Hangul syllable
 * is one, and so is a character outside the Basic Multilingual Plane, which is two units.
 */
internal class PreeditBuffer {
    private val points = ArrayList<Int>()

    /** Where the input method says the caret is inside the run, in characters. */
    var caret = 0
        private set

    val isEmpty: Boolean get() = points.isEmpty()

    /** The run as a string, in the encoding Compose reads. */
    val text: String
        get() = buildString { for (point in points) appendPoint(point) }

    /**
     * Applies one draw callback.
     *
     * A start past the end is clamped to the end rather than rejected: a client that threw
     * here would be a window that dies in the middle of someone's sentence, and appending is
     * what an input method that miscounted meant.
     */
    fun replace(first: Int, length: Int, with: String, newCaret: Int) {
        val start = first.coerceIn(0, points.size)
        val end = (start + length.coerceAtLeast(0)).coerceAtMost(points.size)
        for (index in end - 1 downTo start) points.removeAt(index)
        points.addAll(start, codePointsOf(with))
        caret = newCaret.coerceIn(0, points.size)
    }

    fun clear() {
        points.clear()
        caret = 0
    }
}

/**
 * One client's side of an input method: the callbacks it makes, and what each of them means.
 *
 * Takes [emit] rather than a window, so that a test hands it a list and a window hands it the
 * log every other event goes into. Everything arrives on the thread that reads the display
 * server, from inside the one call that reads it, so nothing here is ever re-entered and
 * nothing needs a lock.
 */
internal class ImeSession(private val emit: (WindowEvent) -> Unit) {
    private val preedit = PreeditBuffer()

    /** Whether something is being composed, which is what a click has to finish. */
    val composing: Boolean get() = !preedit.isEmpty

    /** The input method begins a composition. Nothing is shown until it draws. */
    fun preeditStart() {
        preedit.clear()
    }

    /**
     * The composition changed. Compose is given the whole run each time, because that is what
     * its command replaces: the marked text is the run, and what was marked before is gone.
     */
    fun preeditDraw(first: Int, length: Int, text: String, caret: Int) {
        preedit.replace(first, length, text, caret)
        emit(imeEvent(WindowEvent.TEXT_COMPOSE, preedit.text))
    }

    /** The composition ended without committing anything: the reader backed out of it. */
    fun preeditDone() {
        if (preedit.isEmpty) return
        preedit.clear()
        emit(imeEvent(WindowEvent.TEXT_COMPOSE, ""))
    }

    /**
     * Finished text. Whatever was being composed is taken out first: some input methods clear
     * the preedit and then commit, and some commit and never clear it, and a field must not be
     * left holding both the syllable and the mark that was over it.
     */
    fun commit(text: String) {
        if (text.isEmpty()) return
        preeditDone()
        emit(imeEvent(WindowEvent.TEXT_COMMIT, text))
    }
}

/** A record for something an input method said: no position, no key, only the text. */
internal fun imeEvent(kind: Int, text: String) = WindowEvent(
    kind = kind,
    x = 0f,
    y = 0f,
    buttons = 0,
    modifiers = 0,
    keyCode = 0,
    codePoint = 0,
    text = text,
)

/**
 * What one key press or release becomes, given what the keyboard and the input method said it
 * typed.
 *
 * [text] is whatever came back from asking for the characters of the event: empty for a key
 * that types nothing, one character for most, and several for an input method that committed
 * a word. Three cases follow from it, and each exists because of a way this went wrong.
 *
 * - A key that types one plain ASCII character keeps carrying it in the key event, which is
 *   how every field here has been typed into so far. Moving it would change what a shortcut
 *   sees.
 * - Anything else printable is committed as text and the key event carries no character. A
 *   Hangul syllable put in a key event would be read as one code unit by a field that expects
 *   a character from a keyboard.
 * - An event with no key behind it at all, which is what an input method sends to say it has
 *   committed, produces the commit and no key event: there is nothing for a shortcut to
 *   see, and a key with no name pressed in a text field would only confuse a handler.
 */
internal fun keyEventsFor(
    press: Boolean,
    state: Int,
    keysym: Long,
    text: String,
): List<WindowEvent> {
    val key = x11KeyNumber(keysym)
    val modifiers = x11Modifiers(state)
    if (!press) {
        return listOf(
            WindowEvent(WindowEvent.KEY_UP, 0f, 0f, 0, modifiers, key, 0, ""),
        )
    }
    val first = text.firstOrNull()
    val printable = first != null && first.code >= FIRST_PRINTABLE && first.code != DELETE_CHARACTER
    val plain = text.length == 1 && first!!.code in FIRST_PRINTABLE until DELETE_CHARACTER
    val shortcut = x11IsShortcut(state)
    val down = WindowEvent(
        WindowEvent.KEY_DOWN, 0f, 0f, 0, modifiers, key,
        if (plain) first!!.code else 0, "",
    )
    if (!printable || plain) return listOf(down)
    if (shortcut) return listOf(down)
    val commit = imeEvent(WindowEvent.TEXT_COMMIT, text)
    return if (keysym == 0L) listOf(commit) else listOf(down, commit)
}

/**
 * Where an input method should put its candidate window: under the caret, in the window's own
 * pixels, or null where the field has not been laid out yet.
 *
 * The rectangle is Compose's own answer for the focused field's caret, in the root's units.
 * The scene's density is one point to a pixel (see the window's note on it), so no scale
 * is applied; [density] is there so that changing that changes one place.
 */
internal fun candidateSpot(caret: Rect?, density: Float): Pair<Int, Int>? {
    if (caret == null || caret.isEmpty) return null
    return (caret.left * density).toInt() to (caret.bottom * density).toInt()
}

private const val FIRST_PRINTABLE = 32
private const val DELETE_CHARACTER = 0x7F

