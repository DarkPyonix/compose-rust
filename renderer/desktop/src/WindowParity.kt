package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

// What both macOS windows decide the same way, once.
//
// The GraalVM window and the Kotlin/Native window each make the operating system calls in
// their own language. Everything that is a decision rather than a call lives here and is
// reached from both: the smallest size a window is given, when the Dock icon is ready to be
// put on, how a change of the system's light or dark setting is noticed, and what text on
// the clipboard means. Nothing here names AppKit, AWT or GraalVM.

/**
 * The smallest content area the window may be dragged to, in points, or null where the
 * application named none. A measurement of zero means it did not ask; one axis may be
 * named without the other.
 */
fun contentMinimum(minWidth: Int, minHeight: Int): Pair<Double, Double>? =
    if (minWidth <= 0 && minHeight <= 0) {
        null
    } else {
        minWidth.coerceAtLeast(0).toDouble() to minHeight.coerceAtLeast(0).toDouble()
    }

/**
 * Puts the application's picture on the Dock, once the asset it named has arrived.
 *
 * The id is known from the first batch and the picture a little later, so [tryApply] is
 * asked until it succeeds and does nothing afterwards. What it looks the picture up in and
 * where it puts it are parameters, so the waiting is the same on both windows and can be
 * exercised without a Dock.
 */
class DockIcon<T : Any>(
    private val lookup: (Int) -> T?,
    private val apply: (T) -> Unit,
) {
    var applied = false
        private set

    /** True once the picture is on the Dock, from this call or an earlier one. */
    fun tryApply(assetId: Int): Boolean {
        if (applied) return true
        if (assetId == 0) return false
        val picture = lookup(assetId) ?: return false
        apply(picture)
        applied = true
        return true
    }
}

/**
 * Whether the system is in dark mode, kept current.
 *
 * Compose's own answer is read once and never looks again, so a window opened in light
 * mode stays light while every other application on screen changes. This reads it again
 * when told to, writes the answer into snapshot state, which recomposes whatever read it,
 * and asks [requestFrame] for a frame so that what changed is drawn without waiting for
 * the next input.
 *
 * [read] is what touches the system. How [refresh] gets called is the window's: the
 * Kotlin/Native one subscribes to appearance changes, the GraalVM one polls.
 */
class SystemDarkMonitor(
    private val read: () -> Boolean,
    private val requestFrame: () -> Unit = {},
) {
    private val state = mutableStateOf(read())

    /** True while the system is dark. */
    val dark: State<Boolean> get() = state

    /** Reads the system again, and when the answer moved, publishes it and asks for a frame. */
    fun refresh() {
        val now = read()
        if (now != state.value) {
            state.value = now
            requestFrame()
        }
    }
}

/** True for the appearance names of the dark family: dark Aqua and its vibrant and contrast forms. */
fun isDarkAppearanceName(name: String?): Boolean = name?.contains("Dark") == true

/**
 * The text on a pasteboard, which is all a text field asks for.
 *
 * A seam so that what the clipboard does with it is one set of rules for both windows and
 * can be exercised without a window server.
 */
interface TextPasteboard {
    /** The text on it, or null where it holds nothing or holds something that is not text. */
    fun read(): String?

    /** Replaces what is on it with [text]. */
    fun write(text: String)
}

/** The text to paste, or null: an empty string is nothing, not a paste of nothing. */
fun TextPasteboard.pasteText(): String? = read()?.takeIf { it.isNotEmpty() }

/** Whether there is text to paste, which is what decides if Paste is offered. */
fun TextPasteboard.hasText(): Boolean = pasteText() != null

/** Stores [text], where null clears the clipboard as Compose asks it to. */
fun TextPasteboard.copyText(text: String?) = write(text.orEmpty())
