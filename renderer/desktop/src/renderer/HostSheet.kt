package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.SheetEdge
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalKeyboardInset
import dev.darkpyonix.composerust.runtime.LocalSystemBars
import dev.darkpyonix.composerust.runtime.LocalWindowSizeClass
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.paintProp
import kotlin.math.roundToInt

/** How far a sheet has to be pulled towards its edge before letting go closes it. */
private val DISMISS_TRAVEL = 96.dp

/** The grab handle's size, where the design system draws one. */
private val HANDLE_LENGTH = 32.dp
private val HANDLE_THICKNESS = 4.dp

/**
 * Called inside the window a sheet or a dialog is drawn in, before anything else in it.
 *
 * A hook because only a platform whose overlays are windows of their own can tell such a
 * window not to be moved by the keyboard: Android shrinks a focusable one to the space
 * above the keyboard, which left the scrim covering only part of the screen. The renderer
 * moves the overlay's surface itself (see [LocalKeyboardInset]). Where nobody installs
 * one, this does nothing.
 */
var platformOverlayWindow: @Composable () -> Unit = {}

/**
 * Where the window of a sheet or a dialog goes: at the corner of the application's window,
 * whatever node opened it.
 *
 * The overlay's scrim covers the whole window, so its window has to start where the
 * application's does. Centring it on the node that opened it was only the same thing while
 * the overlay was as big as the window. On Android it was not: the popup came out shorter
 * than the window (see [ModalOverlayProperties]) and centring that on the page put its top
 * part way down, so the status bar strip and the top of the page were left undimmed.
 */
internal object ModalOverlayPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * The popup a sheet or a dialog is drawn in.
 *
 * Focusable, so the fields inside take the keyboard; Back is the renderer's, not the
 * popup's (see [ModalOverlayFocus]). Not clipped: Android measures and places a clipped
 * popup inside the parent window's visible frame, which leaves out the status bar, the
 * navigation bar and the on-screen keyboard. The scrim then stopped short of the status
 * bar, and with the keyboard up the popup was only as tall as the space above it, so the
 * keyboard was taken off the sheet a second time and its surface collapsed to the handle.
 * Unclipped, the popup is measured against the whole window, keyboard or not, and the
 * renderer moves the surface above the keyboard itself ([LocalKeyboardInset]).
 */
internal val ModalOverlayProperties = PopupProperties(
    focusable = true,
    dismissOnBackPress = false,
    clippingEnabled = false,
)

/**
 * How tall a sheet along the bottom edge is, in a window [window] tall.
 *
 * The design system asks for a share of the window ([fraction]), and the keyboard does not
 * change what it asked for: the sheet keeps its height and moves up above the keyboard. Only
 * when the space between the keyboard and the status bar ([top]) is smaller than that does
 * it give way, and then it takes all of that space rather than running under either. Taking
 * the keyboard off the height before the share was applied made the sheet shrink every time
 * the keyboard opened.
 */
internal fun bottomSheetHeight(window: Dp, fraction: Float, keyboard: Dp, top: Dp): Dp {
    val asked = window * fraction
    if (keyboard <= 0.dp) return asked
    return minOf(asked, window - keyboard - top).coerceAtLeast(0.dp)
}

/**
 * A temporary surface that comes in from an edge of the window.
 *
 * On the wire it is a `Dialog`: an `Open` property that seeds this side's own state, and one
 * `OnDismiss` when the user asks to close it. Which edge it comes from is not on the wire at
 * all, because the Renderer is the side that measured the window.
 *
 * **The drag does not cross the boundary.** While the sheet is being pulled about, the Host
 * hears nothing: the travel is state here, and only a drag that ends past the threshold
 * becomes one dismissal. A Host that heard every frame of a drag would be running the
 * VirtualDom for an animation it cannot see.
 */
