package dioxus.compose.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.ColorScheme
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.DesignTokens
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.Theme
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A panel the application filled itself has to come out in that colour.
 *
 * A grouped container answers a role, and the design system decides what that role looks
 * like. The one exception is the Modifier chain, which is every widget's escape hatch, and
 * a screen that needs a panel in the reading ink rather than in the reading surface has
 * nowhere else to say it.
 *
 * That escape hatch did not work on a container. The chain's fill was drawn first and the
 * design system's fill was drawn over it, so the only part that survived was whatever fell
 * outside the style's shape: a ring of the asked-for colour around a panel in the usual
 * one. The text on it was set in the ink that suits the asked-for colour, so on an
 * inverted card the text came out white on white and the panel was empty.
 *
 * Every part read correctly on its own, which is why this looks at a pixel: the Host sent
 * the Background record, the decoder produced it, and the chain applied it.
 */
@OptIn(ExperimentalTestApi::class)
class ContainerBackgroundTest {

    private val root = 1
    private val panel = 2
    private val label = 3

    /** Cupertino, light, which is the pair the inverted card in the samples is drawn in. */
    private val tokens = DesignTokens.of(DesignSystem.Cupertino)

    private fun batch(background: ProtocolModifier?): List<Mutation> = buildList {
        add(
            Mutation.SetTheme(
                Theme(
                    designSystem = DesignSystem.Cupertino,
                    fallback = DesignSystem.Cupertino,
                    colorScheme = ColorScheme.Light,
                    adaptive = false,
                ),
            ),
        )
        add(Mutation.Create(root, WidgetKind.Box))
        add(Mutation.Create(panel, WidgetKind.Surface))
        if (background != null) {
            add(Mutation.SetModifier(panel, 6, background))
        }
        add(Mutation.SetModifier(panel, 10, ProtocolModifier.PaddingRole(SpaceRole.Xxl)))
        add(Mutation.Create(label, WidgetKind.Text))
        add(Mutation.SetProp(label, PropertyKind.Text, PropertyValue.Text("panel")))
        add(Mutation.Insert(panel, label, 0))
        add(Mutation.Insert(root, panel, 0))
    }

    /**
     * A pixel of the panel's fill: the middle of its top edge.
     *
     * Not a corner. The design system rounds a panel and the chain's own fill is applied
     * before that rounding, so at the corner the two disagree whichever of them is
     * winning, and the pixel there is the page showing through. The middle of the top edge
     * is inside the rounding and above the label, which is where one pixel answers the
     * question being asked.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.fillOf(node: Int): Int {
        val image = onNodeWithTag(nodeTestTag(node)).captureToImage().toAwtImage()
        return image.getRGB(image.width / 2, 1)
    }

    /** The opaque RGB of a role, as `getRGB` reports a pixel. */
    private fun rgbOf(role: ColorRole): Int =
        (0xff shl 24) or (tokens.color(role, dark = false) and 0x00ffffff)

    @Test
    fun fr14_a_container_the_host_filled_comes_out_in_that_colour() = runComposeUiTest {
        setContent {
            DioxusContent(
                rememberDioxusHost(
                    FakeHostConnection(
                        batch(ProtocolModifier.Background(Paint.Role(ColorRole.OnSurface))),
                    ),
                ),
            )
        }
        waitForIdle()

        assertEquals(
            rgbOf(ColorRole.OnSurface),
            fillOf(panel),
            "the panel asked to be filled with the reading ink and came out in some other " +
                "colour, so the design system's fill is still being drawn over the one the " +
                "application set",
        )
    }

    /**
     * The other half of the same rule: a container that asked for nothing still gets the
     * design system's fill. Without this the fix above could be "never fill a container",
     * which would pass the test above and leave every untouched panel transparent.
     */
    @Test
    fun fr14_a_container_that_asked_for_nothing_keeps_the_design_systems_fill() =
        runComposeUiTest {
            setContent {
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch(null))))
            }
            waitForIdle()

            assertEquals(
                rgbOf(ColorRole.SurfaceContainer),
                fillOf(panel),
                "a panel nobody recoloured is not the colour this design system makes a " +
                    "panel out of",
            )
        }
}
