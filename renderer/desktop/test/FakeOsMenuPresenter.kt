package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.OsMenuHandle
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.OsMenuResultOnce

/**
 * An OS menu presenter for tests. It writes down every menu put up and leaves the test to
 * choose or dismiss later, as a listener based platform (`PopupMenu`, `UIMenu`) does. The once
 * rule is the production [OsMenuResultOnce], so reporting twice reaches the caller once.
 */
class FakeOsMenuPresenter : OsMenuPresenter {

    /** One menu that was put up: what it was asked to show, and every result it reported. */
    class Shown(val request: OsMenuRequest) {
        val results = mutableListOf<OsMenuResult>()
        internal lateinit var once: OsMenuResultOnce

        /** Whether the menu is still up: nothing has been reported yet. */
        val isOpen: Boolean get() = !once.reported
    }

    /** Every menu put up, oldest first. */
    val shown = mutableListOf<Shown>()

    /** The menu put up last. Fails when none was. */
    val last: Shown get() = shown.lastOrNull() ?: error("no menu was put up")

    /** How many times the application side closed a menu. */
    var closes: Int = 0
        private set

    override fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle {
        val menu = Shown(request)
        menu.once = OsMenuResultOnce { result ->
            menu.results += result
            onResult(result)
        }
        shown += menu
        return OsMenuHandle {
            closes++
            menu.once.dismissed()
        }
    }

    /** The user chooses the entry at [path] in the last menu. */
    fun choose(vararg path: Int) = last.once.chosen(path.toList())

    /** The user closes the last menu without choosing. */
    fun dismiss() = last.once.dismissed()
}
