package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.darkpyonix.composerust.design.LocalDesignTheme
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.role
import kotlinx.coroutines.awaitCancellation

// The interpreter's side of an entry menu put up by the operating system: reading `Menu`,
// `Dropdown` and bar overflow nodes as an entry list, handing it to the window's
// [OsMenuPresenter], and turning what was chosen back into the event the same node sends
// when its menu is drawn. The presenters themselves are in `OsMenu.kt` and per platform.
//
// What a menu is comes from the API, never from looking at the content: a `Menu` and a
// closed `Dropdown` are entry menus and a `Popover` is not, whatever role it declares. So a
// `Menu` that holds something other than entries is not quietly drawn instead: it is
// reported to the Host, and nothing is put up.

/**
 * Whether the Host marked [this] as a menu entry: a button that is a `MenuItem`, or a menu
 * that is a `Submenu`, named by its first child.
 */
internal fun Node.isMenuEntry(): Boolean = flag(PropertyKind.MenuEntry, default = false)

/** What [this] is called in a menu: its text, or the name assistive technology reads when it has none. */
internal fun Node.entryLabel(): String =
    text(PropertyKind.Text).ifEmpty { text(PropertyKind.AccessibleName) }

private fun Node.entryIcon(): IconRole? = role(PropertyKind.Icon, IconRole.entries.toTypedArray())

/** Whether [this] is a button whose content slot replaces its label, so no menu can show it. */
internal fun Node.hasContentSlot(): Boolean = widget == WidgetKind.Button && children.isNotEmpty()

/**
 * A list of nodes read as an entry menu.
 *
 * [entries] is not yet normalised. [notAnEntry] is the first node that is not an entry, or
 * null when every node is one; [owner] is the node whose content it is in.
 */
internal class NodeEntries(
    val entries: List<OsMenuEntry>,
    val notAnEntry: Int? = null,
    val owner: Int? = null,
)

/** The item a `MenuItem` is, with [id] as the tag handed back when it is chosen. */
private fun Node.asItem(id: Int, checked: Boolean = flag(PropertyKind.Checked, default = false)) =
    OsMenuEntry.Item(
        id = id,
        label = entryLabel(),
        shortcutLabel = text(PropertyKind.ShortcutLabel).takeIf { it.isNotEmpty() },
        enabled = flag(PropertyKind.Enabled, default = true),
        checked = checked,
        icon = entryIcon(),
    )

/**
 * The content of the `Menu` [ownerId] as entries, in declaration order. Each item's id is
 * its node id, so choosing it is that node's own click.
 *
 * A `MenuItem` is an item, a `Divider` (a `Separator`) is a separator, and a `Submenu` is a
 * submenu named, pictured and enabled by its first child, holding the entries after it.
 * Anything else stops the reading: a menu with one node it cannot show is not put up.
 */
internal fun NodeTable.menuEntries(ownerId: Int, content: List<Int>): NodeEntries {
    val out = ArrayList<OsMenuEntry>(content.size)
    for (childId in content) {
        val child = node(childId) ?: continue
        when {
            child.widget == WidgetKind.Divider -> out += OsMenuEntry.Separator
            child.widget == WidgetKind.Button && child.isMenuEntry() && !child.hasContentSlot() ->
                out += child.asItem(childId)
            child.widget == WidgetKind.Menu && child.isMenuEntry() -> {
                val submenu = submenuOf(child)
                if (submenu.notAnEntry != null) return NodeEntries(out, submenu.notAnEntry, submenu.owner)
                out += submenu.entries
            }
            else -> return NodeEntries(out, childId, ownerId)
        }
    }
    return NodeEntries(out)
}

/**
 * The `Menu` [menu] as one submenu entry: named by its anchor, the first child, and holding
 * the entries after it. An anchor that is not a button cannot name it, and the menu is then
 * not an entry.
 */
