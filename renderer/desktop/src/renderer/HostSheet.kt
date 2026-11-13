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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.layout
import dev.darkpyonix.composerust.ui.floatProp
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.SheetEdge
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalKeyboardInset
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

    val maxHeight = node.floatProp(PropertyKind.MaxHeightFraction)
        ?.takeIf { it > 0f && !it.isNaN() }
        ?.coerceAtMost(1f)

    val explicitSheetPad = theme.overlay?.sheetPadding
    val sheetPadding = if (node.setsOwnPadding()) {
        Modifier
    } else if (explicitSheetPad != null && explicitSheetPad > 0) {
        Modifier.padding(explicitSheetPad.dp)
    } else {
        Modifier.padding(style.padding)
    }

    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
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
            // Where the application gave the sheet a tallest height, the sheet is as tall as
            // its content up to that share of the window, and the content scrolls past it.
            // Otherwise the design system's own size applies on both edges.
            val window = remember(node.id) { WindowHeight() }
            val sized = when {
                maxHeight != null && bottom ->
                    Modifier.fillMaxWidth().atMostOfWindow(window, maxHeight)
                maxHeight != null -> Modifier.fillMaxWidth(style.widthFraction).atMostOfWindow(window, maxHeight)
                bottom -> Modifier.fillMaxWidth().fillMaxHeight(style.heightFraction)
                else -> Modifier.fillMaxHeight().fillMaxWidth(style.widthFraction)
            }
            val aboveKeyboard = Modifier.padding(bottom = keyboard)
            // Two boxes rather than one: the outer is the sheet itself, which is what is
            // dragged and what the design system sized, and the inner carries whatever
            // Modifiers the Host put on the node, which belong inside its surface.
            Box(
                Modifier
                    .then(if (maxHeight == null) Modifier else window.measured)
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
                    if (maxHeight == null) {
                        node.children.forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                    } else {
                        // The handle stays put and only what the application put in the
                        // sheet scrolls.
                        Column(
                            Modifier
                                .testTag(sheetContentTestTag(node.id))
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
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
}

/**
 * The height of the window a sheet is drawn in, read while the sheet is measured.
 *
 * [measured] sits outside the keyboard's padding, so the share is of the whole window
 * rather than of what the keyboard leaves. The sheet's own size is measured inside it in
 * the same pass, so a plain field is enough and no state changes between frames.
 */
private class WindowHeight {
    var px: Int = 0

    val measured: Modifier = Modifier.layout { measurable, constraints ->
        px = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** As tall as the content, and no taller than [fraction] of the window [window] measured. */
private fun Modifier.atMostOfWindow(window: WindowHeight, fraction: Float): Modifier =
    layout { measurable, constraints ->
        val cap = if (window.px > 0) (window.px * fraction).roundToInt() else constraints.maxHeight
        val placeable = measurable.measure(
            constraints.copy(minHeight = 0, maxHeight = cap.coerceAtMost(constraints.maxHeight)),
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** Test tag of the scrolling content of a sheet that has a tallest height. */
fun sheetContentTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet-content"

/** Test tag of the sheet's own surface, which is its own window, not part of the tree. */
fun sheetTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet"

/** Test tag of what the sheet covers, which is also the area it is measured against. */
fun sheetScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"
