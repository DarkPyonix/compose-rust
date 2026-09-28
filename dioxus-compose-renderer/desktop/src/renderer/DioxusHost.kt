package dioxus.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import dioxus.compose.design.NavigationPresentation
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dioxus.compose.foundation.HostMessages
import dioxus.compose.foundation.systemChrome
import androidx.compose.ui.graphics.Color
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.WindowSizeClass
import dioxus.compose.ui.platform.LocalFrameRequests
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import androidx.compose.runtime.SideEffect
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SlotRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.design.CaptionSide
import dioxus.compose.design.LocalDesignTheme
import dioxus.compose.design.LocalReduceTransparency
import dioxus.compose.design.detectHostPlatform
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.MaterialRole
import dioxus.compose.ui.node.Asset
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import dioxus.compose.ui.node.platformWindowMaterial
import dioxus.compose.ui.node.TableError
import java.lang.InterruptedException
import java.lang.System
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * Sends one event to the Host and reports whether the Host consumed it.
 *
 * Consumption is the same model as the web's `preventDefault()` and Compose's
 * `PointerInputChange.consume()`: the handler marks the event, and the boundary returns that
 * mark in `MutationBatch.result`. The Renderer treats a non-zero result as "consumed", so a
 * Host that always returns 0 simply never consumes. It is what lets Enter in a multiline
 * field submit without also inserting a newline.
 */
fun interface EventDispatcher {
    fun dispatch(event: HostEvent): Boolean
}

/**
 * Owns the interpreted tree and the boundary calls for one Host.
 *
 * The calls are synchronous and on this thread: there is no queue between the two sides, and
 * a batch is consumed inside the call that produced it.
 *
 * Every batch is applied inside a single `Snapshot.withMutableSnapshot` transaction, so the
 * intermediate states of a batch are never drawn.
 */
class DioxusHost(private val connection: HostConnection) : EventDispatcher {
    val table: NodeTable = NodeTable()

    val roots: List<Int> get() = table.roots

    fun start() {
        applyTransaction { apply -> connection.init(apply) }
    }

    /** Dispatches synchronously and returns the Host's consumption result. */
    override fun dispatch(event: HostEvent): Boolean {
        var result = 0L
        applyTransaction { apply -> result = connection.dispatchEvent(event, apply) }
        return result != 0L
    }

    /**
     * Throws the interpreted tree away and asks the Host for the whole of it again.
     *
     * For a Renderer that cannot keep its node table across whatever just happened to it.
     * It costs the application's state, because the Host keeps no shadow of the tree it
     * has already sent and answers by building the application from nothing, so a Renderer
     * that can keep its table should keep it instead. The Android host does.
     */
    fun resync() {
        table.clear()
        applyTransaction { apply ->
            connection.dispatchEvent(
                HostEvent.Resync(nodeId = NodeTable.ROOT_ID, handlerId = 0),
                apply,
            )
        }
    }

    /** Called once per frame after a Host worker asked for one. */
    fun renderFrame(frameTimeNanos: Long) {
        applyTransaction { apply -> connection.renderFrame(frameTimeNanos, apply) }
    }

    fun shutdown() = connection.shutdown()

    private fun applyTransaction(call: ((Mutation) -> Unit) -> Unit) {
        val protocolErrors = mutableListOf<TableError>()
        Snapshot.withMutableSnapshot {
            try {
                call { mutation -> table.apply(mutation) }
            } catch (error: Throwable) {
                // A malformed batch must not take the process down: it becomes a reported
                // protocol error instead.
                if (error is InterruptedException) throw error
                protocolErrors += TableError(PROTOCOL_DECODE_ERROR, error.describe())
            }
            protocolErrors += table.drainErrors()
        }
        // Reported after the transaction so the Host is never re-entered mid-batch.
        //
        // A Host is free to reject the report itself: nothing promises that reporting an
        // error succeeds, and the Rust Host answers a `ProtocolError` event with a
        // protocol-error status because no handler owns it. Letting that failure out of here
        // would turn a reported error into a crashed composition, which is the outcome the
        // report exists to prevent, so the report is best effort and the original error is
        // what gets printed.
        protocolErrors.forEach { error ->
            try {
                connection.dispatchEvent(
                    HostEvent.ProtocolError(
                        nodeId = NodeTable.ROOT_ID,
                        handlerId = 0,
                        code = error.code,
                        message = error.message,
                    ),
                ) { mutation -> Snapshot.withMutableSnapshot { table.apply(mutation) } }
            } catch (report: Throwable) {
                if (report is InterruptedException) throw report
                onProtocolError(error)
            }
        }
    }

