package dev.darkpyonix.composerust.test

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import dev.darkpyonix.composerust.design.BundledFace
import dev.darkpyonix.composerust.design.WEB_FONT_DIRECTORY
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.bundledChain
import dev.darkpyonix.composerust.design.bundledFallback
import dev.darkpyonix.composerust.design.bundledFamily
import dev.darkpyonix.composerust.design.platformDefaultChain
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.design.webDefaultFaces
import dev.darkpyonix.composerust.design.webFallbackFaces
import dev.darkpyonix.composerust.foundation.genericFontFamily
import dev.darkpyonix.composerust.foundation.resolveFontChain
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.FontChain
import dev.darkpyonix.composerust.ui.node.platformFallbackFamilies
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.Typeface

/**
 * The default family the web bundle carries, built from the files the bundle serves and
 * resolved by Compose's own font matching.
 *
 * A browser gives a canvas none of the machine's fonts, and Compose's web build does not
 * synthesize a weight a family lacks, so text in bold drew in the one regular face the page
 * had. What the web renderer does with these files is fetch them and hand the bytes to
 * [bundledFamily], which is the code under test here, on the same Skia the web build uses.
 */
class WebDefaultFamilyTest {
    private val resolver = createFontFamilyResolver()

    @Test
    fun fr13_web_bold_draws_bold() {
        val family = bundledFamily(faces())
        assertEquals(Face(700, italic = false), resolved(family, 700), "weight 700 is the bold face")
        assertEquals(Face(700, italic = true), resolved(family, 700, FontStyle.Italic), "bold italic")
        // CSS's rule for a weight between faces: above 500 the next heavier face, then lighter.
        assertEquals(Face(700, italic = false), resolved(family, 600), "600 takes the bold face")
        assertEquals(Face(700, italic = false), resolved(family, 900), "900 takes the heaviest there is")
    }

    @Test
    fun fr13_web_default_family_resolves_regular_and_bold() {
        val family = bundledFamily(faces())
        assertEquals(Face(400, italic = false), resolved(family, 400))
        assertEquals(Face(700, italic = false), resolved(family, 700))
        assertEquals(Face(400, italic = true), resolved(family, 400, FontStyle.Italic))
        assertEquals(Face(500, italic = false), resolved(family, 500), "the ladders' label weight")
        assertEquals(Face(400, italic = false), resolved(family, 100), "a light weight takes the regular")
    }

    /**
     * Once the web renderer has set the family, `system-ui`, `sans-serif`, a role's face and
     * text that names no font all resolve to it, so none of them is left with one face.
     */
    @Test
    fun fr13_web_generic_families_follow_the_bundled_family() {
        val family = bundledFamily(faces())
        val ui = platformUiFamily
        val sans = platformSansSerifFamily
        try {
            platformUiFamily = family
            platformSansSerifFamily = family
            assertSame(family, genericFontFamily(GenericFamily.SystemUi))
            assertSame(family, genericFontFamily(GenericFamily.SansSerif))
            val chain = resolveFontChain(
                listOf(FontRef.Generic(GenericFamily.SystemUi), FontRef.Generic(GenericFamily.SansSerif)),
                AssetCache(),
                mutableListOf(),
            )
            assertSame(family, chain.primary, "the page's `system-ui, sans-serif`")
            assertSame(family, resolveFontChain(emptyList(), AssetCache(), mutableListOf()).primary)
        } finally {
            platformUiFamily = ui
            platformSansSerifFamily = sans
        }
    }

    /** The table names every face the bundle carries, and only those. */
    @Test
    fun fr13_web_the_face_table_matches_the_files() {
        val directory = fontDirectory()
        val files = directory.listFiles().orEmpty().filter { it.name.endsWith(".ttf") }.map { it.name }.toSet()
        assertEquals(files, (webDefaultFaces.map { it.file } + webFallbackFaces).toSet())
        assertTrue(File(directory, "LICENSE").isFile, "the faces' licence travels with them")
        assertTrue(File(directory, "OFL.txt").isFile, "the fallback faces' licence travels with them")
        for (face in webDefaultFaces) {
            assertEquals(
                Face(face.weight, face.italic),
                resolved(bundledFamily(listOf(face to File(directory, face.file).readBytes())), face.weight,
                    if (face.italic) FontStyle.Italic else FontStyle.Normal),
                "${face.file} is the weight and slant the table says",
            )
        }
    }

