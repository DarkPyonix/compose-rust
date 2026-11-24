package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.runtime.WindowContent
import kotlinx.coroutines.awaitCancellation

/**
 * A [WindowBackend] for paths that own a single platform window.
 *
 * The first `Window` node claims that window and its id is published as snapshot state so the
 * single Compose scene draws only that node's children. Any second or subsequent `Window` node
 * is refused (`supportsMultipleWindows = false`), causing [WindowManager] to emit
 * `HostEvent.WindowUnavailable` once for that node without opening or stacking another view.
 */
internal class SingleWindowBackend(
    private val onTitle: (String) -> Unit = {},
) : WindowBackend {
    override val supportsMultipleWindows: Boolean = false

    private val activeNodeIdState = mutableStateOf(0)
    private var current: Handle? = null

    /** The `Window` node currently bound to the single platform window, or 0 when none is open. */
    val activeWindowNodeId: Int
        get() = activeNodeIdState.value

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        if (current != null) return null
        val handle = Handle(listener)
        current = handle
        activeNodeIdState.value = config.nodeId
        if (config.title.isNotEmpty()) onTitle(config.title)
        return handle
    }

    override fun setApplicationListener(listener: ApplicationListener?) {}

    private inner class Handle(
        @Suppress("unused") private var listener: PlatformWindowListener,
    ) : PlatformWindow {
        private var closed = false

        override fun setListener(listener: PlatformWindowListener) {
            this.listener = listener
        }

        override fun setTitle(title: String) {
            if (!closed) onTitle(title)
        }

        override fun setPosition(x: Float, y: Float) {}

        override fun setContentSize(width: Float, height: Float) {}

        override fun bounds(): ScreenBounds? = null

        override fun focus() {}

        override val isKey: Boolean
            get() = !closed

        override fun pointerOnScreen(): ScreenPointer? = null

        override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false

        override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
            awaitCancellation()
        }

        override fun close() {
            if (closed) return
            closed = true
            if (current === this) {
                current = null
                activeNodeIdState.value = 0
            }
        }
    }
}

/**
 * Connects a [ComposeRustHost] to [WindowManager] through [SingleWindowBackend] when the
 * application runs in [WindowMode.Windows]. Single-window applications keep their direct path
 * untouched and never attach a manager.
 */
internal class SingleWindowSession(
    val host: ComposeRustHost,
    onExit: () -> Unit = {},
    onTitle: (String) -> Unit = {},
) {
    val isMultiWindowMode: Boolean = host.table.window?.mode == WindowMode.Windows
    val backend: SingleWindowBackend? = if (isMultiWindowMode) SingleWindowBackend(onTitle = onTitle) else null
    val manager: WindowManager? = backend?.let { WindowManager(host, it, onExit = onExit) }

    val activeWindowNodeId: Int
        get() = backend?.activeWindowNodeId ?: 0

    fun attach() {
        manager?.attach()
    }

    fun shutdown() {
        manager?.shutdown()
    }
}

/**
 * Remembers and attaches a [SingleWindowSession] for [host], shutting it down when composition
 * leaves. Used by single-window shells that do not manage the session outside composition.
 */
@Composable
internal fun rememberSingleWindowSession(
    host: ComposeRustHost,
    onExit: () -> Unit = {},
    onTitle: (String) -> Unit = {},
): SingleWindowSession {
    val session = remember(host) {
        SingleWindowSession(host, onExit = onExit, onTitle = onTitle).also { it.attach() }
    }
    DisposableEffect(session) {
        onDispose { session.shutdown() }
    }
    return session
}

/**
 * Renders [session]'s content into a single window.
 *
 * In single-window mode, delegates directly to [WindowContent] for root window 0.
 * In multi-window mode, drives frame requests once for the Host and renders only the active
 * `Window` node's children so refused `Window` nodes are never stacked into the scene.
 */
@Composable
internal fun SingleWindowHostContent(
    session: SingleWindowSession,
    modifier: Modifier = Modifier,
    caption: WindowCaption = WindowCaption.None,
) {
    if (!session.isMultiWindowMode) {
        WindowContent(session.host, modifier = modifier, caption = caption)
        return
    }
    val frames = LocalFrameRequests.current
    val manager = session.manager
    LaunchedEffect(session.host, frames, manager) {
        serveFrameRequests(
            requests = frames.counter,
            awaitFrame = { onFrame -> withFrameNanos(onFrame) },
            serve = { frameTimeNanos ->
                session.host.renderFrame(frameTimeNanos)
                manager?.flushFrame()
            },
            alreadyServed = if (session.host.startedWith === frames) {
                session.host.requestsBeforeStart
            } else {
                frames.counter.value
            },
        )
    }
    val activeNodeId = session.activeWindowNodeId
    if (activeNodeId != 0) {
        WindowContent(session.host, modifier = modifier, caption = caption, windowNodeId = activeNodeId)
    } else {
        Box(Modifier.fillMaxSize().then(modifier))
    }
}
