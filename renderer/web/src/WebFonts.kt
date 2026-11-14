@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.design.WEB_FONT_DIRECTORY
import dev.darkpyonix.composerust.design.bundledFamily
import dev.darkpyonix.composerust.design.platformSansSerifFamily
import dev.darkpyonix.composerust.design.platformUiFamily
import dev.darkpyonix.composerust.design.webDefaultFaces
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * Fetches the faces of the default family the bundle carries, and makes that family the
 * page's interface face and its `sans-serif`.
 *
 * A browser gives a canvas none of the machine's fonts, and Skia's web build carries one
 * regular face, so until this has run every weight and slant is drawn in it. Called from
 * `main` before the first frame, so no text is laid out in the regular face and then again.
 *
 * A page served without the files (a bundle built before they were added, or a host that
 * does not serve them) keeps the regular face and says so on the console: text in the wrong
 * weight is better than no screen.
 */
suspend fun installBundledFonts() {
    val files = webDefaultFaces.joinToString("\n") { "$WEB_FONT_DIRECTORY/${it.file}" }
    val fetched = fetchFaces(files).await<JsAny?>() ?: return
    val faces = webDefaultFaces.mapIndexed { index, face -> face to bytesOf(fetched, index) }
    val family = bundledFamily(faces)
    platformUiFamily = family
    platformSansSerifFamily = family
}

private fun bytesOf(fetched: JsAny, index: Int): ByteArray {
    val length = faceLength(fetched, index)
    return ByteArray(length) { offset -> faceByte(fetched, index, offset).toByte() }
}

/**
 * Every file in [files] (one path per line, relative to the page), as an array of
 * `Uint8Array`s in the same order, or null where any of them could not be fetched.
 */
private fun fetchFaces(files: String): Promise<JsAny?> = js(
    """Promise.all(files.split('\n').map((file) =>
        fetch(new URL(file, document.baseURI)).then((response) => {
            if (!response.ok) throw new Error(file + ': ' + response.status);
            return response.arrayBuffer();
        }).then((buffer) => new Uint8Array(buffer))
    )).catch((error) => {
        console.info('compose-rust: the bundled font faces could not be fetched (' + error +
            '). Text is drawn in the one regular face the page has, whatever its weight.');
        return null;
    })""",
)

private fun faceLength(fetched: JsAny, index: Int): Int = js("fetched[index].length")

private fun faceByte(fetched: JsAny, index: Int, offset: Int): Int = js("fetched[index][offset]")
