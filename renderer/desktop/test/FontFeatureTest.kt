package dev.darkpyonix.composerust.test

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.appendFontFeatures
import dev.darkpyonix.composerust.foundation.fontFeatureSettings
import dev.darkpyonix.composerust.foundation.textInput
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * OpenType features on a `Text` and a `TextField`: the blob becomes Compose's
 * `fontFeatureSettings` string once, when it arrives, and the font's tabular figures are
 * what the text is then set with.
 *
 * The widths come from [TabularDigitFont], whose `0` and `1` are of different widths unless
 * `tnum` is on. No installed font is relied on, so the test means the same on every machine.
 */
@OptIn(ExperimentalTestApi::class)
class FontFeatureTest {

    private val font = TabularDigitFont.build()

    private fun record(tag: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(tag.encodeToByteArray())
            .putInt(value)
            .array()

    private val tnum = PropertyKind.FontFeatureSettings to PropertyValue.Bytes(record("tnum", 1))
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

    /** A column of nodes of [kind], each saying its text with its own properties. */
    private fun screen(
        kind: WidgetKind,
        themeFont: Int,
        vararg nodes: Pair<String, List<Pair<PropertyKind, PropertyValue>>>,
    ): List<Mutation> = buildList {
        add(register())
        add(theme(themeFont))
        add(Mutation.Create(COLUMN, WidgetKind.Column))
        nodes.forEachIndexed { index, (text, props) ->
            val id = FIRST + index
            add(Mutation.Create(id, kind))
            add(Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(text)))
            for ((prop, value) in props) add(Mutation.SetProp(id, prop, value))
            add(Mutation.Insert(COLUMN, id, index))
        }
    }

    private fun width(layout: TextLayoutResult): Float = layout.getLineRight(0) - layout.getLineLeft(0)

    /** Four digits at [SIZE] in [TabularDigitFont], each [advance] units wide. */
    private fun digits(advance: Int): Float = 4 * SIZE * advance / TabularDigitFont.UNITS_PER_EM

    /** Within half a pixel a glyph, which is how far the shaper may round an advance. */
    private fun assertWidth(expected: Float, actual: Float, case: String) {
        assertTrue(abs(expected - actual) <= 4 * 0.5f + 0.01f, "$case: $actual wide where $expected was expected")
    }