@Composable
internal fun HostSheet(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val open = rememberOverlayOpen(node)
    if (!open.value) return
    val style = theme.rules.sheet(LocalWindowSizeClass.current, theme)
    val bottom = style.edge == SheetEdge.Bottom
    val threshold = with(LocalDensity.current) { DISMISS_TRAVEL.toPx() }
    var travel by remember(node.id) { mutableFloatStateOf(0f) }
    val close = {
        travel = 0f
        open.set(false)
        dismiss(node, dispatcher)
    }
    val surfaceRequester = remember(node.id) { FocusRequester() }
    val onEscape = { dismiss(node, dispatcher) }

    val scrim = node.paintProp(PropertyKind.Scrim)?.let { theme.color(it) }
        ?: theme.overlay?.scrim?.takeIf { it != 0L }?.let { androidx.compose.ui.graphics.Color(it) }
        ?: style.scrim

    val shape = if (node.setsOwnShape()) {
        node.containerShape(
            dev.darkpyonix.composerust.design.ContainerStyle(
                container = style.container,
                content = androidx.compose.ui.graphics.Color.Unspecified,
                shape = style.shape,
                elevation = style.elevation,
                borderWidth = style.borderWidth,
                borderColor = style.borderColor,
                horizontalPadding = 0.dp,
                verticalPadding = 0.dp,
                separator = null,
                scrim = style.scrim,
                typeRole = dev.darkpyonix.composerust.protocol.TypeRole.Body,
            ),
            theme,
        )
    } else {
        theme.overlay?.sheetCornerRadius?.takeIf { it > 0 }?.let {
            if (bottom) {
                androidx.compose.foundation.shape.RoundedCornerShape(it.dp, it.dp, 0.dp, 0.dp)
            } else {
                androidx.compose.foundation.shape.RoundedCornerShape(it.dp, 0.dp, 0.dp, it.dp)
            }
        } ?: style.shape
    }

    val sheetElevation = if (node.setsOwnElevation()) {
        0.dp
    } else {
        theme.overlay?.sheetElevation?.takeIf { it > 0 }?.let { it.dp } ?: style.elevation
    }
    val raised = if (node.setsOwnElevation() || sheetElevation.value <= 0f) {
        Modifier
    } else {
        theme.rules.elevation(Modifier, sheetElevation, shape, theme)
    }

    val containerBg = if (node.setsOwnBackground()) Modifier else Modifier.background(style.container)

    val outline = if (style.borderWidth.value > 0f) {
        Modifier.border(style.borderWidth, style.borderColor, shape)
    } else {
        Modifier
    }

    val explicitSheetPad = theme.overlay?.sheetPadding
    val sheetPadding = if (node.setsOwnPadding()) {
        Modifier
    } else if (explicitSheetPad != null && explicitSheetPad > 0) {
        Modifier.padding(explicitSheetPad.dp)
    } else {
        Modifier.padding(style.padding)
    }

    Popup(
        popupPositionProvider = ModalOverlayPositionProvider,
        properties = ModalOverlayProperties,
        onDismissRequest = close,
    ) {
        platformOverlayWindow()
        ModalOverlayFocus(node.id, surfaceRequester, onEscape)
        // The scrim covers the whole window, keyboard or not. Only the sheet moves up, so
        // the field inside it is above the keyboard.
        val keyboard = LocalKeyboardInset.current
        Box(
            Modifier
                .testTag(sheetScrimTestTag(node.id))
                .fillMaxSize()
                .background(scrim)
                // Pressing what the sheet covers is the other way of asking to close it,
                // and it gets the same answer: one dismissal. A raw tap detector rather
                // than `clickable`, because a scrim is not a button and saying it is one
                // puts it in the accessibility tree and merges everything under it.
                .pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.CenterEnd,
        ) {
            val top = LocalSystemBars.current.top
            // A sheet along the bottom is given its height here, against the whole window
            // and before the keyboard is taken off, so the keyboard moves it rather than
            // shrinking it. The keyboard's strip is part of what is measured here and is
            // left empty by the padding below.
            val tall = if (bottom) {
                Modifier.layout { measurable, constraints ->
                    if (!constraints.hasBoundedHeight) {
                        val placeable = measurable.measure(constraints)
                        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    val window = constraints.maxHeight.toDp()
                    val height = (bottomSheetHeight(window, style.heightFraction, keyboard, top) + keyboard)
                        .roundToPx()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
            } else {
                Modifier
            }
            val sized = if (bottom) {
                Modifier.fillMaxWidth().fillMaxHeight()
            } else {
                Modifier.fillMaxHeight().fillMaxWidth(style.widthFraction)
            }
            val aboveKeyboard = Modifier.padding(bottom = keyboard)
            // Two boxes rather than one: the outer is the sheet itself, which is what is
            // dragged and what the design system sized, and the inner carries whatever
            // Modifiers the Host put on the node, which belong inside its surface.
            Box(
                Modifier
                    .then(tall)
                    .then(aboveKeyboard)
                    .testTag(sheetTestTag(node.id))
                    .modalOverlaySurface(node.id, surfaceRequester)
                    .then(sized)
                    .offset {
                        if (bottom) {
                            IntOffset(0, travel.roundToInt())
                        } else {
                            IntOffset(travel.roundToInt(), 0)
                        }
                    }
                    .then(raised)
                    .clip(shape)
                    .then(containerBg)
                    .then(outline)
                    .draggable(
                        orientation = if (bottom) Orientation.Vertical else Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Only travel towards the edge counts. Pulling the other way
                            // would drag the sheet off the edge it came from.
                            travel = (travel + delta).coerceAtLeast(0f)
                        },
                        onDragStopped = { if (travel >= threshold) close() else travel = 0f },
                    )
                    // A press inside the sheet is not a press on what it covers, so it is
                    // swallowed here rather than reaching the scrim behind.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier.fillMaxWidth().then(sheetPadding)) {
                    style.handle?.let { handle ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .width(if (bottom) HANDLE_LENGTH else HANDLE_THICKNESS)
                                    .height(if (bottom) HANDLE_THICKNESS else HANDLE_LENGTH)
                                    .clip(theme.shape(ShapeRole.Full))
                                    .background(handle),
                            )
                        }
                    }
                    node.children.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher) }
                    }
                }
            }
        }
    }
}

/** Test tag of the sheet's own surface, which is its own window, not part of the tree. */
fun sheetTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet"

/** Test tag of what the sheet covers, which is also the area it is measured against. */
fun sheetScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"
