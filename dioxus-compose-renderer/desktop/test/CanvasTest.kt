package dioxus.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.DrawCommand
import dioxus.compose.protocol.DrawCommands
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.Protocol
import dioxus.compose.protocol.TypeRole
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag

/**
 * The Canvas side of the checked-in vector: the Host encodes a command list, and the
 * interpreter has to read back the same commands the Host wrote.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasTest {
    @Test
    fun fr17_every_drawing_command_in_the_vector_round_trips() {
        val commands = DrawCommands.decode(canvasBytes())

        assertEquals(
            listOf(
                DrawCommand.Line(Paint.Role(ColorRole.Primary), 1f, 2f, 3f, 4f, 1.5f),
                DrawCommand.Rect(Paint.Literal(0xFF112233.toInt()), 0f, 0f, 8f, 9f, 0f),
                DrawCommand.RoundRect(Paint.Role(ColorRole.Surface), 1f, 2f, 3f, 4f, 5f, 6f),
                DrawCommand.Circle(Paint.Role(ColorRole.Error), 4f, 5f, 6f, 0.5f),
                DrawCommand.Arc(Paint.Role(ColorRole.Outline), 1f, 2f, 3f, 0f, 90f, 2f),
                DrawCommand.PolylineRef(Paint.Role(ColorRole.Secondary), 42, 3f),
                DrawCommand.TextAt(
                    Paint.Role(ColorRole.OnSurface),
                    7 * DrawCommands.COMMAND_LENGTH,
                    "한글".toByteArray(Charsets.UTF_8).size,
                    7f,
                    8f,
                    TypeRole.Label,
                ),
            ),
            commands,
        )
    }

    /**
     * The Renderer resolves the role, so the command list never carries a colour. The
     * design system is what decides what "primary" looks like, canvas or not.
     */
    @Test
    fun fr17_a_color_role_survives_the_wire_unresolved() {
        val paints = DrawCommands.decode(canvasBytes()).map { it.paint }

        assertTrue(paints.contains(Paint.Role(ColorRole.Primary)))
        assertEquals(1, paints.count { it is Paint.Literal }, "only the Rect asked for a literal")
    }

    @Test
    fun fr17_text_offsets_point_past_the_command_records() {
        val bytes = canvasBytes()
        val text = DrawCommands.decode(bytes).filterIsInstance<DrawCommand.TextAt>().single()

        assertEquals("한글", DrawCommands.textOf(bytes, text))
    }

    /** A command list the Host never sent leaves an empty canvas, not a crash. */
    @Test
    fun fr17_a_canvas_without_commands_still_draws() = runComposeUiTest {
        val connection = FakeHostConnection(
            listOf(
                Mutation.Create(1, WidgetKind.Canvas),
                // The size comes from the modifier chain. A canvas nobody sized has
                // nothing to show, which is Compose's own rule for a drawing area.
                Mutation.SetModifier(1, 0, ProtocolModifier.Size(64f, 64f)),
            ),
        )
        lateinit var host: DioxusHost
        setContent {
            host = rememberDioxusHost(connection)
            DioxusContent(host)
        }
        waitForIdle()

        onNodeWithTag(nodeTestTag(1)).assertIsDisplayed()
    }

    private fun canvasBytes(): ByteArray {
        val bytes = vectorFile("mutations.bin").readBytes()
        val mutations = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), mutations::add)
        val record = mutations
            .filterIsInstance<Mutation.SetProp>()
            .single { it.property == PropertyKind.Commands }
        return (record.value as PropertyValue.Bytes).value
    }

    private fun vectorFile(name: String): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "dioxus-compose/tests/vectors/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("protocol vector $name not found above ${System.getProperty("user.dir")}")
    }
}
