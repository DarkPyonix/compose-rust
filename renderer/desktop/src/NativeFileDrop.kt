package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node

// Files let go over a window of our own, delivered to the node they were let go over.
//
// The toolkit's window did this through the toolkit's drag and drop, which knows which
// component is under the pointer. A window of our own is told where the files are and
// nothing else, so the nodes that take files say where they are and this finds the one
// under the point. No toolkit type appears here, which is what lets the same file serve
// every window that has no toolkit.

/**
 * The nodes that take files, and which of them the files are over.
 *
 * One window's worth, because a drag has one place it is over at a time. Called only from
 * the thread the scene runs on, which is the thread every event of the window arrives on.
 */
internal object NativeFileDrops {

    private class Target(
        val nodeId: Int,
        var bounds: Rect,
        val entered: Long?,
        val dropped: Long?,
        val dispatcher: EventDispatcher,
    )

    // Insertion order is composition order, and a node composed later is drawn over one
    // composed earlier, so the last that contains a point is the one on top.
    private val targets = LinkedHashMap<Int, Target>()
    private var over: Int? = null

    fun register(
        nodeId: Int,
        bounds: Rect,
        entered: Long?,
        dropped: Long?,
        dispatcher: EventDispatcher,
    ) {
        val known = targets[nodeId]
        if (known != null && known.entered == entered && known.dropped == dropped) {
            known.bounds = bounds
            return
        }
        targets.remove(nodeId)
        targets[nodeId] = Target(nodeId, bounds, entered, dropped, dispatcher)
    }

    fun unregister(nodeId: Int) {
        targets.remove(nodeId)
        if (over == nodeId) over = null
    }

    private fun at(position: Offset): Target? =
        targets.values.lastOrNull { it.bounds.contains(position) }

    /** Files are over [position]. Said once when they come over a node, not on every move. */
    fun enter(position: Offset) {
        val target = at(position)
        if (target?.nodeId == over) return
        over = target?.nodeId
        val handler = target?.entered ?: return
        target.dispatcher.dispatch(HostEvent.FilesEntered(target.nodeId, handler))
    }

    /** The files left the window without being let go. */
    fun leave() {
        over = null
    }

    /** Whether the files were taken by a node. */
    fun drop(position: Offset, paths: List<String>): Boolean {
        over = null
        val target = at(position) ?: return false
        val handler = target.dropped ?: return false
        if (paths.isEmpty()) return false
        target.dispatcher.dispatch(
            HostEvent.FilesDropped(target.nodeId, handler, paths.joinToString("\u0000")),
        )
        return true
    }
}

/** Makes a file drop target of a node that asked to be one, and leaves the rest alone. */
@Composable
internal fun Modifier.nativeFileDrop(node: Node, dispatcher: EventDispatcher): Modifier {
    if (node.widget != WidgetKind.FileDropTarget) return this
    val entered = node.handler(PropertyKind.OnFilesEntered)
    val dropped = node.handler(PropertyKind.OnFilesDropped)
    DisposableEffect(node.id) {
        onDispose { NativeFileDrops.unregister(node.id) }
    }
    return onGloballyPositioned { coordinates ->
        NativeFileDrops.register(node.id, coordinates.boundsInWindow(), entered, dropped, dispatcher)
    }
}

/** Splits what the window carried into paths. They are separated by the byte no path holds. */
internal fun splitDroppedPaths(carried: String): List<String> =
    carried.split('\u0000').filter { it.isNotEmpty() }

/** Routes the window's file events to the node under the files. */
internal fun routeFileDrop(kind: Int, position: Offset, carried: () -> String) {
    when (kind) {
        WindowEvent.FILES_ENTERED -> NativeFileDrops.enter(position)
        WindowEvent.FILES_EXITED -> NativeFileDrops.leave()
        WindowEvent.FILES_DROPPED -> NativeFileDrops.drop(position, splitDroppedPaths(carried()))
    }
}
