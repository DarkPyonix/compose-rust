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
import androidx.compose.ui.semantics.contentDescription
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
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
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
 */
class OverlayFocusStack {
    private class Entry(val nodeId: Int, var onEscape: () -> Unit, val opener: FocusRequester?)

    private val entries = mutableListOf<Entry>()
    private var openCount by mutableStateOf(0)

    /** Whether any overlay is open, read in composition so a platform back hook follows it. */
    val hasOpen: Boolean get() = openCount > 0
    private var currentFocused: FocusRequester? = null

    internal fun noteFocused(requester: FocusRequester, focused: Boolean) {
        if (focused) {
            currentFocused = requester
        } else if (currentFocused === requester) {
            currentFocused = null
        }
    }

    internal fun push(nodeId: Int, onEscape: () -> Unit): FocusRequester? {
        entries.removeAll { it.nodeId == nodeId }
        val opener = currentFocused
        entries += Entry(nodeId, onEscape, opener)
        openCount = entries.size
        return opener
    }

    internal fun update(nodeId: Int, onEscape: () -> Unit) {
        entries.firstOrNull { it.nodeId == nodeId }?.onEscape = onEscape
    }

    internal fun pop(nodeId: Int) {
        val index = entries.indexOfFirst { it.nodeId == nodeId }
        if (index < 0) return
        val removed = entries.removeAt(index)
        openCount = entries.size
        val target = if (entries.isEmpty()) removed.opener else null
        if (target != null) {
            try {
                target.requestFocus()
            } catch (_: IllegalStateException) {
                // The opener left the tree while the overlay was open.
            }
        }
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
) {
    val stack = LocalOverlayFocusStack.current
    SideEffect { stack.update(nodeId, onEscape) }
    DisposableEffect(stack, nodeId) {
        stack.push(nodeId, onEscape)
        onDispose { stack.pop(nodeId) }
    }
    LaunchedEffect(nodeId) {
        try {
            surfaceRequester.requestFocus()
        } catch (_: IllegalStateException) {
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
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        onDismissRequest = {
            open.set(false)
            dismiss(node, dispatcher)
        },
    ) {
        platformOverlayWindow()
        ModalOverlayFocus(node.id, surfaceRequester, onEscape)
        // The scrim covers the whole window; the dialog is centred in what the keyboard
        // leaves, so a field inside it is not under the keyboard.
        val keyboard = dev.darkpyonix.composerust.runtime.LocalKeyboardInset.current
        Box(
            Modifier
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

/**
 * A popup hanging off an anchor. Child 0 is the anchor and the rest are the entries.
 *
 * The Host never learns a screen coordinate: where the popup sits relative to its anchor is
 * the design system's placement rule. Choosing an entry is that entry's own click, so the
 * menu needs no event of its own.
 */
@Composable
internal fun HostMenu(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    val style = theme.rules.container(ContainerRole.Menu, theme)
    val anchorId = node.children.firstOrNull()
    val entries = node.children.drop(1)
    Box(modifier) {
        if (anchorId != null) {
            key(anchorId) { RenderNode(anchorId, table, dispatcher) }
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
                CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
                Column(
                    Modifier
                        .testTag(menuPopupTestTag(node.id))
                        .containerDecoration(node, style, theme, overlay = true, role = ContainerRole.Menu)
                        .menuWidth(),
                ) {
                    entries.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher) }
                    }
                }
                }
            }
        }
    }
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
