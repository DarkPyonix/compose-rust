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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
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
import dev.darkpyonix.composerust.protocol.SheetPresentation
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalSystemBars
import dev.darkpyonix.composerust.runtime.LocalWindowSizeClass
import dev.darkpyonix.composerust.runtime.platformKeyboardInset
import dev.darkpyonix.composerust.ui.intProp
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.sheetPresentation
import kotlin.math.roundToInt

/** How far a sheet has to be pulled towards its edge before letting go closes it. */
private val DISMISS_TRAVEL = 96.dp

/** The grab handle's size, where the design system draws one. */
private val HANDLE_LENGTH = 32.dp
private val HANDLE_THICKNESS = 4.dp

/**
 * The window a sheet or a dialog is drawn in: it covers the application's whole window, and
 * the overlay's scrim fills it.
 *
 * [onDismissRequest] is what the window calls when it is asked to close by something other
 * than Back, and [onBack] is the overlay's answer to Back. Everything inside is [content].
 *
 * The default is a popup the size of the window, placed at its corner. A hook because that
 * is not true everywhere: Android measures a popup against the parent window's visible
 * frame, which leaves out the gesture bar and the keyboard, so the scrim stopped short of
 * the bottom of the screen and, with the keyboard up, the whole overlay was only as tall as
 * the space above it. Android installs a full screen window of its own instead.
 */
