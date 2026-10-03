package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.style.TextOverflow
import dev.darkpyonix.composerust.design.CaptionTitleAlignment
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.ui.textStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import dev.darkpyonix.composerust.ui.weightOf
import dev.darkpyonix.composerust.design.liftsOffThePage

/**
 * The corner this container is drawn with: the one the Host asked for if it asked, and the
 * design system's corner for this kind of container otherwise.
 */
internal fun Node.containerShape(style: ContainerStyle, theme: ResolvedTheme): Shape =
    if (modifiers.any { it is ProtocolModifier.Shape || it is ProtocolModifier.ShapeRole }) {
        modifiers.resolvedShape(theme)
    } else {
        style.shape
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
): Modifier {
    val shape = node.containerShape(style, theme)
    val raised = if (node.setsOwnElevation()) {
        this
    } else {
        theme.rules.elevation(this, style.elevation, shape, theme)
    }
    // A Host that named its own Background has already had it applied, so painting the
    // design system's fill here would cover it. The corner, stroke and inner padding still
    // come from the system: only the colour is the application's.
    if (node.setsOwnBackground()) return raised
    val material = style.material
    val filled = if (material == null) {
        raised.clip(shape).background(style.container, shape)
    } else {
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
    return filled
        .then(
            if (style.borderWidth.value > 0f) {
                Modifier.border(style.borderWidth, style.borderColor, shape)
            } else {
                Modifier
            },
        )
        .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding)
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
    if (style.floats) {
        FloatingTopAppBar(node, modifier, table, dispatcher, theme, style)
        return
    }
    Column(modifier) {
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
                        node.children.forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                    }
                    BarTitle(node, title, theme)
                }
            } else {
                if (title.isNotEmpty()) {
                    BarTitle(node, title, theme)
                }
                node.children.forEach { childId ->
                    key(childId) { BarChild(childId, table, dispatcher) }
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
        for (group in floatingGroups(node.children, table)) {
            if (group.size == 1 && !table.gathersIntoCapsule(group[0])) {
                key(group[0]) { BarChild(group[0], table, dispatcher) }
                continue
            }
            key(group[0]) {
                Row(
                    Modifier
                        .glassLift(material, capsule)
                        .clip(capsule)
                        .glassSurface(material, capsule)
                        .padding(horizontal = theme.space(SpaceRole.Xs)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OnGlass(true) {
                        group.forEach { childId ->
                            key(childId) { RenderNode(childId, table, dispatcher) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bar's children split into what floats together: each run of plain actions is one
 * group, and everything else is a group of its own.
 */
internal fun floatingGroups(children: List<Int>, table: NodeTable): List<List<Int>> {
    val groups = mutableListOf<List<Int>>()
    var run = mutableListOf<Int>()
    for (childId in children) {
        if (table.gathersIntoCapsule(childId)) {
            run.add(childId)
            continue
        }
        if (run.isNotEmpty()) {
            groups.add(run)
            run = mutableListOf()
        }
        groups.add(listOf(childId))
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
    BasicText(
        text = title,
        style = node.textStyle(theme, TypeRole.Subtitle),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
