package dioxus.compose.ui.node

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.design.ContainerRole
import dioxus.compose.design.ToggleRole
import dioxus.compose.design.LocalDesignTheme
import dioxus.compose.design.LocalGlassDepth
import dioxus.compose.design.SurfaceMaterial
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.foundation.HostBadge
import dioxus.compose.foundation.HostButton
import dioxus.compose.foundation.HostLazyGrid
import dioxus.compose.foundation.HostRichText
import dioxus.compose.foundation.HostScaffold
import dioxus.compose.foundation.HostContainerColumn
import dioxus.compose.foundation.HostDialog
import dioxus.compose.foundation.HostDivider
import dioxus.compose.foundation.HostProgressIndicator
import dioxus.compose.foundation.HostSlider
import dioxus.compose.foundation.HostToggle
import dioxus.compose.foundation.HostCanvas
import dioxus.compose.foundation.HostDatePicker
import dioxus.compose.foundation.HostDropdown
import dioxus.compose.foundation.HostIcon
import dioxus.compose.foundation.HostImage
import dioxus.compose.foundation.HostTimePicker
import dioxus.compose.foundation.HostLazyColumn
import dioxus.compose.foundation.HostLazyRow
import dioxus.compose.foundation.HostMenu
import dioxus.compose.foundation.HostNavigation
import dioxus.compose.foundation.HostNavigationItem
import dioxus.compose.foundation.HostSheet
import dioxus.compose.foundation.HostTabs
import dioxus.compose.foundation.HostTooltip
import dioxus.compose.foundation.HostTopAppBar
import dioxus.compose.foundation.HostTextField
import dioxus.compose.foundation.hostKeyEvents
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.boxAlignment
import dioxus.compose.ui.horizontalAlignment
import dioxus.compose.ui.horizontalArrangement
import dioxus.compose.ui.maxLines
import dioxus.compose.ui.overflow
import dioxus.compose.ui.textStyle
import dioxus.compose.ui.toComposeModifier
import dioxus.compose.ui.verticalAlignment
import dioxus.compose.ui.verticalArrangement
import dioxus.compose.ui.weightOf

/**
 * Counts recompositions of interpreted nodes, so a test can assert that changing one node
 * does not recompose its siblings. Set only by tests.
 */
internal object RenderNodeObserver {
    var onCompose: ((Int) -> Unit)? = null
}

