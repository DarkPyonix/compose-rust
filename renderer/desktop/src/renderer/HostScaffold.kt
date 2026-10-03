package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.FloatingActionPlacement
import dev.darkpyonix.composerust.design.NavigationPresentation
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SlotRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalStripTakesTheTop
import dev.darkpyonix.composerust.runtime.LocalWindowSizeClass
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.RenderNode
import dev.darkpyonix.composerust.design.rememberGlassBackdropState
import dev.darkpyonix.composerust.design.recordsGlassBackdrop
import dev.darkpyonix.composerust.design.LocalGlassBackdrop
import androidx.compose.runtime.CompositionLocalProvider

/**
 * How a screen's frame is arranged at one window width.
 *
 * A pure description so it can be tested without a window. What the frame is made of
 * comes from the slots an application filled; what it becomes is this.
 */
internal enum class ScaffoldFrame {
    /** Bars across the top and the bottom, the page between them. A phone. */
    Stacked,

    /** The destinations run down the leading edge beside the page. A tablet or a desktop. */
    SideBySide,

    /**
     * The destinations are over the page rather than in the frame, and only when asked
     * for. The page has the whole window.
     */
    Overlaid,
}

/**
 * Which frame a window of this width gets.
 *
 * Read off the navigation presentation rather than decided again here. The design system
 * has already answered "bar, rail or drawer" for this width, and a frame that disagreed
 * with it would put a rail down the side and still leave room for a bar at the bottom.
 *
 * [destinationsCanTurn] is what the bottom slot holds. `Navigation` is one declaration
 * that the renderer draws as a bar, a rail or a drawer, so a wide window can stand it on
 * end. A bar an application drew itself is a row, and a row laid down the leading edge is
 * a row on its side: it keeps its own width, takes the page's, and leaves a screen with a
 * strip of icons across the top and nothing under it. That is what the shop and the
 * podcast player drew the first time this ran.
 */
internal fun scaffoldFrame(
    presentation: NavigationPresentation,
    destinationsCanTurn: Boolean,
): ScaffoldFrame =
    when {
        !destinationsCanTurn -> ScaffoldFrame.Stacked
        presentation == NavigationPresentation.Bar -> ScaffoldFrame.Stacked
        // Neither beside the page nor under it. A frame that stood it beside the page left
        // the width the destinations would have had as an empty band, and the page came
        // out pushed off centre by a strip that was not on the screen.
        presentation == NavigationPresentation.PutAway -> ScaffoldFrame.Overlaid
        else -> ScaffoldFrame.SideBySide
    }

/**
 * Where the one action a screen is about goes, for a design system that has no opinion of
 * its own.
 *
 * It floats over the page where there is room below for a thumb to reach it, and moves
 * to the top where the window is wide and the pointer is already up there. Compact is the
 * phone case and the only one that floats. A system that answers differently says so in
 * its own `floatingAction` rule, and the frame follows that answer rather than this one.
 */
internal fun floatingActionFloats(sizeClass: WindowSizeClass): Boolean =
    sizeClass == WindowSizeClass.Compact

/**
 * The screen's frame.
 *
 * The application filled slots and said nothing about what they become. This is where
 * that is answered, from the design system and the width the window was measured at, the
 * same two things `Navigation` already answers "bar, rail or drawer" from.
 *
 * The page is laid out clear of whatever the other slots took, which is the part an
 * application must not compute for itself: it would be wrong the moment the frame chose
 * differently.
 */
