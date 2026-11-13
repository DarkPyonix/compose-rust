@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * Reads plain text from the page's clipboard, `navigator.clipboard.readText()`.
 *
 * The browser has no synchronous read, so this suspends while it asks the user. A refusal,
 * an insecure page, or a browser without `readText` rejects or answers nothing, and all of
 * them come back as null, which the reader sends as no text. Whatever the browser says
 * about a refusal is not repeated here, because it may describe what was on the clipboard.
 *
 * The clipboard Compose hands the composition is taken for the call's shape only. Its
 * native clipboard is a Kotlin external type that cannot be cast to a JavaScript value on
 * Wasm (the cast always fails), so the read goes to `navigator.clipboard` directly, which
 * is the same object.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun readWebClipboardText(clipboard: androidx.compose.ui.platform.Clipboard): String? {
    val text = readText().await<JsString?>()
    return text?.toString()
}

/** `navigator.clipboard.readText()`, or a promise of null when the page has no such call. */
private fun readText(): Promise<JsAny?> = js(
    """(typeof navigator !== 'undefined' && navigator.clipboard &&
        typeof navigator.clipboard.readText === 'function')
        ? navigator.clipboard.readText().catch(function () { return null; })
        : Promise.resolve(null)""",
)
