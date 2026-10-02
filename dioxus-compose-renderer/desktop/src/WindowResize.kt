package dioxus.compose.ui.platform

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Window as AwtWindow

/**
 * Which edge of the window a drag is pulling.
 *
 * Corners carry both axes, because a corner drag moves two edges at once and answering it
 * as one edge is what makes a corner feel like it sticks.
 */
internal enum class ResizeEdge(val horizontal: Int, val vertical: Int) {
    Left(-1, 0),
    Right(1, 0),
    Top(0, -1),
    Bottom(0, 1),
    TopLeft(-1, -1),
    TopRight(1, -1),
    BottomLeft(-1, 1),
    BottomRight(1, 1),
}

/** Where a window is and how large, in the pixels AWT measures it in. */
internal data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Where the window ends up when [edge] is pulled by [dx], [dy].
 *
 * Pure, because this is the part that can be wrong in a way nobody sees until they try to
 * resize a window on a platform the author does not run. Pulling a leading edge moves the
 * window as well as sizing it, and the two have to agree or the opposite edge creeps.
 *
 * A window is never taken below [minWidth] by [minHeight]. Clamping the size alone would
 * let a leading edge keep travelling after the window stopped shrinking, which drags the
 * whole window sideways; the position is held with it.
 */
internal fun resize(
    from: WindowBounds,
    edge: ResizeEdge,
    dx: Int,
    dy: Int,
    minWidth: Int,
    minHeight: Int,
): WindowBounds {
    var x = from.x
    var y = from.y
    var width = from.width
    var height = from.height

    when (edge.horizontal) {
        -1 -> {
            val wanted = (width - dx).coerceAtLeast(minWidth)
            x += width - wanted
            width = wanted
        }
        1 -> width = (width + dx).coerceAtLeast(minWidth)
    }
    when (edge.vertical) {
        -1 -> {
            val wanted = (height - dy).coerceAtLeast(minHeight)
            y += height - wanted
            height = wanted
        }
        1 -> height = (height + dy).coerceAtLeast(minHeight)
    }
    return WindowBounds(x, y, width, height)
}

/** How wide the invisible strip along each edge is. */
private val GRIP: Dp = 6.dp

/**
 * The eight edges of an undecorated window, as things to drag.
 *
 * A window with no system frame has no system resize either, and a window that cannot be
 * resized is not a desktop window. macOS never gets these: it keeps its real title bar and
 * the system resizes it.
 *
 * The strips are drawn last so they sit over the content, and they are the width of a
 * pointer rather than of anything visible, so what is underneath still reads as the edge
 * of the application.
 */
@Composable
internal fun WindowResizeEdges(window: AwtWindow?, minWidth: Int, minHeight: Int) {
    if (window == null) return
    Grip(window, ResizeEdge.Left, Modifier.fillMaxHeight().width(GRIP), Alignment.CenterStart, minWidth, minHeight)
    Grip(window, ResizeEdge.Right, Modifier.fillMaxHeight().width(GRIP), Alignment.CenterEnd, minWidth, minHeight)
    Grip(window, ResizeEdge.Top, Modifier.fillMaxWidth().height(GRIP), Alignment.TopCenter, minWidth, minHeight)
    Grip(window, ResizeEdge.Bottom, Modifier.fillMaxWidth().height(GRIP), Alignment.BottomCenter, minWidth, minHeight)
    Grip(window, ResizeEdge.TopLeft, Modifier.width(GRIP).height(GRIP), Alignment.TopStart, minWidth, minHeight)
    Grip(window, ResizeEdge.TopRight, Modifier.width(GRIP).height(GRIP), Alignment.TopEnd, minWidth, minHeight)
    Grip(window, ResizeEdge.BottomLeft, Modifier.width(GRIP).height(GRIP), Alignment.BottomStart, minWidth, minHeight)
    Grip(window, ResizeEdge.BottomRight, Modifier.width(GRIP).height(GRIP), Alignment.BottomEnd, minWidth, minHeight)
}

@Composable
private fun Grip(
    window: AwtWindow,
    edge: ResizeEdge,
    modifier: Modifier,
    alignment: Alignment,
    minWidth: Int,
    minHeight: Int,
) {
    Box(
        modifier
            .then(Modifier)
            .pointerInput(edge) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Read the window's own bounds each time rather than accumulating.
                    // A drag that is applied to a remembered starting size drifts as soon
                    // as anything else moves the window, and something always does.
                    val next = resize(
                        WindowBounds(window.x, window.y, window.width, window.height),
                        edge,
                        dragAmount.x.toInt(),
                        dragAmount.y.toInt(),
                        minWidth,
                        minHeight,
                    )
                    window.setBounds(next.x, next.y, next.width, next.height)
                }
            },
        contentAlignment = alignment,
    ) {}
}
