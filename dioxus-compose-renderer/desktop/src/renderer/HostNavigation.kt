package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import dioxus.compose.design.glassLift
import dioxus.compose.design.glassSurface
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.runtime.LocalStripTakesTheTop
import dioxus.compose.runtime.LocalWindowCaption
import androidx.compose.ui.unit.Dp
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.opensWithABar
import dioxus.compose.ui.node.OnGlass
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dioxus.compose.design.NavigationExtent
import dioxus.compose.design.NavigationIndicator
import dioxus.compose.design.NavigationPresentation
import dioxus.compose.design.NavigationStyle
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.IconRole
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.runtime.LocalSystemBars
import dioxus.compose.runtime.LocalWindowSizeClass
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.paintProp
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.role
import dioxus.compose.ui.textStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableStateOf
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SlotRole
import dioxus.compose.protocol.TypeRole
import androidx.compose.ui.text.TextStyle
import dioxus.compose.design.fontSize
import dioxus.compose.design.composeLineHeight
import dioxus.compose.design.composeLetterSpacing

/** How thick the bar Fluent draws along the leading edge of the selected row is. */
private val LEADING_BAR_THICKNESS = 3.dp

/**
 * How long that bar is.
 *
 * A fixed length rather than a fraction of the row: the rows sit in a scrolling column,
 * so the height they are offered is unbounded and a fraction of it comes out as nothing.
 */
private val LEADING_BAR_EXTENT = 20.dp

/** How faint a destination that cannot be chosen is drawn. */
private const val DISABLED_ALPHA = 0.38f

/**
 * A set of destinations and the screen they lead to.
 *
 * The children that are `NavigationItem` are the destinations and everything else is the
 * content. Splitting by kind rather than by a count the Host sends means a destination that
 * only exists under some condition cannot put the two lists out of step.
 *
 * Which of the three presentations this is comes from the design system, which is asked
 * about the size class the window is in. That is the whole of the responsive claim: the
 * Host sent one declaration and never learned how wide the window was.
 *
 * The selection is this side's state. `SelectedIndex` seeds it and moves it when the change
 * came from the Host; choosing a destination moves it here and then reports it as that
 * destination's own click, so switching screens costs one event and no rebuilt strip.
 */
/**
 * Whether this child is a slot filling [role] of the strip it is in.
 *
 * The same widget and the same property a `Scaffold`'s slots use, because the meaning is
 * the same one: a slot says where its content goes inside its parent. A strip has two
 * places to put something, above its destinations and below them.
 */
private fun Node?.fillsStrip(role: SlotRole): Boolean =
    this != null &&
        widget == WidgetKind.ScaffoldSlot &&
        this.role(PropertyKind.Slot, SlotRole.entries.toTypedArray()) == role

