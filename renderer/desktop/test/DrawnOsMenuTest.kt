@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.DRAWN_OS_MENU_POPUP_TAG
import dev.darkpyonix.composerust.foundation.DrawnOsMenuHost
import dev.darkpyonix.composerust.foundation.DrawnOsMenuPresenter
import dev.darkpyonix.composerust.foundation.LocalOsMenuPresenter
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.drawnOsMenuEntryTestTag
import dev.darkpyonix.composerust.foundation.drawnOsMenuPopupTestTag
import dev.darkpyonix.composerust.foundation.normalizeOsMenu
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import dev.darkpyonix.composerust.ui.platform.NativeContextMenuRepresentation
import dev.darkpyonix.composerust.ui.platform.NativeMenuEntry
import dev.darkpyonix.composerust.ui.platform.OsMenuContextMenuRepresentation
import dev.darkpyonix.composerust.ui.platform.PasteboardClipboard
import dev.darkpyonix.composerust.ui.platform.WindowClipboardImpl
import dev.darkpyonix.composerust.ui.platform.WindowClipboardManager
import dev.darkpyonix.composerust.ui.platform.toOsMenuEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the drawn entry menu used on Linux X11 backends against the shared OS menu contract:
 * normalized entry list parity (order, separators, submenus, enabled/disabled state, checked state,
 * shortcut labels), path-based selection, single-delivery results, keyboard navigation, and text
 * context menu parity.
 */
class DrawnOsMenuTest {

    private val anchor = NativeRect(24, 32, 140, 32)

    private fun richEntries(): List<OsMenuEntry> = listOf(
        Separator,
        Item(id = 1, label = "Save\nFile", shortcutLabel = "Ctrl+\tS", enabled = true, icon = IconRole.Check),
        Separator,
        Separator,
        Item(id = 2, label = "Disabled Action", enabled = false),
        Item(id = 3, label = "Word Wrap", checked = true, enabled = true),
        Submenu(
            label = "Export",
            children = listOf(
                Separator,
                Item(id = 10, label = "PDF", shortcutLabel = "Ctrl+P"),
                Separator,
                Item(id = 11, label = "Disabled Format", enabled = false),
                Separator,
            ),
        ),
        Submenu(label = "Empty Submenu", children = listOf(Separator)),
        Submenu(label = "Disabled Submenu", children = listOf(Item(id = 12, label = "Hidden")), enabled = false),
        Separator,
    )

