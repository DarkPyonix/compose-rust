@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

// The Xlib side of an input method: which style to ask it for and which locale it needs.
// What it says once it is talking, the preedit model and the key decision, is `ImeComposition.kt`,
// shared with the window that links the native image.

/**
 * Which input style to ask an input method for, out of the ones it offers.
 *
 * Preedit callbacks come first, because they are the only style in which the composing text
 * is handed to this client and drawn by Compose in the field. The styles below it keep
 * working: the input method draws its own composition window, committed text still arrives,
 * and what is lost is that the syllable being built is not in the field until it is finished.
 * Status is never wanted, because there is no status area to give it.
 *
 * Null when it offers none of these, which is an input method this window cannot be typed
 * through at all.
 */
internal fun chooseInputStyle(
    offered: List<Long>,
    preeditCallbacks: Long,
    preeditNothing: Long,
    preeditNone: Long,
    statusNothing: Long,
    statusNone: Long,
): Long? {
    val wanted = listOf(
        preeditCallbacks or statusNothing,
        preeditCallbacks or statusNone,
        preeditNothing or statusNothing,
        preeditNothing or statusNone,
        preeditNone or statusNothing,
        preeditNone or statusNone,
    )
    return wanted.firstOrNull { it in offered }
}

/** Whether a locale name says the text it carries is UTF-8. */
internal fun isUtf8Locale(name: String?): Boolean {
    if (name == null) return false
    val lower = name.lowercase()
    return lower.endsWith(".utf-8") || lower.endsWith(".utf8") || ".utf-8@" in lower || ".utf8@" in lower
}
