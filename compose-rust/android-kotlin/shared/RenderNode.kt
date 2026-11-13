package dev.darkpyonix.composerust.ui.node

import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import dev.darkpyonix.composerust.foundation.HostScroll
import dev.darkpyonix.composerust.foundation.OffsetScrollSurface
import dev.darkpyonix.composerust.foundation.axis
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.runtime.LocalWindowNodeId
import dev.darkpyonix.composerust.runtime.WindowDropTargets
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.islandDensity
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.design.ContainerRole
import androidx.compose.ui.graphics.Shape
import dev.darkpyonix.composerust.foundation.containerShape
import dev.darkpyonix.composerust.ui.accessibility
import dev.darkpyonix.composerust.ui.offersFocusAction
import dev.darkpyonix.composerust.ui.pressable
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.design.ToggleRole
import dev.darkpyonix.composerust.design.LocalDesignTheme
import dev.darkpyonix.composerust.design.LocalGlassDepth
import dev.darkpyonix.composerust.design.SurfaceMaterial
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.foundation.FloatingActionArea
import dev.darkpyonix.composerust.foundation.HostBadge
import dev.darkpyonix.composerust.foundation.HostButton
import dev.darkpyonix.composerust.foundation.HostChip
import dev.darkpyonix.composerust.foundation.HostCodeEditor
import dev.darkpyonix.composerust.foundation.HostFloatingAction
import dev.darkpyonix.composerust.foundation.HostLazyGrid
import dev.darkpyonix.composerust.foundation.HostRichText
import dev.darkpyonix.composerust.foundation.HostScaffold
import dev.darkpyonix.composerust.foundation.HostContainerColumn
import dev.darkpyonix.composerust.foundation.HostDialog
import dev.darkpyonix.composerust.foundation.HostDivider
import dev.darkpyonix.composerust.foundation.HostProgressIndicator
import dev.darkpyonix.composerust.foundation.HostSlider
import dev.darkpyonix.composerust.foundation.HostSplitPane
import dev.darkpyonix.composerust.foundation.HostToggle
import dev.darkpyonix.composerust.foundation.HostCanvas
import dev.darkpyonix.composerust.foundation.HostDatePicker
import dev.darkpyonix.composerust.foundation.HostDropdown
import dev.darkpyonix.composerust.foundation.HostIcon
import dev.darkpyonix.composerust.foundation.HostImage
import dev.darkpyonix.composerust.foundation.HostTimePicker
import dev.darkpyonix.composerust.foundation.HostLazyColumn
import dev.darkpyonix.composerust.foundation.HostLazyRow
import dev.darkpyonix.composerust.foundation.HostMenu
import dev.darkpyonix.composerust.foundation.HostPopover
import dev.darkpyonix.composerust.foundation.HostNavigation
import dev.darkpyonix.composerust.foundation.HostNavigationItem
import dev.darkpyonix.composerust.foundation.HostSheet
import dev.darkpyonix.composerust.foundation.HostTabs
import dev.darkpyonix.composerust.foundation.HostTooltip
import dev.darkpyonix.composerust.foundation.HostTopAppBar
import dev.darkpyonix.composerust.foundation.HostTextField
import dev.darkpyonix.composerust.foundation.SelectableRegion
import dev.darkpyonix.composerust.foundation.Unselectable
import dev.darkpyonix.composerust.foundation.hostKeyEvents
import dev.darkpyonix.composerust.foundation.hostPointerEvents
import dev.darkpyonix.composerust.foundation.subscribesToPointer
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.floatProp
import dev.darkpyonix.composerust.ui.flowHorizontalArrangement
import dev.darkpyonix.composerust.ui.flowItemVerticalAlignment
import dev.darkpyonix.composerust.ui.flowMaxItemsInEachRow
import dev.darkpyonix.composerust.ui.flowVerticalArrangement
import dev.darkpyonix.composerust.ui.boxAlignment
import dev.darkpyonix.composerust.ui.horizontalAlignment
import dev.darkpyonix.composerust.ui.horizontalArrangement
import dev.darkpyonix.composerust.ui.maxLines
import dev.darkpyonix.composerust.ui.overflow
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.ui.toComposeModifier
import dev.darkpyonix.composerust.ui.verticalAlignment
import dev.darkpyonix.composerust.ui.verticalArrangement
import dev.darkpyonix.composerust.ui.weightOf

/**
 * Counts recompositions of interpreted nodes, so a test can assert that changing one node
 * does not recompose its siblings. Set only by tests.
 */
internal object RenderNodeObserver {
    var onCompose: ((Int) -> Unit)? = null

    /**
     * Counts measurements of a box that places its children where the Host put them, so a
     * test can assert that redrawing a node, moving it through a layer or animating it does
     * not measure it again. Set only by tests.
     */
    var onMeasure: ((Int) -> Unit)? = null
}

/** The containers whose `OnClick` makes them buttons. */
private val CLICKABLE_CONTAINERS = setOf(
    WidgetKind.Column,
    WidgetKind.Row,
    WidgetKind.Box,
    WidgetKind.Card,
    WidgetKind.Surface,
    WidgetKind.ScrollColumn,
    WidgetKind.ScrollRow,
)

/** The outline a pressable container is drawn in, which hover and focus follow. */
private fun Node.pressedShape(theme: ResolvedTheme): Shape = when (widget) {
    WidgetKind.Card -> containerShape(theme.rules.container(ContainerRole.Card, theme), theme)
    WidgetKind.Surface -> containerShape(theme.rules.container(ContainerRole.Surface, theme), theme)
    else -> modifiers.resolvedShape(theme)
}

/** Test tag every interpreted node carries, so tests can address nodes by Host node id. */
fun nodeTestTag(nodeId: Int): String = "compose-node-$nodeId"

/**
 * Interprets one node of the table into real Compose widgets.
 *
 * Only the node's own state is read here, so a `SetProp` on a sibling cannot invalidate this
 * composable.
 *
 * `parentModifier` carries what only the parent layout can express, which today is the
 * `Weight` modifier: weight is parent data of a `Column` or `Row` scope and cannot be
 * produced by a modifier chain built outside that scope.
 */