private fun NodeTable.submenuOf(menu: Node): NodeEntries {
    val anchorId = menu.children.firstOrNull() ?: return NodeEntries(emptyList(), menu.id, menu.id)
    val anchor = node(anchorId)
    if (anchor == null || anchor.widget != WidgetKind.Button) return NodeEntries(emptyList(), anchorId, menu.id)
    val inner = menuEntries(menu.id, menu.children.drop(1))
    if (inner.notAnEntry != null) return inner
    val label = anchor.entryLabel().ifEmpty { menu.text(PropertyKind.AccessibleName) }
    return NodeEntries(
        listOf(
            OsMenuEntry.Submenu(
                label = label,
                children = inner.entries,
                enabled = anchor.flag(PropertyKind.Enabled, default = true),
                icon = anchor.entryIcon(),
            ),
        ),
    )
}

/**
 * The options of a `Dropdown` as entries, with the one at [selected] checked. Each item's id
 * is the option's position, which is what the dropdown reports when it is chosen.
 *
 * An option is a `MenuItem` or a `Text`, and a `Divider` between them is a separator.
 */
internal fun NodeTable.optionEntries(ownerId: Int, options: List<Int>, selected: Int): NodeEntries {
    val out = ArrayList<OsMenuEntry>(options.size)
    options.forEachIndexed { index, childId ->
        val child = node(childId) ?: return@forEachIndexed
        when {
            child.widget == WidgetKind.Divider -> out += OsMenuEntry.Separator
            child.widget == WidgetKind.Button && child.isMenuEntry() && !child.hasContentSlot() ->
                out += child.asItem(index, checked = index == selected)
            child.widget == WidgetKind.Text && child.children.isEmpty() ->
                out += OsMenuEntry.Item(id = index, label = child.text(PropertyKind.Text), checked = index == selected)
            else -> return NodeEntries(out, childId, ownerId)
        }
    }
    return NodeEntries(out)
}

/**
 * Whether the bar action [id] has nothing to name an entry with: no text and no accessible
 * name, on a button or on a menu's anchor.
 */
internal fun NodeTable.isUnnamedAction(id: Int): Boolean {
    val action = node(id) ?: return false
    return when (action.widget) {
        WidgetKind.Button -> action.entryLabel().isEmpty()
        WidgetKind.Menu -> {
            val anchor = action.children.firstOrNull()?.let { node(it) }
            (anchor?.entryLabel().orEmpty()).isEmpty() && action.text(PropertyKind.AccessibleName).isEmpty()
        }
        else -> false
    }
}

/**
 * The actions a bar moved into its overflow menu, as entries in declaration order.
 *
 * A button is an item carrying its text (or its accessible name when it shows only an
 * icon), its icon and whether it is enabled; choosing it is that button's own click. A
 * menu is a submenu named by its anchor and holding the menu's entries. An action with no
 * name at all makes no entry; [isUnnamedAction] finds those so they can be reported.
 */
internal fun NodeTable.overflowEntries(actions: List<Int>): NodeEntries {
    val out = ArrayList<OsMenuEntry>(actions.size)
    var notAnEntry: Int? = null
    var owner: Int? = null
    for (id in actions) {
        val action = node(id) ?: continue
        if (isUnnamedAction(id)) continue
        when (action.widget) {
            WidgetKind.Button -> out += OsMenuEntry.Item(
                id = id,
                label = action.entryLabel(),
                enabled = action.flag(PropertyKind.Enabled, default = true),
                icon = action.entryIcon(),
            )
            WidgetKind.Menu -> {
                val submenu = submenuOf(action)
                if (submenu.notAnEntry != null) {
                    if (notAnEntry == null) {
                        notAnEntry = submenu.notAnEntry
                        owner = submenu.owner
                    }
                    // Still in the list, in its place, greyed: the bar does not lose it.
                    val anchor = action.children.firstOrNull()?.let { node(it) }
                    out += OsMenuEntry.Submenu(
                        label = anchor?.entryLabel().orEmpty().ifEmpty { action.text(PropertyKind.AccessibleName) },
                        children = emptyList(),
                        enabled = false,
                        icon = anchor?.entryIcon(),
                    )
                } else {
                    out += submenu.entries
                }
            }
            else -> {}
        }
    }
    return NodeEntries(out, notAnEntry, owner)
}

