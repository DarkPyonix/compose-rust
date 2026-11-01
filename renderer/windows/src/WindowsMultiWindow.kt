@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalServesFrames
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.runtime.LocalWindowNodeId
import dev.darkpyonix.composerust.runtime.WindowActions
import dev.darkpyonix.composerust.runtime.WindowCaption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.thisisthepy.compose.window.windows.Win32Loop
import org.thisisthepy.compose.window.windows.Win32System

// Several windows in one process on Windows: one Host, one node table, one message
// loop, one frame driver, and a Win32 window with a scene of its own for every `Window` node.
// A single-window application never comes here (see runRenderer).

/**
 * Runs a multi-window application until the last window is removed (policy `Exit`) or the
 * application asks to quit, and returns the renderer's status.
 */
internal fun runMultiWindow(host: ComposeRustHost): Int {
    val windows = Win32System.windows
    val loop = MultiWindowLoop(host) { windows.postQuit(0) }
    loop.attach()
    if (loop.backend.openCount == 0 && host.roots.isNotEmpty()) {
        // Windows were asked for and none opened. Told to the application as WindowUnavailable
        // where it listens, and to the person here, as a window on its own says it.
        java.lang.System.err.println(
            "compose-rust: this machine has no Direct3D 12 adapter. Where there is no graphics " +
                "card at all, set DXC_D3D12_WARP=1 to draw with the software one.",
        )
        loop.stop()
        return RendererApi.RUN_FAILED
    }
    try {
        var busy = true
        while (windows.quitPosted() == null) busy = loop.step(rest = !busy)
    } finally {
        loop.stop()
    }
    return RendererApi.RUN_OK
}

/**
 * The loop of one thread, the Host's, for a process with several windows:
 *
 * 1. the frame driver's coroutine runs (it serves the Host's frame requests once per process,
 *    [serveWindowFrames]);
 * 2. one pump serves the message queue for every window;
 * 3. every window hears what is its own, draws where it has reason to, and tells the window
 *    manager what happened to it ([WindowsWindowBackend.turn]).
 *
 * The manager opens and closes the windows from the nodes each batch leaves, and ends the loop
 * through [onExit] (the fork's `Win32System.windows.postQuit`): destroying a window never does.
 */
internal class MultiWindowLoop(
    private val host: ComposeRustHost,
    private val requests: FrameRequestSource = FrameRequests.global,
    onExit: () -> Unit,
) {
    private val driver = FrameDispatcher()
    private val frames = CoroutineScope(driver + SupervisorJob())
    val backend = WindowsWindowBackend { config, window -> windowContent(host, config, window) }
    val manager = WindowManager(host, backend, onExit = onExit)

    /** Hooks the manager to the Host's batches and opens the windows the tree already has. */
    fun attach() {
        manager.attach()
        frames.launch {
            serveWindowFrames(host, manager, requests, alreadyServed = host.requestsBeforeStart)
        }
    }

    /**
     * One turn of the loop. [rest] lets the pump wait up to a frame for a message, which a
     * caller does only when the last turn found nothing to do. True when this turn did.
     */
    fun step(rest: Boolean): Boolean {
        driver.runPending()
        if (backend.openCount == 0) {
            // A pump with no window does not wait, and a loop that kept the policy
            // `KeepRunning` with none open would spin.
            Win32Loop.pump(0.0)
            platform.windows.Sleep(IDLE_MILLIS)
            return false
        }
        Win32Loop.pump(if (rest) FRAME_SECONDS else 0.0)
        return backend.turn()
    }

    /** Closes the windows and lets go of the Host. */
    fun stop() {
        frames.cancel()
        manager.shutdown()
        backend.closeAll()
        host.shutdown()
    }
}

/**
 * What the window of a `Window` node draws: the content of that node, in a scene of its own,
 * under the node's id, so the events a window names itself by (its size class, its zoom) carry
 * it. Frame requests are not served here, because the window manager serves them once for
 * every window.
 */
private fun windowContent(
    host: ComposeRustHost,
    config: PlatformWindowConfig,
    window: Win32Window,
): @Composable () -> Unit {
    val nodeId = config.nodeId
    if (config.chrome == Chrome.System) {
        return {
            ForWindow(nodeId) { ComposeRustContent(host) }
        }
    }
    val actions = WindowActions(
        minimise = { window.perform(0) },
        maximise = { window.perform(1) },
        close = { window.perform(2) },
        maximised = window.maximised,
    )
    return {
        ForWindow(nodeId) {
            CompositionLocalProvider(LocalWindowActions provides actions) {
                ComposeRustContent(host, caption = WindowCaption(height = CAPTION_HEIGHT))
            }
        }
    }
}

@Composable
private fun ForWindow(nodeId: Int, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalWindowNodeId provides nodeId,
        LocalServesFrames provides false,
    ) {
        content()
    }
}

private const val FRAME_SECONDS = 0.016
private const val IDLE_MILLIS = 16u

/** Windows 11's caption height, the same number the single window lays its caption out with. */
private val CAPTION_HEIGHT = 32.dp
