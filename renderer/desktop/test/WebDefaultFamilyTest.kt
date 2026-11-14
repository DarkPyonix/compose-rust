package dev.darkpyonix.composerust.test

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import dev.darkpyonix.composerust.design.BundledFace
import dev.darkpyonix.composerust.design.WEB_FONT_DIRECTORY
import dev.darkpyonix.composerust.design.bundledFamily
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.design.webDefaultFaces
import dev.darkpyonix.composerust.foundation.genericFontFamily
import dev.darkpyonix.composerust.foundation.resolveFontChain
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.ui.node.AssetCache
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.FontSlant

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
        assertEquals(files, webDefaultFaces.map { it.file }.toSet())
        assertTrue(File(directory, "LICENSE").isFile, "the faces' licence travels with them")
        for (face in webDefaultFaces) {
            assertEquals(
                Face(face.weight, face.italic),
                resolved(bundledFamily(listOf(face to File(directory, face.file).readBytes())), face.weight,
                    if (face.italic) FontStyle.Italic else FontStyle.Normal),
                "${face.file} is the weight and slant the table says",
            )
        }
    }

    private data class Face(val weight: Int, val italic: Boolean)

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
}
