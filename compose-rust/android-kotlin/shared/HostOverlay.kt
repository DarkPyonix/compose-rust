package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PopoverAlign
import dev.darkpyonix.composerust.protocol.PopoverPlacement
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SemanticsRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.declaredSemanticsRole
import dev.darkpyonix.composerust.ui.declaresModal
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.isDeclaredSelected
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import androidx.compose.runtime.CompositionLocalProvider

/**
 * The open modal overlays of a window, in the order they were opened, and the control that
 * held focus before each one opened.
 *
 * Escape asks to close only the topmost open overlay, and only after whatever holds focus
 * inside it has declined the key. Android's back gesture and button take the same path, so
 * a back closes the same overlay Escape would. When the overlay closes, focus returns to the
 * control that had it before the overlay opened.
 *
 * It also knows which modal is on top, for assistive technology: the topmost of the open
 * modal overlays (a `Dialog`, a `Sheet`, a `Popover` that declared itself modal) and the
 * nodes in the tree that carry the modal bit, whichever opened last. Everything outside that
 * one modal leaves the accessibility tree while it is up. A node with the modal bit is kept
 * apart from the overlays: Escape, Back and focus return belong to overlays alone.
 */
class OverlayFocusStack {
    private class Entry(
        val nodeId: Int,
        var onEscape: () -> Unit,
        val opener: FocusRequester?,
        val isModal: Boolean,
        val order: Long,
    )

    /** A node with the modal bit: its id, every node above it up to the root, and when. */
    private class InTreeModal(
        val nodeId: Int,
        val ancestors: Set<Int>,
        val inOverlay: Boolean,
        val order: Long,
    )

    /** The modal on top, as the readers of this stack need it. */
    private data class TopModal(
        val nodeId: Int,
        val order: Long,
        val inTree: Boolean,
        val inOverlay: Boolean,
        val ancestors: Set<Int>,
        val overlayOrders: Map<Int, Long>,
    )

    /**
     * The topmost modal when it is a node with the modal bit, the one fact every node of the
     * page reads. Kept as its own state so that opening a `Dialog`, which leaves it null,
     * recomposes no node of the page.
     */
    private data class InTreeTop(val nodeId: Int, val ancestors: Set<Int>)

    private val entries = mutableListOf<Entry>()
    private val inTree = mutableListOf<InTreeModal>()
    private var nextOrder = 0L
    private var openCount by mutableStateOf(0)
    private var top by mutableStateOf<TopModal?>(null)
    private var inTreeTop by mutableStateOf<InTreeTop?>(null)

    /** Whether any overlay is open, read in composition so a platform back hook follows it. */
    val hasOpen: Boolean get() = openCount > 0

    /** Whether any modal is up: an open `Dialog` or `Sheet`, a modal `Popover`, or a node with the modal bit. */
    val hasOpenModal: Boolean get() = top != null

    /**
     * Whether the page of the window leaves the accessibility tree: a modal is up and it is
     * not a node drawn in the page itself, which hides only what is outside it.
     */
    val hidesPage: Boolean
        get() {
            val current = top ?: return false
            return !current.inTree || current.inOverlay
        }

    private var currentFocused: FocusRequester? = null

    internal fun noteFocused(requester: FocusRequester, focused: Boolean) {
        if (focused) {
            currentFocused = requester
        } else if (currentFocused === requester) {
            currentFocused = null
        }
    }

    internal fun push(nodeId: Int, onEscape: () -> Unit, isModal: Boolean = true): FocusRequester? {
        entries.removeAll { it.nodeId == nodeId }
        val opener = currentFocused
        entries += Entry(nodeId, onEscape, opener, isModal, nextOrder++)
        changed()
        return opener
    }

    internal fun update(nodeId: Int, onEscape: () -> Unit) {
        entries.firstOrNull { it.nodeId == nodeId }?.onEscape = onEscape
    }

