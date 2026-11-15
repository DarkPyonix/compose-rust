@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.design.WEB_FONT_DIRECTORY
import dev.darkpyonix.composerust.design.bundledChain
import dev.darkpyonix.composerust.design.bundledFallback
import dev.darkpyonix.composerust.design.bundledFamily
import dev.darkpyonix.composerust.design.platformDefaultChain
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.design.webDefaultFaces
import dev.darkpyonix.composerust.design.webFallbackFaces
import dev.darkpyonix.composerust.ui.node.platformFallbackFamilies
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * Fetches the faces of the default family the bundle carries, and makes that family the
 * page's interface face and its `sans-serif`. Fetches the fallback faces beside them, and
 * makes them where a letter is drawn from when the text's own families have no glyph for it.
 *
 * A browser gives a canvas none of the machine's fonts, and Skia's web build carries one
 * regular face, so until this has run every weight and slant is drawn in it. Called from
 * `main` before the first frame, so no text is laid out in the regular face and then again.
 *
 * A page served without the files (a bundle built before they were added, or a host that
 * does not serve them) keeps the regular face and says so on the console: text in the wrong
 * weight is better than no screen.
 *
 * The two sets are fetched apart, so a page that serves one and not the other still gets
 * the one it serves.
 */
suspend fun installBundledFonts() {
    val defaultFiles = fetchFaces(
        webDefaultFaces.joinToString("\n") { "$WEB_FONT_DIRECTORY/${it.file}" },
        "Text is drawn in the one regular face the page has, whatever its weight.",
    )
    val fallbackFiles = fetchFaces(
        webFallbackFaces.joinToString("\n") { "$WEB_FONT_DIRECTORY/$it" },
        "A character the default family has no glyph for is drawn as an empty box.",
    )
    val fallback = fallbackFiles.await<JsAny?>()
    if (fallback != null) {
        platformFallbackFamilies = webFallbackFaces.indices.mapNotNull { bundledFallback(bytesOf(fallback, it)) }
    }
    val fetched = defaultFiles.await<JsAny?>() ?: return
    val faces = webDefaultFaces.mapIndexed { index, face -> face to bytesOf(fetched, index) }
    val family = bundledFamily(faces)
    platformUiFamily = family
    platformSansSerifFamily = family
    platformDefaultChain = bundledChain(family, faces)
}

private fun bytesOf(fetched: JsAny, index: Int): ByteArray {
    val length = faceLength(fetched, index)
    return ByteArray(length) { offset -> faceByte(fetched, index, offset).toByte() }
}

/**
 * Every file in [files] (one path per line, relative to the page), as an array of
 * `Uint8Array`s in the same order, or null where any of them could not be fetched, which
 * is said on the console with [otherwise], what the page does without them.
 */
private fun fetchFaces(files: String, otherwise: String): Promise<JsAny?> = js(
    """Promise.all(files.split('\n').map((file) =>
        fetch(new URL(file, document.baseURI)).then((response) => {
            if (!response.ok) throw new Error(file + ': ' + response.status);
            return response.arrayBuffer();
        }).then((buffer) => new Uint8Array(buffer))
    )).catch((error) => {
        console.info('compose-rust: the bundled font faces could not be fetched (' + error +
            '). ' + otherwise);
        return null;
    })""",
)

private fun faceLength(fetched: JsAny, index: Int): Int = js("fetched[index].length")

private fun faceByte(fetched: JsAny, index: Int, offset: Int): Int = js("fetched[index][offset]")
