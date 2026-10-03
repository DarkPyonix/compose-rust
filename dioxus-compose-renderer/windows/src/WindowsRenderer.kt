package dioxus.compose.ui.platform

import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.HostConnection

/**
 * Runs the renderer's Compose application. This is what `dioxus_compose_renderer_run` calls.
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
    Notifications.platform = Win32Notifications()
    // Started before there is a window, because what the window should look like is in the
    // first batch and a window cannot be told afterwards. Started on this thread, which is
    // the one every later call to it is made from and the one the frames are drawn on.
    val host = DioxusHost(connection())
    host.start()

    // What the application asked for. A window that said nothing is listed under whatever
    // this renderer happens to be called, and a measurement of zero means it did not ask.
    val asked = host.table.window
    val window = Win32Window.open(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
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
    window.setContent { DioxusContent(host) }
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
