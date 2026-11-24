@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.darkpyonix.composerust.protocol.ColorScheme
import kotlinx.coroutines.awaitCancellation

/**
 * The browser's single-window [WindowBackend].
 *
 * A browser tab is one document and cannot open a second window sharing the same WebAssembly
 * memory. The first `Window` node claims the page canvas; any subsequent `Window` node is
 * refused so [WindowManager] emits `WindowUnavailable` once for that node.
 */
internal class WebWindowBackend(
    private val applyTitle: (String) -> Unit = ::setDocumentTitle,
) : WindowBackend {
    /** The `Window` node currently rendered in the viewport, or 0 when none is open. */
    var activeNodeId: Int by mutableIntStateOf(0)
        private set

    override val supportsMultipleWindows: Boolean = false

    override fun open(
        config: PlatformWindowConfig,
        listener: PlatformWindowListener,
    ): PlatformWindow? {
        if (activeNodeId != 0) return null
        activeNodeId = config.nodeId
        if (config.title.isNotEmpty()) applyTitle(config.title)
        return WebPageWindow(
            nodeId = config.nodeId,
            applyTitle = applyTitle,
            onClose = { id -> if (activeNodeId == id) activeNodeId = 0 },
        )
    }

    // A page has no application around it: nothing reopens it without a window, and the
    // browser never makes a window the page did not ask for, so there is nothing to report.
    override fun setApplicationListener(listener: ApplicationListener?) {}
}

private class WebPageWindow(
    private var nodeId: Int,
    private val applyTitle: (String) -> Unit,
    private val onClose: (Int) -> Unit,
) : PlatformWindow {
    private var closed = false

    override fun setListener(listener: PlatformWindowListener) {}

    override fun setTitle(title: String) {
        if (!closed && title.isNotEmpty()) applyTitle(title)
    }

    override fun setPosition(x: Float, y: Float) {}

    override fun setContentSize(width: Float, height: Float) {}

    override fun bounds(): ScreenBounds? = null

    override fun focus() {}

    override val isKey: Boolean get() = !closed

    override fun pointerOnScreen(): ScreenPointer? = null

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false

    override fun setColorScheme(scheme: ColorScheme) {}

    override fun adoptedBy(nodeId: Int) {
        this.nodeId = nodeId
    }

    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        awaitCancellation()
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose(nodeId)
    }
}

@Suppress("UNUSED_PARAMETER")
private fun setDocumentTitle(title: String): Unit = js(
    """{
        try {
            if (typeof document !== 'undefined') document.title = title;
        } catch (e) {
        }
    }""",
)
