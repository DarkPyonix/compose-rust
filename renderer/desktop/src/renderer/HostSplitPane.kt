package dev.darkpyonix.composerust.foundation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.SplitPanePresentation
import dev.darkpyonix.composerust.design.SplitPaneStyle
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.MotionRole
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.windowSizeClassOf
import dev.darkpyonix.composerust.ui.intProp
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.math.roundToInt

/** The strip that takes a drag between the two panes, and the keyboard focus. */
fun splitDividerTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-divider"

/** The side pane's own box, which is what its measured width is read from. */
fun splitSideTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-side"

/** The body's own box. */
fun splitBodyTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-body"

/** The back button the design system draws over a body shown on its own. */
fun splitBackTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-back"

/** What is laid over the body behind a side pane drawn over it. */
fun splitScrimTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-scrim"

/**
 * The platform's own way back: Android's back, filled in by the Android renderer.
 *
 * A hook, like the drop target and reduced motion, because only the platform that has a
 * back gesture can hear it, and a desktop has none. Where nobody installs one, the back
 * button the design system draws is the way back.
 */
var platformBackHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit =
    { _, _ -> }

/**
 * The pointer shape that says "drag me left or right", filled in by a platform that has
 * one. Null leaves the pointer as it is.
 */
var platformResizeCursor: PointerIcon? = null

/**
 * The window's collapsible split panes, so the platform's show and hide sidebar command
 * reaches one of them wherever focus is.
 *
 * The command is a window command on every platform, like the menu item it belongs to, so
 * it is heard at the root of the window rather than on the divider. A focused text field
 * still sees the key first and may keep it.
 *
 * Which pane answers when there are several: the innermost one that holds focus, and when
 * none holds focus, the outermost. The pane the reader is working in is the one they mean,
 * and with nothing focused the outermost is the window's own sidebar rather than a split
 * inside some pane of it.
 */
class SidebarShortcuts {
    private class Entry(val nodeId: Int, val depth: Int, var toggle: () -> Unit, var focused: Boolean)

    private val entries = mutableListOf<Entry>()

    internal fun register(nodeId: Int, depth: Int, toggle: () -> Unit) {
        entries.removeAll { it.nodeId == nodeId }
        entries += Entry(nodeId, depth, toggle, focused = false)
    }

    internal fun update(nodeId: Int, toggle: () -> Unit) {
        entries.firstOrNull { it.nodeId == nodeId }?.toggle = toggle
    }

    internal fun unregister(nodeId: Int) {
        entries.removeAll { it.nodeId == nodeId }
    }

    internal fun focus(nodeId: Int, focused: Boolean) {
        entries.firstOrNull { it.nodeId == nodeId }?.focused = focused
    }

    /** The node that would answer the command now, or null where no pane can. */
    fun target(): Int? = pick()?.nodeId

    private fun pick(): Entry? =
        entries.filter { it.focused }.maxByOrNull { it.depth }
            ?: entries.minByOrNull { it.depth }

    /** Answers the platform's sidebar command, if [event] is it and a pane can take it. */
    fun handle(event: KeyEvent, platform: HostPlatform): Boolean {
        if (!isSidebarShortcut(event, platform)) return false
        val entry = pick() ?: return false
        entry.toggle()
        return true
    }
}

/** The window's registry. Null outside a window, where there is nothing to command. */
val LocalSidebarShortcuts = staticCompositionLocalOf<SidebarShortcuts?> { null }

/** How many split panes this one sits inside. */
private val LocalSplitDepth = compositionLocalOf { 0 }

/** How much of a narrow place a side pane laid over the body may take. */
private const val OVERLAY_SHARE = 0.85f

/** How far in from the leading edge a back swipe has to begin. */
private val EDGE_SWIPE_ZONE = 20.dp

/** How far a back swipe has to travel before letting go goes back. */
private val EDGE_SWIPE_TRAVEL = 64.dp

/** The height of the strip the back button sits in. */
private val BACK_BAR_HEIGHT = 44.dp

/**
 * The side pane's width, as the Renderer holds it.
 *
 * [width] is the width the user or the Host chose, and it does not move when the window
 * narrows: a pane squeezed by a small window comes back to it when the window widens.
 * [live] is the width a drag or a held key is passing through, which nothing outside this
 * side hears about until it is let go. [reported] is the last width the Host knows, so the
 * same width is never reported twice.
 */
