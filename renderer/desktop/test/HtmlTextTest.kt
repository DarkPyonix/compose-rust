package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.OverflowWrap
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WordBreak
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.systemFontFamily
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Text laid out by CSS: no rung of the type ladder, its own fonts, CSS line breaking, tab
 * stops, and sizes the system's font scale leaves alone.
 *
 * Every case is checked the way the measure call promises: the same text measured and
 * drawn comes out the same, bit for bit, because both go through one resolution function.
 */
@OptIn(ExperimentalTestApi::class)
class HtmlTextTest {

    /** A Text with no role inside a column [width] wide, with [props] on the text. */
    private fun htmlText(width: Float, text: String, vararg props: Pair<PropertyKind, PropertyValue>) =
        listOf(
            Mutation.RegisterAsset(ICON_FONT, AssetKind.Font.ordinal + 1, TinyIconFont.build()),
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(width)),
            Mutation.Create(TEXT, WidgetKind.Text),
            Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text(text)),
        ) + props.map { (kind, value) -> Mutation.SetProp(TEXT, kind, value) } +
            Mutation.Insert(COLUMN, TEXT, 0)

    private val noRole = PropertyKind.TypeRole to PropertyValue.Integer(TYPE_ROLE_NONE.toLong())

    private fun fonts(refs: List<FontRef>) = PropertyKind.Font to PropertyValue.Bytes(fontBlob(refs))

    /** A family this machine has, by name, or one it surely does not. */
    private val installedName: String =
        listOf("Helvetica", "Arial", "DejaVu Sans", "Noto Sans", "Liberation Sans", "Segoe UI")
            .firstOrNull { systemFontFamily(it) != null } ?: "No Such Family Anywhere"

    private class Case(
        val name: String,
        val text: String,
        val width: Float,
        val props: List<Pair<PropertyKind, PropertyValue>>,
        val request: MeasureRequests.(String, Float) -> MeasureRequests,
    )

    private fun cases(): List<Case> {
        val asset = listOf(FontRef.Asset(ICON_FONT), FontRef.Generic(GenericFamily.SansSerif))
        val system = listOf(FontRef.System(installedName), FontRef.Generic(GenericFamily.Serif))
        val generic = listOf(FontRef.Generic(GenericFamily.Monospace))
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val korean = "다람쥐헌쳇바퀴에 타고파그리고한글 줄바꿈을확인합니다"
        return listOf(
            Case("asset font", "Icon  and words", 120f, listOf(noRole, fonts(asset), PropertyKind.FontSize to PropertyValue.Float(18f))) { text, width ->
                text(text, role = 0, width = width, fontSize = 18f, fonts = asset)
            },
            Case("system font $installedName", "Set in an installed family", 110f, listOf(noRole, fonts(system))) { text, width ->
                text(text, role = 0, width = width, fonts = system)
            },
            Case("generic font", "Set in the generic monospace", 110f, listOf(noRole, fonts(generic))) { text, width ->
                text(text, role = 0, width = width, fonts = generic)
            },
            Case("korean normal", korean, 90f, listOf(noRole, fonts(sans), PropertyKind.WordBreak to PropertyValue.Integer(WordBreak.Normal.ordinal + 1L))) { text, width ->
                text(text, role = 0, width = width, fonts = sans, wordBreak = WordBreak.Normal.ordinal + 1)
            },
            Case("korean keep-all", korean, 90f, listOf(noRole, fonts(sans), PropertyKind.WordBreak to PropertyValue.Integer(WordBreak.KeepAll.ordinal + 1L))) { text, width ->
                text(text, role = 0, width = width, fonts = sans, wordBreak = WordBreak.KeepAll.ordinal + 1)
            },
            Case(
                "pre with tabs",
                "a\tbb\tccc\n\tindented\tand more",
                60f,
                listOf(noRole, fonts(generic), PropertyKind.SoftWrap to PropertyValue.Bool(false), PropertyKind.TabSize to PropertyValue.Integer(4)),
            ) { text, width ->
                text(text, role = 0, width = width, fonts = generic, wrap = false, tabSize = 4)
            },
            Case("absolute size", "Sized in CSS pixels", 100f, listOf(noRole, fonts(sans), PropertyKind.FontSize to PropertyValue.Float(15f), PropertyKind.AbsoluteSize to PropertyValue.Integer(1))) { text, width ->
                text(text, role = 0, width = width, fonts = sans, fontSize = 15f, absoluteSize = true)
            },
            Case("scaled size", "Sized in scaled pixels", 100f, listOf(noRole, fonts(sans), PropertyKind.FontSize to PropertyValue.Float(15f))) { text, width ->
                text(text, role = 0, width = width, fonts = sans, fontSize = 15f)
            },
            Case(
                "anywhere",
                "Supercalifragilisticexpialidocious word",
                60f,
                listOf(noRole, fonts(sans), PropertyKind.OverflowWrap to PropertyValue.Integer(OverflowWrap.Anywhere.ordinal + 1L)),
            ) { text, width ->
                text(text, role = 0, width = width, fonts = sans, overflowWrap = OverflowWrap.Anywhere.ordinal + 1)
            },
        )
    }

    @Test
    fun fr40_measured_html_text_matches_the_drawn_text() {
        // At the system's own text size. A measure request carries the text size through its
        // zoom rather than through a font scale, so text drawn under another font scale is
        // compared in fr43_measure_follows_zoom, at the zoom that matches it.
        for (case in cases()) {
            runComposeUiTest {
                val pinned = Density(1.5f, 1f)
                startHost(FakeHostConnection(htmlText(case.width, case.text, *case.props.toTypedArray())), pinned)
                val (status, results) = measure(case.request(MeasureRequests(), case.text, case.width))
                assertEquals(MeasureRecords.CALL_OK, status, case.name)
                assertSameAsDrawn(results.single(), drawnLayout(TEXT), pinned.density, case.name)
            }
        }
    }

    /** CSS keep-all breaks Korean only at spaces, so its narrowest is a whole word. */
    @Test
    fun fr40_keep_all_keeps_korean_words_whole() = runComposeUiTest {
        startHost(FakeHostConnection(htmlText(200f, "x")), Density(1f))
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val text = "가나다라마바사아 자차카"
        val (_, results) = measure(
            MeasureRequests()
                .text(text, role = 0, fonts = sans, constraint = MeasureRecords.CONSTRAINT_MIN_CONTENT, wordBreak = WordBreak.Normal.ordinal + 1)
                .text(text, role = 0, fonts = sans, constraint = MeasureRecords.CONSTRAINT_MIN_CONTENT, wordBreak = WordBreak.KeepAll.ordinal + 1)
                .text("가", role = 0, fonts = sans, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT),
        )
        val (normal, keepAll, syllable) = results
        assertTrue(abs(normal.width - syllable.width) < 1f, "normal breaks between syllables: ${normal.width} against ${syllable.width}")
        assertTrue(keepAll.width > syllable.width * 6, "keep-all broke inside a word: ${keepAll.width}")
    }

    /** A tab advances to the next stop, which is tab-size spaces of the text's own font. */
    @Test
    fun fr40_a_tab_reaches_the_next_tab_stop() = runComposeUiTest {
        startHost(FakeHostConnection(htmlText(200f, "x")), Density(1f))
        val mono = listOf(FontRef.Generic(GenericFamily.Monospace))
        val (_, results) = measure(
            MeasureRequests()
                .text("a\tb", role = 0, fonts = mono, wrap = false, tabSize = 4, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT)
                .text("aaaab", role = 0, fonts = mono, wrap = false, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT)
                .text("abcd\tb", role = 0, fonts = mono, wrap = false, tabSize = 4, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT)
                .text("abcdaaaab", role = 0, fonts = mono, wrap = false, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT),
        )
        // Within a pixel: each width is a whole number of pixels, rounded up from the
        // paragraph's own, and a tab's advance is not rounded the way a glyph's is.
        assertTrue(abs(results[0].width - results[1].width) <= 1f, "a tab after one column reaches column four: ${results[0].width} against ${results[1].width}")
        assertTrue(abs(results[2].width - results[3].width) <= 1f, "a tab at a stop goes on to the next one: ${results[2].width} against ${results[3].width}")
    }

    /** A registered icon font's private use glyph is drawn in that font, at its own width. */
    @Test
    fun fr40_an_icon_font_glyph_is_drawn_in_its_font() = runComposeUiTest {
        val icon = "\uEA60"
        startHost(
            FakeHostConnection(
                htmlText(200f, icon, noRole, fonts(listOf(FontRef.Asset(ICON_FONT))), PropertyKind.FontSize to PropertyValue.Float(20f)),
            ),
            Density(1f),
        )
        val drawn = drawnLayout(TEXT)
        // The glyph is one and a half em wide, which no fallback face makes it, so at 20 it
        // is 30 wide only if it was drawn in the registered font.
        assertEquals(GLYPH_AT_20, drawn.size.width.toFloat(), "the glyph was not drawn in the registered font")
        assertTrue(drawn.size.height > 0)
        val (_, results) = measure(
            MeasureRequests().text(
                icon,
                role = 0,
                fontSize = 20f,
                fonts = listOf(FontRef.Asset(ICON_FONT)),
                constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT,
            ),
        )
        assertEquals(GLYPH_AT_20, results.single().width, "measured in the registered font")
    }

    /** Two Texts side by side: one in CSS pixels with no role, one in the Body rung. */
    private fun absoluteAndRole(sans: List<FontRef>) = listOf(
        Mutation.Create(COLUMN, WidgetKind.Column),
        Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(400f)),
        Mutation.Create(TEXT, WidgetKind.Text),
        Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text("Absolute")),
        noRole.let { (kind, value) -> Mutation.SetProp(TEXT, kind, value) },
        Mutation.SetProp(TEXT, PropertyKind.Font, PropertyValue.Bytes(fontBlob(sans))),
        Mutation.SetProp(TEXT, PropertyKind.FontSize, PropertyValue.Float(16f)),
        Mutation.SetProp(TEXT, PropertyKind.AbsoluteSize, PropertyValue.Integer(1)),
        Mutation.Create(ROLE_TEXT, WidgetKind.Text),
        Mutation.SetProp(ROLE_TEXT, PropertyKind.Text, PropertyValue.Text("Absolute")),
        Mutation.SetProp(ROLE_TEXT, PropertyKind.TypeRole, PropertyValue.Integer(5)),
        Mutation.Insert(COLUMN, TEXT, 0),
        Mutation.Insert(COLUMN, ROLE_TEXT, 1),
    )

    /**
     * At twice the system font scale, text in CSS pixels stays the size it was, measured and
     * drawn, and text with a role grows.
     */
    @Test
    fun fr40_absolute_size_ignores_the_system_font_scale() {
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val answers = listOf(1f, 2f).map { fontScale ->
            var answer: Triple<MeasuredResult, Int, Int>? = null
            runComposeUiTest {
                startHost(FakeHostConnection(absoluteAndRole(sans)), Density(1f, fontScale))
                val drawn = drawnLayout(TEXT)
                val (_, results) = measure(
                    MeasureRequests().text("Absolute", role = 0, fonts = sans, fontSize = 16f, absoluteSize = true, width = 400f),
                )
                assertSameAsDrawn(results.single(), drawn, 1f, "absolute at $fontScale")
                answer = Triple(results.single(), drawn.size.width, drawnLayout(ROLE_TEXT).size.width)
            }
            answer!!
        }
        val (normal, doubled) = answers
        assertEquals(normal.first.width, doubled.first.width, "absolute text grew with the font scale")
        assertEquals(normal.first.height, doubled.first.height)
        assertEquals(normal.second, doubled.second, "absolute text was drawn larger")
        assertTrue(doubled.third > normal.third * 3 / 2, "text with a role did not grow with the font scale")
    }

    /**
     * A request's zoom is the zoom of the region it belongs to: measured at the
     * composition's density times the zoom, with no font scale, it is the size the same
     * text drawn at that density is, in the region's CSS pixels.
     */
    @Test
    fun fr43_measure_follows_zoom() {
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val text = "Zoomed text that wraps across a few lines here"
        for (zoom in listOf(1.25f, 1.5f, 2f)) {
            var drawn: TextLayoutResult? = null
            runComposeUiTest {
                // Drawn where the region would be: at the zoomed density, font scale one.
                startHost(
                    FakeHostConnection(htmlText(120f, text, noRole, fonts(sans), PropertyKind.FontSize to PropertyValue.Float(15f))),
                    Density(zoom, 1f),
                )
                drawn = drawnLayout(TEXT)
            }
            runComposeUiTest {
                // Measured from a composition at density one, through the request's zoom.
                startHost(FakeHostConnection(htmlText(120f, "x")), Density(1f, 1f))
                val (status, results) = measure(
                    MeasureRequests().text(text, role = 0, fonts = sans, fontSize = 15f, width = 120f, zoom = zoom),
                )
                assertEquals(MeasureRecords.CALL_OK, status)
                assertSameAsDrawn(results.single(), drawn!!, zoom, "at zoom $zoom")
            }
        }
    }

    /**
     * Text in CSS pixels has no font scale whatever the composition's, and changes size only
     * with the zoom.
     */
    @Test
    fun fr43_absolute_size_ignores_font_scale() {
        val sans = listOf(FontRef.Generic(GenericFamily.SansSerif))
        val widths = listOf(1f, 3f).map { fontScale ->
            var pixels = 0f to 0f
            runComposeUiTest {
                startHost(FakeHostConnection(htmlText(400f, "x")), Density(1f, fontScale))
                val (_, results) = measure(
                    MeasureRequests()
                        .text("Absolute words", role = 0, fonts = sans, fontSize = 16f, absoluteSize = true, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT)
                        .text("Absolute words", role = 0, fonts = sans, fontSize = 16f, absoluteSize = true, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, zoom = 2f),
                )
                // In device pixels: CSS pixels times the density they were measured at.
                pixels = results[0].width to results[1].width * 2f
            }
            pixels
        }
        assertEquals(widths[0], widths[1], "the composition's font scale reached text in CSS pixels")
        val (atOne, atTwo) = widths[0]
        assertTrue(abs(atTwo - 2 * atOne) <= 2f, "zoom two did not double the size: $atOne and $atTwo")
    }

    /** A font sent for text that has a role is ignored, and the theme's font is used. */
    @Test
    fun fr40_a_font_on_text_with_a_role_is_ignored() = runComposeUiTest {
        // The icon font has a glyph for `A` too, one and a half em wide, so `A` set in it
        // is unmistakable.
        val letter = "A"
        startHost(
            FakeHostConnection(
                htmlText(
                    200f,
                    letter,
                    PropertyKind.TypeRole to PropertyValue.Integer(5),
                    fonts(listOf(FontRef.Asset(ICON_FONT))),
                    PropertyKind.FontSize to PropertyValue.Float(20f),
                ),
            ),
            Density(1f),
        )
        val drawn = drawnLayout(TEXT)
        val (_, results) = measure(
            MeasureRequests()
                .text(letter, role = 5, fontSize = 20f, fonts = listOf(FontRef.Asset(ICON_FONT)), width = 200f)
                .text(letter, role = 5, fontSize = 20f, width = 200f)
                .text(letter, role = 0, fontSize = 20f, fonts = listOf(FontRef.Asset(ICON_FONT)), width = 200f),
        )
        assertSameAsDrawn(results[1], drawn, 1f, "the role's font")
        assertEquals(results[1].width, results[0].width, "the font was not ignored when measuring")
        assertNotEquals(GLYPH_AT_20, drawn.size.width.toFloat(), "drawn in the font a role should have ignored")
        assertEquals(GLYPH_AT_20, results[2].width, "without a role the same font is used")
    }

    private companion object {
        const val COLUMN = 1
        const val TEXT = 2
        const val ROLE_TEXT = 3
        const val ICON_FONT = 31
        const val GLYPH_AT_20 = 20f * TinyIconFont.WIDTH_PER_SIZE
    }
}
