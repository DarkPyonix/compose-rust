@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection

/**
 * Runs the renderer's Compose application. This is what `compose_rust_renderer_run` calls.
 *
 * The window is `desktop/c/win32_window.c`, the one the native image opens on this platform,
 * linked into the application's own executable beside this renderer. What differs from the
 * native image is what is not here: no Java runtime and no toolkit underneath.
 *
 * Returns when the window closes.
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    // Toasts, before the Host starts: its first batch may already post one. A press on the
    // body of one brings this application's window forward, which the C side does itself
    // because it is the side that knows the window.
    // The menu a selection offers goes through the platform's TextToolbar
    // (Win32TextToolbar), as on macOS; the new context menu path would draw Compose's own.
    androidx.compose.foundation.ComposeFoundationFlags.isNewContextMenuEnabled = false
    Notifications.platform = Win32Notifications()
    // Started before there is a window, because what the window should look like is in the
    // first batch and a window cannot be told afterwards. Started on this thread, which is
    // the one every later call to it is made from and the one the frames are drawn on.
    val host = ComposeRustHost(connection())
    host.start()

    // What the application asked for. A window that said nothing is listed under whatever
    // this renderer happens to be called, and a measurement of zero means it did not ask.
    val asked = host.table.window
    // Who draws the caption, as the application asked and as the native image does: with
    // Chrome.System the system's caption, otherwise the window gives the caption strip to
    // the content, which draws the title and the three buttons in it.
    val systemChrome = asked?.chrome == dev.darkpyonix.composerust.protocol.Chrome.System
    val window = Win32Window.open(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
        resizable = asked?.resizable ?: true,
        minWidth = asked?.minWidth?.takeIf { it > 0 } ?: 0,
        minHeight = asked?.minHeight?.takeIf { it > 0 } ?: 0,
        systemChrome = systemChrome,
    )
    if (window == null) {
        java.lang.System.err.println(
            "compose-rust: this machine has no Direct3D 12 adapter. Where there is no graphics " +
                "card at all, set DXC_D3D12_WARP=1 to draw with the software one.",
        )
        host.shutdown()
        return RendererApi.RUN_FAILED
    }

    // The application's own tree, drawn by the same interpreter every other platform uses.
    if (systemChrome) {
        window.setContent { ComposeRustContent(host) }
    } else {
        val actions = dev.darkpyonix.composerust.runtime.WindowActions(
            minimise = { Win32Window.action(0) },
            maximise = { Win32Window.action(1) },
            close = { Win32Window.action(2) },
        )
        window.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                dev.darkpyonix.composerust.runtime.LocalWindowActions provides actions,
            ) {
                ComposeRustContent(
                    host,
                    caption = dev.darkpyonix.composerust.runtime.WindowCaption(height = WINDOWS_CAPTION_HEIGHT),
                )
            }
        }
    }
    try {
        window.run()
    } finally {
        window.close()
        host.shutdown()
    }
    return RendererApi.RUN_OK
}

/** What a window that did not say is opened at, in the units the scene measures in. */
private const val DEFAULT_WIDTH = 520
private const val DEFAULT_HEIGHT = 360

/** Windows 11's caption height, the same number win32_window.c lays the caption out with. */
private val WINDOWS_CAPTION_HEIGHT = 32.dp