@Composable
fun RenderNode(
    nodeId: Int,
    table: NodeTable,
    dispatcher: EventDispatcher,
    parentModifier: Modifier = Modifier,
) {
    val node = table.node(nodeId) ?: return
    // Observation point for the recomposition test; null in production.
    RenderNodeObserver.onCompose?.let { observer -> SideEffect { observer(nodeId) } }
    val theme = LocalDesignTheme.current
    val profiled = if (LayoutProfile.enabled) parentModifier.then(LayoutProfile.modifier(node.widget.name)) else parentModifier
    // A container that was given an `OnClick` is a button. One that was not is left
    // exactly as it was: no focus stop, no semantics, no pointer handling.
    // A node that also carries the `Clickable` modifier is already pressable there, once.
    val clickHandler = if (node.widget in CLICKABLE_CONTAINERS &&
        node.modifiers.none { it is dev.darkpyonix.composerust.protocol.Modifier.Clickable }
    ) {
        node.handler(PropertyKind.OnClick)
    } else {
        null
    }
    // The key handlers go ahead of the node's own modifiers. A modifier that makes the node
    // focusable (a click, say) is a focus target, and a key event reaches only the handlers
    // outside it on the way down. Placed after it, a handler would see keys from focused
    // descendants only and never the keys pressed while the node itself has focus.
    // The TextField wires its own key handling, because it has an editor to intercept and a
    // composition to protect; everything else routes keys here.
    val keyDownHandler = node.handler(PropertyKind.OnKeyDown)
    val keyUpHandler = node.handler(PropertyKind.OnKeyUp)
    val listensForKeys = (keyDownHandler != null || keyUpHandler != null) &&
        node.widget != WidgetKind.TextField
    val keyed = if (listensForKeys) {
        profiled.hostKeyEvents(nodeId, keyDownHandler, keyUpHandler, dispatcher)
    } else {
        profiled
    }
    // Plain containers and text are not focus targets on their own, so a node that
    // listens for keys is made one, ahead of its modifiers like the handlers. Without it
    // Tab never reaches the node and a DOM element with tabindex and onkeydown is dead.
    // Widgets that are controls bring their own focus handling and are left alone.
    val keyFocusTarget = listensForKeys && node.widget in KeyFocusTargets
    // A node that subscribed to pointer events listens for them. One that did not carries
    // nothing for them at all.
    val pointerEvents = if (node.subscribesToPointer()) {
        hostPointerEvents(node, table.pointers, dispatcher)
    } else {
        null
    }
    // The requester a focus request for this node is made through. It exists from the first
    // request on, and from the start for a node that offers a focus action to assistive
    // technology, so such a node can already take focus when a reader asks. TextField and
    // CodeEditor own their requesters and read the request themselves.
    val focusRequest = node.focusRequestRevision
    val focusRequester = if ((focusRequest > 0L || node.offersFocusAction()) &&
        node.widget != WidgetKind.TextField &&
        node.widget != WidgetKind.CodeEditor
    ) {
        remember(nodeId) { FocusRequester() }
    } else {
        null
    }
    if (focusRequester != null) {
        LaunchedEffect(focusRequest) {
            if (focusRequest > 0L) focusRequester.requestFocus()
        }
    }
    // Ahead of the node's own modifiers. A requester moves focus to the first focus target
    // after it, and a node that can be pressed has that target among its modifiers: placed
    // after them, the requester missed the node and reached for its children instead.
    // It is also ahead of the focus target a key listener is given, and behind the key
    // handlers. That target is then the one a request focuses, so the node keeps a single
    // focus target that reports it is focused and whose keys pass the node's handlers.
    val requested = (if (focusRequester == null) keyed else keyed.focusRequester(focusRequester))
        .let { if (keyFocusTarget) it.focusable() else it }
    // A popover's material is what its floating box is made of, and the popover draws it
    // in that box's own corner. Left in the chain it would be drawn a second time, square.
    val ownModifiers: List<dev.darkpyonix.composerust.protocol.Modifier> =
        if (node.widget == WidgetKind.Popover) {
            node.modifiers.filterNot { it is dev.darkpyonix.composerust.protocol.Modifier.Material }
        } else {
            node.modifiers
        }
    val chain = requested
        .then(
            ownModifiers.toComposeModifier(
                nodeId,
                dispatcher,
                theme,
                animated = { table.animations.animated(nodeId) },
                containerClickHandler = clickHandler,
                containerEnabled = node.flag(PropertyKind.Enabled, default = true),
                containerShape = if (clickHandler != null) node.pressedShape(theme) else null,
                pressColor = node.paintProp(PropertyKind.Color)?.let { theme.color(it) } ?: theme.color(ColorRole.OnSurface),
                allowPress = node.widget != WidgetKind.TextField,
                allowPadding = node.widget != WidgetKind.TextField,
                pointerEvents = pointerEvents,
            ),
        )
        .testTag(nodeTestTag(nodeId))
        .accessibility(
            node,
            dispatcher,
            clickHandler
                ?: (node.modifiers.lastOrNull { it is dev.darkpyonix.composerust.protocol.Modifier.Clickable }
                    as? dev.darkpyonix.composerust.protocol.Modifier.Clickable)?.handlerId
                ?: node.handler(PropertyKind.OnClick),
            onFocusAction = { table.requestNodeFocus(nodeId) },
        )
    // Focus-gained and focus-lost for non-TextField nodes: `hasFocus` covers both the node
    // itself and any focusable descendant inside it.
    val focusGainedHandler = node.handler(PropertyKind.OnFocusGained)
    val focusLostHandler = node.handler(PropertyKind.OnFocusLost)
    var hasFocusWithin by remember(nodeId) { mutableStateOf(false) }
    val withFocusEvents = if ((focusGainedHandler == null && focusLostHandler == null) ||
        node.widget == WidgetKind.TextField
    ) {
        chain
    } else {
        chain.onFocusChanged { state ->
            val nowFocused = state.hasFocus
            if (!hasFocusWithin && nowFocused && focusGainedHandler != null) {
                dispatcher.dispatch(HostEvent.FocusGained(nodeId, focusGainedHandler))
            }
            if (hasFocusWithin && !nowFocused && focusLostHandler != null) {
                dispatcher.dispatch(HostEvent.FocusLost(nodeId, focusLostHandler))
            }
            hasFocusWithin = nowFocused
        }
    }
    // A container nothing else made a focus target becomes one here, so the requester above
    // has somewhere to send focus.
    val focusableModifier = if (focusRequester != null &&
        !keyFocusTarget &&
        clickHandler == null &&
        node.widget in CLICKABLE_CONTAINERS &&
        node.modifiers.none { it is dev.darkpyonix.composerust.protocol.Modifier.Clickable }
    ) {
        withFocusEvents.focusable()
    } else {
        withFocusEvents
    }
    val windowId = LocalWindowNodeId.current
    val subscribed = windowId != 0 && node.handler(PropertyKind.OnWindowDragOver) != null
    val density = LocalDensity.current.density
    if (subscribed) {
        DisposableEffect(windowId, nodeId) {
            onDispose { WindowDropTargets.remove(windowId, nodeId) }
        }
    }
    var depth = 0
    var ancestor = node.parentId
    while (ancestor != NodeTable.ROOT_ID && depth <= 1024) {
        depth++
        ancestor = table.node(ancestor)?.parentId ?: NodeTable.ROOT_ID
    }
    val dropModifier = if (subscribed) focusableModifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        WindowDropTargets.update(windowId, nodeId, bounds.left, bounds.top, bounds.right, bounds.bottom, density, depth)
    } else focusableModifier
    val dropTarget = platformFileDrop(dropModifier, node, dispatcher)
    // Where the node is drawn, recorded only while a scroll request needs to know.
    val modifier = if (node.placementWanted) {
        dropTarget.onPlaced { coordinates -> node.placement = coordinates }
    } else {
        dropTarget
    }
    // A layout made of glass tells what is inside it so. A field in a glass bar is part of
    // the bar in the systems that draw glass, and it can only take that shape if it knows
    // what it is sitting on.
    val glass = node.isMadeOfGlass(theme)
    ProvideInheritedColor(nodeId, node, table, theme) {
        when (node.widget) {
            WidgetKind.Column, WidgetKind.Row -> {
                val horizontal = node.widget == WidgetKind.Row
                val sizedModifier = if (node.usesIntrinsicCrossAxis(table)) {
                    if (horizontal) modifier.height(IntrinsicSize.Min) else modifier.width(IntrinsicSize.Min)
                } else modifier
                val weights = node.children.map { table.node(it)?.modifiers?.weightOf() }
                val intrinsicSafe = node.children.map { child -> !table.hasScrollingDescendant(child) }
                if (weights.any { it != null }) {
                    WeightedStack(
                        node, table, dispatcher, sizedModifier, horizontal, weights, intrinsicSafe,
                        node.horizontalArrangement(theme), node.verticalArrangement(theme),
                        node.horizontalAlignment(), node.verticalAlignment(), glass,
                    )
                } else if (horizontal) {
                    Row(sizedModifier, node.horizontalArrangement(theme), node.verticalAlignment()) {
                        OnGlass(glass) { Children(node, table, dispatcher) }
                    }
                } else {
                    Column(sizedModifier, node.verticalArrangement(theme), node.horizontalAlignment()) {
                        OnGlass(glass) { Children(node, table, dispatcher) }
                    }
                }
            }

            // A place files may be dropped is laid out as a plain box. Being this widget is
            // the whole of the difference: the drop target is set up for it and for nothing
            // else in the tree.
            WidgetKind.Box, WidgetKind.FileDropTarget ->
                Box(modifier, contentAlignment = node.boxAlignment()) {
                OnGlass(glass) { Children(node, table, dispatcher) }
            }

            // One widget whether or not it carries runs. A paragraph with a bold phrase in
            // it is one piece of text, and three widgets would wrap at the seams.
            WidgetKind.Text -> {
                val inherited = LocalInheritedTextColor.current
                val holder = table.animations.animated(nodeId)
                HostRichText(
                    node,
                    modifier,
                    dispatcher,
                    theme,
                    table.assets,
                    remember(holder, inherited) { TextColor(node, holder, inherited) },
                )
            }

            WidgetKind.Spacer -> Spacer(modifier)
            // The controls are drawn through `Unselectable`, which leaves them out of a
            // selectable region around them: a label is pressed, not copied. Outside a region
            // it adds nothing.
            WidgetKind.Button -> Unselectable { HostButton(node, modifier, table, dispatcher, theme) }
            WidgetKind.TextField -> Unselectable { HostTextField(node, modifier, dispatcher, table.assets) }
            WidgetKind.LazyColumn -> HostLazyColumn(node, modifier, table, dispatcher)

            // The same window as a list, rounded to whole rows because a row is what a grid
            // lays out. How many items a row holds is the grid's own answer where the screen
            // gave a minimum width instead of a count.
            WidgetKind.LazyGrid -> HostLazyGrid(node, modifier, table, dispatcher)
            // Drawing commands rather than children: the size comes from the modifier chain
            // and the commands are read from the node's one property.
            WidgetKind.Canvas -> HostCanvas(node, modifier, theme)
            // ScrollColumn is a column that scrolls without the Host windowing it, so every
            // child is materialised. Use LazyColumn when the list is long.
            //
            // LinearProgressIndicator is declared by an extension package rather than the core
            // schema, and is drawn like any built-in widget.
            WidgetKind.LinearProgressIndicator -> {
                val progress = node.number(PropertyKind.Progress)
                if (progress == null) {
                    LinearProgressIndicator(modifier = modifier)
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = modifier)
                }
            }
            WidgetKind.ScrollColumn -> {
                val scrollState = rememberHostScrollState(node, dispatcher)
                Column(
                    modifier = modifier.hostScroll(node, table, scrollState, vertical = true).verticalScroll(scrollState),
                    verticalArrangement = node.verticalArrangement(theme),
                    horizontalAlignment = node.horizontalAlignment(),
                ) { Children(node, table, dispatcher) }
            }

            // The same thing on its side: a row that scrolls without the Host windowing it,
            // so every child is materialised. Use LazyRow when the strip is long. The scroll
            // state is remembered here, under the node's own key, so a recomposition of the
            // row or of any child leaves the position where the user put it.
            WidgetKind.ScrollRow -> {
                val scrollState = rememberHostScrollState(node, dispatcher)
                Row(
                    modifier = modifier.hostScroll(node, table, scrollState, vertical = false).horizontalScroll(scrollState),
                    horizontalArrangement = node.horizontalArrangement(theme),
                    verticalAlignment = node.verticalAlignment(),
                ) { Children(node, table, dispatcher) }
            }

            // The containers, the overlays and the tab strip carry no appearance of their own:
            // each one names the kind of container it is and the design system decides what
            // that looks like.
            WidgetKind.Card ->
                HostContainerColumn(ContainerRole.Card, node, modifier, table, dispatcher, theme)

            WidgetKind.Surface ->
                HostContainerColumn(ContainerRole.Surface, node, modifier, table, dispatcher, theme)

            WidgetKind.TopAppBar -> HostTopAppBar(node, modifier, table, dispatcher, theme)
            WidgetKind.Dialog -> HostDialog(node, modifier, table, dispatcher, theme)
            WidgetKind.Menu -> HostMenu(node, modifier, table, dispatcher, theme)
            // The anchor stands where the node does and takes what the parent gave the
            // node; the node's own modifiers are the floating box's.
            WidgetKind.Popover ->
                HostPopover(node, parentModifier, modifier, table, dispatcher, theme)
            WidgetKind.Tabs -> Unselectable { HostTabs(node, modifier, table, dispatcher, theme) }
            WidgetKind.Tooltip -> HostTooltip(node, modifier, table, dispatcher, theme)
            WidgetKind.LazyRow -> HostLazyRow(node, modifier, table, dispatcher)

            // The selection controls and the indicators. Each one is drawn from the state it
            // was sent: the tick, the track, the thumb, the sweep and the weight of a rule are
            // the design system's, so the same declaration is a Material control here and a
            // Cupertino one there.
            WidgetKind.Checkbox -> Unselectable {
                HostToggle(ToggleRole.Checkbox, node, modifier, dispatcher, theme)
            }

            WidgetKind.RadioButton -> Unselectable {
                HostToggle(ToggleRole.RadioButton, node, modifier, dispatcher, theme)
            }

            WidgetKind.Switch -> Unselectable {
                HostToggle(ToggleRole.Switch, node, modifier, dispatcher, theme)
            }

            WidgetKind.Slider -> Unselectable { HostSlider(node, modifier, dispatcher, theme) }
            WidgetKind.ProgressIndicator -> HostProgressIndicator(node, modifier, theme)
            WidgetKind.Divider -> HostDivider(node, modifier, theme)

            // A picture is one registered id. The bytes were read when the Host registered
            // them, so what a frame carries is the id and a lookup.
            WidgetKind.Image -> HostImage(node, modifier, table.assets, dispatcher)
            WidgetKind.Icon -> HostIcon(node, modifier, table.assets, dispatcher, theme)

            // The pickers carry a value, a range and a change handler. Which way of picking the
            // user gets, a calendar grid, a wheel, a dial or a flyout, is the design system's
            // decision, and there is no property that could ask for one of them.
            WidgetKind.DatePicker -> Unselectable { HostDatePicker(node, modifier, dispatcher, theme) }
            WidgetKind.TimePicker -> Unselectable { HostTimePicker(node, modifier, dispatcher, theme) }
            WidgetKind.Dropdown ->
                Unselectable { HostDropdown(node, modifier, table, dispatcher, theme) }

            // One declaration, three presentations. Which one this is comes from the design
            // system, asked about the size class this window is in, so the Host that declared
            // it never learned how wide the window was.
            WidgetKind.Navigation -> HostNavigation(node, modifier, table, dispatcher, theme)

            // Normally drawn by the Navigation it belongs to, which knows whether it is the
            // selected one. On its own it is a destination nobody has chosen.
            WidgetKind.NavigationItem -> Unselectable { HostNavigationItem(node, modifier, theme) }

            // A temporary surface from an edge of the window. Which edge is this side's
            // decision, and so is everything about the drag that closes it.
            WidgetKind.Sheet -> HostSheet(node, modifier, table, dispatcher, theme)

            // The screen's frame. The application filled slots; what each one becomes here
            // comes from the design system and the width this window was measured at.
            WidgetKind.Scaffold -> HostScaffold(node, modifier, table, dispatcher, theme)

            // Drawn by the frame it belongs to, which is the only thing that knows which slot
            // this is. On its own it is a tree with a label nobody read.
            WidgetKind.ScaffoldSlot -> Column(modifier = modifier) {
                Children(node, table, dispatcher)
            }

            // A label, perhaps a glyph, and the chosen state exactly as the Host sent it. The
            // shape of the token is the design system's.
            WidgetKind.Chip -> HostChip(node, modifier, dispatcher, theme)

            // The one action a screen is about, in the form its design system gives it. Where
            // it goes is decided by the frame it is in, which asks the same rule.
            WidgetKind.FloatingAction -> HostFloatingAction(node, modifier, dispatcher, theme)

            // A count, a word or a dot, on its child or on its own. Where it sits and how a
            // large count is written are the design system's.
            WidgetKind.Badge -> HostBadge(node, modifier, table, dispatcher, theme)

            // Every Text inside, however deep, is one selection. The modifier goes on the
            // region rather than the column inside it, so a weight the parent gave it still
            // reaches the parent's scope.
            WidgetKind.SelectionContainer -> SelectableRegion(modifier) {
                Column { Children(node, table, dispatcher) }
            }

            // A side pane and a body. Whether they are side by side, laid over one another or
            // shown one at a time is the design system's answer for the split pane's own width,
            // and the drag on the divider never leaves this side until it is let go.
            WidgetKind.SplitPane -> HostSplitPane(node, modifier, table, dispatcher, theme)

            // The document is the editor's own: its text, caret, selection and history live in
            // the node's model, and the Host hears each committed change. Drawn outside a
            // selectable region around it, because selecting inside an editor is the editor's.
            WidgetKind.CodeEditor -> Unselectable { HostCodeEditor(node, modifier, dispatcher, theme, table) }

            // Children laid where the Host already put them, each at its own `Offset`. Nothing
            // here arranges, aligns or sizes one child by another: the Host's layout engine
            // has decided all of it, and a second layout pass could only disagree with it.
            // A platform window. Opening one is the platform layer's job, and until that
            // layer exists the interpreter draws a window's children where the node stands, so
            // the tree is whole and a protocol test can walk it.
            WidgetKind.Window -> Box(modifier) { Children(node, table, dispatcher) }

            // A row that wraps. Compose measures each child at its own width and starts a
            // new line where the next one does not fit, so the break is where the text
            // really ends. A child wider than the line is given the line's width.
            WidgetKind.FlowRow -> FlowRow(
                modifier = modifier,
                horizontalArrangement = node.flowHorizontalArrangement(),
                verticalArrangement = node.flowVerticalArrangement(),
                itemVerticalAlignment = node.flowItemVerticalAlignment(),
                maxItemsInEachRow = node.flowMaxItemsInEachRow(),
            ) {
                OnGlass(glass) { Children(node, table, dispatcher) }
            }

            WidgetKind.AbsoluteBox -> Layout(
                content = {
                    // The root of an HTML island names the factor it is drawn at. Only the
                    // children are given that Density: the box itself sits among native
                    // widgets and is sized in theirs.
                    val islandZoom = node.floatProp(PropertyKind.IslandZoom)
                    val outer = LocalDensity.current
                    val zoom = LocalZoom.current
                    val density = remember(islandZoom, outer, zoom?.level) {
                        islandZoom?.let { islandDensity(outer, zoom, it) } ?: outer
                    }
                    CompositionLocalProvider(LocalDensity provides density) {
                        node.children.forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                    }
                },
                modifier = modifier,
                measurePolicy = remember(nodeId) { AbsoluteBoxMeasurePolicy(nodeId) },
            )
        }
    }
}

