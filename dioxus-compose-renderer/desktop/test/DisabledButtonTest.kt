package dioxus.compose.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertNotEquals

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
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch(system, enabled))))
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
}
