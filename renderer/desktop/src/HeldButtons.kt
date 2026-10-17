package dev.darkpyonix.composerust.ui.platform

/**
 * The mouse buttons the scene has been told are down, kept honest against the system.
 *
 * A menu the system puts up runs its own event loop until it closes, and the release of the
 * button that opened it, or of the one that chose an item, is taken by that loop and never
 * reaches the window. A scene that heard the press and not the release believes the button
 * is still held, and from then on a click is a second button pressed on top of the first:
 * nothing it is pressed on ever sees a whole click, so every button in the window stops
 * working.
 *
 * The native image's window does not have this problem, because every event it records
 * carries `NSEvent.pressedMouseButtons`, the system's own answer, and the scene takes the
 * buttons from that. The Kotlin/Native window sends a press or a release per event, so it
 * writes down what it sent here and asks [stale] before each event and when a menu closes.
 *
 * Keyed by the system's button numbers: 0 primary, 1 secondary, 2 the middle button.
 */
class HeldButtons<B : Any> {
    /** By the system's button number, what the scene was told was pressed with it. */
    private val held = mutableMapOf<Int, B>()

    /**
     * The scene was told [button] went down, by the system's button [systemButton]. They
     * differ for a click with Control held, which the system reports as the primary button
     * and the scene is told is the secondary one.
     */
    fun pressed(systemButton: Int, button: B) {
        held[systemButton] = button
    }

    /** The system's button [systemButton] came up, and the scene is being told. */
    fun released(systemButton: Int): B? = held.remove(systemButton)

    /** Whether the scene believes any button is down. */
    val anyHeld: Boolean get() = held.isNotEmpty()

    /**
     * What the scene believes is down that [systemPressed], the system's mask of the
     * buttons down now, says is not, in button order. They are forgotten here; the caller
     * sends the scene a release for each.
     */
    fun stale(systemPressed: Long): List<B> {
        val gone = held.keys.filter { it !in 0..63 || systemPressed and (1L shl it) == 0L }.sorted()
        return gone.mapNotNull { held.remove(it) }
    }

    companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val TERTIARY = 2
    }
}