    internal fun pop(nodeId: Int) {
        val index = entries.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) return
        val removed = entries.removeAt(index)
        changed()
        val target = if (entries.isEmpty()) removed.opener else null
        if (target != null) {
            try {
                target.requestFocus()
            } catch (_: IllegalStateException) {
                // The opener left the tree while the overlay was open.
            }
        }
    }

    /**
     * Records a node with the modal bit. [ancestors] is every node above it up to the root,
     * the root included, and [inOverlay] whether one of them is an overlay, whose content is
     * drawn apart from the page.
     */
    internal fun pushInTree(nodeId: Int, ancestors: Set<Int>, inOverlay: Boolean) {
        inTree.removeAll { it.nodeId == nodeId }
        inTree += InTreeModal(nodeId, ancestors, inOverlay, nextOrder++)
        changed()
    }

    internal fun popInTree(nodeId: Int) {
        if (inTree.removeAll { it.nodeId == nodeId }) changed()
    }

    private fun changed() {
        openCount = entries.size
        val overlay = entries.lastOrNull { it.isModal }
        val node = inTree.maxByOrNull { it.order }
        val orders = entries.associate { it.nodeId to it.order }
        top = when {
            node != null && (overlay == null || node.order > overlay.order) ->
                TopModal(node.nodeId, node.order, inTree = true, node.inOverlay, node.ancestors, orders)
            overlay != null ->
                TopModal(overlay.nodeId, overlay.order, inTree = false, inOverlay = false, emptySet(), orders)
            else -> null
        }
        val current = top
        inTreeTop = if (current != null && current.inTree) InTreeTop(current.nodeId, current.ancestors) else null
    }

    /**
     * Whether the overlay [nodeId] stays in the accessibility tree: no modal is up, or it is
     * the modal on top, or it holds the node with the modal bit that is on top, or it opened
     * after the modal on top, as a popover opened from inside a dialog does.
     */
    internal fun exposesOverlay(nodeId: Int): Boolean {
        val current = top ?: return true
        if (nodeId == current.nodeId) return true
        if (current.inTree && nodeId in current.ancestors) return true
        val order = current.overlayOrders[nodeId] ?: return true
        return order > current.order
    }

    /**
     * Whether the node [nodeId], whose parent is [parentId], is outside the node with the
     * modal bit that is on top while sitting beside the path down to it: a sibling of that
     * node or of one of its ancestors. Hiding those hides everything outside the modal, each
     * one with all that is under it.
     */
    internal fun isBesideInTreeModal(nodeId: Int, parentId: Int): Boolean {
        val current = inTreeTop ?: return false
        return parentId in current.ancestors && nodeId != current.nodeId && nodeId !in current.ancestors
    }

    internal fun handleEscape(event: KeyEvent, fromNodeId: Int? = null): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Android delivers its back button as Key.Back to a focused overlay window, which
        // never reaches the Activity's back dispatcher. A desktop keyboard has no such key.
        if (event.key != Key.Escape && event.key != Key.Back) return false
        return handleBack(fromNodeId)
    }

    /**
     * Asks the topmost open overlay to close, as Escape does. Returns false, leaving the
     * back to the app, when no overlay is open.
     */
    fun handleBack(fromNodeId: Int? = null): Boolean {
        val top = entries.lastOrNull() ?: return false
        if (fromNodeId != null && top.nodeId != fromNodeId) return false
        top.onEscape()
        return true
    }
}

/**
 * Leaves this node, and everything under it, out of the accessibility tree when [hidden]:
 * what sits behind the modal on top. The node is still drawn and still takes presses; only
 * assistive technology stops seeing it.
 */
internal fun Modifier.behindModal(hidden: Boolean): Modifier =
    if (hidden) this.clearAndSetSemantics { hideFromAccessibility() } else this

/** The overlays whose content a node with the modal bit is drawn apart from the page in. */
private val OVERLAY_WIDGETS = setOf(WidgetKind.Dialog, WidgetKind.Sheet, WidgetKind.Popover, WidgetKind.Menu)

/**
 * Keeps [node] in the window's modal stack while it carries the modal bit, and keeps Tab and
 * Shift+Tab from moving focus out of it. Returns the modifier that does the second; a node
 * without the bit gets [Modifier] back and nothing is recorded for it.
 *
 * Focus asked for in any other way (a press, the Host's focus request) still leaves: only
 * the two keys that walk the focus order are held at the edge.
 */