@Composable
internal fun HostNavigation(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.navigation(LocalWindowSizeClass.current, theme)
    val destinations = node.children.filter { table.node(it)?.widget == WidgetKind.NavigationItem }
    // A slot names where its content goes inside its parent, and inside a strip that means
    // above the destinations or below them. Everything else that is not a destination is
    // the screen, which is what it always was.
    val head = node.children.firstOrNull { table.node(it).fillsStrip(SlotRole.TopBar) }
    val foot = node.children.firstOrNull { table.node(it).fillsStrip(SlotRole.BottomBar) }
    val content = node.children.filter {
        val child = table.node(it)
        child?.widget != WidgetKind.NavigationItem &&
            !child.fillsStrip(SlotRole.TopBar) &&
            !child.fillsStrip(SlotRole.BottomBar)
    }
    val fromHost = (node.property(PropertyKind.SelectedIndex) as? PropertyValue.Integer)
        ?.value
        ?.toInt()
        ?: 0
    var selected by remember(node.id) { mutableIntStateOf(fromHost) }
    LaunchedEffect(node.id, fromHost) { selected = fromHost }

    val choose: (Int, Int) -> Unit = { index, childId ->
        selected = index
        val handlerId = table.node(childId)?.handler(PropertyKind.OnClick)
        if (handlerId != null) {
            dispatcher.dispatch(HostEvent.Clicked(childId, handlerId))
        }
    }

    // Where the platform has a strip of its own worth more than the one drawn here, it
    // gets the destinations and this side draws only the screen. The question is asked of
    // whatever is installed, and on the platforms where nothing is, the answer is no and
    // the code below is unchanged.
    //
    // Only the bar is offered. A rail and a drawer are laid out beside the screen and take
    // their width out of it, so handing them to a chrome that sits outside the Compose
    // surface would leave the screen the full window wide with the strip on top of it.
    //
    // And only a navigation that is a root of the tree. The platform's chrome belongs to
    // the window, and there is one of it: a navigation nested inside some part of the
    // screen would take the window's bar away from whatever owns it, and two of them would
    // take turns. A nested one keeps the bar drawn here, where it can sit inside the part
    // of the screen it actually belongs to.
    //
    // And only where Apple's design language was asked for. The chrome the shell stands up
    // is Apple's, drawn by Apple; putting it under an application that asked for Material 3
    // or Fluent would answer a question nobody asked. An application on this platform that
    // said nothing gets Apple's anyway, because the default theme follows the platform.
    val apple = theme.system == DesignSystem.Cupertino || theme.system == DesignSystem.LiquidGlass
    val offered = style.presentation == NavigationPresentation.Bar &&
        node.id in table.roots &&
        apple
    val shell = platformNavigationShell?.takeIf { it.drawsStrip && offered }

    // A message is drawn over the whole window, so it has to be told what the bar along
    // the bottom is using or it would cover the destinations.
    val barHeight = when {
        shell != null -> shell.stripHeight.dp
        // Including the strip it grows into, because a message placed above the bar has
        // to clear what is on the screen rather than what the design system nominally
        // asked for.
        style.presentation == NavigationPresentation.Bar ->
            style.barHeight + LocalSystemBars.current.bottom + style.floatingInset
        else -> 0.dp
    }
    DisposableEffect(table, barHeight) {
        table.insets.bottom = barHeight
        onDispose { table.insets.bottom = 0.dp }
    }

    if (shell != null) {
        val handed = destinations.mapNotNull { childId ->
            table.node(childId)?.let { destination ->
                ShellDestination(
                    nodeId = childId,
                    label = destination.text(PropertyKind.Text),
                    icon = destination.role(PropertyKind.Icon, IconRole.entries.toTypedArray()),
                    enabled = destination.flag(PropertyKind.Enabled, default = true),
                )
            }
        }
        // Two effects rather than one. Handing the destinations over happens again every
        // time they or the selection change, and tapping a destination changes the
        // selection, so putting the teardown in the same effect would take the strip down
        // and put it back up on every tap.
        DisposableEffect(shell, handed, selected) {
            shell.present(handed, selected) { index ->
                handed.getOrNull(index)?.let { choose(index, it.nodeId) }
            }
            onDispose {}
        }
        DisposableEffect(shell) {
            onDispose { shell.dismiss() }
        }
        // The strip along the bottom is not padded away: the screen runs under it, which
        // is what gives a bar made of glass something to refract. A title bar is padded
        // away, because it is opaque enough at the top that content under it is lost.
        Box(modifier.padding(top = shell.titleHeight.dp)) {
            Screen(content, table, dispatcher)
        }
        return
    }

    // The page's own colour belongs to the page, so a navigation that holds nothing but its
    // destinations paints none of it. That is what a frame does with this: the strip goes
    // in one slot and the frame paints the page behind both.
    val holdsAPage = content.isNotEmpty()
    // A sidebar the reader has put away. FR-21.2.1 makes being put away the Renderer's
    // state, and a sidebar that can only be put away by making the window narrower is one
    // the reader cannot put away at all: every sidebar this language is drawn from has a
    // button on it that does exactly this.
    var putAway by remember(node.id) { mutableStateOf(false) }
    val presentation = if (putAway && style.presentation == NavigationPresentation.Drawer) {
        NavigationPresentation.PutAway
    } else {
        style.presentation
    }
    when (presentation) {
        NavigationPresentation.Bar -> Column(
            modifier.then(if (holdsAPage) Modifier.pageBackdrop(style) else Modifier),
        ) {
            // Same reason as the rail below: with nothing to show above it, this is a
            // strip and not a screen with a strip under it.
            if (holdsAPage) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Screen(content, table, dispatcher)
                }
                style.separator?.let { line ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                }
            }
            BarStrip(node.id, destinations, selected, style, theme, table, choose)
        }

        // Not on the screen. The page has the whole window, and one button brings the
        // destinations back over it.
        //
        // Whether it is open is kept here and nowhere else: a sidebar being shown is not a
        // fact about the application, and the Host neither sets it nor hears about it.
        NavigationPresentation.PutAway -> {
            var open by remember(node.id) { mutableStateOf(false) }
            // Brought back for good rather than opened over the page, where the width is
            // there for it: a reader who put a sidebar away on a wide window and asked for
            // it again wants it back, not a panel that leaves as soon as it is used.
            val bringBack = { putAway = false }
            Box(modifier.then(if (holdsAPage) Modifier.pageBackdrop(style) else Modifier)) {
                if (holdsAPage) {
                    Screen(content, table, dispatcher)
                }
                // Over the page rather than beside it, and the page stays where it is
                // while it is open: choosing a destination is what changes the page, and a
                // page that disappeared and came back would hide what changed.
                if (open) {
                    // Anywhere else dismisses it, which is what every sidebar that can be
                    // put away does and the only way out on a screen with no keyboard.
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { open = false },
                    )
                    SideStrip(
                        node.id,
                        destinations,
                        selected,
                        style,
                        theme,
                        table,
                        dispatcher,
                        head,
                        foot,
                        onPutAway = null,
                    ) { index, id ->
                        open = false
                        choose(index, id)
                    }
                }
                PutAwayButton(
                    style = style,
                    theme = theme,
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    if (style.presentation == NavigationPresentation.Drawer) {
                        bringBack()
                    } else {
                        open = !open
                    }
                }
            }
        }

        NavigationPresentation.Rail, NavigationPresentation.Drawer -> Row(
            modifier.then(
                if (holdsAPage && style.pageBehindStrip) Modifier.pageBackdrop(style) else Modifier,
            ),
        ) {
            SideStrip(
                node.id,
                destinations,
                selected,
                style,
                theme,
                table,
                dispatcher,
                head,
                foot,
                onPutAway = { putAway = true },
                choose = choose,
            )
            // The rule and the room for a screen belong to the screen. A navigation
            // holding nothing but its destinations is a strip, and a strip that reserved
            // the rest of the window would leave whatever is beside it with no width at
            // all. That is what a frame does with this: the destinations go in one slot
            // and the page in another, and the two are laid out by the frame.
            if (holdsAPage) {
                style.separator?.let { line ->
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .then(if (style.pageBehindStrip) Modifier else Modifier.pageBackdrop(style)),
                ) {
                    PageBesideStrip(style, opensWithABar = table.opensWithABar(content)) {
                        Screen(content, table, dispatcher)
                    }
                }
            }
        }
    }
}

