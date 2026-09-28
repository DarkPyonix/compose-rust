package dioxus.compose.ui.platform

import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.HostConnection

/**
 * Runs the renderer's Compose application. This is what `dioxus_compose_renderer_run` calls.
 *
 * The window is this renderer's own, and on this platform there is no alternative to compare
 * it with: Compose Multiplatform opens no window for Kotlin/Native on Linux, because it
 * publishes no Kotlin/Native target for Linux at all. What it has here is Skia and the
 * composition, which is the half that was worth keeping.
 *
 * Returns when the window closes.
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    // Started before there is a window, because what the window should look like is in the first
    // batch and a window cannot be told afterwards: how big it is and what it is called are
    // settled when it is made. Started on this thread, which is the one every later call to it is
    // made from and the one the frames are drawn on.
    val host = DioxusHost(connection())
    host.start()

    // What the application asked for. A window that said nothing is listed under whatever this
    // renderer happens to be called, which is the library's name and not any application's, and a
    // measurement of zero means it did not ask.
    val asked = host.table.window
    val window = LinuxWindow.open(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "dioxus-compose",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
    )
    if (window == null) {
        java.lang.System.err.println(
            "dioxus-compose: no X11 display, or no double buffered GLX visual on it. " +
                "Check DISPLAY, and that the machine has an X or XWayland server to talk to.",
        )
        host.shutdown()
        return RendererApi.RUN_FAILED
    }

    // The application's own tree, drawn by the same interpreter the native image path uses.
    // Nothing in it knows which of the two it is running on, which is the point.
    //
    // No caption is passed. The window manager draws this platform's title bar itself, outside
    // the window, so there is no strip of our own for content to step clear of.
    window.setContent { DioxusContent(host) }
    try {
        window.run()
    } finally {
        host.shutdown()
    }
    return RendererApi.RUN_OK
}

/** What a window that did not say is opened at, in the units the scene measures in. */
private const val DEFAULT_WIDTH = 520
private const val DEFAULT_HEIGHT = 360
