package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.NavigationPresentation
import dev.darkpyonix.composerust.design.NavigationStyle
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.foundation.ToggleClearance
import dev.darkpyonix.composerust.foundation.navigationFor
import dev.darkpyonix.composerust.foundation.navigationStripTestTag
import dev.darkpyonix.composerust.foundation.navigationToggleTestTag
import dev.darkpyonix.composerust.foundation.toggleClearance
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.CompactNavigation
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SlotRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NAVIGATION = 1
private const val FIRST = 2
private const val SECOND = 3
private const val SCREEN = 4
private const val FRAME = 5
private const val TOP_SLOT = 6
private const val BOTTOM_SLOT = 7
private const val CONTENT_SLOT = 8

/**
 * The tag on the window the test draws into. The test window is shorter than the 800 dp it
 * asks for, so Compose centres the content and its top sits above the root's: bounds that
 * mean "from the window's edge" are measured from this, not from the root.
 */
private const val WINDOW = "compact-navigation-window"
private const val PAGE = 9
private const val BAR = 10
private const val TITLE = 11

/** A compact window, the width the samples were caught at in issue #526. */
private val COMPACT_WIDTH = 420.dp

private fun theme(system: DesignSystem) = Theme(system, system, ColorScheme.Light, adaptive = false)

private fun text(id: Int, parent: Int, index: Int, value: String) = listOf(
    Mutation.Create(id, WidgetKind.Text),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)),
    Mutation.Insert(parent, id, index),
)

/** The tag a choice travels as, or null for no record at all. */
private fun CompactNavigation?.tag(): Long? = this?.let { it.ordinal + 1L }

/** A `Navigation` with two destinations, and the choice it made, if any. */
private fun navigation(choice: CompactNavigation?): List<Mutation> = buildList {
    add(Mutation.Create(NAVIGATION, WidgetKind.Navigation))
    add(Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
    choice.tag()?.let { add(Mutation.SetProp(NAVIGATION, PropertyKind.CompactNavigation, PropertyValue.Integer(it))) }
    add(Mutation.Create(FIRST, WidgetKind.NavigationItem))
    add(Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("Tasks")))
    add(Mutation.SetProp(FIRST, PropertyKind.Icon, PropertyValue.Integer(IconRole.List.ordinal + 1L)))
    add(Mutation.Insert(NAVIGATION, FIRST, 0))
    add(Mutation.Create(SECOND, WidgetKind.NavigationItem))
    add(Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("Done")))
    add(Mutation.SetProp(SECOND, PropertyKind.Icon, PropertyValue.Integer(IconRole.Check.ordinal + 1L)))
    add(Mutation.Insert(NAVIGATION, SECOND, 1))
}

/** `Column { TopAppBar { Text } }`, a page that opens with a bar, inserted under [parent]. */
private fun pageWithABar(parent: Int, index: Int) = listOf(
    Mutation.Create(PAGE, WidgetKind.Column),
    Mutation.Create(BAR, WidgetKind.TopAppBar),
    Mutation.Insert(PAGE, BAR, 0),
) + text(TITLE, BAR, 0, "Good evening") + listOf(Mutation.Insert(parent, PAGE, index))

private fun slot(id: Int, role: SlotRole, index: Int) = listOf(
    Mutation.Create(id, WidgetKind.ScaffoldSlot),
    Mutation.SetProp(id, PropertyKind.Slot, PropertyValue.Integer(role.ordinal + 1L)),
    Mutation.Insert(FRAME, id, index),
)

/** One of the four shapes a page under a put-away navigation comes in. */
private enum class Shape(val firstContent: Int) {
    /** `Navigation { destinations, Text }`: the navigation holds the page. */
    HoldsAPlainPage(SCREEN),

    /** `Navigation { destinations, Column { TopAppBar { Text } } }`. */
    HoldsAPageWithABar(TITLE),

    /** `Scaffold { BottomBar(Navigation), Content(Text) }`. */
    FrameWithoutATopBar(SCREEN),