/**
 * The destinations along the bottom: a strip on the window's edge, or a capsule floating
 * over the page where the design system floats its bar.
 */
@Composable
private fun BarStrip(
    navigationId: Int,
    destinations: List<Int>,
    selected: Int,
    style: NavigationStyle,
    theme: ResolvedTheme,
    table: NodeTable,
    choose: (Int, Int) -> Unit,
) {
    val systemBottom = LocalSystemBars.current.bottom
    val material = style.stripMaterial
    @Composable
    fun Destinations(scope: RowScope) = with(scope) {
        destinations.forEachIndexed { index, childId ->
            key(childId) {
                table.node(childId)?.let { destination ->
                    Destination(
                        node = destination,
                        selected = index == selected,
                        style = style,
                        presentation = style.presentation,
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        onSelect = { choose(index, childId) },
                    )
                }
            }
        }
    }
    if (material == null || style.floatingInset <= 0.dp) {
        Row(
            Modifier
                .testTag(navigationStripTestTag(navigationId))
                .fillMaxWidth()
                .background(style.container)
                // The strip the system's gesture bar sits in belongs to this bar: its
                // own colour runs to the bottom edge of the window and the
                // destinations sit above the gesture bar rather than under it. A bar
                // that stopped short would leave a band of the system's own
                // background below it that no other application on the device has.
                .height(style.barHeight + systemBottom)
                .padding(bottom = systemBottom),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) { Destinations(this) }
        return
    }
    // Floating. The capsule is held off both sides and off the bottom, above the gesture
    // bar rather than grown into it, and the page's own colour runs on around it, which is
    // what the glass takes its colour from. The page's content stops above the capsule
    // rather than scrolling under it, so the last line of a list is never behind it.
    val inset = style.floatingInset
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset, end = inset, top = inset / 2, bottom = inset / 2 + systemBottom),
    ) {
        // The tag is on the capsule rather than on the room around it, so the strip a
        // test measures is the one on the screen.
        Row(
            Modifier
                .testTag(navigationStripTestTag(navigationId))
                .fillMaxWidth()
                .height(style.barHeight)
                .glassLift(material, style.stripShape)
                .clip(style.stripShape)
                .glassSurface(material, style.stripShape)
                .padding(horizontal = theme.space(SpaceRole.Xs)),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnGlass(true) { Destinations(this) }
        }
    }
}

