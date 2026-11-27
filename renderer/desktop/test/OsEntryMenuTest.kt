@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.foundation.DRAWN_OS_MENU_POPUP_TAG
import dev.darkpyonix.composerust.foundation.DrawnOsMenuHost
import dev.darkpyonix.composerust.foundation.DrawnOsMenuPresenter
import dev.darkpyonix.composerust.foundation.LocalOsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuEntry
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuGroup
import dev.darkpyonix.composerust.foundation.OsMenuMappedEntry
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.barOverflowTestTag
import dev.darkpyonix.composerust.foundation.barTitleTestTag
import dev.darkpyonix.composerust.foundation.buildOsMenuGroups
import dev.darkpyonix.composerust.foundation.dropdownPopupTestTag
import dev.darkpyonix.composerust.foundation.formatAndroidMenuTitle
import dev.darkpyonix.composerust.foundation.menuPopupTestTag
import dev.darkpyonix.composerust.foundation.normalizeOsMenu
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entry menus put up as the operating system's own: what the interpreter hands the window's
 * presenter for a `Menu`, a closed `Dropdown` and a bar's overflow, what a choice and a
 * dismissal send back to the Host, and what is reported instead of drawn when a menu holds
 * something no such menu can show. The presenter is [FakeOsMenuPresenter], so no window is
 * needed.
 */
class OsEntryMenuTest {
    private val frames = FrameRequestSource()