@Composable
internal fun HostScaffold(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val sizeClass = LocalWindowSizeClass.current

    val slots = node.children.mapNotNull { id ->
        val child = table.node(id) ?: return@mapNotNull null
        if (child.widget != WidgetKind.ScaffoldSlot) return@mapNotNull null
        slotOf(child)?.let { it to child }
    }.toMap()

    val topBar = slots[SlotRole.TopBar]
    val bottomBar = slots[SlotRole.BottomBar]
    val navigation = theme.rules.navigation(sizeClass, theme)
    val holdsNavigation = bottomBar?.let { holdsANavigation(it, table) } == true
    val frame = scaffoldFrame(navigation.presentation, destinationsCanTurn = holdsNavigation)
    val floatingAction = slots[SlotRole.FloatingAction]
    val content = slots[SlotRole.Content]
    // Where the action goes is the design system's answer, asked once for this width. A
    // frame with no action never asks.
    val action = floatingAction?.let { theme.rules.floatingAction(sizeClass, theme) }
    val actionFloats = action?.placement == FloatingActionPlacement.OverPageBottomEnd
    val actionAtStart = action?.placement == FloatingActionPlacement.BarStart
    val actionAtEnd = action?.placement == FloatingActionPlacement.BarEnd

    @Composable
    fun slot(child: Node?, slotModifier: Modifier = Modifier) {
        if (child == null) return
        child.children.forEach { grandchild ->
            RenderNode(grandchild, table, dispatcher, slotModifier)
        }
    }

    @Composable
    fun page(pageModifier: Modifier) {
        Box(pageModifier) {
            slot(content, Modifier.fillMaxSize())
            if (action != null && actionFloats) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(action.inset),
                ) { slot(floatingAction) }
            }
        }
    }

    // The destinations' strip is only a strip, so the page colour the design system gives
    // a page with destinations is painted here, once, behind the whole frame. Where the
    // strip sits straight on the window's own backdrop instead, the page is painted only
    // beside it.
    val backdrop = holdsNavigation && navigation.pageBehindStrip
    val besideBackdrop = holdsNavigation && !navigation.pageBehindStrip

    // What the chrome is a lens over. The page records itself into it and the bars and the
    // strips read it back, blurred, through their own outlines. Provided here, above both,
    // because the two are siblings: a local given inside the page would not reach a bar,
    // and a bar inside the recording would be blurring itself.
    val backdropState = rememberGlassBackdropState()

    // A rail or a sidebar that runs to the top of the window. The top bar stops where the
    // strip starts rather than running across it, because the strip is what the window
    // buttons sit on and the bar's actions float over the page beside it, on the same
    // line as the buttons.
    CompositionLocalProvider(LocalGlassBackdrop provides backdropState) {
    if (frame == ScaffoldFrame.SideBySide && navigation.carriesCaption && LocalStripTakesTheTop.current) {
        Row(modifier.fillMaxSize().then(if (backdrop) Modifier.pageBackdrop(navigation) else Modifier)) {
            slot(bottomBar)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (besideBackdrop) Modifier.pageBackdrop(navigation) else Modifier),
            ) {
                PageBesideStrip(navigation, opensWithABar = topBar != null) {
                    Column(Modifier.fillMaxSize()) {
                        if (topBar != null) {
                            Row(Modifier.fillMaxWidth()) { slot(topBar) }
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            if (action != null && actionAtStart) {
                                Box(Modifier.align(Alignment.Top).padding(action.inset)) {
                                    slot(floatingAction)
                                }
                            }
                            page(Modifier.weight(1f).fillMaxHeight().recordsGlassBackdrop(backdropState))
                        }
                    }
                }
            }
            if (action != null && actionAtEnd) {
                Box(Modifier.align(Alignment.Top).padding(action.inset)) {
                    slot(floatingAction)
                }
            }
        }
        return@CompositionLocalProvider
    }

    Box(modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().then(
            if (holdsNavigation) Modifier.pageBackdrop(navigation) else Modifier,
        ),
    ) {
        // The top bar spans the window at every width, above the destinations as well as
        // above the page. A bar that stopped at the rail would read as a panel heading
        // rather than as the window's own bar, and on the one platform that hands its
        // caption to the application it is the window's caption.
        Row(Modifier.fillMaxWidth()) { slot(topBar) }

        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (frame == ScaffoldFrame.SideBySide) {
                slot(bottomBar)
            }
            // A system that puts the action at the head of its command bar or header bar
            // gets it at the leading end of the top of the page, and one that puts it with
            // the bar's actions gets it at the trailing end. Drawn here rather than inside
            // the bar so the bar stays whatever the application put in it.
            if (action != null && actionAtStart) {
                Box(Modifier.align(Alignment.Top).padding(action.inset)) {
                    slot(floatingAction)
                }
            }
            page(Modifier.weight(1f).fillMaxSize().recordsGlassBackdrop(backdropState))
            if (action != null && actionAtEnd) {
                Box(Modifier.align(Alignment.Top).padding(action.inset)) {
                    slot(floatingAction)
                }
            }
        }

        if (frame == ScaffoldFrame.Stacked) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) { slot(bottomBar) }
        }
    }
    // Over the page and taking none of it. The slot draws the button that brings the
    // destinations out and, while they are out, the strip and the press that dismisses it.
    if (frame == ScaffoldFrame.Overlaid) {
        slot(bottomBar)
    }
    }
    }
}

/**
 * Whether what fills a slot is a navigation, which is the one thing here that can be a
 * bar at one width and a strip down the edge at another.
 *
 * Only the slot's own children are looked at. A navigation nested somewhere inside a page
 * belongs to that part of the page, not to the window.
 */
internal fun holdsANavigation(slot: Node, table: NodeTable): Boolean =
    slot.children.any { table.node(it)?.widget == WidgetKind.Navigation }

/** Which slot a `ScaffoldSlot` node says it is. */
internal fun slotOf(node: Node): SlotRole? =
    (node.property(PropertyKind.Slot) as? PropertyValue.Integer)
        ?.value
        ?.toInt()
        ?.let { tag -> SlotRole.entries.getOrNull(tag - 1) }
