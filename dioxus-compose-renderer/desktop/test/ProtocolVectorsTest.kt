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
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.MessageDuration
import dioxus.compose.protocol.Modifier as ProtocolModifier
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.ShapeRole
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Protocol
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.ui.node.nodeTestTag

/**
 * Keeps the interpreter in lockstep with the Rust Host: the checked-in vectors are the bytes
 * both sides are tested against.
 *
 * The reference batch deliberately addresses nodes it never creates, so it doubles as the
 * crash-isolation check: every bad record becomes a `ProtocolError` event rather than
 * taking the process down.
 */
@OptIn(ExperimentalTestApi::class)
class ProtocolVectorsTest {

    @Test
    fun pr4_checked_in_mutation_vector_is_interpreted_without_crashing() = runComposeUiTest {
        val mutations = decodeVector("mutations.bin")
        assertTrue(mutations.isNotEmpty(), "the vector must contain records")

        val connection = FakeHostConnection(mutations)
        lateinit var host: DioxusHost
        // Timed, because this test has hung on CI where it takes seconds here, and a bare
        // waitForIdle() that never returns tells you only that a minute went by. Knowing
        // which of the two phases ate it is the difference between a slow machine and a
        // composition that never settles.
        val started = System.nanoTime()
        setContent { host = rememberDioxusHost(connection) ; DioxusContent(host) }
        val composed = System.nanoTime()
        waitForIdle()
        val idle = System.nanoTime()
        println(
            "vector interpreted: setContent ${(composed - started) / 1_000_000}ms, " +
                "waitForIdle ${(idle - composed) / 1_000_000}ms, " +
                "${mutations.size} records",
        )

        val root = host.table.node(1)
        assertEquals(WidgetKind.Box, root?.widget)
        onNodeWithTag(nodeTestTag(1)).assertIsDisplayed()

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        // A record is unhonourable when it names a node the vector never created, and the
        // asset records because the vector's asset is a four byte stand-in rather than a
        // real picture: it cannot be read, so it is never registered and cannot be
        // released either.
        val created = mutations.filterIsInstance<Mutation.Create>().map { it.nodeId }.toSet()
        val badRecords = mutations.count { mutation ->
            when (mutation) {
                is Mutation.SetProp -> mutation.nodeId !in created
                is Mutation.SetModifier -> mutation.nodeId !in created
                is Mutation.Insert -> mutation.nodeId !in created
                is Mutation.Move -> mutation.nodeId !in created
                is Mutation.Remove -> mutation.nodeId !in created
                is Mutation.SetText -> mutation.nodeId !in created
                is Mutation.AppendText -> mutation.nodeId !in created
                // The theme applies to the tree, not to a node, so it is never a bad record.
                is Mutation.SetTheme -> false
                // Nor does the window: it is about the frame around the tree.
                is Mutation.SetWindow -> false
                is Mutation.Create -> false
                is Mutation.RegisterAsset -> true
                is Mutation.ReleaseAsset -> true
                // A message names no node, so there is no node for it to have got wrong.
                is Mutation.ShowMessage -> false
            }
        }
        assertEquals(
            badRecords,
            errors.size,
            "every unknown-node record must be reported, and nothing else: " +
                errors.map { it.message },
        )
    }

    /**
     * The one record in the vector that is not about a node: both sides have to agree on
     * its two string references, its duration and the handler id behind its action.
     */
    @Test
    fun fr21_the_message_record_in_the_vector_decodes_to_the_same_values() {
        val messages = decodeVector("mutations.bin").filterIsInstance<Mutation.ShowMessage>()
        assertEquals(
            listOf(
                Mutation.ShowMessage(
                    handlerId = 77L,
                    text = "삭제했습니다",
                    action = "Undo",
                    duration = MessageDuration.Long,
                ),
            ),
            messages,
        )
    }

    @Test
    fun fr10_every_modifier_variant_in_the_vector_round_trips() {
        val modifiers = decodeVector("mutations.bin")
            .filterIsInstance<Mutation.SetModifier>()
            .map { it.modifier }
        assertEquals(
            listOf(
                ProtocolModifier.Empty,
                ProtocolModifier.Padding(16f),
                ProtocolModifier.FillMaxWidth,
                ProtocolModifier.FillMaxHeight,
                ProtocolModifier.Width(120f),
                ProtocolModifier.Height(48f),
                ProtocolModifier.Size(20f, 30f),
                ProtocolModifier.Background(Paint.Literal(0xFF112233.toInt())),
                ProtocolModifier.Clickable(42L),
                ProtocolModifier.Background(Paint.Role(ColorRole.Surface)),
                ProtocolModifier.PaddingRole(SpaceRole.Md),
                ProtocolModifier.PaddingEach(1f, 2f, 3f, 4f),
                ProtocolModifier.Weight(0.5f),
                ProtocolModifier.Shape(4f, 8f, 12f, 16f),
                ProtocolModifier.ShapeRole(ShapeRole.Large),
                ProtocolModifier.Border(2f, Paint.Role(ColorRole.Outline)),
                ProtocolModifier.Elevation(6f),
            ),
            modifiers,
        )
    }

    private fun decodeVector(name: String): List<Mutation> {
        val bytes = vectorFile(name).readBytes()
        val mutations = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), mutations::add)
        return mutations
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
