package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.CaptionSide
import dev.darkpyonix.composerust.design.CaptionStyle
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.design.platformCaption
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.runtime.WindowActions
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tag on the window itself, so a button's place can be read against the window's edges. */
private const val WINDOW_TAG = "caption-window"

/** The two Apple systems, whose own buttons are the traffic lights at the leading edge. */
private val APPLE = listOf(DesignSystem.Cupertino, DesignSystem.LiquidGlass)

/** The Linux sessions the renderer tells apart, and the design system each one's buttons are drawn in. */
private val LINUX_DESKTOPS = listOf(
    HostPlatform.LinuxGnome to DesignSystem.Gnome,
    HostPlatform.LinuxKde to DesignSystem.Breeze,
    HostPlatform.LinuxOther to DesignSystem.Deepin,
)

/** Where each of the three buttons landed, or null for one that was not drawn. */
private class DrawnCaption(
    val window: DpRect,
    val minimise: DpRect?,
    val maximise: DpRect?,
    val close: DpRect?,
    val safeArea: Triple<Float, Float, Float>,
)

/**
 * The window's caption buttons belong to the operating system the window is on, not to the
 * design system the application chose.
 *
 * An application that picks an Apple system and runs on Windows still gets Windows' three
 * buttons at the right, because that is the strip the native window hit tests as buttons and
 * opens Snap Layouts from; on Linux it gets the desktop's own; on macOS the system keeps its
 * traffic lights and the renderer draws nothing. Only the colours still come from the
 * application's design system.
 */
@OptIn(ExperimentalTestApi::class)
class CaptionFollowsOsTest {
    private fun themeOf(system: DesignSystem, platform: HostPlatform, scheme: ColorScheme = ColorScheme.Light): ResolvedTheme =
        resolveTheme(
            theme = Theme(system, system, scheme, adaptive = false),
            platform = platform,
            systemDark = scheme == ColorScheme.Dark,
        )

    /** The caption a design system draws for itself, on the platform it belongs to. */
    private fun ownCaption(system: DesignSystem, platform: HostPlatform): CaptionStyle {
        val theme = themeOf(system, platform)
        return theme.rules.caption(theme)
    }

    /** How much of the caption's width three buttons of [style] take. */
    private fun buttonsWidth(style: CaptionStyle): Dp =
        style.buttonWidth * 3 + style.spacing * 2 + style.edgePadding * 2

