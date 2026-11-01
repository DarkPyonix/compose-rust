package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A container with individual corner radii cuts only the corners it rounded, and a container
 * with its own fill still clips its children to its shape.
 */
@OptIn(ExperimentalTestApi::class)
class CornerRadiiClipTest {

    private val page = Paint.Literal(0xFF102030.toInt())
    private val ink = Paint.Literal(0xFFE03020.toInt())

    @Test
    fun fr_60_per_corner_radii_clip_the_rounded_corners_and_keep_the_sharp_ones() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(page)),
            Mutation.Create(2, WidgetKind.Box),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Shape(24f, 24f, 0f, 0f)),
            Mutation.SetModifier(2, 2, ProtocolModifier.Clip(true)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.Background(ink)),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        assertEquals(Color(0xFF102030), pixels[1, 1], "top-start is rounded, so (1, 1) shows the page")
        assertEquals(Color(0xFFE03020), pixels[1, pixels.height - 2], "bottom-start is 0 dp, so the child fills the corner")
    }

    @Test
    fun fr_60_a_recoloured_card_clips_an_overflowing_child_to_its_corners() = runComposeUiTest {
        val batch = listOf(
            Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
            Mutation.Create(1, WidgetKind.Box),
            Mutation.SetModifier(1, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(1, 1, ProtocolModifier.Background(page)),
            Mutation.Create(2, WidgetKind.Card),
            Mutation.SetModifier(2, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(2, 1, ProtocolModifier.Shape(24f, 24f, 24f, 24f)),
            Mutation.SetModifier(2, 2, ProtocolModifier.Background(ink)),
            Mutation.SetModifier(2, 3, ProtocolModifier.Padding(0f)),
            Mutation.Create(3, WidgetKind.Box),
            Mutation.SetModifier(3, 0, ProtocolModifier.Size(80f, 80f)),
            Mutation.SetModifier(3, 1, ProtocolModifier.Background(ink)),
            Mutation.Insert(2, 3, 0),
            Mutation.Insert(1, 2, 0),
        )
        setContent { ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch))) }
        waitForIdle()
        val pixels = onNodeWithTag(nodeTestTag(1)).captureToImage().toPixelMap()
        assertEquals(Color(0xFF102030), pixels[1, 1], "the card clips its fill and its child at the corner")
        assertEquals(Color(0xFFE03020), pixels[pixels.width / 2, pixels.height / 2])
    }
}