/**
 * The name over a group of destinations.
 *
 * Set at the caption rung in the strip's own secondary ink, which is what both references
 * do: a heading is there to be found when it is looked for and to stay out of the way when
 * it is not, so it is smaller and quieter than the rows under it rather than louder.
 */
@Composable
private fun SectionHeading(name: String, style: NavigationStyle, theme: ResolvedTheme) {
    val token = theme.type(TypeRole.Caption)
    BasicText(
        text = name,
        style = TextStyle(
            fontSize = token.fontSize,
            fontFamily = theme.family(TypeRole.Caption),
            lineHeight = token.composeLineHeight,
            letterSpacing = token.composeLetterSpacing,
            color = style.headingContent ?: style.content.copy(alpha = SECTION_HEADING_ALPHA),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = style.destinationInset ?: style.itemPadding,
                end = style.destinationInset ?: style.itemPadding,
                // More room over a heading than under it, because what the room does is
                // end the group above rather than open the one below. At the same step top
                // and bottom the heading sat between two lists instead of over one, which
                // is what it looked like: measured, the reference leaves twenty five over
                // its headings and eleven under them.
                top = theme.space(SpaceRole.Lg),
                bottom = theme.space(SpaceRole.Xs),
            ),
    )
}

/**
 * How much quieter a heading is than the rows under it, where the design system does not
 * name a colour for one.
 *
 * A fraction of the row ink, which works while that ink is the secondary one and stops
 * working the moment it is not: with the rows set in the reading ink, seven tenths of it
 * came out 0x434343 against the reference's 0x6F7071, and a heading that dark competes
 * with the rows it heads instead of standing off them.
 */
private const val SECTION_HEADING_ALPHA = 0.7f

/** The hairline round a search field in a drawer. */
private val SEARCH_FIELD_EDGE = 1.dp

/**
 * The destinations down the leading edge: a rail or a drawer on the window's edge, or a
 * panel floating inside it where the design system floats them.
 *
 * A floating one that carries the caption runs to the top of the window, and its first
 * destination starts below the window buttons that sit on it.
 */