/** Sends the click of the entry node [id] to its handler, as a press on it would. */
internal fun NodeTable.clickEntry(id: Int, dispatcher: EventDispatcher) {
    val handlerId = node(id)?.handler(PropertyKind.OnClick) ?: return
    dispatcher.dispatch(HostEvent.Clicked(id, handlerId))
}

/**
 * Says once that [read] holds a node that is not a menu entry, and again only if that
 * changes. After composition, on the thread composition runs on.
 */
@Composable
internal fun ReportNotAnEntry(nodeId: Int, read: NodeEntries, dispatcher: EventDispatcher) {
    val said = remember(nodeId) { intArrayOf(NOTHING_SAID) }
    val offending = read.notAnEntry ?: NOTHING_SAID
    val owner = read.owner ?: nodeId
    SideEffect {
        if (said[0] == offending) return@SideEffect
        said[0] = offending
        if (offending == NOTHING_SAID) return@SideEffect
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = owner,
                handlerId = 0,
                code = TableError.INVALID_CHILDREN,
                message = "node $offending in the menu of node $owner is not a menu entry. A menu " +
                    "holds only MenuItem, Submenu and Separator, which is what an operating " +
                    "system menu can show, so this one is not put up; a popup of other " +
                    "content is a Popover",
            ),
        )
    }
}

/** Below every id the Host can send, which are positive. */
private const val NOTHING_SAID = Int.MIN_VALUE

/**
 * Where an entry menu hangs from: the window bounds of the node this modifier is on, read
 * when the menu goes up.
 */
internal class MenuAnchor {
    private var coordinates: LayoutCoordinates? = null

    val modifier: Modifier = Modifier.onGloballyPositioned { coordinates = it }

    /** The anchor in the window's pixels, or null before it has been laid out. */
    fun rect(): NativeRect? = coordinates?.takeIf { it.isAttached }?.let { NativeRect.of(it.boundsInWindow()) }
}

@Composable
internal fun rememberMenuAnchor(nodeId: Int): MenuAnchor = remember(nodeId) { MenuAnchor() }

/**
 * Puts [entries] up through [presenter] for as long as this is in the composition, below
 * [anchor], and says once how it ended: [onChosen] with the item, or [onDismissed] when the
 * menu closed with nothing chosen. Leaving the composition takes the menu down from the
 * application's side and says nothing, as a drawn popup the Host closed says nothing.
 *
 * The list is the one the menu went up with: a platform menu cannot change while it is up,
 * so a choice is resolved against what the reader saw.
 */
@Composable
internal fun OsEntryMenu(
    presenter: OsMenuPresenter,
    entries: List<OsMenuEntry>,
    anchor: MenuAnchor,
    onChosen: (OsMenuEntry.Item) -> Unit,
    onDismissed: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val theme = LocalDesignTheme.current
    val chosen by rememberUpdatedState(onChosen)
    val dismissed by rememberUpdatedState(onDismissed)
    val latestEntries by rememberUpdatedState(entries)
    LaunchedEffect(presenter) {
        // A menu open from its first frame has no laid out anchor yet.
        if (anchor.rect() == null) withFrameNanos { }
        val shown = normalizeOsMenu(latestEntries)
        if (shown.isEmpty()) {
            dismissed()
            return@LaunchedEffect
        }
        var fromApplication = false
        val request = OsMenuRequest(
            entries = shown,
            anchor = anchor.rect() ?: NativeRect.EMPTY,
            layoutDirection = layoutDirection,
            iconArt = roleIconArt(theme, theme.color(ColorRole.OnSurface)),
        )
        val handle = presenter.open(request) { result ->
            if (fromApplication) return@open
            when (result) {
                is OsMenuResult.Chosen -> {
                    val item = resolveOsMenuPath(shown, result.path)
                    if (item != null) chosen(item) else dismissed()
                }
                OsMenuResult.Dismissed -> dismissed()
            }
        }
        try {
            awaitCancellation()
        } finally {
            fromApplication = true
            handle.close()
        }
    }
}
