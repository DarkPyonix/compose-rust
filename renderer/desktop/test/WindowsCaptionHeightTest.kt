package dev.darkpyonix.composerust.test

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.platformCaptionHeight
import dev.darkpyonix.composerust.protocol.TitleBar
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On Windows the title bar mode picks the caption's height, and the design system
 * gives the number. The window's hit test is told the same number before it opens (the fork's
 * `dxc_native_window_configure_caption_height`), so the strip that is drawn is the strip that
 * moves the window and answers for the buttons.
 */
class WindowsCaptionHeightTest {

    @Test
    fun fr19_7_windows_normal_caption_is_taller_than_simple() {
        assertEquals(44.dp, platformCaptionHeight(HostPlatform.Windows, TitleBar.Normal))
        assertEquals(32.dp, platformCaptionHeight(HostPlatform.Windows, TitleBar.Simple))
    }

    @Test
    fun fr19_7_a_platform_that_draws_its_own_buttons_gets_no_height_from_here() {
        assertEquals(0.dp, platformCaptionHeight(HostPlatform.MacOs, TitleBar.Normal))
        assertEquals(0.dp, platformCaptionHeight(HostPlatform.MacOs, TitleBar.Simple))
    }
}