    private companion object {
        const val PROTOCOL_DECODE_ERROR = 100
    }
}

/**
 * Where a protocol error goes when the Host will not take the report.
 *
 * Tests replace it to assert on what was reported; production leaves it printing.
 */
internal var onProtocolError: (TableError) -> Unit = { error ->
    System.err.println("dioxus-compose protocol error ${error.code}: ${error.message}")
}

/**
 * What to say about a failure, all the way down.
 *
 * The cause as well as the throwable, because some of them say nothing on their own. A
 * class whose initialiser failed reports "There was an error during file or class
 * initialization" and keeps which class and why in its cause, and a reader given only the
 * first line has been told that something went wrong and nothing else. That message was
 * printed twice a second by a window that came up black, and finding what it meant took
 * a debugger and an address arithmetic.
 */
private fun Throwable.describe(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < CAUSE_DEPTH) {
        val name = current::class.qualifiedName ?: current::class.simpleName ?: "error"
        val said = current.message
        parts += if (said.isNullOrBlank()) name else "$name: $said"
        current = current.cause
        depth++
    }
    return parts.joinToString(", caused by ")
}

/** How far down a cause chain is worth printing before it is noise. */
private const val CAUSE_DEPTH = 6

/** Creates a Host bound to the composition's lifetime. */
@Composable
fun rememberDioxusHost(connection: HostConnection): DioxusHost {
    val host = remember(connection) { DioxusHost(connection) }
    DisposableEffect(host) {
        host.start()
        onDispose { host.shutdown() }
    }
    return host
}

/**
 * The same, for a Host that was already started outside composition.
 *
 * The platform layer starts one before it builds the window, because what the window
 * should look like is in the first batch and a window cannot be told after it exists: its
 * decoration is fixed when it is created. Starting it again here would be the Host's
 * second init, which the boundary answers with an error rather than a tree.
 */
@Composable
fun rememberStartedDioxusHost(host: DioxusHost): DioxusHost {
    DisposableEffect(host) { onDispose { host.shutdown() } }
    return host
}

/**
 * Draws the Host's tree and runs the frame loop.
 *
 * Frame requests coming from Host worker threads coalesce into at most one `render_frame`
 * per frame, inside `withFrameNanos`.
 */
