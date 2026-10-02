package dioxus.compose.ui.platform

/**
 * The frame a window manager is holding, and the one rule about letting it go.
 *
 * On X11 the manager moves a window's frame the moment the pointer moves, and what is
 * inside that frame is whatever was last drawn. The two reach the screen in separate steps
 * unless something holds them together, and the strip of window that has been claimed and
 * not yet painted is as wide as the speed of the hand times how late the painting is.
 *
 * What holds them together is a counter. The manager hands out a number with a resize,
 * holds the frame it was about to show, and shows it once the counter carries that number.
 * So the number is a debt: it is owed from the moment the manager asks for it, and the one
 * thing that must never happen is paying it before the drawing exists, because then the
 * manager shows a window whose inside belongs to the previous size. The other thing that
 * must never happen is not paying it at all: a manager waiting on a counter nobody sets
 * holds the window until it gives up on it, which a reader sees as a window that has
 * frozen mid-drag.
 *
 * Both of those are invisible from inside the process and neither is visible in a
 * screenshot, so the ordering lives here rather than in the window: [frameDrawn] hands the
 * drawing over and then pays, [noFrame] pays for a request that produced nothing, and a
 * test can watch the order of the two.
 *
 * This is what X11 has where macOS has a layer that presents with the transaction it is
 * part of.
 */
internal class ResizeSync(
    /** Hands the drawing to the display server. */
    private val present: () -> Unit,
    /** Sets the counter the manager is watching. */
    private val tell: (Long) -> Unit,
) {

    private var owed = false
    private var owedValue = 0L

    /** True while a manager is waiting to be told that a size it asked for has been drawn. */
    val isOwed: Boolean get() = owed

    /**
     * Takes the number the manager handed out with a resize.
     *
     * A second request before the first was paid replaces it. The manager only ever waits
     * on its newest number, and paying an older one tells it about a size that has already
     * been superseded.
     */
    fun requested(value: Long) {
        owedValue = value
        owed = true
    }

    /**
     * Ends a frame: the drawing goes to the server, and then the manager is told.
     *
     * In that order, and that is the whole point of this class. What the manager is waiting
     * to hear is that the drawing for the size it gave us has been handed over, so telling
     * it first is telling it about a frame that does not exist yet.
     */
    fun frameDrawn() {
        present()
        pay()
    }

    /**
     * Pays for a request that produced no frame.
     *
     * Two things do this and neither is a failure: a frame refused because one was already
     * being drawn, and a size change that turned out not to be one (a window that was moved,
     * or told again what it already was). Either way the manager asked and has to be
     * answered.
     */
    fun noFrame() = pay()

    private fun pay() {
        if (!owed) return
        owed = false
        tell(owedValue)
    }
}
