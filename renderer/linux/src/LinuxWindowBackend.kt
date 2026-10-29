@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.thisisthepy.compose.window.TitleBarStyle
import org.thisisthepy.compose.window.WindowChrome
import org.thisisthepy.compose.window.WindowConfig
import org.thisisthepy.compose.window.linux.X11Window
import platform.posix.usleep

/**
 * The window system of the Linux renderer for an application of several windows (FR-53): one
 * [LinuxWindow], an X11 window with a scene of its own, for each `Window` node.
 *
 * The windows of the process share one X11 connection that the fork's window module routes
 * events over by window id, so a single [X11Window.pump] on any one of them services all of
 * them. [run] is that loop: one pump per turn, the shared work (the scene's coroutines and
 * `Dispatchers.Main`) run once, then every window handles what it heard and draws.
 *
 * [content] is what a window draws, given the id of its `Window` node. X11 has no reopen and
 * no restore, so the application listener is never called.
 */
internal class LinuxWindowBackend(
    private val work: FrameDispatcher,
    private val content: (nodeId: Int) -> @Composable () -> Unit,
) : WindowBackend {

    private val windows = ArrayList<LinuxPlatformWindow>()

    /** Any open window's X11 side, for the thread that did not make the work to end the loop's wait. */
    @kotlin.concurrent.Volatile
    private var anyX11: X11Window? = null

    @kotlin.concurrent.Volatile
    private var exiting = false

    /** The number of windows open, so that a test can see that closing them leaves none. */
    internal val openCount: Int get() = windows.size

    /** Once per turn, after the connection was heard and before the windows draw. */
    var onTurn: () -> Unit = {}

    override val supportsMultipleWindows: Boolean get() = true

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        val window = LinuxWindow.open(windowConfigOf(config), work) ?: return null
        window.platformListener = listener
        window.setContent(content(config.nodeId))
        val platform = LinuxPlatformWindow(this, window)
        windows += platform
        anyX11 = window.x11
        return platform
    }

    /** No event of the application as a whole exists on X11, so there is nothing to tell. */
    override fun setApplicationListener(listener: ApplicationListener?) {}

    /** Ends [run] after the turn that is in progress. Any thread may call it. */
    fun requestExit() {
        exiting = true
        anyX11?.wake()
    }

    /** Brings the first window up, for a press on a notification. */
    fun raiseAny() {
        windows.firstOrNull()?.window?.raise()
    }

    internal fun forget(window: LinuxPlatformWindow) {
        windows.remove(window)
        anyX11 = windows.firstOrNull()?.window?.x11
    }

    /**
     * Runs the windows until [requestExit] is called, which the window manager does when the
     * last one went under the `Exit` policy. With no window and `KeepRunning` it waits.
     *
     * The order of a turn is the single window's: hear the server, then the shared work, then
     * each window's events and drawing. A window the work closed is skipped, and one it opened
     * is drawn from the next turn.
     */
    fun run() {
        work.wakeLoop = { anyX11?.wake() }
        try {
            while (!exiting) turn()
        } finally {
            work.wakeLoop = {}
        }
    }

    /**
     * One turn of [run]: hear the server for at most a frame, run the shared work once, then
     * let every window handle what it heard and draw.
     */
    internal fun turn() {
        val pumper = windows.firstOrNull()?.window
        if (pumper == null) {
            // Nothing to hear on: wait out the frame, then run what was sent meanwhile.
            usleep(FRAME_MICROSECONDS)
        } else {
            pumper.x11.pump(FRAME_MILLISECONDS)
        }
        for (window in windows.toList()) window.window.pollFocus()
        onTurn()
        val woken = work.takeWake()
        work.runPending()
        for (window in windows.toList()) {
            if (!window.closed) window.window.processTurn(woken)
        }
    }

    /** Closes whatever is left, and ends the loop. */
    fun closeAll() {
        exiting = true
        for (window in windows.toList()) window.close()
    }

    private fun windowConfigOf(config: PlatformWindowConfig) = WindowConfig(
        title = config.title.ifEmpty { "compose-rust" },
        width = if (config.width > 0f) config.width.toInt() else DEFAULT_WIDTH,
        height = if (config.height > 0f) config.height.toInt() else DEFAULT_HEIGHT,
        minWidth = config.minWidth.toInt(),
        minHeight = config.minHeight.toInt(),
        resizable = config.resizable,
        chrome = when (config.chrome) {
            Chrome.Modern -> WindowChrome.Modern
            Chrome.System -> WindowChrome.System
        },
        titleBar = when (config.titleBar) {
            TitleBar.Normal -> TitleBarStyle.Normal
            TitleBar.Simple -> TitleBarStyle.Simple
        },
        x = if (config.x != null && config.y != null) config.x.toInt() else null,
        y = if (config.x != null && config.y != null) config.y.toInt() else null,
    )

    private companion object {
        const val FRAME_MILLISECONDS = 16L
        const val FRAME_MICROSECONDS = 16_000u
        const val DEFAULT_WIDTH = 520
        const val DEFAULT_HEIGHT = 360
    }
}

/** One window of the Linux backend, in the terms the window manager speaks. */
internal class LinuxPlatformWindow(
    private val backend: LinuxWindowBackend,
    internal val window: LinuxWindow,
) : PlatformWindow {

    internal var closed = false
        private set

    private val x11 get() = window.x11

    override fun setListener(listener: PlatformWindowListener) {
        window.platformListener = listener
    }

    override fun setTitle(title: String) = x11.setTitle(title)

    override fun setMinimumSize(width: Float, height: Float) =
        x11.setMinimumSize(width.toInt(), height.toInt())

    override fun setPosition(x: Float, y: Float) = x11.setPosition(x.toInt(), y.toInt())

    override fun setContentSize(width: Float, height: Float) =
        x11.setContentSize(width.toInt(), height.toInt())

    override fun bounds(): ScreenBounds? = x11.bounds()?.let {
        ScreenBounds(it.x.toFloat(), it.y.toFloat(), it.width.toFloat(), it.height.toFloat())
    }

    override fun contentOrigin(): ScreenPoint? {
        if (closed) return null
        val origin = x11.originOnScreen()
        return ScreenPoint(origin.first.toFloat(), origin.second.toFloat())
    }

    override fun focus() = x11.focus()

    override val isKey: Boolean get() = !closed && x11.isKey

    override fun pointerOnScreen(): ScreenPointer? =
        x11.pointerOnScreen()?.let { ScreenPointer(it.x, it.y, it.primaryDown) }

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean =
        x11.followPointer(anchorX, anchorY)

    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        var waiter: ((Long) -> Unit)? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                waiter = window.addFrameWaiter { nanos ->
                    onFrame(nanos)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        } finally {
            waiter?.let(window::removeFrameWaiter)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        window.platformListener = null
        backend.forget(this)
        window.close()
    }
}
