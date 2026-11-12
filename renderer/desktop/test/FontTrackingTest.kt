package dev.darkpyonix.composerust.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import dev.darkpyonix.composerust.design.installPlatformUiFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.MeasureRecords
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TYPE_ROLE_NONE
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.tooling.FakeHostConnection
import dev.darkpyonix.composerust.ui.node.FontTracking
import dev.darkpyonix.composerust.ui.node.TrackTable
import dev.darkpyonix.composerust.ui.node.fontTracking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Text with no role is set with the `trak` tracking of the face it is in, the way Apple's
 * text system and every browser on a Mac set it.
 *
 * The table is read from the face itself, so the cases that need a tracked face run only
 * where the platform's interface face has one (SF on macOS). Elsewhere no face is tracked,
 * and those cases check that nothing changed instead.
 */
@OptIn(ExperimentalTestApi::class)
class FontTrackingTest {

    /**
     * A `trak` table built by hand: a loose track (-1) and the normal track (0), at 10 and
     * 20 points. The normal track is 20 font units at 10 points and -40 at 20.
     */
    private fun table(
        version: Int = 0x00010000,
        normalTrack: Int = 0,
        sizes: IntArray = intArrayOf(10 shl 16, 20 shl 16),
        normal: ShortArray = shortArrayOf(20, -40),
    ): ByteArray {
        val out = java.nio.ByteBuffer.allocate(52)
        out.putInt(version).putShort(0).putShort(12).putShort(0).putShort(0)
        // Track data: two tracks, two sizes, the size table at 36.
        out.putShort(2).putShort(2).putInt(36)
        out.putInt(-1 shl 16).putShort(256).putShort(44)
        out.putInt(normalTrack).putShort(257).putShort(48)
        sizes.forEach { out.putInt(it) }
        out.putShort(50).putShort(30)
        normal.forEach { out.putShort(it) }
        return out.array()
    }

    @Test
    fun fr40_trak_normal_track_is_read_and_interpolated() {
        val track = assertNotNull(TrackTable.parse(table(), 1000))
        assertEquals(0.02f, track.em(10f), "the value at a size the table lists")
        assertEquals(-0.04f, track.em(20f))
        assertEquals(-0.01f, track.em(15f), 1e-6f, "halfway between two sizes is halfway between their values")
        assertEquals(0.005f, track.em(12.5f), 1e-6f)
        assertEquals(0.02f, track.em(4f), "below the first size the first value holds, as CoreText has it")
        assertEquals(-0.04f, track.em(64f), "above the last size the last value holds")
    }

    @Test
    fun fr40_a_trak_table_this_cannot_read_is_not_used() {
        val good = table()
        assertNull(TrackTable.parse(good.copyOf(40), 1000), "a table cut short")
        assertNull(TrackTable.parse(table(version = 0x00020000), 1000), "a version this does not read")
        assertNull(TrackTable.parse(table(normalTrack = 1 shl 16), 1000), "no normal track")
        assertNull(TrackTable.parse(table(normal = shortArrayOf(0, 0)), 1000), "a normal track that changes nothing")
        assertNull(TrackTable.parse(table(sizes = intArrayOf(20 shl 16, 10 shl 16)), 1000), "sizes out of order")
        assertNull(TrackTable.parse(good, 0), "no units per em")
        assertNull(TrackTable.parse(ByteArray(0), 1000))
    }

    /** The tracking of the platform's interface face, or null where it has none. */
    private fun systemTracking(): FontTracking? {
        installPlatformUiFamily()
        return fontTracking(platformUiFamily, 400, false)
    }

    /**
     * SF's own table, read through the font manager: -40 font units at 16 points out of 2048
     * per em, which is the 0.31px per letter Apple's guidelines give for SF at 16pt.
     */
    @Test
    fun fr40_the_platform_face_tracking_is_its_trak_table() {
        val tracking = systemTracking() ?: return
        assertEquals(-40f / 2048f, tracking.table.em(16f), "SF at 16 points")
        assertEquals(0f, tracking.table.em(12f), "SF at 12 points, where the table is zero")
        assertEquals(-50f / 2048f, tracking.table.em(18f), 1e-7f, "between 17 (-52) and 20 (-46)")
    }

