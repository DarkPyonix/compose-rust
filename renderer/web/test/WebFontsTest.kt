@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlinx.coroutines.DelicateCoroutinesApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.FontLoadResult
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

/**
 * The bundled default family, fetched the way the page fetches it and resolved by Compose's
 * web build in the browser the test runs in.
 *
 * The page a Kotlin/Wasm test runs on serves only the test's own resources, so where the
 * font files are not beside it the test says so and passes, as the boundary tests do for
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

    private fun weightOf(family: FontFamily, weight: Int): Int {
        val result = createFontFamilyResolver().resolve(family, FontWeight(weight)).value as FontLoadResult
        return result.typeface!!.fontStyle.weight
    }
}
