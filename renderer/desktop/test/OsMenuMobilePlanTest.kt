package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuGroup
import dev.darkpyonix.composerust.foundation.OsMenuMappedEntry
import dev.darkpyonix.composerust.foundation.OsMenuPointRect
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.androidAnchorRect
import dev.darkpyonix.composerust.foundation.buildOsMenuGroups
import dev.darkpyonix.composerust.foundation.formatAndroidMenuTitle
import dev.darkpyonix.composerust.foundation.iosAnchorPoints
import dev.darkpyonix.composerust.foundation.normalizeOsMenu
import dev.darkpyonix.composerust.foundation.resolveOsMenuPath
import dev.darkpyonix.composerust.protocol.IconRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure Kotlin tests for the mobile OS menu mapping shared by Android's `PopupMenu` and iOS's
 * `UIMenu` presenters, plus end-to-end presenter contract checks through [FakeOsMenuPresenter].
 */
class OsMenuMobilePlanTest {

    private fun collectActions(groups: List<OsMenuGroup>): List<OsMenuMappedEntry.Action> {
        val out = mutableListOf<OsMenuMappedEntry.Action>()
        for (group in groups) {
            for (entry in group.entries) {
                when (entry) {
                    is OsMenuMappedEntry.Action -> out += entry
                    is OsMenuMappedEntry.Submenu -> out += collectActions(entry.groups)
                }
            }
        }
        return out
    }

    @Test
    fun fr33_2_mobile_groups_split_on_separators_and_preserve_indices() {
        val entries = normalizeOsMenu(
            listOf(
                Item(id = 10, label = "Cut", shortcutLabel = "Ctrl+X"),
                Item(id = 11, label = "Copy", enabled = false),
                Separator,
                Item(id = 12, label = "Wrap", checked = true, icon = IconRole.Check),
                Separator,
                Item(id = 13, label = "Delete", icon = IconRole.Delete),
            ),
        )

        val groups = buildOsMenuGroups(entries)
        assertEquals(3, groups.size)
        assertEquals(listOf(0, 1, 2), groups.map { it.groupIndex })

        val g0 = groups[0].entries
        assertEquals(2, g0.size)
        assertEquals(
            OsMenuMappedEntry.Action(
                path = listOf(0),
                label = "Cut",
                shortcutLabel = "Ctrl+X",
                enabled = true,
                checked = false,
            ),
            g0[0],
        )
        assertEquals(
            OsMenuMappedEntry.Action(
                path = listOf(1),
                label = "Copy",
                enabled = false,
            ),
            g0[1],
        )

        // Index 2 is Separator, so Wrap sits at index 3.
        assertEquals(
            listOf(
                OsMenuMappedEntry.Action(
                    path = listOf(3),
                    label = "Wrap",
                    checked = true,
                    icon = IconRole.Check,
                ),
            ),
            groups[1].entries,
        )

        // Index 4 is Separator, so Delete sits at index 5.
        assertEquals(
            listOf(
                OsMenuMappedEntry.Action(
                    path = listOf(5),
                    label = "Delete",
                    icon = IconRole.Delete,
                ),
            ),
            groups[2].entries,
        )
    }

    @Test
    fun fr33_2_mobile_groups_ignore_unnormalized_leading_trailing_and_doubled_separators() {
        val raw = listOf(
            Separator,
            Item(id = 1, label = "First"),
            Separator,
            Separator,
            Item(id = 2, label = "Second"),
            Separator,
        )
        val groups = buildOsMenuGroups(raw)
        assertEquals(2, groups.size)
        assertEquals(listOf(1), groups[0].entries.single().path)
        assertEquals(listOf(4), groups[1].entries.single().path)
        assertEquals(1, resolveOsMenuPath(raw, groups[0].entries.single().path)?.id)
        assertEquals(2, resolveOsMenuPath(raw, groups[1].entries.single().path)?.id)
    }