@Composable
fun DioxusContent(
    host: DioxusHost,
    modifier: Modifier = Modifier,
    caption: WindowCaption = WindowCaption.None,
) {
    val frames = LocalFrameRequests.current
    LaunchedEffect(host, frames) {
        var applied = frames.counter.value
        frames.counter.collect { requested ->
            if (requested == applied) return@collect
            applied = requested
            withFrameNanos { frameTimeNanos -> host.renderFrame(frameTimeNanos) }
        }
    }
    // The theme is resolved once here, and every node reads it from the CompositionLocal. A `SetTheme` is therefore one record on the wire and one
    // invalidation in Compose, not a SetProp per node.
    val platform = remember { detectHostPlatform() }
    // Compose's isSystemInDarkTheme() reads the setting once on the desktop and never
    // notices it change, so a window there keeps its original colours while the rest of
    // the screen switches. The desktop installs an observer that does follow the system;
    // where nobody installs one, Compose's own answer is correct and is used.
    val observedDark = LocalSystemDarkObserver.current?.invoke() ?: isSystemInDarkTheme()
    val systemDark = systemDarkOverride ?: observedDark
    // The window's size is measured here, where the root content is, and reported to
    // the Host only when it crosses a size class boundary. onSizeChanged already fires
    // only when the measured size differs, and the reporter drops everything that does
    // not change the class, so a drag across one class costs no boundary calls.
    val reporter = remember(host) { WindowSizeReporter() }
    val density = LocalDensity.current
    // The same measurement answers three questions. The Host is told when the class
    // changes so a component can choose what to put on the screen; the widgets that
    // change shape with the window read it from the CompositionLocal, because they are
    // drawn on this side and a round trip to ask would cost a boundary call, a
    // VirtualDom pass and a rebuilt subtree for a layout this side can already reach;
    // and the design system is resolved against it, because a system is allowed to
    // answer differently in a desktop window than on a phone.
    var sizeClass by remember(host) { mutableStateOf(WindowSizeClass.Compact) }
    val measured = Modifier.onSizeChanged { size ->
        with(density) {
            val widthDp = size.width.toDp().value
            sizeClass = windowSizeClassOf(widthDp)
            reporter.report(widthDp, size.height.toDp().value, host)
        }
    }
    // Whether anything in the tree asked to be made of a material, and so whether the
    // window is stood up with the platform's own material behind it. The design system is
    // told the answer, because chrome that sits straight on the desktop is drawn
    // differently from chrome over the application's own page.
    val asked = host.table.asksForWindowMaterial(host.roots)
    val theme = resolveTheme(
        host.table.theme,
        platform,
        systemDark,
        sizeClass,
        fontOf = { asset -> (host.table.assets.asset(asset) as? Asset.Font)?.family },
        brushOf = { asset -> (host.table.assets.asset(asset) as? Asset.Brush)?.brush },
        windowBackdrop = asked && platformBacksWindowWithMaterial(),
    )
    // The answer goes back to the Host, which asked a question it cannot answer itself:
    // an adaptive theme names no system, and the one that ends up running is worked out
    // here where the platform is. Only when it changes, the way a size class is reported.
    //
    // SideEffect rather than LaunchedEffect, and this is not a detail. A boundary call is
    // a direct call on the thread the Host was started on, and that thread is the one
    // composition is applied on. A coroutine effect runs on the toolkit's event thread
    // instead, where the Host has no state at all: the call came back refused, with the
    // status that means it was made in the wrong place.
    val reported = remember { arrayOfNulls<DesignSystem>(1) }
    SideEffect {
        if (reported[0] != theme.system) {
            reported[0] = theme.system
            host.dispatch(
                HostEvent.DesignSystemResolved(nodeId = 0, handlerId = 0, system = theme.system),
            )
        }
    }
    // A tree that opens with a bar, a picture or a colour of its own makes that the
    // window's caption, so the strip the window buttons sit in belongs to it rather than
    // to the page underneath it. Otherwise the page keeps it and the content starts below
    // the buttons.
    // The strips a phone's system draws in. Zero on a desktop, where a title bar is the
    // caption above instead. The keyboard is excluded: the renderer moves the field that
    // is being typed into itself, and taking the keyboard's height off the whole page as
    // well would move everything else twice.
    val bars = with(LocalDensity.current) {
        val safe = WindowInsets.safeDrawing.exclude(WindowInsets.ime)
        SystemBars(top = safe.getTop(this).toDp(), bottom = safe.getBottom(this).toDp())
    }
    // A rail or a sidebar that runs to the top of the window takes the caption the same
    // way a bar does: the window buttons sit on its surface and the page beside it starts
    // its own content clear of them. Leaving the page to take the strip instead drew a band
    // of page colour across the whole window above the sidebar, which is the leftover
    // title bar this exists to avoid.
    val navigation = theme.rules.navigation(sizeClass, theme)
    val stripTakesTheTop = navigation.carriesCaption &&
        navigation.presentation != NavigationPresentation.Bar &&
        host.table.opensWithANavigation(host.roots)
    val barIsCaption = (caption.height > 0.dp || bars.top > 0.dp) &&
        (host.table.opensWithABar(host.roots) || stripTakesTheTop)
    // A navigation showing a bar along the bottom grows into the bottom strip, so the page
    // must not stop short of it as well. The same question the navigation itself asks,
    // asked here because the page is laid out before anything inside it.
    val navigationTakesTheBottom = bars.bottom > 0.dp &&
        navigation.presentation == NavigationPresentation.Bar &&
        host.table.opensWithANavigation(host.roots)
    val (pageTop, pageBottom) = pageInsets(caption, bars, barIsCaption, navigationTakesTheBottom)
    // The clock and the gesture bar are drawn by the system over what this window drew, so
    // they follow the theme that was resolved rather than the device's setting. A light
    // application on a device in dark mode otherwise gets white icons on its own white
    // bar.
    LaunchedEffect(theme.dark) { systemChrome?.setDarkIcons(!theme.dark) }
    val captionStyle = theme.rules.caption(theme)
    // How much room the buttons take and at which end. The platform's own are at the
    // leading edge and the renderer's are wherever this design system puts them, so the
    // bar cannot assume either.
    // The bar is given the strip above it as well as the caption's own height. The two
    // are not the same shape: window buttons sit on the bar's row and a status bar sits
    // above it, so they are handed over separately.
    val strip = caption.copy(insetTop = bars.top)
    val barCaption = when {
        !barIsCaption -> WindowCaption.None
        LocalWindowActions.current == null -> strip
        else -> strip.copy(
            buttonsWidth = captionStyle.buttonWidth * 3 +
                captionStyle.spacing * 2 +
                captionStyle.edgePadding * 2,
            buttonsAtStart = captionStyle.side == CaptionSide.Start,
        )
    }
    CompositionLocalProvider(
        LocalDesignTheme provides theme,
        LocalReduceTransparency provides reduceTransparency,
        LocalWindowCaption provides barCaption,
        LocalStripTakesTheTop provides (barIsCaption && stripTakesTheTop),
        LocalSystemBars provides bars,
    ) {
        // The background fills the whole window and the inset is applied inside it. Putting
        // the inset outside instead leaves the window's own background showing through the
        // strip the title bar used to occupy, which reads as a leftover title bar rather
        // than as content extending underneath one.
        // Told once, and again only when the answer changes. A window that never asks is
        // never told, and comes up on exactly the path it did before any of this existed.
        SideEffect { platformWindowMaterial(asked) }
        CompositionLocalProvider(LocalWindowSizeClass provides sizeClass) {
            Box(modifier.then(measured).background(host.table.windowFill(host.roots, theme))) {
                Box(Modifier.padding(top = pageTop, bottom = pageBottom)) {
                    host.roots.forEach { rootId ->
                        androidx.compose.runtime.key(rootId) {
                            RenderNode(rootId, host.table, host)
                        }
                    }
                }
                // The window's own buttons, over everything, because the caption strip is
                // the window's and whatever the Host drew runs underneath it. Nothing is
                // drawn where the platform draws its own: macOS keeps the system's
                // traffic lights, and the platform layer provides no actions there.
                if (caption.height > 0.dp) {
                    WindowButtons(
                        style = captionStyle,
                        modifier = Modifier
                            .align(
                                if (captionStyle.side == CaptionSide.Start) {
                                    Alignment.TopStart
                                } else {
                                    Alignment.TopEnd
                                },
                            )
                            .height(caption.height),
                    )
                }
                // Over the content rather than in it: a message is not part of the tree,
                // and it covers whatever it has to for as long as it is up.
                HostMessages(host.table.messages, host.table.insets, host, theme)
            }
        }
    }
}

