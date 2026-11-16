@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.foundation.navigationStripTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowHeightClass
import dev.darkpyonix.composerust.protocol.WindowSizeClass
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.RenderNodeObserver
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val PAGE = 1
private const val NAVIGATION = 2
private const val FIRST_DESTINATION = 3
private const val SECOND_DESTINATION = 4
private const val CARD = 5

/** The first id of the rows that fill the page. Each row is a card, a label and a button. */
private const val FIRST_ROW = 100
private const val ROWS = 20
private const val NODES_IN_A_ROW = 4

private fun theme(system: DesignSystem, scheme: ColorScheme = ColorScheme.Light) =
    Mutation.SetTheme(Theme(system, system, scheme, adaptive = false))

/**
 * The rows of an ordinary page, under [parent] from [index] on: a row holding a card with
 * a label in it and a button beside it. None of them asks what class the window is.
 */
private fun rows(parent: Int, index: Int = 0) = buildList {
    repeat(ROWS) { row ->
        val id = FIRST_ROW + row * NODES_IN_A_ROW
        add(Mutation.Create(id, WidgetKind.Row))
        add(Mutation.Create(id + 1, WidgetKind.Card))
        add(Mutation.Create(id + 2, WidgetKind.Text))
        add(Mutation.SetProp(id + 2, PropertyKind.Text, PropertyValue.Text("row $row")))
        add(Mutation.Insert(id + 1, id + 2, 0))
        add(Mutation.Insert(id, id + 1, 0))
        add(Mutation.Create(id + 3, WidgetKind.Button))
        add(Mutation.SetProp(id + 3, PropertyKind.Text, PropertyValue.Text("open $row")))
        add(Mutation.Insert(id, id + 3, 1))
        add(Mutation.Insert(parent, id, index + row))
    }
}

private val rowNodes: Set<Int> = (0 until ROWS * NODES_IN_A_ROW).map { FIRST_ROW + it }.toSet()

/** A page of rows and nothing else, in one named design system. */
private fun page(system: DesignSystem, scheme: ColorScheme = ColorScheme.Light) = buildList {
    add(theme(system, scheme))
    add(Mutation.Create(PAGE, WidgetKind.Column))
    addAll(rows(PAGE))
}

/** A navigation with two destinations, whose screen is the same page of rows. */
private fun navigationPage(system: DesignSystem) = buildList {
    add(theme(system))
    add(Mutation.Create(NAVIGATION, WidgetKind.Navigation))
    add(Mutation.SetProp(NAVIGATION, PropertyKind.SelectedIndex, PropertyValue.Integer(0)))
    for ((slot, id) in listOf(FIRST_DESTINATION, SECOND_DESTINATION).withIndex()) {
        add(Mutation.Create(id, WidgetKind.NavigationItem))
        add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text("destination $slot")))
        add(Mutation.SetProp(id, PropertyKind.Icon, PropertyValue.Integer(IconRole.List.ordinal + 1L)))
        add(Mutation.Insert(NAVIGATION, id, slot))
    }
    add(Mutation.Create(PAGE, WidgetKind.Column))
    addAll(rows(PAGE))
    add(Mutation.Insert(NAVIGATION, PAGE, 2))
}

/**
 * What a change of window size class costs.
 *
 * The class changes at 600 dp and 840 dp of width, and the height has a class of its own
 * that changes at 480 dp. A handful of widgets are drawn differently on either side: a
 * navigation is a bar, a rail or a drawer, and Apple's language has a different page and
 * different cards in a phone sized window. Everything else is the same on both sides and
 * must not be composed again, because during a drag of the window's edge the frame that
 * crosses a boundary is one of the frames of the drag.
 *
 * Each test resizes the scene, as a window being dragged does, and leaves what it composes
 * exactly as it was. The scene's density is 1, so a pixel of its size is a dp.
 */
@OptIn(ExperimentalTestApi::class)
class SizeClassRecompositionTest {
    private val frames = FrameRequestSource()
    private val compositions = mutableMapOf<Int, Int>()

    @AfterTest
    fun clear() {
        RenderNodeObserver.onCompose = null
        hostPlatformOverride = null
    }

    private fun count() {
        RenderNodeObserver.onCompose = { id -> compositions[id] = (compositions[id] ?: 0) + 1 }
    }

    /** The nodes composed since [before] was taken, with how many times each. */
    private fun since(before: Map<Int, Int>): Map<Int, Int> =
        compositions.mapValues { (id, times) -> times - (before[id] ?: 0) }.filterValues { it > 0 }