var platformModalOverlayWindow: @Composable (
    onDismissRequest: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) -> Unit = { onDismissRequest, _, content ->
    Popup(
        popupPositionProvider = ModalOverlayPositionProvider,
        properties = ModalOverlayProperties,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

/**
 * [platformModalOverlayWindow] with the window's page offered to what is inside as its
 * backdrop: a sheet or a dialog covers the page, so the page is what is behind it.
 */
@Composable
internal fun ModalOverlayWindow(
    onDismissRequest: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val page = LocalWindowBackdrop.current
    platformModalOverlayWindow(onDismissRequest, onBack) {
        CompositionLocalProvider(LocalAppBackdrop provides page, content = content)
    }
}

/**
 * Where the default overlay popup goes: at the corner of the application's window, whatever
 * node opened it.
 *
 * The overlay's scrim covers the whole window, so its window has to start where the
 * application's does. Centring it on the node that opened it is only the same thing while
 * the popup is exactly as big as the window, and a popup that came out shorter (as Android's
 * did) then started part way down and left the top of the window undimmed.
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
 * The default overlay popup's properties: focusable, so the fields inside take the keyboard,
 * and Back left to the renderer (see [ModalOverlayFocus]) rather than closing the popup
 * behind its back.
 */
internal val ModalOverlayProperties = PopupProperties(
    focusable = true,
    dismissOnBackPress = false,
)

/**
 * How much of the overlay's own window the keyboard covers.
 *
 * Read inside the overlay's window, not taken from the page's. The two are the same where
 * the overlay is as big as the application's window, which is what every platform aims
 * for. Where it is not, the page's figure is wrong for the overlay: Android once gave the
 * overlay a window that already ended at the keyboard, and lifting the sheet by the page's
 * keyboard inset on top of that collapsed it to its drag handle.
 */
@Composable
internal fun overlayKeyboardInset(): Dp = platformKeyboardInset().coerceAtLeast(0.dp)

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

/** The bits of a sheet's `SheetDetents` property: the heights it may rest at. */
internal const val SHEET_DETENT_MEDIUM = 1
internal const val SHEET_DETENT_LARGE = 2
internal const val SHEET_DETENT_MASK = SHEET_DETENT_MEDIUM or SHEET_DETENT_LARGE

/**
 * How tall a bottom sheet is at one of its resting heights, in a window [window] tall:
 * half the window at the medium one, and at the large one the window less the top safe
 * area ([top]) and the room the design system keeps above the sheet ([margin]).
 */
internal fun sheetDetentHeight(large: Boolean, window: Dp, top: Dp, margin: Dp): Dp =
    if (large) (window - top - margin).coerceAtLeast(0.dp) else window / 2

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
 *
 * A bottom sheet that was given resting heights (`SheetDetents`) opens at the lowest of
 * them and is pulled between them. Which one it rests at is state here too, and moving
 * between them says nothing to the Host. With `SheetDragToDismiss` false the pull stops at
 * the lowest resting height and never closes the sheet; Escape, the platform's back and a
 * press outside still ask to. Neither property means anything to a centered sheet or to
 * one that comes from the side, which is not an error: the same declaration changes form
 * with the window.
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
    val presentation = node.sheetPresentation()
    val sizeClass = LocalWindowSizeClass.current
    val isCompact = sizeClass == WindowSizeClass.Compact
    val isCentered = presentation == SheetPresentation.Centered && !isCompact
    val maxWidthVal = node.intProp(PropertyKind.MaxWidth)
    val maxWidthDp = if (maxWidthVal != null && maxWidthVal > 0L) maxWidthVal.toInt().dp else 560.dp
    val style = theme.rules.sheet(sizeClass, theme)
    val bottom = if (isCentered) false else (if (presentation == SheetPresentation.Centered) true else style.edge == SheetEdge.Bottom)
    val threshold = with(LocalDensity.current) { DISMISS_TRAVEL.toPx() }
    var travel by remember(node.id) { mutableFloatStateOf(0f) }
    val close = {
        travel = 0f
        open.set(false)
        dismiss(node, dispatcher)
    }
    // The resting heights and whether a pull can close the sheet, for a sheet along the
    // bottom edge only.
    val edgeBottom = bottom && !isCentered
    val detents = if (edgeBottom) (node.intProp(PropertyKind.SheetDetents)?.toInt() ?: 0) and SHEET_DETENT_MASK else 0
    val bothDetents = detents == SHEET_DETENT_MASK
    val dragToDismiss = !edgeBottom || node.flag(PropertyKind.SheetDragToDismiss, default = true)
    // Which resting height the sheet is at. It opens at the lowest it was given.
    var large by remember(node.id, detents) { mutableStateOf(detents == SHEET_DETENT_LARGE) }
    // How far apart the two resting heights are, in pixels, as the last layout measured it.
    val detentSpan = remember(node.id) { FloatArray(1) }
    val settleScope = rememberCoroutineScope()
    // The run that brings the sheet back to rest, so a new pull can take over from it.
    val settling = remember(node.id) { arrayOfNulls<Job>(1) }
    // The pull, in pixels: down is positive and moves the sheet, up is negative and grows
    // a sheet that has a taller resting height to go to.
    val pull: (Float) -> Unit = { delta ->
        settling[0]?.cancel()
        val highest = if (bothDetents && !large) -detentSpan[0] else 0f
        val lowest = if (!dragToDismiss && !(bothDetents && large)) 0f else Float.POSITIVE_INFINITY
        travel = (travel + delta).coerceIn(highest, lowest)
    }
    // Moves to the other resting height from where the pull left the sheet, without a jump:
    // the travel is restated against the new height and then let go.
    val settle: (Boolean?) -> Unit = { toLarge ->
        if (toLarge != null && toLarge != large) {
            travel += if (toLarge) detentSpan[0] else -detentSpan[0]
            large = toLarge
        }
        settling[0]?.cancel()
        settling[0] = settleScope.launch {
            animate(travel, 0f) { value, _ -> travel = value }
        }
    }
    val release: () -> Unit = {
        when {
            bothDetents && !large && travel <= -threshold -> settle(true)
            bothDetents && large && travel >= threshold -> settle(false)
            travel >= threshold && dragToDismiss -> close()
            detents != 0 || !dragToDismiss -> settle(null)
            else -> travel = 0f
        }
    }
    // A pull that starts on the sheet's scrolling content: what the content does not use
    // moves the sheet, and a sheet that is off its resting height comes back before the
    // content scrolls. Only a sheet with resting heights listens.
    val contentPull = remember(node.id) {
        object : NestedScrollConnection {
            var pull: (Float) -> Unit = {}
            var release: () -> Unit = {}
            var displaced: () -> Boolean = { false }
            var moved: () -> Float = { 0f }

            private fun take(delta: Float): Offset {
                val before = moved()
                pull(delta)
                return Offset(0f, moved() - before)
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && displaced()) take(available.y) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput) take(available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!displaced()) return Velocity.Zero
                release()
                return available
            }
        }
    }
    contentPull.pull = pull
    contentPull.release = release
    contentPull.displaced = { travel != 0f }
    contentPull.moved = { travel }
    val surfaceRequester = remember(node.id) { FocusRequester() }
    val focusRequesters = remember(node.id) { mutableMapOf<Int, FocusRequester>() }
    var focusedChildId by remember(node.id) { mutableStateOf<Int?>(null) }
    val onEscape = { dismiss(node, dispatcher) }

    val defaultScrim = if (presentation == SheetPresentation.Centered) {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)
    } else {
        style.scrim
    }

    val scrim = node.paintProp(PropertyKind.Scrim)?.let { theme.color(it) }
        ?: theme.overlay?.scrim?.takeIf { it != 0L }?.let { androidx.compose.ui.graphics.Color(it) }
        ?: defaultScrim

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
        } ?: if (presentation == SheetPresentation.Centered) {
            if (bottom) {
                androidx.compose.foundation.shape.RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp)
            } else {
                androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            }
        } else {
            style.shape
        }
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
    val contentPadding = if (explicitSheetPad != null && explicitSheetPad > 0) explicitSheetPad.dp else style.padding
    // The application can turn the handle off; the drag is the surface's, so the sheet drags
    // the same either way.
    val handle = style.handle?.takeIf { !isCentered && node.flag(PropertyKind.SheetHandle, default = true) }
    val ownPadding = node.setsOwnPadding()
    val sheetPadding = when {
        ownPadding -> Modifier
        // The space above the handle is the handle's own, so the sheet's top padding is not
        // stacked on it as well.
        handle != null -> Modifier.padding(start = contentPadding, end = contentPadding, bottom = contentPadding)
        else -> Modifier.padding(contentPadding)
    }
    val handleGap = style.handleGap ?: contentPadding

    ModalOverlayWindow(close, onEscape) {
        val focusManager = LocalFocusManager.current
        val menuEntries = menuEntriesIn(node, node.children, table)
        val menuKeys = if (menuEntries != null) {
            rememberMenuKeyboard(menuEntries, table)
        } else {
            null
        }
        ModalOverlayFocus(node.id, surfaceRequester, onEscape, focusSurface = menuKeys == null)
        // The scrim covers the whole window, keyboard or not. Only the sheet moves up, so
        // the field inside it is above the keyboard.
        val keyboard = overlayKeyboardInset()
        val exposed = LocalOverlayFocusStack.current.exposesOverlay(node.id)
        CompositionLocalProvider(LocalMenuKeyboard provides menuKeys) {
        Box(
            Modifier
                // A modal opened over this one hides it, as this one hides the page.
                .behindModal(!exposed)
                .testTag(sheetScrimTestTag(node.id))
                .fillMaxSize()
                // A scrim that dims the window dims a platform view too: it is covered.
                .nativeViewOverlay(coversWindow = scrim.alpha > 0f)
                .background(scrim)
                // Pressing what the sheet covers is the other way of asking to close it,
                // and it gets the same answer: one dismissal. A raw tap detector rather
                // than `clickable`, because a scrim is not a button and saying it is one
                // puts it in the accessibility tree and merges everything under it.
                .pointerInput(Unit) { detectTapGestures { close() } },
            contentAlignment = when {
                isCentered -> Alignment.Center
                bottom -> Alignment.BottomCenter
                else -> Alignment.CenterEnd
            },
        ) {
            val top = LocalSystemBars.current.top
            // A sheet along the bottom keeps its content clear of the system's strip there
            // (the gesture handle or the navigation bar), by the larger of that strip and
            // the keyboard. The keyboard already lifts the whole surface, and while it is
            // up the strip is under it and reads 0, so what is left to pad inside the
            // surface is only the part of the strip the keyboard does not already clear.
            // The surface itself still runs to the bottom edge.
            val clearBottom = if (bottom && !isCentered) {
                (LocalSystemBars.current.bottom - keyboard).coerceAtLeast(0.dp)
            } else {
                0.dp
            }
            // A sheet along the bottom is given its height here, against the whole window
            // and before the keyboard is taken off, so the keyboard moves it rather than
            // shrinking it. The keyboard's strip is part of what is measured here and is
            // left empty by the padding below.
            //
            // Where the application gave the sheet a tallest height, that share of the
            // window is a ceiling rather than a height: the sheet is as tall as its content
            // up to it, and the content scrolls past it. The keyboard still moves it rather
            // than shrinking it, on either edge.
            //
            // A centered sheet is sized to its target width (up to available window width
            // minus margins) and its content height up to available window height minus margins.
            val tall = when {
                isCentered -> Modifier.layout { measurable, constraints ->
                    if (!constraints.hasBoundedHeight || !constraints.hasBoundedWidth) {
                        val placeable = measurable.measure(constraints)
                        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    val maxAvailWidth = (constraints.maxWidth.toDp() - 32.dp).coerceAtLeast(0.dp)
                    val targetWidth = maxWidthDp.coerceAtMost(maxAvailWidth).roundToPx()
                    val maxAvailHeight = (constraints.maxHeight.toDp() - 32.dp).coerceAtLeast(0.dp)
                    val maxHeightCap = if (maxHeight != null) {
                        minOf(maxAvailHeight, constraints.maxHeight.toDp() * maxHeight)
                    } else {
                        maxAvailHeight
                    }.roundToPx()

                    val childConstraints = constraints.copy(
                        minWidth = targetWidth,
                        maxWidth = targetWidth,
                        minHeight = 0,
                        maxHeight = maxHeightCap,
                    )
                    val placeable = measurable.measure(childConstraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                bottom || maxHeight != null -> Modifier.layout { measurable, constraints ->
                    if (!constraints.hasBoundedHeight) {
                        val placeable = measurable.measure(constraints)
                        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    val window = constraints.maxHeight.toDp()
                    val sheet = when {
                        detents != 0 -> {
                            val medium = sheetDetentHeight(false, window, top, style.largeDetentMargin)
                            val tallest = sheetDetentHeight(true, window, top, style.largeDetentMargin)
                            detentSpan[0] = (tallest - medium).toPx().coerceAtLeast(0f)
                            // A pull upwards grows the sheet towards its taller resting height.
                            val resting = (if (large) tallest else medium) + (-travel).coerceAtLeast(0f).toDp()
                            if (keyboard > 0.dp) minOf(resting, window - keyboard - top).coerceAtLeast(0.dp) else resting
                        }
                        bottom -> bottomSheetHeight(window, maxHeight ?: style.heightFraction, keyboard, top)
                        else -> window * maxHeight!!
                    }
                    val height = (sheet + keyboard)
                        .roundToPx()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    val placeable = measurable.measure(
                        if (maxHeight == null || detents != 0) {
                            constraints.copy(minHeight = height, maxHeight = height)
                        } else {
                            constraints.copy(minHeight = 0, maxHeight = height)
                        },
                    )
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                else -> Modifier
            }
            val sized = when {
                isCentered -> Modifier
                maxHeight != null && bottom -> Modifier.fillMaxWidth()
                maxHeight != null -> Modifier.fillMaxWidth(style.widthFraction)
                bottom -> Modifier.fillMaxWidth().fillMaxHeight()
                else -> Modifier.fillMaxHeight().fillMaxWidth(style.widthFraction)
            }
            val aboveKeyboard = if (isCentered) Modifier else Modifier.padding(bottom = keyboard)
            val focusTrap = if (isCentered) {
                Modifier.onPreviewKeyEvent { event ->
                    if (event.key == Key.Tab) {
                        if (event.type == KeyEventType.KeyDown) {
                            val children = node.children
                            if (children.isNotEmpty()) {
                                val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                                val at = focusedChildId?.let { children.indexOf(it) } ?: -1
                                if (direction == FocusDirection.Next) {
                                    if (at >= 0 && at == children.size - 1) {
                                        val first = children.first()
                                        focusRequesters[first]?.requestFocus()
                                        return@onPreviewKeyEvent true
                                    }
                                } else {
                                    if (at == 0) {
                                        val last = children.last()
                                        focusRequesters[last]?.requestFocus()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                if (!focusManager.moveFocus(direction)) {
                                    val wrap = if (direction == FocusDirection.Next) children.first() else children.last()
                                    focusRequesters[wrap]?.requestFocus()
                                }
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
            } else {
                Modifier
            }
            val dragModifier = if (!isCentered) {
                Modifier
                    .offset {
                        if (bottom) {
                            // An upward pull grows the sheet where it is measured, and
                            // does not lift it off the bottom edge.
                            IntOffset(0, travel.coerceAtLeast(0f).roundToInt())
                        } else {
                            IntOffset(travel.roundToInt(), 0)
                        }
                    }
                    .draggable(
                        orientation = if (bottom) Orientation.Vertical else Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Only travel towards the edge counts. Pulling the other way
                            // would drag the sheet off the edge it came from, unless it
                            // has a taller resting height to grow to.
                            pull(delta)
                        },
                        onDragStopped = { release() },
                    )
                    .let { if (detents != 0) it.nestedScroll(contentPull) else it }
            } else {
                Modifier
            }
            // Two boxes rather than one: the outer is the sheet itself, which is what is
            // dragged and what the design system sized, and the inner carries whatever
            // Modifiers the Host put on the node, which belong inside its surface.
            Box(
                Modifier
                    .then(tall)
                    .then(aboveKeyboard)
                    .testTag(sheetTestTag(node.id))
                    .then(focusTrap)
                    .let { if (menuKeys != null) it.menuKeys(menuKeys) else it }
                    .modalOverlaySurface(node.id, surfaceRequester)
                    .then(sized)
                    .then(dragModifier)
                    // The hole a native view under the sheet leaves for it follows the
                    // drag, so it is placed after the offset and before the shadow.
                    .nativeViewOverlay(shape, shadowReach(sheetElevation))
                    .then(raised)
                    .clip(shape)
                    .then(containerBg)
                    .then(outline)
                    // A press inside the sheet is not a press on what it covers, so it is
                    // swallowed here rather than reaching the scrim behind.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier.fillMaxWidth().then(sheetPadding).padding(bottom = clearBottom)) {
                    // The content starts a gap below the handle rather than level with
                    // it. Padding the Host set itself already holds the space above.
                    if (handle != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = if (ownPadding) 0.dp else handleGap, bottom = handleGap),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .testTag(sheetHandleTestTag(node.id))
                                    .width(if (bottom) HANDLE_LENGTH else HANDLE_THICKNESS)
                                    .height(if (bottom) HANDLE_THICKNESS else HANDLE_LENGTH)
                                    .clip(theme.shape(ShapeRole.Full))
                                    .background(handle),
                            )
                        }
                    }
                    if (maxHeight == null && !isCentered && detents == 0) {
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
                                val requester = focusRequesters.getOrPut(childId) { FocusRequester() }
                                val focusModifier = if (isCentered) {
                                    Modifier
                                        .focusRequester(requester)
                                        .onFocusChanged { if (it.hasFocus) focusedChildId = childId }
                                } else {
                                    Modifier
                                }
                                key(childId) { RenderNode(childId, table, dispatcher, parentModifier = focusModifier) }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** Test tag of the scrolling content of a sheet that has a tallest height. */
fun sheetContentTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet-content"

/** Test tag of a bottom or side sheet's drag handle, where one is drawn. */
fun sheetHandleTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet-handle"

/** Test tag of the sheet's own surface, which is its own window, not part of the tree. */
fun sheetTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-sheet"

/** Test tag of what the sheet covers, which is also the area it is measured against. */
fun sheetScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"