/**
 * Overrides the platform's dark mode reading, for tests and for the dev harness.
 *
 * `ColorScheme.FollowSystem` reads the platform; nothing else consults this.
 */
var systemDarkOverride: Boolean? = null

/**
 * How to find out whether the system is in dark mode, when the platform knows better than
 * Compose does.
 *
 * Installed by the platform at startup. The desktop installs a poller, because Compose's
 * own reading there is taken once and never revisited. iOS and Android leave it null,
 * where Compose observes the change itself and a second mechanism would be noise.
 *
 * It lives here rather than in the desktop module because this file is compiled for every
 * target: the iOS renderer symlinks it, and Skiko, which the desktop observer reads, does
 * not exist on Kotlin/Native.
 *
 * It is a CompositionLocal rather than a global, and that is not a matter of taste. The
 * desktop observer polls in a loop that never finishes, which is correct in a window and
 * fatal under a test clock: a composition with a coroutine forever waiting on a delay never
 * goes idle, so waitForIdle spins until the test times out. As a global, one composition
 * installing it silently did that to every composition created afterwards in the same
 * process, including tests that had nothing to do with it.
 */
val LocalSystemDarkObserver = staticCompositionLocalOf<(@Composable () -> Boolean)?> { null }

/**
 * The strip at the top of a window that belongs to the window rather than to the
 * application: on macOS the transparent title bar the close, minimise and zoom buttons
 * sit in, and on the platforms where the renderer draws the caption itself, the band it
 * draws it in.
 *
 * Content is allowed to run underneath it, which is the whole point of modern window
 * chrome, but a widget placed where the buttons are would leave both unusable. So
 * whatever owns the top of the window steps its own content clear of the strip while
 * still painting across it.
 *
 * The Host never sees these numbers and cannot set them. A safe area is a fact about the
 * window, not a decision the application makes.
 */
