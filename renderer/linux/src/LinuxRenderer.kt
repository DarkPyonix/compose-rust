package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ComposeRustContent
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection

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
    // The notification daemon, over the session bus, before the Host starts: its first batch
    // may already post one. A press on a notification's body raises the window, which does
    // not exist yet, so it is found when the press arrives.
    var opened: LinuxWindow? = null
    dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting = ::askLinuxForMotion
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
    val window = LinuxWindow.open(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
    )
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
 * What the desktop says about reducing motion: KDE's animation speed factor, where zero is
 * no animation, in a KDE session, and GNOME's `enable-animations` otherwise, which is the
 * same question the other way round. Unknown where neither can be asked.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun askLinuxForMotion(): dev.darkpyonix.composerust.protocol.ReducedMotion {
    val kde = platform.posix.getenv("XDG_CURRENT_DESKTOP")?.toKString()
        .orEmpty().contains("KDE", ignoreCase = true)
    if (kde) {
        val factor = runQuery("kreadconfig5 --group KDE --key AnimationDurationFactor 2>/dev/null")
            ?.trim()?.toDoubleOrNull()
        if (factor != null) {
            return if (factor == 0.0) dev.darkpyonix.composerust.protocol.ReducedMotion.On
            else dev.darkpyonix.composerust.protocol.ReducedMotion.Off
        }
    }
    return when (runQuery("gsettings get org.gnome.desktop.interface enable-animations 2>/dev/null")?.trim()) {
        "false" -> dev.darkpyonix.composerust.protocol.ReducedMotion.On
        "true" -> dev.darkpyonix.composerust.protocol.ReducedMotion.Off
        else -> dev.darkpyonix.composerust.protocol.ReducedMotion.Unknown
    }
}

/** The first line a shell command prints, or null where it could not be run. */
@OptIn(ExperimentalForeignApi::class)
private fun runQuery(command: String): String? {
    val pipe = platform.posix.popen(command, "r") ?: return null
    try {
        memScoped {
            val buffer = allocArray<ByteVar>(256)
            val line = platform.posix.fgets(buffer, 256, pipe) ?: return null
            return line.toKString()
        }
    } finally {
        platform.posix.pclose(pipe)
    }
}
