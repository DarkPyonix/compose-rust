package dev.darkpyonix.composerust.foundation

import androidx.compose.ui.text.style.TextOverflow
import dev.darkpyonix.composerust.design.CaptionTitleAlignment
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.ui.textStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.ContainerRole
import dev.darkpyonix.composerust.design.ContainerStyle
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.SurfaceMaterial
import dev.darkpyonix.composerust.design.glassLift
import dev.darkpyonix.composerust.design.glassSurface
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.node.OnGlass
import dev.darkpyonix.composerust.ui.variant
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalWindowCaption
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.Children
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.cornerCurve
import dev.darkpyonix.composerust.ui.withCornerCurve
import dev.darkpyonix.composerust.ui.weightOf
import dev.darkpyonix.composerust.design.liftsOffThePage
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.ui.node.nodeTestTag

/** True when the Host set a shape modifier of its own, which replaces the default shape. */
internal fun Node.setsOwnShape(): Boolean =
    modifiers.any {
        it is ProtocolModifier.Shape ||
            it is ProtocolModifier.ShapeRole ||
            it is ProtocolModifier.CornerEach
    }

/**
 * The corner this container is drawn with: the one the Host asked for if it asked, and the
 * design system's corner for this kind of container otherwise.
 */
internal fun Node.containerShape(style: ContainerStyle, theme: ResolvedTheme): Shape =
    if (setsOwnShape()) {
        modifiers.resolvedShape(theme)
    } else {
        style.shape.withCornerCurve(modifiers.cornerCurve())
    }

/** True when the Host set a resting height of its own, which then replaces the default. */
internal fun Node.setsOwnElevation(): Boolean =
    modifiers.any { it is ProtocolModifier.Elevation }

/**
 * True when the Host filled this container itself, which then replaces the design
 * system's fill rather than sitting behind it.
 *
 * A background in the Modifier chain is drawn before the container style's fill, so the
 * style's fill covers it and the only part that survives is whatever falls outside the
 * style's shape. An application that asked for a panel in the reading ink got a ring of
 * that ink around a panel in the usual colour, with its text set in the surface colour
 * and therefore invisible.
 */
internal fun Node.setsOwnBackground(): Boolean =
    modifiers.any { it is ProtocolModifier.Background }

internal fun Node.setsOwnBorder(): Boolean =
    modifiers.any { it is ProtocolModifier.Border || it is ProtocolModifier.BorderEach }

internal fun Node.setsOwnPadding(): Boolean =
    modifiers.any {
        it is ProtocolModifier.Padding ||
            it is ProtocolModifier.PaddingEach ||
            it is ProtocolModifier.PaddingRole
    }

/**
 * Background, corner, stroke, resting height and inner padding for a container role.
 *
 * The node's own modifiers have already been applied to [modifier], so anything the Host
 * set wins: its `Background` replaces this one and its `Elevation` replaces the resting
 * height instead of adding to it. Everything it did not set still comes from the design
 * system, so a recoloured panel keeps that system's corner, stroke and inner padding.
 */
