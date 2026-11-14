package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.PopoverAlign
import dev.darkpyonix.composerust.protocol.PopoverPlacement
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.role

/**
 * Where a popover's content goes, as its top left corner in window pixels.
 *
 * Along the side it was asked to open on, the content stands [offset] away from the anchor.
 * When it does not fit between the anchor and the window's edge there, it opens on the
 * opposite side instead (the flip), and when it fits on neither it takes the side with
 * more room. Across that side it lines up with the edge of the anchor [align] names.
 * Whatever still reaches past the window is then slid back inside, [margin] clear of the
 * edge (the shift), on both axes.
 *
 * `Start` and `End` mean left and right in a left to right layout and swap in a right to
 * left one, for the side and for the edge alike.
 */
fun popoverPosition(
    anchor: IntRect,
    window: IntSize,
    content: IntSize,
    placement: PopoverPlacement,
    align: PopoverAlign,
    offset: Int,
    margin: Int,
    layoutDirection: LayoutDirection,
): IntOffset {
    val rtl = layoutDirection == LayoutDirection.Rtl
    return when (placement) {
        PopoverPlacement.Top, PopoverPlacement.Bottom -> {
            val y = alongSide(
                anchor.top, anchor.bottom, content.height, window.height, offset, margin,
                after = placement == PopoverPlacement.Bottom,
            )
            // Across a top or bottom side the edges are the leading and trailing ones,
            // which are the right and left ones in a right to left layout.
            val edge = if (rtl) align.mirrored() else align
            val x = acrossSide(anchor.left, anchor.right, content.width, edge)
            IntOffset(shiftInside(x, content.width, window.width, margin), y)
        }
        PopoverPlacement.Start, PopoverPlacement.End -> {
            val x = alongSide(
                anchor.left, anchor.right, content.width, window.width, offset, margin,
                after = (placement == PopoverPlacement.End) != rtl,
            )
            val y = acrossSide(anchor.top, anchor.bottom, content.height, align)
            IntOffset(x, shiftInside(y, content.height, window.height, margin))
        }
    }
}

/**
 * The largest a popover's content may be in a window of this size: the window less
 * [margin] on every side, and never less than nothing.
 */
fun popoverMaxContent(window: IntSize, margin: Int): IntSize = IntSize(
    (window.width - 2 * margin).coerceAtLeast(0),
    (window.height - 2 * margin).coerceAtLeast(0),
)

private fun PopoverAlign.mirrored(): PopoverAlign = when (this) {
    PopoverAlign.Start -> PopoverAlign.End
    PopoverAlign.Center -> PopoverAlign.Center
    PopoverAlign.End -> PopoverAlign.Start
}

/**
 * One axis of the side the content opens on. `after` is the side past the anchor's far
 * edge (below it, or to its right); the other is the side before its near edge.
 */
private fun alongSide(
    anchorNear: Int,
    anchorFar: Int,
    size: Int,
    window: Int,
    offset: Int,
    margin: Int,
    after: Boolean,
): Int {
    val afterAt = anchorFar + offset
    val beforeAt = anchorNear - offset - size
    val roomAfter = window - margin - afterAt
    val roomBefore = anchorNear - offset - margin
    val fitsAfter = size <= roomAfter
    val fitsBefore = size <= roomBefore
    val useAfter = when {
        after && fitsAfter -> true
        !after && fitsBefore -> false
        fitsAfter -> true
        fitsBefore -> false
        // It fits on neither side, so it goes where less of it has to be slid back over
        // the anchor, and stays on the side it was asked for when the two are the same.
        roomAfter == roomBefore -> after
        else -> roomAfter > roomBefore
    }
    return shiftInside(if (useAfter) afterAt else beforeAt, size, window, margin)
}

/** One axis across the side: which edge of the anchor the content lines up with. */
private fun acrossSide(anchorNear: Int, anchorFar: Int, size: Int, align: PopoverAlign): Int =
    when (align) {
        PopoverAlign.Start -> anchorNear
        PopoverAlign.Center -> anchorNear + (anchorFar - anchorNear - size) / 2
        PopoverAlign.End -> anchorFar - size
    }

/**
 * Slides a position back so the content ends before the window's far edge and starts after
 * its near one. The near edge is applied last, so content too large for the window starts
 * at the margin and loses its end rather than its beginning.
 */
private fun shiftInside(position: Int, size: Int, window: Int, margin: Int): Int =
    position.coerceAtMost(window - margin - size).coerceAtLeast(margin)

