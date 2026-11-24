package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.CaptionTitlePlacement
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.platformCaption
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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
                ComposeRustContent(
                    rememberComposeRustHost(FakeHostConnection(batch(DesignSystem.LiquidGlass))),
                    caption = WindowCaption(height = (-12).dp, buttonsWidth = 0.dp),
                )
            }
        }
        onNodeWithText(TITLE).assertIsDisplayed()
    }

    private fun withPlatform(platform: HostPlatform, block: () -> Unit) {
        hostPlatformOverride = platform
        try {
            block()
        } finally {
            hostPlatformOverride = null
        }
    }

    /**
     * Under GNOME the title is centred in the window, not between the bar's children.
     *
     * A title that sat in the middle of whatever else the bar held would drift as buttons
     * were added beside it, which is not what centring the window title means.
     */
    @Test
    fun fr15_2_a_bar_centres_its_title_where_the_design_system_asks() = withPlatform(HostPlatform.LinuxGnome) {
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch(DesignSystem.Gnome))),
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
    }

    /**
     * Under Material 3 it is at the leading edge, ahead of everything else in the bar.
     */
    @Test
    fun fr15_2_a_bar_leads_with_its_title_where_the_design_system_asks() = withPlatform(HostPlatform.Windows) {
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch(DesignSystem.Material3))),
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

    /**
     * Platform decides whether the bar draws its title: macOS hands it to the system caption
     * under every design system, and every other platform keeps it in the bar.
     */
    @Test
    fun fr30_2_platform_caption_title_placement_follows_platform() {
        for (platform in HostPlatform.entries) {
            val expected = if (platform == HostPlatform.MacOs) {
                CaptionTitlePlacement.SystemCaption
            } else {
                CaptionTitlePlacement.Bar
            }
            for (system in DesignSystem.entries) {
                val theme = resolveTheme(
                    theme = Theme(system, system, ColorScheme.Light, adaptive = false),
                    platform = platform,
                    systemDark = false,
                )
                assertEquals(
                    expected,
                    platformCaption(theme).titlePlacement,
                    "$system on $platform reported wrong caption title placement",
                )
            }
        }
    }

    /**
     * With the same caption height, macOS omits the title node from the bar and Windows draws
     * it in the bar, regardless of whether Cupertino or Fluent 2 is selected.
     */
    @Test
    fun fr30_2_macos_omits_bar_title_and_windows_draws_it_for_cupertino_and_fluent() {
        for (system in listOf(DesignSystem.Cupertino, DesignSystem.Fluent)) {
            withPlatform(HostPlatform.MacOs) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalFrameRequests provides frames) {
                            ComposeRustContent(
                                rememberComposeRustHost(FakeHostConnection(batch(system))),
                                caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                            )
                        }
                    }
                    onNodeWithText(TITLE).assertDoesNotExist()
                    onNodeWithText("Share").assertIsDisplayed()
                }
            }
            withPlatform(HostPlatform.Windows) {
                runComposeUiTest {
                    setContent {
                        CompositionLocalProvider(LocalFrameRequests provides frames) {
                            ComposeRustContent(
                                rememberComposeRustHost(FakeHostConnection(batch(system))),
                                caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                            )
                        }
                    }
                    onNodeWithText(TITLE).assertIsDisplayed()
                    onNodeWithText("Share").assertIsDisplayed()
                }
            }
        }
    }

    /**
     * On Windows and Linux, Cupertino and Liquid Glass windows have no system caption to carry
     * the title, so the bar draws the title itself.
     */
    @Test
    fun fr30_2_windows_and_linux_draw_bar_title_for_apple_design_systems() {
        val platforms = listOf(
            HostPlatform.Windows,
            HostPlatform.LinuxGnome,
            HostPlatform.LinuxKde,
            HostPlatform.LinuxOther,
        )
        for (platform in platforms) {
            for (system in listOf(DesignSystem.Cupertino, DesignSystem.LiquidGlass)) {
                withPlatform(platform) {
                    runComposeUiTest {
                        setContent {
                            CompositionLocalProvider(LocalFrameRequests provides frames) {
                                ComposeRustContent(
                                    rememberComposeRustHost(FakeHostConnection(batch(system))),
                                    caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                                )
                            }
                        }
                        onNodeWithText(TITLE).assertIsDisplayed()
                        onNodeWithText("Share").assertIsDisplayed()
                    }
                }
            }
        }
    }

    /**
     * On a Cupertino screen with no caption strip (height 0, e.g. mobile), the bar draws the
     * title itself.
     */
    @Test
    fun fr30_2_cupertino_draws_bar_title_when_caption_height_is_zero() = withPlatform(HostPlatform.MacOs) {
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch(DesignSystem.Cupertino))),
                        caption = WindowCaption(height = 0.dp, buttonsWidth = 0.dp),
                    )
                }
            }
            onNodeWithText(TITLE).assertIsDisplayed()
        }
    }

    /**
     * The opening bar's title is reported to the platform window via `onCaptionTitle` so the
     * system caption displays the bar's title even when the bar omits it.
     */
    @Test
    fun fr30_2_opening_bar_title_propagates_to_caption_title_callback() = withPlatform(HostPlatform.MacOs) {
        runComposeUiTest {
            val reported = mutableListOf<String>()
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(
                        rememberComposeRustHost(FakeHostConnection(batch(DesignSystem.Cupertino))),
                        caption = WindowCaption(height = 40.dp, buttonsWidth = 0.dp),
                        onCaptionTitle = { reported += it },
                    )
                }
            }
            waitForIdle()
            assertTrue(reported.lastOrNull() == TITLE, "expected caption title $TITLE, got $reported")
        }
    }
}
