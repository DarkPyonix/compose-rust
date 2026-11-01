@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.thisisthepy.compose.window.windows.Win32Loop
import platform.windows.ClientToScreen
import platform.windows.POINT

/**
 * The window system of the Windows renderer when one process holds several windows: the
 * [WindowBackend] the window manager opens its windows with.
 *
 * Every window is a [Win32Window]: its own `HWND`, Direct3D 12 swapchain and Compose scene over
 * the Compose fork's Win32 layer, presenting the content of one `Window` node from the node
 * table every window shares. The message queue is the process's, and one pump per turn on any
 * window serves all of them ([turn] is what the loop calls after it); a window finds out what
 * happened to it by asking for its own record, which is why each call selects its window first
 * (`dxc_native_window_select`).
 *
 * Closing is the application's. A window's request to close (the button, Alt+F4, the system
 * menu) is held by the C side and reaches the listener as [PlatformWindowListener.onCloseRequested];
 * the window stays until the manager calls [PlatformWindow.close]. Destroying a window never
 * ends the process either: the loop ends when the manager asks for it, through the fork's
 * quit call.
 *
 * Everything here runs on the thread that opened the first window, which is the Host's.
 */
internal class WindowsWindowBackend(
    /** What a window of this node draws; made by the loop, which holds the Host. */
    private val contentOf: (PlatformWindowConfig, Win32Window) -> (@Composable () -> Unit),
) : WindowBackend {
    private val windows = ArrayList<WindowsPlatformWindow>()
    private val retired = ArrayList<Win32Window>()
    private val started = TimeSource.Monotonic.markNow()
    private var lastNanos = 0L
    private var applicationListener: ApplicationListener? = null

    override val supportsMultipleWindows: Boolean get() = true

    /** How many windows are open. */
    val openCount: Int get() = windows.size

    /** The handles of the open windows, for a check that counts them. */
    val handles: List<COpaquePointer> get() = windows.map { it.handle }

    /** The window of `Window` node [nodeId], or null. */
    fun find(nodeId: Int): WindowsPlatformWindow? = windows.firstOrNull { it.nodeId == nodeId }

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        val window = Win32Window.openOf(config) { frameNanos() } ?: return null
        val platform = WindowsPlatformWindow(this, config.nodeId, window, listener)
        window.setContent(contentOf(config, window))
        windows += platform
        return platform
    }

    override fun setApplicationListener(listener: ApplicationListener?) {
        // Windows has no application-level event of its own (no Dock to reopen from), and the
        // system never makes a window the application has no node for.
        applicationListener = listener
    }

    /**
     * The time of the next frame for every window, in nanoseconds on one clock. Each window
     * drawing from its own counter would let the Host's time go back whenever a different
     * window answered first.
     */
    private fun frameNanos(): Long {
        val now = started.elapsedNow().inWholeNanoseconds + FIRST_FRAME_NANOS
        lastNanos = if (now > lastNanos) now else lastNanos + 1
        return lastNanos
    }

    /**
     * One turn for every window, after the process's queue was served: what each heard and a
     * frame where there is a reason, then what each has to tell its listener. True when
     * anything happened, so the loop does not rest.
     */
    fun turn(): Boolean {
        var busy = false
        // A listener may open or close windows: the ones that were open are served.
        for (platform in windows.toList()) {
            if (platform.closed) continue
            if (platform.turn()) busy = true
        }
        flushRetired()
        return busy
    }

    internal fun remove(platform: WindowsPlatformWindow) {
        windows.remove(platform)
    }

    internal fun retire(window: Win32Window) {
        retired += window
    }

    /** Takes down the scenes of the windows that were destroyed, now that nothing is inside them. */
    fun flushRetired() {
        if (retired.isEmpty()) return
        val done = retired.toList()
        retired.clear()
        for (window in done) window.release()
    }

    /** Closes every window that is still open. */
    fun closeAll() {
        for (platform in windows.toList()) platform.close()
        flushRetired()
    }

    private companion object {
        /** The first frame is not at time zero, as a window on its own draws its first at one step. */
        const val FIRST_FRAME_NANOS = 16_000_000L
    }
}

