package dioxus.compose.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.platform.FrameRequestSource
import dioxus.compose.ui.platform.LocalFrameRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import dioxus.compose.protocol.Modifier as ProtocolModifier

private const val ROOT = 1
private const val BUTTON = 2
private const val LABEL = 3

/** A mint no design system holds, so finding it proves it came from the application. */
private const val MINT = 0xffa7f3d0.toInt()

/**
 * A button drawn in the colour the application named.
 *
 * A unified sample names its reference's own colours, and its chips are buttons. The
 * variant used to paint over whatever the Host had asked for, so a mint chip and a white
 * pill both arrived in the active design system's accent, and every unified sample came
 * out blue whatever its reference said.
 */
@OptIn(ExperimentalTestApi::class)
class ButtonFillTest {
    private val frames = FrameRequestSource()

    @Test
    fun fr14_a_button_keeps_the_fill_the_host_named() = runComposeUiTest {
        val batch = listOf(
            Mutation.Create(ROOT, WidgetKind.Column),
            Mutation.Create(BUTTON, WidgetKind.Button),
            Mutation.SetModifier(BUTTON, 0, ProtocolModifier.Background(Paint.Literal(MINT))),
            Mutation.Create(LABEL, WidgetKind.Text),
            Mutation.SetProp(LABEL, PropertyKind.Text, PropertyValue.Text("Calm")),
            Mutation.Insert(BUTTON, LABEL, 0),
            Mutation.Insert(ROOT, BUTTON, 0),
        )
        setContent {
            CompositionLocalProvider(LocalFrameRequests provides frames) {
                DioxusContent(rememberDioxusHost(FakeHostConnection(batch)))
            }
        }
        // The tag is applied inside the button's own padding, so the parent is what holds
        // the fill. Capturing the node itself would sample the label's ground instead.
        val picture = onNodeWithTag(nodeTestTag(BUTTON)).captureToImage().toAwtImage()
        // The middle of the button, not "somewhere in the capture". The Host's Background
        // is applied to this node's modifier chain whatever else happens, so a search of
        // the whole image finds the mint even when the variant has painted a disc of its
        // own accent on top of it, which is exactly the bug. The centre is the one place
        // that answers which of the two is in front.
        val middle = picture.getRGB(picture.width / 2, picture.height / 2) and 0x00ffffff
        assertEquals(
            MINT and 0x00ffffff,
            middle,
            "the middle of the button is #%06x rather than the fill the application named, "
                .format(middle) +
                "so the design system painted over it and a unified sample cannot say " +
                "what its reference says",
        )
    }
}
