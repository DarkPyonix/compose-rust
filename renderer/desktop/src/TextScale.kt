package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.Density

// How much larger than its default size the reader has asked text to be.
//
// Nothing in this file names a platform. Each desktop has its own place the setting lives
// (Windows' text size, GNOME's text scaling factor, KDE's font DPI, an in-app zoom on macOS)
// and its own way of saying it changed; what a window does with the answer is the same on
// all of them, and it is written once here so that every window builds its Density the
// same way. Shared with the Kotlin/Native windows through a symlink, which is why it
// names nothing of GraalVM either.

/**
 * The reader's text scale, as a window holds it between frames.
 *
 * [read] is asked once when this is made and again on every [refresh]. It is called once a
 * turn of a window's loop, so it has to be cheap: each platform's source keeps its last
 * answer and goes back to the system only when the system has said something changed.
 *
 * A window builds every Density it hands its scene with [density], so the display's scale
 * and the reader's text scale always arrive together and a frame never draws with one of
 * them stale.
 */
class TextScale(private val read: () -> Float) {

    /** The scale text is drawn at, where 1 is the size the design asked for. */
    var fontScale: Float = sanitizeFontScale(read())
        private set

    /**
     * Asks the source again, and answers whether the scale changed.
     *
     * True is the window's cue to draw: nothing in the scene has invalidated, but every
     * piece of text in it is about to be laid out again at another size.
     */
    fun refresh(): Boolean {
        val next = sanitizeFontScale(read())
        if (next == fontScale) return false
        fontScale = next
        return true
    }

    /** The Density a scene is given: the display's pixels per point, and this font scale. */
    fun density(scale: Float): Density = Density(scale, fontScale)
}

/**
 * Keeps a scale a platform reported inside what text can be drawn at.
 *
 * A setting that is missing, unreadable or nonsense is read as the default rather than
 * drawn: a zero would lay every line out at no height, and a scale of a hundred is a
 * misread setting rather than anyone's choice. The bounds are wider than any of the
 * platforms offer, so nothing a reader can actually pick is cut.
 */
fun sanitizeFontScale(value: Float): Float = when {
    value.isNaN() || value <= 0f -> 1f
    value < MIN_FONT_SCALE -> MIN_FONT_SCALE
    value > MAX_FONT_SCALE -> MAX_FONT_SCALE
    else -> value
}

const val MIN_FONT_SCALE = 0.5f
const val MAX_FONT_SCALE = 4f