@Composable
internal fun Modifier.containerDecoration(
    node: Node,
    style: ContainerStyle,
    theme: ResolvedTheme,
    /** True for a menu, a dialog or a tooltip: a sheet over the page rather than in it. */
    overlay: Boolean = false,
    role: ContainerRole? = null,
): Modifier {
    val shape = (if (node.setsOwnShape()) {
        node.containerShape(style, theme)
    } else if (overlay) {
        when (role) {
            ContainerRole.Dialog -> theme.overlay?.dialogCornerRadius?.takeIf { it > 0 }?.let { RoundedCornerShape(it.dp) }
            ContainerRole.Menu -> theme.overlay?.popupCornerRadius?.takeIf { it > 0 }?.let { RoundedCornerShape(it.dp) }
            else -> null
        } ?: style.shape
    } else {
        style.shape
    }).withCornerCurve(node.modifiers.cornerCurve())
    val effectiveElevation = if (node.setsOwnElevation()) {
        0.dp
    } else if (overlay) {
        when (role) {
            ContainerRole.Dialog -> theme.overlay?.dialogElevation?.takeIf { it > 0 }?.let { it.dp }
            ContainerRole.Menu -> theme.overlay?.popupElevation?.takeIf { it > 0 }?.let { it.dp }
            else -> null
        } ?: style.elevation
    } else {
        style.elevation
    }
    val raised = if (node.setsOwnElevation() || effectiveElevation.value <= 0f) {
        this
    } else {
        theme.rules.elevation(this, effectiveElevation, shape, theme)
    }
    val material = style.material
    // A Host that named its own Background has already had it applied, so painting the
    // design system's fill here would cover it. Clipping, stroke and inner padding still
    // come from the system unless the Host named its own: only the colour is the application's.
    val filled = when {
        node.setsOwnBackground() -> raised.clip(shape)
        material == null -> raised.clip(shape).background(style.container, shape)
        else -> {
            // A glass surface paints its own fill and its own lit edge, because the two are
            // one effect: the tint is what lets the backdrop through and the edge is what
            // gives the sheet thickness. Clipping still happens first so a child cannot spill
            // past the corner.
            //
            // The lift goes before the clip, because it is drawn outside the outline and a
            // clip earlier in the chain cuts it off. Only an overlay gets one: a menu is a
            // sheet held clear of what it covers and the shadow is what says so, and a shadow
            // under every glass surface in a tree turns a page into a pile of cards.
            raised
                .then(if (liftsOffThePage(material, overlay)) Modifier.glassLift(material, shape) else Modifier)
                .clip(shape)
                .glassSurface(material, shape)
        }
    }
    return filled
        .then(
            if (!node.setsOwnBorder() && style.borderWidth.value > 0f) {
                Modifier.border(style.borderWidth, style.borderColor, shape)
            } else {
                Modifier
            },
        )
        .then(
            if (node.setsOwnPadding()) {
                Modifier
            } else if (overlay) {
                val customPadding = when (role) {
                    ContainerRole.Dialog -> theme.overlay?.dialogPadding?.takeIf { it > 0 }?.let { it.dp }
                    ContainerRole.Menu -> theme.overlay?.popupPadding?.takeIf { it > 0 }?.let { it.dp }
                    else -> null
                }
                if (customPadding != null) {
                    Modifier.padding(customPadding)
                } else {
                    Modifier.padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding)
                }
            } else {
                Modifier.padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding)
            },
        )
}

/**
 * A grouped container. Its children are its content, stacked top to bottom.
 *
 * Nothing about how it looks is decided here: [ContainerRole.Card] and
 * [ContainerRole.Surface] differ only in what the design system says each one means.
 */
@Composable
internal fun HostContainerColumn(
    role: ContainerRole,
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.container(role, theme)
    Column(modifier.containerDecoration(node, style, theme)) {
        Children(node, table, dispatcher)
    }
}

/**
 * The bar across the top of a screen: its children laid out left to right, vertically
 * centred, with whatever rule the design system draws under it.
 *
 * A child with a `Weight` modifier takes its share of the bar, which is how a title pushes
 * the actions to the far end.
 */
@Composable
internal fun HostTopAppBar(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.container(ContainerRole.TopAppBar, theme)
    val caption = LocalWindowCaption.current
    // The bar says how tall it is, so a message placed along the top of the window can
    // start below it. Only the height: whether this bar is the one across the top of the
    // window is the page's question, answered where the message is drawn.
    val insets = table.insets
    val density = LocalDensity.current
    DisposableEffect(insets, node.id) { onDispose { insets.topBar(node.id, null) } }
    val measured = modifier.onSizeChanged { size ->
        insets.topBar(node.id, with(density) { size.height.toDp() })
    }
    if (style.floats) {
        FloatingTopAppBar(node, measured, table, dispatcher, theme, style)
        return
    }
    Column(measured) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The bar is the window's caption where the tree opens with one: at
                // least as tall as the strip the window buttons sit in, and starting
                // clear of them. Its content shares that row rather than stacking under
                // it, because a desktop toolbar sits on the same line as the window
                // buttons and a bar that began below them would be twice as tall for
                // nothing.
                .heightIn(min = caption.height)
                .containerDecoration(node, style, theme)
                .padding(
                    start = if (caption.buttonsAtStart) caption.buttonsWidth else 0.dp,
                    end = if (caption.buttonsAtStart) 0.dp else caption.buttonsWidth,
                    // A phone's status bar. The decoration above paints through it, so
                    // the bar's own colour runs to the top edge of the screen and only
                    // its content starts below the clock. Padding outside the decoration
                    // instead would leave the page showing above the bar.
                    top = caption.insetTop,
                ),
            horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Sm)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = node.text(PropertyKind.Text)
            val centred = caption.height > 0.dp &&
                theme.rules.caption(theme).titleAlignment == CaptionTitleAlignment.Center
            if (title.isNotEmpty() && centred) {
                // Centred in the window rather than between the bar's other children.
                // GNOME, Breeze and Deepin all put the window title in the middle of the
                // caption and everything else at the leading edge, and a title that
                // drifted as buttons were added beside it would not be that. A Box over
                // the row is the only way to centre against the window while the rest of
                // the row lays out normally.
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val children = node.children.toList()
                        val split = table.trailingActionsStart(children)
                        children.subList(0, split).forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                        if (split < children.size) {
                            BarActions(node, children.subList(split, children.size), table, dispatcher, theme, gap = 0.dp, capsule = null)
                        }
                    }
                    BarTitle(node, title, theme)
                }
            } else {
                if (title.isNotEmpty()) {
                    BarTitle(node, title, theme)
                }
                val children = node.children.toList()
                val split = table.trailingActionsStart(children)
                children.subList(0, split).forEach { childId ->
                    key(childId) { BarChild(childId, table, dispatcher) }
                }
                if (split < children.size) {
                    BarActions(
                        node,
                        children.subList(split, children.size),
                        table,
                        dispatcher,
                        theme,
                        gap = theme.space(SpaceRole.Sm),
                        capsule = null,
                    )
                }
            }
        }
        val separator = style.separator
        if (separator != null) {
            Box(Modifier.fillMaxWidth().height(HAIRLINE).background(separator))
        }
    }
}

