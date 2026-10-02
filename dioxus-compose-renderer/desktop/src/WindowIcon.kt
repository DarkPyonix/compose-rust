package dioxus.compose.ui.platform

import java.awt.AWTEvent
import java.awt.Image
import java.awt.Toolkit
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent

/**
 * Puts the application's picture on every window it opens, not only the first one.
 *
 * A window with no picture of its own wears the toolkit's, which on Windows is the Java
 * coffee cup, and it shows up wherever the system lists windows rather than processes.
 * Setting it on the window the renderer creates is not enough for two reasons, and both
 * were visible before this: a dialog or a popup that becomes a platform window of its own
 * is a window nobody set it on, and the picture arrives as an asset some time after the
 * first window is already up, so anything opened in between was born without one.
 *
 * So the picture is held here rather than on a window, applied to everything already open
 * whenever it changes, and applied to anything that opens afterwards.
 */
internal object WindowIcon {

    private var images: List<Image> = emptyList()
    private var listening = false

    /**
     * Says what the application's picture is, and puts it on every window already open.
     *
     * Idempotent: the same picture twice does nothing the second time, which matters
     * because the effect that calls this runs again whenever composition says it might
     * have changed.
     */
    @Synchronized
    fun use(picture: Image?) {
        val next = listOfNotNull(picture)
        if (next == images) return
        images = next
        listen()
        applyTo(AwtWindow.getWindows().asList())
    }

    /** Puts the current picture on [windows] that do not already have it. */
    @Synchronized
    fun applyTo(windows: List<AwtWindow>) {
        if (images.isEmpty()) return
        applyIcon(windows, images, { it.iconImages.orEmpty() }, { window, next -> window.iconImages = next })
    }

    private fun listen() {
        if (listening) return
        listening = true
        // Every window passes through here as it opens, whoever created it. Compose makes
        // a platform window for a popup that will not fit inside its parent, and those
        // never went through the code that stood the first window up.
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { event ->
                val window = (event as? WindowEvent)?.window
                if (window != null && event.id == WindowEvent.WINDOW_OPENED) {
                    applyTo(listOf(window))
                }
            },
            AWTEvent.WINDOW_EVENT_MASK,
        )
    }
}

/**
 * Gives [ours] to each of [windows] that does not already carry it, and says how many
 * that was.
 *
 * Separated from AWT so it can be tested. A window cannot be made in a test without a
 * display, and the part worth fixing is which windows are chosen rather than how the
 * picture is put on one.
 */
internal fun <W> applyIcon(
    windows: List<W>,
    ours: List<Image>,
    current: (W) -> List<Image>,
    assign: (W, List<Image>) -> Unit,
): Int {
    if (ours.isEmpty()) return 0
    var changed = 0
    for (window in windows) {
        if (current(window) == ours) continue
        assign(window, ours)
        changed++
    }
    return changed
}
