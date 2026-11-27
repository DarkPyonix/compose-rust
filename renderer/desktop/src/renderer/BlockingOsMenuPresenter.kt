package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
     * Dismisses the menu waiting to go up, if any, without putting it up: for a window that
     * went away before its loop came round, so the menu never opens over a window that is
     * not there.
     */
    fun dismissPending() {
        val menu = next ?: return
        next = null
        menu.once.dismissed()
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

/**
 * Gives [content] the window's entry menu presenter as [LocalOsMenuPresenter]. Every window
 * of a host wraps its own content in this with its own presenter, because a menu's anchor is
 * in that one window's pixels.
 */
@Composable
fun ProvideOsMenu(presenter: OsMenuPresenter?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOsMenuPresenter provides presenter, content = content)
}

/**
 * What a window's loop does with [presenter] after it drew and presented a frame: puts up the
 * menu waiting, if any, and blocks while it is up. The loop calls it outside composition,
 * layout and drawing, with no lock held that the menu's own loop could need.
 *
 * True when the loop must not sleep on its next wait for events, which it treats as it treats
 * a frame that is due: a menu just closed and what was chosen needs a frame to show, or a newer
 * menu was asked for while this one was up and waits for the next turn.
 */
fun runOsMenuAfterFrame(presenter: BlockingOsMenuPresenter): Boolean {
    val had = presenter.waiting
    presenter.runPending()
    return had || presenter.waiting
}
