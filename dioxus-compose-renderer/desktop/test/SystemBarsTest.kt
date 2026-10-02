package dioxus.compose.test

import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.runtime.SystemBars
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.opensWithANavigation
import dioxus.compose.runtime.pageInsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A phone: a clock above and a gesture bar below. */
private val PHONE = SystemBars(top = 24.dp, bottom = 48.dp)

/**
 * What the page gives up to the strips the system draws in.
 *
 * Padding everything by the safe area is the obvious thing and it is wrong: the system's
 * own background shows through both strips and the result is a white band under the clock
 * and a grey one above the gesture bar, which no other application on the device has. The
 * window's fill is drawn edge to edge instead, and each strip is given to whichever part
 * of the tree should grow into it.
 */
class SystemBarsTest {
    private fun tableOf(batch: List<Mutation>): NodeTable {
        val table = NodeTable()
        batch.forEach(table::apply)
        return table
    }

    @Test
    fun fr19_a_bar_that_opens_the_tree_takes_the_strip_above_it() {
        val (top, _) = pageInsets(WindowCaption.None, PHONE, barTakesTheTop = true, navigationTakesTheBottom = false)
        assertEquals(
            0.dp,
            top,
            "the page stopped below the status bar as well, so the bar's own surface " +
                "ends under the clock and a band of page colour sits above it",
        )
    }

    @Test
    fun fr19_a_page_with_no_bar_starts_below_the_status_bar() {
        val (top, _) = pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = false)
        assertEquals(24.dp, top, "the first line of the application is drawn through the clock")
    }

    /**
     * To the page they add up, because both are strips it has to start below.
     *
     * To the bar they do not: window buttons sit on its row and a clock sits above it.
     * That is why the bar is handed the two separately.
     */
    @Test
    fun fr19_a_window_caption_and_a_status_bar_add_up_for_the_page() {
        val (top, _) = pageInsets(
            WindowCaption(height = 32.dp),
            PHONE,
            barTakesTheTop = false,
            navigationTakesTheBottom = false,
        )
        assertEquals(56.dp, top)
    }

    /** The bar keeps them apart: one is a row it shares, the other is a strip above it. */
    @Test
    fun fr19_a_bar_is_told_the_status_bar_and_the_caption_separately() {
        val strip = WindowCaption(height = 32.dp).copy(insetTop = PHONE.top)
        assertEquals(32.dp, strip.height, "the row the bar shares with the window buttons")
        assertEquals(
            24.dp,
            strip.insetTop,
            "the strip above it, which its surface covers and its content starts below",
        )
    }

    @Test
    fun fr19_a_navigation_bar_takes_the_strip_below_it() {
        val (_, bottom) = pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = true)
        assertEquals(
            0.dp,
            bottom,
            "the page stopped above the gesture bar as well, so the navigation's own " +
                "colour ends short of the bottom edge",
        )
    }

    @Test
    fun fr19_a_page_with_no_navigation_stops_above_the_gesture_bar() {
        val (_, bottom) = pageInsets(WindowCaption.None, PHONE, barTakesTheTop = false, navigationTakesTheBottom = false)
        assertEquals(48.dp, bottom, "the last line of the application is drawn under the gesture bar")
    }

    /** A desktop has neither strip, and nothing about it changes. */
    @Test
    fun fr19_a_window_with_no_system_bars_gives_up_nothing() {
        val (top, bottom) = pageInsets(
            WindowCaption.None,
            SystemBars.None,
            barTakesTheTop = false,
            navigationTakesTheBottom = false,
        )
        assertEquals(0.dp, top)
        assertEquals(0.dp, bottom)
    }

    /**
     * Only the navigation that owns the window grows into the bottom strip.
     *
     * One nested inside part of a screen has content below it, so a bar of its own that
     * reached for the bottom edge would leave a gap in the middle of the screen.
     */
    @Test
    fun fr19_only_a_navigation_that_owns_the_window_takes_the_bottom_strip() {
        val owns = tableOf(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Navigation),
                Mutation.Insert(1, 2, 0),
            ),
        )
        assertTrue(
            owns.opensWithANavigation(owns.roots),
            "a wrapper the reader never sees is passed through, so this navigation is " +
                "still the first thing the window draws",
        )

        val nested = tableOf(
            listOf(
                Mutation.Create(1, WidgetKind.Column),
                Mutation.Create(2, WidgetKind.Text),
                Mutation.SetProp(2, PropertyKind.Text, PropertyValue.Text("a heading")),
                Mutation.Insert(1, 2, 0),
                Mutation.Create(3, WidgetKind.Navigation),
                Mutation.Insert(1, 3, 1),
            ),
        )
        assertFalse(
            nested.opensWithANavigation(nested.roots),
            "there is a heading above this navigation, so its bar is not along the " +
                "bottom edge of the window",
        )
    }
}