@Composable
private fun RowScope.BarChild(childId: Int, table: NodeTable, dispatcher: EventDispatcher) {
    val weight = table.node(childId)?.modifiers?.weightOf()
    RenderNode(
        childId,
        table,
        dispatcher,
        if (weight == null) Modifier else Modifier.weight(weight),
    )
}

/**
 * A top bar that paints nothing across the window.
 *
 * The title sits on the page as it is. Each run of plain actions is gathered into one
 * capsule of the bar's material floating over the page, the way a toolbar groups its
 * buttons in the systems that draw glass: the two trailing actions of a chat window share
 * one capsule, and a back button standing on its own is a capsule of its own.
 *
 * An action that fills itself, a filled button above all, is a capsule already and stands
 * alone rather than inside another. Anything that is not an action is laid out as it is,
 * so a weighted spacer still pushes the actions to the far end.
 *
 * The caption is honoured exactly as the strip does: the row is at least as tall as the
 * strip the window buttons sit in, shares their line, and keeps clear of them.
 */
@Composable
private fun FloatingTopAppBar(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    style: ContainerStyle,
) {
    val caption = LocalWindowCaption.current
    val material = style.material ?: SurfaceMaterial.Opaque(style.container)
    val capsule = style.shape
    val edge = style.floatingInset ?: theme.space(SpaceRole.Sm)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = caption.height)
            .padding(
                start = (if (caption.buttonsAtStart) caption.buttonsWidth else 0.dp) + edge,
                end = (if (caption.buttonsAtStart) 0.dp else caption.buttonsWidth) + edge,
                top = caption.insetTop + (style.floatingInset ?: theme.space(SpaceRole.Xs)),
                bottom = style.floatingInset ?: theme.space(SpaceRole.Xs),
            ),
        horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Sm)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val title = node.text(PropertyKind.Text)
        if (title.isNotEmpty()) {
            BarTitle(node, title, theme)
        }
        val children = node.children.toList()
        val split = table.trailingActionsStart(children)
        for (group in floatingGroups(children.subList(0, split), table)) {
            if (group.size == 1 && !table.gathersIntoCapsule(group[0])) {
                key(group[0]) { BarChild(group[0], table, dispatcher) }
                continue
            }
            key(group[0]) {
                FloatingCapsule(BarCapsule(material, capsule, theme.space(SpaceRole.Xs))) {
                    group.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher) }
                    }
                }
            }
        }
        if (split < children.size) {
            BarActions(
                node,
                children.subList(split, children.size),
                table,
                dispatcher,
                theme,
                gap = theme.space(SpaceRole.Sm),
                capsule = BarCapsule(material, capsule, theme.space(SpaceRole.Xs)),
            )
        }
    }
}

/** How a floating bar draws a run of plain actions: one capsule of its material around them. */
private class BarCapsule(val material: SurfaceMaterial, val shape: Shape, val padding: Dp)

