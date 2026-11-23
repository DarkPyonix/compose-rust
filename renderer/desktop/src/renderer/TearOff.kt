package dev.darkpyonix.composerust.runtime

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable

// Pulling a node that carries a `TearOffKey` out into a window of its own.
//
// The interpreter only recognises the gesture; what a drag out of a window is belongs to the
// platform. A desktop window system asks the application for a window and moves it with the
// pointer, Android starts a system drag that may open an Activity, and iPadOS recognises the
// drag itself and only asks which node is under it. Each platform window provides one
// [TearOffDrag]; where there is none, a keyed node carries nothing for it and behaves like any
// other node.

/** What a platform is told when the user pulls a keyed node out of its window. */
class TearOffRequest(
    /** The node carrying the key. */
    val nodeId: Int,
    /** The `Window` node the node is drawn in. */
    val windowId: Int,
    /** The node's `TearOffKey`: the restore key of the window it becomes. */
    val key: String,
    /** The pointer holding the node, as the Host's pointer events name it (1 for the mouse). */
    val pointerId: Int,
    /** Where the node was pressed, in dp from its top left corner. */
    val x: Float,
    val y: Float,
    /** The node's size in dp. */
    val width: Float,
    val height: Float,
    /** Pixels per dp in the window the node is drawn in. */
    val density: Float,
)

/** Which gesture pulls a node out, which is the platform's to say. */
enum class TearOffTrigger {
    /** Held still for a long press, then dragged past the touch slop (Android). */
    LongPressThenDrag,

    /** Pressed, then dragged until the pointer leaves the window (a desktop window). */
    LeaveWindow,

    /**
     * The platform's own gesture recogniser starts the drag (iPadOS' drag interaction) and asks
     * [TearOffTargets] which keyed node is under it. The interpreter only records where keyed
     * nodes are.
     */
    PlatformGesture,
}

/** How a platform window pulls a keyed node out into a window of its own. */
interface TearOffDrag {
    val trigger: TearOffTrigger

    /**
     * Starts the platform's drag for [request]. Called at most once per gesture, on the UI
     * thread, while the pointer is still held. Returns whether a drag started; when none did,
     * nothing was sent and the node stays where it is.
     */
    fun start(request: TearOffRequest): Boolean
}

/**
 * The tear-off of the window being composed, or null where the platform cannot pull a node out
 * of it. A platform window that has one may provide it here, or through its node table's
 * [NodeTable.tearOffDrag] for every window at once; this one wins.
 */
val LocalTearOffDrag = staticCompositionLocalOf<TearOffDrag?> { null }

/**
 * Where the keyed nodes of each window are, for a platform whose own gesture recogniser starts
 * the drag ([TearOffTrigger.PlatformGesture]). Bounds are in the pixels of the window's content.
 */
internal object TearOffTargets {
    private class Target(
        val key: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val depth: Int,
    )

    private val targets = HashMap<Long, Target>()

    private fun slot(windowId: Int, nodeId: Int): Long = (windowId.toLong() shl 32) or (nodeId.toLong() and 0xFFFFFFFFL)

    fun update(windowId: Int, nodeId: Int, key: String, left: Float, top: Float, right: Float, bottom: Float, depth: Int) {
        targets[slot(windowId, nodeId)] = Target(key, left, top, right, bottom, depth)
    }

    fun remove(windowId: Int, nodeId: Int) {
        targets.remove(slot(windowId, nodeId))
    }

    /** The key of the deepest keyed node of [windowId] under ([x], [y]), in its content's pixels, or null. */
    fun keyAt(windowId: Int, x: Float, y: Float): String? {
        var best: Target? = null
        for ((id, target) in targets) {
            if ((id ushr 32).toInt() != windowId) continue
            if (x < target.left || x >= target.right || y < target.top || y >= target.bottom) continue
            if (best == null || target.depth > best.depth) best = target
        }
        return best?.key
    }
}

