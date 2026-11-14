package dev.darkpyonix.composerust.test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.dialogTestTag
import dev.darkpyonix.composerust.foundation.sheetScrimTestTag
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverlayTokens
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The overlay token scrim, drawn.
 *
 * The token is an ARGB word, like every colour the Host sends. It used to be handed to
 * Compose's `Color(ULong)`, which reads its argument as Compose's own packed value, where the
 * colour sits in the upper half and the lower half names a colour space. Read that way an
 * ARGB word is a colour with every channel zero: the scrim the application asked for came out
 * transparent and what was behind the modal stayed at full strength.
 *
 * These open a Dialog and a Sheet over a white page with only the token set, and read the
 * colour the scrim leaves in a corner the overlay's own surface does not cover.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayScrimColourTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr74_a_token_scrim_draws_half_black_behind_a_dialog() = runComposeUiTest {
        show(WidgetKind.Dialog)
        assertHalfBlack(dialogTestTag(OVERLAY), "Dialog")
    }

    @Test
    fun fr74_a_token_scrim_draws_half_black_behind_a_sheet() = runComposeUiTest {
        show(WidgetKind.Sheet)
        assertHalfBlack(sheetScrimTestTag(OVERLAY), "Sheet")
    }

    private fun ComposeUiTest.show(kind: WidgetKind) {
        val connection = FakeHostConnection(
            listOf(
                Mutation.SetTheme(
                    Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false),
                ),
                Mutation.SetOverlayTokens(OverlayTokens(scrim = HALF_BLACK)),
                Mutation.Create(ROOT, WidgetKind.Column),
                Mutation.Create(OVERLAY, kind),
                Mutation.SetProp(OVERLAY, PropertyKind.Open, PropertyValue.Bool(true)),
                Mutation.Create(LABEL, WidgetKind.Text),
                Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Confirm")),
                Mutation.Insert(OVERLAY, LABEL, 0),
                Mutation.Insert(ROOT, OVERLAY, 0),
            ),
        )
        setContent {
            CompositionLocalProvider(
                LocalFrameRequests provides frames,
                LocalDensity provides Density(1f),
            ) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.assertHalfBlack(tag: String, what: String) {
        val picture = onNodeWithTag(tag).captureToImage().toAwtImage()
        val pixel = picture.getRGB(2, 2)
        val channels = listOf((pixel shr 16) and 0xff, (pixel shr 8) and 0xff, pixel and 0xff)
        // Half black over white is 0x80 of each channel, give or take the rounding of the
        // blend. Without the scrim the corner stays white.
        assertTrue(
            channels.all { abs(it - 0x7f) <= 3 },
            ("the corner behind the $what is #%06x, not the half black a token scrim of " +
                "0x80000000 lays over a white page").format(pixel and 0xffffff),
        )
    }

    private companion object {
        const val ROOT = 1
        const val OVERLAY = 2
        const val LABEL = 3
        const val HALF_BLACK = 0x80000000L
    }
}