/**
 * The colour text inside a container inherits, the way CSS `color` reaches every piece of
 * text below the element that set it. Null outside any container that names one, where a
 * Text draws in its own style's colour.
 *
 * A colour producer rather than a colour, read in the draw phase: an animation the Renderer
 * plays on the container's colour repaints every Text that inherits it, and none of them is
 * recomposed or sent a record of its own.
 */
val LocalInheritedTextColor = compositionLocalOf<ColorProducer?> { null }

/** The layout containers whose `color` is what the text inside them inherits. */
internal val INHERITS_TEXT_COLOR: Set<WidgetKind> = setOf(
    WidgetKind.Column,
    WidgetKind.Row,
    WidgetKind.FlowRow,
    WidgetKind.Box,
    WidgetKind.FileDropTarget,
    WidgetKind.AbsoluteBox,
    WidgetKind.ScrollColumn,
    WidgetKind.ScrollRow,
    WidgetKind.LazyColumn,
    WidgetKind.LazyRow,
    WidgetKind.LazyGrid,
)

/**
 * Hands a container's colour down to the text inside it. Every container provides one,
 * whether or not it names a colour now, so an animation or a `color` that arrives later
 * reaches its text in the draw phase without the container being composed again.
 */
@Composable
private fun ProvideInheritedColor(
    nodeId: Int,
    node: Node,
    table: NodeTable,
    theme: ResolvedTheme,
    content: @Composable () -> Unit,
) {
    if (node.widget !in INHERITS_TEXT_COLOR) {
        content()
        return
    }
    val parent = LocalInheritedTextColor.current
    val holder = table.animations.animated(nodeId)
    val inherited = remember(holder, theme, parent) { InheritedColor(node, holder, theme, parent) }
    CompositionLocalProvider(LocalInheritedTextColor provides inherited, content = content)
}

