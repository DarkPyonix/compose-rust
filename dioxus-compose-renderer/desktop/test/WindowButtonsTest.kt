package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dioxus.compose.design.CaptionSide
import dioxus.compose.design.CaptionTitleAlignment
import dioxus.compose.design.HostPlatform
import dioxus.compose.design.resolveTheme
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.LocalWindowActions
import dioxus.compose.runtime.WindowActions
import dioxus.compose.runtime.WindowCaption
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val LABEL = 2

private val CAPTION = WindowCaption(height = 40.dp, buttonsWidth = 78.dp)

/**
 * `Column { Text }` under a named design system, a window with no bar of its own.
 *
 * The system is named rather than left to adapt, because which end the buttons sit at is
 * one of the things these systems disagree about, and a test that adapted would assert
 * whatever the machine it ran on happens to be.
 */
private fun plainTree(system: DesignSystem) = listOf(
    Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
    Mutation.Create(ROOT, WidgetKind.Column),
    Mutation.Create(LABEL, WidgetKind.Text),
    Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Title")),
    Mutation.Insert(ROOT, LABEL, 0),
)

/**
 * The window's own three buttons.
 *
 * On the platforms where the renderer draws the caption, these are the only way to
 * minimise, maximise or close the window, so they are not decoration: a window drawn
 * without them cannot be closed at all. Each design system draws its own, because the
 * colour, the position and the shape of a window button belong to the design system and
 * the platform and to nothing the Host can reach.
 */
@OptIn(ExperimentalTestApi::class)
class WindowButtonsTest {
    private val frames = FrameRequestSource()

    private fun themeOf(system: DesignSystem) = resolveTheme(
        theme = Theme(system, system, ColorScheme.Light, adaptive = false),
        platform = HostPlatform.Unknown,
        systemDark = false,
    )

    /**
     * No two systems draw the caption alike.
     *
     * Colours are left out on purpose: every system has its own palette, so comparing
     * whole styles would pass while all seven drew the identical strip in seven tints.
     * What is compared is the shape of the thing: which end the buttons sit at, how big
     * they are, whether the glyph is there at rest, and where the title goes.
     */
    @Test
    fun fr19_1_no_two_systems_draw_the_caption_alike() {
        val shapes = DesignSystem.entries.associateWith { system ->
            val theme = themeOf(system)
            val style = theme.rules.caption(theme)
            listOf(
                style.side,
                style.buttonWidth,
                style.buttonHeight,
                style.glyphAtRest,
                style.titleAlignment,
                style.spacing,
                style.edgePadding,
            )
        }
        assertEquals(
            shapes.size,
            shapes.values.distinct().size,
            "a window button belongs to the design system, so no two can draw the set " +
                "alike: $shapes",
        )
    }

    /**
     * The Apple systems put their buttons at the leading edge and everyone else at the
     * trailing edge, and the Linux desktops centre the title.
     *
     * These two are the parts of a caption a reader recognises before reading anything in
     * it, which is why they are asserted by name rather than only by being different.
     */
    @Test
    fun fr19_1_the_two_conventions_are_mirror_images() {
        val leading = setOf(DesignSystem.Cupertino, DesignSystem.LiquidGlass)
        DesignSystem.entries.forEach { system ->
            val theme = themeOf(system)
            val expected = if (system in leading) CaptionSide.Start else CaptionSide.End
            assertEquals(expected, theme.rules.caption(theme).side, "$system puts its buttons at the wrong end")
        }

        val centred = setOf(
            DesignSystem.Gnome,
            DesignSystem.Breeze,
            DesignSystem.Deepin,
            DesignSystem.Cupertino,
            DesignSystem.LiquidGlass,
        )
        DesignSystem.entries.forEach { system ->
            val theme = themeOf(system)
            val expected = if (system in centred) {
                CaptionTitleAlignment.Center
            } else {
                CaptionTitleAlignment.Start
            }
            assertEquals(
                expected,
                theme.rules.caption(theme).titleAlignment,
                "$system puts its window title in the wrong place",
            )
        }
    }

    /**
     * A window with a caption strip draws the buttons, and pressing one operates the
     * window.
     *
     * The strip is what says the renderer owns this caption. Where the platform draws its
     * own, nothing supplies the actions and nothing is drawn, which is the case below.
     */
    @Test
    fun fr19_4_2_pressing_a_window_button_operates_the_window() = runComposeUiTest {
        var minimised = 0
        var maximised = 0
        var closed = 0
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalWindowActions provides WindowActions(
                    minimise = { minimised++ },
                    maximise = { maximised++ },
                    close = { closed++ },
                ),
            ) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(plainTree(DesignSystem.Material3))), caption = CAPTION)
            }
        }
        waitForIdle()

        onNodeWithTag("window-button-close").assertIsDisplayed().performClick()
        onNodeWithTag("window-button-minimise").assertIsDisplayed().performClick()
        onNodeWithTag("window-button-maximise").assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(1, closed, "the close button did not close the window")
        assertEquals(1, minimised, "the minimise button did not minimise the window")
        assertEquals(1, maximised, "the maximise button did not maximise the window")
    }

    /**
     * The buttons sit in the caption strip, at the end their system says.
     *
     * Material is at the trailing edge, so its buttons have to end near the window's
     * right edge rather than start at its left, and they have to be inside the strip
     * rather than below it.
     */
    @Test
    fun fr19_2_the_buttons_sit_in_the_caption_strip() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalWindowActions provides WindowActions({}, {}, {}),
            ) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(plainTree(DesignSystem.Material3))), caption = CAPTION)
            }
        }
        waitForIdle()

        val close = onNodeWithTag("window-button-close").getBoundsInRoot()
        assertTrue(
            close.top >= 0.dp && close.bottom <= CAPTION.height,
            "the close button runs from ${close.top} to ${close.bottom}, outside the " +
                "${CAPTION.height} caption strip",
        )
        val minimise = onNodeWithTag("window-button-minimise").getBoundsInRoot()
        assertTrue(
            minimise.left < close.left,
            "Material puts its buttons at the trailing edge, where close comes last, and " +
                "close is at ${close.left} with minimise at ${minimise.left}",
        )
    }

    /**
     * The Apple order is the mirror image, not the same row moved across.
     *
     * Close comes first at the leading edge. A set that kept the trailing order and only
     * changed ends would put zoom where a reader reaches for close, which is the worst
     * possible place to get wrong.
     */
    @Test
    fun fr19_2_a_leading_set_runs_close_first() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalWindowActions provides WindowActions({}, {}, {}),
            ) {
                DioxusContent(
                    rememberDioxusHost(FakeHostConnection(plainTree(DesignSystem.LiquidGlass))),
                    caption = CAPTION,
                )
            }
        }
        waitForIdle()

        val close = onNodeWithTag("window-button-close").getBoundsInRoot()
        val minimise = onNodeWithTag("window-button-minimise").getBoundsInRoot()
        val maximise = onNodeWithTag("window-button-maximise").getBoundsInRoot()
        assertTrue(
            close.left < minimise.left && minimise.left < maximise.left,
            "a leading set runs close, minimise, zoom, and this one runs ${close.left}, " +
                "${minimise.left}, ${maximise.left}",
        )
    }

    /** Where nothing owns the window, nothing is drawn rather than drawn and dead. */
    @Test
    fun fr19_1_a_platform_that_draws_its_own_buttons_gets_none_from_us() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(plainTree(DesignSystem.Material3))), caption = CAPTION)
            }
        }
        waitForIdle()

        onNodeWithTag("window-button-close").assertDoesNotExist()
    }
}