internal class SplitPaneState(width: Float, collapsed: Boolean, hostValue: Float?) {
    var width by mutableFloatStateOf(width)
    var collapsed by mutableStateOf(collapsed)
    var live by mutableStateOf<Float?>(null)
    var reported: Float = if (collapsed) 0f else width
    var lastHostValue: Float? = hostValue
}

/** What a screen reader calls the divider when the application did not name it. */
internal fun sidebarWord(language: String = Locale.current.language): String = when (language) {
    "ko" -> "사이드바"
    "ja" -> "サイドバー"
    "zh" -> "侧边栏"
    "de" -> "Seitenleiste"
    "fr" -> "Barre latérale"
    "es" -> "Barra lateral"
    "it" -> "Barra laterale"
    "pt" -> "Barra lateral"
    else -> "Sidebar"
}

/**
 * Whether a key press is the platform's own command for showing and hiding a sidebar:
 * Control Command S on Apple's systems, F9 in GNOME.
 */
internal fun isSidebarShortcut(event: KeyEvent, platform: HostPlatform): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (platform) {
        HostPlatform.MacOs, HostPlatform.Ios ->
            event.key == Key.S && event.isCtrlPressed && event.isMetaPressed
        HostPlatform.LinuxGnome -> event.key == Key.F9
        else -> false
    }
}

/**
 * A side pane and a body.
 *
 * The two children are kept as movable content, so whichever presentation the width calls
 * for, side by side, laid over or one at a time, the panes are the same compositions moved
 * to a new place. A field half typed in the side pane is still half typed after the window
 * is narrowed and widened again, and the Host sends nothing either way.
 *
 * **The drag does not cross the boundary.** The divider follows the pointer here, and the
 * width it is let go at is reported once, through the value change handler. A key held on
 * the divider is the same: it moves while held and is reported when released. Folding is
 * reported as zero.
 */
@Composable
internal fun HostSplitPane(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val panes = node.children.toList()
    if (panes.size != 2) {
        ReportPaneCount(node.id, panes.size, dispatcher)
        // Stacked, so nothing the Host sent is lost from the screen while it is wrong.
        Column(modifier) {
            panes.forEach { child -> key(child) { RenderNode(child, table, dispatcher) } }
        }
        return
    }
    val side = panes[0]
    val body = panes[1]
    val sideContent = remember(side, table, dispatcher) {
        movableContentOf { RenderNode(side, table, dispatcher, Modifier.fillMaxSize()) }
    }
    val bodyContent = remember(body, table, dispatcher) {
        movableContentOf { RenderNode(body, table, dispatcher, Modifier.fillMaxSize()) }
    }
    BoxWithConstraints(modifier) {
        val depth = LocalSplitDepth.current
        // The split pane's own width, not the window's. A split pane in the body of a wide
        // window's navigation can be narrow, and two columns forced into it would be wrong.
        val available = if (constraints.hasBoundedWidth) maxWidth.value else Float.MAX_VALUE
        val style = theme.rules.splitPane(windowSizeClassOf(available), theme)
        CompositionLocalProvider(LocalSplitDepth provides depth + 1) {
            SplitPaneLayout(node, style, available, sideContent, bodyContent, dispatcher, theme, depth)
        }
    }
}

/**
 * Says once that a split pane does not have two panes, and again only if the count changes.
 *
 * After composition and on the thread composition runs on, which is the thread the Host
 * was started on.
 */
@Composable
private fun ReportPaneCount(nodeId: Int, count: Int, dispatcher: EventDispatcher) {
    val said = remember(nodeId) { intArrayOf(-1) }
    SideEffect {
        if (said[0] == count) return@SideEffect
        said[0] = count
        dispatcher.dispatch(
            HostEvent.ProtocolError(
                nodeId = nodeId,
                handlerId = 0,
                code = TableError.INVALID_CHILDREN,
                message = "a split pane has exactly two children, the side pane and the " +
                    "body, and node $nodeId has $count; they are drawn one above the other",
            ),
        )
    }
}

