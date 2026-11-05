package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Switch, Slider and Checkbox draw with the colours and the size the Host named, and
 * keep the design system's look for every slot it did not name.
 */
@OptIn(ExperimentalTestApi::class)
class ControlColoursTest {
    private val root = 1
    private val control = 2

    private val ink = 0xFF1C1B19.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()

    private fun literal(argb: Int) = PropertyValue.Integer((2L shl 32) or (argb.toLong() and 0xffffffffL))

    private fun size(width: Float, height: Float) = PropertyValue.Bytes(
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(width).putFloat(height).array(),
    )

    /** One control on a white 240x80 page, with the properties the test names. */
    private fun render(
        system: DesignSystem,
        widget: WidgetKind,
        properties: List<Pair<PropertyKind, PropertyValue>>,
        extra: List<Mutation> = emptyList(),
        check: androidx.compose.ui.test.ComposeUiTest.(java.awt.image.BufferedImage) -> Unit,
    ) {
        runComposeUiTest {
            val mutations = buildList {
                add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
                add(Mutation.Create(root, WidgetKind.Box))
                add(Mutation.SetModifier(root, 0, ProtocolModifier.Size(240f, 80f)))
                add(Mutation.SetModifier(root, 1, ProtocolModifier.Background(Paint.Literal(white))))
                add(Mutation.Create(control, widget))
                properties.forEach { (kind, value) -> add(Mutation.SetProp(control, kind, value)) }
                addAll(extra)
                add(Mutation.Insert(root, control, 0))
            }
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations)))
            }
            waitForIdle()
            check(onNodeWithTag(nodeTestTag(root)).captureToImage().toAwtImage())
        }
    }

    @Test
    fun fr67_a_switch_with_a_size_and_colours_measures_that_size_and_draws_them() {
        DesignSystem.entries.forEach { system ->
            render(
                system,
                WidgetKind.Switch,
                listOf(
                    PropertyKind.Checked to PropertyValue.Bool(true),
                    PropertyKind.ControlSize to size(36f, 20f),
                    PropertyKind.TrackColor to literal(ink),
                    PropertyKind.ThumbColor to literal(red),
                ),
            ) { image ->
                onNodeWithTag(nodeTestTag(control)).assertWidthIsEqualTo(36.dp)
                onNodeWithTag(nodeTestTag(control)).assertHeightIsEqualTo(20.dp)
                // The track, left of the thumb, and the thumb, 16 dp across at the right end.
                assertEquals(ink, image.getRGB(5, 10), "$system: the checked track")
                assertEquals(red, image.getRGB(26, 10), "$system: the thumb")
                // Outside the 36x20 box is the page.
                assertEquals(white, image.getRGB(40, 10), "$system: beyond the track")
            }
        }
    }

    @Test
    fun fr67_a_slider_draws_the_filled_and_the_remaining_track_in_the_colours_named() {
        listOf(DesignSystem.Material3, DesignSystem.Fluent).forEach { system ->
            render(
                system,
                WidgetKind.Slider,
                listOf(
                    PropertyKind.Value to PropertyValue.Float(0.5f),
                    PropertyKind.Min to PropertyValue.Float(0f),
                    PropertyKind.Max to PropertyValue.Float(1f),
                    PropertyKind.ActiveTrackColor to literal(ink),
                    PropertyKind.TrackColor to literal(green),
                    PropertyKind.ThumbColor to literal(red),
                ),
                extra = listOf(Mutation.SetModifier(control, 0, ProtocolModifier.Size(200f, 48f))),
            ) { image ->
                assertEquals(ink, image.getRGB(30, 24), "$system: the filled part")
                assertEquals(green, image.getRGB(170, 24), "$system: the remaining part")
            }
        }
    }

    @Test
    fun fr67_a_checked_checkbox_is_a_filled_square_with_a_check_in_the_colour_named() {
        DesignSystem.entries.forEach { system ->
            render(
                system,
                WidgetKind.Checkbox,
                listOf(
                    PropertyKind.Checked to PropertyValue.Bool(true),
                    PropertyKind.ControlSize to size(18f, 18f),
                    PropertyKind.ContainerColor to literal(ink),
                    PropertyKind.CheckColor to literal(white),
                ),
                extra = listOf(Mutation.SetModifier(control, 0, ProtocolModifier.ShapeRole(ShapeRole.None))),
            ) { image ->
                onNodeWithTag(nodeTestTag(control)).assertWidthIsEqualTo(18.dp)
                onNodeWithTag(nodeTestTag(control)).assertHeightIsEqualTo(18.dp)
                // The corner of a square filled box is the container colour.
                assertEquals(ink, image.getRGB(1, 1), "$system: the box")
                assertEquals(ink, image.getRGB(16, 2), "$system: the box")
                // The check passes through the middle of the box in white.
                var whitePixels = 0
                for (y in 2 until 16) for (x in 2 until 16) if (image.getRGB(x, y) == white) whitePixels++
                check(whitePixels > 8) { "$system: no white check inside the box ($whitePixels)" }
            }
        }
    }
}