    @Test
    fun fr33_2_linux_drawn_menu_matches_os_menu_entries_order_and_enabled_state() = runComposeUiTest {
        val raw = richEntries()
        val expected = normalizeOsMenu(raw)
        val fakeOs = FakeOsMenuPresenter()
        val drawn = DrawnOsMenuPresenter()
        var provided: OsMenuPresenter? = null

        setContent {
            DrawnOsMenuHost(drawn) {
                provided = LocalOsMenuPresenter.current
                Box(Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        assertSame(drawn, provided, "DrawnOsMenuHost must provide DrawnOsMenuPresenter via LocalOsMenuPresenter")

        fakeOs.open(OsMenuRequest(expected, anchor)) {}
        drawn.open(OsMenuRequest(raw, anchor)) {}
        waitForIdle()

        assertEquals(fakeOs.last.request.entries, drawn.normalizedEntries)
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()

        // Verify every normalized entry is rendered in order with matching enabled/checked state.
        expected.forEachIndexed { index, entry ->
            val tag = drawnOsMenuEntryTestTag(listOf(index))
            onNodeWithTag(tag).assertIsDisplayed()
            when (entry) {
                is Item -> {
                    if (entry.enabled) {
                        onNodeWithTag(tag).assertIsEnabled()
                    } else {
                        onNodeWithTag(tag).assertIsNotEnabled()
                    }
                    if (entry.checked) {
                        onNodeWithTag(tag).assertIsSelected()
                    }
                    onNodeWithText(entry.label).assertIsDisplayed()
                    entry.shortcutLabel?.let { shortcut ->
                        onNodeWithText(shortcut).assertIsDisplayed()
                    }
                }
                is Separator -> {
                    // Separator is present at the exact normalized index between entries.
                    onNodeWithTag(tag).assertIsDisplayed()
                }
                is Submenu -> {
                    if (entry.enabled) {
                        onNodeWithTag(tag).assertIsEnabled()
                    } else {
                        onNodeWithTag(tag).assertIsNotEnabled()
                    }
                    onNodeWithText(entry.label).assertIsDisplayed()
                }
            }
        }

        // Verify vertical order matches normalized list order.
        val tops = expected.indices.map { idx ->
            onNodeWithTag(drawnOsMenuEntryTestTag(listOf(idx))).fetchSemanticsNode().boundsInRoot.top
        }
        for (i in 1 until tops.size) {
            assertTrue(tops[i] >= tops[i - 1], "entry $i must appear below entry ${i - 1}: $tops")
        }
    }

    @Test
    fun fr33_2_linux_drawn_menu_chooses_root_and_submenu_items_by_path_once() = runComposeUiTest {
        val raw = richEntries()
        val expected = normalizeOsMenu(raw)
        val drawn = DrawnOsMenuPresenter()

        setContent {
            DrawnOsMenuHost(drawn) {
                Box(Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        // 1. Clicking a disabled item or disabled/empty submenu does not choose or close.
        val results1 = mutableListOf<OsMenuResult>()
        drawn.open(OsMenuRequest(raw, anchor)) { results1 += it }
        waitForIdle()

        val disabledItemIndex = expected.indexOfFirst { it is Item && !it.enabled }
        val emptySubmenuIndex = expected.indexOfFirst { it is Submenu && it.label == "Empty Submenu" }
        val disabledSubmenuIndex = expected.indexOfFirst { it is Submenu && it.label == "Disabled Submenu" }

        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(disabledItemIndex))).performClick()
        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(emptySubmenuIndex))).performClick()
        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(disabledSubmenuIndex))).performClick()
        waitForIdle()

        assertTrue(results1.isEmpty(), "disabled entries must not report a result")
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()
        assertTrue(
            onAllNodes(hasTestTag(drawnOsMenuPopupTestTag(listOf(emptySubmenuIndex)))).fetchSemanticsNodes().isEmpty(),
            "empty submenu must not open",
        )
        assertTrue(
            onAllNodes(hasTestTag(drawnOsMenuPopupTestTag(listOf(disabledSubmenuIndex)))).fetchSemanticsNodes().isEmpty(),
            "disabled submenu must not open",
        )

        // 2. Clicking an enabled root item reports Chosen(listOf(0)) once and closes.
        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(0))).performClick()
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(0))), results1)
        assertTrue(
            onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty(),
            "choosing an item must close the drawn menu",
        )

        // 3. Opening an enabled submenu and choosing a child reports Chosen(listOf(submenuIdx, childIdx)) once.
        val results2 = mutableListOf<OsMenuResult>()
        drawn.open(OsMenuRequest(raw, anchor)) { results2 += it }
        waitForIdle()

        val exportIndex = expected.indexOfFirst { it is Submenu && it.label == "Export" }
        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(exportIndex))).performClick()
        waitForIdle()

        onNodeWithTag(drawnOsMenuPopupTestTag(listOf(exportIndex))).assertIsDisplayed()
        onNodeWithTag(drawnOsMenuEntryTestTag(listOf(exportIndex, 0))).performClick()
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(exportIndex, 0))), results2)
        assertTrue(
            onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty(),
            "choosing a submenu item must close the root menu",
        )
    }

    @Test
    fun fr33_2_linux_drawn_menu_dismisses_once_on_escape_and_handle_close() = runComposeUiTest {
        val drawn = DrawnOsMenuPresenter()
        val entries = listOf(Item(1, "Alpha"), Item(2, "Beta"))

        setContent {
            DrawnOsMenuHost(drawn) {
                Box(Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        // Escape dismisses once.
        val escapeResults = mutableListOf<OsMenuResult>()
        val handle1 = drawn.open(OsMenuRequest(entries, anchor)) { escapeResults += it }
        waitForIdle()
        onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        handle1.close()
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), escapeResults)
        assertTrue(onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty())

        // Application-side close dismisses once even when called twice.
        val closeResults = mutableListOf<OsMenuResult>()
        val handle2 = drawn.open(OsMenuRequest(entries, anchor)) { closeResults += it }
        waitForIdle()
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()
        handle2.close()
        handle2.close()
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), closeResults)
        assertTrue(onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty())

        // Separator-only / empty list dismisses immediately without opening a popup.
        val emptyResults = mutableListOf<OsMenuResult>()
        drawn.open(OsMenuRequest(listOf(Separator, Separator), anchor)) { emptyResults += it }
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Dismissed), emptyResults)
        assertTrue(onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun fr33_2_linux_drawn_menu_keyboard_skips_separators_and_disabled_items() = runComposeUiTest {
        val drawn = DrawnOsMenuPresenter()
        val entries = listOf(
            Item(1, "First"),
            Separator,
            Item(2, "Skipped", enabled = false),
            Item(3, "Last"),
        )
        val results = mutableListOf<OsMenuResult>()

        setContent {
            DrawnOsMenuHost(drawn) {
                Box(Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        drawn.open(OsMenuRequest(entries, anchor)) { results += it }
        waitForIdle()
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()

        // First enabled item is focused on open; Down skips separator and disabled item to reach index 3 ("Last").
        onAllNodes(isRoot()).onLast().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        waitForIdle()

        assertEquals(listOf<OsMenuResult>(OsMenuResult.Chosen(listOf(3))), results)
        assertTrue(onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Suppress("DEPRECATION")
    @Test
    fun fr33_2_linux_drawn_text_context_menu_matches_os_menu_entry_list() = runComposeUiTest {
        var backing: String? = "clip text"
        val pasteboard = PasteboardClipboard(readText = { backing }, writeText = { backing = it })
        val clipboard = WindowClipboardImpl(pasteboard)
        val clipboardManager = WindowClipboardManager(pasteboard)
        val nativeCaptured = mutableListOf<List<OsMenuEntry>>()
        val fakeOsPresenter = FakeOsMenuPresenter()
        val drawnPresenter = DrawnOsMenuPresenter()
        var localization: PlatformLocalization? = null
        val frames = FrameRequestSource()
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Column),
            Mutation.Insert(0, 1, 0),
            Mutation.Create(2, WidgetKind.TextField),
            Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("hello world")),
            Mutation.Insert(1, 2, 0),
        )
        val connection = FakeHostConnection(batch)
        var mode by mutableIntStateOf(0) // 0 = native, 1 = fakeOs, 2 = drawn

        val nativeRepr = NativeContextMenuRepresentation { entries ->
            nativeCaptured += normalizeOsMenu(entries.mapIndexed { idx, entry -> entry.toOsMenuEntry(idx) })
            -1
        }
        val fakeOsRepr = OsMenuContextMenuRepresentation(fakeOsPresenter)
        val drawnRepr = OsMenuContextMenuRepresentation(drawnPresenter)

        setContent {
            localization = LocalLocalization.current
            DrawnOsMenuHost(drawnPresenter) {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                    LocalClipboard provides clipboard,
                    LocalClipboardManager provides clipboardManager,
                    LocalContextMenuRepresentation provides when (mode) {
                        0 -> nativeRepr
                        1 -> fakeOsRepr
                        else -> drawnRepr
                    },
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        val strings = assertNotNull(localization)

        // 1. Capture native menu entry list.
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()

        // 2. Capture OsMenuPresenter entry list via FakeOsMenuPresenter.
        mode = 1
        waitForIdle()
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()
        val fakeEntries = fakeOsPresenter.last.request.entries
        fakeOsPresenter.dismiss()
        waitForIdle()

        // 3. Open in DrawnOsMenuPresenter and compare normalized entries.
        mode = 2
        waitForIdle()
        onNodeWithTag(nodeTestTag(2)).performMouseInput { rightClick(center) }
        waitForIdle()

        assertEquals(nativeCaptured.last(), fakeEntries)
        assertEquals(nativeCaptured.last(), drawnPresenter.normalizedEntries)
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()
        onNodeWithText(strings.paste).assertIsDisplayed()

        // Dismiss the drawn context menu cleanly.
        drawnPresenter.dismiss()
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag(DRAWN_OS_MENU_POPUP_TAG)).fetchSemanticsNodes().isEmpty())
    }
}
