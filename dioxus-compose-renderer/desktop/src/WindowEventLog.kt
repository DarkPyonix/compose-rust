package dioxus.compose.ui.platform

/**
 * Everything a window heard, read from the display server in one place.
 *
 * Why it is one place, and why that is worth a class of its own: a window drawn inside its
 * own resize draws from the middle of reading an event. Anything reached from that drawing
 * which also reads the server takes the rest of the drag out of the queue while the turn
 * that is handling one size change is still running, and the sizes it took are then handled
 * by nobody. The window keeps moving under the hand and stops being painted, which looks
 * like the drawing lagging rather than like a queue being drained twice.
 *
 * So reading is guarded rather than trusted. [read] is the only thing that talks to the
 * server, and a [read] reached from inside a [read] does nothing at all.
 *
 * The events themselves are held rather than delivered, for a different reason: the frame
 * loop wants the scene's own pending work run before it is given new input, so that a list
 * which asked for rows on the last frame has them in hand before this one is measured.
 * [drain] is where the loop takes them.
 */
internal class WindowEventLog {

    private val heard = ArrayList<WindowEvent>()
    private var reading = false

    /**
     * Lets [fromTheServer] read whatever the display server has, unless a read is running.
     *
     * False says a read was already in progress and this one did nothing, which is not a
     * failure: it is the guard doing its job.
     */
    fun read(fromTheServer: () -> Unit): Boolean {
        if (reading) return false
        reading = true
        try {
            fromTheServer()
        } finally {
            reading = false
        }
        return true
    }

    /** Writes down one thing the window heard, in the order it arrived. */
    fun heard(event: WindowEvent) {
        heard.add(event)
    }

    /**
     * Moves everything heard since the last time into [into], leaving this empty.
     *
     * Into a list the caller keeps rather than out of a list this makes, because this runs
     * once a frame for as long as the window is open and a frame is not the place to be
     * allocating a container per turn.
     */
    fun drain(into: MutableList<WindowEvent>) {
        into.addAll(heard)
        heard.clear()
    }
}
