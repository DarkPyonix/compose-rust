package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.NavigationPresentation
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.navigationFor
import dev.darkpyonix.composerust.foundation.navigationStripTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.CompactNavigation
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SlotRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.SystemBars
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.runtime.systemBarsOverride
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NAVIGATION = 1
private const val FIRST = 2
private const val SECOND = 3
private const val PAGE = 4
private const val FRAME = 5
private const val BOTTOM_SLOT = 6
private const val CONTENT_SLOT = 7
private const val FIRST_ROW = 20
private const val ROWS = 20

/** The tag on the window the test draws into; bounds are measured from it, not from the root. */
private const val WINDOW = "floating-navigation-window"

private val COMPACT_WIDTH = 420.dp
private val WINDOW_HEIGHT = 600.dp

/** The strip the system keeps at the bottom of a phone's screen, as the tests give it. */
private val GESTURE_BAR = 24.dp

private fun theme(system: DesignSystem) = Theme(system, system, ColorScheme.Light, adaptive = false)

/** A `Navigation` with two destinations that asked for a floating bar. */
private fun floatingNavigation(): List<Mutation> = buildList {
    add(Mutation.Create(NAVIGATION, WidgetKind.Navigation))
    add(Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
    add(
        Mutation.SetProp(
            NAVIGATION,
            PropertyKind.CompactNavigation,
            PropertyValue.Integer(CompactNavigation.Floating.ordinal + 1L),
        ),
    )
    add(Mutation.Create(FIRST, WidgetKind.NavigationItem))
    add(Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("Tasks")))
    add(Mutation.SetProp(FIRST, PropertyKind.Icon, PropertyValue.Integer(IconRole.List.ordinal + 1L)))
    add(Mutation.Insert(NAVIGATION, FIRST, 0))
    add(Mutation.Create(SECOND, WidgetKind.NavigationItem))
    add(Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("Done")))
    add(Mutation.SetProp(SECOND, PropertyKind.Icon, PropertyValue.Integer(IconRole.Check.ordinal + 1L)))
    add(Mutation.Insert(NAVIGATION, SECOND, 1))
}

/** The page: a node of [widget] that fills what it is given, with [rows] rows of 80dp in it. */
private fun page(widget: WidgetKind, rows: Int): List<Mutation> = buildList {
    add(Mutation.Create(PAGE, widget))
    add(Mutation.SetModifier(PAGE, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(PAGE, 1, ProtocolModifier.FillMaxHeight))
    for (index in 0 until rows) {
        val id = FIRST_ROW + index
        add(Mutation.Create(id, WidgetKind.Box))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.FillMaxWidth))
        add(Mutation.SetModifier(id, 1, ProtocolModifier.Height(80f)))
        add(Mutation.Insert(PAGE, id, index))
    }
}

/** `Navigation { destinations, page }`, or `Scaffold { BottomBar(Navigation), Content(page) }`. */
private fun tree(system: DesignSystem, inAFrame: Boolean, widget: WidgetKind, rows: Int): List<Mutation> = buildList {
    add(Mutation.SetTheme(theme(system)))
    if (inAFrame) {
        add(Mutation.Create(FRAME, WidgetKind.Scaffold))
        add(Mutation.Create(BOTTOM_SLOT, WidgetKind.ScaffoldSlot))
        add(Mutation.SetProp(BOTTOM_SLOT, PropertyKind.Slot, PropertyValue.Integer(SlotRole.BottomBar.ordinal + 1L)))
        add(Mutation.Insert(FRAME, BOTTOM_SLOT, 0))
        addAll(floatingNavigation())
        add(Mutation.Insert(BOTTOM_SLOT, NAVIGATION, 0))
        add(Mutation.Create(CONTENT_SLOT, WidgetKind.ScaffoldSlot))
        add(Mutation.SetProp(CONTENT_SLOT, PropertyKind.Slot, PropertyValue.Integer(SlotRole.Content.ordinal + 1L)))
        add(Mutation.Insert(FRAME, CONTENT_SLOT, 1))
        addAll(page(widget, rows))
        add(Mutation.Insert(CONTENT_SLOT, PAGE, 0))
    } else {
        addAll(floatingNavigation())
        addAll(page(widget, rows))
        add(Mutation.Insert(NAVIGATION, PAGE, 2))
    }
}

/** The bar an application chose to float over the bottom of a compact window. */
@OptIn(ExperimentalTestApi::class)
class FloatingNavigationTest {
    private val frames = FrameRequestSource()

    @AfterTest
    fun clearOverrides() {
        hostPlatformOverride = null
        systemBarsOverride = null
    }

