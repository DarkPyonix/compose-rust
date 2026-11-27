package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.foundation.BlockingOsMenu
import dev.darkpyonix.composerust.foundation.BlockingOsMenuPresenter
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * On macOS and Windows `NSMenu` and `TrackPopupMenuEx` hold the thread while the menu is
 * up, so the presenter puts the menu up from the window's loop after [BlockingOsMenuPresenter.open]
 * returns, and keeps the contract's one result per menu around that.
 */
class BlockingOsMenuPresenterTest {

    /** A platform menu that answers [answer] and writes down what it was asked. */
    private class Platform(var answer: (OsMenuRequest) -> List<Int>? = { null }) : BlockingOsMenu {
        val shown = mutableListOf<OsMenuRequest>()
        var closes = 0

        override fun show(request: OsMenuRequest): List<Int>? {
            shown += request
            return answer(request)
        }

        override fun close() {
            closes++
        }
    }

    private val entries = listOf(
        Item(id = 1, label = "Open", shortcutLabel = "Ctrl+O"),
        Separator,
        Item(id = 2, label = "Save", enabled = false),
        Submenu("Recent", listOf(Item(id = 3, label = "a.txt"), Item(id = 4, label = "b.txt"))),
    )

    private fun request(list: List<dev.darkpyonix.composerust.foundation.OsMenuEntry> = entries) =
        OsMenuRequest(list, NativeRect(10, 20, 120, 32))

    @Test
    fun fr33_2_a_blocking_menu_goes_up_only_after_open_returns() {
        val platform = Platform()
        val presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request()) {}
        assertTrue(platform.shown.isEmpty(), "open never blocks the caller")
        assertTrue(presenter.waiting)
        presenter.runPending()
        assertEquals(1, platform.shown.size)
        assertFalse(presenter.waiting)
    }

    @Test
    fun fr33_2_the_chosen_path_comes_back_once() {
        val results = mutableListOf<OsMenuResult>()
        val presenter = BlockingOsMenuPresenter(Platform { listOf(3, 1) })
        val handle = presenter.open(request()) { results += it }
        presenter.runPending()
        handle.close()
        presenter.runPending()
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(3, 1))), results)
    }

    @Test
    fun fr33_2_closing_with_nothing_chosen_is_a_dismissal() {
        val results = mutableListOf<OsMenuResult>()
        val presenter = BlockingOsMenuPresenter(Platform { null })
        presenter.open(request()) { results += it }
        presenter.runPending()
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
    }

    @Test
    fun fr33_2_a_path_to_nothing_choosable_is_a_dismissal() {
        for (path in listOf(listOf(1), listOf(2), listOf(3), listOf(9), listOf(3, 5), emptyList())) {
            val results = mutableListOf<OsMenuResult>()
            val presenter = BlockingOsMenuPresenter(Platform { path })
            presenter.open(request()) { results += it }
            presenter.runPending()
            assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results, "path $path")
        }
    }

    @Test
    fun fr33_2_closed_before_it_goes_up_dismisses_and_never_shows() {
        val platform = Platform()
        val results = mutableListOf<OsMenuResult>()
        val presenter = BlockingOsMenuPresenter(platform)
        val handle = presenter.open(request()) { results += it }
        handle.close()
        presenter.runPending()
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
        assertTrue(platform.shown.isEmpty())
        assertEquals(0, platform.closes, "no platform menu was up to take down")
    }

    @Test
    fun fr33_2_closed_while_up_takes_the_platform_menu_down_and_chooses_nothing() {
        val results = mutableListOf<OsMenuResult>()
        lateinit var close: () -> Unit
        // The platform's loop runs the application while the menu is up; the node leaves then.
        val platform = Platform { close(); listOf(0) }
        val presenter = BlockingOsMenuPresenter(platform)
        val handle = presenter.open(request()) { results += it }
        close = handle::close
        presenter.runPending()
        assertEquals(1, platform.closes)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
    }

    @Test
    fun fr33_2_the_platform_gets_the_normalised_list() {
        val platform = Platform()
        val presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request(listOf(Separator, Item(id = 1, label = "A\tB"), Separator))) {}
        presenter.runPending()
        assertEquals(listOf<dev.darkpyonix.composerust.foundation.OsMenuEntry>(Item(id = 1, label = "A B")), platform.shown.single().entries)
    }

    @Test
    fun fr33_2_an_empty_list_is_dismissed_without_a_menu() {
        val platform = Platform()
        val results = mutableListOf<OsMenuResult>()
        val presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request(listOf(Separator))) { results += it }
        presenter.runPending()
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
        assertTrue(platform.shown.isEmpty())
    }

    @Test
    fun fr33_2_a_newer_menu_replaces_one_still_waiting() {
        val platform = Platform()
        val first = mutableListOf<OsMenuResult>()
        val presenter = BlockingOsMenuPresenter(platform)
        presenter.open(request()) { first += it }
        presenter.open(request(listOf(Item(id = 7, label = "Other")))) {}
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), first)
        presenter.runPending()
        assertEquals("Other", (platform.shown.single().entries.single() as Item).label)
    }

    @Test
    fun fr33_2_a_menu_whose_window_went_away_is_dismissed_and_never_goes_up() {
        val platform = Platform { listOf(0) }
        val presenter = BlockingOsMenuPresenter(platform)
        val results = mutableListOf<OsMenuResult>()
        presenter.open(request()) { results += it }
        presenter.dismissPending()
        assertFalse(presenter.waiting)
        presenter.runPending()
        assertTrue(platform.shown.isEmpty(), "a dismissed menu is not put up")
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
        presenter.dismissPending()
        assertEquals(1, results.size, "dismissing with nothing waiting reports nothing")
    }
}
