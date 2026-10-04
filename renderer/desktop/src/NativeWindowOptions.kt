package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.asksForWindowMaterial

// What the application asked of its window, read the same way by every window of our own:
// the native image windows and the Kotlin/Native ones. One reading, so that a title, a size or
// a smallest size means the same thing on every path.

/** What the application asked of its window in its first batch. */
internal data class NativeWindowOptions(
    val title: String,
    val width: Int,
    val height: Int,
    val resizable: Boolean,
    val minWidth: Int,
    val minHeight: Int,
    val systemChrome: Boolean,
    val backdrop: Boolean,
)

/**
 * Reads the window's options from the Host's first batch.
 *
 * [backdropSupported] says whether this platform's window can show what is behind it,
 * because asking for a material the window cannot draw would leave a design drawing for a
 * desktop that never shows through.
 */
internal fun nativeWindowOptions(host: ComposeRustHost, backdropSupported: Boolean): NativeWindowOptions {
    val asked = host.table.window
    return NativeWindowOptions(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
        resizable = asked?.resizable ?: true,
        minWidth = asked?.minWidth?.takeIf { it > 0 } ?: 0,
        minHeight = asked?.minHeight?.takeIf { it > 0 } ?: 0,
        systemChrome = asked?.chrome == Chrome.System,
        backdrop = backdropSupported && host.table.asksForWindowMaterial(host.roots),
    )
}

private const val DEFAULT_WIDTH = 520
private const val DEFAULT_HEIGHT = 360
