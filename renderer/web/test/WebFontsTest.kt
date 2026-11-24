@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.platformDefaultChain
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.design.webFallbackFaces
import dev.darkpyonix.composerust.foundation.resolveFontChain
import dev.darkpyonix.composerust.protocol.FontRef
import dev.darkpyonix.composerust.protocol.GenericFamily
import dev.darkpyonix.composerust.protocol.TypeRole
import dev.darkpyonix.composerust.ui.node.AssetCache
import dev.darkpyonix.composerust.ui.node.platformFallbackFamilies
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import org.jetbrains.skia.Typeface

/**
 * The bundled default family and the fallback faces beside it, fetched the way the page
 * fetches them and resolved by Compose's web build in the browser the test runs in.
 *
 * `testResources/fonts` symlinks `resources/fonts` so the test page serves the bundled
 * files directly. Where a checkout cannot follow symlinks and the font files are not
 * served beside the test page, the test says so and passes, as the boundary tests do for
 * the Host. The same family is resolved on the desktop's Skia from the same files in
 * `WebDefaultFamilyTest`.
 */
class WebFontsTest {
    @Test
    fun fr13_web_bold_draws_bold(): Promise<JsAny?> = GlobalScope.promise<JsAny?> {
        val before = platformUiFamily
        installBundledFonts()
        if (platformUiFamily === before) {
            println("fr13 skipped: the bundled font files are not served beside the test page")
            return@promise null
        }
        assertSame(platformUiFamily, platformSansSerifFamily, "sans-serif is the same family")
        assertEquals(700, weightOf(platformUiFamily, 700), "weight 700 is the bold face")
        assertEquals(400, weightOf(platformUiFamily, 400), "weight 400 is the regular face")
        null
    }

    /**
     * The signs the default family has no glyph for (erase to the left, the small down
     * triangle, the play triangle) are set in the fallback face the page fetched, and the
     * face the browser's Skia resolves for them has the glyph. A Latin letter stays in the
     * default family.
     */
    @Test
    fun fr13_web_symbols_the_default_family_lacks_draw_from_the_fallback_face(): Promise<JsAny?> =
        GlobalScope.promise<JsAny?> {
            val before = platformUiFamily
            installBundledFonts()
            if (platformUiFamily === before || platformFallbackFamilies.isEmpty()) {
                println("fr13 skipped: the bundled font files are not served beside the test page")
                return@promise null
            }
            assertEquals(webFallbackFaces.size, platformFallbackFamilies.size, "every fallback face was read")
            val chain = assertNotNull(platformDefaultChain, "the default family knows its letters")
            assertSame(platformUiFamily, chain.primary)
            assertTrue(chain.spans("Play").isEmpty(), "Latin stays in the default family")
            assertTrue(hasGlyph(platformUiFamily, 'P'.code), "the default family has a Latin letter")
            for (sign in listOf(0x232B, 0x25BE, 0x25B6)) {
                val name = "U+" + sign.toString(16).uppercase()
                assertTrue(!hasGlyph(platformUiFamily, sign), "$name is not in the default family")
                val spans = chain.spans("M" + Char(sign) + "M")
                assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "$name alone leaves the default family")
                val drawnIn = assertNotNull(spans.single().item.fontFamily)
                assertTrue(hasGlyph(drawnIn, sign), "$name has a glyph in the fallback face")
            }
            null
        }

    /**
     * Monospace, serif, CSS `ui-monospace` / `ui-serif`, and a monospace type role reach the
     * bundled fallback face for symbols the default family lacks, while keeping Latin in
     * their primary family.
     */
    @Test
    fun fr13_web_monospace_and_serif_reach_the_fallback_face(): Promise<JsAny?> =
        GlobalScope.promise<JsAny?> {
            installBundledFonts()
            if (platformDefaultChain == null || platformFallbackFamilies.isEmpty()) {
                println("fr13 skipped: the bundled font files are not served beside the test page")
                return@promise null
            }
            val theme = resolveTheme(theme = null, platform = HostPlatform.Web, systemDark = false)
            val codeChain = assertNotNull(theme.chainFor(theme.family(TypeRole.Mono)), "Code role has a chain")
            val chains = listOf(
                "Code role" to codeChain,
                "monospace" to resolveFontChain(listOf(FontRef.Generic(GenericFamily.Monospace)), AssetCache(), mutableListOf()),
                "serif" to resolveFontChain(listOf(FontRef.Generic(GenericFamily.Serif)), AssetCache(), mutableListOf()),
                "ui-monospace" to resolveFontChain(listOf(FontRef.System("ui-monospace")), AssetCache(), mutableListOf()),
                "ui-serif" to resolveFontChain(listOf(FontRef.System("ui-serif")), AssetCache(), mutableListOf()),
            )
            for ((label, chain) in chains) {
                assertTrue(chain.spans("Play 123").isEmpty(), "$label keeps Latin in primary")
                for (sign in listOf(0x232B, 0x25BE, 0x25B6)) {
                    val name = "U+" + sign.toString(16).uppercase()
                    val spans = chain.spans("M" + Char(sign) + "M")
                    assertEquals(listOf(1 to 2), spans.map { it.start to it.end }, "$label: $name leaves primary")
                    val drawnIn = assertNotNull(spans.single().item.fontFamily)
                    assertTrue(hasGlyph(drawnIn, sign), "$label: $name has a glyph in fallback")
                }
            }
            null
        }

    private fun weightOf(family: FontFamily, weight: Int): Int = typeface(family, weight).fontStyle.weight

    private fun hasGlyph(family: FontFamily, codePoint: Int): Boolean =
        typeface(family, 400).getUTF32Glyph(codePoint) != 0.toShort()

    private fun typeface(family: FontFamily, weight: Int): Typeface {
        val result = createFontFamilyResolver().resolve(family, FontWeight(weight)).value as FontLoadResult
        return result.typeface!!
    }
}