/** What a container shows its text: an animation's colour, its own `color`, or its parent's. */
private class InheritedColor(
    private val node: Node,
    private val holder: AnimatedNode,
    private val theme: ResolvedTheme,
    private val parent: ColorProducer?,
) : ColorProducer {
    override fun invoke(): Color {
        val bits = holder.textColor.longValue
        if (bits != AnimatedNode.NO_COLOR) return Color(bits.toULong())
        node.paintProp(PropertyKind.Color)?.let { return theme.color(it) }
        return parent?.invoke() ?: Color.Unspecified
    }
}

/**
 * The colour a Text draws with: its own animation's while one plays, its own style where it
 * names a `color`, and otherwise the colour of the container it is in.
 */
private class TextColor(
    private val node: Node,
    private val holder: AnimatedNode,
    private val inherited: ColorProducer?,
) : ColorProducer {
    override fun invoke(): Color {
        val bits = holder.textColor.longValue
        if (bits != AnimatedNode.NO_COLOR) return Color(bits.toULong())
        if (node.paintProp(PropertyKind.Color) != null) return Color.Unspecified
        return inherited?.invoke() ?: Color.Unspecified
    }
}

/**
 * How an `AbsoluteBox` lays out: every child at the box's own origin, moved from there by
 * its `Offset`, with later children drawn over earlier ones.
 *
 * Each child is measured with no limit at all, so it is exactly the size its own modifiers
 * say. Under a limit, a child bigger than the box would be squeezed, or, with a required
 * size, centred on the room it was given, and either would move it away from where the
 * Host put it. CSS lets a box overflow its parent, and so does this.
 *
 * The box itself is as big as its largest child, within what its parent allows, the way a
 * `Box` is. An HTML screen gives every box a `RequiredSize`, so this is rarely what decides.
 */
