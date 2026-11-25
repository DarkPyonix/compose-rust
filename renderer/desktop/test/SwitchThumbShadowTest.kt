package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.darkpyonix.composerust.design.trackBorderOf
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import dev.darkpyonix.composerust.ui.platform.FrameRequestSource
import dev.darkpyonix.composerust.ui.platform.LocalFrameRequests
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A Switch lifts its thumb with a soft shadow and draws a line around its track when the
 * Host asks, in every design system, and draws exactly as before when it does not.
 */
@OptIn(ExperimentalTestApi::class)
class SwitchThumbShadowTest {
    private val root = 1
    private val control = 2
    private val page = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()

    private val unchecked = PropertyKind.Checked to PropertyValue.Bool(false)

    private fun elevation(dp: Float) = PropertyKind.ThumbElevation to PropertyValue.Float(dp)

    private fun borderBytes(width: Float, paintBits: Long): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(width).putLong(paintBits).array()

    private fun literalBits(argb: Int): Long = (2L shl 32) or (argb.toLong() and 0xffffffffL)

    private fun border(width: Float, argb: Int) =
        PropertyKind.TrackBorder to PropertyValue.Bytes(borderBytes(width, literalBits(argb)))

    /** The control's measured size and every pixel of the 240 by 80 page it sits on. */
    private class Snapshot(val width: Int, val height: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int): Int = pixels[y * PAGE_WIDTH + x]
    }

    /** One switch on a white page, with `properties`, and `afterwards` sent in a later frame. */
    private fun snapshot(
        system: DesignSystem,
        properties: List<Pair<PropertyKind, PropertyValue>>,
        afterwards: List<Mutation> = emptyList(),
    ): Snapshot {
        lateinit var result: Snapshot
        runComposeUiTest {
            val frames = FrameRequestSource()
            val mutations = buildList {
                add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
                add(Mutation.Create(root, WidgetKind.Box))
                add(Mutation.SetModifier(root, 0, ProtocolModifier.Size(PAGE_WIDTH.toFloat(), 80f)))
                add(Mutation.SetModifier(root, 1, ProtocolModifier.Background(Paint.Literal(page))))
                add(Mutation.Create(control, WidgetKind.Switch))
                properties.forEach { (kind, value) -> add(Mutation.SetProp(control, kind, value)) }
                add(Mutation.Insert(root, control, 0))
            }
            val connection = FakeHostConnection(mutations)
            setContent {
                CompositionLocalProvider(LocalFrameRequests provides frames) {
                    ComposeRustContent(rememberComposeRustHost(connection))
                }
            }
            waitForIdle()
            if (afterwards.isNotEmpty()) {
                connection.scheduleFrame(afterwards)
                frames.request()
                waitForIdle()
            }
            val bounds = onNodeWithTag(nodeTestTag(control)).getUnclippedBoundsInRoot()
            val image = onNodeWithTag(nodeTestTag(root)).captureToImage().toAwtImage()
            val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) }
            result = Snapshot(bounds.width.value.toInt(), bounds.height.value.toInt(), pixels)
        }
        return result
    }

    /** How far from white the pixels of a rectangle are, summed over the three channels. */
    private fun darkness(image: Snapshot, xs: IntRange, ys: IntRange): Long {
        var sum = 0L
        for (y in ys) for (x in xs) {
            val pixel = image.at(x, y)
            for (shift in 0..2) sum += 255 - ((pixel shr (shift * 8)) and 0xff)
        }
        return sum
    }

    private fun nearRed(pixel: Int): Boolean = (0..2).all { shift ->
        val a = (pixel shr (shift * 8)) and 0xff
        val b = (red shr (shift * 8)) and 0xff
        kotlin.math.abs(a - b) <= 24
    }

    /**
     * An unchecked switch has its thumb at the start. With an elevation, the track between
     * the thumb and the bottom of the track is darker than with none, and the far end of the
     * track, away from the thumb, is the same. Where the system's own switch is the shared
     * drawing (every system but Material 3), leaving the elevation out is no shadow at all,
     * which is what each of them draws today.
     */
    @Test
    fun fr67_switch_thumb_elevation_draws_a_shadow_under_the_thumb() {
        DesignSystem.entries.forEach { system ->
            val flat = snapshot(system, listOf(unchecked, elevation(0f)))
            val lifted = snapshot(system, listOf(unchecked, elevation(4f)))
            val lower = (flat.height / 2) until flat.height
            val start = 0 until flat.width / 2
            check(darkness(lifted, start, lower) > darkness(flat, start, lower)) {
                "$system: the thumb's elevation drew no shadow under it"
            }
            assertEquals(
                flat.at(flat.width - 4, flat.height / 2),
                lifted.at(lifted.width - 4, lifted.height / 2),
                "$system: the far end of the track changed",
            )
            if (system != DesignSystem.Material3) {
                val unset = snapshot(system, listOf(unchecked))
                assertEquals(flat.pixels.toList(), unset.pixels.toList(), "$system: no elevation is no shadow")
            }
        }
    }

    /** The line goes along the straight top edge of the track, in both states, in every system. */
    @Test
    fun fr67_switch_track_border_draws_in_the_width_and_colour_named() {
        DesignSystem.entries.forEach { system ->
            for (checked in listOf(false, true)) {
                val image = snapshot(
                    system,
                    listOf(PropertyKind.Checked to PropertyValue.Bool(checked), border(2f, red)),
                )
                check(nearRed(image.at(image.width / 2, 1))) {
                    "$system (checked: $checked): the top edge of the track is not the border colour"
                }
                assertEquals(page, image.at(image.width + 4, image.height / 2), "$system: beyond the track")
            }
        }
    }

    /** A width of zero asks for no line, even where the design system draws one. */
    @Test
    fun fr67_switch_track_border_of_zero_width_draws_none() {
        DesignSystem.entries.forEach { system ->
            val image = snapshot(system, listOf(unchecked, border(0f, red)))
            for (y in 0 until image.height) for (x in 0 until image.width) {
                check(!nearRed(image.at(x, y))) { "$system: a red pixel at ($x, $y) with a border of no width" }
            }
        }
    }

    /** Once both are removed the switch is drawn by the design system again, pixel for pixel. */
    @Test
    fun fr67_removing_thumb_elevation_and_track_border_restores_the_default() {
        val named = listOf(elevation(4f), border(2f, red))
        DesignSystem.entries.forEach { system ->
            val reference = snapshot(system, listOf(unchecked))
            val styled = snapshot(system, listOf(unchecked) + named)
            assertNotEquals(reference.pixels.toList(), styled.pixels.toList(), "$system: the two were applied")
            val removed = snapshot(
                system,
                listOf(unchecked) + named,
                afterwards = named.map { (kind, _) -> Mutation.SetProp(control, kind, PropertyValue.None) },
            )
            assertEquals(reference.width, removed.width, "$system: the width after the removal")
            assertEquals(reference.height, removed.height, "$system: the height after the removal")
            assertEquals(reference.pixels.toList(), removed.pixels.toList(), "$system: the pixels after the removal")
        }
    }

    /** The 12 bytes are the width, then the paint; anything short or out of range is not a border. */
    @Test
    fun fr67_track_border_decodes_its_width_and_paint() {
        assertEquals(1.5f to Paint.Literal(red), trackBorderOf(borderBytes(1.5f, literalBits(red))))
        val outline = (1L shl 32) or (ColorRole.Outline.ordinal + 1).toLong()
        assertEquals(0f to Paint.Role(ColorRole.Outline), trackBorderOf(borderBytes(0f, outline)))
        assertNull(trackBorderOf(null))
        assertNull(trackBorderOf(ByteArray(8)))
        assertNull(trackBorderOf(borderBytes(-1f, literalBits(red))))
        assertNull(trackBorderOf(borderBytes(Float.NaN, literalBits(red))))
        assertNull(trackBorderOf(borderBytes(1f, 0L)))
    }

    private companion object {
        const val PAGE_WIDTH = 240
    }
}