@Composable
internal fun inTreeModal(node: Node, table: NodeTable): Modifier {
    if (!node.declaresModal()) return Modifier
    val stack = LocalOverlayFocusStack.current
    val parentId = node.parentId
    DisposableEffect(stack, node.id, parentId) {
        val ancestors = HashSet<Int>()
        var inOverlay = false
        var current = parentId
        var steps = 0
        while (current != NodeTable.ROOT_ID && steps++ < 4096) {
            ancestors += current
            val above = table.node(current) ?: break
            if (above.widget in OVERLAY_WIDGETS) inOverlay = true
            current = above.parentId
        }
        ancestors += NodeTable.ROOT_ID
        stack.pushInTree(node.id, ancestors, inOverlay)
        onDispose { stack.popInTree(node.id) }
    }
    return Modifier
        .focusProperties {
            onExit = {
                if (requestedFocusDirection == FocusDirection.Next ||
                    requestedFocusDirection == FocusDirection.Previous
                ) {
                    cancelFocusChange()
                }
            }
        }
        .focusGroup()
}

val LocalOverlayFocusStack = staticCompositionLocalOf { OverlayFocusStack() }

/**
 * Keeps [node]'s presence in the window's overlay stack and moves focus into [surfaceRequester]
 * when the overlay opens, returning focus to the opener when it leaves.
 */
@Composable
internal fun ModalOverlayFocus(
    nodeId: Int,
    surfaceRequester: FocusRequester,
    onEscape: () -> Unit,
    focusSurface: Boolean = true,
    isModal: Boolean = true,
) {
    val stack = LocalOverlayFocusStack.current
    SideEffect { stack.update(nodeId, onEscape) }
    DisposableEffect(stack, nodeId, isModal) {
        stack.push(nodeId, onEscape, isModal)
        onDispose { stack.pop(nodeId) }
    }
    if (focusSurface) {
        LaunchedEffect(nodeId) {
            try {
                surfaceRequester.requestFocus()
            } catch (_: IllegalStateException) {
            }
        }
    }
}

/** Modifier that places an overlay's surface in the focus tree and catches an unconsumed Escape. */
@Composable
internal fun Modifier.modalOverlaySurface(
    nodeId: Int,
    surfaceRequester: FocusRequester,
): Modifier {
    val stack = LocalOverlayFocusStack.current
    return this
        .onKeyEvent { event -> stack.handleEscape(event, fromNodeId = nodeId) }
        .focusRequester(surfaceRequester)
        .focusable()
}

/** Whether an overlay is showing, and the way to change it without asking the Host. */
internal class OverlayOpen(val value: Boolean, val set: (Boolean) -> Unit)

/**
 * The open state of an overlay.
 *
 * It lives here, in the Renderer. The Host property seeds it and carries changes that came
 * from somewhere else, so opening and closing costs no boundary call and the enter and exit
 * never wait for an answer. The Host is told about a dismissal once, and decides whether to
 * honour it by sending the property back as false.
 */
@Composable
internal fun rememberOverlayOpen(node: Node): OverlayOpen {
    val fromHost = node.flag(PropertyKind.Open, default = false)
    var open by remember(node.id) { mutableStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { open = fromHost }
    return OverlayOpen(open) { open = it }
}

/** Tells the Host the user asked to close this overlay, if it asked to be told. */
internal fun dismiss(node: Node, dispatcher: EventDispatcher) {
    val handlerId = node.handler(PropertyKind.OnDismiss) ?: return
    dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId))
}

/**
 * A modal. Its children are its content.
 *
 * The scrim, the placement and the corner come from the design system, so the same records
 * are a Material dialog, a HIG alert or a Fluent content dialog.
 */
/** Test tag of a dialog's surface inside its popup. */
fun dialogTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-dialog"

