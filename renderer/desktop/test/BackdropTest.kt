package dev.darkpyonix.composerust.test

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MaterialRole
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.reduceTransparency
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val STRIPES = 2
private const val GLASS = 3
private const val FIRST_STRIPE = 10

private const val WIDTH = 200
private const val HEIGHT = 100
private const val STRIPE = 10

/** Where the glass node sits: at the root's top left corner, 120 by 60. */
private const val GLASS_WIDTH = 120
private const val GLASS_HEIGHT = 60

/** White at 40%. */
private val FROST = Paint.Literal(0x66ffffff)

/**
 * A box of black and white stripes, ten pixels each, with a node over its top left corner
 * that carries [glass]. The stripes are the first child and the node the second, so the
 * stripes are what is behind it.
 */
private fun scene(system: DesignSystem, vararg glass: ProtocolModifier): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(ROOT, WidgetKind.Box))
    add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.Size(WIDTH.toFloat(), HEIGHT.toFloat())))
    add(Mutation.Create(STRIPES, WidgetKind.Row))
    add(Mutation.SetModifier(STRIPES, 0, ProtocolModifier.Size(WIDTH.toFloat(), HEIGHT.toFloat())))
    for (index in 0 until WIDTH / STRIPE) {
        val id = FIRST_STRIPE + index
        add(Mutation.Create(id, WidgetKind.Box))
        add(Mutation.SetModifier(id, 0, ProtocolModifier.Size(STRIPE.toFloat(), HEIGHT.toFloat())))
        add(
            Mutation.SetModifier(
                id,
                1,
                ProtocolModifier.Background(Paint.Literal(if (index % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt())),
            ),
        )
        add(Mutation.Insert(STRIPES, id, index))
    }
    add(Mutation.Insert(ROOT, STRIPES, 0))
    add(Mutation.Create(GLASS, WidgetKind.Box))
    add(Mutation.SetModifier(GLASS, 0, ProtocolModifier.Size(GLASS_WIDTH.toFloat(), GLASS_HEIGHT.toFloat())))
    glass.forEachIndexed { index, modifier -> add(Mutation.SetModifier(GLASS, 1 + index, modifier)) }
    add(Mutation.Insert(ROOT, GLASS, 1))
}

/** The pixels of the glass node, well inside its edges, row by row. */
private fun PixelMap.glass(): List<Int> = buildList {
    for (y in 10 until GLASS_HEIGHT - 10) {
        for (x in 10 until GLASS_WIDTH - 10) add(this@glass[x, y].toArgb())
    }
}

/** How much neighbouring pixels along the middle row of the glass node differ, summed over the three channels. */
private fun PixelMap.contrastAcrossTheGlass(): Int {
    val y = GLASS_HEIGHT / 2
    var total = 0
    for (x in 10 until GLASS_WIDTH - 11) {
        val a = this[x, y].toArgb()
        val b = this[x + 1, y].toArgb()
        for (shift in listOf(16, 8, 0)) total += abs(((a shr shift) and 0xff) - ((b shr shift) and 0xff))
    }
    return total
}

