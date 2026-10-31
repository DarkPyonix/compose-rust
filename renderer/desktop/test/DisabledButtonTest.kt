package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.IconRole
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
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A button that cannot be pressed has to look like one.
 *
 * `Enabled` has been on the wire since the first milestone and the Renderer used it for
 * one thing: refusing the click. So a disabled button was drawn pixel for pixel like a
 * live one and the only way to find out was to press it. A menu entry that moves a row up
 * shipped disabled on the first row of every list, looking exactly like the entry below
 * it that works.
 *
 * Every design system, because the fade is one of their values and a system that answered
 * with a fully opaque one would pass a test that only looked at Material 3.
 */
@OptIn(ExperimentalTestApi::class)
class DisabledButtonTest {
    private val root = 1
    private val button = 2

    private fun batch(system: DesignSystem, enabled: Boolean) = listOf(
        Mutation.SetTheme(Theme(system, system, ColorScheme.Light, false)),
        Mutation.Create(root, WidgetKind.Box),
        Mutation.Create(button, WidgetKind.Button),
        Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("Move up")),
        Mutation.SetProp(
            button,
            PropertyKind.Variant,
            PropertyValue.Integer(ButtonVariant.Filled.ordinal + 1L),
        ),
        Mutation.SetProp(button, PropertyKind.Enabled, PropertyValue.Bool(enabled)),
        Mutation.Insert(root, button, 0),
    )

    /**
     * Everything the button draws, reduced to one number.
     *
     * The whole of it rather than the middle of it. This used to read the centre pixel
     * and call it the fill, which was true for six of the seven: under Liquid Glass a
     * filled button is translucent, so over a white page its centre is white whether the
     * button is live or not, and what the test was actually comparing there was the
     * label, which happened to cross the centre. The day the typeface changed the label
     * moved off that pixel and the test failed without anything about buttons changing.
     *
     * Comparing the rendering is also the stronger question. A disabled button has to be
     * visibly different, and it does not matter which part of it carries that.
     */
    private fun renderingOf(system: DesignSystem, enabled: Boolean): Int {
        var digest = 0
        runComposeUiTest {
            setContent {
                ComposeRustContent(rememberComposeRustHost(FakeHostConnection(batch(system, enabled))))
            }
            waitForIdle()
            val image = onNodeWithTag(nodeTestTag(button)).captureToImage().toAwtImage()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    digest = digest * 31 + image.getRGB(x, y)
                }
            }
        }
        return digest
    }

    @Test
    fun fr13_a_disabled_button_is_drawn_faded_in_every_design_system() {
        DesignSystem.entries.forEach { system ->
            assertNotEquals(
                renderingOf(system, enabled = true),
                renderingOf(system, enabled = false),
                "under $system a disabled button is drawn exactly like a live one, so the " +
                    "only way to find out that it cannot be pressed is to press it",
            )
        }
    }

    /**
     * A button that named its own background and glyph colour fades both as one group when
     * disabled.
     *
     * The node's `Background` is applied on the modifier chain before `HostButton` runs, so
     * placing the disabled fade after the incoming modifier left the background fully opaque
     * and faded the glyph on top of it, turning a white glyph on a dark circle grey while
     * the circle stayed at full strength.
     */
    @Test
    fun fr13_a_disabled_button_fades_the_fill_the_host_named() {
        val ink = 0xFF14181F.toInt()
        val white = 0xFFFFFFFF.toInt()
        fun captureFillChannel(enabled: Boolean): Int {
            var green = 0
            runComposeUiTest {
                val mutations = listOf(
                    Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, false)),
                    Mutation.Create(root, WidgetKind.Box),
                    Mutation.SetModifier(root, 0, ProtocolModifier.Size(80f, 80f)),
                    Mutation.SetModifier(root, 1, ProtocolModifier.Background(Paint.Literal(white))),
                    Mutation.Create(button, WidgetKind.Button),
                    Mutation.SetModifier(button, 0, ProtocolModifier.Size(36f, 36f)),
                    Mutation.SetModifier(button, 1, ProtocolModifier.Background(Paint.Literal(ink))),
                    Mutation.SetModifier(button, 2, ProtocolModifier.ShapeRole(ShapeRole.Full)),
                    Mutation.SetProp(button, PropertyKind.Text, PropertyValue.Text("")),
                    Mutation.SetProp(button, PropertyKind.Icon, PropertyValue.Integer(IconRole.Send.ordinal + 1L)),
                    Mutation.SetProp(button, PropertyKind.Variant, PropertyValue.Integer(ButtonVariant.Text.ordinal + 1L)),
                    Mutation.SetProp(
                        button,
                        PropertyKind.Color,
                        PropertyValue.Integer((2L shl 32) or (white.toLong() and 0xffffffffL)),
                    ),
                    Mutation.SetProp(button, PropertyKind.Enabled, PropertyValue.Bool(enabled)),
                    Mutation.Insert(root, button, 0),
                )
                setContent {
                    ComposeRustContent(rememberComposeRustHost(FakeHostConnection(mutations)))
                }
                waitForIdle()
                // Sample inside the 36x36 button (placed at TopStart of the 80x80 root)
                // away from the centred glyph: on the root's white ground, an unfaded ink
                // circle stays at 0x18 while a faded one blends toward white.
                val image = onNodeWithTag(nodeTestTag(root)).captureToImage().toAwtImage()
                val rgb = image.getRGB(18, 6)
                green = (rgb ushr 8) and 0xFF
            }
            return green
        }
        val live = captureFillChannel(enabled = true)
        val disabled = captureFillChannel(enabled = false)
        assertTrue(
            live <= 0x20,
            "the live button's ink fill should be dark (green=$live), not faded",
        )
        assertTrue(
            disabled in (live + 0x40)..0xE0,
            "a disabled button with an app background must fade that background toward the page " +
                "(live green=$live, disabled green=$disabled)",
        )
    }
}
