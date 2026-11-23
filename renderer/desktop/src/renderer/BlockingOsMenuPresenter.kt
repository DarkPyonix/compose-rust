package dev.darkpyonix.composerust.foundation

// On macOS and Windows `NSMenu.popUpMenuPositioningItem` and `TrackPopupMenuEx` hold
// the thread in the system's menu loop until the menu closes. Calling one from inside
// composition or an effect would run the whole application from there, so [open] only writes
// the menu down and the window's loop puts it up between frames with [runPending].

/** A platform menu that is up for as long as [show] runs. */
interface BlockingOsMenu {
    /**
     * Puts up [request]'s entries (already normalised) and answers the path of the item chosen,
     * or null when the menu closed with nothing chosen. Runs the system's menu loop until then.
     */
    fun show(request: OsMenuRequest): List<Int>?

    /** Takes the menu [show] has up down, so [show] returns. Called only while it is up. */
    fun close()
}

/**
 * The [OsMenuPresenter] over a [BlockingOsMenu]. One menu at a time: a newer [open] dismisses a
 * menu that is still waiting to go up. Everything here runs on the UI thread.
 */
class BlockingOsMenuPresenter(private val platform: BlockingOsMenu) : OsMenuPresenter {

    private class Menu(val request: OsMenuRequest, val once: OsMenuResultOnce)

    private var next: Menu? = null
    private var up: Menu? = null

    /** Whether a menu waits for [runPending]: the window's loop must not sleep on its events. */
    val waiting: Boolean get() = next != null

    override fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle {
        next?.once?.dismissed()
        next = null
        val entries = normalizeOsMenu(request.entries)
        val menu = Menu(request.copy(entries = entries), OsMenuResultOnce(onResult))
        if (entries.isEmpty()) {
            menu.once.dismissed()
            return OsMenuHandle {}
        }
        next = menu
        return OsMenuHandle {
            if (menu.once.reported) return@OsMenuHandle
            menu.once.dismissed()
            if (next === menu) next = null
            if (up === menu) platform.close()
        }
    }

    /**
     * Puts up the menu waiting, if any, and reports its result. Blocks while it is up. The
     * window's loop calls it after a frame, outside composition, layout and drawing.
     */
    fun runPending() {
        val menu = next ?: return
        next = null
        if (menu.once.reported) return
        up = menu
        val path = try {
            platform.show(menu.request)
        } finally {
            up = null
        }
        if (path != null && resolveOsMenuPath(menu.request.entries, path) != null) {
            menu.once.chosen(path)
        } else {
            menu.once.dismissed()
        }
    }
}