private class AbsoluteBoxMeasurePolicy(private val nodeId: Int) : MeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<Measurable>,
        constraints: Constraints,
    ): MeasureResult {
        RenderNodeObserver.onMeasure?.invoke(nodeId)
        val placeables = measurables.map { it.measure(Constraints()) }
        var width = constraints.minWidth
        var height = constraints.minHeight
        for (placeable in placeables) {
            width = maxOf(width, placeable.width)
            height = maxOf(height, placeable.height)
        }
        width = width.coerceAtMost(constraints.maxWidth)
        height = height.coerceAtMost(constraints.maxHeight)
        // `place` rather than `placeRelative`: the Host's coordinates are the screen's, so a
        // right-to-left layout direction must not mirror them a second time.
        return layout(width, height) {
            for (placeable in placeables) placeable.place(0, 0)
        }
    }
}

/**
 * How a node that takes files gets its drop target, filled in by the platform that has
 * one.
 *
 * A hook rather than a call, because setting up a drop target means reading the toolkit's
 * clipboard flavours and only the desktop has them. A phone has no notion of letting a
 * file go over a window, so it leaves this alone and the modifier chain is unchanged,
 * which is the same rule pointer hover follows.
 */
var platformFileDrop: @Composable (Modifier, Node, EventDispatcher) -> Modifier =
    { modifier, _, _ -> modifier }

/**
 * Whether the system has been asked to reduce motion, answered by the platform that can
 * be asked.
 *
 * A hook for the same reason as the one above: the setting lives in a different place on
 * each desktop and nowhere at all on a target that has no desktop. The Host is not
 * consulted and never learns the answer, because this is the user's setting about their
 * own machine rather than anything the application declared.
 *
 * Read only by a node that declared a motion role, so a screen that declares none never
 * asks the question.
 */
var platformReducedMotion: () -> Boolean = { false }

/**
 * What the platform says about reducing motion, including that it cannot say.
 *
 * The answer the Host is told, so a screen written with CSS can answer
 * `prefers-reduced-motion` the way a browser would. Asked when the Renderer starts and again
 * when its window comes back into use. A platform that installs nothing answers unknown.
 */
var platformReducedMotionSetting: () -> dev.darkpyonix.composerust.protocol.ReducedMotion =
    { dev.darkpyonix.composerust.protocol.ReducedMotion.Unknown }

/**
 * Whether the person at this machine has asked the platform for high contrast colours:
 * Windows' contrast themes, macOS's increased contrast, GNOME's high contrast setting.
 *
 * A hook for the same reason as the one above. When it says yes, an application's palette
 * is set aside and the design system's own colours are drawn: those colours were chosen
 * by the person so they could see the screen, and a brand must not paint over them.
 *
 * Read when the theme is resolved. The desktop answers once and keeps the answer; a
 * target that does not install anything answers no.
 */
var platformHighContrast: () -> Boolean = { false }

