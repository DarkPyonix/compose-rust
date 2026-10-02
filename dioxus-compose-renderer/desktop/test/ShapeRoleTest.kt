package dioxus.compose.test

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * A corner role has to round the corner it names.
 *
 * A chat bubble asked for the design system's large corner, was filled, and came out with
 * four square corners. Every part in isolation looked right: the Host emitted ShapeRole,
 * the token table had fourteen for that role, and the chain clipped with the resolved
 * shape. Reading the code could not settle it, so this looks at the pixels.
 */
@OptIn(ExperimentalTestApi::class)
class ShapeRoleTest {

    private val root = 1
    private val bubble = 2

    @Test
    fun fr13_a_shape_role_rounds_the_corner_it_names() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(bubble, WidgetKind.Column),
            Mutation.SetModifier(bubble, 5, ProtocolModifier.ShapeRole(ShapeRole.Large)),
            Mutation.SetModifier(
                bubble,
                6,
                ProtocolModifier.Background(Paint.Role(ColorRole.Primary)),
            ),
            Mutation.SetModifier(bubble, 10, ProtocolModifier.PaddingRole(SpaceRole.Xxl)),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("bubble")),
            Mutation.Insert(bubble, 3, 0),
            Mutation.Insert(root, bubble, 0),
        )
        setContent { DioxusContent(rememberDioxusHost(FakeHostConnection(batch))) }
        waitForIdle()

        assertCornerIsRounded(
            "the top left pixel is the same colour as the top edge, so the corner the role " +
                "named was not rounded: the fill reaches all the way into it",
        )
    }

    /** The same thing with an explicit radius, to tell a broken role from a broken path. */
    @Test
    fun fr13_an_explicit_corner_radius_rounds_the_corner() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(root, WidgetKind.Box),
            Mutation.Create(bubble, WidgetKind.Column),
            Mutation.SetModifier(
                bubble,
                5,
                ProtocolModifier.Shape(20f, 20f, 20f, 20f),
            ),
            Mutation.SetModifier(
                bubble,
                6,
                ProtocolModifier.Background(Paint.Role(ColorRole.Primary)),
            ),
            Mutation.SetModifier(bubble, 10, ProtocolModifier.PaddingRole(SpaceRole.Xxl)),
            Mutation.Create(3, WidgetKind.Text),
            Mutation.SetProp(3, PropertyKind.Text, PropertyValue.Text("bubble")),
            Mutation.Insert(bubble, 3, 0),
            Mutation.Insert(root, bubble, 0),
        )
        setContent { DioxusContent(rememberDioxusHost(FakeHostConnection(batch))) }
        waitForIdle()

        assertCornerIsRounded(
            "an explicit twenty dp corner did not round either, so nothing in the shape " +
                "path reaches the screen and the role is not what is broken",
        )
    }

    /**
     * The bubble sits at the top left of the root, so the root's own top left pixel is the
     * bubble's corner. The root is captured rather than the bubble because the test tag is
     * applied after the padding in the chain, which puts the semantics bounds inside the
     * padding, where every pixel is filled whatever the corner does.
     */
    private fun androidx.compose.ui.test.ComposeUiTest.assertCornerIsRounded(why: String) {
        val image = onNodeWithTag(nodeTestTag(root)).captureToImage().toAwtImage()
        val corner = image.getRGB(0, 0)
        val onTheEdge = image.getRGB(image.width / 2, 2)
        assertNotEquals(onTheEdge, corner, why)
    }
}