    /**
     * The three signs the samples write that Roboto has no glyph for: erase to the left, the
     * small down triangle and the play triangle. In the default family's chain each is one
     * span in the fallback face, and the face Compose resolves for that span has the glyph.
     */
    @Test
    fun fr13_web_symbols_the_default_family_lacks_draw_from_the_fallback_face() {
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()), "the regular face says which letters it has")
        assertSame(family, chain.primary)
        val symbols = fallbackMembers()
        val primary = typeface(family, 400)
        withPlatformFonts(family, chain, symbols) {
            for (sign in SIGNS) {
                val name = "U+" + sign.toString(16).uppercase()
                assertEquals(0.toShort(), primary.getUTF32Glyph(sign), "$name is not in the default family")
                val spans = chain.spans("M" + Char(sign) + "M")
                assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "$name alone leaves the default family")
                val drawnIn = spans.single().item.fontFamily
                assertSame(symbols.single().family, drawnIn, "$name goes to the fallback face")
                assertTrue(typeface(drawnIn!!, 400).getUTF32Glyph(sign) != 0.toShort(), "$name has a glyph there")
                // Compose's web build draws no weight a family lacks, so a sign in bold text
                // is the one face the fallback has, and still a glyph.
                assertTrue(typeface(drawnIn, 700).getUTF32Glyph(sign) != 0.toShort(), "$name in bold text")
            }
        }
    }

    /** Text the default family has every letter of is set in it whole, with no span. */
    @Test
    fun fr13_web_latin_text_stays_in_the_default_family() {
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()))
        val primary = typeface(family, 400)
        withPlatformFonts(family, chain, fallbackMembers()) {
            assertTrue(primary.getUTF32Glyph('A'.code) != 0.toShort(), "the default family has a Latin letter")
            assertTrue(chain.spans("Play 1 \u2212 2 \u00d7 3").isEmpty(), "Latin, digits and the signs Roboto has")
            // A letter nothing here has stays in the default family, as it was before.
            assertTrue(chain.spans("\uAC00").isEmpty(), "a Hangul syllable no bundled face has")
        }
    }

    /**
     * Every way text arrives at the default family arrives at its chain: a role's face, a
     * font list ending in `system-ui` or `sans-serif`, the interface face by its CSS name,
     * and text that names no font at all.
     */
    @Test
    fun fr13_web_default_text_reaches_the_fallback_face() {
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()))
        withPlatformFonts(family, chain, fallbackMembers()) {
            val theme = resolveTheme(theme = null, platform = HostPlatform.Unknown, systemDark = false)
            assertSame(family, theme.family(TypeRole.Body))
            assertSame(chain, theme.chainFor(theme.family(TypeRole.Body)), "a role set in the default family")
            val lists: List<List<FontRef>> = listOf(
                emptyList(),
                listOf(FontRef.Generic(GenericFamily.SystemUi)),
                listOf(FontRef.Generic(GenericFamily.SansSerif)),
                listOf(FontRef.System("ui-sans-serif")),
            )
            for (list in lists) {
                val resolved = resolveFontChain(list, AssetCache(), mutableListOf())
                assertSame(family, resolved.primary, "$list")
                for (sign in SIGNS) {
                    val spans = resolved.spans("M" + Char(sign))
                    assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "$list, U+${sign.toString(16)}")
                }
            }
        }
    }

    /**
     * A face the application registered is asked for its letters first, and a sign it lacks
     * goes to the fallback face too, in a font list and in a role.
     */
    @Test
    fun fr13_web_an_application_face_reaches_the_fallback_face() {
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()))
        val symbols = fallbackMembers()
        val cache = AssetCache()
        val roboto = File(fontDirectory(), "Roboto-Regular.ttf").readBytes()
        assertNull(cache.register(APPLICATION_FACE, AssetKind.Font.ordinal + 1, roboto))
        val font = cache.asset(APPLICATION_FACE) as Asset.Font
        withPlatformFonts(family, chain, symbols) {
            val listed = resolveFontChain(listOf(FontRef.Asset(APPLICATION_FACE)), cache, mutableListOf())
            assertSame(font.family, listed.primary)
            val spans = listed.spans("Play \u25B6")
            assertEquals(listOf(5 to 6), spans.map { it.start to it.end })
            assertSame(symbols.single().family, spans.single().item.fontFamily)
            val letters = assertNotNull(font.letters, "one font file knows its letters")
            assertSame(font.family, letters.primary)
            assertSame(letters, font.letters, "the same chain each time it is asked")
            assertEquals(listOf(5 to 6), letters.spans("Play \u25B6").map { it.start to it.end })
        }
    }

    /**
     * Monospace, serif, CSS `ui-monospace` / `ui-serif`, a monospace type role, and named
     * installed families all reach the bundled fallback face for symbols the default family
     * lacks, while keeping Latin and digits in their own primary family.
     */
    @Test
    fun fr13_web_monospace_serif_and_named_families_reach_the_fallback_face() {
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()))
        val symbols = fallbackMembers()
        withPlatformFonts(family, chain, symbols) {
            val theme = resolveTheme(theme = null, platform = HostPlatform.Unknown, systemDark = false)
            val codeFamily = theme.family(TypeRole.Mono)
            assertSame(FontFamily.Monospace, codeFamily, "a monospace role uses the monospace family")
            val codeChain = assertNotNull(theme.chainFor(codeFamily), "a monospace role has a fallback chain")
            assertSame(FontFamily.Monospace, codeChain.primary)
            assertTrue(codeChain.spans("Play 123").isEmpty(), "Latin and digits stay in monospace")
            for (sign in SIGNS) {
                val spans = codeChain.spans("M" + Char(sign) + "M")
                assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "Code role, U+${sign.toString(16)}")
                assertSame(symbols.single().family, spans.single().item.fontFamily)
            }

            val lists = mutableListOf<Pair<String, List<FontRef>>>(
                "monospace" to listOf(FontRef.Generic(GenericFamily.Monospace)),
                "serif" to listOf(FontRef.Generic(GenericFamily.Serif)),
                "ui-monospace" to listOf(FontRef.System("ui-monospace")),
                "ui-serif" to listOf(FontRef.System("ui-serif")),
            )
            val installedName = (0 until FontMgr.default.familiesCount)
                .asSequence()
                .map { FontMgr.default.getFamilyName(it) }
                .firstOrNull { it.isNotBlank() && dev.darkpyonix.composerust.ui.node.systemFontFamily(it) != null }
            if (installedName != null) {
                lists += "named($installedName)" to listOf(FontRef.System(installedName))
            }

            for ((label, list) in lists) {
                val resolved = resolveFontChain(list, AssetCache(), mutableListOf())
                assertTrue(resolved.spans("Play 123").isEmpty(), "$label keeps Latin and digits in primary")
                for (sign in SIGNS) {
                    val spans = resolved.spans("M" + Char(sign) + "M")
                    assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "$label, U+${sign.toString(16)}")
                    val drawnIn = assertNotNull(spans.single().item.fontFamily)
                    assertSame(symbols.single().family, drawnIn, "$label routes U+${sign.toString(16)} to fallback")
                    assertTrue(typeface(drawnIn, 400).getUTF32Glyph(sign) != 0.toShort())
                }
            }
        }
    }

    /**
     * Where the renderer carries no fallback faces, which is every target with fonts of its
     * own, nothing is taken from the first family: the machine finds the letter.
     */
    @Test
    fun fr13_web_no_fallback_face_leaves_text_to_the_platform() {
        assertTrue(platformFallbackFamilies.isEmpty(), "the desktop carries no fallback faces")
        assertNull(platformDefaultChain, "the desktop's default family is the machine's")
        val family = bundledFamily(faces())
        val chain = assertNotNull(bundledChain(family, faces()))
        assertTrue(chain.spans("M\u25BE").isEmpty())
        val theme = resolveTheme(theme = null, platform = HostPlatform.Unknown, systemDark = false)
        assertNull(theme.chainFor(theme.family(TypeRole.Body)))
        assertNull(theme.chainFor(theme.family(TypeRole.Mono)))
        assertTrue(
            resolveFontChain(listOf(FontRef.Generic(GenericFamily.Monospace)), AssetCache(), mutableListOf())
                .spans("M\u25BE")
                .isEmpty(),
        )
    }

    private data class Face(val weight: Int, val italic: Boolean)

    private fun typeface(family: FontFamily, weight: Int): Typeface {
        val result = resolver.resolve(family, FontWeight(weight)).value as FontLoadResult
        return result.typeface!!
    }

    private fun fallbackMembers(): List<FontChain.Member> {
        val directory = fontDirectory()
        return webFallbackFaces.map { file ->
            assertNotNull(bundledFallback(File(directory, file).readBytes()), "$file is a font")
        }
    }

    /** Runs [block] with the globals the web renderer sets before its first frame. */
    private fun withPlatformFonts(
        family: FontFamily,
        chain: FontChain,
        fallback: List<FontChain.Member>,
        block: () -> Unit,
    ) {
        val ui = platformUiFamily
        val sans = platformSansSerifFamily
        val defaultChain = platformDefaultChain
        val fallbackFamilies = platformFallbackFamilies
        try {
            platformUiFamily = family
            platformSansSerifFamily = family
            platformDefaultChain = chain
            platformFallbackFamilies = fallback
            block()
        } finally {
            platformUiFamily = ui
            platformSansSerifFamily = sans
            platformDefaultChain = defaultChain
            platformFallbackFamilies = fallbackFamilies
        }
    }

    private fun resolved(family: FontFamily, weight: Int, style: FontStyle = FontStyle.Normal): Face {
        val result = resolver.resolve(family, FontWeight(weight), style).value as FontLoadResult
        val declared = result.typeface!!.fontStyle
        return Face(declared.weight, declared.slant != FontSlant.UPRIGHT)
    }

    private fun faces(): List<Pair<BundledFace, ByteArray>> {
        val directory = fontDirectory()
        return webDefaultFaces.map { it to File(directory, it.file).readBytes() }
    }

    private fun fontDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "renderer/web/resources/$WEB_FONT_DIRECTORY")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        error("renderer/web/resources/$WEB_FONT_DIRECTORY not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** Erase to the left, the small down triangle, the play triangle. */
        val SIGNS = listOf(0x232B, 0x25BE, 0x25B6)
        const val APPLICATION_FACE = 41
    }
}