    @Test
    fun fr33_2_mobile_submenu_nesting_disabled_and_empty_submenus() {
        val entries = normalizeOsMenu(
            listOf(
                Item(id = 1, label = "Open"),
                Separator,
                Submenu(
                    label = "Export",
                    icon = IconRole.Share,
                    children = listOf(
                        Item(id = 2, label = "PDF"),
                        Separator,
                        Submenu(
                            label = "Images",
                            children = listOf(
                                Item(id = 3, label = "PNG", checked = true),
                                Item(id = 4, label = "JPEG", enabled = false),
                            ),
                        ),
                    ),
                ),
                Submenu(
                    label = "Disabled",
                    enabled = false,
                    icon = IconRole.Lock,
                    children = listOf(Item(id = 5, label = "Hidden")),
                ),
                Submenu(
                    label = "Empty",
                    children = listOf(Separator),
                ),
            ),
        )

        val groups = buildOsMenuGroups(entries)
        assertEquals(2, groups.size)

        val secondGroup = groups[1].entries
        assertEquals(3, secondGroup.size)

        val export = secondGroup[0] as OsMenuMappedEntry.Submenu
        assertTrue(export.enabled)
        assertEquals(listOf(2), export.path)
        assertEquals(IconRole.Share, export.icon)
        assertEquals(2, export.groups.size)
        assertEquals(listOf(2, 0), export.groups[0].entries.single().path)

        val images = export.groups[1].entries.single() as OsMenuMappedEntry.Submenu
        assertTrue(images.enabled)
        assertEquals(listOf(2, 2), images.path)
        assertEquals(1, images.groups.size)
        val png = images.groups[0].entries[0] as OsMenuMappedEntry.Action
        val jpeg = images.groups[0].entries[1] as OsMenuMappedEntry.Action
        assertEquals(listOf(2, 2, 0), png.path)
        assertTrue(png.checked)
        assertEquals(listOf(2, 2, 1), jpeg.path)
        assertFalse(jpeg.enabled)

        val disabled = secondGroup[1] as OsMenuMappedEntry.Submenu
        assertFalse(disabled.enabled)
        assertEquals(emptyList(), disabled.groups)
        assertEquals(IconRole.Lock, disabled.icon)

        val empty = secondGroup[2] as OsMenuMappedEntry.Submenu
        assertFalse(empty.enabled)
        assertEquals(emptyList(), empty.groups)

        // Every enabled mapped action path resolves back to the exact original item.
        val enabledActions = collectActions(groups).filter { it.enabled }
        assertEquals(listOf(1, 2, 3), enabledActions.map { resolveOsMenuPath(entries, it.path)?.id })
    }

    @Test
    fun fr33_2_android_title_formatting_and_anchor_geometry() {
        assertEquals("Save", formatAndroidMenuTitle("Save", null))
        assertEquals("Save", formatAndroidMenuTitle("Save", "   "))
        assertEquals("Save (Ctrl+S)", formatAndroidMenuTitle("Save", "Ctrl+S"))

        assertEquals(NativeRect(0, 0, 1, 1), androidAnchorRect(NativeRect(-10, -5, 0, 0)))
        assertEquals(NativeRect(24, 48, 120, 36), androidAnchorRect(NativeRect(24, 48, 120, 36)))

        assertEquals(
            OsMenuPointRect(10.0, 20.0, 60.0, 16.0),
            iosAnchorPoints(NativeRect(30, 60, 180, 48), scale = 3.0),
        )
        assertEquals(
            OsMenuPointRect(0.0, 0.0, 1.0, 1.0),
            iosAnchorPoints(NativeRect(-4, -8, 0, 0), scale = 2.0),
        )
        assertEquals(
            OsMenuPointRect(10.0, 20.0, 40.0, 12.0),
            iosAnchorPoints(NativeRect(10, 20, 40, 12), scale = 0.0),
        )
    }

    @Test
    fun fr33_2_fake_presenter_contract_covers_nested_choice_and_dismissal() {
        val entries = normalizeOsMenu(
            listOf(
                Item(id = 1, label = "Edit"),
                Separator,
                Submenu(
                    label = "Share",
                    children = listOf(
                        Item(id = 2, label = "Copy link"),
                        Separator,
                        Item(id = 3, label = "Mail"),
                    ),
                ),
            ),
        )
        val fake = FakeOsMenuPresenter()
        val results = mutableListOf<OsMenuResult>()
        val request = OsMenuRequest(
            entries = entries,
            anchor = NativeRect(12, 24, 100, 40),
            layoutDirection = LayoutDirection.Rtl,
        )

        val handle = fake.open(request) { results += it }
        val groups = buildOsMenuGroups(fake.last.request.entries)
        val mailAction = collectActions(groups).single { it.label == "Mail" }
        fake.choose(*mailAction.path.toIntArray())
        // Subsequent dismiss or app-side close after selection is ignored.
        fake.dismiss()
        handle.close()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(2, 2))), results)
        assertEquals(3, resolveOsMenuPath(entries, (results.single() as OsMenuResult.Chosen).path)?.id)

        val dismissResults = mutableListOf<OsMenuResult>()
        val handle2 = fake.open(request) { dismissResults += it }
        handle2.close()
        fake.choose(0)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), dismissResults)
    }
}
