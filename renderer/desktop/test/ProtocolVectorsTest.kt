package dev.darkpyonix.composerust.test

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
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MessageDuration
import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import dev.darkpyonix.composerust.protocol.NotificationPresentation
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PaletteEntry
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpanRecords
import dev.darkpyonix.composerust.protocol.TextSpanRecord
import dev.darkpyonix.composerust.protocol.ShapeRole
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.Protocol
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.nodeTestTag

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
        lateinit var host: ComposeRustHost
        // Timed, because this test has hung on CI where it takes seconds here, and a bare
        // waitForIdle() that never returns tells you only that a minute went by. Knowing
        // which of the two phases ate it is the difference between a slow machine and a
        // composition that never settles.
        val started = System.nanoTime()
        setContent { host = rememberComposeRustHost(connection) ; ComposeRustContent(host) }
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
                // Nor does a notification: it is outside the window altogether.
                is Mutation.PostNotification -> false
                is Mutation.WithdrawNotification -> false
                is Mutation.RequestNotificationPermission -> false
                // An animation is played on a node, so one that names a node the vector
                // never created is reported once the batch is in.
                is Mutation.StartAnimation -> mutation.animation.nodeId !in created
                // A control for a key nothing plays under is left alone, not reported.
                is Mutation.ControlAnimation -> false
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

    /**
     * The three notification records, decoded from the bytes the Host wrote: six string
     * references and two enums, one string reference, and a header with nothing after it.
     */
    @Test
    fun fr36_the_notification_records_in_the_vector_decode_to_the_same_values() {
        val notifications = decodeVector("mutations.bin").filter {
            it is Mutation.PostNotification ||
                it is Mutation.WithdrawNotification ||
                it is Mutation.RequestNotificationPermission
        }
        assertEquals(
            listOf(
                Mutation.PostNotification(
                    key = "session/7",
                    title = "세션이 끝났습니다",
                    body = "테스트 214개 통과",
                    channel = "세션",
                    action1 = "열기",
                    action2 = "",
                    importance = NotificationImportance.Urgent,
                    presentation = NotificationPresentation.WhenInactive,
                ),
                Mutation.WithdrawNotification("session/7"),
                Mutation.RequestNotificationPermission,
            ),
            notifications,
        )
    }

    /** The two notification events encode to the bytes the Host decodes. */
    @Test
    fun fr36_the_notification_events_encode_to_the_vector_bytes() {
        val bytes = vectorFile("events.bin").readBytes()
        val activated = ByteBuffer.allocate(64)
        val length = Protocol.encodeEvent(
            HostEvent.NotificationActivated(nodeId = 0, handlerId = 0, action = 1, key = "session/7"),
            activated,
        )
        assertEquals(37, length)
        assertEquals(bytes.copyOfRange(225, 262).toList(), activated.array().copyOf(length).toList())

        val changed = ByteBuffer.allocate(32)
        val changedLength = Protocol.encodeEvent(
            HostEvent.NotificationPermissionChanged(
                nodeId = 0,
                handlerId = 0,
                state = NotificationPermission.Denied,
            ),
            changed,
        )
        assertEquals(20, changedLength)
        assertEquals(bytes.copyOfRange(262, 282).toList(), changed.array().copyOf(changedLength).toList())
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
                ProtocolModifier.Offset(12.5f, -4f),
                ProtocolModifier.RequiredSize(320f, 180f),
                ProtocolModifier.BorderEach(
                    1f, 2f, 3f, 4f,
                    Paint.Literal(0xFF112233.toInt()),
                    Paint.Role(ColorRole.Outline),
                    Paint.Literal(0x80445566.toInt()),
                    Paint.Role(ColorRole.Primary),
                ),
                ProtocolModifier.CornerEach(4f, 8f, 12f, 16f),
                ProtocolModifier.Shadow(0f, 2f, 6f, -1f, Paint.Literal(0x40000000)),
                ProtocolModifier.Clip(true),
                ProtocolModifier.Alpha(0.5f),
                ProtocolModifier.Transform(0.75f, 0.5f, -0.25f, 1.25f, 12f, -6f, 0.5f, 0.25f),
            ),
            modifiers,
        )
    }

    /**
     * The HTML elements in the vector: the box that places its children where the Host put
     * them, and its seven modifiers, two of which are longer than the 28 bytes every older
     * modifier record has. Both decoders have to agree on the length each tag fixes.
     */
    @Test
    fun fr42_the_html_element_records_in_the_vector_decode_to_the_same_values() {
        val mutations = decodeVector("mutations.bin")
        assertEquals(
            Mutation.Create(8, WidgetKind.AbsoluteBox),
            mutations.filterIsInstance<Mutation.Create>().single { it.nodeId == 8 },
        )
        val onBox = mutations.filterIsInstance<Mutation.SetModifier>().filter { it.nodeId == 8 }
        assertEquals((0..7).toList(), onBox.map { it.index })

        // The record lengths, read from the bytes rather than from the decoder: 28 for a
        // value of two words, 36 for the shadow's three and 60 for the border's six.
        val bytes = ByteBuffer.wrap(vectorFile("mutations.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val recordsLength = bytes.getInt(4)
        val lengths = mutableMapOf<Int, Int>()
        var offset = 12
        while (offset < recordsLength) {
            val tag = bytes.getShort(offset).toInt()
            val length = bytes.getShort(offset + 2).toInt()
            if (tag == 3 && bytes.getInt(offset + 4) == 8) {
                lengths[bytes.getShort(offset + 10).toInt()] = length
            }
            offset += length
        }
        assertEquals(
            mapOf(19 to 28, 20 to 28, 21 to 60, 22 to 28, 23 to 36, 24 to 28, 25 to 28, 26 to 44),
            lengths,
        )
    }

    /**
     * A modifier record whose length is not the one its tag fixes is refused, whichever way
     * it is wrong: a border cut down to 28 bytes, and a padding grown to 36.
     */
    @Test
    fun fr42_a_modifier_record_of_the_wrong_length_is_a_protocol_error() {
        for ((modifierTag, length) in listOf(21 to 28, 23 to 28, 1 to 36)) {
            val batch = ByteBuffer.allocate(12 + length).order(ByteOrder.LITTLE_ENDIAN)
            batch.putShort(0).putShort(12).putInt(12 + length).putInt(1)
            batch.putShort(3).putShort(length.toShort()).putInt(1).putShort(0).putShort(modifierTag.toShort())
            batch.position(0)
            val failure = runCatching { Protocol.decode(batch) { } }.exceptionOrNull()
            assertTrue(
                failure is dev.darkpyonix.composerust.protocol.ProtocolException,
                "a $length byte record for modifier $modifierTag decoded instead of being refused: $failure",
            )
        }
    }

    /**
     * The animations in the vector: one per property, a transform for each function kind
     * and one of three functions, and a control record, read from the bytes the Host wrote.
     */
    @Test
    fun fr41_the_animation_records_in_the_vector_decode_to_the_same_values() {
        val mutations = decodeVector("mutations.bin")
        val animations = mutations.filterIsInstance<Mutation.StartAnimation>().map { it.animation }
        assertEquals(9, animations.size)
        val alpha = animations.first()
        assertEquals(dev.darkpyonix.composerust.protocol.AnimatedProperty.Alpha, alpha.property)
        assertEquals(dev.darkpyonix.composerust.protocol.PlaybackDirection.Alternate, alpha.direction)
        assertEquals(dev.darkpyonix.composerust.protocol.FillMode.Both, alpha.fill)
        assertEquals(-250f, alpha.delayMs)
        assertEquals(2.5f, alpha.iterations)
        assertEquals(2 or 8, alpha.events)
        assertEquals(
            listOf(
                dev.darkpyonix.composerust.protocol.Keyframe(
                    0f,
                    dev.darkpyonix.composerust.protocol.Timing.CubicBezier(0.25f, 0.1f, 0.25f, 1f),
                    true,
                    dev.darkpyonix.composerust.protocol.KeyframeValue.Alpha(0f),
                ),
                dev.darkpyonix.composerust.protocol.Keyframe(
                    0.6f,
                    dev.darkpyonix.composerust.protocol.Timing.Steps(4, dev.darkpyonix.composerust.protocol.StepPosition.JumpBoth),
                    false,
                    dev.darkpyonix.composerust.protocol.KeyframeValue.Alpha(1f),
                ),
                dev.darkpyonix.composerust.protocol.Keyframe(
                    1f,
                    dev.darkpyonix.composerust.protocol.Timing.Linear,
                    false,
                    dev.darkpyonix.composerust.protocol.KeyframeValue.Alpha(1f),
                ),
            ),
            alpha.keyframes,
        )
        val kinds = animations.drop(3).map { animation ->
            (animation.keyframes.last().value as dev.darkpyonix.composerust.protocol.KeyframeValue.Transform)
                .functions.map { it.kind }
        }
        assertEquals(
            listOf(
                listOf(dev.darkpyonix.composerust.protocol.TransformFunctionKind.Translate),
                listOf(dev.darkpyonix.composerust.protocol.TransformFunctionKind.Rotate),
                listOf(dev.darkpyonix.composerust.protocol.TransformFunctionKind.Scale),
                listOf(dev.darkpyonix.composerust.protocol.TransformFunctionKind.Skew),
                listOf(dev.darkpyonix.composerust.protocol.TransformFunctionKind.Matrix),
                listOf(
                    dev.darkpyonix.composerust.protocol.TransformFunctionKind.Rotate,
                    dev.darkpyonix.composerust.protocol.TransformFunctionKind.Translate,
                    dev.darkpyonix.composerust.protocol.TransformFunctionKind.Scale,
                ),
            ),
            kinds,
        )
        assertEquals(
            Mutation.ControlAnimation(
                8, 1,
                dev.darkpyonix.composerust.protocol.AnimatedProperty.Alpha, 0,
                dev.darkpyonix.composerust.protocol.AnimationControl.Pause, 1_500_000_000L,
            ),
            mutations.filterIsInstance<Mutation.ControlAnimation>().single(),
        )
    }

    /** The animation event and the motion setting encode to the bytes the Host decodes. */
    @Test
    fun fr41_the_animation_events_encode_to_the_vector_bytes() {
        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(64)
        val length = Protocol.encodeEvent(
            HostEvent.AnimationEvent(
                nodeId = 8,
                handlerId = 0,
                animationId = 7,
                kind = dev.darkpyonix.composerust.protocol.AnimationEventKind.End,
                property = dev.darkpyonix.composerust.protocol.AnimatedProperty.Transform,
                slot = 2,
                iteration = 3,
                elapsedMs = 3000f,
                timeNanos = 123_456_789_012L,
            ),
            out,
        )
        assertEquals(40, length)
        assertEquals(bytes.copyOfRange(282, 322).toList(), out.array().copyOf(length).toList())
        val motion = ByteBuffer.allocate(32)
        val motionLength = Protocol.encodeEvent(
            HostEvent.ReducedMotionChanged(0, 0, dev.darkpyonix.composerust.protocol.ReducedMotion.On),
            motion,
        )
        assertEquals(20, motionLength)
        assertEquals(bytes.copyOfRange(322, 342).toList(), motion.array().copyOf(motionLength).toList())
    }

    /** The theme record carries the palette behind it, and both sides read the same entries. */
    @Test
    fun fr14_10_the_theme_in_the_vector_carries_its_palette() {
        val theme = decodeVector("mutations.bin").filterIsInstance<Mutation.SetTheme>().single().theme
        assertEquals(
            listOf(
                PaletteEntry(ColorRole.Primary, dark = false, argb = 0xFFE8590C.toInt()),
                PaletteEntry(ColorRole.Primary, dark = true, argb = 0xFFFF8A4C.toInt()),
                PaletteEntry(ColorRole.SyntaxKeyword, dark = false, argb = 0x80112233.toInt()),
            ),
            theme.palette,
        )
        assertEquals(emptyList(), theme.paletteProblems)
    }

    /** The text runs in the vector are 36 byte records with a colour and a background each. */
    @Test
    fun fr26_the_runs_in_the_vector_decode_to_the_same_values() {
        val runs = decodeVector("mutations.bin")
            .filterIsInstance<Mutation.SetProp>()
            .single { it.property == PropertyKind.Spans }
            .value as PropertyValue.Bytes
        val records = SpanRecords.decode(runs.value)
        assertEquals(
            listOf(
                TextSpanRecord(
                    start = 0,
                    length = 4,
                    typeRole = null,
                    color = Paint.Role(ColorRole.SyntaxKeyword),
                    background = Paint.Role(ColorRole.DiffAddedEmphasis),
                    bold = true,
                    italic = false,
                    underline = false,
                    strikethrough = false,
                    handlerId = 0L,
                ),
                TextSpanRecord(
                    start = 5,
                    length = 2,
                    typeRole = null,
                    color = null,
                    background = Paint.Literal(0xFF445566.toInt()),
                    bold = false,
                    italic = false,
                    underline = false,
                    strikethrough = false,
                    handlerId = 0L,
                ),
            ),
            records,
        )
    }

    /** A split pane's one property of its own crosses as the boolean it is. */
    @Test
    fun fr15_2_12_collapsible_in_the_vector_decodes_to_the_same_value() {
        val collapsible = decodeVector("mutations.bin")
            .filterIsInstance<Mutation.SetProp>()
            .single { it.property == PropertyKind.Collapsible }
        assertEquals(Mutation.SetProp(2, PropertyKind.Collapsible, PropertyValue.Bool(true)), collapsible)
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
            val candidate = File(directory, "compose-rust/tests/vectors/$name")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("protocol vector $name not found above ${System.getProperty("user.dir")}")
    }
}