@Composable
private fun SplitPaneLayout(
    node: Node,
    style: SplitPaneStyle,
    available: Float,
    sideContent: @Composable () -> Unit,
    bodyContent: @Composable () -> Unit,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    depth: Int,
) {
    val hostValue = node.number(PropertyKind.Value)
    val minWidth = node.number(PropertyKind.Min)?.takeIf { it > 0f } ?: style.minWidth.value
    val maxWidth = (node.number(PropertyKind.Max)?.takeIf { it > 0f } ?: style.maxWidth.value)
        .coerceAtLeast(minWidth)
    val collapsible = node.flag(PropertyKind.Collapsible, default = false)
    val state = remember(node.id) {
        SplitPaneState(
            width = hostValue?.takeIf { it > 0f } ?: style.defaultWidth.value,
            collapsed = collapsible && hostValue != null && hostValue <= 0f,
            hostValue = hostValue,
        )
    }
    // A width that came from outside: a sidebar toggle in the application, or a width it
    // restored from the last run. Not reported back, because the Host is where it came from.
    LaunchedEffect(node.id, hostValue) {
        if (hostValue == state.lastHostValue) return@LaunchedEffect
        state.lastHostValue = hostValue
        when {
            hostValue == null -> Unit
            hostValue <= 0f -> if (collapsible) state.collapsed = true
            else -> {
                state.collapsed = false
                state.width = hostValue
            }
        }
        if (hostValue != null) state.reported = if (hostValue <= 0f && collapsible) 0f else hostValue
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val line = style.lineWidth.value

    fun displayed(raw: Float): Float =
        if (collapsible && raw < minWidth - style.collapseDistance.value) 0f
        else raw.coerceIn(minWidth, maxWidth)

    fun report(value: Float) {
        if (value == state.reported) return
        state.reported = value
        val handler = node.handler(PropertyKind.OnValueChange) ?: return
        dispatcher.dispatch(HostEvent.ValueChanged(node.id, handler, value.toDouble()))
    }

    fun settle(value: Float) {
        if (value == 0f) {
            state.collapsed = true
        } else {
            state.collapsed = false
            state.width = value
        }
        state.live = null
    }

    fun finish() {
        val raw = state.live ?: return
        val value = displayed(raw)
        settle(value)
        report(value)
    }

    fun toggle() {
        if (!collapsible) return
        state.live = null
        state.collapsed = !state.collapsed
    }

    val chosen = state.live?.let(::displayed)
        ?: if (state.collapsed && collapsible) 0f else state.width.coerceIn(minWidth, maxWidth)

    // Two columns that leave the body narrower than it can be read at are not an answer in
    // any system, so the side pane goes over the body instead.
    val presentation = if (
        style.presentation == SplitPanePresentation.SideBySide &&
        minWidth + line + style.bodyMinWidth.value > available
    ) {
        SplitPanePresentation.Overlay
    } else {
        style.presentation
    }
    val shown = when (presentation) {
        // Narrowing the window takes the side pane down to its minimum before anything
        // else gives, without touching the width the user chose.
        SplitPanePresentation.SideBySide ->
            if (chosen == 0f) 0f
            else minOf(chosen, (available - line - style.bodyMinWidth.value).coerceAtLeast(minWidth))
        SplitPanePresentation.Overlay ->
            if (chosen == 0f) 0f else minOf(chosen, available * OVERLAY_SHARE)
        SplitPanePresentation.Stacked -> chosen
    }

    val name = node.text(PropertyKind.Text).ifEmpty { sidebarWord() }
    // The platform's show and hide sidebar command, heard at the window's root wherever
    // focus is. Only a pane that may fold registers.
    val shortcuts = LocalSidebarShortcuts.current
    val toggleAndReport = {
        toggle()
        report(if (state.collapsed) 0f else state.width)
    }
    DisposableEffect(node.id, shortcuts, collapsible) {
        if (collapsible) shortcuts?.register(node.id, depth, toggleAndReport)
        onDispose { shortcuts?.unregister(node.id) }
    }
    SideEffect { shortcuts?.update(node.id, toggleAndReport) }
    val shortcut = Modifier.onFocusChanged { shortcuts?.focus(node.id, it.hasFocus) }

    val divider: @Composable (Modifier) -> Unit = { placement ->
        SplitDivider(
            nodeId = node.id,
            style = style,
            shown = shown,
            minWidth = minWidth,
            maxWidth = maxWidth,
            collapsible = collapsible,
            name = name,
            modifier = placement,
            onDragStart = { state.live = shown },
            onDrag = { deltaPx ->
                val delta = deltaPx / density
                state.live = (state.live ?: shown) + if (rtl) -delta else delta
            },
            onDragStop = { finish() },
            onKey = { event ->
                val step = style.keyStep.value * if (rtl) -1f else 1f
                fun nudge(by: Float) {
                    val from = state.live ?: shown
                    state.live = (from + by).coerceIn(minWidth, maxWidth)
                }
                when (event.type) {
                    KeyEventType.KeyDown -> when (event.key) {
                        Key.DirectionLeft -> { nudge(-step); true }
                        Key.DirectionRight -> { nudge(step); true }
                        Key.MoveHome -> { state.live = minWidth; true }
                        Key.MoveEnd -> { state.live = maxWidth; true }
                        Key.Enter, Key.NumPadEnter -> if (collapsible) { toggle(); true } else false
                        else -> false
                    }
                    // Reported when the key comes up, once, however long it was held.
                    KeyEventType.KeyUp -> when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight, Key.MoveHome, Key.MoveEnd -> {
                            finish()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> if (collapsible) {
                            report(if (state.collapsed) 0f else state.width)
                            true
                        } else {
                            false
                        }
                        else -> false
                    }
                    else -> false
                }
            },
            onSet = { target ->
                val value = if (collapsible && target <= 0f) 0f else target.coerceIn(minWidth, maxWidth)
                settle(value)
                report(value)
            },
        )
    }

    when (presentation) {
        SplitPanePresentation.SideBySide -> Box(Modifier.fillMaxSize().then(shortcut)) {
            Row(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .testTag(splitSideTestTag(node.id))
                        .width(shown.dp)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(style.sideBackground),
                ) { sideContent() }
                if (line > 0f) {
                    Box(Modifier.width(style.lineWidth).fillMaxHeight().background(style.lineColor))
                }
                Box(Modifier.testTag(splitBodyTestTag(node.id)).weight(1f).fillMaxHeight()) {
                    bodyContent()
                }
            }
            divider(
                Modifier.offset(
                    x = (shown + line / 2f - style.grabWidth.value / 2f).coerceAtLeast(0f).dp,
                ),
            )
        }

        SplitPanePresentation.Overlay -> Box(Modifier.fillMaxSize().then(shortcut)) {
            Box(Modifier.testTag(splitBodyTestTag(node.id)).fillMaxSize()) { bodyContent() }
            if (shown > 0f && collapsible) {
                // Pressing what the side pane covers puts it away, and that is a fold like
                // any other: reported once, as zero.
                Box(
                    Modifier
                        .testTag(splitScrimTestTag(node.id))
                        .fillMaxSize()
                        .background(style.scrim)
                        .pointerInput(node.id) {
                            detectTapGestures {
                                settle(0f)
                                report(0f)
                            }
                        },
                )
            }
            val fill = if (style.sideBackground == Color.Transparent) {
                theme.color(ColorRole.Surface)
            } else {
                style.sideBackground
            }
            Box(
                Modifier
                    .testTag(splitSideTestTag(node.id))
                    .width(shown.dp)
                    .fillMaxHeight()
                    .shadow(if (shown > 0f) 8.dp else 0.dp)
                    .background(fill)
                    .clipToBounds(),
            ) { sideContent() }
            divider(Modifier.offset(x = (shown - style.grabWidth.value / 2f).coerceAtLeast(0f).dp))
        }

        SplitPanePresentation.Stacked -> {
            val hostSelected = (node.intProp(PropertyKind.SelectedIndex) ?: 0L).toInt()
            // Going back shows the side pane at once, without waiting for the Host, and the
            // Host is told once. When it answers with a selection of its own, that wins.
            var local by remember(node.id) { mutableStateOf<Int?>(null) }
            LaunchedEffect(node.id, hostSelected) { local = null }
            val selected = local ?: hostSelected
            val goBack = {
                if (selected == 1) {
                    local = 0
                    dismiss(node, dispatcher)
                }
            }
            platformBackHandler(selected == 1, goBack)
            AnimatedContent(
                targetState = selected == 1,
                modifier = Modifier.fillMaxSize().then(shortcut),
                transitionSpec = {
                    val forward = targetState
                    (
                        slideInHorizontally(theme.motion(MotionRole.Standard)) { width ->
                            if (forward == !rtl) width else -width
                        } + fadeIn(theme.motion(MotionRole.Standard))
                        ) togetherWith (
                        slideOutHorizontally(theme.motion(MotionRole.Standard)) { width ->
                            if (forward == !rtl) -width / 3 else width / 3
                        } + fadeOut(theme.motion(MotionRole.Standard))
                        )
                },
                label = "split pane",
            ) { showBody ->
                if (showBody) {
                    Column(Modifier.fillMaxSize()) {
                        if (style.backButton) {
                            Row(Modifier.fillMaxWidth().height(BACK_BAR_HEIGHT)) {
                                Box(
                                    Modifier
                                        .testTag(splitBackTestTag(node.id))
                                        .padding(theme.space(SpaceRole.Xs))
                                        .size(BACK_BAR_HEIGHT - 8.dp)
                                        .clip(theme.shape(ShapeRole.Full))
                                        .clickable(onClickLabel = "Back") { goBack() }
                                        .semantics { contentDescription = "Back" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    RoleIcon(IconRole.Back, theme.color(ColorRole.Primary), theme)
                                }
                            }
                        }
                        Box(
                            Modifier
                                .testTag(splitBodyTestTag(node.id))
                                .weight(1f)
                                .fillMaxWidth()
                                .then(
                                    if (style.edgeSwipeBack) {
                                        Modifier.edgeSwipeBack(rtl, density, goBack)
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) { bodyContent() }
                    }
                } else {
                    Box(Modifier.testTag(splitSideTestTag(node.id)).fillMaxSize()) { sideContent() }
                }
            }
        }
    }
}

/**
 * A swipe that starts at the leading edge and travels in far enough goes back, the way a
 * pushed screen is popped on Apple's systems.
 */
private fun Modifier.edgeSwipeBack(rtl: Boolean, density: Float, goBack: () -> Unit): Modifier =
    pointerInput(rtl) {
        var travel = 0f
        var fromEdge = false
        detectHorizontalDragGestures(
            onDragStart = { start ->
                val leading = if (rtl) size.width - start.x else start.x
                fromEdge = leading <= EDGE_SWIPE_ZONE.value * density
                travel = 0f
            },
            onDragEnd = {
                if (fromEdge && travel >= EDGE_SWIPE_TRAVEL.value * density) goBack()
                fromEdge = false
            },
            onDragCancel = { fromEdge = false },
        ) { _, amount ->
            if (fromEdge) travel += if (rtl) -amount else amount
        }
    }

/**
 * The divider: what is drawn, the strip that takes the drag, the keyboard focus, and what
 * a screen reader hears.
 *
 * To an assistive technology it is an adjustable control with a name, a current width and
 * a range, which is how a splitter is announced on every platform: "Sidebar, 280". Raising
 * and lowering it from there reports once, like a drag.
 */
@Composable
private fun SplitDivider(
    nodeId: Int,
    style: SplitPaneStyle,
    shown: Float,
    minWidth: Float,
    maxWidth: Float,
    collapsible: Boolean,
    name: String,
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStop: () -> Unit,
    onKey: (KeyEvent) -> Boolean,
    onSet: (Float) -> Unit,
) {
    var focused by remember(nodeId) { mutableStateOf(false) }
    val floor = if (collapsible) 0f else minWidth
    val cursor = platformResizeCursor
    Box(
        modifier
            .testTag(splitDividerTestTag(nodeId))
            .width(style.grabWidth)
            .fillMaxHeight()
            .then(if (style.resizeCursor && cursor != null) Modifier.pointerHoverIcon(cursor) else Modifier)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStarted = { onDragStart() },
                onDragStopped = { onDragStop() },
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent(onKey)
            .focusable()
            .semantics {
                contentDescription = name
                stateDescription = shown.roundToInt().toString()
                progressBarRangeInfo = ProgressBarRangeInfo(shown.coerceIn(floor, maxWidth), floor..maxWidth)
                setProgress { target ->
                    onSet(target)
                    true
                }
            }
            .then(
                if (focused) Modifier.border(2.dp, style.focusRing, RectangleShape) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The line itself is drawn by the layout between the panes. What is drawn here is
        // the grip, where the system has one.
        style.handle?.let { handle ->
            Box(
                Modifier
                    .size(width = handle.thickness, height = handle.length)
                    .clip(handle.shape)
                    .background(handle.color),
            )
        }
    }
}