/** Where a keyed node was last laid out, which the leave-the-window gesture measures against. */
private class TearOffGeometry {
    var coordinates: LayoutCoordinates? = null
}

/**
 * Lets the user pull [node] out into a window of its own, when it carries a `TearOffKey`, the
 * application runs several windows, and the window it is drawn in ([windowId]) has a
 * [TearOffDrag]. Anywhere else the modifier is returned as it was and the node carries nothing
 * for it.
 *
 * The gesture never takes a tap: a press released before the gesture completes goes on to the
 * node's own click. A press that moves before the long press is a scroll or another drag and is
 * left to it. A press that a node inside took (a text field's own long press selection, a
 * drag the Host consumed) does not start a drag.
 */
@Composable
internal fun Modifier.tearOff(node: Node, table: NodeTable, windowId: Int, depth: Int): Modifier {
    val key = node.text(PropertyKind.TearOffKey)
    val drag = LocalTearOffDrag.current ?: table.tearOffDrag
    if (key.isEmpty() || drag == null || windowId == 0 || table.window?.mode != WindowMode.Windows) return this
    val nodeId = node.id
    if (drag.trigger == TearOffTrigger.PlatformGesture) {
        DisposableEffect(windowId, nodeId) {
            onDispose { TearOffTargets.remove(windowId, nodeId) }
        }
        return onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            TearOffTargets.update(windowId, nodeId, key, bounds.left, bounds.top, bounds.right, bounds.bottom, depth)
        }
    }
    val geometry = remember(nodeId) { TearOffGeometry() }
    return onGloballyPositioned { geometry.coordinates = it }
        .pointerInput(nodeId, windowId, key, drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val crossed = when (drag.trigger) {
                    TearOffTrigger.LongPressThenDrag -> longPressThenDrag(down)
                    TearOffTrigger.LeaveWindow -> leavingWindow(down, geometry)
                    TearOffTrigger.PlatformGesture -> null
                } ?: return@awaitEachGesture
                val request = TearOffRequest(
                    nodeId = nodeId,
                    windowId = windowId,
                    key = key,
                    pointerId = if (down.type == PointerType.Mouse) 1 else down.id.value.toInt(),
                    x = down.position.x / density,
                    y = down.position.y / density,
                    width = size.width / density,
                    height = size.height / density,
                    density = density,
                )
                // The rest of this press is the drag's: nothing behind the node scrolls with it.
                if (drag.start(request)) crossed.consume()
            }
        }
}

/**
 * Waits for the pointer of [down] to be held still for a long press and then moved past the
 * touch slop, and returns the change that moved it. Null when the press is released, moves
 * before the long press, or is taken by a node inside.
 */
private suspend fun AwaitPointerEventScope.longPressThenDrag(down: PointerInputChange): PointerInputChange? {
    val slop = viewConfiguration.touchSlop
    // A value means the press ended before the long press timed out; null is the long press.
    val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var held = true
        while (held) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            held = change != null && change.pressed && !change.isConsumed &&
                (change.position - down.position).getDistance() <= slop
        }
        true
    }
    if (endedEarly != null) return null
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed) return null
        if ((change.position - down.position).getDistance() > slop) return change
    }
}

/**
 * Waits for the pointer of [down] to leave the window while it is held, and returns the change
 * that left. Null when it is released first. A pointer held on a window keeps reporting outside
 * it, which is what lets the press be followed there.
 */
private suspend fun AwaitPointerEventScope.leavingWindow(down: PointerInputChange, geometry: TearOffGeometry): PointerInputChange? {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return null
        val node = geometry.coordinates?.takeIf { it.isAttached } ?: continue
        val root = node.findRootCoordinates()
        val at = root.localPositionOf(node, change.position)
        if (at.x < 0f || at.y < 0f || at.x >= root.size.width || at.y >= root.size.height) return change
    }
}
