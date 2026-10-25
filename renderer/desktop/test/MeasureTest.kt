package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.RendererMeasure
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.nodeTestTag
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A Host's tree, and the density to draw it at, or null for the test's own. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.startHost(
    connection: FakeHostConnection,
    density: Density? = null,
): ComposeRustHost {
    lateinit var host: ComposeRustHost
    setContent {
        if (density != null) {
            CompositionLocalProvider(LocalDensity provides density) {
                host = rememberComposeRustHost(connection)
                ComposeRustContent(host)
            }
        } else {
            host = rememberComposeRustHost(connection)
            ComposeRustContent(host)
        }
    }
    waitForIdle()
    return host
}

/** Asks the Renderer, on its UI thread, as the Host does. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.measure(requests: MeasureRequests): Pair<Int, List<MeasuredResult>> {
    var answer: Pair<Int, List<MeasuredResult>>? = null
    runOnIdle {
        val results = resultBuffer(requests.count)
        val status = RendererMeasure.measure(requests.encode(), requests.count, results)
        answer = status to results.results(requests.count)
    }
    return answer!!
}

/** How the Text node [nodeId] was really laid out on screen. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.drawnLayout(nodeId: Int): TextLayoutResult {
    fun find(node: SemanticsNode): TextLayoutResult? {
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            action(layouts)
            layouts.firstOrNull()?.let { return it }
        }
        return node.children.firstNotNullOfOrNull(::find)
    }
    val node = onNodeWithTag(nodeTestTag(nodeId), useUnmergedTree = true).fetchSemanticsNode()
    return assertNotNull(find(node), "node $nodeId has no text layout to compare with")
}

/** The measured answer is the drawn layout, field by field and bit for bit. */
internal fun assertSameAsDrawn(measured: MeasuredResult, drawn: TextLayoutResult, density: Float, case: String) {
    assertEquals(MeasureRecords.STATUS_OK, measured.status, "$case: status")
    assertEquals(drawn.size.width / density, measured.width, "$case: width")
    assertEquals(drawn.size.height / density, measured.height, "$case: height")
    assertEquals(drawn.firstBaseline / density, measured.firstBaseline, "$case: first baseline")
    assertEquals(drawn.lineCount, measured.lineCount, "$case: line count")
    val last = drawn.lineCount - 1
    assertEquals(
        (drawn.getLineRight(last) - drawn.getLineLeft(last)) / density,
        measured.lastLineWidth,
        "$case: last line width",
    )
    assertEquals(drawn.multiParagraph.didExceedMaxLines, measured.truncated, "$case: truncated")
}

/**
 * The measure call, answered by the Renderer.
 *
 * A Host that lays out its own page asks how big a run of text, or a node it already sent,
 * will be, from inside a call this side made into it. What it is told has to be what is
 * drawn: that is the whole reason to ask the side that draws.
 */
@OptIn(ExperimentalTestApi::class)
class MeasureTest {

