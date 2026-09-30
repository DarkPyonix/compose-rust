package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val ROOT = 1
private const val BAR = 2
private const val ACTION = 3
private const val TITLE = "Notes"

/**
 * Where a window's own bar puts its title.
 *
 * Three design systems centre it and the rest leave it at the leading edge. The value
 * saying which has been in `CaptionStyle` from the beginning and nothing read it, because
 * a bar's children are an arbitrary tree and there was no way to tell which of them was
 * the title. It is a property of the bar now, so there is.
 */
@OptIn(ExperimentalTestApi::class)
class BarTitleTest {
    private val frames = FrameRequestSource()

    /** `Column { TopAppBar(title) { Button } }`, under one design system. */
    private fun batch(system: DesignSystem) = listOf(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
        Mutation.Create(ROOT, WidgetKind.Column),
        Mutation.Create(BAR, WidgetKind.TopAppBar),
        Mutation.SetProp(BAR, PropertyKind.Text, PropertyValue.Text(TITLE)),
        // A button's label is its own Text property, not a child node.
        Mutation.Create(ACTION, WidgetKind.Button),
        Mutation.SetProp(ACTION, PropertyKind.Text, PropertyValue.Text("Share")),
        Mutation.Insert(BAR, ACTION, 0),
        Mutation.Insert(ROOT, BAR, 0),
    )

    /**
     * A caption measured shorter than nothing does not take the window down.
     *
     * A platform reads its caption as the difference between two rectangles, and while a
     * window is being resized one of them can be updated before the other, so the
     * difference is negative for a moment. The page's top is made of it, a negative padding
     * throws from inside composition, and the window closed the first time anyone dragged
     * its edge.
     */
    @Test
    fun fr19_a_caption_measured_below_nothing_does_not_close_the_window() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(batch(DesignSystem.LiquidGlass))),
                    caption = WindowCaption(height = (-12).dp, buttonsWidth = 0.dp),
                )
            }
        }
        onNodeWithText(TITLE).assertIsDisplayed()
    }

    /**
     * Under GNOME the title is centred in the window, not between the bar's children.
     *
     * A title that sat in the middle of whatever else the bar held would drift as buttons
     * were added beside it, which is not what centring the window title means.
     */
    @Test
    fun fr15_2_a_bar_centres_its_title_where_the_design_system_asks() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(batch(DesignSystem.Gnome))),
                    caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                )
            }
        }
        onNodeWithText(TITLE).assertIsDisplayed()
        val bar = onNodeWithTag(nodeTestTag(BAR)).getBoundsInRoot()
        val title = onNodeWithText(TITLE).getBoundsInRoot()
        val barMiddle = (bar.left + bar.right) / 2
        val titleMiddle = (title.left + title.right) / 2
        assertTrue(
            abs((titleMiddle - barMiddle).value) < 2f,
            "the title's middle is at $titleMiddle and the bar's is at $barMiddle, so it " +
                "was laid out beside the other children rather than centred in the window",
        )
    }

    /**
     * Under Material 3 it is at the leading edge, ahead of everything else in the bar.
     */
    @Test
    fun fr15_2_a_bar_leads_with_its_title_where_the_design_system_asks() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(batch(DesignSystem.Material3))),
                    caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                )
            }
        }
        val title = onNodeWithText(TITLE).getBoundsInRoot()
        val action = onNodeWithText("Share").getBoundsInRoot()
        assertTrue(
            title.left < action.left,
            "the title starts at ${title.left} and the bar's button at ${action.left}, so " +
                "the title is not at the leading edge",
        )
    }
}