    private fun theme(system: DesignSystem = DesignSystem.Material3) =
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false))

    private fun icon(role: IconRole) = PropertyValue.Integer(role.ordinal + 1L)

    private fun textVariant() = PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)

    private fun handlerOf(id: Int): Long = 100L + id

    /** A `MenuItem` as the Host writes it: a text button marked as a menu entry. */
    private fun MutableList<Mutation>.menuItem(
        id: Int,
        parent: Int,
        index: Int,
        label: String,
        shortcut: String? = null,
        enabled: Boolean = true,
        checked: Boolean = false,
        icon: IconRole? = null,
    ) {
        add(Mutation.Create(id, WidgetKind.Button))
        add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.SetProp(id, PropertyKind.Variant, textVariant()))
        add(Mutation.SetProp(id, PropertyKind.Enabled, PropertyValue.Bool(enabled)))
        add(Mutation.SetProp(id, PropertyKind.Checked, PropertyValue.Bool(checked)))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(id))))
        add(Mutation.SetProp(id, PropertyKind.MenuEntry, PropertyValue.Bool(true)))
        if (shortcut != null) add(Mutation.SetProp(id, PropertyKind.ShortcutLabel, PropertyValue.Text(shortcut)))
        if (icon != null) add(Mutation.SetProp(id, PropertyKind.Icon, icon(icon)))
        add(Mutation.Insert(parent, id, index))
    }

    private fun MutableList<Mutation>.button(id: Int, parent: Int, index: Int, label: String) {
        add(Mutation.Create(id, WidgetKind.Button))
        add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(id))))
        add(Mutation.Insert(parent, id, index))
    }

    private val menuId = 2
    private val dismissHandler = 900L

    /**
     * An open `Menu` with every kind of entry: an item with a shortcut and an icon, a disabled
     * one, a checked one, a separator, and a submenu of two items.
     */
    private fun fullMenuBatch(): List<Mutation> = buildList {
        add(theme())
        add(Mutation.Create(1, WidgetKind.Box))
        add(Mutation.Insert(0, 1, 0))
        add(Mutation.Create(menuId, WidgetKind.Menu))
        add(Mutation.SetProp(menuId, PropertyKind.Open, PropertyValue.Bool(true)))
        add(Mutation.SetProp(menuId, PropertyKind.OnDismiss, PropertyValue.Integer(dismissHandler)))
        add(Mutation.Insert(1, menuId, 0))
        button(3, menuId, 0, "File")
        menuItem(10, menuId, 1, "Open", shortcut = "Ctrl+O", icon = IconRole.Search)
        menuItem(11, menuId, 2, "Save", enabled = false)
        menuItem(12, menuId, 3, "Wrap", checked = true)
        add(Mutation.Create(13, WidgetKind.Divider))
        add(Mutation.Insert(menuId, 13, 4))
        add(Mutation.Create(14, WidgetKind.Menu))
        add(Mutation.SetProp(14, PropertyKind.MenuEntry, PropertyValue.Bool(true)))
        add(Mutation.Insert(menuId, 14, 5))
        menuItem(15, 14, 0, "Export", icon = IconRole.Share)
        menuItem(16, 14, 1, "PDF")
        menuItem(17, 14, 2, "PNG")
    }

    private val fullMenuEntries: List<OsMenuEntry> = listOf(
        Item(id = 10, label = "Open", shortcutLabel = "Ctrl+O", icon = IconRole.Search),
        Item(id = 11, label = "Save", enabled = false),
        Item(id = 12, label = "Wrap", checked = true),
        Separator,
        Submenu(
            label = "Export",
            children = listOf(Item(id = 16, label = "PDF"), Item(id = 17, label = "PNG")),
            icon = IconRole.Share,
        ),
    )

    private fun ComposeUiTest.show(
        connection: FakeHostConnection,
        presenter: OsMenuPresenter?,
        modifier: Modifier = Modifier.fillMaxSize(),
    ) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
                LocalOsMenuPresenter provides presenter,
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), modifier)
            }
        }
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun FakeHostConnection.clicks(): List<HostEvent.Clicked> = events.filterIsInstance<HostEvent.Clicked>()

    private fun FakeHostConnection.errors(): List<HostEvent.ProtocolError> =
        events.filterIsInstance<HostEvent.ProtocolError>()

    // ----- Menu -------------------------------------------------------------------------

    /** Criterion 1: the entries go to the window in declaration order and state, and no popup is drawn. */
    @Test
    fun fr33_2_a_menu_hands_its_entries_to_the_os_menu_and_draws_no_popup() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(fullMenuBatch())
        show(connection, fake)

        assertEquals(1, fake.shown.size, "one open menu puts up one OS menu")
        assertEquals(normalizeOsMenu(fullMenuEntries), fake.last.request.entries)
        assertTrue(!exists(menuPopupTestTag(menuId)), "a Compose popup was drawn beside the OS menu")
        assertTrue(fake.last.isOpen, "the OS menu closed by itself")
        assertTrue(!fake.last.request.anchor.isEmpty, "the menu was put up with no anchor: ${fake.last.request.anchor}")
        assertEquals(emptyList(), connection.clicks(), "the Host heard something while the menu was up")
    }

    /** Criterion 2: a choice is the entry's own click, once, and nothing else. */
    @Test
    fun fr33_2_choosing_an_os_menu_entry_sends_its_click_once() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(fullMenuBatch())
        show(connection, fake)

        runOnIdle { fake.choose(0) }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(10, handlerOf(10))), connection.clicks())
        assertTrue(!fake.last.isOpen)
        assertEquals(1, fake.shown.size, "choosing put the menu up again")
    }

    /** Criterion 2: a choice inside a submenu is that item's click. */
    @Test
    fun fr33_2_choosing_a_submenu_entry_sends_that_items_click_once() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(fullMenuBatch())
        show(connection, fake)

        runOnIdle { fake.choose(4, 1) }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(17, handlerOf(17))), connection.clicks())
    }

    /** Criterion 2: closing with nothing chosen is the menu's dismissal, once. */
    @Test
    fun fr33_2_closing_an_os_menu_without_a_choice_sends_on_dismiss_once() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(fullMenuBatch())
        show(connection, fake)

        runOnIdle {
            fake.dismiss()
            fake.dismiss()
        }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(menuId, dismissHandler)), connection.clicks())
        assertEquals(1, fake.shown.size)
    }

    /** Criterion 5: a node that is not an entry is reported, and nothing is put up or drawn. */
    @Test
    fun fr33_2_a_menu_holding_a_node_that_is_not_an_entry_reports_it_and_puts_nothing_up() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val batch = buildList {
            add(theme())
            add(Mutation.Create(1, WidgetKind.Box))
            add(Mutation.Insert(0, 1, 0))
            add(Mutation.Create(menuId, WidgetKind.Menu))
            add(Mutation.SetProp(menuId, PropertyKind.Open, PropertyValue.Bool(true)))
            add(Mutation.SetProp(menuId, PropertyKind.OnDismiss, PropertyValue.Integer(dismissHandler)))
            add(Mutation.Insert(1, menuId, 0))
            button(3, menuId, 0, "File")
            menuItem(10, menuId, 1, "Open")
            // A plain button, the way the samples wrote their rows: not an entry.
            button(11, menuId, 2, "Delete")
        }
        val connection = FakeHostConnection(batch)
        show(connection, fake)

        val errors = connection.errors()
        assertEquals(listOf(TableError.INVALID_CHILDREN), errors.map { it.code }, "events were ${connection.events}")
        assertEquals(menuId, errors.single().nodeId)
        assertTrue(errors.single().message.contains("11"), errors.single().message)
        assertEquals(0, fake.shown.size, "a menu with a node no OS menu can show was put up")
        assertTrue(!exists(menuPopupTestTag(menuId)), "the menu fell back to a drawn popup")
        assertTrue(!exists(nodeTestTag(11)), "the node that is not an entry was drawn")
    }

    /** Criterion 4: a popover is never an OS menu, whatever role it declares. */
    @Test
    fun fr33_2_a_popover_with_the_menu_role_opens_its_drawn_popup_not_an_os_menu() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val popover = 2
        val batch = buildList {
            add(theme())
            add(Mutation.Create(1, WidgetKind.Box))
            add(Mutation.Insert(0, 1, 0))
            add(Mutation.Create(popover, WidgetKind.Popover))
            add(Mutation.SetProp(popover, PropertyKind.Open, PropertyValue.Bool(true)))
            add(Mutation.SetProp(popover, PropertyKind.OnDismiss, PropertyValue.Integer(dismissHandler)))
            add(Mutation.SetModifier(popover, 0, ProtocolModifier.Semantics(SemanticsRole.Menu, 0)))
            add(Mutation.Insert(1, popover, 0))
            button(3, popover, 0, "IDE")
            add(Mutation.Create(4, WidgetKind.Column))
            add(Mutation.Insert(popover, 4, 1))
            // Even entries the Host marked do not make a popover an OS menu.
            menuItem(10, 4, 0, "Ember")
            menuItem(11, 4, 1, "VS Code")
        }
        val connection = FakeHostConnection(batch)
        show(connection, fake)

        assertEquals(0, fake.shown.size, "a popover was put up as an OS menu")
        assertTrue(exists(nodeTestTag(10)) && exists(nodeTestTag(11)), "the popover's content was not drawn")
        assertEquals(emptyList(), connection.errors())
    }

    // ----- Dropdown ---------------------------------------------------------------------

    private val dropdownId = 1
    private val valueHandler = 41L

    /** A closed dropdown whose options are two texts, a separator and a `MenuItem`. */
    private fun dropdownBatch(extra: MutableList<Mutation>.() -> Unit = {}): List<Mutation> = buildList {
        add(theme())
        add(Mutation.Create(dropdownId, WidgetKind.Dropdown))
        add(Mutation.SetProp(dropdownId, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
        add(Mutation.SetProp(dropdownId, PropertyKind.Collapsible, PropertyValue.Bool(true)))
        add(Mutation.SetProp(dropdownId, PropertyKind.OnValueChange, PropertyValue.Integer(valueHandler)))
        add(Mutation.Insert(0, dropdownId, 0))
        listOf(2 to "One", 3 to "Two").forEachIndexed { index, (id, label) ->
            add(Mutation.Create(id, WidgetKind.Text))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
            add(Mutation.Insert(dropdownId, id, index))
        }
        add(Mutation.Create(4, WidgetKind.Divider))
        add(Mutation.Insert(dropdownId, 4, 2))
        menuItem(5, dropdownId, 3, "Three", icon = IconRole.Add)
        extra()
    }

    /** Criterion 3: one entry per option, the chosen one checked, and a choice is a value change. */
    @Test
    fun fr33_2_a_closed_dropdown_puts_its_options_up_with_the_chosen_one_checked() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(dropdownBatch())
        show(connection, fake)
        assertEquals(0, fake.shown.size, "a closed dropdown put a menu up before it was pressed")

        onNodeWithText("One").performClick()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        assertEquals(1, fake.shown.size, "pressing the field put up no OS menu")
        assertEquals(
            normalizeOsMenu(
                listOf(
                    Item(id = 0, label = "One", checked = true),
                    Item(id = 1, label = "Two"),
                    Separator,
                    Item(id = 3, label = "Three", icon = IconRole.Add),
                ),
            ),
            fake.last.request.entries,
        )
        assertTrue(!exists(dropdownPopupTestTag(dropdownId)), "a drawn list was opened beside the OS menu")

        runOnIdle { fake.choose(3) }
        waitForIdle()
        assertEquals(
            listOf(HostEvent.ValueChanged(dropdownId, valueHandler, 3.0)),
            connection.events.filterIsInstance<HostEvent.ValueChanged>(),
        )
        assertEquals(emptyList(), connection.clicks(), "choosing an option clicked the option's own node")
    }

    /** Criterion 5, for a dropdown: an option that is not an entry is reported, and the field opens nothing. */
    @Test
    fun fr33_2_a_dropdown_option_that_is_not_an_entry_is_reported_and_opens_nothing() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(
            dropdownBatch {
                add(Mutation.Create(6, WidgetKind.Row))
                add(Mutation.Insert(dropdownId, 6, 4))
            },
        )
        show(connection, fake)

        val errors = connection.errors()
        assertEquals(listOf(TableError.INVALID_CHILDREN), errors.map { it.code }, "events were ${connection.events}")
        assertEquals(dropdownId, errors.single().nodeId)

        onNodeWithText("One").performClick()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertEquals(0, fake.shown.size, "a dropdown with an option no OS menu can show was put up")
        assertTrue(!exists(dropdownPopupTestTag(dropdownId)), "the dropdown fell back to a drawn list")
        assertEquals(1, connection.errors().size, "the same problem was reported twice")
    }

    // ----- the other presenters ---------------------------------------------------------

    /** Criterion 7: the drawn menu of the Linux windows shows the same list the OS menu gets. */
    @Test
    fun fr33_2_linux_drawn_menu_shows_the_same_entries_as_the_os_menu() = runComposeUiTest {
        val drawn = DrawnOsMenuPresenter()
        val connection = FakeHostConnection(fullMenuBatch())
        setContent {
            DrawnOsMenuHost(drawn) {
                CompositionLocalProvider(
                    LocalFrameRequests provides frames,
                    LocalDensity provides Density(1f),
                ) {
                    ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        assertEquals(normalizeOsMenu(fullMenuEntries), drawn.normalizedEntries)
        onNodeWithTag(DRAWN_OS_MENU_POPUP_TAG).assertIsDisplayed()
        assertTrue(!exists(menuPopupTestTag(menuId)), "the interpreter drew its own popup beside the drawn menu")
        onNodeWithText("Open").assertIsDisplayed()
        onNodeWithText("Ctrl+O").assertIsDisplayed()

        // Escape on the drawn menu is a dismissal, which the Host hears once.
        runOnIdle { drawn.dismiss() }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(menuId, dismissHandler)), connection.clicks())
    }

    /**
     * Criterion 10: what Android's `PopupMenu` and iOS's `UIMenu` are built from keeps the
     * entries' order and state; a separator becomes a group edge and a shortcut becomes part
     * of Android's title.
     */
    @Test
    fun fr33_2_mobile_presenters_get_the_same_entries_in_order_and_state() = runComposeUiTest {
        val fake = FakeOsMenuPresenter()
        show(FakeHostConnection(fullMenuBatch()), fake)

        val groups = buildOsMenuGroups(fake.last.request.entries)
        assertEquals(
            listOf(
                OsMenuGroup(
                    0,
                    listOf(
                        OsMenuMappedEntry.Action(listOf(0), "Open", shortcutLabel = "Ctrl+O", icon = IconRole.Search),
                        OsMenuMappedEntry.Action(listOf(1), "Save", enabled = false),
                        OsMenuMappedEntry.Action(listOf(2), "Wrap", checked = true),
                    ),
                ),
                OsMenuGroup(
                    1,
                    listOf(
                        OsMenuMappedEntry.Submenu(
                            listOf(4),
                            "Export",
                            icon = IconRole.Share,
                            groups = listOf(
                                OsMenuGroup(
                                    0,
                                    listOf(
                                        OsMenuMappedEntry.Action(listOf(4, 0), "PDF"),
                                        OsMenuMappedEntry.Action(listOf(4, 1), "PNG"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            groups,
        )
        val title = formatAndroidMenuTitle("Open", "Ctrl+O")
        assertTrue(title.contains("Open") && title.contains("Ctrl+O"), title)
    }

    // ----- the bar's overflow -----------------------------------------------------------

    private val root = 1
    private val bar = 2
    private val wide = 1200
    private val height = 600

    private fun barBatch(title: String = "Notes", actions: MutableList<Mutation>.() -> Unit): List<Mutation> = buildList {
        add(theme())
        add(Mutation.Create(root, WidgetKind.Column))
        add(Mutation.Insert(0, root, 0))
        add(Mutation.Create(bar, WidgetKind.TopAppBar))
        add(Mutation.SetProp(bar, PropertyKind.Text, PropertyValue.Text(title)))
        add(Mutation.Insert(root, bar, 0))
        actions()
    }

    private fun MutableList<Mutation>.action(
        id: Int,
        index: Int,
        label: String = "",
        icon: IconRole? = null,
        enabled: Boolean = true,
        accessibleName: String? = null,
    ) {
        add(Mutation.Create(id, WidgetKind.Button))
        add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.SetProp(id, PropertyKind.Variant, textVariant()))
        add(Mutation.SetProp(id, PropertyKind.Enabled, PropertyValue.Bool(enabled)))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(id))))
        if (icon != null) add(Mutation.SetProp(id, PropertyKind.Icon, icon(icon)))
        if (accessibleName != null) add(Mutation.SetProp(id, PropertyKind.AccessibleName, PropertyValue.Text(accessibleName)))
        add(Mutation.Insert(bar, id, index))
    }

    /** A button whose content slot holds one text, which no menu can show as an entry. */
    private fun MutableList<Mutation>.slotAction(id: Int, index: Int, label: String, width: Float? = null) {
        add(Mutation.Create(id, WidgetKind.Button))
        add(Mutation.SetProp(id, PropertyKind.Variant, textVariant()))
        add(Mutation.SetProp(id, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(id))))
        if (width != null) add(Mutation.SetModifier(id, 0, ProtocolModifier.Width(width)))
        add(Mutation.Create(id + 50, WidgetKind.Text))
        add(Mutation.SetProp(id + 50, PropertyKind.Text, PropertyValue.Text(label)))
        add(Mutation.Insert(id, id + 50, 0))
        add(Mutation.Insert(bar, id, index))
    }

    private fun ComposeUiTest.showBar(connection: FakeHostConnection, presenter: OsMenuPresenter?, width: () -> Int) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
                LocalOsMenuPresenter provides presenter,
            ) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.requiredSize(width().dp, height.dp))
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.openOverflow() {
        onNodeWithTag(barOverflowTestTag(bar)).performClick()
        waitForIdle()
        mainClock.advanceTimeByFrame()
        waitForIdle()
    }

    /**
     * Criterion 11: the overflow menu goes up as an OS menu of the overflowed actions in
     * declaration order with their text, icon and enabled state, an overflowed menu as a
     * submenu, and a choice is the action's own click.
     */
    @Test
    fun fr33_2_the_bar_overflow_menu_is_an_os_menu_of_the_overflowed_actions() = runDesktopComposeUiTest(wide, height) {
        val fake = FakeOsMenuPresenter()
        val actions = listOf(10, 11, 12, 13)
        val connection = FakeHostConnection(
            barBatch {
                action(10, 0, "Alpha", icon = IconRole.Add)
                action(11, 1, "Bravo", enabled = false)
                action(12, 2, "Charlie", icon = IconRole.Search)
                add(Mutation.Create(13, WidgetKind.Menu))
                add(Mutation.SetProp(13, PropertyKind.OnDismiss, PropertyValue.Integer(handlerOf(13))))
                add(Mutation.Insert(bar, 13, 3))
                add(Mutation.Create(20, WidgetKind.Button))
                add(Mutation.SetProp(20, PropertyKind.Text, PropertyValue.Text("Delta")))
                add(Mutation.SetProp(20, PropertyKind.Variant, textVariant()))
                add(Mutation.SetProp(20, PropertyKind.Icon, icon(IconRole.More)))
                add(Mutation.SetProp(20, PropertyKind.OnClick, PropertyValue.Integer(handlerOf(20))))
                add(Mutation.Insert(13, 20, 0))
                menuItem(21, 13, 1, "One")
                menuItem(22, 13, 2, "Two")
            },
        )
        showBar(connection, fake) { 200 }

        val inBar = actions.filter { exists(nodeTestTag(it)) }
        val overflowed = actions - inBar.toSet()
        assertTrue(13 in overflowed, "the last declared action stayed in a 200 dp bar: $inBar")
        assertEquals(actions.take(inBar.size), inBar)

        openOverflow()
        assertEquals(1, fake.shown.size, "pressing the overflow button put up no OS menu")
        val every = mapOf(
            10 to Item(id = 10, label = "Alpha", icon = IconRole.Add),
            11 to Item(id = 11, label = "Bravo", enabled = false),
            12 to Item(id = 12, label = "Charlie", icon = IconRole.Search),
            13 to Submenu(
                label = "Delta",
                children = listOf(Item(id = 21, label = "One"), Item(id = 22, label = "Two")),
                icon = IconRole.More,
            ),
        )
        val expected = normalizeOsMenu(overflowed.map { every.getValue(it) })
        assertEquals(expected, fake.last.request.entries)

        // The first overflowed entry that can be chosen is that action's own click.
        val firstItem = expected.indexOfFirst { it is Item && it.enabled }
        if (firstItem >= 0) {
            val id = (expected[firstItem] as Item).id
            runOnIdle { fake.choose(firstItem) }
            waitForIdle()
            assertEquals(listOf(HostEvent.Clicked(id, handlerOf(id))), connection.clicks())
            openOverflow()
        }
        // An item in the overflowed menu's submenu is that item's click.
        val submenu = fake.last.request.entries.indexOfFirst { it is Submenu }
        val before = connection.clicks().size
        runOnIdle { fake.choose(submenu, 1) }
        waitForIdle()
        assertEquals(listOf(HostEvent.Clicked(22, handlerOf(22))), connection.clicks().drop(before))
    }

    /** Criterion 14: an icon-only action is named in the menu by its accessible name. */
    @Test
    fun fr33_2_an_icon_only_action_is_named_by_its_accessible_name() = runDesktopComposeUiTest(wide, height) {
        val fake = FakeOsMenuPresenter()
        val connection = FakeHostConnection(
            barBatch {
                action(10, 0, "Alpha")
                action(11, 1, "Bravo")
                action(12, 2, icon = IconRole.Search, accessibleName = "Find in notes")
            },
        )
        showBar(connection, fake) { 160 }
        assertTrue(!exists(nodeTestTag(12)), "the last declared action stayed in a 160 dp bar")

        openOverflow()
        val entry = fake.last.request.entries.filterIsInstance<Item>().single { it.id == 12 }
        assertEquals("Find in notes", entry.label)
        assertEquals(IconRole.Search, entry.icon)
        assertEquals(emptyList(), connection.errors())
    }

    /**
     * Criterion 15: an action with neither text nor an accessible name is reported once at
     * the width where it overflows, makes no entry, and is never reported while it fits.
     */
    @Test
    fun fr33_2_a_nameless_action_is_reported_once_when_it_overflows_and_makes_no_entry() =
        runDesktopComposeUiTest(wide, height) {
            val fake = FakeOsMenuPresenter()
            var width by mutableStateOf(wide)
            val connection = FakeHostConnection(
                barBatch {
                    action(10, 0, "Alpha")
                    action(11, 1, "Bravo")
                    action(12, 2, icon = IconRole.Search)
                },
            )
            showBar(connection, fake) { width }
            assertTrue(exists(nodeTestTag(12)), "the nameless action is not in a $wide dp bar")
            assertEquals(emptyList(), connection.errors(), "an action that fits was reported")

            width = 160
            waitForIdle()
            assertTrue(!exists(nodeTestTag(12)), "the nameless action stayed in a 160 dp bar")
            val errors = connection.errors()
            assertEquals(listOf(TableError.INVALID_CHILDREN), errors.map { it.code }, "events were ${connection.events}")
            assertEquals(12, errors.single().nodeId)

            if (exists(barOverflowTestTag(bar))) {
                openOverflow()
                val ids = fake.shown.flatMap { menu -> menu.request.entries.filterIsInstance<Item>().map { it.id } }
                assertTrue(12 !in ids, "the nameless action made an entry: $ids")
            }

            width = 150
            waitForIdle()
            assertEquals(1, connection.errors().size, "the nameless action was reported again while it stayed in the menu")
        }

    /**
     * Criterion 16: an action with a content slot cannot be an entry, so it stays in the bar
     * while the plain actions around it move into the menu, the last declared first.
     */
    private fun ComposeUiTest.assertTheSlotActionStays(slotFirst: Boolean) {
        val fake = FakeOsMenuPresenter()
        var width by mutableStateOf(wide)
        val connection = FakeHostConnection(
            barBatch {
                if (slotFirst) {
                    slotAction(10, 0, "main")
                    action(11, 1, "Alpha")
                    action(12, 2, "Bravo")
                } else {
                    action(11, 0, "Alpha")
                    action(12, 1, "Bravo")
                    slotAction(10, 2, "main")
                }
            },
        )
        showBar(connection, fake) { width }
        assertTrue(listOf(10, 11, 12).all { exists(nodeTestTag(it)) }, "not everything fits in $wide dp")

        width = 200
        waitForIdle()
        val case = if (slotFirst) "declared first" else "declared last"
        assertTrue(exists(nodeTestTag(10)), "the action with a content slot, $case, left the bar")
        assertTrue(!exists(nodeTestTag(12)), "the last plain action stayed in a 200 dp bar")
        val slot = onNodeWithTag(nodeTestTag(10), useUnmergedTree = true).getBoundsInRoot()
        assertTrue(slot.right - slot.left > 1.dp, "the action with a content slot, $case, was squeezed to $slot")

        openOverflow()
        val ids = fake.last.request.entries.filterIsInstance<Item>().map { it.id }
        assertTrue(10 !in ids && 12 in ids, "the overflow menu holds $ids")
        assertEquals(emptyList(), connection.errors())
    }

    @Test
    fun fr33_2_an_action_with_a_content_slot_stays_in_the_bar_when_the_plain_ones_after_it_overflow() =
        runDesktopComposeUiTest(wide, height) { assertTheSlotActionStays(slotFirst = true) }

    @Test
    fun fr33_2_an_action_with_a_content_slot_declared_last_still_stays_in_the_bar() =
        runDesktopComposeUiTest(wide, height) { assertTheSlotActionStays(slotFirst = false) }

    /**
     * Criterion 17: when the action with a content slot does not fit beside the title, the
     * title gives way, cut short, and the action keeps its width; only once the title has
     * nothing left does the action shrink.
     */
    @Test
    fun fr33_2_the_title_gives_way_before_an_action_with_a_content_slot() = runDesktopComposeUiTest(wide, height) {
        var width by mutableStateOf(wide)
        val slotWidth = 160f
        val connection = FakeHostConnection(
            barBatch(title = "A rather long window title that wants its room") {
                slotAction(10, 0, "main", width = slotWidth)
            },
        )
        showBar(connection, FakeOsMenuPresenter()) { width }

        fun title() = onNodeWithTag(barTitleTestTag(bar), useUnmergedTree = true).getBoundsInRoot()
        fun slot() = onNodeWithTag(nodeTestTag(10), useUnmergedTree = true).getBoundsInRoot()
        val fullTitle = title().right - title().left
        assertEquals(slotWidth, (slot().right - slot().left).value, 0.5f)

        width = (fullTitle.value + slotWidth).toInt() - 40
        waitForIdle()
        val cut = title().right - title().left
        assertTrue(cut < fullTitle, "the title kept all of its ${fullTitle} at $width dp, it is $cut")
        assertTrue(cut > 1.dp, "the title vanished while there was room for some of it at $width dp")
        assertEquals(slotWidth, (slot().right - slot().left).value, 0.5f, "the action gave way before the title")
        assertTrue(slot().right <= onNodeWithTag(nodeTestTag(bar)).getBoundsInRoot().right + 0.5.dp, "the action runs past the bar")

        width = slotWidth.toInt() - 40
        waitForIdle()
        assertTrue(title().right - title().left < 1.dp, "the title kept ${title().right - title().left} while the action shrank")
        val squeezed = slot().right - slot().left
        assertTrue(squeezed.value < slotWidth, "the action kept its width in a $width dp bar")
        assertTrue(squeezed > 1.dp, "the action vanished instead of shrinking")
    }
}