@Composable
internal fun HostDialog(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    if (!open.value) return
    val style = theme.rules.container(ContainerRole.Dialog, theme)
    val scrim = node.paintProp(PropertyKind.Scrim)?.let { theme.color(it) }
        ?: theme.overlay?.scrim?.takeIf { it != 0L }?.let { androidx.compose.ui.graphics.Color(it) }
        ?: style.scrim
    val surfaceRequester = remember(node.id) { FocusRequester() }
    val onEscape = { dismiss(node, dispatcher) }
    ModalOverlayWindow(
        {
            open.set(false)
            dismiss(node, dispatcher)
        },
        onEscape,
    ) {
        val menuEntries = menuEntriesIn(node, node.children, table)
        val menuKeys = if (menuEntries != null) {
            rememberMenuKeyboard(menuEntries, table)
        } else {
            null
        }
        ModalOverlayFocus(node.id, surfaceRequester, onEscape, focusSurface = menuKeys == null)
        // The scrim covers the whole window; the dialog is centred in what the keyboard
        // leaves, so a field inside it is not under the keyboard.
        val keyboard = overlayKeyboardInset()
        val exposed = LocalOverlayFocusStack.current.exposesOverlay(node.id)
        CompositionLocalProvider(LocalMenuKeyboard provides menuKeys) {
            Box(
                Modifier
                    // A modal opened over this one hides it, as this one hides the page.
                    .behindModal(!exposed)
                    .fillMaxSize()
                    // A scrim that dims the window dims a platform view too: it is covered.
                    .nativeViewOverlay(coversWindow = scrim.alpha > 0f)
                    .background(scrim)
                    .testTag(dialogTestTag(node.id)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize().padding(bottom = keyboard), contentAlignment = Alignment.Center) {
                    Column(
                        modifier
                            .let { if (menuKeys != null) it.menuKeys(menuKeys) else it }
                            .modalOverlaySurface(node.id, surfaceRequester)
                            .containerDecoration(node, style, theme, overlay = true, role = ContainerRole.Dialog),
                    ) {
                        node.children.forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A popup hanging off an anchor. Child 0 is the anchor and the rest are the entries.
 *
 * The Host never learns a screen coordinate: where the popup sits relative to its anchor is
 * the design system's placement rule. Choosing an entry is that entry's own click, so the
 * menu needs no event of its own.
 *
 * Where the window has an [OsMenuPresenter] the entries go up as the operating system's
 * menu instead of a popup drawn here, and a node among them that no such menu can show is
 * reported rather than drawn ([ReportNotAnEntry]). A menu the Host marked as an entry is a
 * submenu: its open state is the Renderer's alone and its anchor's press opens it, because
 * that is how every platform's submenu behaves and the Host has nothing to decide.
 */
@Composable
internal fun HostMenu(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val submenu = node.isMenuEntry()
    val hostOpen = rememberOverlayOpen(node)
    var ownOpen by remember(node.id) { mutableStateOf(false) }
    val open = if (submenu) OverlayOpen(ownOpen) { ownOpen = it } else hostOpen
    val style = theme.rules.container(ContainerRole.Menu, theme)
    val anchorId = node.children.firstOrNull()
    val entries = node.children.drop(1)
    val anchorRequester = remember(node.id) { FocusRequester() }
    // A submenu's row opens it here and is not a click the Host hears.
    val anchorDispatcher = if (submenu && anchorId != null) {
        remember(dispatcher, anchorId) {
            EventDispatcher { event ->
                if (event is HostEvent.Clicked && event.nodeId == anchorId) {
                    ownOpen = !ownOpen
                    true
                } else {
                    dispatcher.dispatch(event)
                }
            }
        }
    } else {
        dispatcher
    }
    val presenter = LocalOsMenuPresenter.current
    if (presenter != null) {
        val read = table.menuEntries(node.id, entries)
        ReportNotAnEntry(node.id, read, dispatcher)
        val anchor = rememberMenuAnchor(node.id)
        Box(modifier.then(anchor.modifier)) {
            if (anchorId != null) {
                key(anchorId) { RenderNode(anchorId, table, anchorDispatcher, Modifier.focusRequester(anchorRequester)) }
            }
            if (open.value && read.notAnEntry == null) {
                OsEntryMenu(
                    presenter = presenter,
                    entries = read.entries,
                    anchor = anchor,
                    onChosen = { item ->
                        open.set(false)
                        table.clickEntry(item.id, dispatcher)
                    },
                    onDismissed = {
                        open.set(false)
                        dismiss(node, dispatcher)
                    },
                )
            }
        }
        return
    }
    Box(modifier) {
        if (anchorId != null) {
            key(anchorId) { RenderNode(anchorId, table, anchorDispatcher, Modifier.focusRequester(anchorRequester)) }
        }
        if (open.value) {
            Popup(
                popupPositionProvider = BelowAnchorPositionProvider,
                properties = PopupProperties(focusable = true),
                onDismissRequest = {
                    open.set(false)
                    dismiss(node, dispatcher)
                },
            ) {
                // The recorded page does not reach in here. A popup is its own platform
                // window, so a position measured against "the window" means a different
                // window on each side of this line, and the difference between the two is
                // not a distance: drawing the page by it puts a piece of the page
                // somewhere inside the menu. A menu that hangs past the window's edge has
                // nothing of ours behind it there anyway.
                ReturnsFocusOnClose(anchorRequester)
                val keys = rememberMenuKeyboard(entries, table) {
                    open.set(false)
                    dismiss(node, dispatcher)
                }
                CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
                Column(
                    Modifier
                        .testTag(menuPopupTestTag(node.id))
                        .menuKeys(keys)
                        .containerDecoration(node, style, theme, overlay = true, role = ContainerRole.Menu)
                        .menuWidth(),
                ) {
                    entries.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher, keys.entry(childId)) }
                    }
                }
                }
            }
        }
    }
}

/**
 * Gives focus back to [opener] when the popup this is called in goes away, whichever way it
 * was closed. An opener with nothing that can hold focus leaves focus where it was.
 */
@Composable
internal fun ReturnsFocusOnClose(opener: FocusRequester) {
    DisposableEffect(opener) {
        onDispose {
            try {
                opener.requestFocus()
            } catch (_: IllegalStateException) {
                // The opener left the tree while the menu was open.
            }
        }
    }
}

/**
 * The keyboard of an open menu, as a desktop menu and a WAI-ARIA menu have it.
 *
 * Each entry is one stop. Opening the menu focuses the entry the Host marked selected, or
 * the first entry that can take focus. The up and down arrows move to the previous and the
 * next entry that can take focus and go round at the ends, and Home and End go to the first
 * and the last. Enter and Space are the focused entry's own press. Escape closes the menu,
 * as a press outside it does, and [ReturnsFocusOnClose] hands focus back to the anchor.
 * The popup does not close on Escape by itself, so without this the key did nothing.
 * An entry that cannot take focus, a divider or a disabled row, is passed over.
 *
 * None of these keys reaches the Host: moving among the entries is the menu's own state,
 * like the scroll position of a list, and choosing one is that entry's ordinary click.
 */
internal class MenuKeyboard(private val table: NodeTable) {
    var entries: List<Int> = emptyList()

    /**
     * The entry to focus on open when the selection is not marked on the entries themselves,
     * as in a select field that keeps its choice by position.
     */
    var chosen: Int? = null

    /** What Escape does: the same as a press outside the menu, or null when the overlay handles Escape itself. */
    var onEscape: (() -> Unit)? = null
    private val requesters = mutableMapOf<Int, FocusRequester>()

    /** The entry that has focus now, set by a move made here or by focus arriving another way. */
    private var current: Int? = null

    /**
     * The entry focused via keyboard navigation (ArrowDown, ArrowUp, Home, End), observed by
     * Compose so the focused row draws its keyboard-focus highlight. Stays null when a menu
     * opens by pointer so no ring appears until the user moves with the keyboard.
     */
    var focusedByKeyboard: Int? by mutableStateOf(null)
        private set

    private fun requester(id: Int) = requesters.getOrPut(id) { FocusRequester() }

    /** The modifier an entry is drawn with, so focus can be sent to it and seen arriving. */
    fun entry(id: Int): Modifier = Modifier
        .focusRequester(requester(id))
        .onFocusChanged {
            if (it.hasFocus) {
                current = id
            } else if (focusedByKeyboard == id) {
                focusedByKeyboard = null
            }
        }

    fun isKeyboardFocused(id: Int): Boolean = focusedByKeyboard == id

    private fun focus(id: Int, fromKeyboard: Boolean = false): Boolean {
        val taken = try {
            requester(id).requestFocus()
        } catch (_: IllegalStateException) {
            false
        }
        if (taken) {
            current = id
            if (fromKeyboard) focusedByKeyboard = id
        }
        return taken
    }

    /** Focuses the first entry that takes it, starting at [from] and stepping by [step]. */
    private fun focusFrom(from: Int, step: Int, fromKeyboard: Boolean = false): Boolean {
        val count = entries.size
        if (count == 0) return false
        for (i in 0 until count) {
            if (focus(entries[(from + step * i).mod(count)], fromKeyboard)) return true
        }
        return false
    }

    /** Where focus goes when the menu opens. */
    fun focusOnOpen() {
        val selected = chosen ?: entries.firstOrNull { table.node(it)?.isDeclaredSelected() == true }
        if (selected != null && focus(selected)) return
        focusFrom(0, 1)
    }

    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // Android delivers its back button to a focused popup as Key.Back.
        if (event.key == Key.Escape || event.key == Key.Back) {
            val escape = onEscape ?: return false
            escape()
            return true
        }
        val count = entries.size
        if (count == 0) return false
        val at = current?.let { entries.indexOf(it) }?.takeIf { it >= 0 }
        when (event.key) {
            Key.DirectionDown -> focusFrom(if (at == null) 0 else at + 1, 1, fromKeyboard = true)
            Key.DirectionUp -> focusFrom(if (at == null) count - 1 else at - 1, -1, fromKeyboard = true)
            Key.MoveHome -> focusFrom(0, 1, fromKeyboard = true)
            Key.MoveEnd -> focusFrom(count - 1, -1, fromKeyboard = true)
            else -> return false
        }
        // Taken even when no other entry could take focus, so the key does not fall
        // through to whatever is behind the menu.
        return true
    }
}

/**
 * The keyboard of a menu whose popup shows [entries], focused on the selected one as it
 * opens, or on [chosen] when that is given. [onEscape] is what closing the menu from the keyboard does.
 */
@Composable
internal fun rememberMenuKeyboard(
    entries: List<Int>,
    table: NodeTable,
    chosen: Int? = null,
    onEscape: (() -> Unit)? = null,
): MenuKeyboard {
    val keys = remember(table) { MenuKeyboard(table) }
    keys.entries = entries
    keys.chosen = chosen
    keys.onEscape = onEscape
    LaunchedEffect(keys) { keys.focusOnOpen() }
    return keys
}

/** Moves focus among the entries for the keys a menu answers, after the focused entry declined them. */
internal fun Modifier.menuKeys(keys: MenuKeyboard): Modifier = onKeyEvent { keys.handle(it) }

/**
 * The [MenuKeyboard] of an enclosing popover whose content is a menu, so entries nested
 * inside layout containers receive their focus requesters when drawn.
 */
internal val LocalMenuKeyboard = staticCompositionLocalOf<MenuKeyboard?> { null }

/**
 * The entries of an overlay (popover, sheet, or dialog) whose content is a menu, in tree order,
 * or null when the overlay is not a menu.
 *
 * An overlay is a menu when it or a node in its content declares [SemanticsRole.Menu], or when
 * its content holds nodes that declare [SemanticsRole.MenuItem]. When items are marked with
 * [SemanticsRole.MenuItem], those nodes are the entries. Otherwise the direct children of the
 * node that declared [SemanticsRole.Menu] are the entries, as in a `Menu` widget.
 */
internal fun menuEntriesIn(
    container: Node,
    content: List<Int>,
    table: NodeTable,
): List<Int>? {
    val menuItems = mutableListOf<Int>()
    var menuContainer: Node? = if (container.declaredSemanticsRole() == SemanticsRole.Menu) container else null

    fun walk(id: Int) {
        val current = table.node(id) ?: return
        when (current.declaredSemanticsRole()) {
            SemanticsRole.MenuItem -> {
                menuItems += id
                return
            }
            SemanticsRole.Menu -> if (menuContainer == null) menuContainer = current
            else -> {}
        }
        // Do not descend into a nested overlay's own content.
        if (current.widget == WidgetKind.Popover ||
            current.widget == WidgetKind.Menu ||
            current.widget == WidgetKind.Dialog ||
            current.widget == WidgetKind.Sheet
        ) {
            return
        }
        for (childId in current.children) walk(childId)
    }

    for (childId in content) walk(childId)
    if (menuItems.isNotEmpty()) return menuItems
    val found = menuContainer ?: return null
    return if (found.id == container.id) content else found.children
}

/**
 * Whether what is being drawn is inside a menu's popup.
 *
 * A menu's entries are declared as buttons, because that is what they are to the
 * application: a thing with a name that does something when it is chosen. What they are to
 * the reader is a list, and a system that cuts every button into a capsule drew the list as
 * a stack of pills with room between them, which is a sheet of actions on a phone rather
 * than a menu on a desktop.
 */
val LocalInAMenu = compositionLocalOf { false }

/**
 * Places a menu or dropdown popup directly below its anchor, flipping above the anchor only
 * when there is not enough room below in the window.
 *
 * Across, the popup lines up with the anchor's leading edge when it fits that way, and with
 * the anchor's trailing edge when it would otherwise run past the window, so a menu opened
 * from a button at the end of a bar hangs under that button instead of being pushed
 * against the window's edge. Whatever still does not fit is slid back inside the window.
 */
internal object BelowAnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val fitsFromStart = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + popupContentSize.width <= windowSize.width
        } else {
            anchorBounds.right - popupContentSize.width >= 0
        }
        return popoverPosition(
            anchor = anchorBounds,
            window = windowSize,
            content = popupContentSize,
            placement = PopoverPlacement.Bottom,
            align = if (fitsFromStart) PopoverAlign.Start else PopoverAlign.End,
            offset = 0,
            margin = 0,
            layoutDirection = layoutDirection,
        )
    }
}