/**
 * Tells the platform whether this window wants a material behind it.
 *
 * A hook because only one build can answer: the effect view is put behind the window by
 * the shared library's own C entry, which exists in the native image and not in the
 * development shell, and not at all on a platform whose windows are not ours. Whoever
 * cannot do it leaves this alone, and the window comes up the way it always did.
 */
var platformWindowMaterial: (Boolean) -> Unit = { }

/**
 * The axis a widget stacks its children along, which is the axis a child's `Weight` is a
 * share of: a weight under a Column is a share of the height, a weight under a Row a share
 * of the width.
 */
internal enum class StackingAxis { Vertical, Horizontal, None }

/**
 * How the node that hosts a child lays its children out, so a child can tell whether a
 * `Weight` of its own has already decided one of its sizes.
 *
 * Only the layouts that really apply a weight are named here. Anything else, a Box or a
 * widget that draws its children without a weight scope, leaves a weighted child both of
 * its axes free.
 */
internal fun NodeTable.stackingAxis(nodeId: Int): StackingAxis = when (node(nodeId)?.widget) {
    WidgetKind.Column,
    WidgetKind.ScrollColumn,
    WidgetKind.Card,
    WidgetKind.Surface,
    WidgetKind.SelectionContainer,
    -> StackingAxis.Vertical

    WidgetKind.Row,
    WidgetKind.ScrollRow,
    WidgetKind.TopAppBar,
    -> StackingAxis.Horizontal

    else -> StackingAxis.None
}

/** Whether this node asked for a material that the running design system draws as glass. */
private fun Node.isMadeOfGlass(theme: ResolvedTheme): Boolean = modifiers.any { value ->
    value is ProtocolModifier.Material &&
        theme.rules.material(value.role, theme) is SurfaceMaterial.Glass
}

/**
 * Whether a Row or Column sizes its cross axis to its largest child, so siblings with
 * `FillMaxHeight` or `FillMaxWidth` match the tallest or widest sibling when the container
 * was not given a fixed cross-axis size of its own.
 */
private fun Node.usesIntrinsicCrossAxis(table: NodeTable): Boolean {
    if (flag(PropertyKind.Collapsible, default = false)) return true
    val weightedAxis = if (modifiers.weightOf() != null) table.stackingAxis(parentId) else StackingAxis.None
    return when (widget) {
        WidgetKind.Row -> {
            val boundedHeight = weightedAxis == StackingAxis.Vertical || modifiers.any {
                it is ProtocolModifier.Height ||
                    it is ProtocolModifier.Size ||
                    it is ProtocolModifier.RequiredSize ||
                    it is ProtocolModifier.FillMaxHeight
            }
            !boundedHeight && children.any { childId ->
                table.node(childId)?.modifiers?.any { it is ProtocolModifier.FillMaxHeight } == true
            }
        }
        WidgetKind.Column -> {
            val boundedWidth = weightedAxis == StackingAxis.Horizontal || modifiers.any {
                it is ProtocolModifier.Width ||
                    it is ProtocolModifier.Size ||
                    it is ProtocolModifier.RequiredSize ||
                    it is ProtocolModifier.FillMaxWidth
            }
            !boundedWidth && children.any { childId ->
                table.node(childId)?.modifiers?.any { it is ProtocolModifier.FillMaxWidth } == true
            }
        }
        else -> false
    }
}

/**
 * Remembers the Compose [ScrollState] of a [WidgetKind.ScrollColumn] or [WidgetKind.ScrollRow]
 * and wires the Host's optional scroll commands (`Value`, `Determinate`, `Count`) and scroll
 * position reporting (`OnRangeRequested`).
 */
@Composable
private fun rememberHostScrollState(node: Node, dispatcher: EventDispatcher): ScrollState {
    val state = rememberScrollState()
    val density = LocalDensity.current
    val revision = (node.property(PropertyKind.Count) as? PropertyValue.Integer)?.value ?: 0L
    if (revision > 0L) {
        LaunchedEffect(node.id, revision, density) {
            // A move that came in a batch with scroll requests has its place among them
            // and is applied there, once.
            if (node.scroll?.statedRevision == revision) return@LaunchedEffect
            node.statedScroll(state, density)
        }
    }
    val handlerId = node.handler(PropertyKind.OnRangeRequested)
    if (handlerId != null) {
        LaunchedEffect(node.id, state, handlerId, density, dispatcher) {
            snapshotFlow { state.value to state.maxValue }
                .distinctUntilChanged()
                .collect { (valuePx, maxPx) ->
                    val clampedMax = if (maxPx == Int.MAX_VALUE) 0 else maxPx.coerceAtLeast(0)
                    val offsetDp = with(density) { valuePx.coerceAtLeast(0).toDp().value }
                    val maxDp = with(density) { clampedMax.toDp().value }
                    dispatcher.dispatch(
                        HostEvent.RangeRequested(
                            node.id,
                            handlerId,
                            offsetDp.toBits(),
                            maxDp.toBits(),
                        ),
                    )
                }
        }
    }
    return state
}

/**
 * Applies the move a scroll container's own properties ask for: to `Value` dp, animated
 * where `Determinate` says so. An animation counts as this side's own movement, so a
 * scroll request that arrives while it runs replaces it instead of being taken for the
 * user's hand.
 */
private suspend fun Node.statedScroll(state: ScrollState, density: androidx.compose.ui.unit.Density) {
    val targetDp = number(PropertyKind.Value) ?: 0f
    val targetPx = with(density) { targetDp.coerceAtLeast(0f).dp.toPx() }.roundToInt()
    if (flag(PropertyKind.Determinate, default = false)) {
        val port = scroll
        port?.let { it.ownMotion++ }
        try {
            state.animateScrollTo(targetPx)
        } finally {
            port?.let { it.ownMotion-- }
        }
    } else {
        state.scrollTo(targetPx)
    }
}

/**
 * Wires a `ScrollColumn` or `ScrollRow` to the Host's scroll requests and to its scroll
 * report. The size of the viewport across the scrolling axis is measured only for a node
 * that has a handler to report it to.
 */
@Composable
private fun Modifier.hostScroll(node: Node, table: NodeTable, state: ScrollState, vertical: Boolean): Modifier {
    val density by rememberUpdatedState(LocalDensity.current)
    val cross = remember(node.id) { mutableIntStateOf(0) }
    val surface = remember(node.id, state, vertical) {
        val axis = state.axis()
        OffsetScrollSurface(
            x = if (vertical) null else axis,
            y = if (vertical) axis else null,
            mainVertical = vertical,
            cross = { cross.intValue },
            statedMove = { node.statedScroll(state, density) },
        )
    }
    HostScroll(node, table, surface)
    if (node.handler(PropertyKind.OnScrollChanged) == null) return this
    return onSizeChanged { size -> cross.intValue = if (vertical) size.width else size.height }
}