    /**
     * Draws an empty window under [system] on [platform] and reads back where its three
     * buttons are and what safe area the Host was told about.
     *
     * [actions] is whether the platform layer hands the content the window's actions, which
     * is what makes the renderer draw buttons at all. macOS hands none.
     */
    private fun draw(
        platform: HostPlatform,
        system: DesignSystem,
        caption: WindowCaption,
        actions: Boolean,
    ): DrawnCaption {
        lateinit var drawn: DrawnCaption
        hostPlatformOverride = platform
        try {
            runComposeUiTest {
                val connection = FakeHostConnection(
                    listOf(
                        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)),
                        Mutation.Create(1, WidgetKind.Column),
                    ),
                )
                setContent {
                    CompositionLocalProvider(
                        LocalFrameRequests provides FrameRequestSource(),
                        LocalWindowActions provides if (actions) WindowActions({}, {}, {}) else null,
                    ) {
                        ComposeRustContent(
                            rememberComposeRustHost(connection),
                            Modifier.requiredSize(800.dp, 600.dp).testTag(WINDOW_TAG),
                            caption = caption,
                        )
                    }
                }
                waitForIdle()
                fun bounds(tag: String): DpRect? =
                    if (onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) null else onNodeWithTag(tag).getBoundsInRoot()
                val event = connection.events.filterIsInstance<HostEvent.WindowSizeChanged>().last { it.nodeId == 0 }
                drawn = DrawnCaption(
                    window = onNodeWithTag(WINDOW_TAG).getBoundsInRoot(),
                    minimise = bounds("window-button-minimise"),
                    maximise = bounds("window-button-maximise"),
                    close = bounds("window-button-close"),
                    safeArea = Triple(event.safeAreaTop, event.safeAreaLeading, event.safeAreaTrailing),
                )
            }
        } finally {
            hostPlatformOverride = null
        }
        return drawn
    }

    private fun assertNear(expected: Dp, actual: Dp, what: String) {
        assertTrue(abs(expected.value - actual.value) < 0.5f, "$what is at $actual, not $expected")
    }

    @Test
    fun fr19_5_windows_draws_windows_caption_buttons_under_every_design_system() {
        for (system in DesignSystem.entries) {
            for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
                val style = platformCaption(themeOf(system, HostPlatform.Windows, scheme))
                val name = "$system ($scheme) on Windows"
                assertEquals(CaptionSide.End, style.side, "$name puts its buttons at the wrong end")
                assertEquals(46.dp, style.buttonWidth, "$name draws a button of the wrong width")
                assertEquals(32.dp, style.buttonHeight, "$name draws a button of the wrong height")
                assertEquals(0.dp, style.spacing, "$name leaves room between its buttons")
                assertEquals(0.dp, style.edgePadding, "$name keeps its buttons off the window's edge")
                assertTrue(style.glyphAtRest, "$name hides its glyphs until the pointer is over them")
                assertEquals(Color(0xFFC42B1C), style.closeHover, "$name does not turn close Windows' red")
            }
        }
        for (system in APPLE + DesignSystem.Material3) {
            val drawn = draw(HostPlatform.Windows, system, WindowCaption(height = 32.dp), actions = true)
            val name = "$system on Windows"
            val right = drawn.window.right
            val top = drawn.window.top
            val minimise = checkNotNull(drawn.minimise) { "$name drew no minimise button" }
            val maximise = checkNotNull(drawn.maximise) { "$name drew no maximise button" }
            val close = checkNotNull(drawn.close) { "$name drew no close button" }
            // The native window answers HTMAXBUTTON from 46 to 92 dip in from the right edge
            // and down to 32, and Snap Layouts opens only over that answer, so the drawn
            // maximise button has to be exactly that rectangle.
            assertNear(right - 92.dp, maximise.left, "$name's maximise button's left edge")
            assertNear(right - 46.dp, maximise.right, "$name's maximise button's right edge")
            assertNear(top, maximise.top, "$name's maximise button's top")
            assertNear(top + 32.dp, maximise.bottom, "$name's maximise button's bottom")
            assertNear(right, close.right, "$name's close button's right edge")
            assertNear(right - 46.dp, close.left, "$name's close button's left edge")
            assertNear(right - 138.dp, minimise.left, "$name's minimise button's left edge")
            assertNear(right - 92.dp, minimise.right, "$name's minimise button's right edge")
            assertEquals(Triple(32f, 0f, 138f), drawn.safeArea, "$name tells the Host the wrong safe area")
        }
    }

    @Test
    fun fr19_5_linux_draws_the_desktops_caption_buttons_under_every_design_system() {
        for ((platform, desktop) in LINUX_DESKTOPS) {
            val native = ownCaption(desktop, platform)
            for (system in DesignSystem.entries) {
                val style = platformCaption(themeOf(system, platform))
                val name = "$system on $platform"
                assertEquals(CaptionSide.End, style.side, "$name puts its buttons at the wrong end")
                assertEquals(native.buttonWidth, style.buttonWidth, "$name draws a button of the wrong width")
                assertEquals(native.buttonHeight, style.buttonHeight, "$name draws a button of the wrong height")
                assertEquals(native.spacing, style.spacing, "$name spaces its buttons unlike $desktop")
                assertEquals(native.edgePadding, style.edgePadding, "$name keeps its buttons a different way off the edge from $desktop")
                assertEquals(native.shape, style.shape, "$name draws its buttons in a different shape from $desktop")
                assertEquals(native.glyphAtRest, style.glyphAtRest, "$name shows its glyphs unlike $desktop")
                assertEquals(native.glyphStroke, style.glyphStroke, "$name strokes its glyphs unlike $desktop")
            }
            for (system in APPLE + DesignSystem.Fluent) {
                val drawn = draw(platform, system, WindowCaption(height = 32.dp), actions = true)
                val name = "$system on $platform"
                val minimise = checkNotNull(drawn.minimise) { "$name drew no minimise button" }
                val maximise = checkNotNull(drawn.maximise) { "$name drew no maximise button" }
                val close = checkNotNull(drawn.close) { "$name drew no close button" }
                assertTrue(
                    minimise.left < maximise.left && maximise.left < close.left,
                    "a trailing set runs minimise, maximise, close, and $name runs " +
                        "${minimise.left}, ${maximise.left}, ${close.left}",
                )
                assertNear(drawn.window.right - native.edgePadding, close.right, "$name's close button's right edge")
                assertNear(native.buttonWidth, close.right - close.left, "$name's close button's width")
                assertTrue(close.bottom <= drawn.window.top + 32.dp, "$name's close button runs below the caption")
                assertEquals(
                    Triple(32f, 0f, buttonsWidth(native).value),
                    drawn.safeArea,
                    "$name tells the Host the wrong safe area",
                )
            }
        }
    }

    @Test
    fun fr19_5_macos_leaves_the_traffic_lights_to_the_system() {
        // What the macOS window measures and hands the content: the title bar's height and
        // the room the system's own traffic lights take at the leading edge.
        val caption = WindowCaption(height = 28.dp, buttonsWidth = 70.dp, buttonsAtStart = true)
        for (system in APPLE + DesignSystem.Fluent + DesignSystem.Material3) {
            val drawn = draw(HostPlatform.MacOs, system, caption, actions = false)
            val name = "$system on macOS"
            assertEquals(null, drawn.close, "$name drew a close button of its own beside the system's")
            assertEquals(null, drawn.minimise, "$name drew a minimise button of its own beside the system's")
            assertEquals(null, drawn.maximise, "$name drew a zoom button of its own beside the system's")
            assertEquals(Triple(28f, 70f, 0f), drawn.safeArea, "$name tells the Host the wrong safe area")
        }
    }

    @Test
    fun fr19_5_the_platforms_own_design_system_draws_the_caption_it_drew_before() {
        val pairs = listOf(HostPlatform.Windows to DesignSystem.Fluent) + LINUX_DESKTOPS
        for ((platform, system) in pairs) {
            for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
                val theme = themeOf(system, platform, scheme)
                assertEquals(
                    theme.rules.caption(theme),
                    platformCaption(theme),
                    "$system ($scheme) on $platform no longer draws its own caption",
                )
            }
        }
    }
}
