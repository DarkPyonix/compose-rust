package dev.darkpyonix.composerust.test

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import dev.darkpyonix.composerust.design.BundledFace
import dev.darkpyonix.composerust.design.bundledFamily
import dev.darkpyonix.composerust.protocol.AssetKind
import dev.darkpyonix.composerust.ui.node.Asset
import dev.darkpyonix.composerust.ui.node.AssetCache
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Font
import org.jetbrains.skia.Typeface

/**
 * A font file the application registers is held in memory once.
 *
 * The renderer copies the file into Skia when it is registered, and every typeface Compose
 * makes for the face is made from that copy. A font made from the byte array instead keeps
 * the array alive as long as the face is registered, and Skia makes its own copy beside it
 * when Compose loads the face: a measured macOS process held 7.1 MB of font files in the
 * Kotlin heap and 6.4 MB of the same files again in Skia.
 *
 * Compose lets a typeface go from its own cache a minute after it was last asked for, and
 * loads the face again from the font when it is next needed. So a font that dropped its
 * bytes after the first load would fail later, and the tests here load each face from a
 * resolver of its own after the byte array has been collected.
 */
class FontSingleCopyTest {

    @Test
    fun nfr3_a_registered_font_file_is_not_kept_as_a_byte_array() {
        val cache = AssetCache()
        val file = registerFont(cache, FACE, TinyIconFont.build(family = "Tiny Once"))
        assertTrue(collected(file), "the registered font's byte array is still reachable after registration")
        assertNotNull(cache.asset(FACE) as? Asset.Font, "the face is still registered")
    }

    @Test
    fun nfr3_a_font_whose_byte_array_is_gone_still_loads_in_compose() {
        val cache = AssetCache()
        val file = registerFont(cache, FACE, TinyIconFont.build(family = "Tiny Reload"))
        assertTrue(collected(file), "the registered font's byte array is still reachable after registration")
        val font = cache.asset(FACE) as Asset.Font
        // Twice, each from a resolver of its own: the second is Compose loading the face
        // again after its cache let the first typeface go.
        repeat(2) { round ->
            val typeface = load(font.family, FontWeight.Normal, FontStyle.Normal)
            assertNotEquals(0.toShort(), typeface.getUTF32Glyph('A'.code), "load ${round + 1} has the face's glyph for A")
            assertNotEquals(0.toShort(), typeface.getUTF32Glyph(TinyIconFont.CODE_POINT), "load ${round + 1} has the icon glyph")
        }
        assertTrue(font.face!!.hasGlyph('A'.code))
    }

    /**
     * Each face of a family keeps an identity of its own, so a regular, a bold and an italic
     * of one family name are three faces Compose chooses between by weight and slant, with
     * their byte arrays gone.
     */
    @Test
    fun fr48_the_faces_of_a_family_resolve_to_their_own_typefaces_without_their_bytes() {
        val cache = AssetCache()
        val files = listOf(
            registerFont(cache, REGULAR, TinyIconFont.build(family = "Tiny Faces", weight = 400, advance = 1500)),
            registerFont(cache, BOLD, TinyIconFont.build(family = "Tiny Faces", weight = 700, advance = 1000)),
            registerFont(cache, ITALIC, TinyIconFont.build(family = "Tiny Faces", weight = 400, advance = 1250, italic = true)),
        )
        assertNull(cache.register(STACK, AssetKind.Font.ordinal + 1, stack(listOf(REGULAR, BOLD, ITALIC))))
        files.forEachIndexed { at, file -> assertTrue(collected(file), "face ${at + 1}'s byte array is still reachable") }

        val family = (cache.asset(STACK) as Asset.Font).family
        val regular = load(family, FontWeight.Normal, FontStyle.Normal)
        val bold = load(family, FontWeight.Bold, FontStyle.Normal)
        val italic = load(family, FontWeight.Normal, FontStyle.Italic)
        assertEquals(400, regular.fontStyle.weight)
        assertEquals(700, bold.fontStyle.weight)
        assertEquals(3, setOf(regular.uniqueId, bold.uniqueId, italic.uniqueId).size, "three faces, three typefaces")
        // Each face's glyph has its own width, so the width says which file the typeface is.
        assertEquals(1500f, advance(regular), 1f, "the regular face")
        assertEquals(1000f, advance(bold), 1f, "the bold face")
        assertEquals(1250f, advance(italic), 1f, "the italic face")
    }

    @Test
    fun nfr3_a_bundled_family_does_not_keep_its_files() {
        val (family, file) = bundled(TinyIconFont.build(family = "Tiny Bundled", weight = 700))
        assertTrue(collected(file), "the bundled face's byte array is still reachable")
        val typeface = load(family, FontWeight.Bold, FontStyle.Normal)
        assertEquals(700, typeface.fontStyle.weight)
        assertNotEquals(0.toShort(), typeface.getUTF32Glyph('A'.code))
    }

    /**
     * Registers [bytes] as a font and returns a weak reference to them. The array is a
     * parameter here, so once this returns nothing in the test holds it.
     */
    private fun registerFont(cache: AssetCache, id: Int, bytes: ByteArray): WeakReference<ByteArray> {
        assertNull(cache.register(id, AssetKind.Font.ordinal + 1, bytes), "asset $id registers")
        return WeakReference(bytes)
    }

    private fun bundled(bytes: ByteArray): Pair<FontFamily, WeakReference<ByteArray>> =
        bundledFamily(listOf(BundledFace("Tiny-Bold.ttf", 700, italic = false) to bytes)) to WeakReference(bytes)

    /** The typeface a resolver that has never loaded [family] gives for the weight and slant. */
    private fun load(family: FontFamily, weight: FontWeight, style: FontStyle): Typeface {
        val result = createFontFamilyResolver().resolve(family, weight, style).value
        val loaded = assertNotNull(result as? FontLoadResult, "the family resolves to a loaded font, not $result")
        return assertNotNull(loaded.typeface, "the loaded font has a typeface")
    }

    /** How wide [typeface] draws `A`, in font units (the test faces have 1000 to the em). */
    private fun advance(typeface: Typeface): Float {
        val font = Font(typeface, TinyIconFont.UNITS_PER_EM.toFloat())
        return try {
            font.getWidths(shortArrayOf(typeface.getUTF32Glyph('A'.code)))[0]
        } finally {
            font.close()
        }
    }

    /** Whether [reference] is cleared once the collector has been asked to run. */
    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(50) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(20)
        }
        return reference.get() == null
    }

    /** A stack asset of one family, as compose-rust's `FontStack::to_bytes` writes it. */
    private fun stack(faces: List<Int>): ByteArray {
        val out = ByteBuffer.allocate((4 + faces.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.put("CRFS".encodeToByteArray())
        out.putInt(1)
        out.putInt(1)
        out.putInt(faces.size)
        for (face in faces) out.putInt(face)
        return out.array()
    }

    private companion object {
        const val FACE = 51
        const val REGULAR = 52
        const val BOLD = 53
        const val ITALIC = 54
        const val STACK = 55
    }
}