/**
 * Runs [content] one glass layer deeper where [glass] is true, and unchanged otherwise.
 *
 * Unchanged is the ordinary case and costs nothing: no provider is composed for a node
 * that is not made of glass.
 */
@Composable
internal fun OnGlass(glass: Boolean, content: @Composable () -> Unit) {
    if (!glass) {
        content()
        return
    }
    CompositionLocalProvider(LocalGlassDepth provides LocalGlassDepth.current + 1, content = content)
}

/** Measures weighted children at least as large as their main-axis minimum intrinsic size. */
@Composable
private fun WeightedStack(
    node: Node,
    table: NodeTable,
    dispatcher: EventDispatcher,
    modifier: Modifier,
    horizontal: Boolean,
    weights: List<Float?>,
    intrinsicSafe: List<Boolean>,
    horizontalArrangement: Arrangement.Horizontal,
    verticalArrangement: Arrangement.Vertical,
    horizontalAlignment: Alignment.Horizontal,
    verticalAlignment: Alignment.Vertical,
    glass: Boolean,
) {
    val policy = remember(horizontal, weights, intrinsicSafe, horizontalArrangement, verticalArrangement, horizontalAlignment, verticalAlignment) {
        WeightedStackPolicy(horizontal, weights, intrinsicSafe, horizontalArrangement, verticalArrangement, horizontalAlignment, verticalAlignment)
    }
    Layout(
        content = {
            OnGlass(glass) {
                node.children.forEach { childId -> key(childId) { RenderNode(childId, table, dispatcher) } }
            }
        },
        modifier = modifier.clipToBounds(),
        measurePolicy = policy,
    )
}

