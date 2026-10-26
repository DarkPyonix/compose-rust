package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.TableError
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fonts an application ships, used for every piece of text and not swapped for the
 * machine's own.
 *
 * Every face here is built by [TinyIconFont], whose glyph is a block of a width no installed
 * font gives the same letter. So a letter that comes out exactly that wide was drawn in the
 * shipped face, and one that does not came from a fallback. The Latin face has a glyph for
 * `A` and none for Hangul; the Hangul face has one for `가`. That is the shape of Inter and
 * Pretendard: a Korean letter in Latin text has to come from the Korean face the
 * application shipped, not from whichever Korean font the machine happens to have.
 *
 * These run on the desktop renderer's own interpreter, which the GraalVM image and the
 * Kotlin/Native renderers for macOS, Linux and Windows all compile from the same files.
 */
@OptIn(ExperimentalTestApi::class)
class FontStackTest {

    private val latin = TinyIconFont.build(codePoint = 0xEA60, family = "Tiny Latin")
    private val hangul = TinyIconFont.build(codePoint = 0xAC00, family = "Tiny Hangul")

    /** A stack asset's bytes, as compose-rust's `FontStack::to_bytes` writes them. */
    private fun stack(vararg families: List<Int>): ByteArray {
        val words = 3 + families.sumOf { 1 + it.size }
        val out = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.put("CRFS".encodeToByteArray())
        out.putInt(1)
        out.putInt(families.size)
        for (family in families) {
            out.putInt(family.size)
            for (face in family) out.putInt(face)
        }
        return out.array()
    }

    private fun register(id: Int, bytes: ByteArray) =
        Mutation.RegisterAsset(id, AssetKind.Font.ordinal + 1, bytes)

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

    /** The fonts, a theme set in [font], and one node of [kind] saying [text] in a column. */
    private fun screen(
        font: Int,
        kind: WidgetKind,
        text: String,
        vararg props: Pair<PropertyKind, PropertyValue>,
    ) = listOf(
        register(LATIN, latin),
        register(HANGUL, hangul),
        register(STACK, stack(listOf(LATIN), listOf(HANGUL))),
        themeIn(font),
        Mutation.Create(COLUMN, WidgetKind.Column),
        Mutation.Create(NODE, kind),
        Mutation.SetProp(NODE, PropertyKind.Text, PropertyValue.Text(text)),
    ) + props.map { (prop, value) -> Mutation.SetProp(NODE, prop, value) } +
        Mutation.Insert(COLUMN, NODE, 0)

    /** How wide the first line was set, against how wide [letters] glyphs of the tiny face would be. */
    private fun assertInShippedFace(layout: TextLayoutResult, letters: Int, case: String) {
        val size = layout.layoutInput.style.fontSize.value
        val width = layout.getLineRight(0) - layout.getLineLeft(0)
        val expected = letters * size * TinyIconFont.WIDTH_PER_SIZE
        assertTrue(abs(width - expected) < 0.5f, "$case: $width wide, the shipped face gives $expected")
    }

    private fun assertNotInShippedFace(layout: TextLayoutResult, case: String) {
        val size = layout.layoutInput.style.fontSize.value
        val width = layout.getLineRight(0) - layout.getLineLeft(0)
        assertTrue(abs(width - size * TinyIconFont.WIDTH_PER_SIZE) >= 0.5f, "$case: drawn in the tiny face")
    }

    @Test
    fun fr52_text_in_a_stack_takes_hangul_from_the_shipped_korean_face() = runComposeUiTest {
        startHost(FakeHostConnection(screen(STACK, WidgetKind.Text, "A가")), Density(1f))
        assertInShippedFace(drawnLayout(NODE), 2, "Latin and Hangul in a stack")
    }

    @Test
    fun fr52_without_the_korean_face_hangul_comes_from_the_machine() = runComposeUiTest {
        startHost(FakeHostConnection(screen(LATIN, WidgetKind.Text, "가")), Density(1f))
        assertNotInShippedFace(drawnLayout(NODE), "Hangul with only the Latin face")
    }

    @Test
    fun fr52_a_button_label_in_a_stack_uses_both_faces() = runComposeUiTest {
        startHost(FakeHostConnection(screen(STACK, WidgetKind.Button, "A가")), Density(1f))
        assertInShippedFace(drawnLayout(NODE), 2, "button label")
    }

    @Test
    fun fr52_a_chip_label_in_a_stack_uses_both_faces() = runComposeUiTest {
        startHost(FakeHostConnection(screen(STACK, WidgetKind.Chip, "A가")), Density(1f))
        assertInShippedFace(drawnLayout(NODE), 2, "chip label")
    }

    @Test
    fun fr52_a_text_field_in_a_stack_uses_both_faces() = runComposeUiTest {
        startHost(FakeHostConnection(screen(STACK, WidgetKind.TextField, "A가")), Density(1f))
        assertInShippedFace(drawnLayout(NODE), 2, "text field")
    }