@androidx.compose.runtime.Immutable
data class WindowCaption(
    /** How tall the strip is. */
    val height: Dp = 0.dp,
    /** How much room the window buttons take at the end they sit at. */
    val buttonsWidth: Dp = 0.dp,
    /**
     * Which end that is.
     *
     * The platform's own buttons are at the leading edge, because the one platform that
     * keeps its own puts them there. Buttons the renderer draws sit wherever the running
     * design system puts them, which is the trailing edge for five of the seven, and a
     * bar that reserved room at the wrong end would push its title away from the buttons
     * and then draw them over its other end.
     */
    val buttonsAtStart: Boolean = true,
    /**
     * A strip directly above the bar that the bar's surface covers and its content starts
     * below.
     *
     * A phone's status bar, which is a different shape of problem from [height]: window
     * buttons share the bar's row and a clock does not, so a bar that treated the two the
     * same would draw its title through the time.
     */
    val insetTop: Dp = 0.dp,
) {
    companion object {
        /** No strip to avoid: a system title bar, or a platform without one. */
        val None = WindowCaption()
    }
}

/**
 * The caption the top app bar has to lay itself out around.
 *
 * Zero unless the Host's tree opens with a bar. Anything else keeps the caption on the
 * page, and a bar buried deeper in the tree is not at the top of the window, so stepping
 * its content down by the height of a strip it is nowhere near would only push it out of
 * line with everything beside it.
 */
val LocalWindowCaption = staticCompositionLocalOf { WindowCaption.None }

/**
 * Whether a rail or a sidebar at the root of the tree runs to the top of the window and
 * carries the caption on its own surface.
 *
 * Separate from [LocalWindowCaption], which a bar opening the tree also receives. A strip
 * that stepped its destinations down by the caption whenever one was handed out would
 * step them down under a bar that had already taken it, and leave a blank band beside it.
 */
val LocalStripTakesTheTop = staticCompositionLocalOf { false }

/** The child of a frame that fills [slot], if the application filled it. */
private fun NodeTable.slotChild(node: Node, slot: SlotRole): Int? =
    node.children.firstOrNull { childId ->
        val child = node(childId) ?: return@firstOrNull false
        child.widget == WidgetKind.ScaffoldSlot &&
            (child.property(PropertyKind.Slot) as? PropertyValue.Integer)?.value?.toInt() ==
            slot.ordinal + 1
    }

/**
 * True when the first thing the tree draws is a top app bar.
 *
 * Only the leading edge is followed, and only through the layouts that are wrappers
 * rather than things on screen: an application writes `Column { TopAppBar { } ... }`, and
 * the column is not something the reader sees. A bar reached any other way is not the top
 * of the window.
 */
internal fun NodeTable.opensWithABar(roots: List<Int>): Boolean {
    var id = roots.firstOrNull() ?: return false
    repeat(BAR_SEARCH_DEPTH) {
        val node = node(id) ?: return false
        when (node.widget) {
            WidgetKind.TopAppBar -> return true
            // A picture at the top of the window is the same case as a bar: it is what
            // the reader sees across the top, and leaving a strip of page colour above it
            // reads as a title bar nobody asked for.
            WidgetKind.Image -> return true
            // A Column stacks its children, so its first child is the top of the window.
            // A Box stacks them front to back, and a bar drawn first is chrome the rest
            // of the screen scrolls under, which is the same thing here.
            //
            // A wrapper is passed through whether or not it paints a background. Its
            // colour is the page's, and the page is exactly what should start below the
            // window buttons rather than run under them; what it holds decides instead.
            WidgetKind.Column, WidgetKind.Box -> id = node.children.firstOrNull() ?: return false
            // A frame names its parts, so the top of the window is the slot that says it
            // is the top bar rather than whichever child happens to come first. Following
            // the order instead would hand the caption to the destinations on a screen
            // that filled the bottom slot and not the top one.
            WidgetKind.Scaffold -> id = slotChild(node, SlotRole.TopBar) ?: return false
            WidgetKind.ScaffoldSlot -> id = node.children.firstOrNull() ?: return false
            // Nothing else takes it. A shell that paints itself, a Navigation holding a
            // whole page for instance, is the page rather than a strip across the top of
            // it, and handing it the caption puts its first line of text under the window
            // buttons. What its colour should do is fill the window, which is a separate
            // question answered by windowFill below.
            else -> return false
        }
    }
    return false
}