    /**
     * The window becomes [size], and the frames that follow are drawn: the layout that
     * measures the new size, and whatever that layout asked to be composed again.
     *
     * A test scene is not laid out again by being resized. It is when something asks where
     * a node is, so the root is asked. More than once, because the first answer is the
     * layout and the composition it scheduled is only run, and laid out, after it.
     */
    private fun DesktopComposeUiTest.resize(size: IntSize) {
        scene.size = size
        repeat(3) {
            waitForIdle()
            onRoot().fetchSemanticsNode()
            mainClock.advanceTimeByFrame()
        }
        waitForIdle()
    }

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(
            abs(expected.value - actual.value) <= 1f,
            "$what should be about ${expected.value}dp but was ${actual.value}dp",
        )
    }

    /**
     * A Material 3 page has nothing in it that depends on the class, so crossing any of the
     * three boundaries, in either direction, composes none of its nodes.
     */
    @Test
    fun nfr9_crossing_a_size_class_recomposes_only_its_readers() = runDesktopComposeUiTest(820, 700) {
        count()
        val connection = FakeHostConnection(page(DesignSystem.Material3))
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(connection), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        assertEquals(rowNodes + PAGE, compositions.keys, "every node of the page was composed once to begin with")

        fun sizes() = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>()
        val steps = listOf(
            "840 dp wide, growing" to IntSize(860, 700),
            "840 dp wide, shrinking" to IntSize(820, 700),
            "600 dp wide, shrinking" to IntSize(580, 700),
            "600 dp wide, growing" to IntSize(620, 700),
            "480 dp tall, shrinking" to IntSize(620, 460),
            "480 dp tall, growing" to IntSize(620, 500),
        )
        for ((crossing, size) in steps) {
            val before = compositions.toMap()
            val reports = sizes().size
            resize(size)
            assertEquals(reports + 1, sizes().size, "crossing $crossing is a change of class, and the Host is told")
            val composed = since(before)
            assertEquals(
                emptyMap(),
                composed,
                "crossing $crossing composed ${composed.size} of the page's ${rowNodes.size + 1} nodes again, " +
                    "and none of them is drawn differently in the new class",
            )
        }
        assertEquals(WindowSizeClass.Medium, sizes().last().sizeClass)
        assertEquals(WindowHeightClass.Medium, sizes().last().heightClass)
    }

    /**
     * A navigation is a reader of the class: it is a rail below 840 dp and a drawer from
     * there on. It changes, and the screen it holds is not composed again for it. Only the
     * part of the navigation that lays out its strip reads the class, so its own node need
     * not be composed again either; the drawer's width is what shows it followed.
     */
    @Test
    fun nfr9_a_navigation_follows_the_class_without_recomposing_its_screen() = runDesktopComposeUiTest(820, 700) {
        count()
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(navigationPage(DesignSystem.Material3))), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        val rail = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
        assertNear(80.dp, rail.right - rail.left, "the rail's width")
        val before = compositions.toMap()

        resize(IntSize(860, 700))

        val drawer = onNodeWithTag(navigationStripTestTag(NAVIGATION)).getBoundsInRoot()
        assertNear(280.dp, drawer.right - drawer.left, "the drawer's width")
        val composed = since(before)
        val bystanders = composed.keys intersect (rowNodes + PAGE)
        assertEquals(
            emptySet(),
            bystanders,
            "${bystanders.size} of the screen's ${rowNodes.size + 1} nodes were composed again " +
                "when the rail became a drawer",
        )
    }

    /**
     * Apple's language is the one that answers by class: in a dark window the page is black
     * at phone width and the darkest grey in a desktop window, and a card is glass only at
     * phone width. Both still change when the window crosses 600 dp, and the labels and
     * buttons, which are the same on both sides, are left alone.
     */
    @Test
    // Much taller than the rows, so a pixel at the bottom edge is the page.
    fun nfr9_liquid_glass_changes_its_page_and_cards_with_the_class_and_nothing_else() = runDesktopComposeUiTest(580, 2400) {
        hostPlatformOverride = HostPlatform.MacOs
        count()
        val batch = page(DesignSystem.LiquidGlass, ColorScheme.Dark)
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch)), Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        fun pageColour(): Color {
            val image = onRoot().captureToImage().toAwtImage()
            return Color(image.getRGB(image.width / 2, image.height - 4))
        }
        val firstCard = nodeTestTag(FIRST_ROW + 1)
        fun cardColour(): Color {
            val image = onNodeWithTag(firstCard).captureToImage().toAwtImage()
            return Color(image.getRGB(image.width - 3, image.height / 2))
        }
        val phonePage = pageColour()
        val phoneCard = cardColour()
        assertEquals(Color.Black, phonePage, "a dark phone sized window is black")
        val before = compositions.toMap()

        resize(IntSize(620, 2400))

        val desktopPage = pageColour()
        assertNotEquals(phonePage, desktopPage, "a dark desktop window is not black")
        assertNotEquals(phoneCard, cardColour(), "a card is glass only in a phone sized window, so it is drawn differently")
        val composed = since(before)
        val same = (0 until ROWS).flatMap { row ->
            val id = FIRST_ROW + row * NODES_IN_A_ROW
            listOf(id, id + 2, id + 3)
        }.toSet()
        val bystanders = composed.keys intersect same
        assertEquals(
            emptySet(),
            bystanders,
            "${bystanders.size} of the ${same.size} rows, labels and buttons were composed again, " +
                "and they are the same in both classes",
        )
    }

    /**
     * The other side of the same rule: what really is a change of theme still reaches every
     * node. A stable theme must not become a theme that has stopped listening.
     */
    @Test
    fun nfr9_a_change_of_theme_still_recomposes_every_node() = runDesktopComposeUiTest(820, 700) {
        count()
        val themes = listOf(
            "the colour scheme" to Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Dark, adaptive = false),
            "the design system" to Theme(DesignSystem.Fluent, DesignSystem.Fluent, ColorScheme.Dark, adaptive = false),
        )
        var next = 0
        val connection = FakeHostConnection(page(DesignSystem.Material3))
        // Only the click is answered with a theme. The window reports its size as well, and
        // answering that would change the theme before anything was counted.
        connection.respondWith { event ->
            if (event is HostEvent.Clicked) HostResponse(listOf(Mutation.SetTheme(themes[next].second))) else HostResponse()
        }
        lateinit var host: ComposeRustHost
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames, LocalDensity provides Density(1f)) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host, Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        for ((index, change) in themes.withIndex()) {
            next = index
            val before = compositions.toMap()
            host.dispatch(HostEvent.Clicked(PAGE, 0))
            waitForIdle()
            assertEquals(
                rowNodes + PAGE,
                since(before).keys,
                "changing ${change.first} is a change every node is drawn from",
            )
        }
    }
}
