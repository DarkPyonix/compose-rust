@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import org.thisisthepy.compose.window.FileDialogOutcome as SheetOutcome
import org.thisisthepy.compose.window.FileDialogRequest as SheetRequest
import org.thisisthepy.compose.window.macos.MacosFileDialogs
import platform.AppKit.NSApplication
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowWillCloseNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.native.ref.WeakReference

/**
 * The file dialogs of the macOS renderer: `NSOpenPanel` and `NSSavePanel` as sheets on the
 * owning `NSWindow`, through the fork's [MacosFileDialogs].
 *
 * Installed in [FileDialogs.platform] before the Host is made, because the interpreter's
 * centre takes the platform when the node table is created. What the windows are is only
 * known later, so it is handed in afterwards: [nodeWindow] gives the platform window of a
 * `Window` node (the window manager's handle, read through the centre's resolver), and
 * [rootWindow] the window of node 0, the implicit single window, or in a multi-window run the
 * window in use.
 */
internal class MacosFileDialogPlatform : FileDialogPlatform {
    /** Gives the platform window of a `Window` node, or null. */
    var nodeWindow: (Int) -> Any? = { null }

    /** Gives the window a request on node 0 is shown on, or null when there is none yet. */
    var rootWindow: () -> NSWindow? = { null }

    private val inner = SheetFileDialogPlatform<NSWindow>(
        windowFor = { handle, node ->
            nativeWindowOf(handle) ?: when {
                node > 0 -> nativeWindowOf(nodeWindow(node))
                node == 0 -> rootWindow()
                else -> null
            }
        },
        sheetsFor = ::macosSheets,
        later = ::onMainQueueLater,
    )

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) =
        inner.show(window, request, onResult)

    override fun cancelWindow(window: Any?) = inner.cancelWindow(window)
}

private fun nativeWindowOf(handle: Any?): NSWindow? = when (handle) {
    is MacosPlatformWindow -> handle.nativeWindow
    is NSWindow -> handle
    else -> null
}

/**
 * The window a multi-window run shows a node 0 request on: the key window when it is one of
 * [windows], otherwise the first of them.
 */
internal fun keyOrFirstWindow(windows: List<NSWindow>): NSWindow? {
    val key = NSApplication.sharedApplication.keyWindow
    return windows.firstOrNull { it === key } ?: windows.firstOrNull()
}

/**
 * One window's sheets. The window is held weakly, so a window that is closed is not kept alive
 * by the dialogs it once showed, and its closing ends them: every open and waiting request is
 * cancelled and the window is forgotten.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private fun macosSheets(window: NSWindow, gone: () -> Unit): WindowSheets {
    val ref = WeakReference(window)
    val dialogs = MacosFileDialogs { ref.get() }
    var observer: Any? = null
    val sheets = object : WindowSheets {
        override fun show(request: SheetRequest, onResult: (SheetOutcome) -> Unit) =
            dialogs.show(request, onResult)

        override fun cancelAll() {
            observer?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
            observer = null
            dialogs.cancelAll()
        }
    }
    observer = NSNotificationCenter.defaultCenter.addObserverForName(
        name = NSWindowWillCloseNotification,
        `object` = window,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        gone()
        sheets.cancelAll()
    }
    return sheets
}

/** Runs [block] on the main queue after [WINDOW_WAIT_MILLIS], once the run loop is turning. */
private fun onMainQueueLater(block: () -> Unit) {
    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW, WINDOW_WAIT_MILLIS * 1_000_000L),
        dispatch_get_main_queue(),
        block,
    )
}