/**
 * True when a navigation holding the whole page is what the tree opens with.
 *
 * The one that owns the window, rather than one nested inside part of a screen. Only that
 * one draws a bar along the bottom edge of the window, and only that bar should grow into
 * the strip the system's gesture bar sits in: a nested one has content below it and would
 * leave a gap in the middle of the screen.
 */
internal fun NodeTable.opensWithANavigation(roots: List<Int>): Boolean {
    var id = roots.firstOrNull() ?: return false
    repeat(BAR_SEARCH_DEPTH) {
        val node = node(id) ?: return false
        when (node.widget) {
            WidgetKind.Navigation -> return true
            // The same wrappers the search above passes through, for the same reason:
            // they are not things the reader sees.
            WidgetKind.Column, WidgetKind.Box -> id = node.children.firstOrNull() ?: return false
            // A frame's destinations are whatever fills its bottom slot, and the frame
            // owns the page around them, which is what this question is really asking.
            WidgetKind.Scaffold -> id = slotChild(node, SlotRole.BottomBar) ?: return false
            WidgetKind.ScaffoldSlot -> id = node.children.firstOrNull() ?: return false
            else -> return false
        }
    }
    return false
}

/**
 * The colour the whole window is painted, before the content is laid out inside it.
 *
 * The root's own fill where it named one, and the theme's background where it did not.
 * Painting the theme's background regardless leaves an application that holds its own
 * palette with a page in its colour and a strip along the top in the design system's,
 * which reads as a leftover title bar. This is the rule a container already follows, that
 * the fill the application named wins, applied to the window.
 */
internal fun NodeTable.windowFill(roots: List<Int>, theme: ResolvedTheme): Color {
    val named = paintAtTheTopOfTheWindow(roots)
    val fill = named?.let(theme::color) ?: theme.color(ColorRole.Background)
    return if (asksForWindowMaterial(roots)) fill.letTheWindowShowThrough() else fill
}

/**
 * The colour of whatever reaches the top of the window, or null where nothing named one.
 *
 * The root is asked first and then the leading edge below it, because the thing that fills
 * a window is not always the root: an application writes a frame and puts a page in it, so
 * what paints is a step or two down and nothing above it names a colour at all. Reading
 * only the root left the strip above the page in the design system's background while the
 * page under it was the application's own, which is the leftover title bar this section
 * exists to avoid.
 */
private fun NodeTable.paintAtTheTopOfTheWindow(roots: List<Int>): Paint? {
    var id = roots.firstOrNull() ?: return null
    var atTheRoot = true
    repeat(BAR_SEARCH_DEPTH) {
        val node = node(id) ?: return null
        val painted = node.modifiers.firstNotNullOfOrNull {
            (it as? ProtocolModifier.Background)?.paint
        }
        when (node.widget) {
            // Chrome, not the window. A bar or a picture takes the caption and paints that
            // strip itself, so lending the window its colour would paint every screen that
            // opens with one in the chrome's colour.
            WidgetKind.TopAppBar, WidgetKind.Image -> return null
            // A shell holds the whole page, so the colour it paints is the colour the
            // window is.
            WidgetKind.Navigation -> return painted
            // A frame paints nothing itself and names its parts, so the window's colour is
            // the content's rather than the bar's.
            WidgetKind.Scaffold -> {
                painted?.let { return it }
                atTheRoot = false
                id = slotChild(node, SlotRole.Content) ?: return null
            }
            WidgetKind.ScaffoldSlot -> {
                painted?.let { return it }
                atTheRoot = false
                id = node.children.firstOrNull() ?: return null
            }
            // The same wrappers the caption search passes through, and for the same
            // reason: they are not things the reader sees. Their own colour counts only at
            // the root, where it is the page's; deeper down a painted Column is a card or
            // a bubble, and painting the window in a bubble's colour hides the rounding
            // that makes it one.
            WidgetKind.Column, WidgetKind.Box -> {
                if (atTheRoot && painted != null) return painted
                atTheRoot = false
                id = node.children.firstOrNull() ?: return null
            }
            else -> return if (atTheRoot) painted else null
        }
    }
    return null
}

/**
 * Whether anything in this tree asked to be made of a material.
 *
 * Anything, not the page. A window that shows what is behind it is not a window that is
 * see-through all over: no application on this platform is. A sidebar is vibrant and the
 * page beside it is not, and that is decided where the material is drawn rather than
 * here. What is decided here is only whether the window is stood up in a form that has
 * anything behind it to show, which is a property of the window and has to be settled
 * before it opens.
 *
 * A page that paints itself still covers what is behind it, exactly as it always did.
 */