@Composable
private fun SideStrip(
    navigationId: Int,
    destinations: List<Int>,
    selected: Int,
    style: NavigationStyle,
    theme: ResolvedTheme,
    table: NodeTable,
    dispatcher: EventDispatcher,
    head: Int?,
    foot: Int?,
    /** What puts this strip away, or null where it is already the thing that came back. */
    onPutAway: (() -> Unit)?,
    choose: (Int, Int) -> Unit,
) {
    val width = if (style.presentation == NavigationPresentation.Rail) {
        style.railWidth
    } else {
        style.drawerWidth
    }
    val material = style.stripMaterial
    val floating = material != null && style.floatingInset > 0.dp
    val caption = LocalWindowCaption.current
    // The caption only reaches a strip that carries it; anywhere else the page above has
    // already taken it and the strip starts where it was put.
    val carries = style.carriesCaption && LocalStripTakesTheTop.current
    val captionTop = if (carries) caption.height + caption.insetTop else 0.dp
    @Composable
    fun Destinations() {
        head?.let { key(it) { Screen(listOf(it), table, dispatcher) } }
        var group: String? = null
        destinations.forEachIndexed { index, childId ->
            key(childId) {
                table.node(childId)?.let { destination ->
                    // A heading once, where the name changes. Neighbouring destinations
                    // carrying the same name are one group, so a name that comes back
                    // later is a second group with the same heading rather than a
                    // continuation of the first.
                    val named = destination.text(PropertyKind.Section).takeIf { it.isNotEmpty() }
                    if (named != null && named != group) {
                        SectionHeading(named, style, theme)
                    }
                    group = named
                    Destination(
                        node = destination,
                        selected = index == selected,
                        style = style,
                        presentation = style.presentation,
                        theme = theme,
                        modifier = Modifier.fillMaxWidth(),
                        onSelect = { choose(index, childId) },
                    )
                }
            }
        }
    }

    /**
     * What sits at the strip's foot, held there.
     *
     * Below the destinations and below the bottom of the list rather than after the last
     * row. An account row that follows the last conversation floats in the middle of the
     * panel with the rest of the strip empty under it, which is where this one was.
     */
    @Composable
    fun Foot() {
        foot?.let { key(it) { Screen(listOf(it), table, dispatcher) } }
    }
    val gap = style.destinationGap ?: style.itemSpacing
    val strip = style.stripPadding ?: style.itemPadding
    if (!floating) {
        Column(
            Modifier
                .testTag(navigationStripTestTag(navigationId))
                .width(width)
                .fillMaxHeight()
                .background(style.container)
                .padding(top = captionTop + strip, bottom = strip),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The destinations scroll and the foot does not. The scroll is on the list
            // rather than on the strip, so a long list runs under nothing and the foot
            // stays on the floor.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Destinations() }
            Foot()
        }
        return
    }
    val inset = style.floatingInset
    val shape = style.stripShape
    Box(
        Modifier
            .width(width + inset)
            .fillMaxHeight()
            .padding(start = inset, top = inset, bottom = inset + LocalSystemBars.current.bottom),
    ) {
        Column(
            Modifier
                .testTag(navigationStripTestTag(navigationId))
                .fillMaxSize()
                .glassLift(material!!, shape)
                .clip(shape)
                .glassSurface(material, shape)
                .padding(
                    // The panel starts [inset] down from the top of the window, and the
                    // window buttons sit on it, so its first row starts below them.
                    top = (captionTop - inset).coerceAtLeast(0.dp) + strip,
                    bottom = strip,
                    start = strip,
                    end = strip,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnGlass(true) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Destinations() }
                Foot()
            }
        }
        // Over the strip's own top trailing corner, which is where every sidebar this is
        // drawn from puts it: the thing that puts a panel away belongs on the panel.
        if (onPutAway != null) {
            PutAwayButton(
                style = style,
                theme = theme,
                modifier = Modifier.align(Alignment.TopEnd),
                fromTheCorner = false,
                onClick = onPutAway,
            )
        }
    }
}

/**
 * The page beside a strip that carries the caption.
 *
 * The strip took the top of the window, so the page has to keep its own content clear of
 * the caption. A page that opens with a bar hands the caption to the bar, which lays its
 * content out on the same line as the window buttons; the buttons are on the strip, so
 * the bar is told there is nothing of theirs to make room for.
 */
