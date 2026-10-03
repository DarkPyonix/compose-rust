package dev.darkpyonix.composerust.ui.platform

/**
 * What this renderer tells the Compose fork's `ComposeWindow` about how its window is drawn.
 *
 * The fork's window on Windows takes the caption strip, holds a live resize for its frame,
 * and by default draws a band and three buttons there. This renderer draws its own: the bar
 * at the top of the tree takes the caption and the design system draws the buttons. So it
 * asks the fork for the caption strip without the band (`content`), or for the system's own
 * caption when the application asked for that.
 *
 * Nothing reads these until the renderer builds against the fork's desktop artifacts; until
 * then Compose is JetBrains' and ignores them, and `c/renderer_entry.c` keeps doing the same
 * work itself. Set before any window exists, because the fork reads them when one is made.
 */
internal fun forkWindowChromeProperties(chrome: WindowChrome): Map<String, String> = mapOf(
    "compose.windows.caption" to when (chrome) {
        WindowChrome.Modern -> "content"
        WindowChrome.System -> "system"
    },
)

/**
 * Sets [forkWindowChromeProperties] where nothing has set them already. A property given on
 * the command line wins, so the fork's other modes stay reachable for diagnosis.
 */
internal fun applyForkWindowChromeProperties(
    chrome: WindowChrome,
    get: (String) -> String? = System::getProperty,
    set: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
) {
    for ((key, value) in forkWindowChromeProperties(chrome)) {
        if (get(key) == null) set(key, value)
    }
}