internal fun NodeTable.asksForWindowMaterial(roots: List<Int>): Boolean {
    // The roots are taken so that the question is asked of a tree rather than of the
    // table in the abstract, and so a caller cannot forget to check that one exists.
    if (roots.isEmpty()) return false
    return asksForMaterial
}

/**
 * The page of a window that is backed by a material: none of it.
 *
 * The material behind the window is the page. Painting one over it is painting over the
 * thing that was asked for, and it does not take much: a page at three quarters opacity
 * left four percent of the backdrop showing, which measures as a couple of levels and
 * reads as a flat dark rectangle.
 *
 * What the screen declared is still drawn. The root carries the material role, and the
 * design system's own glass is drawn for it in the ordinary way, over a window that now
 * has something real behind it to be glass over.
 */
private fun Color.letTheWindowShowThrough(): Color =
    if (platformBacksWindowWithMaterial()) Color.Transparent else this

/**
 * True where the window has a material behind it for a translucent page to reveal.
 *
 * macOS only, and only because the renderer puts one there. Everywhere else a page with
 * alpha would show whatever the toolkit leaves behind a window, which is nothing to look
 * at and on some systems is black.
 */
internal fun platformBacksWindowWithMaterial(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = osName.startsWith("Mac")

/**
 * How many wrappers deep the search for that bar goes.
 *
 * Deep enough for the layouts an application really writes around a bar, shallow enough
 * that a tree which simply does not have one costs a handful of lookups per frame rather
 * than a walk.
 */
private const val BAR_SEARCH_DEPTH = 4

/**
 * Whether the reader has asked the system for reduced transparency.
 *
 * Every glass surface draws its opaque fallback instead when this is true, and the blur
 * pass that fed it disappears with it, so the setting removes the cost as well as the
 * look.
 *
 * It is read once, from `DXC_REDUCE_TRANSPARENCY` in the environment or the
 * `dioxus.compose.reduceTransparency` system property, either of which counts as set when
 * it is anything other than "0" or "false". A platform that can query the accessibility
 * setting directly assigns to this at startup instead; a platform that cannot leaves the
 * reader with a way to say so, which is better than no way at all.
 */
var reduceTransparency: Boolean = run {
    val raw = System.getenv("DXC_REDUCE_TRANSPARENCY")
        ?: System.getProperty("dioxus.compose.reduceTransparency")
        ?: return@run false
    !raw.equals("0", ignoreCase = true) && !raw.equals("false", ignoreCase = true)
}

/**
 * The strips of the window the system draws its own things in.
 *
 * A phone's status bar and its navigation bar, and the home indicator on the platforms
 * that have one. Zero on a desktop, where the window is the whole of what the application
 * draws in and a title bar is the [WindowCaption] above instead.
 *
 * This is deliberately not applied as padding around everything. An application that stops
 * short of both strips leaves the system's own background showing through them, which is
 * the grey band under a phone's gesture bar that no other application on the device has.
 * The window's fill is drawn edge to edge and then these are handed to whichever part of
 * the tree should grow into them.
 */
data class SystemBars(val top: Dp = 0.dp, val bottom: Dp = 0.dp) {
    companion object {
        /** A window with nothing over it. */
        val None = SystemBars()
    }
}

/** What the bottom strip is, for the part of the tree that grows into it. */
val LocalSystemBars = staticCompositionLocalOf { SystemBars.None }

/**
 * How far the page has to start and stop short of the edges of the window.
 *
 * Whatever a strip is given to is not the page's concern, which is what the two booleans
 * say. A bar that opens the tree takes the top strip and paints its own surface behind the
 * status bar, the way every application on a phone does. A navigation showing a bar along
 * the bottom takes the bottom strip for the same reason. What is left over is the page's,
 * because content running under a clock or a gesture bar is unreadable.
 */
internal fun pageInsets(
    caption: WindowCaption,
    bars: SystemBars,
    barTakesTheTop: Boolean,
    navigationTakesTheBottom: Boolean,
): Pair<Dp, Dp> {
    val top = if (barTakesTheTop) 0.dp else caption.height + bars.top
    val bottom = if (navigationTakesTheBottom) 0.dp else bars.bottom
    return top to bottom
}