    @Test
    fun fr52_a_text_field_with_no_role_uses_its_own_font_list() = runComposeUiTest {
        val fonts = listOf(FontRef.Asset(LATIN), FontRef.Asset(HANGUL), FontRef.Generic(GenericFamily.SansSerif))
        val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
        // The theme names no shipped font, so only the field's own list can explain the width.
        startHost(
            FakeHostConnection(
                screen(0, WidgetKind.TextField, "A가", noRole, PropertyKind.Font to PropertyValue.Bytes(fontBlob(fonts))),
            ),
            Density(1f),
        )
        assertInShippedFace(drawnLayout(NODE), 2, "text field with its own fonts")
    }

    @Test
    fun fr48_missing_glyph_falls_to_next_font() = runComposeUiTest {
        val fonts = listOf(FontRef.Asset(LATIN), FontRef.Asset(HANGUL), FontRef.Generic(GenericFamily.SansSerif))
        val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())
        startHost(
            FakeHostConnection(
                screen(
                    0,
                    WidgetKind.Text,
                    "A가",
                    noRole,
                    PropertyKind.Font to PropertyValue.Bytes(fontBlob(fonts)),
                    PropertyKind.FontSize to PropertyValue.Float(20f),
                ),
            ),
            Density(1f),
        )
        val drawn = drawnLayout(NODE)
        assertInShippedFace(drawn, 2, "the list's second font")
        // Measured as it is drawn, so a Host laying out a page gets the width it will see.
        val (status, results) = measure(
            MeasureRequests().text("A가", role = 0, fontSize = 20f, fonts = fonts, width = 400f),
        )
        assertEquals(MeasureRecords.CALL_OK, status)
        assertSameAsDrawn(results.single(), drawn, 1f, "fallback to the next font")
    }

    @Test
    fun fr52_two_weights_of_one_family_stay_two_faces() = runComposeUiTest {
        // Same family name, different weights and different widths. Registered by family
        // name, the second would have drawn as the first.
        val regular = TinyIconFont.build(family = "Tiny Weights", weight = 400, advance = 1500)
        val bold = TinyIconFont.build(family = "Tiny Weights", weight = 700, advance = 1000)
        val batch = listOf(
            register(LATIN, regular),
            register(HANGUL, bold),
            Mutation.SetTheme(
                Theme(
                    DesignSystem.Material3,
                    DesignSystem.Material3,
                    ColorScheme.Light,
                    adaptive = false,
                    fonts = TypeRole.entries.map { if (it == TypeRole.Display) LATIN else if (it == TypeRole.Body) HANGUL else 0 },
                ),
            ),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.Create(NODE, WidgetKind.Text),
            Mutation.SetProp(NODE, PropertyKind.Text, PropertyValue.Text("A")),
            Mutation.SetProp(NODE, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Display.ordinal + 1L)),
            Mutation.Create(SECOND, WidgetKind.Text),
            Mutation.SetProp(SECOND, PropertyKind.Text, PropertyValue.Text("A")),
            Mutation.SetProp(SECOND, PropertyKind.TypeRole, PropertyValue.Integer(TypeRole.Body.ordinal + 1L)),
            Mutation.Insert(COLUMN, NODE, 0),
            Mutation.Insert(COLUMN, SECOND, 1),
        )
        startHost(FakeHostConnection(batch), Density(1f))
        assertInShippedFace(drawnLayout(NODE), 1, "the regular face")
        val second = drawnLayout(SECOND)
        val size = second.layoutInput.style.fontSize.value
        val width = second.getLineRight(0) - second.getLineLeft(0)
        assertTrue(abs(width - size) < 0.5f, "the bold face is one em wide, drew $width at $size")
    }

    @Test
    fun fr52_a_stack_naming_an_unregistered_face_is_refused() {
        val cache = AssetCache()
        assertNull(cache.register(LATIN, AssetKind.Font.ordinal + 1, latin))
        val error = cache.register(STACK, AssetKind.Font.ordinal + 1, stack(listOf(LATIN), listOf(99)))
        assertNotNull(error)
        assertEquals(TableError.UNREADABLE_ASSET, error.code)
        assertTrue("99" in error.message, error.message)
        assertNull(cache.asset(STACK))
    }

    @Test
    fun fr52_a_stack_is_read_into_a_chain_of_its_families() {
        val cache = AssetCache()
        cache.register(LATIN, AssetKind.Font.ordinal + 1, latin)
        cache.register(HANGUL, AssetKind.Font.ordinal + 1, hangul)
        assertNull(cache.register(STACK, AssetKind.Font.ordinal + 1, stack(listOf(LATIN), listOf(HANGUL))))
        val font = cache.asset(STACK) as Asset.Font
        val chain = assertNotNull(font.chain)
        assertEquals(2, chain.members.size)
        assertTrue(chain.primary === font.family, "the stack's family is its first family")
        // Only the Hangul letter goes to the second family.
        val spans = chain.spans("AA가A")
        assertEquals(listOf(2 to 3), spans.map { it.start to it.end })
    }

    private companion object {
        const val COLUMN = 1
        const val NODE = 2
        const val SECOND = 3
        const val LATIN = 41
        const val HANGUL = 42
        const val STACK = 43
    }
}
