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
import dev.darkpyonix.composerust.protocol.FileDelivery
import dev.darkpyonix.composerust.protocol.FileDialogKind
import dev.darkpyonix.composerust.protocol.FileOp
import dev.darkpyonix.composerust.protocol.IoStatus
import dev.darkpyonix.composerust.protocol.FileDialogStatus
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.ProtocolException
import dev.darkpyonix.composerust.protocol.SyntaxSpanRecord
import dev.darkpyonix.composerust.protocol.Severity
import dev.darkpyonix.composerust.protocol.HoverPhase
import dev.darkpyonix.composerust.protocol.DecorationRecord
import dev.darkpyonix.composerust.protocol.DecorationKind
import dev.darkpyonix.composerust.protocol.CodeEditorRecords
import dev.darkpyonix.composerust.protocol.FoldRecord
import dev.darkpyonix.composerust.protocol.GutterDecorationKind
import dev.darkpyonix.composerust.protocol.GutterDecorationRecord
import dev.darkpyonix.composerust.protocol.RevealAlign
import dev.darkpyonix.composerust.protocol.MessageDuration
import dev.darkpyonix.composerust.protocol.MessageHorizontal
import dev.darkpyonix.composerust.protocol.MessageVertical
import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import dev.darkpyonix.composerust.protocol.NotificationPresentation
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverlayTokens
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.BatchBytes
import dev.darkpyonix.composerust.protocol.StorageOp
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PaletteEntry
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TextAlign
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
                is Mutation.SetOverlayTokens -> false
                // Nor does the window: it is about the frame around the tree.
                is Mutation.SetWindow -> false
                // A window's theme and bringing one forward name a node the vector created.
                is Mutation.SetWindowTheme -> mutation.nodeId !in created
                is Mutation.FocusWindow -> mutation.nodeId !in created
                is Mutation.RequestFocus -> mutation.nodeId !in created && mutation.nodeId != 0
                is Mutation.Create -> false
                is Mutation.RegisterAsset -> true
                is Mutation.ReleaseAsset -> true
                // A message names no node, so there is no node for it to have got wrong.
                is Mutation.ShowMessage -> false
                // Nor does a notification: it is outside the window altogether.
                is Mutation.PostNotification -> false
                is Mutation.WithdrawNotification -> false
                is Mutation.RequestNotificationPermission -> false
                // The vector's dialog is on the implicit root window, which always exists.
                is Mutation.ShowFileDialog -> false
                // The clipboard is the platform's, not a node's.
                is Mutation.SetClipboardText -> false
                is Mutation.ReadClipboard -> false
                // A file operation names a handle, not a node, and is answered, not reported.
                is Mutation.FileCall -> false
                is Mutation.ReleaseFileHandle -> false
                // Storage is the application's, not a node's.
                is Mutation.StorageCall -> false
                // A scroll request names the container it asks.
                is Mutation.ScrollTo -> mutation.nodeId !in created
                is Mutation.ScrollIntoView -> mutation.nodeId !in created
                is Mutation.EditCode -> mutation.nodeId !in created
                is Mutation.SetSelection -> mutation.nodeId !in created
                is Mutation.RevealRange -> mutation.nodeId !in created
                is Mutation.SetFolded -> mutation.nodeId !in created
                is Mutation.SetScrollOffset -> mutation.nodeId !in created
                is Mutation.QueryPositionRect -> mutation.nodeId !in created
                is Mutation.EditCodeGroup -> mutation.nodeId !in created
                is Mutation.InsertSnippet -> mutation.nodeId !in created
                is Mutation.SetSelections -> mutation.nodeId !in created
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
                Mutation.ShowMessage(
                    handlerId = 0L,
                    text = "복사했습니다",
                    action = "",
                    duration = MessageDuration.Short,
                    vertical = MessageVertical.Bottom,
                    horizontal = MessageHorizontal.Center,
                    offsetDp = 96f,
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
        assertEquals(bytes.copyOfRange(254, 291).toList(), activated.array().copyOf(length).toList())

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
        assertEquals(bytes.copyOfRange(291, 311).toList(), changed.array().copyOf(changedLength).toList())
    }

    /** The clipboard write, read from the bytes the Host wrote: one string reference. */
    @Test
    fun fr73_set_clipboard_text_round_trips() {
        assertEquals(
            listOf(Mutation.SetClipboardText("초대 링크")),
            decodeVector("mutations.bin").filterIsInstance<Mutation.SetClipboardText>(),
        )
    }

    /**
     * The clipboard read and its answer, read from and encoded to the bytes the Host wrote:
     * one word, then three words and a string reference.
     */
    @Test
    fun fr78_clipboard_read_records_in_the_vector_round_trip() {
        assertEquals(
            listOf(Mutation.ReadClipboard(9)),
            decodeVector("mutations.bin").filterIsInstance<Mutation.ReadClipboard>(),
        )

        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(128)
        val length = Protocol.encodeEvent(
            HostEvent.ClipboardText(
                nodeId = 0,
                handlerId = 0,
                request = 9,
                status = 1,
                length = 15,
                text = "한글\n줄 🙂",
            ),
            out,
        )
        assertEquals(51, length)
        assertEquals(bytes.copyOfRange(1042, 1042 + 51).toList(), out.array().copyOf(length).toList())
    }

    /** The wrapping row and its five layout properties, decoded from the bytes the Host wrote. */
    @Test
    fun fr76_the_flow_row_records_in_the_vector_decode_to_the_same_values() {
        val mutations = decodeVector("mutations.bin")
        assertTrue(Mutation.Create(21, WidgetKind.FlowRow) in mutations)
        assertEquals(
            listOf(
                Mutation.SetProp(21, PropertyKind.HorizontalSpacing, PropertyValue.Float(8f)),
                Mutation.SetProp(21, PropertyKind.VerticalSpacing, PropertyValue.Float(12f)),
                Mutation.SetProp(21, PropertyKind.HorizontalAlignment, PropertyValue.Integer(2)),
                Mutation.SetProp(21, PropertyKind.VerticalAlignment, PropertyValue.Integer(3)),
                Mutation.SetProp(21, PropertyKind.MaxItemsInEachRow, PropertyValue.Integer(3)),
            ),
            mutations.filterIsInstance<Mutation.SetProp>().filter { it.nodeId == 21 },
        )
    }

    /**
     * The width a split pane stacks below decodes to the float the Host wrote, under the tag
     * after the split pane's `Collapsible`.
     */
    @Test
    fun fr15_2_12_collapse_below_round_trips() {
        assertEquals(
            listOf(Mutation.SetProp(2, PropertyKind.CollapseBelow, PropertyValue.Float(720.5f))),
            decodeVector("mutations.bin").filterIsInstance<Mutation.SetProp>()
                .filter { it.property == PropertyKind.CollapseBelow },
        )
    }

    /** The two physical alignments decode to the tags the Host wrote, `Left` 5 and `Right` 6. */
    @Test
    fun fr13_physical_text_align_round_trips() {
        val aligns = decodeVector("mutations.bin").filterIsInstance<Mutation.SetProp>()
            .filter { it.property == PropertyKind.TextAlign }
        assertEquals(
            listOf(
                Mutation.SetProp(2, PropertyKind.TextAlign, PropertyValue.Integer(TextAlign.Left.ordinal + 1L)),
                Mutation.SetProp(2, PropertyKind.TextAlign, PropertyValue.Integer(TextAlign.Right.ordinal + 1L)),
            ),
            aligns,
        )
        assertEquals(listOf(5L, 6L), aligns.map { (it.value as PropertyValue.Integer).value })
    }

    /**
     * The storage request and both events, read from and encoded to the bytes the Host
     * wrote: a request, an operation and three references; two words and a reference to the
     * bytes; and one string reference.
     */
    @Test
    fun fr79_storage_records_round_trip_and_unknown_enums_are_errors() {
        val value = "1\t0\t할 일\u0000".encodeToByteArray()
        assertEquals(
            listOf(
                Mutation.StorageCall(
                    request = 11,
                    op = StorageOp.Write,
                    store = "dev.darkpyonix.composerust.samples.todo",
                    key = "tasks.tsv",
                    data = BatchBytes.of(value),
                ),
            ),
            decodeVector("mutations.bin").filterIsInstance<Mutation.StorageCall>(),
        )

        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(256)
        val answer = HostEvent.IoAnswer(
            nodeId = 0,
            handlerId = 0,
            request = 11,
            status = IoStatus.Ok,
            data = byteArrayOf(0xea.toByte(), 0xb0.toByte(), 0x92.toByte(), 0, 0xff.toByte()),
        )
        val answerLength = Protocol.encodeEvent(answer, out)
        assertEquals(37, answerLength)
        assertEquals(bytes.copyOfRange(1093, 1093 + 37).toList(), out.array().copyOf(answerLength).toList())
        assertTrue("length=5" in answer.toString(), "the answer prints its length, not its bytes")

        out.clear()
        val locationLength = Protocol.encodeEvent(
            HostEvent.StorageLocation(
                nodeId = 0,
                handlerId = 0,
                text = "/data/user/0/dev.darkpyonix.composerust.samples.todo/files",
            ),
            out,
        )
        assertEquals(82, locationLength)
        assertEquals(bytes.copyOfRange(1237, 1237 + 82).toList(), out.array().copyOf(locationLength).toList())

        // An operation this side does not know is a record the two sides disagree on, which
        // the decoder refuses instead of guessing.
        val batch = vectorFile("mutations.bin").readBytes()
        val view = ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN)
        val recordsLength = view.getInt(4)
        var offset = 12
        var patched = false
        while (offset < recordsLength) {
            val tag = view.getShort(offset).toInt()
            val length = view.getShort(offset + 2).toInt()
            if (tag == 32) {
                view.putShort(offset + 8, 9.toShort())
                patched = true
            }
            offset += length
        }
        assertTrue(patched, "the vector holds a storage request")
        val failure = runCatching {
            Protocol.decode(ByteBuffer.wrap(batch).order(ByteOrder.LITTLE_ENDIAN)) { }
        }.exceptionOrNull()
        assertTrue(failure is ProtocolException, "an unknown operation is a protocol error, got $failure")
    }

    /**
     * The two scroll requests and the scroll report, read from and encoded to the bytes the
     * Host wrote: a node, an item, a flags word and two doubles; a node, a target, four
     * half words and four doubles; and an item, a flags word and six doubles.
     */
    @Test
    fun fr80_scroll_records_round_trip_through_the_protocol_vectors() {
        val mutations = decodeVector("mutations.bin")
        assertEquals(
            listOf(Mutation.ScrollTo(10, 3, ScrollWire.TO_ANIMATE or ScrollWire.TO_Y, 0.0, 120.5)),
            mutations.filterIsInstance<Mutation.ScrollTo>(),
        )
        assertEquals(
            listOf(
                Mutation.ScrollIntoView(
                    nodeId = 10,
                    target = 12,
                    targetKind = ScrollWire.TARGET_NODE,
                    alignX = ScrollWire.ALIGN_KEEP,
                    alignY = ScrollWire.ALIGN_NEAREST,
                    flags = ScrollWire.INTO_VIEW_ANIMATE,
                    rectXPx = 0.0,
                    rectYPx = 8.0,
                    rectWidthPx = 40.0,
                    rectHeightPx = 24.5,
                ),
            ),
            mutations.filterIsInstance<Mutation.ScrollIntoView>(),
        )

        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(128)
        val length = Protocol.encodeEvent(
            HostEvent.ScrollChanged(
                nodeId = 10,
                handlerId = 21,
                item = 3,
                flags = ScrollWire.CHANGED_SETTLED or ScrollWire.CHANGED_CAN_SCROLL_BACKWARD,
                xPx = 0.0,
                yPx = 700.5,
                maxXPx = 0.0,
                maxYPx = -1.0,
                viewportWidthPx = 320.0,
                viewportHeightPx = 300.0,
            ),
            out,
        )
        assertEquals(72, length)
        assertEquals(bytes.copyOfRange(1319, 1319 + 72).toList(), out.array().copyOf(length).toList())
    }

    /**
     * The file dialog request and its result, read from and encoded to the bytes the Host
     * wrote: four string references, a kind and a default filter, and three words and a
     * string reference.
     */
    @Test
    fun fr72_the_file_dialog_records_in_the_vector_round_trip() {
        assertEquals(
            listOf(
                Mutation.ShowFileDialog(
                    request = 7,
                    window = 0,
                    kind = FileDialogKind.OpenFiles,
                    defaultFilter = 1,
                    title = "파일 고르기",
                    directory = "/home/한글",
                    name = "보고서.txt",
                    filters = "이미지\u0000png,jpg\u0000문서\u0000pdf,txt",
                ),
            ),
            decodeVector("mutations.bin").filterIsInstance<Mutation.ShowFileDialog>(),
        )

        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(128)
        val length = Protocol.encodeEvent(
            HostEvent.FileDialogResult(
                nodeId = 0,
                handlerId = 0,
                request = 7,
                status = FileDialogStatus.Chosen,
                filter = 1,
                paths = "/Users/한글/문서.txt\u0000/tmp/a b.png",
            ),
            out,
        )
        assertEquals(73, length)
        assertEquals(bytes.copyOfRange(969, 969 + 73).toList(), out.array().copyOf(length).toList())
    }

    /** A kind the Renderer does not know is a decode error, which the host reports instead of crashing. */
    @Test
    fun fr72_an_unknown_file_dialog_kind_is_a_protocol_error() {
        val bytes = vectorFile("mutations.bin").readBytes()
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val recordsLength = view.getInt(4)
        var offset = 12
        var patched = false
        while (offset < recordsLength) {
            val tag = view.getShort(offset).toInt()
            val length = view.getShort(offset + 2).toInt()
            if (tag == 26) {
                for (unknown in listOf(0, 6, 9)) {
                    view.putShort(offset + 12, unknown.toShort())
                    val failure = runCatching {
                        Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)) { }
                    }.exceptionOrNull()
                    assertTrue(failure is ProtocolException, "kind $unknown decoded instead of being refused: $failure")
                }
                patched = true
            }
            offset += length
        }
        assertTrue(patched, "the vector carries a file dialog request")
    }

    /**
     * The overlay tokens record with the transient message's two fields appended: every
     * field the Rust encoder wrote reads back as the same value, the older ones where they
     * always were and the two new ones after the scrim.
     */
    @Test
    fun fr74_toast_elevation_token_round_trips_through_the_protocol_vectors() {
        assertEquals(
            listOf(
                Mutation.SetOverlayTokens(
                    OverlayTokens(
                        sheetCornerRadius = 24,
                        dialogCornerRadius = 16,
                        popupCornerRadius = 12,
                        sheetElevation = 8,
                        popupElevation = 4,
                        dialogElevation = 6,
                        sheetPadding = 20,
                        dialogPadding = 18,
                        popupPadding = 10,
                        scrim = 0x8000_0000L,
                        toastElevation = 7,
                        toastCornerRadius = 14,
                    ),
                ),
            ),
            decodeVector("mutations.bin").filterIsInstance<Mutation.SetOverlayTokens>(),
        )
    }

    /**
     * The text node's three font features, read from the bytes the Host wrote: tabular
     * figures on, ligatures off, and a character variant whose value is wider than a byte.
     */
    @Test
    fun fr83_font_feature_settings_round_trips() {
        val mutations = decodeVector("mutations.bin")
        assertTrue(Mutation.Create(22, WidgetKind.Text) in mutations)
        val props = mutations.filterIsInstance<Mutation.SetProp>().filter { it.nodeId == 22 }
        assertEquals(listOf(PropertyKind.FontFeatureSettings), props.map { it.property })
        val bytes = (props.single().value as PropertyValue.Bytes).value
        val expected = "tnum".encodeToByteArray() + byteArrayOf(1, 0, 0, 0) +
            "liga".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) +
            "cv01".encodeToByteArray() + byteArrayOf(2, 1, 0, 0)
        assertEquals(expected.toList(), bytes.toList())
        val errors = mutableListOf<String>()
        assertEquals(
            "tnum 1, liga 0, cv01 258",
            dev.darkpyonix.composerust.foundation.fontFeatureSettings(bytes, errors::add),
        )
        assertEquals(emptyList(), errors)
    }

    /** The Rust encoder wrote `CornerCurve { smoothing: 0.6 }` last; this side reads the same value. */
    @Test
    fun fr50_corner_curve_round_trips() {
        val last = decodeVector("mutations.bin").last()
        assertEquals(Mutation.SetModifier(8, 9, ProtocolModifier.CornerCurve(0.6f)), last)
    }

    /**
     * The web's file records: the operation and the release in the batch decode to what the
     * Host wrote, and the two answers encode to the bytes the Host decodes. An operation this
     * side does not know is refused, never guessed at.
     */
    @Test
    fun fr72_web_file_records_round_trip() {
        val mutations = decodeVector("mutations.bin")
        assertEquals(
            listOf(
                Mutation.FileCall(
                    request = 0x8000_0011.toInt(),
                    op = FileOp.Write,
                    create = false,
                    handle = 3,
                    name = "",
                    data = byteArrayOf(0x00, 0xff.toByte(), 0x7f, 0x80.toByte()),
                ),
                Mutation.FileCall(
                    request = 0x8000_0012.toInt(),
                    op = FileOp.Child,
                    create = true,
                    handle = 4,
                    name = "새 문서.txt",
                    data = ByteArray(0),
                ),
            ),
            mutations.filterIsInstance<Mutation.FileCall>(),
        )
        assertEquals(listOf(Mutation.ReleaseFileHandle(3)), mutations.filterIsInstance<Mutation.ReleaseFileHandle>())

        val events = vectorFile("events.bin").readBytes()
        fun encoded(event: HostEvent): ByteArray {
            val out = ByteBuffer.allocate(256)
            val length = Protocol.encodeEvent(event, out)
            return out.array().copyOf(length)
        }
        val answer = encoded(
            HostEvent.IoAnswer(0, 0, 0x8000_0011.toInt(), IoStatus.Ok, byteArrayOf(0x00, 0xff.toByte(), 0x7f, 0x80.toByte())),
        )
        assertEquals(36, answer.size)
        assertEquals(events.copyOfRange(1130, 1130 + 36).toList(), answer.toList())
        val handles = encoded(
            HostEvent.FileHandles(0, 0, 7, FileDialogStatus.Chosen, 1, FileDelivery.Handle, 3, "문서.txt\u0000사진", "fd"),
        )
        assertEquals(71, handles.size)
        assertEquals(events.copyOfRange(1166, 1166 + 71).toList(), handles.toList())

        // An operation outside the four, and a create flag that is neither 0 nor 1.
        for ((at, values) in listOf(8 to listOf(0, 5, 9), 10 to listOf(2, 7))) {
            val bytes = vectorFile("mutations.bin").readBytes()
            val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val recordsLength = view.getInt(4)
            var offset = 12
            var patched = false
            while (offset < recordsLength) {
                val tag = view.getShort(offset).toInt()
                val length = view.getShort(offset + 2).toInt()
                if (tag == 33 && !patched) {
                    for (value in values) {
                        view.putShort(offset + at, value.toShort())
                        val failure = runCatching {
                            Protocol.decode(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)) { }
                        }.exceptionOrNull()
                        assertTrue(failure is ProtocolException, "$value at $at decoded instead of being refused: $failure")
                    }
                    patched = true
                }
                offset += length
            }
            assertTrue(patched, "the vector carries a file operation")
        }
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
                ProtocolModifier.ObserveSize(9, false),
                ProtocolModifier.ObserveSize(9, true),
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
                ProtocolModifier.MatchParentSize,
                ProtocolModifier.CornerCurve(0.6f),
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
        assertEquals((0..9).toList(), onBox.map { it.index })

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
            mapOf(19 to 28, 20 to 28, 21 to 60, 22 to 28, 23 to 36, 24 to 28, 25 to 28, 26 to 44, 31 to 28, 35 to 28),
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
        assertEquals(10, animations.size)
        // The border colour came after every record that was there before it.
        assertEquals(dev.darkpyonix.composerust.protocol.AnimatedProperty.BorderColor, animations.last().property)
        assertEquals(
            dev.darkpyonix.composerust.protocol.KeyframeValue.PaintValue(
                dev.darkpyonix.composerust.protocol.Paint.Literal(0xFF336699.toInt()),
            ),
            animations.last().keyframes.last().value,
        )
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
        val kinds = animations.drop(3).take(6).map { animation ->
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
        assertEquals(bytes.copyOfRange(487, 527).toList(), out.array().copyOf(length).toList())
        val motion = ByteBuffer.allocate(32)
        val motionLength = Protocol.encodeEvent(
            HostEvent.ReducedMotionChanged(0, 0, dev.darkpyonix.composerust.protocol.ReducedMotion.On),
            motion,
        )
        assertEquals(20, motionLength)
        assertEquals(bytes.copyOfRange(527, 547).toList(), motion.array().copyOf(motionLength).toList())
    }

    /**
     * The system appearance report encodes to the 20 bytes the Host decodes: the header,
     * then the scheme as one word, 0 for light and 1 for dark. It is the vector's last record.
     */
    @Test
    fun fr44_color_scheme_event_round_trips() {
        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val length = Protocol.encodeEvent(
            HostEvent.ColorSchemeChanged(0, 0, dev.darkpyonix.composerust.protocol.SystemColorScheme.Dark),
            out,
        )
        assertEquals(20, length)
        assertEquals(bytes.copyOfRange(1659, 1679).toList(), out.array().copyOf(length).toList())
        assertEquals(1755, bytes.size)
        val light = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(
            20,
            Protocol.encodeEvent(
                HostEvent.ColorSchemeChanged(0, 0, dev.darkpyonix.composerust.protocol.SystemColorScheme.Light),
                light,
            ),
        )
        assertEquals(52, light.getShort(0).toInt())
        assertEquals(0, light.getInt(16))
        assertEquals(1, out.getInt(16))
    }

    /** The pointer event encodes to the 72 bytes the Host decodes. */
    @Test
    fun fr46_the_pointer_event_encodes_to_the_vector_bytes() {
        val bytes = vectorFile("events.bin").readBytes()
        val out = ByteBuffer.allocate(96)
        val length = Protocol.encodeEvent(
            HostEvent.Pointer(
                nodeId = 13,
                handlerId = 23,
                kind = dev.darkpyonix.composerust.protocol.PointerKind.Down,
                pointerId = 1,
                pointerType = dev.darkpyonix.composerust.protocol.PointerType.Mouse,
                x = 12.5f,
                y = -3f,
                buttons = 9,
                button = 8,
                modifiers = 5,
                clickCount = 2,
                deltaX = 0f,
                deltaY = 0f,
                deltaMode = dev.darkpyonix.composerust.protocol.WheelDeltaMode.Pixel,
                timestampNanos = 987_654_321_000L,
            ),
            out,
        )
        assertEquals(72, length)
        assertEquals(bytes.copyOfRange(547, 619).toList(), out.array().copyOf(length).toList())
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

    /**
     * The code editor in the vector: the edit record, and the decoration and colour run
     * records inside its two blobs, read at the offsets the Rust schema gives them.
     */
    @Test
    fun fr38_the_code_editor_records_in_the_vector_decode_to_the_same_values() {
        val mutations = decodeVector("mutations.bin")
        assertEquals(
            listOf(Mutation.EditCode(12, 7, 0, 1, 8, 1, 8, "mut ")),
            mutations.filterIsInstance<Mutation.EditCode>(),
        )
        val props = mutations.filterIsInstance<Mutation.SetProp>().filter { it.nodeId == 12 }
        assertEquals(WidgetKind.CodeEditor, mutations.filterIsInstance<Mutation.Create>().single { it.nodeId == 12 }.widget)
        val errors = mutableListOf<String>()
        val decorations = CodeEditorRecords.decodeDecorations(
            (props.single { it.property == PropertyKind.Decorations }.value as PropertyValue.Bytes).value,
            errors::add,
        )
        assertEquals(
            listOf(
                DecorationRecord(0, DecorationKind.Underline, Severity.Warning, ColorRole.Error, 1, 8, 1, 9, 0L, ""),
                DecorationRecord(0, DecorationKind.CodeLens, null, null, 0, 0, 0, 0, 41L, "Run"),
                DecorationRecord(0, DecorationKind.HoverAnchor, null, null, 0, 3, 0, 7, 42L, ""),
                DecorationRecord(0, DecorationKind.GhostText, null, null, 2, 1, 2, 1, 43L, " // end"),
                DecorationRecord(0, DecorationKind.LineBackground, null, ColorRole.DiffAdded, 1, 0, 2, 0, 0L, ""),
                DecorationRecord(0, DecorationKind.RangeBackground, null, ColorRole.DiffAdded, 1, 8, 1, 9, 0L, ""),
            ),
            decorations,
        )
        val spans = CodeEditorRecords.decodeSyntaxSpans(
            (props.single { it.property == PropertyKind.SyntaxSpans }.value as PropertyValue.Bytes).value,
            errors::add,
        )
        assertEquals(
            listOf(
                SyntaxSpanRecord(0, 0, 0, 0, 2, Paint.Role(ColorRole.Primary)),
                SyntaxSpanRecord(0, 1, 12, 1, 16, Paint.Role(ColorRole.Tertiary)),
            ),
            spans,
        )
        assertEquals(emptyList<String>(), errors)
    }

    /** The five code editor events encode here to the bytes the Rust Host decodes. */
    @Test
    fun fr38_the_code_editor_events_encode_to_the_vector_bytes() {
        val bytes = vectorFile("events.bin").readBytes()
        fun encoded(event: HostEvent): ByteArray {
            val buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
            val length = Protocol.encodeEvent(event, buffer)
            return buffer.array().copyOf(length)
        }
        val expected = listOf(
            311 to HostEvent.CodeChanged(12, 18L, 1, 1, 8, 1, 8, "mut "),
            359 to HostEvent.CodeEditRejected(12, 19L, 7, 0, 2, 1, 8, 1, 9),
            403 to HostEvent.CodeHovered(12, 20L, 42L, 0, 4, HoverPhase.Rest),
            439 to HostEvent.CodeSaveRequested(12, 21L, 2),
            463 to HostEvent.DecorationActivated(12, 22L, 41L),
            619 to HostEvent.SelectionChanged(12, 23L, 2, 1, 12, 0, 3),
            655 to HostEvent.VisibleRangeChanged(12, 24L, 2, 0, 3),
            683 to HostEvent.EditorFocusChanged(12, 25L, true),
            707 to HostEvent.FoldChanged(12, 26L, 0, true),
            731 to HostEvent.CompositionChanged(12, 27L, true),
            // The row height and the scroll offset, after everything that was here before.
            1391 to HostEvent.CodeMetrics(12, 28L, 40.0),
            1415 to HostEvent.CodeScrolled(12, 29L, 1234.5),
            // Where the caret is drawn and the answer to the position query, after them.
            1463 to HostEvent.CaretRect(12, 30L, 1, 8, 120.5f, 64.25f, 2f, 20f),
            1503 to HostEvent.PositionRect(12, 0L, 17, true, 88.75f, 64.25f, 2f, 20f),
            // A Cmd-click over the second line, with the place in the text under it.
            1543 to HostEvent.CodePointer(
                12, 31L, dev.darkpyonix.composerust.protocol.PointerKind.Down, 1,
                dev.darkpyonix.composerust.protocol.PointerType.Mouse, 96.5f, 30.25f, 1, 1, 8, 1, 0f, 0f,
                dev.darkpyonix.composerust.protocol.WheelDeltaMode.Pixel, 123_456_789_000L, 1, 13,
            ),
            // What became of the grouped edit in the mutation vector: both edits went in.
            1623 to HostEvent.CodeEditGroupResult(
                12, 32L, 8, dev.darkpyonix.composerust.protocol.EditGroupStatus.Applied, 1, 3, 2,
            ),
            // Every selection of the editor, a caret last and a selection made backwards in
            // the middle, the four numbers of each behind the record.
            1679 to HostEvent.SelectionsChanged(12, 33L, 4, intArrayOf(0, 3, 0, 7, 1, 12, 1, 8, 2, 0, 2, 0)),
        )
        for ((offset, event) in expected) {
            val wire = encoded(event)
            assertTrue(
                wire.contentEquals(bytes.copyOfRange(offset, offset + wire.size)),
                "$event at $offset",
            )
        }
        assertEquals(1755, bytes.size)
    }

    /**
     * A node's size motion settling encodes here to the bytes the Rust Host decodes: the
     * header, then the settled width and height as `f32` in dp. The handler property it
     * is sent for reads back from its tag.
     */
    @Test
    fun fr24_motion_finished_round_trips() {
        val bytes = vectorFile("events.bin").readBytes()
        val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        val length = Protocol.encodeEvent(HostEvent.MotionFinished(14, 30L, 240.5f, 0f), buffer)
        assertEquals(24, length)
        assertTrue(buffer.array().copyOf(length).contentEquals(bytes.copyOfRange(1439, 1463)))
        assertEquals(1755, bytes.size)

        // A SetProp of the handler, written by hand: the record header, the node, the
        // property tag 193 and an integer value holding the handler id.
        val record = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        record.putShort(2) // SetProp, 24 bytes with its header
        record.putShort(24)
        record.putInt(14)
        record.putShort(193)
        record.putShort(3) // an integer value
        record.putLong(30L)
        record.putInt(0)
        val envelope = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        envelope.putShort(0)
        envelope.putShort(12)
        envelope.putInt(36) // the records end 36 bytes in, the envelope included
        envelope.putInt(1)
        envelope.put(record.array())
        val decoded = mutableListOf<Mutation>()
        Protocol.decode(ByteBuffer.wrap(envelope.array()).order(ByteOrder.LITTLE_ENDIAN), decoded::add)
        assertEquals(
            listOf<Mutation>(Mutation.SetProp(14, PropertyKind.OnMotionFinished, PropertyValue.Integer(30L))),
            decoded,
        )
    }

    /**
     * The code editor's later additions in the vector: the three commands, and the fold and
     * gutter records inside their blobs, read at the offsets the Rust schema gives them.
     */
    @Test
    fun fr38_the_editor_selection_fold_and_gutter_records_decode_to_the_same_values() {
        val mutations = decodeVector("mutations.bin")
        assertEquals(
            listOf(Mutation.SetSelection(12, 0, 1, 12, 0, 3)),
            mutations.filterIsInstance<Mutation.SetSelection>(),
        )
        assertEquals(
            listOf(Mutation.RevealRange(12, 1, 1, 12, 1, 16, RevealAlign.Center)),
            mutations.filterIsInstance<Mutation.RevealRange>(),
        )
        assertEquals(
            listOf(Mutation.SetFolded(12, 0, true)),
            mutations.filterIsInstance<Mutation.SetFolded>(),
        )
        assertEquals(
            listOf(Mutation.QueryPositionRect(12, 17, 1, 8)),
            mutations.filterIsInstance<Mutation.QueryPositionRect>(),
        )
        assertEquals(
            listOf(Mutation.SetScrollOffset(12, 1234.5)),
            mutations.filterIsInstance<Mutation.SetScrollOffset>(),
        )
        val props = mutations.filterIsInstance<Mutation.SetProp>().filter { it.nodeId == 12 }
        val errors = mutableListOf<String>()
        // The grouped edit: its head, then its two edits back to front as the Rust side
        // listed them, the emoji replaced by another surrogate pair.
        val group = mutations.filterIsInstance<Mutation.EditCodeGroup>().single()
        assertEquals(listOf(12, 8, 1), listOf(group.nodeId, group.requestId, group.baseVersion))
        assertEquals(null, group.malformed)
        assertEquals(
            listOf(
                dev.darkpyonix.composerust.protocol.CodeEditRecord(1, 17, 1, 19, "\uD83C\uDF89"),
                dev.darkpyonix.composerust.protocol.CodeEditRecord(0, 0, 0, 2, "pub fn"),
            ),
            CodeEditorRecords.decodeEditGroup(group.edits, errors::add),
        )
        assertEquals(
            PropertyValue.Integer(32L),
            props.single { it.property == PropertyKind.OnEditGroupResult }.value,
        )
        // Three selections, the second made backwards.
        val selections = mutations.filterIsInstance<Mutation.SetSelections>().single()
        assertEquals(listOf(12, 1), listOf(selections.nodeId, selections.baseVersion))
        val words = ByteBuffer.wrap(selections.selections).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(
            listOf(0, 3, 0, 7, 1, 12, 1, 8, 2, 0, 2, 0),
            (0 until selections.selections.size / 4).map { words.getInt(it * 4) },
        )
        // The snippet over the closing brace, every character of it as the Rust side wrote it.
        assertEquals(
            listOf(Mutation.InsertSnippet(12, 3, 2, 0, 2, 1, "} // \${1:name} = \${1:name}, \${2|\uD55C,\uD83D\uDE00|} \\\$5\$0")),
            mutations.filterIsInstance<Mutation.InsertSnippet>(),
        )
        // Word wrap at forty columns or the edge of the view, whichever comes first.
        assertEquals(
            PropertyValue.Integer((dev.darkpyonix.composerust.protocol.WordWrapMode.Bounded.ordinal + 1).toLong() or (40L shl 16)),
            props.single { it.property == PropertyKind.WordWrap }.value,
        )
        // The minimap on the right, and two view zones: the first with text that is not
        // ASCII, the second half a row high.
        assertEquals(
            PropertyValue.Integer((dev.darkpyonix.composerust.protocol.MinimapSide.Right.ordinal + 1).toLong()),
            props.single { it.property == PropertyKind.Minimap }.value,
        )
        assertEquals(
            listOf(
                dev.darkpyonix.composerust.protocol.ViewZoneRecord(0, 2f, "\u2212 2\uC904"),
                dev.darkpyonix.composerust.protocol.ViewZoneRecord(1, 0.5f, ""),
            ),
            CodeEditorRecords.decodeViewZones(
                (props.single { it.property == PropertyKind.ViewZones }.value as PropertyValue.Bytes).value,
                errors::add,
            ),
        )
        assertEquals(
            listOf(FoldRecord(0, 0, 2, false), FoldRecord(0, 1, 3, true)),
            CodeEditorRecords.decodeFolds(
                (props.single { it.property == PropertyKind.Folds }.value as PropertyValue.Bytes).value,
                errors::add,
            ),
        )
        assertEquals(
            listOf(
                GutterDecorationRecord(0, 0, GutterDecorationKind.Dot, null, 51L, 0),
                GutterDecorationRecord(0, 1, GutterDecorationKind.Bar, ColorRole.Tertiary, 0L, 0),
                GutterDecorationRecord(0, 2, GutterDecorationKind.Glyph, null, 52L, 0x1F41B),
            ),
            CodeEditorRecords.decodeGutterDecorations(
                (props.single { it.property == PropertyKind.GutterDecorations }.value as PropertyValue.Bytes).value,
                errors::add,
            ),
        )
        assertEquals(emptyList<String>(), errors)
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
