package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.foundation.textInput
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.SpanFontFeatureRecords
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SpanFontFeaturesTest {

    private val font = TabularDigitFont.build()

    private fun featureRecord(tag: String, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(tag.encodeToByteArray())
            .putInt(value)
            .array()

    private fun spanRecord(start: Int, length: Int, role: TypeRole? = null): ByteArray =
        ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(start)
            .putInt(length)
            .putShort((role?.ordinal?.plus(1) ?: 0).toShort())
            .putShort(0) // flags
            .putLong(0) // color
            .putLong(0) // background
            .putLong(0) // handler
            .array()

    private fun spanFontFeaturesBlob(entries: List<Pair<Int, List<Pair<String, Int>>>>): ByteArray {
        val entryCount = entries.size
        val listsAt = 4 + entryCount * 12
        var currentOffset = listsAt
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(entryCount)
        for ((spanIndex, features) in entries) {
            buffer.putInt(spanIndex)
            buffer.putInt(currentOffset)
            buffer.putInt(features.size)
            currentOffset += features.size * 8
        }
        for ((_, features) in entries) {
            for ((tag, value) in features) {
                buffer.put(tag.encodeToByteArray())
                buffer.putInt(value)
            }
        }
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    private val DIGITS = 1
    private val SIZE = 40f
    private val COLUMN = 1
    private val FIRST = 2

    private fun register() = Mutation.RegisterAsset(DIGITS, AssetKind.Font.ordinal + 1, font)

    private fun theme(font: Int) = Mutation.SetTheme(
        Theme(
            DesignSystem.Material3,
            DesignSystem.Material3,
            ColorScheme.Light,
            adaptive = false,
            fonts = TypeRole.entries.map { if (it == TypeRole.Mono) 0 else font },
        ),
    )

    @Test
    fun fr88_span_font_features_decode_blob() {
        val blob = spanFontFeaturesBlob(
            listOf(
                1 to listOf("tnum" to 1),
                3 to listOf("liga" to 0, "ss01" to 2),
            ),
        )
        val decoded = SpanFontFeatureRecords.decodeBlob(blob)
        assertNotNull(decoded)
        assertEquals(2, decoded.size)
        assertTrue(decoded.containsKey(1))
        assertTrue(decoded.containsKey(3))
        assertEquals(8, decoded[1]?.size)
        assertEquals(16, decoded[3]?.size)

        // Empty blob
        assertEquals(emptyMap(), SpanFontFeatureRecords.decodeBlob(ByteArray(0)))

        // Truncated blob
        assertNull(SpanFontFeatureRecords.decodeBlob(ByteArray(3)))

        // Corrupted offset
        val corrupted = blob.copyOf()
        corrupted[4 + 4] = 0x7F // out-of-bounds offset
        corrupted[4 + 5] = 0x7F
        assertNull(SpanFontFeatureRecords.decodeBlob(corrupted))
    }

    @Test
    fun fr88_span_font_features_tabular_numerals_on_span_only() = runComposeUiTest {
        val spans = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
            .put(spanRecord(0, 4))
            .put(spanRecord(5, 4))
            .array()
        val spanFeatures = spanFontFeaturesBlob(listOf(1 to listOf("tnum" to 1)))

        val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
        val ownFont = PropertyKind.Font to PropertyValue.Bytes(fontBlob(listOf(FontRef.Asset(DIGITS))))
        val size = PropertyKind.FontSize to PropertyValue.Float(SIZE)

        val mutations = listOf(
            register(),
            theme(0),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.Create(FIRST, WidgetKind.Text),
            Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("1111 0000")),
            Mutation.SetProp(FIRST, noRole.first, noRole.second),
            Mutation.SetProp(FIRST, ownFont.first, ownFont.second),
            Mutation.SetProp(FIRST, size.first, size.second),
            Mutation.SetProp(FIRST, PropertyKind.Spans, PropertyValue.Bytes(spans)),
            Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(spanFeatures)),
            Mutation.Insert(COLUMN, FIRST, 0),
        )

        val connection = FakeHostConnection(mutations)
        startHost(connection, Density(1f))

        val layout = drawnLayout(FIRST)
        val annotated = layout.layoutInput.text

        // Span 0 (0..4) has no fontFeatureSettings
        val styleSpan0 = annotated.spanStyles.find { it.start == 0 && it.end == 4 }
        assertNotNull(styleSpan0)
        assertNull(styleSpan0.item.fontFeatureSettings)

        // Span 1 (5..9) has tnum 1
        val styleSpan1 = annotated.spanStyles.find { it.start == 5 && it.end == 9 }
        assertNotNull(styleSpan1)
        assertEquals("tnum 1", styleSpan1.item.fontFeatureSettings)
    }

    @Test
    fun fr88_span_font_features_applied_to_role_spans() = runComposeUiTest {
        val spans = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
            .put(spanRecord(0, 4, role = TypeRole.Body))
            .put(spanRecord(5, 4, role = TypeRole.Title))
            .array()
        val spanFeatures = spanFontFeaturesBlob(listOf(0 to listOf("tnum" to 1)))

        val mutations = listOf(
            register(),
            theme(DIGITS),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.Create(FIRST, WidgetKind.Text),
            Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("1111 0000")),
            Mutation.SetProp(FIRST, PropertyKind.Spans, PropertyValue.Bytes(spans)),
            Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(spanFeatures)),
            Mutation.Insert(COLUMN, FIRST, 0),
        )

        val connection = FakeHostConnection(mutations)
        startHost(connection, Density(1f))

        val layout = drawnLayout(FIRST)
        val annotated = layout.layoutInput.text

        val span0 = annotated.spanStyles.find { it.start == 0 && it.end == 4 }
        assertNotNull(span0)
        assertEquals("tnum 1", span0.item.fontFeatureSettings)

        val span1 = annotated.spanStyles.find { it.start == 5 && it.end == 9 }
        assertNotNull(span1)
        assertNull(span1.item.fontFeatureSettings)
    }

    @Test
    fun fr88_span_font_features_override_node_features() = runComposeUiTest {
        val spans = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
            .put(spanRecord(0, 4))
            .put(spanRecord(5, 4))
            .array()
        val spanFeatures = spanFontFeaturesBlob(listOf(1 to listOf("tnum" to 1)))
        val nodeFeatures = featureRecord("liga", 0)

        val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
        val ownFont = PropertyKind.Font to PropertyValue.Bytes(fontBlob(listOf(FontRef.Asset(DIGITS))))
        val size = PropertyKind.FontSize to PropertyValue.Float(SIZE)

        val mutations = listOf(
            register(),
            theme(0),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.Create(FIRST, WidgetKind.Text),
            Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("1111 0000")),
            Mutation.SetProp(FIRST, noRole.first, noRole.second),
            Mutation.SetProp(FIRST, ownFont.first, ownFont.second),
            Mutation.SetProp(FIRST, size.first, size.second),
            Mutation.SetProp(FIRST, PropertyKind.FontFeatureSettings, PropertyValue.Bytes(nodeFeatures)),
            Mutation.SetProp(FIRST, PropertyKind.Spans, PropertyValue.Bytes(spans)),
            Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(spanFeatures)),
            Mutation.Insert(COLUMN, FIRST, 0),
        )

        val connection = FakeHostConnection(mutations)
        startHost(connection, Density(1f))

        val layout = drawnLayout(FIRST)
        assertEquals("liga 0", layout.layoutInput.style.fontFeatureSettings)

        val annotated = layout.layoutInput.text
        val span1 = annotated.spanStyles.find { it.start == 5 && it.end == 9 }
        assertNotNull(span1)
        assertEquals("liga 0, tnum 1", span1.item.fontFeatureSettings)
    }

    @Test
    fun fr88_malformed_span_font_features_handled_gracefully() = runComposeUiTest {
        val badBlob = ByteArray(7) // not valid table
        val connection = FakeHostConnection(
            listOf(
                register(),
                theme(0),
                Mutation.Create(COLUMN, WidgetKind.Column),
                Mutation.Create(FIRST, WidgetKind.Text),
                Mutation.SetProp(FIRST, PropertyKind.Text, PropertyValue.Text("hello")),
                Mutation.SetProp(FIRST, PropertyKind.SpanFontFeatures, PropertyValue.Bytes(badBlob)),
                Mutation.Insert(COLUMN, FIRST, 0),
            ),
        )
        startHost(connection, Density(1f))

        val errors = connection.events.filterIsInstance<HostEvent.ProtocolError>()
        assertEquals(1, errors.size)
        assertEquals(TableError.INVALID_FONT_FEATURES, errors[0].code)
    }
}
