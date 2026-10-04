package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.runtime.LocalWindowActions
import dev.darkpyonix.composerust.runtime.WindowActions
import dev.darkpyonix.composerust.runtime.WindowCaption

// What every window of our own has in common, so that each platform's file is only what is
// different about its window: the things the application asked of it, the hooks that make
// the tree behave as it does on a desktop, and the content that goes inside.

/**
 * Installs what the tree reads from the platform, before the Host starts: its first batch
 * may already ask.
 */
internal fun installNativeWindowHooks(backdropSupported: Boolean) {
    dev.darkpyonix.composerust.design.installPlatformUiFamily()
    dev.darkpyonix.composerust.ui.installReducedMotion()
    dev.darkpyonix.composerust.ui.installHighContrast()
    dev.darkpyonix.composerust.ui.installResizeCursor()
    dev.darkpyonix.composerust.ui.node.platformFileDrop = { modifier, node, dispatcher ->
        modifier.nativeFileDrop(node, dispatcher)
    }
    dev.darkpyonix.composerust.runtime.platformBacksWindowWithMaterial = { backdropSupported }
}

/**
 * The application's tree inside a window of our own.
 *
 * [overlay] is drawn last, over the content: the resize edges of a window with no frame of
 * the system's. [drag] is drawn first, under it, so a widget in the caption takes the press
 * and the strip only sees the room around it.
 */
@Suppress("DEPRECATION")
@Composable
internal fun NativeWindowContent(
    host: ComposeRustHost,
    caption: WindowCaption,
    actions: WindowActions?,
    drag: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    CompositionLocalProvider(
        LocalSystemDarkObserver provides { rememberSystemDark().value },
        LocalClipboard provides WindowClipboard,
        LocalClipboardManager provides WindowClipboardManagerInstance,
        LocalWindowActions provides actions,
    ) {
        Box(Modifier.fillMaxSize()) {
            drag()
            ComposeRustContent(host, Modifier.fillMaxSize(), caption = caption)
            overlay()
        }
    }
}

/**
 * The strip across the top of a window with no frame of the system's that moves it when
 * pressed, and maximises it when pressed twice.
 *
 * Drawn under the content, so a widget in the caption takes the press and the strip only
 * sees the room around it. The move itself is the window manager's, handed over at the
 * press: a drag measured here would lag it.
 */
@Composable
internal fun NativeCaptionDrag(height: androidx.compose.ui.unit.Dp, actions: WindowActions?) {
    val lastPress = androidx.compose.runtime.remember { LongArray(1) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val now = System.nanoTime()
                    if (now - lastPress[0] < DOUBLE_PRESS_NANOS) {
                        lastPress[0] = 0L
                        actions?.maximise?.invoke()
                    } else {
                        lastPress[0] = now
                        beginNativeWindowDrag(0)
                    }
                }
            },
    )
}

private const val DOUBLE_PRESS_NANOS = 400_000_000L

/** How wide the invisible strip along each edge of a window with no frame is. */
private val EDGE_GRIP = 6.dp

/**
 * The eight edges of a window with no frame of the system's, as things to press.
 *
 * Drawn last so they sit over the content, and as wide as a pointer rather than as anything
 * visible. The resize is the window manager's, handed over at the press.
 */
@Composable
internal fun BoxScope.NativeResizeEdges() {
    // The numbers are the ones beginNativeWindowDrag takes: left, right, top, bottom, then
    // the four corners.
    Edge(1, Modifier.fillMaxHeight().width(EDGE_GRIP), Alignment.CenterStart)
    Edge(2, Modifier.fillMaxHeight().width(EDGE_GRIP), Alignment.CenterEnd)
    Edge(3, Modifier.fillMaxWidth().height(EDGE_GRIP), Alignment.TopCenter)
    Edge(4, Modifier.fillMaxWidth().height(EDGE_GRIP), Alignment.BottomCenter)
    Edge(5, Modifier.size(EDGE_GRIP), Alignment.TopStart)
    Edge(6, Modifier.size(EDGE_GRIP), Alignment.TopEnd)
    Edge(7, Modifier.size(EDGE_GRIP), Alignment.BottomStart)
    Edge(8, Modifier.size(EDGE_GRIP), Alignment.BottomEnd)
}

@Composable
private fun BoxScope.Edge(edge: Int, size: Modifier, alignment: Alignment) {
    Box(
        Modifier.align(alignment).then(size).pointerInput(edge) {
            awaitEachGesture {
                awaitFirstDown()
                beginNativeWindowDrag(edge)
            }
        },
    )
}