private class WeightedStackPolicy(
    private val horizontal: Boolean,
    private val weights: List<Float?>,
    private val intrinsicSafe: List<Boolean>,
    private val horizontalArrangement: Arrangement.Horizontal,
    private val verticalArrangement: Arrangement.Vertical,
    private val horizontalAlignment: Alignment.Horizontal,
    private val verticalAlignment: Alignment.Vertical,
) : MeasurePolicy {
    private fun spacing(scope: androidx.compose.ui.unit.Density): Int = with(scope) {
        (if (horizontal) horizontalArrangement.spacing else verticalArrangement.spacing).roundToPx()
    }

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val mainMax = if (horizontal) constraints.maxWidth else constraints.maxHeight
        val mainMin = if (horizontal) constraints.minWidth else constraints.minHeight
        val crossMax = if (horizontal) constraints.maxHeight else constraints.maxWidth
        val gap = spacing(this)
        val places = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        var fixed = 0
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        fun childConstraints(min: Int, max: Int) = if (horizontal) {
            Constraints(minWidth = min, maxWidth = max, maxHeight = crossMax)
        } else {
            Constraints(minHeight = min, maxHeight = max, maxWidth = crossMax)
        }
        measurables.forEachIndexed { index, measurable ->
            if (weights[index] == null) {
                val available = if (mainMax == Constraints.Infinity) mainMax else (mainMax - fixed - gaps).coerceAtLeast(0)
                val place = measurable.measure(childConstraints(0, available))
                places[index] = place
                fixed += if (horizontal) place.width else place.height
            }
        }
        val target = if (mainMax == Constraints.Infinity) mainMin else mainMax
        val remaining = (target - fixed - gaps).coerceAtLeast(0)
        val totalWeight = weights.filterNotNull().sum()
        var distributed = 0
        var remainingWeight = totalWeight
        measurables.forEachIndexed { index, measurable ->
            val weight = weights[index] ?: return@forEachIndexed
            val share = if (remainingWeight == weight) remaining - distributed else (remaining * weight / totalWeight).roundToInt().coerceAtMost((remaining - distributed).coerceAtLeast(0))
            distributed += share
            remainingWeight -= weight
            val intrinsic = intrinsic(index) { if (horizontal) measurable.minIntrinsicWidth(crossMax) else measurable.minIntrinsicHeight(crossMax) }
            val size = maxOf(share, intrinsic).coerceAtMost(Constraints.Infinity - 1)
            places[index] = measurable.measure(childConstraints(size, size))
        }
        val children = places.map { it!! }
        val sizes = children.map { if (horizontal) it.width else it.height }.toIntArray()
        val contentMain = (sizes.fold(0L) { sum, size -> sum + size } + gaps).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val main = if (horizontal) constraints.constrainWidth(contentMain) else constraints.constrainHeight(contentMain)
        val contentCross = children.maxOfOrNull { if (horizontal) it.height else it.width } ?: 0
        val cross = if (horizontal) constraints.constrainHeight(contentCross) else constraints.constrainWidth(contentCross)
        val positions = IntArray(children.size)
        if (horizontal) with(horizontalArrangement) { arrange(main, sizes, layoutDirection, positions) }
        else with(verticalArrangement) { arrange(main, sizes, positions) }
        return layout(if (horizontal) main else cross, if (horizontal) cross else main) {
            children.forEachIndexed { index, place ->
                if (horizontal) place.place(positions[index], verticalAlignment.align(place.height, cross))
                else place.place(horizontalAlignment.align(place.width, cross, layoutDirection), positions[index])
            }
        }
    }

    private fun IntrinsicMeasureScope.mainIntrinsic(children: List<IntrinsicMeasurable>, cross: Int, min: Boolean): Int {
        val gap = spacing(this) * (children.size - 1).coerceAtLeast(0)
        val sizes = children.mapIndexed { index, child -> intrinsic(index) {
            if (horizontal) {
                if (min) child.minIntrinsicWidth(cross) else child.maxIntrinsicWidth(cross)
            } else {
                if (min) child.minIntrinsicHeight(cross) else child.maxIntrinsicHeight(cross)
            }
        } }
        val unit = sizes.indices.filter { weights[it] != null }.maxOfOrNull { sizes[it] / weights[it]!! } ?: 0f
        val weighted = (unit * weights.filterNotNull().sum()).roundToInt()
        return (sizes.indices.filter { weights[it] == null }.sumOf { sizes[it].toLong() } + weighted + gap)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun IntrinsicMeasureScope.crossIntrinsic(children: List<IntrinsicMeasurable>, main: Int, min: Boolean): Int {
        val gap = spacing(this) * (children.size - 1).coerceAtLeast(0)
        val fixed = children.indices.filter { weights[it] == null }.map { index ->
            val child = children[index]
            intrinsic(index) { if (horizontal) child.maxIntrinsicWidth(Constraints.Infinity) else child.maxIntrinsicHeight(Constraints.Infinity) }
        }.fold(0L) { sum, value -> sum + value }
        val remaining = if (main == Constraints.Infinity) Constraints.Infinity else (main.toLong() - fixed - gap).coerceAtLeast(0).toInt()
        val total = weights.filterNotNull().sum()
        return children.indices.maxOfOrNull { index ->
            val child = children[index]
            val weight = weights[index]
            val available = if (weight == null || remaining == Constraints.Infinity) Constraints.Infinity
                else (remaining * weight / total).roundToInt()
            val minimum = intrinsic(index) { if (horizontal) child.minIntrinsicWidth(Constraints.Infinity) else child.minIntrinsicHeight(Constraints.Infinity) }
            val size = if (weight == null) available else maxOf(available, minimum)
            intrinsic(index) { if (horizontal) {
                if (min) child.minIntrinsicHeight(size) else child.maxIntrinsicHeight(size)
            } else {
                if (min) child.minIntrinsicWidth(size) else child.maxIntrinsicWidth(size)
            } }
        } ?: 0
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (horizontal) mainIntrinsic(measurables, height, true) else crossIntrinsic(measurables, height, true)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        if (horizontal) mainIntrinsic(measurables, height, false) else crossIntrinsic(measurables, height, false)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        if (horizontal) crossIntrinsic(measurables, width, true) else mainIntrinsic(measurables, width, true)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        if (horizontal) crossIntrinsic(measurables, width, false) else mainIntrinsic(measurables, width, false)

    private inline fun intrinsic(index: Int, query: () -> Int): Int {
        if (!intrinsicSafe[index]) return 0
        return try { query() } catch (_: IllegalStateException) { 0 }
    }
}

private fun NodeTable.hasScrollingDescendant(id: Int): Boolean {
    val node = node(id) ?: return false
    if (node.widget in setOf(WidgetKind.LazyColumn, WidgetKind.LazyRow, WidgetKind.LazyGrid, WidgetKind.ScrollColumn, WidgetKind.ScrollRow)) return true
    return node.children.any { hasScrollingDescendant(it) }
}

/**
 * Children of a Column: weight is applied here because `Modifier.weight` exists only inside
 * `ColumnScope`. Every layout that stacks its children vertically draws them through this,
 * so a weighted child is given its share wherever it is hosted and not only under a Column.
 */
@Composable
internal fun ColumnScope.Children(node: Node, table: NodeTable, dispatcher: EventDispatcher) {
    // `key` keeps each child's state attached to its Host node id across Move mutations.
    node.children.forEach { childId -> key(childId) { WeightedChild(childId, table, dispatcher) } }
}

@Composable
private fun ColumnScope.WeightedChild(childId: Int, table: NodeTable, dispatcher: EventDispatcher) {
    // Its own composable, so reading the child's modifier list subscribes this scope alone
    // and a `SetModifier` on one child cannot invalidate its siblings.
    val weight = table.node(childId)?.modifiers?.weightOf()
    RenderNode(
        childId,
        table,
        dispatcher,
        if (weight == null) Modifier else Modifier.weight(weight),
    )
}

@Composable
internal fun RowScope.Children(node: Node, table: NodeTable, dispatcher: EventDispatcher) {
    node.children.forEach { childId -> key(childId) { WeightedChild(childId, table, dispatcher) } }
}

@Composable
private fun RowScope.WeightedChild(childId: Int, table: NodeTable, dispatcher: EventDispatcher) {
    val weight = table.node(childId)?.modifiers?.weightOf()
    RenderNode(
        childId,
        table,
        dispatcher,
        if (weight == null) Modifier else Modifier.weight(weight),
    )
}

/**
 * A Box has no weight axis, so its children are drawn as they are.
 *
 * Except the one action a screen is about. A Box is how a screen holds its page, so an
 * action declared in one belongs to that page and is laid over it, at the corner the design
 * system names, without taking any of the room the page is laid out in.
 */
@Composable
private fun BoxScope.Children(node: Node, table: NodeTable, dispatcher: EventDispatcher) {
    node.children.forEach { childId ->
        key(childId) {
            if (table.node(childId)?.widget == WidgetKind.FloatingAction) {
                FloatingActionArea(LocalDesignTheme.current) { RenderNode(childId, table, dispatcher) }
            } else {
                val matchesParent = table.node(childId)?.modifiers?.any { it is ProtocolModifier.MatchParentSize } == true
                RenderNode(childId, table, dispatcher, if (matchesParent) Modifier.matchParentSize() else Modifier)
            }
        }
    }
}

/** The label of a Button follows the design system's button type role unless overridden. */
internal fun Node.buttonTextStyle(theme: ResolvedTheme, role: TypeRole) = textStyle(theme, role)

/**
 * Where a frame's measuring goes, by kind of node, for a profile of a resize. Off unless a
 * measurement switches it on before the content is composed; when off it adds nothing to any
 * node. Each node's time is its own: what its children took is taken out of it, so a kind's
 * total is what measuring nodes of that kind cost in themselves.
 */
object LayoutProfile {
    var enabled = false

    private class Entry(var nanos: Long = 0, var count: Int = 0)

    private val totals = HashMap<String, Entry>()
    private val childNanos = ArrayList<Long>()

    fun modifier(kind: String): Modifier = Modifier.layout { measurable, constraints ->
        childNanos.add(0L)
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val placeable = measurable.measure(constraints)
        val inclusive = started.elapsedNow().inWholeNanoseconds
        val children = childNanos.removeAt(childNanos.size - 1)
        if (childNanos.isNotEmpty()) childNanos[childNanos.size - 1] = childNanos[childNanos.size - 1] + inclusive
        val entry = totals.getOrPut(kind) { Entry() }
        entry.nanos += inclusive - children
        entry.count++
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    /** Clears what was gathered. */
    fun reset() {
        totals.clear()
        childNanos.clear()
    }

    /** Kinds by their own measuring time, most first: kind, milliseconds, nodes measured. */
    fun snapshot(): List<Triple<String, Double, Int>> =
        totals.entries.map { Triple(it.key, it.value.nanos / 1e6, it.value.count) }.sortedByDescending { it.second }
}

/** The containers that become focus targets when they listen for keys. */
private val KeyFocusTargets = setOf(WidgetKind.Box, WidgetKind.Column, WidgetKind.Row, WidgetKind.FileDropTarget)
