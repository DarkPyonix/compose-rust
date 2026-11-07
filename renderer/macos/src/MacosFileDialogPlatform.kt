@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.FileDialogKind
import org.thisisthepy.compose.window.FileDialogFilter as SheetFilter
import org.thisisthepy.compose.window.FileDialogKind as SheetKind
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

/** How long a request waits for its window to be on screen: this many tries, [WINDOW_WAIT_MILLIS] apart. */
internal const val WINDOW_WAIT_ATTEMPTS = 100

/** The pause between two looks for a request's window. */
internal const val WINDOW_WAIT_MILLIS = 50L

/**
 * The sheets of one window, as [SheetFileDialogPlatform] uses them: requests shown one after
 * the other, and a way to end every one of them at once.
 */
internal interface WindowSheets {
    /** Shows [request] without waiting for the user. [onResult] is called once, on the main thread. */
    fun show(request: SheetRequest, onResult: (SheetOutcome) -> Unit)

    /** Ends the open sheet and every waiting request with a cancel, each answered once. */
    fun cancelAll()
}

/**
 * The renderer's file dialogs on top of the fork's per-window sheets.
 *
 * The fork shows a sheet and keeps one window's requests in order; the interpreter's
 * [FileDialogCenter] keeps the protocol's rules. This sits between the two: it finds the
 * window a request belongs to, keeps one [WindowSheets] for each window that has had one, and
 * translates the request and its answer between the two vocabularies.
 *
 * A request can arrive before its window is on screen: the batch that asked for it may be the
 * first one, which is applied before any window exists, or the one that created its `Window`
 * node. So a request whose window is not there yet is looked at again, [attempts] times,
 * through [later], and is answered as unavailable only when the window never comes.
 *
 * Generic in the window type so the rules can be tested without AppKit. [windowFor] turns the
 * handle the centre resolved (and the node the request names) into a window, or null when
 * there is none yet. [sheetsFor] makes the sheets of one window and is given the function to
 * call when that window goes away. Everything is called on the main thread.
 */
internal class SheetFileDialogPlatform<W : Any>(
    private val windowFor: (handle: Any?, node: Int) -> W?,
    private val sheetsFor: (window: W, gone: () -> Unit) -> WindowSheets,
    private val later: (() -> Unit) -> Unit,
    private val attempts: Int = WINDOW_WAIT_ATTEMPTS,
) : FileDialogPlatform {
    private val open = HashMap<W, WindowSheets>()

    /** How many windows have sheets kept for them now. */
    internal val windowCount: Int get() = open.size

    override fun show(window: Any?, request: FileDialogSpec, onResult: (FileDialogOutcome) -> Unit) {
        showWhenThere(window, request.windowNode, sheetRequestOf(request), onResult, attempts)
    }

    private fun showWhenThere(
        handle: Any?,
        node: Int,
        request: SheetRequest,
        onResult: (FileDialogOutcome) -> Unit,
        left: Int,
    ) {
        val target = windowFor(handle, node)
        if (target == null) {
            if (left <= 0) {
                onResult(FileDialogOutcome.Unavailable)
            } else {
                later { showWhenThere(handle, node, request, onResult, left - 1) }
            }
            return
        }
        val sheets = open.getOrPut(target) { sheetsFor(target) { open.remove(target) } }
        sheets.show(request) { outcome -> onResult(outcomeOf(outcome)) }
    }

    override fun cancelWindow(window: Any?) {
        // The implicit root window is never removed, and a handle that was null when the
        // dialog started is ended by the window's own close notification instead.
        if (window == null) return
        val target = windowFor(window, NO_NODE) ?: return
        open.remove(target)?.cancelAll()
    }

    companion object {
        /** Passed as the node when only a handle is known. */
        const val NO_NODE = -1
    }
}

/** The request as the fork's sheets take it. The centre has already normalised it. */
internal fun sheetRequestOf(spec: FileDialogSpec): SheetRequest = SheetRequest(
    kind = when (spec.kind) {
        FileDialogKind.OpenFile -> SheetKind.OpenFile
        FileDialogKind.OpenFiles -> SheetKind.OpenFiles
        FileDialogKind.OpenFolder -> SheetKind.OpenFolder
        FileDialogKind.OpenFolders -> SheetKind.OpenFolders
        FileDialogKind.Save -> SheetKind.Save
    },
    title = spec.title,
    directory = spec.directory,
    name = spec.name,
    filters = spec.filters.map { SheetFilter(it.label, it.extensions) },
    defaultFilter = spec.defaultFilter,
)

/** The sheet's answer as the centre takes it. A filter index below zero means there was no filter. */
internal fun outcomeOf(outcome: SheetOutcome): FileDialogOutcome = when (outcome) {
    is SheetOutcome.Chosen -> FileDialogOutcome.Chosen.ofText(
        outcome.paths,
        outcome.filterIndex.takeIf { it >= 0 },
    )
    SheetOutcome.Cancelled -> FileDialogOutcome.Cancelled
    SheetOutcome.Unavailable -> FileDialogOutcome.Unavailable
}

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