    @Test
    fun fr83_tabular_numerals_equalise_digit_widths() = runComposeUiTest {
        val featured = listOf(noRole, ownFont, size, tnum)
        val plain = listOf(noRole, ownFont, size)
        startHost(
            FakeHostConnection(
                screen(WidgetKind.Text, 0, "1111" to featured, "0000" to featured, "1111" to plain, "0000" to plain),
            ),
            Density(1f),
        )
        val ones = width(drawnLayout(FIRST))
        val zeros = width(drawnLayout(FIRST + 1))
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), ones, "1111 with tnum")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), zeros, "0000 with tnum")
        assertTrue(abs(ones - zeros) < 0.5f, "with tnum the two are one width: $ones against $zeros")

        val plainOnes = width(drawnLayout(FIRST + 2))
        val plainZeros = width(drawnLayout(FIRST + 3))
        assertWidth(digits(TabularDigitFont.ONE_ADVANCE), plainOnes, "1111 without features")
        assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), plainZeros, "0000 without features")
        assertTrue(plainZeros - plainOnes > 10f, "without features the digits keep their widths: $plainOnes against $plainZeros")
    }

    @Test
    fun fr83_role_text_keeps_its_role_and_takes_features() = runComposeUiTest {
        val body = PropertyKind.TypeRole to PropertyValue.Integer(TypeRole.Body.ordinal + 1L)
        val connection = FakeHostConnection(
            screen(WidgetKind.Text, DIGITS, "1111" to listOf(body, tnum), "0000" to listOf(body, tnum), "1111" to listOf(body)),
        )
        startHost(connection, Density(1f))
        val featured = drawnLayout(FIRST)
        val featuredZeros = drawnLayout(FIRST + 1)
        val plain = drawnLayout(FIRST + 2)

        // The role's font, size and weight are what they are without the features.
        val withStyle = featured.layoutInput.style
        val plainStyle = plain.layoutInput.style
        assertEquals(plainStyle.fontFamily, withStyle.fontFamily, "the role's font")
        assertEquals(plainStyle.fontSize, withStyle.fontSize, "the role's size")
        assertEquals(plainStyle.fontWeight, withStyle.fontWeight, "the role's weight")
        assertEquals(plainStyle.lineHeight, withStyle.lineHeight, "the role's line height")
        assertEquals("\"tnum\" 1", withStyle.fontFeatureSettings)
        assertNull(plainStyle.fontFeatureSettings)

        // Only the digits' widths change.
        val roleSize = withStyle.fontSize.value
        val tabular = 4 * roleSize * TabularDigitFont.TABULAR_ADVANCE / TabularDigitFont.UNITS_PER_EM
        assertWidth(tabular, width(featured), "1111 in Body with tnum")
        assertWidth(tabular, width(featuredZeros), "0000 in Body with tnum")
        assertWidth(4 * roleSize * TabularDigitFont.ONE_ADVANCE / TabularDigitFont.UNITS_PER_EM, width(plain), "1111 in Body")
        // Features on text with a role are not a font named for it, so nothing is reported.
        assertEquals(emptyList(), connection.events.filterIsInstance<HostEvent.ProtocolError>())
    }

    @Test
    fun fr83_text_field_applies_font_features() = runComposeUiTest {
        val featured = listOf(noRole, ownFont, size, tnum)
        val plain = listOf(noRole, ownFont, size)
        startHost(
            FakeHostConnection(
                screen(WidgetKind.TextField, 0, "1111" to featured, "0000" to featured, "1111" to plain, "0000" to plain),
            ),
            Density(1f),
        )
        val ones = width(drawnLayout(FIRST))
        val zeros = width(drawnLayout(FIRST + 1))
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), ones, "typed 1111 with tnum")
        assertWidth(digits(TabularDigitFont.TABULAR_ADVANCE), zeros, "typed 0000 with tnum")
        assertTrue(abs(ones - zeros) < 0.5f, "typed digits with tnum are one width: $ones against $zeros")
        val plainOnes = width(drawnLayout(FIRST + 2))
        val plainZeros = width(drawnLayout(FIRST + 3))
        assertTrue(plainZeros - plainOnes > 10f, "typed digits without features keep their widths: $plainOnes against $plainZeros")
        assertEquals("\"tnum\" 1", drawnLayout(FIRST).layoutInput.style.fontFeatureSettings)
    }

    /** The placeholder is set in the field's style, features included. */
    @Test
    fun fr83_text_field_placeholder_takes_font_features() = runComposeUiTest {
        val mutations = listOf(
            register(),
            theme(0),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.Create(FIRST, WidgetKind.TextField),
            Mutation.SetProp(FIRST, PropertyKind.Placeholder, PropertyValue.Text("0000")),
        ) + listOf(noRole, ownFont, size, tnum).map { (kind, value) -> Mutation.SetProp(FIRST, kind, value) } +
            Mutation.Insert(COLUMN, FIRST, 0)
        val host = startHost(FakeHostConnection(mutations), Density(1f))
        assertEquals("\"tnum\" 1", host.table.node(FIRST)?.fontFeatures)
        val layouts = mutableListOf<TextLayoutResult>()
        collectLayouts(FIRST, layouts)
        assertTrue(
            layouts.any { it.layoutInput.text.text == "0000" && it.layoutInput.style.fontFeatureSettings == "\"tnum\" 1" },
            "the placeholder was set with the features: ${layouts.map { it.layoutInput.text.text to it.layoutInput.style.fontFeatureSettings }}",
        )
    }

    @Test
    fun fr83_malformed_font_features_are_a_protocol_error() = runComposeUiTest {
        val cut = PropertyKind.FontFeatureSettings to PropertyValue.Bytes(record("tnum", 1).copyOf(7))
        val unprintable = PropertyKind.FontFeatureSettings to PropertyValue.Bytes(
            record("tnum", 1) + byteArrayOf(0x01, 'n'.code.toByte(), 'u'.code.toByte(), 'm'.code.toByte(), 1, 0, 0, 0),
        )
        val tooMany = PropertyKind.FontFeatureSettings to PropertyValue.Bytes(
            (0 until 17).fold(ByteArray(0)) { bytes, _ -> bytes + record("tnum", 1) },
        )
        val connection = FakeHostConnection(
            screen(
                WidgetKind.Text,
                0,
                "0000" to listOf(noRole, ownFont, size, cut),
                "0000" to listOf(noRole, ownFont, size, unprintable),
                "0000" to listOf(noRole, ownFont, size, tooMany),
            ),
        )
        val host = startHost(connection, Density(1f))
        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(
            listOf(TableError.INVALID_FONT_FEATURES, TableError.INVALID_FONT_FEATURES, TableError.INVALID_FONT_FEATURES),
            errors.map { it.code },
            errors.map { it.message }.toString(),
        )
        assertEquals(24, TableError.INVALID_FONT_FEATURES)
        // The process lives on and each text is drawn, without features.
        for (index in 0 until 3) {
            assertNull(host.table.node(FIRST + index)?.fontFeatures)
            assertWidth(digits(TabularDigitFont.ZERO_ADVANCE), width(drawnLayout(FIRST + index)), "malformed list $index")
        }
    }

    /** The string Compose is given, in order, with a repeated tag written twice for the later to win. */
    @Test
    fun fr83_feature_records_become_the_compose_string() {
        val errors = mutableListOf<String>()
        val bytes = record("tnum", 1) + record("liga", 0) + record("cv01", 0x0102) + record("tnum", 0)
        assertEquals("\"tnum\" 1, \"liga\" 0, \"cv01\" 258, \"tnum\" 0", fontFeatureSettings(bytes, errors::add))
        assertNull(fontFeatureSettings(ByteArray(0), errors::add), "an empty list is no features")
        assertEquals(emptyList(), errors)
        assertEquals("\"liga\" 0, \"tnum\" 1", appendFontFeatures("\"liga\" 0", "\"tnum\" 1"))
        assertEquals("\"tnum\" 1", appendFontFeatures(null, "\"tnum\" 1"))
        assertEquals("\"liga\" 0", appendFontFeatures("\"liga\" 0", null))
    }

    @Test
    fun fr83_font_features_do_not_allocate_in_steady_state() {
        val table = NodeTable()
        table.apply(Mutation.Create(FIRST, WidgetKind.Text))
        table.apply(Mutation.Create(FIRST + 1, WidgetKind.Text))
        table.apply(Mutation.SetProp(FIRST, tnum.first, tnum.second))
        table.drainErrors()
        val featured = table.node(FIRST)!!
        val plain = table.node(FIRST + 1)!!
        val made = featured.fontFeatures
        assertEquals("\"tnum\" 1", made)

        // A frame that only changes the text keeps the string made when the list arrived.
        repeat(1_000) { frame ->
            table.apply(Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("$frame")))
            assertSame(made, featured.fontFeatures)
        }
        assertEquals(emptyList(), table.drainErrors())

        // Reading the node's text costs the same with features as without.
        fun perRead(node: dev.darkpyonix.composerust.ui.node.Node): Long {
            var sink = 0
            repeat(20_000) { sink += node.textInput().fontFeatures?.length ?: 0 }
            val before = threads.currentThreadAllocatedBytes
            repeat(50_000) { sink += node.textInput().fontFeatures?.length ?: 0 }
            val bytes = (threads.currentThreadAllocatedBytes - before) / 50_000L
            check(sink >= 0)
            return bytes
        }
        val withFeatures = perRead(featured)
        val without = perRead(plain)
        assertTrue(
            withFeatures <= without + 8,
            "reading a text with features allocated $withFeatures bytes where one without took $without",
        )
    }

    private val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Every text layout under node [nodeId], the placeholder's and the field's own. */
    private fun androidx.compose.ui.test.ComposeUiTest.collectLayouts(nodeId: Int, out: MutableList<TextLayoutResult>) {
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
            node.children.forEach(::walk)
        }
        walk(
            onNodeWithTag(dev.darkpyonix.composerust.ui.node.nodeTestTag(nodeId), useUnmergedTree = true)
                .fetchSemanticsNode(),
        )
    }

    private companion object {
        const val COLUMN = 1
        const val FIRST = 2
        const val DIGITS = 51
        const val SIZE = 20f
    }
}
