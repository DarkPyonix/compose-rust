@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * Reads plain text from the browser's clipboard, through the clipboard Compose hands the
 * composition (its native clipboard is the page's `navigator.clipboard`).
 *
 * The browser has no synchronous read, so this suspends while it asks the user. A refusal,
 * an insecure page, or a browser without `readText` rejects or answers nothing, and all of
 * them come back as null, which the reader sends as no text. Whatever the browser says
 * about a refusal is not repeated here, because it may describe what was on the clipboard.
 */
suspend fun readWebClipboardText(clipboard: androidx.compose.ui.platform.Clipboard): String? {
    val native = clipboard.nativeClipboard as? JsAny
    val text = readText(native).await<JsString?>()
    return text?.toString()
}

/** `clipboard.readText()`, or a promise of null when the page has no such call. */
@Suppress("UNUSED_PARAMETER")
private fun readText(clipboard: JsAny?): Promise<JsAny?> = js(
    """(clipboard && typeof clipboard.readText === 'function')
        ? clipboard.readText().catch(function () { return null; })
        : Promise.resolve(null)""",
)