/**
 * [popoverPosition] as the position provider Compose's `Popup` asks.
 *
 * The window it places by is the one its content was last measured against ([measuredIn]),
 * when there is one. A popup is measured against the window as it is now, every time the
 * window changes size. The size the popup hands this provider is read from the window's
 * description when the popup is composed, and a window that does not announce a change of
 * size leaves that reading at the size the popover opened in.
 */
internal data class PopoverPositionProvider(
    val placement: PopoverPlacement,
    val align: PopoverAlign,
    val offset: Int,
    val margin: Int,
) : PopupPositionProvider {
    /** The window the content was last measured against, or null before the first measure. */
    var measuredIn: IntSize? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = popoverPosition(
        anchor = anchorBounds,
        window = measuredIn ?: windowSize,
        content = popupContentSize,
        placement = placement,
        align = align,
        offset = offset,
        margin = margin,
        layoutDirection = layoutDirection,
    )
}

/**
 * Measures what it wraps in no more than the window less [margin] on every side.
 *
 * A popup is measured against the window it is shown in, so the incoming maximum is the
 * window's size. Content smaller than that keeps the size it asked for.
 */
private fun Modifier.popoverSizeLimit(
    margin: Dp,
    /** Told the window this was measured against, before the popup asks where to go. */
    provider: PopoverPositionProvider,
): Modifier = layout { measurable, constraints ->
    val edge = margin.roundToPx()
    provider.measuredIn = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
        IntSize(constraints.maxWidth, constraints.maxHeight)
    } else {
        null
    }
    val limit = popoverMaxContent(
        IntSize(
            if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity,
            if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
        ),
        edge,
    )
    val placeable = measurable.measure(
        Constraints(
            maxWidth = if (constraints.hasBoundedWidth) limit.width else Constraints.Infinity,
            maxHeight = if (constraints.hasBoundedHeight) limit.height else Constraints.Infinity,
        ),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** How far a popover's content is kept from the window's edges. */
internal fun popoverWindowMargin(theme: ResolvedTheme): Dp = theme.space(SpaceRole.Sm)

/** The gap between an anchor and its popover when the Host names none. */
internal fun popoverDefaultOffset(theme: ResolvedTheme): Dp = theme.space(SpaceRole.Xs)

/** Test tag of the scrim a popover draws over the window when it was asked for one. */
fun popoverScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"

/** Test tag of the box round a popover's anchor, which is what the content is placed by. */
fun popoverAnchorTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-anchor"

/**
 * A floating box of any content attached to an anchor. Child 0 is the anchor and is always
 * drawn; the rest is the content, drawn while the Host says the popover is open.
 *
 * **Where it goes is decided here.** The Host names a side, an edge and a gap, and never
 * learns a coordinate. [popoverPosition] keeps the box inside the window, and the popup asks
 * it again whenever the anchor moves or the window changes size.
 *
 * **Whether it is open is the Host's.** Pressing outside the box, or Escape while it is the
 * topmost overlay, sends one dismissal and closes nothing: the box goes when the Host sends
 * the property back as false. That is unlike a menu, which closes itself, because what a
 * popover holds is the application's own content and may have work in it to keep.
 *
 * [parentModifier] is what only the anchor's parent can say (a weight in a row) and goes
 * on the anchor. [modifier] is the node's own chain and goes on the floating box, so a
 * shape, a border, a shadow or a padding written on the node replaces the design system's.
 */
@Composable
internal fun HostPopover(
    node: Node,
    parentModifier: Modifier,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = node.flag(PropertyKind.Open, default = false)
    val anchorId = node.children.firstOrNull()
    val content = node.children.drop(1)
    val anchorRequester = remember(node.id) { FocusRequester() }
    Box(
        parentModifier
            .focusRequester(anchorRequester)
            .testTag(popoverAnchorTestTag(node.id)),
        // A weight or a width the parent gave reaches the anchor, so the box the content
        // is placed by is the anchor and not a larger box with the anchor in its corner.
        propagateMinConstraints = true,
    ) {
        if (anchorId != null) {
            key(anchorId) { RenderNode(anchorId, table, dispatcher) }
        }
        if (open) {
            PopoverScrim(node, theme)
            PopoverContent(node, content, modifier, anchorRequester, table, dispatcher, theme)
        }
    }
}

/**
 * The dimming behind an open popover, drawn only when the node asked for it.
 *
 * It is a popup of its own, below the content's, and takes no input: the content's popup
 * is the one that hears a press outside itself, so a press on the scrim is a press outside
 * the content and is reported once, by that popup.
 *
 * The popup is there for as long as the popover is open, empty until a scrim is asked for.
 * Popups stack in the order they are made, so one made when the Host turns the scrim on
 * for a popover that is already open would sit above the content, dim it and take the
 * presses meant to dismiss it. An empty popup has no size, draws nothing and hears nothing.
 */
@Composable
private fun PopoverScrim(node: Node, theme: ResolvedTheme) {
    val named = node.paintProp(PropertyKind.Scrim)
    val asked = named != null || node.flag(PropertyKind.Scrim, default = false)
    Popup(alignment = Alignment.TopStart, properties = PopupProperties(focusable = false)) {
        if (!asked) return@Popup
        val color = named?.let { theme.color(it) }
            ?: theme.overlay?.scrim?.takeIf { it != 0L }?.let { Color(it) }
            // A menu's own scrim is clear, because a menu covers nothing. The one a dialog
            // dims the window with is the design system's answer to "dim the window".
            ?: theme.rules.container(ContainerRole.Dialog, theme).scrim
        Box(
            Modifier
                .fillMaxSize()
                // A scrim that dims the window dims a platform view too: it is covered.
                .nativeViewOverlay(coversWindow = color.alpha > 0f)
                .background(color)
                .testTag(popoverScrimTestTag(node.id)),
        )
    }
}

@Composable
private fun PopoverContent(
    node: Node,
    content: List<Int>,
    modifier: Modifier,
    anchorRequester: FocusRequester,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val density = LocalDensity.current
    val margin = popoverWindowMargin(theme)
    val offset = node.number(PropertyKind.OffsetDp)?.dp ?: popoverDefaultOffset(theme)
    val placement = node.role(PropertyKind.Placement, PopoverPlacement.entries.toTypedArray())
        ?: PopoverPlacement.Bottom
    val align = node.role(PropertyKind.Align, PopoverAlign.entries.toTypedArray())
        ?: PopoverAlign.Start
    val offsetPx = with(density) { offset.roundToPx() }
    val marginPx = with(density) { margin.roundToPx() }
    // One provider for as long as the rule is the same, so the one the popup asks is the
    // one the content tells the window it was measured against.
    val provider = remember(placement, align, offsetPx, marginPx) {
        PopoverPositionProvider(placement, align, offsetPx, marginPx)
    }
    // The surface is a popup's: the same fill, corner, height and padding a menu takes
    // from the design system and from the application's overlay tokens. A material the
    // node named replaces the fill, and is drawn here rather than by the node's modifier
    // chain so that it takes the surface's corner and lifts off the page as one sheet.
    val base = theme.rules.container(ContainerRole.Menu, theme)
    val ownMaterial = node.modifiers.lastOrNull { it is ProtocolModifier.Material }
        as? ProtocolModifier.Material
    val style = if (ownMaterial == null) {
        base
    } else {
        base.copy(material = theme.rules.material(ownMaterial.role, theme))
    }
    val surfaceRequester = remember(node.id) { FocusRequester() }
    val onDismiss = { dismiss(node, dispatcher) }
    Popup(
        popupPositionProvider = provider,
        // Escape is not the popup's to act on: the overlay stack gives it to the topmost
        // overlay only, after whatever holds focus inside has declined it.
        // The position rule already keeps the box inside the window. The popup's own
        // clipping is left off because it clips to the window size it read when it was
        // composed, which after the window has grown is the smaller, older one.
        properties = PopupProperties(focusable = true, dismissOnBackPress = false, clippingEnabled = false),
        onDismissRequest = onDismiss,
    ) {
        // Registered before the overlay's own focus effect so that it is undone after it:
        // the stack hands focus back to whatever held it, and then the anchor takes it if
        // it has anything that can. An anchor with no focus stop leaves the first answer.
        DisposableEffect(node.id) {
            onDispose {
                try {
                    anchorRequester.requestFocus()
                } catch (_: IllegalStateException) {
                    // The anchor left the tree while the popover was open.
                }
            }
        }
        ModalOverlayFocus(node.id, surfaceRequester, onDismiss)
        // The recorded page does not reach into a popup, for the reason a menu gives.
        CompositionLocalProvider(LocalGlassBackdrop provides null) {
            Column(
                Modifier
                    .popoverSizeLimit(margin, provider)
                    .then(modifier)
                    .modalOverlaySurface(node.id, surfaceRequester)
                    // A group that floats over the page, not a dialog: nothing behind it
                    // is taken away from the reader, and no title is announced for it.
                    .semantics {
                        popup()
                        isTraversalGroup = true
                    }
                    .containerDecoration(node, style, theme, overlay = true, role = ContainerRole.Menu),
            ) {
                content.forEach { childId ->
                    key(childId) { RenderNode(childId, table, dispatcher) }
                }
            }
        }
    }
}