    /** One Text inside a column of the given width, with [props] on the text. */
    private fun textInColumn(width: Float, text: String, vararg props: Pair<PropertyKind, PropertyValue>) =
        listOf(
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(width)),
            Mutation.Create(TEXT, WidgetKind.Text),
            Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text(text)),
        ) + props.map { (kind, value) -> Mutation.SetProp(TEXT, kind, value) } +
            Mutation.Insert(COLUMN, TEXT, 0)

    private class Case(
        val name: String,
        val text: String,
        val width: Float,
        val props: List<Pair<PropertyKind, PropertyValue>> = emptyList(),
        val request: MeasureRequests.(String, Float) -> MeasureRequests,
    )

    private val cases = listOf(
        Case("latin", "The quick brown fox jumps over the lazy dog", 120f) { text, width ->
            text(text, width = width)
        },
        Case("korean", "다람쥐 헌 쳇바퀴에 타고파 그리고 한글 줄바꿈을 확인합니다", 100f) { text, width ->
            text(text, width = width)
        },
        Case("emoji", "Party 🎉 time 👩‍👩‍👧 fun", 80f) { text, width ->
            text(text, width = width)
        },
        Case(
            "spans",
            "Plain bold and mono text that wraps",
            90f,
            listOf(
                PropertyKind.Spans to PropertyValue.Bytes(
                    spanRecord(6, 4, flags = 1) + spanRecord(15, 4, role = 9),
                ),
            ),
        ) { text, width ->
            text(text, width = width, spans = spanRecord(6, 4, flags = 1) + spanRecord(15, 4, role = 9))
        },
        Case(
            "truncated",
            "One line, then another line, then a third that is cut off",
            70f,
            listOf(PropertyKind.MaxLines to PropertyValue.Integer(2)),
        ) { text, width ->
            text(text, width = width, maxLines = 2)
        },
        Case(
            "sized",
            "Larger and heavier",
            100f,
            listOf(
                PropertyKind.FontSize to PropertyValue.Float(22f),
                PropertyKind.FontWeight to PropertyValue.Integer(700),
                PropertyKind.LineHeight to PropertyValue.Float(30f),
            ),
        ) { text, width ->
            text(text, width = width, fontSize = 22f, fontWeight = 700, lineHeight = 30f)
        },
    )

    @Test
    fun pr2_measured_text_matches_the_drawn_text() {
        for (case in cases) {
            runComposeUiTest {
                startHost(FakeHostConnection(textInColumn(case.width, case.text, *case.props.toTypedArray())))
                val (status, results) = measure(case.request(MeasureRequests(), case.text, case.width))
                assertEquals(MeasureRecords.CALL_OK, status, case.name)
                assertSameAsDrawn(results.single(), drawnLayout(TEXT), density.density, case.name)
            }
        }
    }

    @Test
    fun pr2_intrinsic_widths_match_compose() = runComposeUiTest {
        lateinit var drawDensity: Density
        lateinit var resolver: FontFamily.Resolver
        setContent {
            drawDensity = LocalDensity.current
            resolver = LocalFontFamilyResolver.current
            ComposeRustContent(rememberComposeRustHost(FakeHostConnection(emptyList())))
        }
        waitForIdle()
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val serif = listOf(FontRef.Generic(GenericFamily.Serif))
        val texts = listOf(
            Triple("A sentence with several words", sans, FontFamily.SansSerif),
            Triple("Incomprehensibilities and short", serif, FontFamily.Serif),
            Triple("한글 문장의 가장 긴 조각", sans, FontFamily.SansSerif),
        )
        for ((text, fonts, family) in texts) {
            val (status, results) = measure(
                MeasureRequests()
                    .text(text, role = 0, fontSize = 16f, fonts = fonts, constraint = MeasureRecords.CONSTRAINT_MIN_CONTENT)
                    .text(text, role = 0, fontSize = 16f, fonts = fonts, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT),
            )
            assertEquals(MeasureRecords.CALL_OK, status)
            val intrinsics = ParagraphIntrinsics(
                text = text,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight(400), fontFamily = family),
                annotations = emptyList(),
                density = drawDensity,
                fontFamilyResolver = resolver,
            )
            assertEquals(intrinsics.minIntrinsicWidth / drawDensity.density, results[0].width, "min content of '$text'")
            assertEquals(intrinsics.maxIntrinsicWidth / drawDensity.density, results[1].width, "max content of '$text'")
            assertTrue(results[0].width < results[1].width, "'$text' has somewhere to break")
        }
        // Text that does not wrap is as narrow as it is wide.
        val (_, nowrap) = measure(
            MeasureRequests()
                .text("no wrapping here", role = 0, fontSize = 16f, fonts = sans, wrap = false, constraint = MeasureRecords.CONSTRAINT_MIN_CONTENT)
                .text("no wrapping here", role = 0, fontSize = 16f, fonts = sans, wrap = false, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT),
        )
        assertEquals(nowrap[1].width, nowrap[0].width)
    }

    @Test
    fun pr2_measured_node_matches_its_layout() = runComposeUiTest {
        val tree = listOf(
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(240f)),
            Mutation.Create(BUTTON, WidgetKind.Button),
            Mutation.SetProp(BUTTON, PropertyKind.Text, PropertyValue.Text("Press me")),
            Mutation.Create(FIELD, WidgetKind.TextField),
            Mutation.SetProp(FIELD, PropertyKind.Placeholder, PropertyValue.Text("Type here")),
            Mutation.Create(INNER, WidgetKind.Column),
            Mutation.Create(FIRST, WidgetKind.Text),
            Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("First of two")),
            Mutation.Create(SECOND, WidgetKind.Text),
            Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("Second, and a little longer")),
            Mutation.Insert(INNER, FIRST, 0),
            Mutation.Insert(INNER, SECOND, 1),
            Mutation.Insert(COLUMN, BUTTON, 0),
            Mutation.Insert(COLUMN, FIELD, 1),
            Mutation.Insert(COLUMN, INNER, 2),
        )
        startHost(FakeHostConnection(tree))
        val requests = MeasureRequests()
        val nodes = listOf(BUTTON, FIELD, INNER)
        nodes.forEach { requests.node(it, maxWidth = 240f) }
        val (status, results) = measure(requests)
        assertEquals(MeasureRecords.CALL_OK, status)
        nodes.forEachIndexed { index, nodeId ->
            val drawn = onNodeWithTag(nodeTestTag(nodeId), useUnmergedTree = true).fetchSemanticsNode().size
            val measured = results[index]
            assertEquals(MeasureRecords.STATUS_OK, measured.status, "node $nodeId")
            assertEquals(drawn.width / density.density, measured.width, "width of node $nodeId")
            assertEquals(drawn.height / density.density, measured.height, "height of node $nodeId")
            assertTrue(measured.width > 0f && measured.height > 0f, "node $nodeId measured as nothing")
            assertEquals(0, measured.lineCount)
            assertTrue(measured.lastLineWidth.isNaN())
        }
    }

    @Test
    fun pr2_unknown_node_is_reported_per_record() = runComposeUiTest {
        val connection = FakeHostConnection(textInColumn(200f, "Already here"))
        val inside = mutableListOf<List<MeasuredResult>>()
        // The Host creates a node in the same call it asks about it. That node is only
        // applied once the call returns, so it is unknown now and known next time.
        connection.respondWith { event ->
            if (event !is HostEvent.Clicked || event.handlerId != ASK) return@respondWith HostResponse()
            val requests = MeasureRequests()
                .text("before", width = 200f)
                .node(LATE)
                .node(NEVER_SENT)
                .node(TEXT)
            val results = resultBuffer(requests.count)
            RendererMeasure.measure(requests.encode(), requests.count, results)
            inside += results.results(requests.count)
            HostResponse(
                listOf(
                    Mutation.Create(LATE, WidgetKind.Text),
                    Mutation.SetProp(LATE, PropertyKind.Text, PropertyValue.Text("late")),
                    Mutation.Insert(COLUMN, LATE, 1),
                ),
            )
        }
        val host = startHost(connection)
        runOnIdle { host.dispatch(HostEvent.Clicked(TEXT, ASK)) }
        waitForIdle()
        runOnIdle { host.dispatch(HostEvent.Clicked(TEXT, ASK)) }
        val (first, second) = inside
        assertEquals(
            listOf(
                MeasureRecords.STATUS_OK,
                MeasureRecords.STATUS_UNKNOWN_NODE,
                MeasureRecords.STATUS_UNKNOWN_NODE,
                MeasureRecords.STATUS_OK,
            ),
            first.map { it.status },
        )
        assertTrue(first[1].width == 0f && first[1].firstBaseline.isNaN())
        assertEquals(MeasureRecords.STATUS_OK, second[1].status, "the node the first call created")
        assertEquals(MeasureRecords.STATUS_UNKNOWN_NODE, second[2].status)
    }

    @Test
    fun pr1_measure_is_answered_inside_the_host_call() = runComposeUiTest {
        val connection = FakeHostConnection(textInColumn(200f, "measured from inside"))
        var answered: Pair<Int, List<MeasuredResult>>? = null
        var answeredOn: Thread? = null
        connection.respondWith { event ->
            if (event is HostEvent.Clicked && event.handlerId == ASK) {
                val requests = MeasureRequests().text("measured from inside", width = 200f).node(TEXT)
                val results = resultBuffer(requests.count)
                val status = RendererMeasure.measure(requests.encode(), requests.count, results)
                answered = status to results.results(requests.count)
                answeredOn = Thread.currentThread()
            }
            HostResponse()
        }
        val host = startHost(connection)
        runOnIdle {
            host.dispatch(HostEvent.Clicked(TEXT, ASK))
            // Answered before the dispatch returned, on this thread: no queue, no hop.
            val (status, results) = assertNotNull(answered, "the measure call was not answered inside the dispatch")
            assertSame(Thread.currentThread(), answeredOn)
            assertEquals(MeasureRecords.CALL_OK, status)
            assertEquals(MeasureRecords.STATUS_OK, results[0].status)
            assertEquals(results[1].width, results[0].width, "the text measured as text and as the node it is")
            assertEquals(results[1].height, results[0].height)
        }
    }

    @Test
    fun pr3_measure_off_the_ui_thread_is_refused() = runComposeUiTest {
        startHost(FakeHostConnection(textInColumn(200f, "here")))
        val requests = MeasureRequests().text("here", width = 200f)
        val results = resultBuffer(requests.count)
        var status = 0
        thread { status = RendererMeasure.measure(requests.encode(), requests.count, results) }.join()
        assertEquals(MeasureRecords.CALL_OFF_UI_THREAD, status)
        assertEquals(0, results.results(1).single().status, "a refused call wrote a result")
        assertEquals(0f, results.results(1).single().width)
        // And the UI thread is answered as before.
        val (onUi, measured) = measure(requests)
        assertEquals(MeasureRecords.CALL_OK, onUi)
        assertTrue(measured.single().width > 0f)
    }

    @Test
    fun nfr7_malformed_measure_buffer_is_a_protocol_error() = runComposeUiTest {
        startHost(FakeHostConnection(textInColumn(200f, "fine")))
        var whole = 0
        var negative = 0
        var shortResults = 0
        runOnIdle {
            whole = RendererMeasure.measure(ByteBuffer.allocate(10), 3, resultBuffer(3))
            negative = RendererMeasure.measure(ByteBuffer.allocate(10), -1, resultBuffer(1))
            shortResults = RendererMeasure.measure(MeasureRequests().text("x", width = 9f).encode(), 1, ByteBuffer.allocate(4))
        }
        assertEquals(MeasureRecords.CALL_UNREADABLE, whole, "three records do not fit ten bytes")
        assertEquals(MeasureRecords.CALL_UNREADABLE, negative)
        assertEquals(MeasureRecords.CALL_UNREADABLE, shortResults, "no room for the answer")

        // One record that points outside the buffer, and one of a kind nobody knows, among
        // good ones: those two say so and the rest are measured.
        val requests = MeasureRequests()
            .text("fine", width = 200f)
            .text("broken", width = 200f)
            .text("fine", width = 200f)
            .node(TEXT)
        val buffer = requests.encode()
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MeasureRecords.RECORD_LENGTH + MeasureRecords.TEXT_TEXT_OFFSET_AT, Int.MAX_VALUE - 2)
        buffer.putShort(2 * MeasureRecords.RECORD_LENGTH + MeasureRecords.KIND_AT, 99)
        var status = 0
        var results: List<MeasuredResult> = emptyList()
        runOnIdle {
            val out = resultBuffer(4)
            status = RendererMeasure.measure(buffer, 4, out)
            results = out.results(4)
        }
        assertEquals(MeasureRecords.CALL_OK, status)
        assertEquals(
            listOf(
                MeasureRecords.STATUS_OK,
                MeasureRecords.STATUS_MALFORMED,
                MeasureRecords.STATUS_MALFORMED,
                MeasureRecords.STATUS_OK,
            ),
            results.map { it.status },
        )
    }

    private companion object {
        const val COLUMN = 1
        const val TEXT = 2
        const val BUTTON = 3
        const val FIELD = 4
        const val INNER = 5
        const val FIRST = 6
        const val SECOND = 7
        const val LATE = 50
        const val NEVER_SENT = 4_000_000
        const val ASK = 77L
    }
}
