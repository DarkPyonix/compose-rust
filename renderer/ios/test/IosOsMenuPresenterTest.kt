package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.OsMenuResultOnce
import dev.darkpyonix.composerust.foundation.buildOsMenuGroups
import dev.darkpyonix.composerust.foundation.normalizeOsMenu
import dev.darkpyonix.composerust.protocol.IconRole
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAction
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementAttributesDisabled
import platform.UIKit.UIMenuElementState
import platform.UIKit.UIMenuOptionsDisplayInline
import platform.UIKit.UIWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosOsMenuPresenterTest {

    @Test
    fun fr33_2_ios_builds_inline_separator_groups_states_and_submenus() {
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, 400.0, 800.0))
        val presenter = IosOsMenuPresenter(window)
        val entries = normalizeOsMenu(
            listOf(
                Item(id = 1, label = "Save", shortcutLabel = "Cmd+S", checked = true, icon = IconRole.Check),
                Item(id = 2, label = "Paste", enabled = false),
                Separator,
                Submenu(
                    label = "Export",
                    icon = IconRole.Share,
                    children = listOf(
                        Item(id = 3, label = "PDF"),
                        Separator,
                        Item(id = 4, label = "PNG"),
                    ),
                ),
                Submenu(
                    label = "Recent",
                    enabled = false,
                    icon = IconRole.History,
                    children = listOf(Item(id = 5, label = "Doc")),
                ),
            ),
        )

        val results = mutableListOf<OsMenuResult>()
        val menu = presenter.buildUiMenu(
            groups = buildOsMenuGroups(entries),
            once = OsMenuResultOnce { results += it },
        )

        // Two separator-delimited top-level groups become two inline UIMenu sections.
        assertEquals(2, menu.children.size)
        val firstSection = menu.children[0] as UIMenu
        val secondSection = menu.children[1] as UIMenu
        assertEquals(UIMenuOptionsDisplayInline, firstSection.options)
        assertEquals(UIMenuOptionsDisplayInline, secondSection.options)

        val save = firstSection.children[0] as UIAction
        assertEquals("Save", save.title)
        assertEquals("Cmd+S", save.discoverabilityTitle)
        assertEquals(UIMenuElementState.UIMenuElementStateOn, save.state)
        assertEquals(0uL, save.attributes and UIMenuElementAttributesDisabled)

        val paste = firstSection.children[1] as UIAction
        assertEquals("Paste", paste.title)
        assertEquals(UIMenuElementState.UIMenuElementStateOff, paste.state)
        assertEquals(UIMenuElementAttributesDisabled, paste.attributes and UIMenuElementAttributesDisabled)

        val export = secondSection.children[0] as UIMenu
        assertEquals("Export", export.title)
        assertEquals(2, export.children.size)
        assertEquals(UIMenuOptionsDisplayInline, (export.children[0] as UIMenu).options)
        assertEquals(UIMenuOptionsDisplayInline, (export.children[1] as UIMenu).options)

        // Disabled submenus cannot be represented as disabled UIMenu, so iOS emits a disabled UIAction.
        val recent = secondSection.children[1] as UIAction
        assertEquals("Recent", recent.title)
        assertEquals(UIMenuElementAttributesDisabled, recent.attributes and UIMenuElementAttributesDisabled)
    }

    @Test
    fun fr33_2_ios_open_and_handle_close_removes_anchor_and_reports_dismissed_once() {
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, 400.0, 800.0))
        val presenter = IosOsMenuPresenter(window)
        val baselineSubviews = window.subviews.size
        val results = mutableListOf<OsMenuResult>()

        val request = OsMenuRequest(
            entries = normalizeOsMenu(listOf(Item(id = 1, label = "Copy"))),
            anchor = NativeRect(20, 40, 120, 44),
            layoutDirection = LayoutDirection.Ltr,
        )
        val handle = presenter.open(request) { results += it }
        assertTrue(window.subviews.size == baselineSubviews + 1)

        handle.close()
        handle.close()

        assertEquals(baselineSubviews, window.subviews.size)
        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), results)
    }
}
