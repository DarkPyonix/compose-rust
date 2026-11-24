@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import org.thisisthepy.compose.window.FileDialogOutcome as SheetOutcome
import org.thisisthepy.compose.window.FileDialogRequest as SheetRequest
import org.thisisthepy.compose.window.windows.Win32Loop
import org.thisisthepy.compose.window.windows.WindowsFileDialogs

/**
 * The file dialogs of the Windows renderer: `IFileOpenDialog` and `IFileSaveDialog`
 * owned by the request's window, through the fork's [WindowsFileDialogs], which shows them
 * without stopping the message pump and answers on the window's thread.
 *
 * The rules shared with macOS (which window a request belongs to, waiting for a window that
 * is not open yet, the translation between the protocol and the fork) are
 * [SheetFileDialogPlatform]'s. Windows are keyed by their HWND.
 *
 * Installed in [FileDialogs.platform] before the Host is made, as on macOS; [nodeWindow] and
 * [rootWindow] are handed in once the windows exist. The loop calls [deliverWindowsFileDialogs]
 * after every pump: that is when the fork hands its answers over, and when a request waiting
 * for its window looks again.
 */
internal class WindowsFileDialogPlatform : FileDialogPlatform {
    /** Gives the platform window of a `Window` node, or null. */
    var nodeWindow: (Int) -> Any? = { null }

    /** Gives the window a request on node 0 is shown on, or null when there is none yet. */
    var rootWindow: () -> COpaquePointer? = { null }

    private class Later(val due: TimeSource.Monotonic.ValueTimeMark, val block: () -> Unit)

    private val later = ArrayList<Later>()

    private val inner = SheetFileDialogPlatform<Long>(
        windowFor = { handle, node ->
            hwndOf(handle) ?: when {
                node > 0 -> hwndOf(nodeWindow(node))
                node == 0 -> rootWindow()?.toLong()?.takeIf { it != 0L }
                else -> null
            }
        },
        sheetsFor = { hwnd, _ -> windowsSheets(hwnd) },
        later = { block -> later += Later(TimeSource.Monotonic.markNow() + WINDOW_WAIT_MILLIS.milliseconds, block) },
    )

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) =
        inner.show(window, request, onResult)

    override fun cancelWindow(window: Any?) = inner.cancelWindow(window)

    /** Runs the looks for a window that are due. */
    fun turn() {
        if (later.isEmpty()) return
        val due = later.filter { it.due.hasPassedNow() }
        later.removeAll(due)
        for (entry in due) entry.block()
    }
}

/** The platform the loops deliver to, once [runRenderer] installed it. */
internal var installedWindowsFileDialogs: WindowsFileDialogPlatform? = null

/**
 * Called after every pump of a Windows loop: the fork hands over the dialogs that ended, each
 * exactly once on this thread, and a request still waiting for its window looks again.
 */
internal fun deliverWindowsFileDialogs() {
    Win32Loop.deliverFileDialogs()
    installedWindowsFileDialogs?.turn()
}

/** The HWND of a window handle the window manager or the single window gave, or null. */
private fun hwndOf(handle: Any?): Long? = when (handle) {
    is WindowsPlatformWindow -> handle.handle.toLong()
    is Win32Window -> handle.handle.toLong()
    else -> null
}?.takeIf { it != 0L }

/**
 * One window's dialogs. The fork keeps that window's requests in order, one dialog at a time;
 * a window that is gone (its HWND no longer a window) answers what comes as unavailable.
 */
private fun windowsSheets(hwnd: Long): WindowSheets {
    val dialogs = WindowsFileDialogs {
        hwnd.toCPointer<CPointed>()?.takeIf { platform.windows.IsWindow(it.reinterpret()) != 0 }
    }
    return object : WindowSheets {
        override fun show(request: SheetRequest, onResult: (SheetOutcome) -> Unit) = dialogs.show(request, onResult)

        override fun cancelAll() = dialogs.cancelAll()
    }
}
