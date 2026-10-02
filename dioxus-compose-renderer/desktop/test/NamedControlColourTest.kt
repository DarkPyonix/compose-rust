package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ROOT = 1
private const val FIRST = 2
private const val SECOND = 3

/** A mint no design system holds, so finding it proves it came from the application. */
private const val MINT = 0xffa7f3d0.toInt()

/**
 * The colour a control was told to draw itself in.
 *
 * A tab strip and a slider are drawn rather than written, so there is no Text underneath
 * to colour instead, and a unified sample whose reference has no accent has no way to say
 * what they should look like unless the widget itself takes a colour.
 *
 * Saying it once has to work whichever way the active design system draws the control.
 * Material marks a selected tab with a bar under it and a segmented control fills the
 * segment instead, and Material hands a slider to its own control where every other
 * system draws the track itself. A colour that reached only one of each pair would do
 * nothing under half the systems, which is worse than not offering it.
 */
@OptIn(ExperimentalTestApi::class)
class NamedControlColourTest {
    private val frames = FrameRequestSource()

    private fun batch(system: DesignSystem, colour: Int?) = listOf(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
        Mutation.Create(ROOT, WidgetKind.Tabs),
        Mutation.SetProp(ROOT, PropertyKind.SelectedIndex, PropertyValue.Integer(0)),
    ) + listOfNotNull(
        // A colour slot carries Paint bits: the top word says which kind, 2 being a
        // literal, and the bottom word is the value.
        colour?.let {
            Mutation.SetProp(
                ROOT,
                PropertyKind.Color,
                PropertyValue.Integer((2L shl 32) or (it.toLong() and 0xffffffffL)),
            )
        },
    ) + listOf(
        Mutation.Create(FIRST, WidgetKind.Text),
        Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("One")),
        Mutation.Insert(ROOT, FIRST, 0),
        Mutation.Create(SECOND, WidgetKind.Text),
        Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("Two")),
        Mutation.Insert(ROOT, SECOND, 1),
    )

    private fun ComposeUiTest.strip(system: DesignSystem, colour: Int?): Boolean {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch(system, colour))))
            }
        }
        waitForIdle()
        return holds(onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toAwtImage(), MINT)
    }

    /** Whether a colour appears anywhere in a capture. */
    private fun holds(picture: java.awt.image.BufferedImage, colour: Int): Boolean {
        val wanted = colour and 0x00ffffff
        for (y in 0 until picture.height) {
            for (x in 0 until picture.width) {
                if ((picture.getRGB(x, y) and 0x00ffffff) == wanted) return true
            }
        }
        return false
    }

    /** Material marks the selection with a bar under the tab. */
    @Test
    fun fr15_a_tab_strip_draws_its_indicator_in_the_colour_the_host_named() = runComposeUiTest {
        assertTrue(
            strip(DesignSystem.Material3, MINT),
            "the strip holds none of the colour the application named, so its indicator " +
                "is still the design system's accent and a unified sample cannot say " +
                "what its reference says",
        )
    }

    /** A segmented control has no indicator: the selection is the filled segment. */
    @Test
    fun fr15_a_segmented_control_fills_the_selection_in_the_colour_the_host_named() =
        runComposeUiTest {
            assertTrue(
                strip(DesignSystem.Cupertino, MINT),
                "a segmented control leaves the indicator empty and fills the segment " +
                    "instead, so a named colour that only reached the indicator would " +
                    "colour nothing here",
            )
        }

    /**
     * A slider's filled track, which the design system draws rather than the strip.
     *
     * Material hands the drawing to its own control and every other system draws the
     * track itself, so a named colour has to reach both routes or a sample comes out
     * accented under one of them.
     */
    @Test
    fun fr15_a_slider_fills_its_track_in_the_colour_the_host_named() = runComposeUiTest {
        for (system in listOf(DesignSystem.Material3, DesignSystem.Cupertino)) {
            assertTrue(
                slider(system, MINT),
                "under $system the slider holds none of the colour the application " +
                    "named, so its track is still the design system's accent",
            )
        }
    }

    private fun ComposeUiTest.slider(system: DesignSystem, colour: Int): Boolean {
        val batch = listOf(
            Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
            Mutation.Create(ROOT, WidgetKind.Slider),
            Mutation.SetProp(ROOT, PropertyKind.Value, PropertyValue.Float(0.75f)),
            Mutation.SetProp(
                ROOT,
                PropertyKind.Color,
                PropertyValue.Integer((2L shl 32) or (colour.toLong() and 0xffffffffL)),
            ),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch)))
            }
        }
        waitForIdle()
        return holds(onNodeWithTag(nodeTestTag(ROOT)).captureToImage().toAwtImage(), colour)
    }

    /** Naming nothing leaves the choice where it belongs. */
    @Test
    fun fr15_a_tab_strip_that_names_no_colour_keeps_the_design_system_s_own() =
        runComposeUiTest {
            assertFalse(
                strip(DesignSystem.Cupertino, null),
                "the strip holds a colour no design system defines, so something is " +
                    "painting it without being asked",
            )
        }
}
