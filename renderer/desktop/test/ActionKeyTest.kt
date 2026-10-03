package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ButtonKind
import dev.darkpyonix.composerust.protocol.ButtonVariant
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
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT = 1
private const val KEY = 2

/** A ground no design system paints, so a pixel of it inside the key's box is outside the key. */
private const val MAGENTA = 0xffff00ff.toInt()

/** How far from magenta, per channel, a pixel may be and still be the ground. */
private const val GROUND_TOLERANCE = 64

/**
 * A key of a dense grid, drawn: the declaration crosses the protocol as a kind, and the
 * corner on screen is the one the design system answers for its own calculator.
 *
 * The rule tests in [DesignSystemDifferenceTest] say what each system answers. These say
 * that the answer is what reaches the pixels, which is the half a rule test cannot see: a
 * kind that decoded and was then never read would pass every rule and draw every key as a
 * plain button.
 *
 * The key stands on a magenta ground and is eighty dp square. What is counted is how much
 * ground shows inside the key's own box in its top left corner, which grows with the
 * corner's radius and is untouched by the fill, the label, a border or a soft shadow.
 */
@OptIn(ExperimentalTestApi::class)
class ActionKeyTest {

    private fun batch(system: DesignSystem, kind: ButtonKind?) = buildList {
        add(Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)))
        add(Mutation.Create(ROOT, WidgetKind.Box))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.Background(Paint.Literal(MAGENTA))))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.Padding(24f)))
        add(Mutation.Create(KEY, WidgetKind.Button))
        add(Mutation.SetModifier(KEY, 0, ProtocolModifier.Size(KEY_SIZE, KEY_SIZE)))
        add(Mutation.SetProp(KEY, PropertyKind.Text, PropertyValue.Text("7")))
        add(
            Mutation.SetProp(
                KEY,
                PropertyKind.Variant,
                PropertyValue.Integer(ButtonVariant.Filled.ordinal + 1L),
            ),
        )
        if (kind != null) {
            add(Mutation.SetProp(KEY, PropertyKind.ButtonKind, PropertyValue.Integer(kind.ordinal + 1L)))
        }
        add(Mutation.Insert(ROOT, KEY, 0))
    }

    private fun picture(system: DesignSystem, kind: ButtonKind?): BufferedImage {
        var image: BufferedImage? = null
        runComposeUiTest {
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch(system, kind))))
            }
            waitForIdle()
            image = onNodeWithTag(nodeTestTag(KEY)).captureToImage().toAwtImage()
        }
        return image!!
    }

    private fun isGround(argb: Int): Boolean {
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff
        return abs(red - 0xff) <= GROUND_TOLERANCE &&
            green <= GROUND_TOLERANCE &&
            abs(blue - 0xff) <= GROUND_TOLERANCE
    }

    /** Ground pixels in the top left fifth of the key's box, which is where the corner is. */
    private fun cornerGround(image: BufferedImage): Int {
        val reach = image.width / 5
        var count = 0
        for (y in 0 until reach) {
            for (x in 0 until reach) {
                if (isGround(image.getRGB(x, y))) count++
            }
        }
        return count
    }

    /** Whether the point a tenth of the way in on both axes is outside the key. */
    private fun tenthInIsGround(image: BufferedImage): Boolean =
        isGround(image.getRGB(image.width / 10, image.height / 10))

    @Test
    fun fr30_a_cupertino_action_key_is_a_circle_and_a_fluent_one_is_not() {
        val apple = picture(DesignSystem.Cupertino, ButtonKind.ActionKey)
        val windows = picture(DesignSystem.Fluent, ButtonKind.ActionKey)
        // A tenth of the way in on both axes is inside any rounded rectangle with a corner
        // under a third of the side, and outside a circle.
        assertTrue(
            tenthInIsGround(apple),
            "a Cupertino action key covers the point a tenth of the way into its box, so it is not a circle",
        )
        assertTrue(
            !tenthInIsGround(windows),
            "a Fluent action key leaves the point a tenth of the way into its box uncovered, so its corner is far " +
                "larger than the Windows 11 control corner",
        )
        val appleGround = cornerGround(apple)
        val windowsGround = cornerGround(windows)
        assertTrue(
            appleGround > windowsGround,
            "the Cupertino key shows $appleGround ground pixels in its corner and the Fluent one $windowsGround, " +
                "so Cupertino's corner is not the larger",
        )
    }

    @Test
    fun fr30_a_deepin_action_key_is_rounder_than_a_fluent_one() {
        val deepinGround = cornerGround(picture(DesignSystem.Deepin, ButtonKind.ActionKey))
        val fluentGround = cornerGround(picture(DesignSystem.Fluent, ButtonKind.ActionKey))
        assertTrue(
            deepinGround > fluentGround,
            "the Deepin key shows $deepinGround ground pixels in its corner and the Fluent one $fluentGround, " +
                "so Deepin's corner is not the larger",
        )
    }

    /**
     * The kind is what makes the difference, not the system alone: the same Cupertino
     * button without it is the variant's rounded rectangle and covers the point a circle
     * leaves bare.
     */
    @Test
    fun fr30_the_kind_is_what_turns_a_cupertino_button_into_a_circular_key() {
        val standard = picture(DesignSystem.Cupertino, kind = null)
        assertTrue(
            !tenthInIsGround(standard),
            "a standard Cupertino button already leaves the point a tenth of the way in uncovered, so this test " +
                "cannot tell a key from a button",
        )
        assertTrue(tenthInIsGround(picture(DesignSystem.Cupertino, ButtonKind.ActionKey)))
    }

    /**
     * A button that says it is standard is the same picture as one that says nothing, in
     * every design system. Existing screens send no kind at all, so this is what keeps
     * them as they were.
     */
    @Test
    fun fr30_a_standard_button_is_drawn_as_it_always_was() {
        for (system in DesignSystem.entries) {
            val silent = picture(system, kind = null)
            val standard = picture(system, ButtonKind.Standard)
            assertEquals(silent.width, standard.width, "$system: a standard button changed size")
            assertEquals(silent.height, standard.height, "$system: a standard button changed size")
            for (y in 0 until silent.height) {
                for (x in 0 until silent.width) {
                    assertEquals(
                        silent.getRGB(x, y),
                        standard.getRGB(x, y),
                        "$system: a button declared standard differs at ($x, $y) from one that named no kind",
                    )
                }
            }
        }
    }

    private companion object {
        const val KEY_SIZE = 80f
    }
}