/**
 * As wide as the widest entry, and no wider.
 *
 * A popup is measured against the whole window, so an entry that asks to fill the width,
 * which is how a menu row is written, would otherwise make the menu as wide as the window:
 * a band across the screen under the bar rather than a menu under its button. Measuring
 * the entries' own widths first gives the column a width, and a filling entry then fills
 * that, so every row is as wide as the widest one.
 */
internal fun Modifier.menuWidth(): Modifier = width(IntrinsicSize.Max)

/** Test tag of the popup half of a menu, which is its own window, not part of the anchor. */
fun menuPopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-popup"

/**
 * An explanation attached to its child.
 *
 * The text is also the child's accessibility description, so it can be reached without a
 * pointer. How long the pointer has to rest before the popup appears is a design system
 * rule, not something the Host sends.
 */
@Composable
internal fun HostTooltip(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val description = node.text(PropertyKind.Text)
    val style = theme.rules.container(ContainerRole.Tooltip, theme)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var showing by remember(node.id) { mutableStateOf(false) }
    val delayMillis = theme.rules.motion.tooltipDelayMillis
    LaunchedEffect(hovered, delayMillis) {
        if (!hovered) {
            showing = false
            return@LaunchedEffect
        }
        delay(delayMillis.toLong())
        showing = true
    }
    Box(
        modifier
            .hoverable(interactions)
            .semantics { contentDescription = description },
    ) {
        node.children.forEach { childId -> key(childId) { RenderNode(childId, table, dispatcher) } }
        if (showing && description.isNotEmpty()) {
            Popup(alignment = Alignment.BottomCenter) {
                Box(
                    Modifier
                        .testTag(tooltipPopupTestTag(node.id))
                        .containerDecoration(node, style, theme, overlay = true),
                ) {
                    ThemedText(
                        text = description,
                        style = node.textStyle(theme, style.typeRole).copy(color = style.content),
                    )
                }
            }
        }
    }
}

/** Test tag of the tooltip popup, which is its own window, not part of what it explains. */
fun tooltipPopupTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-tooltip"