@Composable
internal fun PageBesideStrip(
    style: NavigationStyle,
    opensWithABar: Boolean,
    content: @Composable () -> Unit,
) {
    val caption = LocalWindowCaption.current
    val carries = style.carriesCaption && LocalStripTakesTheTop.current
    if (!carries || caption.height <= 0.dp && caption.insetTop <= 0.dp) {
        content()
        return
    }
    if (opensWithABar) {
        // Whatever of the window buttons does not fit on the strip still reaches into the
        // bar beside it, and a rail is narrower than some platforms' three buttons. Buttons
        // at the trailing end are on the bar's side of the window altogether.
        val strip = sideStripOuterWidth(style)
        val left = if (caption.buttonsAtStart) {
            (caption.buttonsWidth - strip).coerceAtLeast(0.dp)
        } else {
            caption.buttonsWidth
        }
        CompositionLocalProvider(LocalWindowCaption provides caption.copy(buttonsWidth = left)) {
            content()
        }
    } else {
        CompositionLocalProvider(LocalWindowCaption provides WindowCaption.None) {
            Box(Modifier.padding(top = caption.height + caption.insetTop)) { content() }
        }
    }
}

/** How much of the window's width a rail or a drawer takes, its inset included. */
internal fun sideStripOuterWidth(style: NavigationStyle): Dp {
    val width = if (style.presentation == NavigationPresentation.Rail) {
        style.railWidth
    } else {
        style.drawerWidth
    }
    return if (style.stripMaterial != null && style.floatingInset > 0.dp) {
        width + style.floatingInset
    } else {
        width
    }
}

/** The part of the tree that is not a destination: the screen the selection leads to. */
@Composable
private fun Screen(content: List<Int>, table: NodeTable, dispatcher: EventDispatcher) {
    content.forEach { childId -> key(childId) { RenderNode(childId, table, dispatcher) } }
}

/**
 * One destination.
 *
 * A bar and a rail stack the label under the icon; a drawer sets it beside, and is the only
 * one wide enough to do so. The mark on the selected one is the design system's: a filled
 * pill, a bar along the leading edge, or nothing but the colour of the label.
 */
@Composable
internal fun Destination(
    node: Node,
    selected: Boolean,
    style: NavigationStyle,
    presentation: NavigationPresentation,
    theme: ResolvedTheme,
    modifier: Modifier,
    onSelect: () -> Unit,
) {
    val label = node.text(PropertyKind.Text)
    val role = node.role(PropertyKind.Icon, IconRole.entries.toTypedArray())
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val search = role == IconRole.Search &&
        presentation == NavigationPresentation.Drawer &&
        style.searchContainer != null
    // The design system decides what "selected" looks like, unless the node names a
    // colour itself. A unified sample is what needs the exception: its reference bar is
    // white icons on black with no accent anywhere, and asking the active system instead
    // puts its own accent on the selected one.
    //
    // The named colour is used for both states. A destination that says what colour it is
    // is saying it about itself, not about half of itself, and a sample wanting the two
    // states apart says so by giving each destination its own colour.
    val named = node.paintProp(PropertyKind.Color)?.let { theme.color(it) }
    val tint = named ?: if (selected && !search) style.selectedContent else style.content
    val showLabel = label.isNotEmpty() &&
        (presentation != NavigationPresentation.Rail || style.labelInRail)
    val pill = selected && !search && style.indicatorKind == NavigationIndicator.Pill
    val bar = selected && !search && style.indicatorKind == NavigationIndicator.LeadingEdgeBar

    Box(
        modifier
            .testTag(nodeTestTag(node.id))
            .then(
                if (search) {
                    Modifier
                        .clip(style.indicatorShape)
                        .background(style.searchContainer)
                        .then(
                            style.searchOutline?.let { edge ->
                                Modifier.border(SEARCH_FIELD_EDGE, edge, style.indicatorShape)
                            } ?: Modifier,
                        )
                } else {
                    Modifier
                },
            )
            // `selectable` rather than `clickable`: one of a set is chosen, and saying so
            // is what puts "selected" in the accessibility tree instead of leaving a
            // screen reader to announce every destination identically.
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onSelect)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        // The mark is drawn behind the content rather than around it, so selecting a
        // destination never changes how much room it takes and the strip does not shift.
        if (pill) {
            Box(
                indicatorSize(presentation, style.indicatorExtent)
                    .clip(style.indicatorShape)
                    .background(style.indicator),
            )
        }
        if (bar) {
            LeadingBar(presentation, style)
        }
        if (presentation == NavigationPresentation.Drawer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        style.destinationHeight?.let { Modifier.height(it) } ?: Modifier,
                    )
                    .padding(
                        horizontal = style.destinationInset ?: style.itemPadding,
                        vertical = style.itemPadding,
                    ),
                horizontalArrangement = Arrangement.spacedBy(style.itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DestinationIcon(role, tint, theme)
                DestinationLabel(node, label, showLabel, tint, style, theme)
            }
        } else {
            Column(
                Modifier.padding(horizontal = style.itemPadding, vertical = style.itemPadding),
                verticalArrangement = Arrangement.spacedBy(style.itemSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DestinationIcon(role, tint, theme)
                DestinationLabel(node, label, showLabel, tint, style, theme)
            }
        }
    }
}