    /** `Scaffold { TopBar(TopAppBar { Text }), BottomBar(Navigation), Content(Text) }`. */
    FrameWithATopBar(TITLE),
}

private fun tree(system: DesignSystem, choice: CompactNavigation?, shape: Shape): List<Mutation> =
    buildList {
        add(Mutation.SetTheme(theme(system)))
        when (shape) {
            Shape.HoldsAPlainPage -> {
                addAll(navigation(choice))
                addAll(text(SCREEN, NAVIGATION, 2, "Skills To Pump!"))
            }
            Shape.HoldsAPageWithABar -> {
                addAll(navigation(choice))
                addAll(pageWithABar(NAVIGATION, 2))
            }
            Shape.FrameWithoutATopBar, Shape.FrameWithATopBar -> {
                add(Mutation.Create(FRAME, WidgetKind.Scaffold))
                var index = 0
                if (shape == Shape.FrameWithATopBar) {
                    addAll(slot(TOP_SLOT, SlotRole.TopBar, index++))
                    add(Mutation.Create(BAR, WidgetKind.TopAppBar))
                    add(Mutation.Insert(TOP_SLOT, BAR, 0))
                    addAll(text(TITLE, BAR, 0, "All"))
                }
                addAll(slot(BOTTOM_SLOT, SlotRole.BottomBar, index++))
                addAll(navigation(choice))
                add(Mutation.Insert(BOTTOM_SLOT, NAVIGATION, 0))
                addAll(slot(CONTENT_SLOT, SlotRole.Content, index))
                addAll(text(SCREEN, CONTENT_SLOT, 0, "the page"))
            }
        }
    }

private fun DpRect.overlaps(other: DpRect): Boolean =
    left < other.right && other.left < right && top < other.bottom && other.top < bottom

/** A `Navigation` node carrying [choice], read back through the same table the Renderer keeps. */
private fun navigationNode(choice: CompactNavigation?): Node {
    val table = NodeTable()
    navigation(choice).forEach(table::apply)
    return table.node(NAVIGATION)!!
}

/** The parts of a style a reader can see, compared rather than the whole data class. */
private fun NavigationStyle.looks() = listOf(
    presentation,
    container,
    content,
    selectedContent,
    indicator,
    separator,
    barHeight,
    railWidth,
    drawerWidth,
)

/**
 * An application chooses what its destinations become in a compact window, and only
 * there. Without a choice the design system answers as it did before there was one.
 */
@OptIn(ExperimentalTestApi::class)
class CompactNavigationTest {
    private val frames = FrameRequestSource()

    @AfterTest
    fun clearPlatform() {
        hostPlatformOverride = null
    }

