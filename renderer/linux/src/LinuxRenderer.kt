package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import org.thisisthepy.compose.window.WindowConfig

/**
 * Runs the renderer's Compose application. This is what `compose_rust_renderer_run` calls.
 *
 * The window is this renderer's own, and on this platform there is no alternative to compare
 * it with: Compose Multiplatform opens no window for Kotlin/Native on Linux, because it
 * publishes no Kotlin/Native target for Linux at all. What it has here is Skia and the
 * composition, which is the half that was worth keeping.
 *
 * Returns when the window closes.
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    // Asked for by a build that proves the executable shapes and wraps Korean with no ICU data
    // file beside it. A failure stops here, before a window, so the build sees it.
    if (runTextSelfCheckIfAsked() == false) return RendererApi.RUN_FAILED
    // The notification daemon, over the session bus, before the Host starts: its first batch
    // may already post one. A press on a notification's body raises the window, which does
    // not exist yet, so it is found when the press arrives.
    var opened: LinuxWindow? = null
    Notifications.platform = DBusNotifications(
        open = { PosixBusConnection.open() },
        bringToFront = { opened?.raise() },
        applicationName = PosixBusConnection.applicationName(),
    )
    // Started before there is a window, because what the window should look like is in the first
    // batch and a window cannot be told afterwards: how big it is and what it is called are
    // settled when it is made. Started on this thread, which is the one every later call to it is
    // made from and the one the frames are drawn on.
    val host = ComposeRustHost(connection())
    host.start()

    // What the application asked for. A window that said nothing is listed under whatever this
    // renderer happens to be called, which is the library's name and not any application's, and a
    // measurement of zero means it did not ask.
    val asked = host.table.window
    val window = LinuxWindow.open(linuxWindowConfig(asked))
    if (window == null) {
        java.lang.System.err.println(
            "compose-rust: no X11 display, or no double buffered GLX visual on it. " +
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
    opened = window
    // Joined before the loop starts: a screen reader that is already running reads the window
    // from its first frame. Nothing happens where there is no accessibility bus.
    window.startAccessibility(PosixBusConnection.applicationName())
    // The bus is read on this thread every turn, because there is no other thread to read it
    // on and nothing else would notice a press arriving while the window is idle.
    window.onTurn = { host.table.notifications.pump() }
    window.setContent { ComposeRustContent(host) }
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

/**
 * The window the application asked for, in the form the X11 layer opens one from.
 *
 * Everything the application can say about its window's size travels: whether it may be
 * resized and the smallest size it may be dragged to, as well as the size it opens at. The
 * X11 layer turns the last three into the hints the window manager reads, so a window that
 * asked not to be resized stays at its size and one with a minimum is not dragged below it.
 * A window that said nothing opens at the default size, resizable, with no minimum.
 */
internal fun linuxWindowConfig(asked: dev.darkpyonix.composerust.protocol.Window?): WindowConfig =
    WindowConfig(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
        minWidth = asked?.minWidth?.takeIf { it > 0 } ?: 0,
        minHeight = asked?.minHeight?.takeIf { it > 0 } ?: 0,
        resizable = asked?.resizable ?: true,
    )
