package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.Mutation
import androidx.compose.ui.graphics.Color
import dioxus.compose.design.resolveTheme
import dioxus.compose.design.HostPlatform
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.opensWithABar
import dioxus.compose.runtime.opensWithANavigation
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Theme
import dioxus.compose.runtime.platformBacksWindowWithMaterial
import dioxus.compose.protocol.MaterialRole
import dioxus.compose.runtime.asksForWindowMaterial
import dioxus.compose.runtime.windowFill
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROOT = 1
private const val BAR = 2
private const val LABEL = 3

/** The height of the strip the window buttons sit in, for these tests. */
private val CAPTION = WindowCaption(height = 40.dp, buttonsWidth = 78.dp)

private fun label(id: Int, parent: Int, index: Int, value: String) = listOf(
    Mutation.Create(id, WidgetKind.Text),
    Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(value)),
    Mutation.Insert(parent, id, index),
)

/** A window whose tree opens with a bar: `Column { TopAppBar { Text } }`. */
private val TREE_WITH_A_BAR =
    listOf(Mutation.Create(ROOT, WidgetKind.Column), Mutation.Create(BAR, WidgetKind.TopAppBar)) +
        label(LABEL, BAR, 0, "Title") +
        listOf(Mutation.Insert(ROOT, BAR, 0))

/** The same window without one: `Column { Text }`. */
private val TREE_WITHOUT_A_BAR =
    listOf(Mutation.Create(ROOT, WidgetKind.Column)) + label(LABEL, ROOT, 0, "Title")

/** A bar that paints itself at the top of the window: `Column { TopAppBar(cream) }`. */
private val TREE_WITH_A_PAINTED_BAR = listOf(
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.Create(BAR, WidgetKind.TopAppBar),
    Mutation.SetModifier(BAR, 0, ProtocolModifier.Background(Paint.Literal(CREAM))),
    Mutation.Insert(ROOT, BAR, 0),
)

/** A window whose top is a picture: `Column { Image }`. */
private val TREE_WITH_A_PICTURE = listOf(
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.Create(BAR, WidgetKind.Image),
    Mutation.Insert(ROOT, BAR, 0),
)

/**
 * A frame with its top slot filled: `Scaffold { ScaffoldSlot(TopBar) { TopAppBar } }`.
 *
 * The shape the samples took when they stopped building their own frames, and the shape
 * that broke this: the search walked Column and Box and stopped at anything else, so a
 * bar inside a frame was not found, the bar did not take the caption, and the window
 * opened with a strip of page colour above it and the buttons floating in it.
 */
private const val SLOT = 4
private val TREE_IN_A_FRAME = listOf(
    Mutation.Create(ROOT, WidgetKind.Scaffold),
    Mutation.Create(SLOT, WidgetKind.ScaffoldSlot),
    Mutation.SetProp(SLOT, PropertyKind.Slot, PropertyValue.Integer(1)),
    Mutation.Insert(ROOT, SLOT, 0),
    Mutation.Create(BAR, WidgetKind.TopAppBar),
    Mutation.Insert(SLOT, BAR, 0),
)

/** The same frame with only its bottom slot filled, which is not a bar at the top. */
private val TREE_IN_A_FRAME_WITHOUT_A_TOP_BAR = listOf(
    Mutation.Create(ROOT, WidgetKind.Scaffold),
    Mutation.Create(SLOT, WidgetKind.ScaffoldSlot),
    Mutation.SetProp(SLOT, PropertyKind.Slot, PropertyValue.Integer(2)),
    Mutation.Insert(ROOT, SLOT, 0),
    Mutation.Create(BAR, WidgetKind.Navigation),
    Mutation.Insert(SLOT, BAR, 0),
)

/** The colour an application names when it holds its own palette rather than a role's. */
private const val CREAM = 0xfffdf3e7.toInt()

/** A window whose root is a shell that paints itself: `Navigation { Text }`. */
private val TREE_IN_A_PAINTED_SHELL = listOf(
    Mutation.Create(ROOT, WidgetKind.Navigation),
    Mutation.SetModifier(ROOT, 0, ProtocolModifier.Background(Paint.Literal(CREAM))),
) + label(LABEL, ROOT, 0, "Title")