    private fun ComposeUiTest.show(batch: List<Mutation>, width: Dp = COMPACT_WIDTH): FakeHostConnection {
        val connection = FakeHostConnection(batch)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                // Pinned, so the bounds are in dp rather than in this machine's pixels.
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(connection),
                    Modifier.requiredSize(width, WINDOW_HEIGHT).testTag(WINDOW),
                )
            }
        }
        waitForIdle()
        return connection
    }

    /** The choice is a bar on every design system and platform, and only in a compact window. */
    @Test
    fun fr21_2_3_a_chosen_floating_bar_is_a_bar_in_a_compact_window_only() {
        val table = NodeTable()
        floatingNavigation().forEach(table::apply)
        val node = table.node(NAVIGATION)!!
        for (system in DesignSystem.entries) {
            for (platform in HostPlatform.entries) {
                val compact = resolveTheme(theme(system), platform, systemDark = false, sizeClass = WindowSizeClass.Compact)
                val style = compact.navigationFor(node, WindowSizeClass.Compact)
                assertEquals(NavigationPresentation.Bar, style.presentation, "$system on $platform")
                assertTrue(style.floating, "$system on $platform floats its bar")
                assertTrue(style.floatingInset > 0.dp, "$system on $platform holds it off the edges")
                // A wider window keeps the design system's own answer, which never floats
                // a bar because it was asked to.
                for (wider in listOf(WindowSizeClass.Medium, WindowSizeClass.Expanded)) {
                    val resolved = resolveTheme(theme(system), platform, systemDark = false, sizeClass = wider)
                    val answer = resolved.navigationFor(node, wider)
                    assertEquals(resolved.rules.navigation(wider, resolved).presentation, answer.presentation)
                    assertTrue(!answer.floating, "$system's $wider navigation on $platform floats")
                }
            }
        }
    }

    /**
     * On every design system the chosen bar is one strip held off the sides of a compact
     * window and off its bottom by at least the bottom safe area, and the page runs on
     * under it. The same holds where the navigation is in a frame's bottom slot.
     */
    @Test
    fun fr21_2_3_a_chosen_floating_bar_floats_on_every_design_system() {
        hostPlatformOverride = HostPlatform.Android
        systemBarsOverride = SystemBars(bottom = GESTURE_BAR)
        for (system in DesignSystem.entries) {
            for (inAFrame in listOf(false, true)) {
                val where = "$system, ${if (inAFrame) "in a frame" else "holding its page"}"
                runComposeUiTest {
                    show(tree(system, inAFrame, WidgetKind.Box, rows = 0))
                    onAllNodesWithTag(navigationStripTestTag(NAVIGATION)).assertCountEquals(1)
                    val window = onNodeWithTag(WINDOW).getBoundsInRoot()
                    val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
                    val page = onNodeWithTag(nodeTestTag(PAGE)).getBoundsInRoot()
                    assertTrue(
                        window.bottom - strip.bottom >= GESTURE_BAR,
                        "$where: the bar ends ${window.bottom - strip.bottom} above the window's bottom edge",
                    )
                    assertTrue(strip.left > window.left && strip.right < window.right, "$where: the bar is held off the sides: $strip")
                    assertTrue(strip.bottom - strip.top > 0.dp, "$where: the bar has a height")
                    assertTrue(
                        page.bottom >= strip.bottom && page.top < strip.top,
                        "$where: the page runs on under the bar: page $page, bar $strip",
                    )
                }
            }
        }
    }

    /**
     * A `ScrollColumn` under the bar, scrolled to its end, shows its last child above the
     * bar. Both where the navigation holds the page and where a frame does.
     */
    @Test
    fun fr21_2_3_the_floating_bar_never_hides_the_last_item() {
        hostPlatformOverride = HostPlatform.Android
        systemBarsOverride = SystemBars(bottom = GESTURE_BAR)
        for (system in DesignSystem.entries) {
            for (inAFrame in listOf(false, true)) {
                val where = "$system, ${if (inAFrame) "in a frame" else "holding its page"}"
                runComposeUiTest {
                    show(tree(system, inAFrame, WidgetKind.ScrollColumn, ROWS))
                    onNodeWithTag(nodeTestTag(PAGE)).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                    waitForIdle()
                    val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
                    val last = onNodeWithTag(nodeTestTag(FIRST_ROW + ROWS - 1)).getUnclippedBoundsInRoot()
                    assertTrue(
                        last.bottom <= strip.top,
                        "$where: the last row ends at ${last.bottom}, behind a bar that starts at ${strip.top}",
                    )
                    // And it did scroll: the first row is off the top.
                    val first = onNodeWithTag(nodeTestTag(FIRST_ROW)).getUnclippedBoundsInRoot()
                    val window = onNodeWithTag(WINDOW).getBoundsInRoot()
                    assertTrue(first.top < window.top, "$where: the page scrolled")
                }
            }
        }
    }

    /** The strip the system keeps at the top of the window is the top of the safe area the Host is told. */
    @Test
    fun fr21_2_3_safe_area_top_is_reported() {
        fun reported(bars: SystemBars?): HostEvent.WindowSizeChanged {
            systemBarsOverride = bars
            lateinit var connection: FakeHostConnection
            runComposeUiTest {
                connection = show(listOf(Mutation.SetTheme(theme(DesignSystem.Material3)), Mutation.Create(PAGE, WidgetKind.Column)))
            }
            return connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().last { it.nodeId == 0 }
        }
        val notched = reported(SystemBars(top = 30.dp, bottom = GESTURE_BAR))
        assertEquals(30f, notched.safeAreaTop)
        assertEquals(GESTURE_BAR.value, notched.safeAreaBottom)
        val plain = reported(null)
        assertEquals(0f, plain.safeAreaTop)
    }
}