    private fun screen() = FakeHostConnection(
        listOf(
            Mutation.Create(COLUMN, WidgetKind.Column),
            Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(400f)),
            Mutation.Create(TEXT, WidgetKind.Text),
            Mutation.SetProp(TEXT, PropertyKind.Text, PropertyValue.Text(LINE)),
            Mutation.SetProp(TEXT, PropertyKind.TypeRole, PropertyValue.Integer(TYPE_ROLE_NONE.toLong())),
            Mutation.SetProp(TEXT, PropertyKind.Font, PropertyValue.Bytes(fontBlob(SYSTEM_UI))),
            Mutation.SetProp(TEXT, PropertyKind.FontSize, PropertyValue.Float(16f)),
            Mutation.SetProp(TEXT, PropertyKind.LetterSpacing, PropertyValue.Float(0f)),
            Mutation.SetProp(TEXT, PropertyKind.AbsoluteSize, PropertyValue.Integer(1)),
            Mutation.Insert(COLUMN, TEXT, 0),
        ),
    )

    private fun MeasureRequests.line(text: String = LINE, fontSize: Float = 16f, letterSpacing: Float = 0f) =
        text(
            text,
            role = 0,
            constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            fonts = SYSTEM_UI,
            absoluteSize = true,
        )

    /**
     * The line from the report, at 16px in `system-ui`, is as wide as CoreText sets it
     * (355.77px, measured with `CTFontGetAdvancesForGlyphs` on macOS 26) rather than as wide
     * as the untracked advances (370.77px), which is what it measured before. And it is drawn
     * at the width it is measured at.
     */
    @Test
    fun fr40_system_ui_text_is_as_wide_as_core_text_sets_it() = runComposeUiTest {
        val tracking = systemTracking()
        startHost(screen(), Density(1f))
        val (status, results) = measure(
            MeasureRequests()
                .line()
                .line(letterSpacing = Float.NaN)
                .text(LINE, role = 0, fontSize = 16f, letterSpacing = 0f, fonts = SYSTEM_UI, absoluteSize = true, width = 400f),
        )
        assertEquals(MeasureRecords.CALL_OK, status)
        val (zero, unset, atMost) = results
        assertEquals(zero.width, unset.width, "an explicit zero letter spacing is tracked the same as none")
        assertSameAsDrawn(atMost, drawnLayout(TEXT), 1f, "tracked system-ui text")
        if (tracking == null) return@runComposeUiTest
        // The same line with the tracking taken back out by letter spacing: what it measured
        // before the face was tracked, the sum of SF's own advances.
        val untracked = measure(MeasureRequests().line(letterSpacing = -tracking.table.em(16f) * 16f)).second.single()
        println("system-ui at 16px: ${zero.width}px tracked, ${untracked.width}px untracked")
        assertTrue(abs(untracked.width - UNTRACKED_WIDTH) <= 1f, "SF's advances sum to $UNTRACKED_WIDTH px, measured ${untracked.width}")
        assertTrue(abs(zero.width - CORE_TEXT_WIDTH) <= 1f, "CoreText sets the line at $CORE_TEXT_WIDTH px, measured ${zero.width}")
    }

    /** CSS letter spacing is added to the tracked advances, not put in place of them. */
    @Test
    fun fr40_css_letter_spacing_adds_to_the_tracking() = runComposeUiTest {
        startHost(screen(), Density(1f))
        val (_, results) = measure(MeasureRequests().line().line(letterSpacing = 1f))
        val (zero, one) = results
        val letters = LINE.length
        assertTrue(
            abs(one.width - zero.width - letters) <= 0.5f,
            "one pixel of letter spacing on $letters letters made ${one.width - zero.width}px",
        )
    }

    /**
     * A letter SF has no glyph for (Hangul) is drawn from a fallback face, which SF's table
     * does not track. Set untracked, its width is in proportion to its size; SF's tracking
     * at 16px would take 0.31px off every letter, 6px over these twenty, and none at 12px.
     */
    @Test
    fun fr40_letters_the_tracked_face_lacks_are_not_tracked() = runComposeUiTest {
        val tracking = systemTracking() ?: return@runComposeUiTest
        val hangul = "가나다라마바사아자차카타파하가나다라마바"
        assertTrue(hangul.all { !tracking.has(it.code) }, "SF has no Hangul, so it falls back")
        startHost(screen(), Density(1f))
        val (_, results) = measure(MeasureRequests().line(hangul, 16f).line(hangul, 12f))
        val (at16, at12) = results
        val expected = at12.width * 16f / 12f
        assertTrue(
            abs(at16.width - expected) <= 2f,
            "Hangul at 16px is ${at16.width}px, in proportion to 12px it would be $expected px",
        )
    }

    /**
     * A run set in fonts of its own is tracked by its own face, or not at all where that face
     * has none: a monospace run inside `system-ui` text is as wide as the same letters in
     * monospace text.
     */
    @Test
    fun fr40_a_run_in_its_own_font_is_tracked_by_that_font() = runComposeUiTest {
        val tracking = systemTracking()
        startHost(screen(), Density(1f))
        val text = "monospaced letters inside"
        val mono = listOf(FontRef.Generic(GenericFamily.Monospace))
        val run = spanRecord(0, text.length)
        val (_, results) = measure(
            MeasureRequests()
                .text(text, role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = 16f, letterSpacing = 0f, fonts = SYSTEM_UI, absoluteSize = true, spans = run, spanFonts = mapOf(0 to mono))
                .text(text, role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = 16f, letterSpacing = 0f, fonts = mono, absoluteSize = true),
        )
        val (inRun, alone) = results
        assertEquals(alone.width, inRun.width, "tracked face: ${tracking != null}")
    }

    /**
     * A run with a role is set at the role's size, and tracked at that size: its width does
     * not depend on the size of the text around it, which SF's table tracks differently at
     * 12 and 16 points.
     */
    @Test
    fun fr40_a_run_with_a_role_is_tracked_at_the_role_size() = runComposeUiTest {
        startHost(screen(), Density(1f))
        val run = spanRecord(0, LINE.length, role = 9)
        val (_, results) = measure(
            MeasureRequests()
                .text(LINE, role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = 16f, letterSpacing = 0f, fonts = SYSTEM_UI, absoluteSize = true, spans = run)
                .text(LINE, role = 0, constraint = MeasureRecords.CONSTRAINT_MAX_CONTENT, fontSize = 12f, letterSpacing = 0f, fonts = SYSTEM_UI, absoluteSize = true, spans = run),
        )
        val (in16, in12) = results
        assertEquals(in12.width, in16.width, "a run at the role's size inside 16px and 12px text")
    }

    /**
     * A field with no role that names its letter spacing is tracked as text with no role
     * is, so a value typed into it is as wide as the same words drawn beside it.
     */
    @Test
    fun fr40_a_field_with_no_role_is_tracked_as_its_text_is() = runComposeUiTest {
        systemTracking()
        fun node(id: Int, kind: WidgetKind) = listOf(
            Mutation.Create(id, kind),
            Mutation.SetProp(id, PropertyKind.Text, PropertyValue.Text(LINE)),
            Mutation.SetProp(id, PropertyKind.TypeRole, PropertyValue.Integer(TYPE_ROLE_NONE.toLong())),
            Mutation.SetProp(id, PropertyKind.Font, PropertyValue.Bytes(fontBlob(SYSTEM_UI))),
            Mutation.SetProp(id, PropertyKind.FontSize, PropertyValue.Float(16f)),
            Mutation.SetProp(id, PropertyKind.FontWeight, PropertyValue.Integer(400)),
            // What a Host sends for a letter spacing of zero, which a node reads as unset.
            Mutation.SetProp(id, PropertyKind.LetterSpacing, PropertyValue.Float(SMALLEST_SPACING)),
            Mutation.Insert(COLUMN, id, id - TEXT),
        )
        startHost(
            FakeHostConnection(
                listOf(Mutation.Create(COLUMN, WidgetKind.Column), Mutation.SetModifier(COLUMN, 0, ProtocolModifier.Width(500f))) +
                    node(TEXT, WidgetKind.Text) + node(FIELD, WidgetKind.TextField),
            ),
            Density(1f),
        )
        fun lineWidth(id: Int) = drawnLayout(id).let { it.getLineRight(0) - it.getLineLeft(0) }
        assertEquals(lineWidth(TEXT), lineWidth(FIELD), 0.01f, "the field's value against the same text")
    }

    private companion object {
        const val COLUMN = 1
        const val TEXT = 2
        const val FIELD = 3
        /** The smallest normal float, which dioxus-compose sends for a letter spacing of zero. */
        const val SMALLEST_SPACING = 1.17549435E-38f
        const val LINE = "This page is HTML and CSS, laid out by the Host."
        const val CORE_TEXT_WIDTH = 355.77f
        const val UNTRACKED_WIDTH = 370.77f
        val SYSTEM_UI = listOf(FontRef.Generic(GenericFamily.SystemUi))
    }
}