/**
 * A page inside a wrapper that paints: `Column { Navigation(cream) { Text } }`.
 *
 * How a shell reaches the top of the window in practice. The application writes a
 * navigation and the renderer wraps it, so nothing at the root names a colour and the one
 * that fills the window is a step down.
 */
private val TREE_UNDER_A_PAINTED_SHELL = listOf(
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.Create(BAR, WidgetKind.Navigation),
    Mutation.SetModifier(BAR, 0, ProtocolModifier.Background(Paint.Literal(CREAM))),
    Mutation.Insert(ROOT, BAR, 0),
) + label(LABEL, BAR, 0, "Title")

/** `Column { Text }` with the page painted cream by the application. */
private val TREE_PAINTED_BY_THE_APPLICATION = listOf(
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.SetModifier(ROOT, 0, ProtocolModifier.Background(Paint.Literal(CREAM))),
) + label(LABEL, ROOT, 0, "Title")

/** The same page, made of the thickest material a design system has. */
private val TREE_MADE_OF_CHROME = listOf(
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.SetModifier(ROOT, 0, ProtocolModifier.Background(Paint.Literal(CREAM))),
    Mutation.SetModifier(ROOT, 1, ProtocolModifier.Material(MaterialRole.Chrome)),
) + label(LABEL, ROOT, 0, "Title")

@OptIn(ExperimentalTestApi::class)
class WindowCaptionTest {
    private val frames = FrameRequestSource()

    private fun tableOf(batch: List<Mutation>): NodeTable {
        val table = NodeTable()
        batch.forEach(table::apply)
        return table
    }

    /**
     * The bar is what runs across the top of the window, so it is the piece that has to
     * carry the caption. Anything else in the tree is not at the top of the window.
     */
    @Test
    fun fr19_2_a_tree_that_opens_with_a_bar_hands_it_the_caption() {
        val withBar = tableOf(TREE_WITH_A_BAR)
        assertTrue(withBar.opensWithABar(withBar.roots))

        val withoutBar = tableOf(TREE_WITHOUT_A_BAR)
        assertFalse(withoutBar.opensWithABar(withoutBar.roots))
    }

    /**
     * A bar inside a frame is still the bar across the top of the window.
     *
     * The frame names its parts, so the search follows the slot that says it is the top
     * bar rather than whichever child comes first: a screen that filled only the bottom
     * slot has no bar at the top and must not be handed the caption.
     */
    @Test
    fun fr19_2_a_frame_hands_its_top_slot_the_caption() {
        val framed = tableOf(TREE_IN_A_FRAME)
        assertTrue(
            framed.opensWithABar(framed.roots),
            "the bar is in the frame's top slot, so it is what runs across the top of " +
                "the window; missing it leaves a strip of page colour above it with the " +
                "window buttons floating in it",
        )

        val bottomOnly = tableOf(TREE_IN_A_FRAME_WITHOUT_A_TOP_BAR)
        assertFalse(
            bottomOnly.opensWithABar(bottomOnly.roots),
            "the destinations are at the bottom and there is no bar at the top",
        )
        assertTrue(
            bottomOnly.opensWithANavigation(bottomOnly.roots),
            "the frame's bottom slot is the navigation that owns the window",
        )
    }

    /**
     * A screen whose top is a photograph is the same case as a bar. Starting the picture
     * below the window buttons leaves a strip of page colour above it, which is what a
     * window with a title bar nobody asked for looks like.
     */
    @Test
    fun fr19_2_a_tree_that_opens_with_a_picture_hands_it_the_caption() {
        val withPicture = tableOf(TREE_WITH_A_PICTURE)
        assertTrue(withPicture.opensWithABar(withPicture.roots))
    }

