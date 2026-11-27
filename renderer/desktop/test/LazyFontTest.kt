package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.tooling.HostResponse
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.MissingGlyphFaces
import dev.darkpyonix.composerust.ui.node.parseCmapRanges
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Fonts the Host keeps until a letter needs them: a `LazyAsset` candidate of a font list, or
 * a lazy family of a stack.
 *
 * The faces are built by [TinyIconFont], whose one glyph is a block of a width no installed
 * font gives the same letter. The subset face has `가` at one and a half em; the full face
 * has `똠` at one em. Neither has the other's letter, which is the shape of a Korean face cut
 * down to the 2,350 letters of KS X 1001 and the face it was cut from. A letter drawn as a
 * missing glyph is [MissingGlyphFaces.ADVANCE] units wide, three fifths of an em, which no
 * installed face gives `똠` either. So the width of a line says which face drew each letter,
 * and whether any of it came from the machine.
 */
@OptIn(ExperimentalTestApi::class)
class LazyFontTest {

    private val subset = TinyIconFont.build(codePoint = HANGUL_GA, family = "Tiny Subset", advance = SUBSET_ADVANCE)
    private val full = TinyIconFont.build(codePoint = HANGUL_TTOM, family = "Tiny Full", advance = FULL_ADVANCE)

    private val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
    private val size = PropertyKind.FontSize to PropertyValue.Float(SIZE)
    private val lazyList = listOf(FontRef.Asset(SUBSET), FontRef.LazyAsset(FULL))
    private val ownFonts = PropertyKind.Font to PropertyValue.Bytes(fontBlob(lazyList))

    private fun register(id: Int, bytes: ByteArray) = Mutation.RegisterAsset(id, AssetKind.Font.ordinal + 1, bytes)

    /** A stack asset's bytes in version 2: each family a kind word (1 registered, 4 kept on the Host), then its faces. */
    private fun lazyStack(vararg families: Pair<Boolean, List<Int>>): ByteArray {
        val words = 3 + families.sumOf { 2 + it.second.size }
        val out = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.put("CRFS".encodeToByteArray())
        out.putInt(2)
        out.putInt(families.size)
        for ((lazy, faces) in families) {
            out.putInt(if (lazy) 4 else 1)
            out.putInt(faces.size)
            for (face in faces) out.putInt(face)
        }
        return out.array()
    }