/** Test tag every interpreted node carries, so tests can address nodes by Host node id. */
fun nodeTestTag(nodeId: Int): String = "dioxus-node-$nodeId"

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
    val chain = parentModifier
        .then(node.modifiers.toComposeModifier(nodeId, dispatcher, theme))
        .testTag(nodeTestTag(nodeId))
    // The TextField wires its own key handling, because it has an editor to intercept and a
    // composition to protect; everything else routes keys here.
    val keyDownHandler = node.handler(PropertyKind.OnKeyDown)
    val withKeys = if (keyDownHandler == null || node.widget == WidgetKind.TextField) {
        chain
    } else {
        chain.hostKeyEvents(nodeId, keyDownHandler, dispatcher)
    }
    // Only where the node said it is a place files may be dropped. Everywhere else this
    // adds nothing to the chain and the platform shows no drop cursor.
    val modifier = platformFileDrop(withKeys, node, dispatcher)
    // A layout made of glass tells what is inside it so. A field in a glass bar is part of
    // the bar in the systems that draw glass, and it can only take that shape if it knows
    // what it is sitting on.
    val glass = node.isMadeOfGlass(theme)
    when (node.widget) {
        WidgetKind.Column -> Column(
            modifier = modifier,
            verticalArrangement = node.verticalArrangement(theme),
            horizontalAlignment = node.horizontalAlignment(),
        ) { OnGlass(glass) { Children(node, table, dispatcher) } }

        WidgetKind.Row -> Row(
            modifier = modifier,
            horizontalArrangement = node.horizontalArrangement(theme),
            verticalAlignment = node.verticalAlignment(),
        ) { OnGlass(glass) { Children(node, table, dispatcher) } }

        // A place files may be dropped is laid out as a plain box. Being this widget is
        // the whole of the difference: the drop target is set up for it and for nothing
        // else in the tree.
        WidgetKind.Box, WidgetKind.FileDropTarget ->
            Box(modifier, contentAlignment = node.boxAlignment()) {
            OnGlass(glass) { Children(node, table, dispatcher) }
        }

        // One widget whether or not it carries runs. A paragraph with a bold phrase in
        // it is one piece of text, and three widgets would wrap at the seams.
        WidgetKind.Text -> HostRichText(node, modifier, dispatcher, theme)

        WidgetKind.Spacer -> Spacer(modifier)
        WidgetKind.Button -> HostButton(node, modifier, dispatcher, theme)
        WidgetKind.TextField -> HostTextField(node, modifier, dispatcher)
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
        WidgetKind.ScrollColumn -> Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = node.verticalArrangement(theme),
            horizontalAlignment = node.horizontalAlignment(),
        ) { Children(node, table, dispatcher) }

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
        WidgetKind.Tabs -> HostTabs(node, modifier, table, dispatcher, theme)
        WidgetKind.Tooltip -> HostTooltip(node, modifier, table, dispatcher, theme)
        WidgetKind.LazyRow -> HostLazyRow(node, modifier, table, dispatcher)

        // The selection controls and the indicators. Each one is drawn from the state it
        // was sent: the tick, the track, the thumb, the sweep and the weight of a rule are
        // the design system's, so the same declaration is a Material control here and a
        // Cupertino one there.
        WidgetKind.Checkbox ->
            HostToggle(ToggleRole.Checkbox, node, modifier, dispatcher, theme)

        WidgetKind.RadioButton ->
            HostToggle(ToggleRole.RadioButton, node, modifier, dispatcher, theme)

        WidgetKind.Switch -> HostToggle(ToggleRole.Switch, node, modifier, dispatcher, theme)
        WidgetKind.Slider -> HostSlider(node, modifier, dispatcher, theme)
        WidgetKind.ProgressIndicator -> HostProgressIndicator(node, modifier, theme)
        WidgetKind.Divider -> HostDivider(node, modifier, theme)

        // A picture is one registered id. The bytes were read when the Host registered
        // them, so what a frame carries is the id and a lookup.
        WidgetKind.Image -> HostImage(node, modifier, table.assets, dispatcher)
        WidgetKind.Icon -> HostIcon(node, modifier, table.assets, dispatcher, theme)

        // The pickers carry a value, a range and a change handler. Which way of picking the
        // user gets, a calendar grid, a wheel, a dial or a flyout, is the design system's
        // decision, and there is no property that could ask for one of them.
        WidgetKind.DatePicker -> HostDatePicker(node, modifier, dispatcher, theme)
        WidgetKind.TimePicker -> HostTimePicker(node, modifier, dispatcher, theme)
        WidgetKind.Dropdown -> HostDropdown(node, modifier, table, dispatcher, theme)

        // One declaration, three presentations. Which one this is comes from the design
        // system, asked about the size class this window is in, so the Host that declared
        // it never learned how wide the window was.
        WidgetKind.Navigation -> HostNavigation(node, modifier, table, dispatcher, theme)

        // Normally drawn by the Navigation it belongs to, which knows whether it is the
        // selected one. On its own it is a destination nobody has chosen.
        WidgetKind.NavigationItem -> HostNavigationItem(node, modifier, theme)

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

        // A count, a word or a dot, on its child or on its own. Where it sits and how a
        // large count is written are the design system's.
        WidgetKind.Badge -> HostBadge(node, modifier, table, dispatcher, theme)

        // Interpreted once text selection lands. Until then it lays its children out
        // as a plain column, so nothing inside is lost.
        WidgetKind.SelectionContainer -> Column(modifier) { Children(node, table, dispatcher) }
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
    -> StackingAxis.Vertical

    WidgetKind.Row,
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
private fun RowScope.Children(node: Node, table: NodeTable, dispatcher: EventDispatcher) {
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

/** A Box has no weight axis, so its children are drawn as they are. */
@Composable
private fun BoxScope.Children(node: Node, table: NodeTable, dispatcher: EventDispatcher) {
    node.children.forEach { childId -> key(childId) { RenderNode(childId, table, dispatcher) } }
}

/** The label of a Button follows the design system's button type role unless overridden. */
internal fun Node.buttonTextStyle(theme: ResolvedTheme, role: TypeRole) = textStyle(theme, role)