/** One window of the Windows renderer, as the window manager sees it. */
internal class WindowsPlatformWindow(
    private val backend: WindowsWindowBackend,
    /** The `Window` node this window is for. */
    val nodeId: Int,
    private val window: Win32Window,
    private var listener: PlatformWindowListener,
) : PlatformWindow {
    /** Set when the window is closed by the manager or gone by the system. Nothing is called after. */
    var closed = false
        private set

    /** The `HWND`. */
    val handle: COpaquePointer get() = window.handle

    /** How many pixels a point is on the monitor the window is on. */
    val pixelsPerPoint: Float get() = window.pixelsPerPoint()

    private var reportedKey = false
    private val waiters = ArrayList<CancellableContinuation<Long>>()

    init {
        window.afterFrame = { nanos -> resumeWaiters(nanos) }
        window.frameWanted = {
            waiters.removeAll { !it.isActive }
            waiters.isNotEmpty()
        }
    }

    override fun setListener(listener: PlatformWindowListener) {
        this.listener = listener
    }

    override fun setTitle(title: String) {
        if (closed) return
        window.select()
        memScoped { setWindowTitle(title.cstr.ptr) }
    }

    override fun setMinimumSize(width: Float, height: Float) {
        if (closed) return
        window.select()
        setWindowMinSize(width.roundToInt(), height.roundToInt())
    }

    override fun setPosition(x: Float, y: Float) {
        if (closed) return
        window.select()
        setWindowPosition(x.roundToInt(), y.roundToInt())
    }

    override fun setContentSize(width: Float, height: Float) {
        if (closed) return
        window.select()
        setWindowContentSize(width.roundToInt(), height.roundToInt())
    }

    override fun bounds(): ScreenBounds? {
        if (closed) return null
        window.select()
        return readBounds { x, y, w, h -> windowBounds(x, y, w, h) }
    }

    /**
     * Where the content's top left corner is on the screen: the frame's own corner for a
     * window whose content fills it, the corner of the client area for one with the system's
     * caption. Asked of the system, in the points of the window's monitor.
     */
    override fun contentOrigin(): ScreenPoint? {
        if (closed) return null
        val scale = pixelsPerPoint
        if (scale <= 0f) return null
        return memScoped {
            val point = alloc<POINT>()
            point.x = 0
            point.y = 0
            if (ClientToScreen(window.handle.reinterpret(), point.ptr) == 0) {
                null
            } else {
                ScreenPoint(point.x / scale, point.y / scale)
            }
        }
    }

    override fun focus() {
        if (closed) return
        window.select()
        // A refusal is a flash of the taskbar button and nothing else. The activation, when
        // it comes, is reported from what the system said, so a refused focus reports no change.
        focusWindow()
    }

    override val isKey: Boolean
        get() {
            if (closed) return false
            window.select()
            return windowIsActive()
        }

    override fun pointerOnScreen(): ScreenPointer? {
        if (closed) return null
        window.select()
        return memScoped {
            val x = alloc<FloatVar>()
            val y = alloc<FloatVar>()
            val down = alloc<IntVar>()
            if (readPointerOnScreen(x.ptr, y.ptr, down.ptr)) ScreenPointer(x.value, y.value, down.value != 0) else null
        }
    }

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean {
        if (closed) return false
        window.select()
        return startFollow(anchorX, anchorY)
    }

    /**
     * Suspends until this window draws its next frame. A frame is asked for, so a window that
     * has nothing else to draw still answers; a minimised window has no buffer to draw into
     * and never does, which is why the manager waits for the first of all of them.
     */
    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        if (closed) awaitCancellation()
        val nanos = suspendCancellableCoroutine<Long> { continuation ->
            waiters += continuation
        }
        onFrame(nanos)
    }

    private fun resumeWaiters(nanos: Long) {
        if (waiters.isEmpty()) return
        val ready = waiters.toList()
        waiters.clear()
        for (continuation in ready) {
            if (continuation.isActive) continuation.resume(nanos)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        waiters.clear()
        backend.remove(this)
        window.destroy()
        // The scene is taken down by the loop after the turn, never from inside an event.
        backend.retire(window)
    }

    /** The system took the window without the application asking (logging off, the process's windows closed from outside). */
    private fun goneBySystem() {
        closed = true
        waiters.clear()
        backend.remove(this)
        window.destroy()
        backend.retire(window)
        listener.onClosedBySystem()
    }

    /** What the window heard and drew this turn, then what it has to tell its listener. */
    fun turn(): Boolean {
        val busy = window.turn()
        if (closed) return true
        deliver()
        return busy
    }

    /**
     * Activation, a move, the end of a follow and a request to close, in the order the fork's
     * own platform delivers them, each reported once. After each call to the listener the
     * window may be closed, and the window is selected again because the listener may have
     * opened or closed others.
     */
    private fun deliver() {
        window.select()
        if (windowDestroyed()) {
            goneBySystem()
            return
        }
        val activation = memScoped {
            val active = alloc<IntVar>()
            if (takeActivation(active.ptr)) active.value != 0 else null
        }
        if (activation != null && activation != reportedKey) {
            reportedKey = activation
            window.focused.value = activation
            listener.onFocusChanged(activation)
            if (closed) return
        }
        window.select()
        val moved = readBounds { x, y, w, h -> takeMoved(x, y, w, h) }
        if (moved != null) {
            listener.onMoved(moved)
            // The move is sent with the next frame, so a move that no frame was asked for
            // is not held back.
            FrameRequests.request()
            if (closed) return
        }
        window.select()
        val followEnd = memScoped {
            val cancelled = alloc<IntVar>()
            if (takeFollowEnd(cancelled.ptr)) cancelled.value != 0 else null
        }
        if (followEnd != null) {
            listener.onFollowEnded(followEnd)
            FrameRequests.request()
            if (closed) return
        }
        window.select()
        if (takeCloseRequest()) listener.onCloseRequested()
    }

    private inline fun readBounds(
        call: (
            CPointer<IntVar>,
            CPointer<IntVar>,
            CPointer<IntVar>,
            CPointer<IntVar>,
        ) -> Boolean,
    ): ScreenBounds? = memScoped {
        val x = alloc<IntVar>()
        val y = alloc<IntVar>()
        val width = alloc<IntVar>()
        val height = alloc<IntVar>()
        if (call(x.ptr, y.ptr, width.ptr, height.ptr)) {
            ScreenBounds(x.value.toFloat(), y.value.toFloat(), width.value.toFloat(), height.value.toFloat())
        } else {
            null
        }
    }
}

/** True once the selected window has been destroyed. */
private fun windowDestroyed(): Boolean = Win32Loop.closed
