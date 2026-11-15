package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.spanFontFeatureSettings
import dev.darkpyonix.composerust.foundation.textInput
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpanFontFeatureRecords
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.RendererMeasure
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
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
 * OpenType features on single runs of a `Text`: the table beside the runs becomes one
 * Compose `fontFeatureSettings` string for each run it names, once, when it arrives, and
 * each of those runs is then set with its own features after the text's.
 *
 * The widths come from [TabularDigitFont], whose `0` and `1` are of different widths unless
 * `tnum` is on, so a run that took the feature and one that did not are told apart by what
 * was drawn and not only by what was asked for.
 */
@OptIn(ExperimentalTestApi::class)
class SpanFontFeaturesTest {

    private val font = TabularDigitFont.build()

    private fun record(tag: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(tag.encodeToByteArray())
            .putInt(value)
            .array()

    /**
     * The table as the Host lays it out: the entry count, a 12-byte entry for each run
     * (run index, offset from the start of the blob, record count), then the records.
     */
    private fun table(vararg entries: Pair<Int, ByteArray>): ByteArray {
        var listAt = 4 + entries.size * 12
        val out = ByteBuffer.allocate(listAt + entries.sumOf { it.second.size }).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(entries.size)
        for ((run, records) in entries) {
            out.putInt(run).putInt(listAt).putInt(records.size / 8)
            listAt += records.size
        }
        for ((_, records) in entries) out.put(records)
        return out.array()
    }

    /** [bytes] with the little-endian word at [at] replaced by [value]. */
    private fun patched(bytes: ByteArray, at: Int, value: Int): ByteArray =
        bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value) }

    private fun features(bytes: ByteArray) = PropertyKind.SpanFontFeatures to PropertyValue.Bytes(bytes)
    private fun runs(vararg records: ByteArray) =
        PropertyKind.Spans to PropertyValue.Bytes(records.fold(ByteArray(0)) { all, record -> all + record })

    private val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
    private val ownFont = PropertyKind.Font to PropertyValue.Bytes(fontBlob(listOf(FontRef.Asset(DIGITS))))
    private val size = PropertyKind.FontSize to PropertyValue.Float(SIZE)

    private fun register() = Mutation.RegisterAsset(DIGITS, AssetKind.Font.ordinal + 1, font)

    /** A theme with [font] for every role but code, or none at zero. */
    private fun theme(font: Int) = Mutation.SetTheme(
        Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
            fonts = TypeRole.entries.map { if (it == TypeRole.Mono) 0 else font },
        ),
    )

    /** A column of `Text` nodes, each saying its text with its own properties. */
    private fun screen(
        themeFont: Int,
        vararg nodes: Pair<String, List<Pair<PropertyKind, PropertyValue>>>,
    ): List<Mutation> = buildList {
        add(register())
        add(theme(themeFont))
        add(Mutation.Create(COLUMN, WidgetKind.Column))
        nodes.forEachIndexed { index, (text, props) ->
            val id = FIRST + index
            add(Mutation.Create(id, WidgetKind.Text))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(text)))
            for ((prop, value) in props) add(Mutation.SetProp(id, prop, value))
            add(Mutation.Insert(COLUMN, id, index))
        }
    }

    /** How wide the letters from [start] up to [end] were drawn, on the first line. */
    private fun width(layout: TextLayoutResult, start: Int, end: Int): Float =
        layout.getHorizontalPosition(end, usePrimaryDirection = true) -
            layout.getHorizontalPosition(start, usePrimaryDirection = true)

    /** Four digits at [fontSize] in [TabularDigitFont], each [advance] units wide. */
    private fun digits(advance: Int, fontSize: Float = SIZE): Float = 4 * fontSize * advance / TabularDigitFont.UNITS_PER_EM

    /** Within half a pixel a glyph, which is how far the shaper may round an advance. */
    private fun assertWidth(expected: Float, actual: Float, case: String) {
        assertTrue(abs(expected - actual) <= 4 * 0.5f + 0.01f, "$case: $actual wide where $expected was expected")
    }

    /** The features each range of [layout]'s text was given, for the ranges given any. */
    private fun featured(layout: TextLayoutResult): Map<IntRange, String> =
        layout.layoutInput.text.spanStyles
            .filter { it.item.fontFeatureSettings != null }
            .associate { (it.start until it.end) to it.item.fontFeatureSettings!! }

    private fun decode(bytes: ByteArray): Pair<Map<Int, List<Byte>>?, List<String>> {
        val errors = mutableListOf<String>()
        val decoded = SpanFontFeatureRecords.decodeBlob(bytes, errors::add)
        return decoded?.mapValues { it.value.toList() } to errors
    }

    @Test
    fun fr88_span_font_feature_table_decodes_by_its_layout() {
        assertEquals(12, SpanFontFeatureRecords.ENTRY_LENGTH)
        assertEquals(8, SpanFontFeatureRecords.FEATURE_LENGTH)
        assertEquals(16, SpanFontFeatureRecords.MAX_FEATURES)

        // Written out byte for byte, not through the helper, so the helper cannot share a
        // mistake with the decoder: two entries, then one record and two records.
        val blob = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2)
            .putInt(1).putInt(28).putInt(1)
            .putInt(3).putInt(36).putInt(2)
            .put(record("tnum", 1))
            .put(record("liga", 0))
            .put(record("ss01", 2))
            .array()
        assertEquals(blob.toList(), table(1 to record("tnum", 1), 3 to (record("liga", 0) + record("ss01", 2))).toList())
        val (decoded, errors) = decode(blob)
        assertEquals(emptyList(), errors)
        assertEquals(
            mapOf(
                1 to record("tnum", 1).toList(),
                3 to (record("liga", 0) + record("ss01", 2)).toList(),
            ),
            decoded,
        )
        assertEquals(listOf(1, 3), decoded!!.keys.toList(), "in the table's order")
        assertEquals(mapOf(1 to "tnum 1", 3 to "liga 0, ss01 2"), spanFontFeatureSettings(blob) { error(it) })

        // No table at all, and a table with no entries.
        val (none, noErrors) = decode(ByteArray(0))
        assertEquals(emptyMap(), none)
        assertEquals(emptyList(), noErrors)
        assertNull(spanFontFeatureSettings(ByteArray(0)) { error(it) })
        assertNull(spanFontFeatureSettings(ByteArray(4)) { error(it) })

        // Sixteen features for one run are taken.
        val most = (0 until 16).fold(ByteArray(0)) { all, _ -> all + record("tnum", 1) }
        assertEquals(16 * 8, decode(table(0 to most)).first?.get(0)?.size)

        val secondEntry = 4 + 12
        val malformed = mapOf(
            "too short for its count" to blob.copyOf(3),
            "cut inside its entries" to blob.copyOf(20),
            "a count one more than its entries" to patched(blob, 0, 3),
            "a count of four thousand million" to patched(blob, 0, -1),
            "a run index no Int holds" to patched(blob, 4, -1),
            "a list that ends past the blob" to patched(blob, secondEntry + 4, 44),
            "an offset before the start" to patched(blob, secondEntry + 4, -4),
            "an offset that is not a multiple of four" to patched(blob, 4 + 4, 30),
            "an offset inside the entries" to patched(blob, 4 + 4, 16),
            "more records than the blob holds" to patched(blob, secondEntry + 8, 3),
            "a negative record count" to patched(blob, secondEntry + 8, -1),
            "seventeen features for one run" to table(0 to most + record("tnum", 1)),
            "a run named twice" to patched(blob, secondEntry, 1),
        )
        for ((case, bytes) in malformed) {
            val (map, why) = decode(bytes)
            assertNull(map, "$case: decoded as $map")
            assertEquals(1, why.size, "$case is reported once: $why")
            val reported = mutableListOf<String>()
            assertNull(spanFontFeatureSettings(bytes, reported::add), case)
            assertEquals(why, reported, case)
        }

        // The records of each run are held to the rules of a node's own list, and one bad
        // run refuses the whole table, the good run with it.
        val unprintable = table(0 to record("tnum", 1), 2 to byteArrayOf(0x01, 'n'.code.toByte(), 'u'.code.toByte(), 'm'.code.toByte(), 1, 0, 0, 0))
        assertNotNull(decode(unprintable).first, "the layout itself is sound")
        val reported = mutableListOf<String>()
        assertNull(spanFontFeatureSettings(unprintable, reported::add))
        assertEquals(1, reported.size, reported.toString())
        assertTrue("run 2" in reported.single(), reported.single())
    }

    @Test
    fun fr88_span_font_features_tabular_numerals_on_span_only() = runComposeUiTest {
        val host = startHost(
            FakeHostConnection(
                screen(
                    0,
                    // Ones, which tnum widens, and zeros, which it narrows. The middle run of
                    // each names tnum; the runs on either side of it name nothing.
                    "1111 1111 1111" to listOf(
                        noRole, ownFont, size,
                        runs(spanRecord(0, 4), spanRecord(5, 4), spanRecord(10, 4)),
                        features(table(1 to record("tnum", 1))),
                    ),
                    "0000 0000 0000" to listOf(
                        noRole, ownFont, size,
                        runs(spanRecord(0, 4), spanRecord(5, 4), spanRecord(10, 4)),
                        features(table(1 to record("tnum", 1))),
                    ),
                ),
            ),
            Density(1f),
        )
        assertEquals(mapOf(1 to "tnum 1"), host.table.node(FIRST)?.spanFontFeatures)

        val ones = drawnLayout(FIRST)
        assertEquals(mapOf((5 until 9) to "tnum 1"), featured(ones), "only the run that names it is given the feature")
        assertNull(ones.layoutInput.style.fontFeatureSettings, "the text itself names none")
        assertWidth(digits(TabularDigitFont.ONE_ADVANCE), width(ones, 0, 4), "1111 before the run")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(ones, 5, 9), "1111 in the run with tnum")
        assertWidth(digits(TabularDigitFont.ONE_ADVANCE), width(ones, 10, 14), "1111 after the run")

        val zeros = drawnLayout(FIRST + 1)
        assertEquals(mapOf((5 until 9) to "tnum 1"), featured(zeros))
        assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(zeros, 0, 4), "0000 before the run")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(zeros, 5, 9), "0000 in the run with tnum")
        assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(zeros, 10, 14), "0000 after the run")
        assertTrue(
            abs(width(ones, 5, 9) - width(zeros, 5, 9)) < 0.5f,
            "with tnum the two runs are one width: ${width(ones, 5, 9)} against ${width(zeros, 5, 9)}",
        )
        assertTrue(
            width(zeros, 0, 4) - width(ones, 0, 4) > 10f,
            "without it the digits keep their widths: ${width(ones, 0, 4)} against ${width(zeros, 0, 4)}",
        )
    }

    @Test
    fun fr88_span_font_features_applied_to_role_spans() = runComposeUiTest {
        val body = PropertyKind.TypeRole to PropertyValue.Integer(TypeRole.Body.ordinal + 1L)
        val title = TypeRole.Title.ordinal + 1
        // Text with a role, which takes no font of its own. Its first run has a role too,
        // its second has none, and both name tnum; the third names nothing.
        val props = listOf(
            body,
            runs(spanRecord(0, 4, role = title), spanRecord(5, 4), spanRecord(10, 4)),
            features(table(0 to record("tnum", 1), 1 to record("tnum", 1))),
        )
        val connection = FakeHostConnection(screen(DIGITS, "1111 1111 1111" to props, "0000 0000 0000" to props))
        val host = startHost(connection, Density(1f))
        assertEquals(mapOf(0 to "tnum 1", 1 to "tnum 1"), host.table.node(FIRST)?.spanFontFeatures)
        // Features on a run of text with a role are not a font named for it: nothing is reported.
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())

        val ones = drawnLayout(FIRST)
        val zeros = drawnLayout(FIRST + 1)
        assertEquals(mapOf((0 until 4) to "tnum 1", (5 until 9) to "tnum 1"), featured(ones))
        // The run with a role keeps the role's size, which is not the text's.
        val bodySize = ones.layoutInput.style.fontSize
        val titled = ones.layoutInput.text.spanStyles.single { it.start == 0 && it.end == 4 && it.item.fontFeatureSettings != null }
        assertTrue(
            titled.item.fontSize.isSp && titled.item.fontSize != bodySize,
            "the run is set at its role's size: ${titled.item.fontSize} against $bodySize",
        )
        // Compared with each other and not with a number, so the role's letter spacing,
        // which every digit carries alike, does not come into it.
        assertTrue(
            abs(width(ones, 0, 4) - width(zeros, 0, 4)) < 0.5f,
            "the run with a role and tnum is one width: ${width(ones, 0, 4)} against ${width(zeros, 0, 4)}",
        )
        assertTrue(
            abs(width(ones, 5, 9) - width(zeros, 5, 9)) < 0.5f,
            "the run with tnum in text with a role is one width: ${width(ones, 5, 9)} against ${width(zeros, 5, 9)}",
        )
        assertTrue(
            width(zeros, 10, 14) - width(ones, 10, 14) > 10f,
            "the run that names nothing keeps its digits' widths: ${width(ones, 10, 14)} against ${width(zeros, 10, 14)}",
        )
        assertTrue(
            abs(width(ones, 0, 4) - width(ones, 5, 9)) > 1f,
            "the run with a role is drawn at another size than the one without: ${width(ones, 0, 4)} against ${width(ones, 5, 9)}",
        )
    }

    /** The node's features and a run's own are both in force in that run, the run's last. */
    @Test
    fun fr88_span_font_features_override_node_features() = runComposeUiTest {
        val nodeTnum = PropertyKind.FontFeatureSettings to PropertyValue.Bytes(record("tnum", 1))
        val host = startHost(
            FakeHostConnection(
                screen(
                    0,
                    // The text asks for tnum. Its first run turns tnum off again, its second
                    // names another feature and says nothing of tnum, its third names nothing.
                    "0000 0000 0000" to listOf(
                        noRole, ownFont, size, nodeTnum,
                        runs(spanRecord(0, 4), spanRecord(5, 4), spanRecord(10, 4)),
                        features(table(0 to record("tnum", 0), 1 to record("liga", 0))),
                    ),
                ),
            ),
            Density(1f),
        )
        assertEquals("tnum 1", host.table.node(FIRST)?.fontFeatures)
        assertEquals(mapOf(0 to "tnum 0", 1 to "liga 0"), host.table.node(FIRST)?.spanFontFeatures)

        val layout = drawnLayout(FIRST)
        assertEquals("tnum 1", layout.layoutInput.style.fontFeatureSettings)
        assertEquals(
            mapOf((0 until 4) to "tnum 1, tnum 0", (5 until 9) to "tnum 1, liga 0"),
            featured(layout),
            "a run's features come after the text's, and a run that names none is given none of its own",
        )
        assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(layout, 0, 4), "the run's tnum 0 wins over the text's tnum 1")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(layout, 5, 9), "a run naming another feature keeps the text's tnum")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(layout, 10, 14), "a run naming nothing is drawn with the text's tnum")
    }

    @Test
    fun fr88_malformed_span_font_features_handled_gracefully() = runComposeUiTest {
        val three = runs(spanRecord(0, 4), spanRecord(5, 4), spanRecord(10, 4))
        val good = table(1 to record("tnum", 1))
        val seventeen = (0 until 17).fold(ByteArray(0)) { all, _ -> all + record("tnum", 1) }
        val unprintable = byteArrayOf(0x01, 'n'.code.toByte(), 'u'.code.toByte(), 'm'.code.toByte(), 1, 0, 0, 0)
        // Each names tnum for the middle run and is wrong in one way. The last two are
        // sound tables that name a run the text does not have.
        val bad = listOf(
            "cut short" to good.copyOf(7),
            "an offset past the end" to patched(good, 4 + 4, 4096),
            "seventeen features" to table(1 to seventeen),
            "a tag that is not printable" to table(1 to record("tnum", 1), 2 to unprintable),
            "a run named twice" to table(1 to record("tnum", 1), 1 to record("tnum", 1)),
            "a run past the last" to table(1 to record("tnum", 1), 3 to record("tnum", 1)),
            "a run index no text has" to table(1 to record("tnum", 1), -1 to record("tnum", 1)),
        )
        val nodes = bad.map { (_, bytes) -> "0000 0000 0000" to listOf(noRole, ownFont, size, three, features(bytes)) } +
            // A table for a text with no runs at all, and after them a sound one.
            ("0000 0000 0000" to listOf(noRole, ownFont, size, features(good))) +
            ("0000 0000 0000" to listOf(noRole, ownFont, size, three, features(good)))
        val noRuns = FIRST + bad.size
        val sound = noRuns + 1
        val connection = FakeHostConnection(screen(0, *nodes.toTypedArray()))
        val host = startHost(connection, Density(1f))

        fun errors() = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        val first = errors()
        assertEquals(
            List(bad.size + 1) { TableError.INVALID_FONT_FEATURES },
            first.map { it.code },
            first.map { it.message }.toString(),
        )
        // One report for each text that is wrong, saying which.
        for (id in FIRST..noRuns) {
            assertEquals(1, first.count { "node $id:" in it.message }, "node $id: ${first.map { it.message }}")
        }

        // The process lives on and each text is drawn, its runs with it, without features.
        for (id in FIRST..noRuns) {
            val case = bad.getOrNull(id - FIRST)?.first ?: "no runs"
            val layout = drawnLayout(id)
            assertEquals("0000 0000 0000", layout.layoutInput.text.text, case)
            assertEquals(emptyMap(), featured(layout), case)
            assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(layout, 5, 9), case)
            if (id != noRuns) assertEquals(3, host.table.node(id)!!.textInput().runs.size, case)
        }
        // A table that could not be read leaves nothing on the node.
        for (id in FIRST until FIRST + 5) assertNull(host.table.node(id)?.spanFontFeatures, "node $id")
        // The text beside them is untouched by any of it.
        assertEquals(mapOf((5 until 9) to "tnum 1"), featured(drawnLayout(sound)))
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(drawnLayout(sound), 5, 9), "the sound table")

        // Drawing the same wrong tables again says nothing more.
        connection.scheduleFrame(
            (FIRST..sound).map { Mutation.SetProp(it, PropertyKind.Text, PropertyValue.Text("0000 0000 0001")) },
        )
        host.renderFrame(0L)
        waitForIdle()
        assertEquals("0000 0000 0001", drawnLayout(FIRST).layoutInput.text.text)
        assertEquals(first.size, errors().size, errors().drop(first.size).map { it.message }.toString())

        // A sound table sent afterwards is taken.
        connection.scheduleFrame((FIRST until noRuns).map { Mutation.SetProp(it, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(good)) })
        host.renderFrame(0L)
        waitForIdle()
        assertEquals(first.size, errors().size, errors().drop(first.size).map { it.message }.toString())
        for (id in FIRST until noRuns) {
            assertEquals(mapOf((5 until 9) to "tnum 1"), featured(drawnLayout(id)), "node $id")
            assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(drawnLayout(id), 5, 9), "node $id once its table is sound")
        }
    }

    /** A table that goes away takes its features with it. */
    @Test
    fun fr88_span_font_features_are_cleared_with_the_property() = runComposeUiTest {
        val connection = FakeHostConnection(
            screen(
                0,
                "0000 0000" to listOf(
                    noRole, ownFont, size,
                    runs(spanRecord(0, 4), spanRecord(5, 4)),
                    features(table(1 to record("tnum", 1))),
                ),
            ),
        )
        val host = startHost(connection, Density(1f))
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), width(drawnLayout(FIRST), 5, 9), "with the table")

        connection.scheduleFrame(listOf(Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.None)))
        host.renderFrame(0L)
        waitForIdle()
        assertNull(host.table.node(FIRST)?.spanFontFeatures)
        assertEquals(emptyMap(), featured(drawnLayout(FIRST)))
        assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(drawnLayout(FIRST), 5, 9), "once the table is gone")
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
    }

    /** Only a `Text` takes the table: anything else reports it and keeps nothing. */
    @Test
    fun fr88_span_font_features_belong_to_text_only() {
        val nodes = NodeTable()
        nodes.apply(Mutation.Create(FIRST, WidgetKind.TextField))
        nodes.apply(Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(table(0 to record("tnum", 1)))))
        assertEquals(listOf(TableError.UNSUPPORTED_PROPERTY), nodes.drainErrors().map { it.code })
        assertNull(nodes.node(FIRST)?.spanFontFeatures)
    }

    @Test
    fun fr88_span_font_features_do_not_allocate_in_steady_state() {
        val nodes = NodeTable()
        val props = listOf(
            PropertyKind.Text to PropertyValue.Text("0000 0000"),
            runs(spanRecord(0, 4), spanRecord(5, 4)),
        )
        for (id in listOf(FIRST, FIRST + 1)) {
            nodes.apply(Mutation.Create(id, WidgetKind.Text))
            for ((prop, value) in props) nodes.apply(Mutation.SetProp(id, prop, value))
        }
        val blob = features(table(1 to record("tnum", 1)))
        nodes.apply(Mutation.SetProp(FIRST, blob.first, blob.second))
        assertEquals(emptyList(), nodes.drainErrors())
        val featured = nodes.node(FIRST)!!
        val plain = nodes.node(FIRST + 1)!!
        val made = featured.spanFontFeatures
        assertEquals(mapOf(1 to "tnum 1"), made)
        assertNull(plain.spanFontFeatures)

        // A frame that only changes the text keeps the map made when the table arrived,
        // and what the text is resolved from is that map, not one read again.
        repeat(1_000) { frame ->
            nodes.apply(Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("000$frame 0000".take(9))))
            assertSame(made, featured.spanFontFeatures)
            assertSame(made, featured.textInput().runFontFeatures)
        }
        assertEquals(emptyList(), nodes.drainErrors())

        // Reading the node's text costs the same with a feature table as without.
        fun perRead(node: dev.darkpyonix.composerust.ui.node.Node): Long {
            var sink = 0
            repeat(50_000) { sink += node.textInput().runFontFeatures.size }
            val before = threads.currentThreadAllocatedBytes
            repeat(50_000) { sink += node.textInput().runFontFeatures.size }
            val bytes = (threads.currentThreadAllocatedBytes - before) / 50_000L
            check(sink >= 0)
            return bytes
        }
        perRead(plain)
        perRead(featured)
        val withFeatures = perRead(featured)
        val without = perRead(plain)
        assertTrue(
            withFeatures <= without + 8,
            "reading a text with a feature table allocated $withFeatures bytes where one without took $without",
        )
    }

    @Test
    fun fr88_measured_span_font_features_match_drawn_text() = runComposeUiTest {
        val spans = runs(spanRecord(0, 4), spanRecord(5, 4))
        val tnumRun = table(1 to record("tnum", 1))
        startHost(
            FakeHostConnection(
                screen(
                    0,
                    "0000 1111" to listOf(noRole, ownFont, size, spans, features(tnumRun)),
                    "0000 1111" to listOf(noRole, ownFont, size, spans),
                ),
            ),
            Density(1f),
        )
        fun lineWidth(layout: TextLayoutResult) = layout.getLineRight(0) - layout.getLineLeft(0)
        val drawnFeatured = lineWidth(drawnLayout(FIRST))
        val drawnPlain = lineWidth(drawnLayout(FIRST + 1))

        val spanBytes = spanRecord(0, 4) + spanRecord(5, 4)
        val requests = MeasureRequests()
            .text("0000 1111", role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = SIZE, fonts = listOf(FontRef.Asset(DIGITS)), spans = spanBytes, spanFeatures = tnumRun)
            .text("0000 1111", role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = SIZE, fonts = listOf(FontRef.Asset(DIGITS)), spans = spanBytes)
        val out = resultBuffer(requests.count)
        var status = 0
        var results: List<MeasuredResult> = emptyList()
        runOnIdle {
            status = RendererMeasure.measure(requests.encode(), requests.count, out)
            results = out.results(requests.count)
        }
        assertEquals(MeasureRecords.CALL_OK, status)
        assertEquals(MeasureRecords.STATUS_OK, results[0].status)
        assertEquals(MeasureRecords.STATUS_OK, results[1].status)

        assertEquals(drawnFeatured.toRawBits(), results[0].width.toRawBits(), "the run with tnum: measured ${results[0].width}, drawn $drawnFeatured")
        assertEquals(drawnPlain.toRawBits(), results[1].width.toRawBits(), "no run features: measured ${results[1].width}, drawn $drawnPlain")
        // In this font tnum widens ones, so the run that names it measures wider.
        assertTrue(
            results[0].width - results[1].width > 2f,
            "the tnum run measures wider than the plain one: ${results[0].width} against ${results[1].width}",
        )

        // A table the renderer refuses for the property is refused in a measure request too.
        val malformed = MeasureRequests()
            .text("0000 1111", role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = SIZE, fonts = listOf(FontRef.Asset(DIGITS)), spans = spanBytes, spanFeatures = tnumRun.copyOf(10))
        val malformedOut = resultBuffer(1)
        runOnIdle { RendererMeasure.measure(malformed.encode(), 1, malformedOut) }
        assertEquals(MeasureRecords.STATUS_MALFORMED, malformedOut.results(1).single().status)
    }

    private val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private companion object {
        const val COLUMN = 1
        const val FIRST = 2
        const val DIGITS = 51
        const val SIZE = 20f
    }
}
