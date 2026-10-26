package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.runtime.WindowActions
import dev.darkpyonix.composerust.runtime.WindowButton
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.WindowGlyph
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.runtime.windowButtonFill
import dev.darkpyonix.composerust.runtime.windowGlyph
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The Windows caption buttons the renderer draws once the window gives up its caption
 * (compose-rust#339): Windows 11 sizes and colours, maximise turning into restore.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsCaptionButtonsTest {
    private fun fluent(scheme: ColorScheme) = resolveTheme(
        theme = Theme(DesignSystem.Fluent, DesignSystem.Fluent, scheme, adaptive = false),
        platform = HostPlatform.Unknown,
        systemDark = scheme == ColorScheme.Dark,
    ).let { it.rules.caption(it) }

    @Test
    fun fr19_4_maximise_shows_restore_while_the_window_is_maximised() {
        assertEquals(WindowGlyph.Maximise, windowGlyph(WindowButton.Maximise, maximised = false))
        assertEquals(WindowGlyph.Restore, windowGlyph(WindowButton.Maximise, maximised = true))
        assertEquals(WindowGlyph.Close, windowGlyph(WindowButton.Close, maximised = true))
        assertEquals(WindowGlyph.Minimise, windowGlyph(WindowButton.Minimise, maximised = true))
    }

    @Test
    fun fr19_4_fluent_buttons_are_windows_11_sized_and_coloured_in_both_schemes() {
        for (scheme in listOf(ColorScheme.Light, ColorScheme.Dark)) {
            val style = fluent(scheme)
            assertEquals(46.dp, style.buttonWidth)
            assertEquals(32.dp, style.buttonHeight)
            val red = Color(0xFFC42B1C)
            assertEquals(red, windowButtonFill(WindowButton.Close, style, hovered = true, pressed = false))
            assertEquals(Color.White, style.closeHoverGlyph)
            val rest = windowButtonFill(WindowButton.Maximise, style, hovered = false, pressed = false)
            val hover = windowButtonFill(WindowButton.Maximise, style, hovered = true, pressed = false)
            val held = windowButtonFill(WindowButton.Maximise, style, hovered = true, pressed = true)
            assertEquals(Color.Transparent, rest, "$scheme: a resting button has no fill")
            assertNotEquals(rest, hover, "$scheme: hover changes the fill")
            assertNotEquals(hover, held, "$scheme: a held button is not the hover fill")
            assertNotEquals(red, windowButtonFill(WindowButton.Close, style, hovered = true, pressed = true))
        }
    }

    @Test
    fun fr19_4_the_maximise_button_reads_restore_when_the_window_is_maximised() = runComposeUiTest {
        val maximised = mutableStateOf(false)
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides FrameRequestSource(),
                LocalWindowActions provides WindowActions({}, {}, {}, maximised = maximised),
            ) {
                ComposeRustContent(
                    rememberComposeRustHost(
                        FakeHostConnection(
                            listOf(
                                Mutation.SetTheme(Theme(DesignSystem.Fluent, DesignSystem.Fluent, ColorScheme.Light, adaptive = false)),
                                Mutation.Create(1, WidgetKind.Column),
                            ),
                        ),
                    ),
                    caption = WindowCaption(height = 32.dp),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("window-button-maximise").assertContentDescriptionEquals("Maximise")
        maximised.value = true
        waitForIdle()
        onNodeWithTag("window-button-maximise").assertContentDescriptionEquals("Restore")
    }
}
