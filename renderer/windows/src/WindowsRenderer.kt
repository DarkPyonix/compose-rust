@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.WindowContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection

/**
 * Runs the renderer's Compose application. This is what `compose_rust_renderer_run` calls.
 *
 * The window is the Compose fork's `extended/window/windows-shared`, the one the native image
 * opens on this platform too,
 * linked into the application's own executable beside this renderer. What differs from the
 * native image is what is not here: no Java runtime and no toolkit underneath.
 *
 * Returns when the window closes, or for an application of several windows when the last of
 * them is removed (see [runMultiWindow]).
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    // Asked for by a build that proves the executable finds, shapes, wraps and draws Korean
    // with no ICU data file beside it. A failure stops here, before a window, so the build
    // sees it.
    if (runTextSelfCheckIfAsked() == false) return RendererApi.RUN_FAILED
    // Toasts, before the Host starts: its first batch may already post one. A press on the
    // body of one brings this application's window forward, which the C side does itself
    // because it is the side that knows the window.
    // Compose's old context menu path. On this target the new one has no default provider
    // and composes nothing in a text field's place. On the old one a field draws a menu of
    // its own on a right click, and the window puts up the system's instead (Win32EditMenu),
    // keeping Compose's shut while it does.
    androidx.compose.foundation.ComposeFoundationFlags.isNewContextMenuEnabled = false
    Win32EditMenu.install()
    Notifications.platform = Win32Notifications()
    // Windows' "Show animations in Windows" is the reduced-motion setting the Host is told and
    // the press feedback and motion roles follow.
    installWindowsReducedMotion(::win32ClientAreaAnimation)
    // Asked for by the build that proves several windows work here (see
    // `Fr53WindowsCheck.kt`). Like the text check, it ends the run when it fails and lets the
    // application start when it passed.
    if (runFr53WindowsChecksIfAsked() == false) return RendererApi.RUN_FAILED
    // Asked for by the build that proves the window draws in device pixels at every display
    // scale (`HiDpiCheck.kt`). It is the whole run: its window is the check's, not the
    // application's, so the application does not start after it.
    runFr82WindowsChecksIfAsked()?.let { passed -> return if (passed) RendererApi.RUN_OK else RendererApi.RUN_FAILED }
    // Started before there is a window, because what the window should look like is in the
    // first batch and a window cannot be told afterwards. Started on this thread, which is
    // the one every later call to it is made from and the one the frames are drawn on.
    // Files let go over a window go to the node under them. Set before the Host starts, because
    // its first batch may already contain a drop target.
    dev.darkpyonix.composerust.ui.node.platformFileDrop = { modifier, node, dispatcher ->
        modifier.nativeFileDrop(node, dispatcher)
    }
    // Where the application's zoom level is kept between runs (FR-43), before the first
    // composition reads it.
    dev.darkpyonix.composerust.runtime.platformZoomLevelStore = ::windowsZoomLevelStore
    // The file dialogs (FR-72), before the Host exists: the node table takes the platform when
    // it is made, and the first batch may already ask for one. The windows they belong to are
    // handed in once they exist.
    val fileDialogs = WindowsFileDialogPlatform()
    installedWindowsFileDialogs = fileDialogs
    FileDialogs.platform = fileDialogs
    val host = ComposeRustHost(connection())
    fileDialogs.nodeWindow = { id -> host.table.fileDialogs.windowResolver(id) }
    host.start()

    // What the application asked for. A window that said nothing is listed under whatever
    // this renderer happens to be called, and a measurement of zero means it did not ask.
    val asked = host.table.window
    // An application that asked for several windows (SetWindow mode Windows) gets one Win32
    // window for every `Window` node and a loop of its own; what follows is the window of an
    // application that has one, and is as it always was.
    if (asked?.mode == WindowMode.Windows) return runMultiWindow(host)
    // Who draws the caption, as the application asked and as the native image does: with
    // Chrome.System the system's caption, otherwise the window gives the caption strip to
    // the content, which draws the title and the three buttons in it.
    val systemChrome = asked?.chrome == dev.darkpyonix.composerust.protocol.Chrome.System
    val fullSize = asked?.chrome == dev.darkpyonix.composerust.protocol.Chrome.FullSize
    val window = Win32Window.open(
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else DEFAULT_WIDTH,
        height = if (asked != null && asked.height > 0) asked.height else DEFAULT_HEIGHT,
        resizable = asked?.resizable ?: true,
        minWidth = asked?.minWidth?.takeIf { it > 0 } ?: 0,
        minHeight = asked?.minHeight?.takeIf { it > 0 } ?: 0,
        systemChrome = systemChrome,
        appCaption = fullSize,
    )
    if (window == null) {
        java.lang.System.err.println(
            "compose-rust: this machine has no Direct3D 12 adapter. Where there is no graphics " +
                "card at all, set DXC_D3D12_WARP=1 to draw with the software one.",
        )
        host.shutdown()
        return RendererApi.RUN_FAILED
    }

    fileDialogs.rootWindow = { window.handle }

    // The window's own light or dark, for what the system draws of it: the caption of a window
    // with the system's chrome, and the frame of every window. Given before the first frame
    // from the theme the first batch set, and again whenever the application changes it.
    val colorScheme = WindowSchemeFollower { scheme ->
        setWindowColorScheme(windowAppearanceCode(scheme))
    }
    colorScheme.follow(host.table.theme)

    // The application's own tree, drawn by the same interpreter every other platform uses.
    // Whatever the chrome, a `WindowDrag(true)` node moves the window (FR-19.9) and the
    // system's light or dark setting is followed while the window is open (FR-44).
    if (systemChrome) {
        window.setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { host.table.theme }.collect { colorScheme.follow(it) }
            }
            WindowsPlatformLocals(window) { WindowContent(host) }
        }
    } else {
        val actions = dev.darkpyonix.composerust.runtime.WindowActions(
            minimise = { Win32Window.action(0) },
            maximise = { Win32Window.action(1) },
            close = { Win32Window.action(2) },
            maximised = window.maximised,
        )
        window.setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { host.table.theme }.collect { colorScheme.follow(it) }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                dev.darkpyonix.composerust.runtime.LocalWindowActions provides actions,
            ) {
                WindowsPlatformLocals(window) {
                    WindowContent(
                        host,
                        caption = dev.darkpyonix.composerust.runtime.WindowCaption(
                            height = WINDOWS_CAPTION_HEIGHT,
                            fullSize = fullSize,
                        ),
                    )
                }
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

/**
 * What every Windows window gives its content, whatever its chrome: a `WindowDrag(true)` node
 * moves [window] (FR-19.9, through [windowsWindowDrag]), and the system's dark setting is
 * followed while the window is open (FR-44).
 */
@androidx.compose.runtime.Composable
internal fun WindowsPlatformLocals(window: Win32Window, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        dev.darkpyonix.composerust.runtime.LocalWindowDrag provides { windowsWindowDrag(window) },
        dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver provides { rememberWin32SystemDark().value },
    ) {
        content()
    }
}

/**
 * What a press on a `WindowDrag(true)` node does to its window: the system's own move, from
 * the press. A check replaces it to see which window a press reached without moving one.
 */
internal var windowsWindowDrag: (Win32Window) -> Unit = { it.beginDrag() }