    /**
     * The window is painted in the colour the application named, and in the theme's
     * background only where it named none. Painting the theme's background regardless
     * leaves a page in the application's colour under a strip in the design system's.
     */
    /**
     * A shell that paints itself is the page, not a strip across the top of it. Handing it
     * the caption put the first line of a sample's text under the window buttons. What its
     * colour should do is fill the window, which the next test covers.
     */
    @Test
    fun fr19_2_a_painted_shell_does_not_take_the_caption() {
        val shell = tableOf(TREE_IN_A_PAINTED_SHELL)
        assertFalse(shell.opensWithABar(shell.roots))
    }

    @Test
    fun fr19_2_the_caption_strip_takes_the_colour_the_root_was_painted() {
        // Material 3 rather than whatever this machine resolves to, because a glass
        // window keeps the same colour and gives up some of its opacity, and this test is
        // about which colour rather than how much of it.
        val theme = resolveTheme(
            theme = Theme(
                DesignSystem.Material3,
                DesignSystem.Material3,
                ColorScheme.Light,
                adaptive = false,
            ),
            platform = HostPlatform.MacOs,
            systemDark = false,
        )
        val painted = tableOf(TREE_PAINTED_BY_THE_APPLICATION)
        assertEquals(Color(CREAM), painted.windowFill(painted.roots, theme))

        val plain = tableOf(TREE_WITHOUT_A_BAR)
        assertEquals(theme.color(ColorRole.Background), plain.windowFill(plain.roots, theme))
    }

    /**
     * The window takes the colour of whatever reaches its top, not only of the root.
     *
     * A tree that opens with a shell keeps the caption on the page, so the content starts
     * below the window buttons and the strip above it is painted by the window. Reading
     * only the root left that strip in the design system's background while the page under
     * it was the application's own, which is the leftover title bar this section exists to
     * avoid.
     */
    @Test
    fun fr19_the_window_takes_the_colour_that_fills_it() {
        val theme = lightMaterial3()
        val shell = tableOf(TREE_UNDER_A_PAINTED_SHELL)
        assertEquals(Color(CREAM), shell.windowFill(shell.roots, theme))
    }

    /**
     * A bar at the top of the window does not lend the window its colour.
     *
     * The bar takes the caption, so what it paints is the strip it draws rather than the
     * window behind the page. Taking it here would paint every screen that opens with a
     * bar in the chrome's colour.
     */
    @Test
    fun fr19_a_bar_does_not_lend_the_window_its_colour() {
        val theme = lightMaterial3()
        val barred = tableOf(TREE_WITH_A_PAINTED_BAR)
        assertEquals(theme.color(ColorRole.Background), barred.windowFill(barred.roots, theme))
    }