/**
 * The page's own colour, where the design system gives the page a gradient.
 *
 * Painted once, by whatever holds the page: a navigation that holds its screen, or the
 * frame a navigation strip was put in. Painting it twice would lay a translucent gradient
 * over itself.
 */
internal fun Modifier.pageBackdrop(style: NavigationStyle): Modifier {
    val start = style.pageGradientStart ?: return this
    val end = style.pageGradientEnd ?: return this
    val hold = style.pageGradientHold.coerceIn(0f, 0.99f)
    val wash = Brush.verticalGradient(0f to start, hold to start, 1f to end)
    val glow = style.pageCornerGlow ?: return background(wash)
    return drawBehind {
        drawRect(wash)
        // Laid over the ramp rather than folded into it, because the two run in different
        // directions: the ramp turns from top to bottom and these spread from a point, and
        // one brush cannot do both.
        glow(glow, Offset(0f, size.height), size.width * LEADING_GLOW_WIDE, size.height * LEADING_GLOW_TALL)
        glow(glow, Offset(size.width, size.height), size.width * TRAILING_GLOW_WIDE, size.height * TRAILING_GLOW_TALL)
    }
}

/**
 * One corner's glow: the wash's own colour, at full strength where it is anchored and gone
 * at the edge of an ellipse.
 *
 * An ellipse rather than a circle, and a different one at each corner, because that is what
 * is there to copy. Measured across the reference, the leading corner's reach is wide and
 * shallow and the trailing corner's is narrow and tall; a pair of circles draws a wash that
 * is symmetrical, which reads as a shape laid on the page rather than as light in a room.
 */
private fun DrawScope.glow(core: Color, at: Offset, wide: Float, tall: Float) {
    val radius = maxOf(wide, tall)
    if (radius <= 0f) return
    // Falling away fast and then trailing, rather than evenly. An even radial puts half
    // the colour at half the reach, and measured against the reference that came out
    // twenty levels too deep across the middle of the page while the height the wash began
    // at was right: the arc is drawn by the last of the light, so what sets where it starts
    // is the tail and what sets how the page reads is the near half.
    val brush = Brush.radialGradient(
        colorStops = arrayOf(
            0f to core,
            GLOW_KNEE to core.copy(alpha = GLOW_KNEE_ALPHA),
            1f to core.copy(alpha = 0f),
        ),
        center = at,
        radius = radius,
    )
    scale(wide / radius, tall / radius, pivot = at) {
        drawCircle(brush, radius = radius, center = at)
    }
}

/**
 * How far each corner's glow reaches, as a fraction of the window's width and height.
 *
 * Fitted to the height the reference's wash begins at, read off nine columns across the
 * window: the two ellipses those points lie on come out at about eleven twentieths of the
 * width by a third of the height at the leading corner, and a little under a quarter of the
 * width by three sevenths of the height at the trailing one.
 */
/** Where the glow stops falling away quickly, and how much of it is left there. */
private const val GLOW_KNEE = 0.35f
private const val GLOW_KNEE_ALPHA = 0.35f