    /** A theme with every role but code set in [font]. */
    private fun themeIn(font: Int) = Mutation.SetTheme(
        Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
            fonts = TypeRole.entries.map { if (it == TypeRole.Mono) 0 else font },
        ),
    )

    /** The subset registered, and a column of nodes, each of [kind] saying its text with [props]. */
    private fun screen(
        vararg nodes: Triple<WidgetKind, String, List<Pair<PropertyKind, PropertyValue>>>,
        before: List<Mutation> = emptyList(),
    ): List<Mutation> = buildList {
        add(register(SUBSET, subset))
        addAll(before)
        add(Mutation.Create(COLUMN, WidgetKind.Column))
        nodes.forEachIndexed { index, (kind, text, props) ->
            val id = FIRST + index
            add(Mutation.Create(id, kind))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(text)))
            for ((prop, value) in props) add(Mutation.SetProp(id, prop, value))
            add(Mutation.Insert(COLUMN, id, index))
        }
    }

    private fun text(value: String) = Triple(WidgetKind.Text, value, listOf(noRole, size, ownFonts))

    private fun width(layout: TextLayoutResult): Float = layout.getLineRight(0) - layout.getLineLeft(0)

    /** Each glyph's advance may be rounded to the half pixel; a face of the machine's is pixels off. */
    private fun assertWidth(expected: Float, layout: TextLayoutResult, letters: Int, case: String) {
        val actual = width(layout)
        assertTrue(abs(expected - actual) <= letters * 0.5f + 0.01f, "$case: $actual wide where $expected was expected")
    }

    private fun em(units: Int): Float = SIZE * units / TinyIconFont.UNITS_PER_EM

    private val missing: Float get() = em(MissingGlyphFaces.ADVANCE)

    private fun FakeHostConnection.wanted(): List<HostEvent.AssetWanted> = events.filterIsInstance<HostEvent.AssetWanted>()

    /** The Host's answer to a request, delivered with the next frame as the real Host's next batch would be. */
    private fun ComposeUiTest.deliver(host: ComposeRustHost, connection: FakeHostConnection, vararg batch: Mutation) {
        runOnIdle {
            connection.scheduleFrame(batch.toList())
            host.renderFrame(0L)
        }
        waitForIdle()
    }

    /**
     * `똠` is in no face before the lazy one, so the lazy face is asked for, once, with the
     * Host's own node and handler. Until its bytes arrive the letter is a missing glyph from
     * a face made for it, never one of the machine's; after the batch that carries them it
     * is set in the full face.
     */
    @Test
    fun fr48_4_missing_glyph_loads_the_lazy_face_never_the_os_font() = runComposeUiTest {
        val connection = FakeHostConnection(screen(text("가똠")))
        val host = startHost(connection, Density(1f))
        assertEquals(listOf(HostEvent.AssetWanted(0, 0L, FULL)), connection.wanted())

        val before = drawnLayout(FIRST)
        assertWidth(em(SUBSET_ADVANCE) + missing, before, 2, "가 from the subset and 똠 as a missing glyph")
        // The span that draws 똠 names the face made for it, and that face has only 똠.
        val ttom = before.layoutInput.text.spanStyles.single { it.start <= 1 && it.end >= 2 }
        assertSame(MissingGlyphFaces.family(intArrayOf(HANGUL_TTOM)), ttom.item.fontFamily)
        assertTrue(missingFaceHas(HANGUL_TTOM))
        assertTrue(!missingFaceHas(HANGUL_GA))

        deliver(host, connection, register(FULL, full))
        assertWidth(em(SUBSET_ADVANCE) + em(FULL_ADVANCE), drawnLayout(FIRST), 2, "똠 from the full face")
        assertEquals(1, connection.wanted().size, "the request went once")
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
    }

    /**
     * Three texts, a field and a measure request all reach the same lazy face in one frame,
     * and a stack naming it as well: one request. The Host here answers the way the real one
     * does, with the registration on the batch the request returns.
     */
    @Test
    fun fr48_4_lazy_face_is_requested_once() = runComposeUiTest {
        val connection = FakeHostConnection(
            screen(
                text("똠"),
                text("가똠"),
                text("똠똠"),
                Triple(WidgetKind.TextField, "똠", listOf(noRole, size, ownFonts)),
                Triple(WidgetKind.Text, "똠", listOf(PropertyKind.TypeRole to PropertyValue.Integer(TypeRole.Body.ordinal + 1L))),
                before = listOf(register(STACK, lazyStack(false to listOf(SUBSET), true to listOf(FULL))), themeIn(STACK)),
            ),
        )
        val held = mutableListOf<HostEvent.AssetWanted>()
        connection.respondWith { event ->
            if (event is HostEvent.AssetWanted) {
                held += event
                HostResponse(listOf(register(event.assetId, full)))
            } else {
                HostResponse()
            }
        }
        startHost(connection, Density(1f))
        val (status, _) = measure(MeasureRequests().text("똠", role = 0, fontSize = SIZE, fonts = lazyList, width = 400f))
        assertEquals(MeasureRecords.CALL_OK, status)
        waitForIdle()

        assertEquals(listOf(HostEvent.AssetWanted(0, 0L, FULL)), connection.wanted())
        assertEquals(1, held.size)
        // Answered, every one of them is set in the full face, the stack's text included.
        assertWidth(em(FULL_ADVANCE), drawnLayout(FIRST), 1, "the first text")
        assertWidth(em(SUBSET_ADVANCE) + em(FULL_ADVANCE), drawnLayout(FIRST + 1), 2, "the second")
        assertWidth(2 * em(FULL_ADVANCE), drawnLayout(FIRST + 2), 2, "the third")
        val body = drawnLayout(FIRST + 4)
        val bodySize = body.layoutInput.style.fontSize.value
        assertTrue(
            abs(width(body) - bodySize * FULL_ADVANCE / TinyIconFont.UNITS_PER_EM) <= 0.51f,
            "text in the stack's role is set in the full face once it is here: ${width(body)} at $bodySize",
        )
    }

    /**
     * What a measure request is told is what is drawn, while the lazy face is missing and
     * after it has arrived.
     */
    @Test
    fun fr48_4_measure_and_draw_agree_across_the_load() = runComposeUiTest {
        val connection = FakeHostConnection(screen(text("가똠가")))
        val host = startHost(connection, Density(1f))
        val request = MeasureRequests().text("가똠가", role = 0, fontSize = SIZE, fonts = lazyList, width = 400f)

        val before = drawnLayout(FIRST)
        assertWidth(2 * em(SUBSET_ADVANCE) + missing, before, 3, "before the load")
        val (status, results) = measure(request)
        assertEquals(MeasureRecords.CALL_OK, status)
        assertSameAsDrawn(results.single(), before, 1f, "measured before the load")

        deliver(host, connection, register(FULL, full))
        val after = drawnLayout(FIRST)
        assertWidth(2 * em(SUBSET_ADVANCE) + em(FULL_ADVANCE), after, 3, "after the load")
        val (afterStatus, afterResults) = measure(request)
        assertEquals(MeasureRecords.CALL_OK, afterStatus)
        assertSameAsDrawn(afterResults.single(), after, 1f, "measured after the load")
        assertEquals(1, connection.wanted().size)
    }

    /** A screen of letters the subset has asks for nothing, drawn or measured. */
    @Test
    fun fr48_4_covered_text_requests_nothing() = runComposeUiTest {
        val connection = FakeHostConnection(
            screen(
                text("가"),
                text("가가가"),
                Triple(WidgetKind.TextField, "가가", listOf(noRole, size, ownFonts)),
                before = listOf(register(STACK, lazyStack(false to listOf(SUBSET), true to listOf(FULL)))),
            ),
        )
        startHost(connection, Density(1f))
        val (status, _) = measure(MeasureRequests().text("가가", role = 0, fontSize = SIZE, fonts = lazyList, width = 400f))
        assertEquals(MeasureRecords.CALL_OK, status)
        waitForIdle()
        assertEquals(emptyList(), connection.wanted())
        assertWidth(em(SUBSET_ADVANCE), drawnLayout(FIRST), 1, "the subset draws it")
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
    }

    /** A lazy face the Host let go of is asked for again the next time a letter needs it. */
    @Test
    fun fr48_4_a_released_lazy_face_is_asked_for_again() = runComposeUiTest {
        val connection = FakeHostConnection(screen(text("가똠")))
        val host = startHost(connection, Density(1f))
        deliver(host, connection, register(FULL, full))
        assertWidth(em(SUBSET_ADVANCE) + em(FULL_ADVANCE), drawnLayout(FIRST), 2, "loaded")

        deliver(host, connection, Mutation.ReleaseAsset(FULL))
        waitForIdle()
        assertEquals(listOf(HostEvent.AssetWanted(0, 0L, FULL), HostEvent.AssetWanted(0, 0L, FULL)), connection.wanted())
        assertWidth(em(SUBSET_ADVANCE) + missing, drawnLayout(FIRST), 2, "let go of, a missing glyph again")
    }

    /**
     * A stack whose lazy family is missing reads as a chain whose second family is pending,
     * and is read again, with the face, when it arrives. Bytes already registered make the
     * family an ordinary one from the start.
     */
    @Test
    fun fr48_4_a_lazy_stack_family_is_read_again_when_its_face_arrives() {
        val cache = AssetCache()
        val asked = mutableListOf<Int>()
        assertNull(cache.register(SUBSET, AssetKind.Font.ordinal + 1, subset))
        assertNull(cache.register(STACK, AssetKind.Font.ordinal + 1, lazyStack(false to listOf(SUBSET), true to listOf(FULL))))
        val pending = assertNotNull((cache.asset(STACK) as Asset.Font).chain)
        assertEquals(2, pending.members.size)
        assertNull(pending.members[0].pending)
        assertEquals(listOf(FULL), pending.members[1].pending?.assetIds?.toList())
        // Only the letter the subset lacks reaches the pending family, and asks for it.
        cache.wake = {}
        val spans = pending.spans("가똠가")
        assertEquals(listOf(1 to 2), spans.map { it.start to it.end })
        cache.drainWanted(asked::add)
        assertEquals(listOf(FULL), asked)

        assertNull(cache.register(FULL, AssetKind.Font.ordinal + 1, full))
        val loaded = assertNotNull((cache.asset(STACK) as Asset.Font).chain)
        assertTrue(loaded !== pending, "the stack is a new chain once its face is here")
        assertNull(loaded.members[1].pending)
        assertTrue(loaded.members[1].has(HANGUL_TTOM))

        // Registered before the stack, the face is there from the start.
        val early = AssetCache()
        early.register(SUBSET, AssetKind.Font.ordinal + 1, subset)
        early.register(FULL, AssetKind.Font.ordinal + 1, full)
        assertNull(early.register(STACK, AssetKind.Font.ordinal + 1, lazyStack(false to listOf(SUBSET), true to listOf(FULL))))
        val chain = assertNotNull((early.asset(STACK) as Asset.Font).chain)
        assertTrue(chain.members.all { it.pending == null })
        early.wake = {}
        chain.spans("가똠")
        val none = mutableListOf<Int>()
        early.drainWanted(none::add)
        assertEquals(emptyList(), none)
    }

    /** Whether the missing glyph face made for `똠` alone maps [codePoint], read from its own `cmap`. */
    /** A theme with only the code role set in [font]: what an application with a lazy monospace face sends. */
    private fun monoIn(font: Int) = Mutation.SetTheme(
        Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
            fonts = TypeRole.entries.map { if (it == TypeRole.Mono) font else 0 },
        ),
    )

    private fun inRole(role: TypeRole, value: String) =
        Triple(WidgetKind.Text, value, listOf(PropertyKind.TypeRole to PropertyValue.Integer(role.ordinal + 1L), size))

    /** Whether the window's picture is one colour: nothing drawn on it, or nothing new. */
    private fun ComposeUiTest.blank(): Boolean {
        val pixels = onRoot().captureToImage().toPixelMap()
        val first = pixels[0, 0]
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) if (pixels[x, y] != first) return false
        return true
    }

    /**
     * FR-48.4 criterion 7: a theme whose code role is a stack of faces the Host keeps asks for
     * nothing while no text is in that role, and for the face once one is.
     */
    @Test
    fun fr48_4_a_lazy_theme_font_is_asked_for_only_when_its_role_is_drawn() = runComposeUiTest {
        val connection = FakeHostConnection(
            screen(
                inRole(TypeRole.Body, "가"),
                before = listOf(register(STACK, lazyStack(true to listOf(FULL))), monoIn(STACK)),
            ),
        )
        val host = startHost(connection, Density(1f))
        assertEquals(emptyList(), connection.wanted(), "a screen with no code text asked for the code face")

        deliver(
            host,
            connection,
            Mutation.Create(FIRST + 1, WidgetKind.Text),
            Mutation.SetProp(FIRST + 1, PropertyKind.Text, PropertyValue.Text("똠")),
            Mutation.SetProp(FIRST + 1, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Mono.ordinal + 1L)),
            Mutation.Insert(COLUMN, FIRST + 1, 1),
        )
        waitForIdle()
        assertEquals(listOf(HostEvent.AssetWanted(0, 0L, FULL)), connection.wanted())
    }

    /**
     * FR-48.4 criterion 8: the first picture of a text whose font has no face yet is not
     * drawn with boxes; the window waits, and draws the text in its font once the bytes come.
     */
    @Test
    fun fr48_4_a_text_with_no_face_holds_the_frame_until_its_font_arrives() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(
            screen(
                inRole(TypeRole.Mono, "똠똠"),
                before = listOf(register(STACK, lazyStack(true to listOf(FULL))), monoIn(STACK)),
            ),
        )
        val host = startHost(connection, Density(1f))
        repeat(3) { mainClock.advanceTimeByFrame() }
        assertTrue(host.table.assets.holdingFrame, "the window is not waiting for the face")
        assertTrue(blank(), "boxes were drawn while the face was on its way")

        deliver(host, connection, register(FULL, full))
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertTrue(!host.table.assets.holdingFrame, "still waiting after the face arrived")
        assertTrue(!blank(), "the text was not drawn once its face arrived")
        assertWidth(2 * em(FULL_ADVANCE), drawnLayout(FIRST), 2, "the code text in its own face")
    }

    /** FR-48.4 criterion 9: a face that never comes holds the window for 300 ms at most. */
    @Test
    fun fr48_4_a_font_that_never_arrives_holds_the_frame_at_most_300_ms() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(
            screen(
                inRole(TypeRole.Mono, "똠"),
                before = listOf(register(STACK, lazyStack(true to listOf(FULL))), monoIn(STACK)),
            ),
        )
        val host = startHost(connection, Density(1f))
        repeat(3) { mainClock.advanceTimeByFrame() }
        assertTrue(host.table.assets.holdingFrame)
        mainClock.advanceTimeBy(dev.darkpyonix.composerust.runtime.FONT_HOLD_MILLIS - 100)
        assertTrue(host.table.assets.holdingFrame, "the wait ended before its time")
        mainClock.advanceTimeBy(150)
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertTrue(!host.table.assets.holdingFrame, "the window waited past 300 ms")
        assertTrue(!blank(), "the missing glyph was not drawn after the wait")
    }

    /** FR-48.4 criterion 10: a stack whose first family is here never holds the window. */
    @Test
    fun fr48_4_a_loaded_first_family_never_holds_the_frame() = runComposeUiTest {
        mainClock.autoAdvance = false
        val connection = FakeHostConnection(
            screen(
                inRole(TypeRole.Mono, "가똠"),
                before = listOf(register(STACK, lazyStack(false to listOf(SUBSET), true to listOf(FULL))), monoIn(STACK)),
            ),
        )
        val host = startHost(connection, Density(1f))
        repeat(3) { mainClock.advanceTimeByFrame() }
        assertEquals(listOf(HostEvent.AssetWanted(0, 0L, FULL)), connection.wanted())
        assertTrue(!host.table.assets.holdingFrame, "a text with a face to draw in held the window")
        assertTrue(!blank(), "the subset's letter was not drawn")
    }

    private fun missingFaceHas(codePoint: Int): Boolean {
        val bytes = dev.darkpyonix.composerust.ui.node.missingGlyphFont(intArrayOf(HANGUL_TTOM))
        return assertNotNull(parseCmapRanges(bytes)).contains(codePoint)
    }

    private companion object {
        const val COLUMN = 1
        const val FIRST = 2
        const val SUBSET = 61
        const val FULL = 62
        const val STACK = 63
        const val SIZE = 20f
        const val SUBSET_ADVANCE = 1500
        const val FULL_ADVANCE = 1000
        const val HANGUL_GA = 0xAC00
        const val HANGUL_TTOM = 0xB620
    }
}