@Composable
private fun FloatingCapsule(capsule: BarCapsule, content: @Composable () -> Unit) {
    Row(
        Modifier
            .glassLift(capsule.material, capsule.shape)
            .clip(capsule.shape)
            .glassSurface(capsule.material, capsule.shape)
            .padding(horizontal = capsule.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnGlass(true) { content() }
    }
}

/**
 * The bar's children split into what floats together: each run of plain actions is one
 * group, and everything else is a group of its own.
 */
internal fun floatingGroups(children: List<Int>, table: NodeTable): List<List<Int>> =
    capsuleRuns(children) { table.gathersIntoCapsule(it) }

/**
 * [items] split into what floats together: each run of items that [gathers] says share a
 * capsule is one group, and every other item is a group of its own.
 */
internal fun <T> capsuleRuns(items: List<T>, gathers: (T) -> Boolean): List<List<T>> {
    val groups = mutableListOf<List<T>>()
    var run = mutableListOf<T>()
    for (item in items) {
        if (gathers(item)) {
            run.add(item)
            continue
        }
        if (run.isNotEmpty()) {
            groups.add(run)
            run = mutableListOf()
        }
        groups.add(listOf(item))
    }
    if (run.isNotEmpty()) groups.add(run)
    return groups
}

/**
 * Whether a bar child is a plain action, which floats in a capsule shared with its
 * neighbours.
 *
 * A button that paints no fill of its own, and a menu, whose anchor is one. A button that
 * fills itself is already a capsule, and one drawn inside another is a pill in a pill.
 */
internal fun NodeTable.gathersIntoCapsule(childId: Int): Boolean {
    val child = node(childId) ?: return false
    return when (child.widget) {
        WidgetKind.Button -> when (child.variant()) {
            ButtonVariant.Text, ButtonVariant.Tonal, ButtonVariant.Outlined -> true
            ButtonVariant.Filled, ButtonVariant.Operator -> false
        }
        WidgetKind.Menu -> true
        else -> false
    }
}

/** The thinnest line that still draws on every density. */
private val HAIRLINE = 1.dp

/**
 * The window's title, as its own bar draws it.
 *
 * Its rung is the design system's, not the application's: a window title is the one piece
 * of text in a bar whose size is decided by the platform rather than by what it says.
 */
@Composable
private fun BarTitle(node: Node, title: String, theme: ResolvedTheme) {
    ThemedText(
        text = title,
        style = node.textStyle(theme, TypeRole.Subtitle),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Where a bar's trailing actions begin: the run of `Button` and `Menu` children at its end.
 *
 * A weighted child ends the run, and so does anything that is not an action. What comes
 * before the run (a back button, a title, a spacer, a row the application built) is the
 * bar's leading content and keeps its place; only the actions in the run can move into the
 * overflow menu.
 */
internal fun NodeTable.trailingActionsStart(children: List<Int>): Int {
    var start = children.size
    while (start > 0 && isBarAction(children[start - 1])) start--
    return start
}

private fun NodeTable.isBarAction(childId: Int): Boolean {
    val child = node(childId) ?: return false
    if (child.modifiers.weightOf() != null) return false
    return child.widget == WidgetKind.Button || child.widget == WidgetKind.Menu
}

/**
 * How many actions, counted from the first declared, a bar can draw in [available] pixels.
 *
 * [rowWidth] answers how wide the bar's actions are with the first `count` of them drawn
 * and, when `withAnchor` is true, the overflow button after them. All of them fitting needs
 * no overflow button; otherwise the longest prefix that fits beside the button wins. The
 * order is the declaration order because the schema has no priority to say otherwise, and
 * an application that wants an action kept to the last declares it first.
 */
internal fun fittingActions(count: Int, available: Int, rowWidth: (count: Int, withAnchor: Boolean) -> Int): Int {
    if (rowWidth(count, false) <= available) return count
    for (shown in count - 1 downTo 0) {
        if (rowWidth(shown, true) <= available) return shown
    }
    return 0
}

/**
 * What a bar remembers about its actions between frames.
 *
 * The widths are each action's own, measured the last time it was drawn in the bar, so the
 * bar can tell when one that went into the menu would fit again without drawing it twice.
 * A node is only ever drawn in one place: in the bar or in the menu.
 */
private class BarOverflowState {
    var shown by mutableIntStateOf(Int.MAX_VALUE)
    var open by mutableStateOf(false)
    val widths = HashMap<Int, Int>()
    var anchorWidth = 0
}

/**
 * A bar's trailing actions: as many as fit drawn in the bar at their own width, the rest in
 * a menu behind an overflow button.
 *
 * Laid out as one child of the bar's row, after the leading content, so the row gives it
 * whatever the leading content left over. A Compose `Row` hands each child what the ones
 * before it did not take, and when the actions were the row's own children the last ones
 * were measured at nothing and drawn as empty pills or not at all.
 *
 * [capsule] is set on a floating bar, which gathers each run of plain actions, the overflow
 * button among them, into one capsule of its material.
 */
@Composable
private fun BarActions(
    bar: Node,
    actions: List<Int>,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    gap: Dp,
    capsule: BarCapsule?,
) {
    val state = remember(bar.id) { BarOverflowState() }
    val shown = state.shown.coerceIn(0, actions.size)
    val inBar = actions.subList(0, shown)
    val overflowed = actions.subList(shown, actions.size)
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val capsulePx = capsule?.let { with(density) { it.padding.roundToPx() } } ?: 0
    val gathers = actions.map { table.gathersIntoCapsule(it) }

    fun measured(childId: Int): Modifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.widths[childId] = placeable.width
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val anchorMeasured = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.anchorWidth = placeable.width
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    // One the bar has not measured yet counts as nothing, so it is drawn on the next frame,
    // measured there, and the decision is made again with its real width.
    fun rowWidth(count: Int, withAnchor: Boolean): Int {
        val items = (0 until count).map { (state.widths[actions[it]] ?: 0) to gathers[it] } +
            (if (withAnchor) listOf(state.anchorWidth to true) else emptyList())
        if (items.isEmpty()) return 0
        if (capsule == null) return items.sumOf { it.first } + gapPx * (items.size - 1)
        val groups = capsuleRuns(items) { it.second }
        return groups.sumOf { group ->
            group.sumOf { it.first } + if (group.size == 1 && !group[0].second) 0 else 2 * capsulePx
        } + gapPx * (groups.size - 1)
    }

    Layout(
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (capsule == null) {
                    inBar.forEach { childId ->
                        key(childId) { RenderNode(childId, table, dispatcher, measured(childId)) }
                    }
                    if (overflowed.isNotEmpty()) {
                        OverflowButton(bar, overflowed, state, table, dispatcher, theme, anchorMeasured)
                    }
                } else {
                    // The overflow button is an action like the rest, so it shares the
                    // capsule of the plain actions just before it.
                    val items: List<Int?> = inBar + (if (overflowed.isNotEmpty()) listOf(null) else emptyList())
                    for (group in capsuleRuns(items) { it == null || table.gathersIntoCapsule(it) }) {
                        val only = group.singleOrNull()
                        if (only != null && !table.gathersIntoCapsule(only)) {
                            key(only) { RenderNode(only, table, dispatcher, measured(only)) }
                            continue
                        }
                        key(group.first() ?: OVERFLOW_KEY) {
                            FloatingCapsule(capsule) {
                                group.forEach { childId ->
                                    if (childId == null) {
                                        OverflowButton(bar, overflowed, state, table, dispatcher, theme, anchorMeasured)
                                    } else {
                                        key(childId) { RenderNode(childId, table, dispatcher, measured(childId)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // Each action at its own width: the row is measured unbounded, and only what fits
        // the bar's leftover width stays in it.
        val row = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val fits = fittingActions(actions.size, available, ::rowWidth)
        val settled = fits == shown
        if (!settled) state.shown = fits
        val width = row.width.coerceAtMost(available).coerceAtLeast(constraints.minWidth)
        layout(width, row.height) {
            // A frame whose actions are about to change is not drawn: what it holds is
            // wider than the bar, and drawing it would lay actions over the window's own
            // buttons for a frame.
            if (settled) row.placeRelative(0, 0)
        }
    }
}

/**
 * The button the overflow menu hangs from, and the menu.
 *
 * It is drawn as the design system draws a plain button with the overflow glyph and no word,
 * so it matches the icon actions beside it in every system. It is the bar's own button, not
 * a node the Host sent, so pressing it opens the menu here and nothing reaches the Host.
 * The actions in the menu are the same nodes the bar would have drawn, and choosing one is
 * that node's own click.
 */
@Composable
private fun OverflowButton(
    bar: Node,
    overflowed: List<Int>,
    state: BarOverflowState,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
    modifier: Modifier,
) {
    val button = remember(bar.id) { overflowButtonNode(bar.id) }
    val local = remember(dispatcher, button.id) {
        EventDispatcher { event ->
            if (event is HostEvent.Clicked && event.nodeId == button.id) {
                state.open = !state.open
                true
            } else {
                dispatcher.dispatch(event)
            }
        }
    }
    // A menu left open when everything fits again would come back open the next time
    // something overflowed.
    DisposableEffect(bar.id) { onDispose { state.open = false } }
    Box(modifier) {
        HostButton(
            button,
            Modifier
                .testTag(barOverflowTestTag(bar.id))
                .semantics { contentDescription = OVERFLOW_NAME },
            table,
            local,
            theme,
        )
        if (state.open) {
            OverflowMenu(bar, overflowed, state, table, dispatcher, theme)
        }
    }
}

@Composable
private fun OverflowMenu(
    bar: Node,
    overflowed: List<Int>,
    state: BarOverflowState,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.container(ContainerRole.Menu, theme)
    val surface = remember(bar.id) { Node(overflowButtonId(bar.id), WidgetKind.Menu) }
    val scope = rememberCoroutineScope()
    val close = remember(state) { { state.open = false } }
    Popup(
        popupPositionProvider = BelowAnchorPositionProvider,
        properties = PopupProperties(focusable = true),
        onDismissRequest = close,
    ) {
        // The same popup a `Menu` opens, for the same reasons: the recorded page does not
        // reach into another platform window, and its entries are a list, not a stack of
        // buttons.
        CompositionLocalProvider(LocalGlassBackdrop provides null, LocalInAMenu provides true) {
            Column(
                Modifier
                    .testTag(barOverflowMenuTestTag(bar.id))
                    .containerDecoration(surface, style, theme, overlay = true, role = ContainerRole.Menu)
                    .menuWidth(),
            ) {
                overflowed.forEach { childId ->
                    key(childId) {
                        // A menu among the overflowed actions opens a popup of its own from
                        // its anchor, so pressing it must leave this one open.
                        val entry = table.node(childId)
                        val closes = entry != null && entry.widget != WidgetKind.Menu &&
                            entry.flag(PropertyKind.Enabled, default = true)
                        Box(if (closes) Modifier.closesAfterChoice(scope, close) else Modifier) {
                            RenderNode(childId, table, dispatcher)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Closes the menu once its entry has been chosen.
 *
 * The entry's own click goes first. A tap is seen on the final pass, after the entry has
 * handled it on the main one, and a key is let through to the entry before the menu is
 * closed on the next turn of the loop.
 */
private fun Modifier.closesAfterChoice(scope: CoroutineScope, close: () -> Unit): Modifier =
    pointerInput(close) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.none { it.pressed }) break
            }
            close()
        }
    }.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar)
        ) {
            scope.launch { close() }
        }
        false
    }

/** The node the bar's overflow button is drawn from. It never reaches the Host. */
private fun overflowButtonNode(barId: Int): Node =
    Node(overflowButtonId(barId), WidgetKind.Button).apply {
        props[PropertyKind.Icon] = PropertyValue.Integer((IconRole.More.ordinal + 1).toLong())
        props[PropertyKind.Variant] = PropertyValue.Integer((ButtonVariant.Text.ordinal + 1).toLong())
        // Any handler: the bar answers the click itself.
        props[PropertyKind.OnClick] = PropertyValue.Integer(1L)
        // Named here rather than after the glyph, which would read "More".
        props[PropertyKind.AccessibleName] = PropertyValue.Text(OVERFLOW_NAME)
    }

/** Below every id the Host can send, which are positive, and different for each bar. */
private fun overflowButtonId(barId: Int): Int = -1 - barId

/** What assistive technology calls the bar's overflow button. */
internal const val OVERFLOW_NAME = "More actions"

private const val OVERFLOW_KEY = "overflow"

/** Test tag of a bar's overflow button. */
fun barOverflowTestTag(barId: Int): String = "${nodeTestTag(barId)}-overflow"

/** Test tag of the menu a bar's overflow button opens. */
fun barOverflowMenuTestTag(barId: Int): String = "${nodeTestTag(barId)}-overflow-menu"
