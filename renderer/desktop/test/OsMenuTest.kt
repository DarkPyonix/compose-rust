@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.foundation.LocalOsMenuPresenter
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.normalizeOsMenu
import dev.darkpyonix.composerust.foundation.resolveOsMenuPath
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.ui.platform.NativeMenuEntry
import dev.darkpyonix.composerust.ui.platform.toOsMenuEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The entry menu contract every OS menu presenter codes against: the list rules they all
 * apply, how a chosen path comes back to an item, and one result per menu. No window needed.
 */
class OsMenuTest {

    private fun item(id: Int, enabled: Boolean = true) = Item(id = id, label = "Item $id", enabled = enabled)

    private val anchor = NativeRect(10, 20, 120, 32)

    private val menu = listOf(
        item(1),
        Separator,
        item(2, enabled = false),
        Submenu("More", listOf(item(3), Separator, item(4))),
        Submenu("Off", listOf(item(5)), enabled = false),
    )

    @Test
    fun fr33_2_separators_stay_only_between_two_entries_at_every_level() {
        val lines = listOf(Separator, Separator, item(1), Separator, Separator, item(2), Separator)
        val kept = listOf(item(1), Separator, item(2))
        assertEquals(kept, normalizeOsMenu(lines))
        assertEquals(listOf<OsMenuEntry>(Submenu("More", kept)), normalizeOsMenu(listOf(Submenu("More", lines))))
        assertEquals(emptyList<OsMenuEntry>(), normalizeOsMenu(listOf(Separator, Separator)))
    }

    @Test
    fun fr33_2_empty_or_disabled_submenu_is_disabled_with_no_children() {
        val list = listOf(
            item(1),
            Separator,
            Submenu("Empty", emptyList()),
            Submenu("Only lines", listOf(Separator, Separator)),
            Submenu("Recent", listOf(item(2)), enabled = false, icon = IconRole.List),
            Separator,
            item(3),
        )
        assertEquals(
            listOf(
                item(1),
                Separator,
                Submenu("Empty", emptyList(), enabled = false),
                Submenu("Only lines", emptyList(), enabled = false),
                Submenu("Recent", emptyList(), enabled = false, icon = IconRole.List),
                Separator,
                item(3),
            ),
            normalizeOsMenu(list),
            "a submenu nobody can open stays in its place, greyed, with the separators around it",
        )
    }

    @Test
    fun fr33_2_labels_are_one_line_and_state_is_kept() {
        val list = listOf(
            Item(1, "Save\nall", shortcutLabel = "Ctrl+\tS", enabled = false),
            Item(2, "Close", shortcutLabel = "  ", checked = true, icon = IconRole.Check),
        )
        assertEquals(
            listOf(
                Item(1, "Save all", shortcutLabel = "Ctrl+ S", enabled = false),
                Item(2, "Close", shortcutLabel = null, checked = true, icon = IconRole.Check),
            ),
            normalizeOsMenu(list),
        )
    }

    @Test
    fun fr33_2_path_resolves_to_the_item() {
        assertEquals(item(1), resolveOsMenuPath(menu, listOf(0)))
        assertEquals(item(3), resolveOsMenuPath(menu, listOf(3, 0)))
        assertEquals(4, resolveOsMenuPath(menu, listOf(3, 2))?.id)
    }

    @Test
    fun fr33_2_path_rejects_what_cannot_be_chosen() {
        assertNull(resolveOsMenuPath(menu, emptyList()), "an empty path")
        assertNull(resolveOsMenuPath(menu, listOf(9)), "an index past the end")
        assertNull(resolveOsMenuPath(menu, listOf(-1)), "a negative index")
        assertNull(resolveOsMenuPath(menu, listOf(1)), "a separator")
        assertNull(resolveOsMenuPath(menu, listOf(2)), "a disabled item")
        assertNull(resolveOsMenuPath(menu, listOf(3)), "a submenu itself")
        assertNull(resolveOsMenuPath(menu, listOf(3, 1)), "a separator inside a submenu")
        assertNull(resolveOsMenuPath(menu, listOf(4, 0)), "an item inside a disabled submenu")
        assertNull(resolveOsMenuPath(menu, listOf(0, 0)), "a step into an item")
        assertNull(resolveOsMenuPath(menu, listOf(3, 0, 0)), "a path deeper than the menu")
    }

    @Test
    fun fr33_2_fake_records_the_request_and_reports_a_choice_once() {
        val fake = FakeOsMenuPresenter()
        val heard = mutableListOf<OsMenuResult>()
        val request = OsMenuRequest(normalizeOsMenu(menu), anchor)
        val handle = fake.open(request) { heard += it }
        assertSame(request, fake.last.request)
        assertTrue(fake.last.isOpen)

        fake.choose(3, 0)
        fake.choose(0)
        fake.dismiss()
        handle.close()
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(3, 0))), heard)
        assertFalse(fake.last.isOpen)
    }

    @Test
    fun fr33_2_dismissal_by_user_or_app_is_reported_once() {
        val fake = FakeOsMenuPresenter()
        val heard = mutableListOf<OsMenuResult>()
        val byUser = fake.open(OsMenuRequest(menu, anchor)) { heard += it }
        fake.dismiss()
        fake.dismiss()
        byUser.close()
        val byApp = fake.open(OsMenuRequest(menu, anchor)) { heard += it }
        byApp.close()
        byApp.close()
        fake.choose(0)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed, OsMenuResult.Dismissed), heard)
        assertEquals(3, fake.closes)
    }

    @Test
    fun fr33_2_no_presenter_is_provided_by_default() = runComposeUiTest {
        var seen: OsMenuPresenter? = FakeOsMenuPresenter()
        setContent { seen = LocalOsMenuPresenter.current }
        waitForIdle()
        assertNull(seen, "without a presenter, entry menus stay drawn")
    }

    @Test
    fun fr33_2_text_menu_entry_converts_to_an_item() {
        assertEquals(Item(id = 2, label = "Paste", enabled = false), NativeMenuEntry("Paste", enabled = false).toOsMenuEntry(2))
    }
}