    private fun lightMaterial3() = resolveTheme(
        theme = Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
        ),
        platform = HostPlatform.MacOs,
        systemDark = false,
    )

    /**
     * A page made of chrome keeps its colour and stops painting all of it.
     *
     * The material the renderer puts behind the window is only a material if something
     * lets it through, and the page is the backmost thing drawn. Painted opaque it covers
     * the backdrop completely, which is what the first run of this looked like: the
     * desktop was being composited behind a window that then hid it.
     */
    @Test
    fun fr29_a_window_made_of_chrome_lets_its_backdrop_through() {
        val glass = glassTheme()
        val chrome = tableOf(TREE_MADE_OF_CHROME)
        val fill = chrome.windowFill(chrome.roots, glass)
        if (platformBacksWindowWithMaterial()) {
            // None of it. The material behind the window is the page, and a page painted
            // over it is paint over the thing that was asked for: three quarters opacity
            // left four percent of the backdrop showing, which measured as two levels.
            assertEquals(0f, fill.alpha, "a painted page hides the window's own backdrop")
        } else {
            assertEquals(1f, fill.alpha, "nothing is put behind the window here to reveal")
        }
    }

    /**
     * A window that did not ask comes up the way it always did.
     *
     * This is half of what the requirement asks for, and the more important half: the
     * feature must cost nothing to every window that is not using it. The other half is
     * that no effect view is built for such a window, which is the same answer read by
     * the shim that would build one.
     */
    @Test
    fun fr29_a_window_that_did_not_ask_is_painted_as_before() {
        val glass = glassTheme()
        val painted = tableOf(TREE_PAINTED_BY_THE_APPLICATION)
        assertEquals(1f, painted.windowFill(painted.roots, glass).alpha)
        assertTrue(!painted.asksForWindowMaterial(painted.roots))
    }

    /**
     * Any material anywhere asks, not the page alone.
     *
     * No application on this platform is see-through all over: a sidebar is vibrant and
     * the page beside it is not. So the question the window has to settle before it opens
     * is whether anything in the tree will be glass, and where that glass ends up is
     * decided later, where it is drawn.
     */
    @Test
    fun fr29_a_material_anywhere_in_the_tree_asks_for_a_backdrop() {
        val chrome = tableOf(TREE_MADE_OF_CHROME)
        assertTrue(chrome.asksForWindowMaterial(chrome.roots))

        // A sidebar made of glass under a page that is not.
        val sidebar = tableOf(
            listOf(
                Mutation.Create(ROOT, WidgetKind.Column),
                Mutation.Create(LABEL + 1, WidgetKind.Column),
                Mutation.SetModifier(LABEL + 1, 0, ProtocolModifier.Material(MaterialRole.Thick)),
                Mutation.Insert(ROOT, LABEL + 1, 0),
            ) + label(LABEL, ROOT, 1, "Page"),
        )
        assertTrue(sidebar.asksForWindowMaterial(sidebar.roots))
    }

    /** A tree with no material anywhere leaves the window exactly as it was. */
    @Test
    fun fr29_a_tree_with_no_material_asks_for_nothing() {
        val plain = tableOf(TREE_WITHOUT_A_BAR)
        assertTrue(!plain.asksForWindowMaterial(plain.roots))
    }

    /** A material that goes away takes the window's reason for a backdrop with it. */
    @Test
    fun fr29_removing_the_last_material_stops_asking() {
        val table = tableOf(TREE_MADE_OF_CHROME)
        assertTrue(table.asksForWindowMaterial(table.roots))
        table.apply(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Empty))
        assertTrue(!table.asksForWindowMaterial(table.roots))
    }

    private fun glassTheme() = resolveTheme(
        theme = Theme(
            DesignSystem.LiquidGlass,
            DesignSystem.LiquidGlass,
            ColorScheme.Light,
            adaptive = false,
        ),
        platform = HostPlatform.MacOs,
        systemDark = false,
    )

    /**
     * A bar that has the caption covers the strip and lays its content out beside the
     * window buttons, rather than drawing the application's title where the traffic
     * lights are or starting a second row below them.
     */
    @Test
    fun fr19_2_a_bar_lays_its_content_out_clear_of_the_window_buttons() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(TREE_WITH_A_BAR)),
                    caption = CAPTION,
                )
            }
        }
        waitForIdle()

        val bar = onNodeWithTag(nodeTestTag(BAR)).getBoundsInRoot()
        val title = onNodeWithTag(nodeTestTag(LABEL)).getBoundsInRoot()
        // The bar itself starts at the very top of the window: its surface covers the
        // strip instead of beginning underneath it.
        assertTrue(bar.top.value <= 0.5f, "the bar starts at ${bar.top}, not at the top of the window")
        assertTrue(
            bar.bottom - bar.top >= CAPTION.height,
            "the bar is ${bar.bottom - bar.top} tall, shorter than the ${CAPTION.height} caption it covers",
        )
        assertTrue(
            title.left >= CAPTION.buttonsWidth,
            "the title starts at ${title.left}, over the window buttons",
        )
        // One row, not two: the title shares the line the window buttons are on.
        assertTrue(
            title.top < CAPTION.height,
            "the title starts at ${title.top}, on a second row below the window buttons",
        )
    }

    /** Without a bar the page keeps the caption, so nothing is drawn under the buttons. */
    @Test
    fun fr19_2_content_without_a_bar_starts_below_the_caption() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(TREE_WITHOUT_A_BAR)),
                    caption = CAPTION,
                )
            }
        }
        waitForIdle()

        val title = onNodeWithTag(nodeTestTag(LABEL)).getBoundsInRoot()
        assertTrue(
            title.top >= CAPTION.height,
            "the title starts at ${title.top}, inside the ${CAPTION.height} caption",
        )
    }
}