private const val LEADING_GLOW_WIDE = 0.55f
private const val LEADING_GLOW_TALL = 0.33f
private const val TRAILING_GLOW_WIDE = 0.23f
private const val TRAILING_GLOW_TALL = 0.43f

@Composable
private fun DestinationIcon(role: IconRole?, tint: Color, theme: ResolvedTheme) {
    if (role != null) {
        RoleIcon(role, tint, theme, Modifier)
    }
}

@Composable
private fun DestinationLabel(
    node: Node,
    label: String,
    show: Boolean,
    tint: Color,
    style: NavigationStyle,
    theme: ResolvedTheme,
) {
    if (!show) return
    BasicText(
        text = label,
        style = node.textStyle(theme, style.typeRole).copy(color = tint),
    )
}

/**
 * How big the mark behind the selected destination is.
 *
 * A system that covers the whole destination is matched to it rather than given a size,
 * so the fill reaches the label the design system coloured to read on it. The systems
 * that mark the icon alone keep a fixed mark: a drawer's spans the row, a bar's or a
 * rail's sits behind the icon with the label under it.
 */
@Composable
private fun BoxScope.indicatorSize(
    presentation: NavigationPresentation,
    extent: NavigationExtent,
): Modifier = when {
    extent == NavigationExtent.Destination -> Modifier.matchParentSize()
    presentation == NavigationPresentation.Drawer -> Modifier.fillMaxWidth().height(40.dp)
    else -> Modifier.size(56.dp, 34.dp)
}

/** Fluent's mark: a short bar along the edge the row starts at. */
@Composable
private fun BoxScope.LeadingBar(
    presentation: NavigationPresentation,
    style: NavigationStyle,
) {
    if (presentation == NavigationPresentation.Bar) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(LEADING_BAR_EXTENT)
                .height(LEADING_BAR_THICKNESS)
                .clip(style.indicatorShape)
                .background(style.indicator),
        )
    } else {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(LEADING_BAR_THICKNESS)
                .height(LEADING_BAR_EXTENT)
                .clip(style.indicatorShape)
                .background(style.indicator),
        )
    }
}

/** A destination outside a `Navigation` still draws, unselected and unmarked. */
@Composable
internal fun HostNavigationItem(
    node: Node,
    modifier: Modifier,
    theme: ResolvedTheme,
) {
    val style = theme.rules.navigation(LocalWindowSizeClass.current, theme)
    Destination(
        node = node,
        selected = false,
        style = style.copy(indicator = Color.Transparent),
        presentation = NavigationPresentation.Drawer,
        theme = theme,
        modifier = modifier,
        onSelect = {},
    )
}

/** Test tag of the strip of destinations, which is the half that changes shape. */
fun navigationStripTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-strip"

/**
 * The button that brings a put-away navigation back.
 *
 * Placed after the window's own buttons where the platform hands its caption over, because
 * that is where every sidebar this is drawn from puts it, and dropping it on top of the
 * traffic lights is the one place it cannot go.
 */
@Composable
private fun PutAwayButton(
    style: NavigationStyle,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    /** True where it sits at the window's own leading corner, beside the platform's buttons. */
    fromTheCorner: Boolean = true,
    onClick: () -> Unit,
) {
    val caption = LocalWindowCaption.current
    val leading = if (fromTheCorner && caption.buttonsAtStart) caption.buttonsWidth else 0.dp
    val padding = theme.space(SpaceRole.Sm)
    Box(
        modifier
            .padding(
                start = leading + padding,
                end = padding,
                top = if (fromTheCorner) caption.insetTop + padding else padding,
            )
            .size(PUT_AWAY_BUTTON)
            .clip(theme.shape(ShapeRole.Small))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The sidebar's own glyph rather than the three lines. Three lines mean "a menu
        // of things", and this is not that: it puts a panel away and brings it back.
        RoleIcon(IconRole.Sidebar, style.content, theme, Modifier)
    }
}

/** How big that button is. A caption's height, so it sits on the caption's line. */
private val PUT_AWAY_BUTTON = 28.dp