/** `Backdrop`, the blur an application chooses for itself, as the renderer draws it. */
@OptIn(ExperimentalTestApi::class)
class BackdropTest {
    private fun capture(batch: List<Mutation>, events: MutableList<HostEvent>? = null): PixelMap {
        lateinit var pixels: PixelMap
        runComposeUiTest {
            val connection = FakeHostConnection(batch)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    ComposeRustContent(
                        rememberComposeRustHost(connection),
                        Modifier.requiredSize(WIDTH.dp, HEIGHT.dp),
                    )
                }
            }
            waitForIdle()
            pixels = onRoot().captureToImage().toPixelMap()
            events?.addAll(connection.events)
        }
        return pixels
    }

    /** A blur or a saturation that is negative or not a number is reported, and the modifier is kept for its tint. */
    @Test
    fun fr23_3_1_backdrop_values_out_of_range_are_a_protocol_error() {
        for ((blur, saturation) in listOf(-1f to 1f, Float.NaN to 1f, 20f to -0.5f, 20f to Float.POSITIVE_INFINITY)) {
            val table = NodeTable()
            table.apply(Mutation.Create(GLASS, WidgetKind.Box))
            val modifier = ProtocolModifier.Backdrop(blur, saturation, FROST)
            table.apply(Mutation.SetModifier(GLASS, 0, modifier))
            assertEquals(
                listOf(TableError.INVALID_MODIFIER),
                table.drainErrors().map { it.code },
                "blur $blur, saturation $saturation",
            )
            assertEquals(1, table.node(GLASS)?.modifiers?.size, "the modifier is kept, to be painted as its tint")
        }
        val table = NodeTable()
        table.apply(Mutation.Create(GLASS, WidgetKind.Box))
        table.apply(Mutation.SetModifier(GLASS, 0, ProtocolModifier.Backdrop(0f, 0f, FROST)))
        assertEquals(emptyList(), table.drainErrors())
    }

    /**
     * The same backdrop over the same stripes is the same pixels on every design system,
     * Material 3 among them, and the stripes under it are blurred: neighbouring pixels
     * differ less than they do under the tint alone.
     */
    @Test
    fun fr23_3_1_backdrop_blurs_the_same_on_every_design_system() {
        val blurred = DesignSystem.entries.associateWith { system ->
            capture(scene(system, ProtocolModifier.Backdrop(20f, 1f, FROST)))
        }
        val reference = blurred.getValue(DesignSystem.Material3)
        for ((system, pixels) in blurred) {
            assertEquals(reference.glass(), pixels.glass(), "$system draws the backdrop as Material3 does")
        }
        // The tint alone, with nothing blurred, for comparison.
        val tinted = capture(scene(DesignSystem.Material3, ProtocolModifier.Backdrop(0f, 1f, FROST)))
        val sharp = tinted.contrastAcrossTheGlass()
        val soft = reference.contrastAcrossTheGlass()
        assertTrue(sharp > 0, "the stripes show through the tint")
        assertTrue(soft < sharp / 4, "the stripes were not blurred: $soft against $sharp under the tint alone")
        // Outside the node the stripes are as they were.
        assertEquals(0xff000000.toInt(), reference[5, HEIGHT - 5].toArgb())
        assertEquals(0xffffffff.toInt(), reference[15, HEIGHT - 5].toArgb())
    }

    /** Under reduced transparency the node is its tint, opaque, with no stripe showing through. */
    @Test
    fun fr23_3_1_reduced_transparency_draws_the_tint_only() {
        val previous = reduceTransparency
        reduceTransparency = true
        try {
            val pixels = capture(scene(DesignSystem.LiquidGlass, ProtocolModifier.Backdrop(20f, 1f, FROST)))
            assertEquals(setOf(0xffffffff.toInt()), pixels.glass().toSet(), "the node is its tint at full opacity")
        } finally {
            reduceTransparency = previous
        }
    }

    /** A node with nothing recorded behind it, and one whose values cannot be drawn, are painted the same way. */
    @Test
    fun fr23_3_1_a_backdrop_that_cannot_blur_is_its_tint() {
        // The values are refused, and reported.
        val events = mutableListOf<HostEvent>()
        val refused = capture(scene(DesignSystem.Material3, ProtocolModifier.Backdrop(-1f, 1f, FROST)), events)
        assertEquals(setOf(0xffffffff.toInt()), refused.glass().toSet())
        assertTrue(
            events.filterIsInstance<HostEvent.ProtocolError>().any { it.code == TableError.INVALID_MODIFIER },
            "$events",
        )
        // The first child of a box has nothing of the application's behind it.
        val alone = capture(
            listOf(
                Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)),
                Mutation.Create(ROOT, WidgetKind.Box),
                Mutation.SetModifier(ROOT, 0, ProtocolModifier.Size(WIDTH.toFloat(), HEIGHT.toFloat())),
                Mutation.Create(GLASS, WidgetKind.Box),
                Mutation.SetModifier(GLASS, 0, ProtocolModifier.Size(GLASS_WIDTH.toFloat(), GLASS_HEIGHT.toFloat())),
                Mutation.SetModifier(GLASS, 1, ProtocolModifier.Backdrop(20f, 1f, FROST)),
                Mutation.Insert(ROOT, GLASS, 0),
            ),
        )
        assertEquals(setOf(0xffffffff.toInt()), alone.glass().toSet())
    }

    /** With a material and a backdrop on the same node, what is drawn is the backdrop. */
    @Test
    fun fr23_3_1_backdrop_wins_over_material() {
        for (system in listOf(DesignSystem.LiquidGlass, DesignSystem.Material3)) {
            val backdrop = capture(scene(system, ProtocolModifier.Backdrop(20f, 1f, FROST)))
            val both = capture(
                scene(system, ProtocolModifier.Material(MaterialRole.Regular), ProtocolModifier.Backdrop(20f, 1f, FROST)),
            )
            assertEquals(backdrop.glass(), both.glass(), "$system")
            // And in the other order in the list.
            val reversed = capture(
                scene(system, ProtocolModifier.Backdrop(20f, 1f, FROST), ProtocolModifier.Material(MaterialRole.Regular)),
            )
            assertEquals(backdrop.glass(), reversed.glass(), "$system, material last")
        }
    }
}