    private fun ComposeUiTest.show(batch: List<Mutation>, width: Dp, caption: WindowCaption = WindowCaption.None) {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                // Pinned, so the bounds are in dp rather than in this machine's pixels.
                LocalDensity provides Density(1f),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(batch)),
                    Modifier.requiredSize(width, 800.dp).testTag(WINDOW),
                    caption = caption,
                )
            }
        }
        waitForIdle()
    }

    /**
     * A sidebar behind a toggle, on every design system, including the ones whose own
     * compact answer is a bottom bar. Android is the platform here because there every one
     * of the seven answers a bar, Apple's included, so a toggle can only be the choice.
     */
    @Test
    fun fr21_2_3_a_chosen_sidebar_is_put_away_on_every_design_system() {
        hostPlatformOverride = HostPlatform.Android
        val node = navigationNode(CompactNavigation.Sidebar)
        for (system in DesignSystem.entries) {
            for (platform in HostPlatform.entries) {
                val resolved = resolveTheme(theme(system), platform, systemDark = false, sizeClass = WindowSizeClass.Compact)
                assertEquals(
                    NavigationPresentation.PutAway,
                    resolved.navigationFor(node, WindowSizeClass.Compact).presentation,
                    "$system on $platform did not put the chosen sidebar away",
                )
            }
            runComposeUiTest {
                show(tree(system, CompactNavigation.Sidebar, Shape.HoldsAPlainPage), COMPACT_WIDTH)
                onAllNodesWithTag(navigationStripTestTag(NAVIGATION)).assertCountEquals(0)
                onNodeWithTag(navigationToggleTestTag(NAVIGATION)).performClick()
                waitForIdle()
                val strip = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
                assertTrue(
                    strip.right - strip.left < COMPACT_WIDTH,
                    "$system brought out a bar across the window rather than a sidebar: $strip",
                )
                onNodeWithTag(nodeTestTag(SECOND)).performClick()
                waitForIdle()
                onAllNodesWithTag(navigationStripTestTag(NAVIGATION)).assertCountEquals(0)
            }
        }
    }

    /** No destinations and no toggle: the page has the whole compact window. */
    @Test
    fun fr21_2_3_a_chosen_none_draws_no_destinations_on_every_design_system() {
        val node = navigationNode(CompactNavigation.None)
        for (system in DesignSystem.entries) {
            for (platform in HostPlatform.entries) {
                val resolved = resolveTheme(theme(system), platform, systemDark = false, sizeClass = WindowSizeClass.Compact)
                assertEquals(
                    NavigationPresentation.Hidden,
                    resolved.navigationFor(node, WindowSizeClass.Compact).presentation,
                    "$system on $platform still drew destinations",
                )
            }
            for (shape in listOf(Shape.HoldsAPlainPage, Shape.FrameWithoutATopBar)) {
                runComposeUiTest {
                    show(tree(system, CompactNavigation.None, shape), COMPACT_WIDTH)
                    onAllNodesWithTag(navigationStripTestTag(NAVIGATION)).assertCountEquals(0)
                    onAllNodesWithTag(navigationToggleTestTag(NAVIGATION)).assertCountEquals(0)
                    onAllNodesWithTag(nodeTestTag(FIRST)).assertCountEquals(0)
                    val window = onNodeWithTag(WINDOW).getBoundsInRoot()
                    val page = onNodeWithTag(nodeTestTag(SCREEN)).getBoundsInRoot()
                    assertEquals(window.left, page.left, "$system $shape inset the page from the leading edge")
                    assertEquals(window.top, page.top, "$system $shape inset the page from the top")
                }
            }
        }
    }

    /** Medium and Expanded windows keep the design system's rail and drawer whatever was chosen. */
    @Test
    fun fr21_2_3_the_choice_leaves_medium_and_expanded_to_the_design_system() {
        for (system in DesignSystem.entries) {
            for (platform in HostPlatform.entries) {
                for (sizeClass in listOf(WindowSizeClass.Medium, WindowSizeClass.Expanded)) {
                    val resolved = resolveTheme(theme(system), platform, systemDark = false, sizeClass = sizeClass)
                    val answer = resolved.rules.navigation(sizeClass, resolved).looks()
                    for (choice in CompactNavigation.entries) {
                        assertEquals(
                            answer,
                            resolved.navigationFor(navigationNode(choice), sizeClass).looks(),
                            "$choice changed $system's $sizeClass navigation on $platform",
                        )
                    }
                }
            }
        }
    }

    /** A navigation that chose nothing is drawn exactly as the design system answers. */
    @Test
    fun fr21_2_3_without_a_choice_the_design_system_answers_as_before() {
        val node = navigationNode(null)
        assertEquals(null, node.property(PropertyKind.CompactNavigation), "no choice sends no record")
        for (system in DesignSystem.entries) {
            for (platform in HostPlatform.entries) {
                for (sizeClass in WindowSizeClass.entries) {
                    val resolved = resolveTheme(theme(system), platform, systemDark = false, sizeClass = sizeClass)
                    assertEquals(
                        resolved.rules.navigation(sizeClass, resolved).looks(),
                        resolved.navigationFor(node, sizeClass).looks(),
                        "$system's $sizeClass navigation on $platform moved without a choice",
                    )
                }
            }
        }
        // And on the screen: Material 3's compact answer is still its bottom bar.
        runComposeUiTest {
            show(tree(DesignSystem.Material3, null, Shape.HoldsAPlainPage), 500.dp)
            val bar = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
            assertEquals(500.dp, bar.right - bar.left, "the default compact bar is not across the window")
            onAllNodesWithTag(navigationToggleTestTag(NAVIGATION)).assertCountEquals(0)
        }
    }

    /**
     * The toggle is drawn over the page from the window's leading top corner. Whatever the
     * page opens with, and whether the navigation holds the page or sits in a frame's
     * bottom slot, the first thing on the page is clear of it.
     *
     * This is issue #526: the toggle sat on the first letters of the page title in
     * academic, social, todo and selfcare at 420 wide.
     */
    @Test
    fun fr21_2_3_the_toggle_never_overlaps_the_page() {
        for (system in DesignSystem.entries) {
            for (shape in Shape.entries) {
                runComposeUiTest {
                    show(tree(system, CompactNavigation.Sidebar, shape), COMPACT_WIDTH)
                    val toggle = onNodeWithTag(navigationToggleTestTag(NAVIGATION)).getBoundsInRoot()
                    assertTrue(toggle.right > toggle.left, "$system $shape drew no toggle")
                    val firstThings = if (shape == Shape.FrameWithATopBar) {
                        listOf(TITLE, SCREEN)
                    } else {
                        listOf(shape.firstContent)
                    }
                    for (id in firstThings) {
                        val content = onNodeWithTag(nodeTestTag(id)).getBoundsInRoot()
                        assertFalse(
                            toggle.overlaps(content),
                            "$system $shape: the toggle $toggle is drawn over the page's content $content",
                        )
                    }
                }
            }
        }
    }

    /**
     * Where the window has a caption and the page opens with a bar on it, the bar keeps
     * its place on the window buttons' line and starts its content after the toggle, which
     * sits after the window buttons.
     */
    @Test
    fun fr21_2_3_a_bar_on_the_caption_starts_after_the_toggle() {
        val caption = WindowCaption(height = 40.dp, buttonsWidth = 78.dp)
        val clearance = toggleClearance(caption, padding = 8.dp, opensWithABar = true)
        assertIs<ToggleClearance.Beside>(clearance)
        assertTrue(clearance.caption.buttonsWidth >= 78.dp + 8.dp + 28.dp, "the bar would start under the toggle")
        assertTrue(clearance.caption.height >= 8.dp + 28.dp + 8.dp, "the bar is shorter than the toggle it sits beside")
        // Window buttons at the far end leave the caption one end to name, so the page
        // goes below the toggle instead.
        assertIs<ToggleClearance.Below>(
            toggleClearance(caption.copy(buttonsAtStart = false), padding = 8.dp, opensWithABar = true),
        )
        assertIs<ToggleClearance.Below>(toggleClearance(caption, padding = 8.dp, opensWithABar = false))
        assertIs<ToggleClearance.Below>(toggleClearance(WindowCaption.None, padding = 8.dp, opensWithABar = true))

        for (system in DesignSystem.entries) {
            runComposeUiTest {
                show(tree(system, CompactNavigation.Sidebar, Shape.FrameWithATopBar), COMPACT_WIDTH, caption)
                val toggle = onNodeWithTag(navigationToggleTestTag(NAVIGATION)).getBoundsInRoot()
                val title = onNodeWithTag(nodeTestTag(TITLE)).getBoundsInRoot()
                val page = onNodeWithTag(nodeTestTag(SCREEN)).getBoundsInRoot()
                assertTrue(toggle.left >= 78.dp, "$system drew the toggle over the window buttons: $toggle")
                assertTrue(title.left >= toggle.right, "$system started the bar's title under the toggle: $title, $toggle")
                assertFalse(toggle.overlaps(page), "$system drew the toggle over the page: $toggle, $page")
            }
        }
    }
}
