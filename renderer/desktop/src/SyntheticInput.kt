package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.IntSize
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.ElementRole
import org.thisisthepy.compose.window.WindowEvent
import java.lang.System

/**
 * Input and resizing made up from inside the window, for measuring without a hand.
 *
 * A session with no accessibility permission cannot post a click, type a letter or drag
 * an edge, and the two things worth timing here are exactly those: how long a typed letter
 * takes to show, and what a drag does to the picture. Asked for with `DXC_SYNTH`, a
 * comma separated list of `type`, `resize` and `exit`; unset, nothing here runs.
 *
 * `type` presses on the first text field the window describes, then commits one letter
 * every 700 ms from two seconds in. `resize` takes the window from its size to 360x420 in
 * 60 steps, six seconds in (`DXC_SYNTH_RESIZE_AT` seconds, where set), from a single call
 * that holds the thread as a drag does. `exit` ends the window two seconds after the resize
 * (`DXC_SYNTH_EXIT_AT` seconds in, where set), so a run measures and finishes by itself and
 * the summary at the end of the loop is printed.
 */
internal class SyntheticInput(what: String) {
    private val wantsType = "type" in what.split(',')
    private val wantsResize = "resize" in what.split(',')
    private val wantsExit = "exit" in what.split(',')
    private val resizeAt = System.getenv("DXC_SYNTH_RESIZE_AT")?.toDoubleOrNull() ?: 6.0
    private val exitAt = System.getenv("DXC_SYNTH_EXIT_AT")?.toDoubleOrNull() ?: (resizeAt + 2.0)
    private val started = System.nanoTime()
    private var field: AccessibleElement? = null
    private var clicked = false
    private var letters = 0
    private var resized = false
    private var lastTyped = ""

    // Real key presses posted to the window's own queue, so the input method and the key
    // path are in the measurement. `DXC_SYNTH_KEYS` names the letters.
    private val keys = if ("keys" in what.split(',')) {
        (System.getenv("DXC_SYNTH_KEYS") ?: "abc").toList()
    } else {
        emptyList()
    }
    private var keysSent = 0

    /** The next key to post, as a key code and the letter it types, once it is time. */
    fun keysDue(now: Long): Pair<Int, String>? {
        if (keys.isEmpty() || !clicked || keysSent >= keys.size) return null
        if (seconds(now) < 2.0 + 0.7 * keysSent) return null
        val letter = keys[keysSent++]
        return (ANSI_KEY_CODES[letter] ?: 0) to letter.toString()
    }

    private fun seconds(now: Long) = (now - started) / 1.0e9

    /** What the window should hear by now and has not yet. */
    fun due(now: Long, @Suppress("UNUSED_PARAMETER") size: IntSize): List<WindowEvent> {
        if (!wantsType && keys.isEmpty()) return emptyList()
        val at = seconds(now)
        val found = ArrayList<WindowEvent>()
        val target = field
        if (!clicked && at >= 1.0 && target != null) {
            clicked = true
            val x = target.x + target.width / 2
            val y = target.y + target.height / 2
            found += WindowEvent(WindowEvent.POINTER_DOWN, x, y, 1, 0, 0, 0, "")
            found += WindowEvent(WindowEvent.POINTER_UP, x, y, 0, 0, 0, 0, "")
        }
        if (wantsType && clicked && letters < 5 && at >= 2.0 + 0.7 * letters) {
            val letter = ('a' + letters).toString()
            letters++
            found += WindowEvent(WindowEvent.TEXT_COMMIT, 0f, 0f, 0, 0, 0, 0, letter)
        }
        return found
    }

    /** True once, when it is time to take the window through its sizes. */
    fun resizeDue(now: Long): Boolean {
        if (!wantsResize || resized || seconds(now) < resizeAt) return false
        resized = true
        return true
    }

    /** True once the run has done what it was asked to and should close the window. */
    fun exitDue(now: Long): Boolean =
        wantsExit && (!wantsResize || resized) && seconds(now) >= exitAt

    /** Remembers where the field is, and says when the echo line has caught up. */
    fun noteElements(elements: List<AccessibleElement>) {
        if (field == null) field = elements.firstOrNull { it.role == ElementRole.FIELD }
        val echo = elements.firstOrNull { it.label.startsWith("typed: ") }?.label ?: return
        if (echo != lastTyped && echo.length > "typed: ".length) {
            lastTyped = echo
            LatencyTrace.mark("semantics shows '$echo'")
        }
    }
}

/** Where each letter is on an ANSI keyboard, for the keys posted to the window. */
private val ANSI_KEY_CODES = mapOf(
    'a' to 0, 's' to 1, 'd' to 2, 'f' to 3, 'h' to 4, 'g' to 5, 'z' to 6, 'x' to 7, 'c' to 8,
    'v' to 9, 'b' to 11, 'q' to 12, 'w' to 13, 'e' to 14, 'r' to 15, 'y' to 16, 't' to 17,
    'o' to 31, 'u' to 32, 'i' to 34, 'p' to 35, 'l' to 37, 'j' to 38, 'k' to 40, 'n' to 45,
    'm' to 46,
)
