package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.node.TableError
import kotlin.math.ceil
import kotlin.math.roundToInt

// A `NativeView` node is a place in the window for a platform view the Host made (an
// `NSView`, an `HWND`, an X11 window). Nothing here makes, links or destroys one. The window
// layer of the platform does the placing, clipping, ordering and focusing through
// [NativeViewWindow]; this file decides, from Compose's own layout, where the view goes, what
// part of it shows, and when focus moves, and reports what the window says back to the Host.
//
// The view always draws above the scene. Ancestors that clip (a scroll container, a `Clip`,
// the window's edge) cut it to their bounds, and every Compose surface drawn above it (a
// popover, a menu, a dropdown's list, a dialog, a sheet, a tooltip, a message) is cut out of
// it as a rounded hole the size of the surface and its shadow, so the surface shows through as
// if it were on top. A scrim that dims the whole window covers the whole view.
//
// Placement is done inside Compose's layout pass, from `onGloballyPositioned`, and never posted
// for later: a view placed one frame after the content it sits in trails the content by a frame
// while it scrolls.

/** A rectangle in window pixels, from the top left of the window's content. */
data class NativeRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersect(other: NativeRect): NativeRect {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return NativeRect(left, top, maxOf(0, r - left), maxOf(0, b - top))
    }

    fun intersects(other: NativeRect): Boolean = !intersect(other).isEmpty

    companion object {
        val EMPTY = NativeRect(0, 0, 0, 0)

        /** The whole pixels a Compose rectangle covers: its edges rounded to the nearest pixel. */
        fun of(rect: Rect): NativeRect {
            val left = rect.left.roundToInt()
            val top = rect.top.roundToInt()
            return NativeRect(left, top, rect.right.roundToInt() - left, rect.bottom.roundToInt() - top)
        }
    }
}

/** A hole in a view's visible region: the rectangle of a surface drawn above it, with its corners rounded. */
data class NativeHole(val rect: NativeRect, val cornerRadius: Int)

/**
 * The platform views of one window, as its window layer offers them. On macOS this is the
 * fork's `WindowPlatform.nativeViews`; a platform without such a slot provides none.
 *
 * Ids are the `NativeView` node ids. Positions are in the pixels Compose lays the window out
 * in (`boundsInWindow`). Every call is made on the thread the window runs on.
 */
interface NativeViewWindow {
    /** The handle a view is made against: the content view on macOS. 0 once the window closed. */
    val parentHandle: Long

    /** One of the `NativeViewWire.PARENT_*` values. */
    val parentKind: Int

    /** The window's backing scale: how many pixels make one point. */
    val scale: Float

    /** Puts [handle] into the window as slot [id], hidden, above every slot put there before. */
    fun attach(id: Int, handle: Long): Boolean
    fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int)
    fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>)
    fun setVisible(id: Int, visible: Boolean)
    fun focus(id: Int)
    fun focusScene()
    fun detach(id: Int)

    /**
     * Where the window says what happened: a slot took or lost keyboard focus, and the
     * window's backing scale changed. Set by the renderer while the window's content is up,
     * null otherwise.
     */
    var events: NativeViewWindowEvents?
}

/** What a [NativeViewWindow] tells the renderer. */
interface NativeViewWindowEvents {
    /** Slot [id] took keyboard focus ([focused] true) or lost it. */
    fun focusChanged(id: Int, focused: Boolean)

    /** The window's backing scale is now [scale]. */
    fun scaleChanged(scale: Float)
}

/**
 * Whether this process can place platform views at all. A renderer whose windows have the
 * slot says so before its Host starts; elsewhere every view is refused as unsupported, from
 * the batch that asked for it.
 */
object NativeViewPlatform {
    var available: Boolean = false
}

/** The platform view slot of the window being composed, or null where there is none. */
val LocalNativeViewWindow = staticCompositionLocalOf<NativeViewWindow?> { null }

/** The views of the window being composed, and the surfaces drawn above them. */
internal val LocalNativeViews = staticCompositionLocalOf<NativeViewCoordinator?> { null }

/** One surface drawn above the page: where it is, how round its corners are, and whether it covers the window. */
private class Overlay(val order: Long) {
    var coordinates: LayoutCoordinates? = null
    var hole: NativeHole? = null
    var coversWindow = false
}

/** One `NativeView` node of the window. */
private class Slot(val nodeId: Int) {
    var coordinates: LayoutCoordinates? = null
    var handle = 0L
    var attached = false
    var wantsVisible = true
    var frame: NativeRect? = null
    var clip: NativeRect? = null

    // What the window was last told, so a layout that moved nothing calls nothing.
    var sentFrame: NativeRect? = null
    var sentClip: NativeRect? = null
    var sentHoles: List<NativeHole>? = null
    var sentVisible = false

    /** The focus handler the view had when it took focus, for the report that it lost it. */
    var focusHandler: Long? = null

    fun forget() {
        sentFrame = null
        sentClip = null
        sentHoles = null
    }
}

/**
 * Places the platform views of one window and says what happens to them.
 *
 * Every layout callback (a view's or an overlay's) works the whole region out again and tells
 * the window what changed, inside the layout pass. A window has a handful of views and fewer
 * overlays, so the whole of it is cheaper than keeping track of which changed.
 */
internal class NativeViewCoordinator(
    private val table: NodeTable,
    private val windowNodeId: Int,
    private val window: NativeViewWindow?,
) : NativeViewWindowEvents {
    private val slots = LinkedHashMap<Int, Slot>()
    private val overlays = LinkedHashMap<Any, Overlay>()
    private var nextOrder = 1L
    private var parentReported = false
    private var reportedScale = 0f
    private var reportedHandle = 0L

    /** The slot that holds keyboard focus, or null for the scene. */
    var focused: Int? = null
        private set

    /** Events for the Host, waiting for the frame to send them in. */
    private val outbox = ArrayList<HostEvent>()
    val pendingGeneration = mutableIntStateOf(0)
    val hasPending: Boolean get() = outbox.isNotEmpty()

    fun flush(send: (HostEvent) -> Unit) {
        if (outbox.isEmpty()) return
        val drained = outbox.toList()
        outbox.clear()
        drained.forEach(send)
    }

    private fun post(event: HostEvent) {
        outbox += event
        pendingGeneration.intValue++
    }

    private fun refuse(code: Int, message: String) {
        post(HostEvent.ProtocolError(nodeId = NodeTable.ROOT_ID, handlerId = 0, code = code, message = message))
    }

    // ----- the views -----

    fun enter(nodeId: Int) {
        slots.getOrPut(nodeId) { Slot(nodeId) }
        reportParentIfNeeded()
    }

    fun leave(nodeId: Int) {
        val slot = slots.remove(nodeId) ?: return
        takeOut(slot)
    }

    /** The node's handle, attached in place of whatever it had. 0 takes the view out. */
    fun attach(nodeId: Int, handle: Long) {
        val slot = slots[nodeId] ?: return
        if (slot.handle == handle && (slot.attached || handle == 0L)) return
        takeOut(slot)
        slot.handle = handle
        if (handle == 0L) return
        val window = window ?: return
        if (!window.attach(nodeId, handle)) {
            slot.handle = 0L
            table.forgetNativeHandle(nodeId)
            refuse(
                TableError.NATIVE_VIEW_REJECTED,
                "AttachNativeView on node $nodeId: the window did not take handle 0x${handle.toString(16)} " +
                    "(not a view of this platform, or already in a window)",
            )
            return
        }
        slot.attached = true
        slot.sentVisible = false
        apply()
    }

    private fun takeOut(slot: Slot) {
        if (!slot.attached) return
        slot.attached = false
        slot.forget()
        slot.sentVisible = false
        // The window gives focus back to the scene first and reports it.
        window?.detach(slot.nodeId)
        if (focused == slot.nodeId) focusChanged(slot.nodeId, false)
    }

    fun setWantsVisible(nodeId: Int, visible: Boolean) {
        val slot = slots[nodeId] ?: return
        if (slot.wantsVisible == visible) return
        slot.wantsVisible = visible
        apply()
    }

    fun positioned(nodeId: Int, coordinates: LayoutCoordinates) {
        val slot = slots[nodeId] ?: return
        slot.coordinates = coordinates
        apply()
    }

    fun focusView(nodeId: Int, focus: Boolean) {
        val window = window ?: return
        val slot = slots[nodeId] ?: return
        if (focus) {
            if (slot.attached) window.focus(nodeId)
        } else if (focused == nodeId) {
            window.focusScene()
        }
    }

    /** A Compose node asked for focus: a view that holds it gives it back to the scene. */
    fun sceneFocusRequested() {
        if (focused != null) window?.focusScene()
    }

    // ----- the surfaces above them -----

    fun overlayEntered(key: Any) {
        overlays.getOrPut(key) { Overlay(nextOrder++) }
    }

    fun overlayLeft(key: Any) {
        if (overlays.remove(key) != null) apply()
    }

    fun overlayPositioned(key: Any, coordinates: LayoutCoordinates, radiusPx: Float, shadowPx: Float, coversWindow: Boolean) {
        val overlay = overlays[key] ?: return
        overlay.coordinates = coordinates
        overlay.coversWindow = coversWindow
        if (!coversWindow) {
            val bounds = coordinates.boundsInWindow()
            val grown = Rect(
                bounds.left - shadowPx,
                bounds.top - shadowPx,
                bounds.right + shadowPx,
                bounds.bottom + shadowPx,
            )
            overlay.hole = NativeHole(NativeRect.of(grown), ceil(radiusPx + shadowPx).toInt())
        }
        if (slots.isNotEmpty()) apply()
    }

    // ----- what the window says -----

    override fun focusChanged(id: Int, focused: Boolean) {
        val slot = slots[id]
        if (focused) {
            this.focused = id
            val handler = table.node(id)?.handler(PropertyKind.OnNativeViewFocus)
            slot?.focusHandler = handler
            if (handler != null) post(HostEvent.NativeViewFocus(id, handler, true))
        } else {
            if (this.focused != id) return
            this.focused = null
            val handler = slot?.focusHandler ?: table.node(id)?.handler(PropertyKind.OnNativeViewFocus)
            slot?.focusHandler = null
            if (handler != null) post(HostEvent.NativeViewFocus(id, handler, false))
        }
    }

    override fun scaleChanged(scale: Float) {
        // The platform converts pixels with the scale it has when it is called, so what it
        // was told before is said again.
        for (slot in slots.values) slot.forget()
        apply()
        reportParent(force = false)
    }

    // ----- the parent handle -----

    private fun reportParentIfNeeded() {
        if (!parentReported) reportParent(force = true)
    }

    private fun reportParent(force: Boolean) {
        val window = window ?: return
        if (slots.isEmpty() && !parentReported) return
        val handle = window.parentHandle
        val scale = window.scale
        if (!force && parentReported && handle == reportedHandle && scale == reportedScale) return
        parentReported = true
        reportedHandle = handle
        reportedScale = scale
        post(HostEvent.NativeViewParent(windowNodeId, 0L, handle, window.parentKind, scale))
    }

    /** The window's content is going away: every view leaves it, and the Host hears the window close. */
    fun close() {
        for (slot in slots.values.toList()) takeOut(slot)
        slots.clear()
        overlays.clear()
        window?.events = null
        if (parentReported && window != null) {
            parentReported = false
            post(HostEvent.NativeViewParent(windowNodeId, 0L, 0L, window.parentKind, reportedScale.takeIf { it > 0f } ?: 1f))
        }
    }

    // ----- the region -----

    /** Tells the window where every attached view goes and what part of it shows. */
    fun apply() {
        val window = window ?: return
        for (slot in slots.values) {
            if (!slot.attached) continue
            val coordinates = slot.coordinates?.takeIf { it.isAttached }
            if (coordinates == null) {
                show(window, slot, false)
                continue
            }
            val position = coordinates.positionInWindow()
            val size = coordinates.size
            val frame = NativeRect.of(Rect(position, Size(size.width.toFloat(), size.height.toFloat())))
            val clip = NativeRect.of(coordinates.boundsInWindow())
            val region = regionOf(frame, clip, holesAbove(coordinates))
            if (region == null) {
                show(window, slot, false)
                continue
            }
            if (slot.sentFrame != frame) {
                window.setFrame(slot.nodeId, frame.x, frame.y, frame.width, frame.height)
                slot.sentFrame = frame
            }
            val sentClip = if (region.clip == frame) null else region.clip
            if (slot.sentClip != sentClip || slot.sentHoles != region.holes) {
                window.setVisibleRegion(slot.nodeId, sentClip, region.holes)
                slot.sentClip = sentClip
                slot.sentHoles = region.holes
            }
            show(window, slot, slot.wantsVisible)
        }
    }

    private fun show(window: NativeViewWindow, slot: Slot, visible: Boolean) {
        if (slot.sentVisible == visible) return
        slot.sentVisible = visible
        window.setVisible(slot.nodeId, visible)
    }

    /**
     * The surfaces drawn above a view at [coordinates]: every surface opened after the
     * innermost one the view itself sits in, that does not hold the view.
     */
    private fun holesAbove(coordinates: LayoutCoordinates): List<NativeOverlayShape> {
        if (overlays.isEmpty()) return emptyList()
        var floor = 0L
        val holding = HashSet<Overlay>()
        for (overlay in overlays.values) {
            val at = overlay.coordinates?.takeIf { it.isAttached } ?: continue
            if (contains(at, coordinates)) {
                holding += overlay
                floor = maxOf(floor, overlay.order)
            }
        }
        val above = ArrayList<NativeOverlayShape>()
        for (overlay in overlays.values) {
            if (overlay in holding || overlay.order <= floor) continue
            if (overlay.coordinates?.isAttached != true) continue
            if (overlay.coversWindow) {
                above += NativeOverlayShape.Window
            } else {
                overlay.hole?.let { above += NativeOverlayShape.Hole(it) }
            }
        }
        return above
    }

    /** Whether [outer] is [inner] or one of the coordinates it is laid out in. */
    private fun contains(outer: LayoutCoordinates, inner: LayoutCoordinates): Boolean {
        var at: LayoutCoordinates? = inner
        var steps = 0
        while (at != null && steps++ < 4096) {
            if (at === outer) return true
            at = at.parentCoordinates
        }
        return false
    }
}

/** A surface above a view: a hole of its shape, or a scrim over the whole window. */
internal sealed interface NativeOverlayShape {
    data class Hole(val hole: NativeHole) : NativeOverlayShape
    object Window : NativeOverlayShape
}

/** What part of a view shows: inside [clip], outside every hole. */
internal data class NativeViewRegion(val clip: NativeRect, val holes: List<NativeHole>)

/**
 * What part of a view with [frame] shows, cut to its ancestors' [clip] and with every surface
 * [above] it cut out, or null when nothing of it shows. A scrim over the window cuts out the
 * whole view, as a hole the size of what was left, so the view keeps its focus and its state.
 */
internal fun regionOf(frame: NativeRect, clip: NativeRect, above: List<NativeOverlayShape>): NativeViewRegion? {
    val bounds = frame.intersect(clip)
    if (bounds.isEmpty) return null
    if (above.any { it is NativeOverlayShape.Window }) {
        return NativeViewRegion(bounds, listOf(NativeHole(bounds, 0)))
    }
    val holes = above.mapNotNull { (it as? NativeOverlayShape.Hole)?.hole }
        .filter { it.rect.intersects(bounds) }
    return NativeViewRegion(bounds, holes)
}

/**
 * Marks a surface drawn above the page (a popover, a menu, a dialog, a sheet, a tooltip, a
 * message): a platform view under it is cut out where the surface and its [shadow] are, in the
 * surface's [shape], so the surface shows on top. [coversWindow] is for a scrim that dims the
 * whole window, which covers the whole view.
 *
 * Costs a layout callback only in a window that has a platform view slot.
 */
internal fun Modifier.nativeViewOverlay(
    shape: Shape = RectangleShape,
    shadow: Dp = 0.dp,
    coversWindow: Boolean = false,
): Modifier = composed {
    val views = LocalNativeViews.current ?: return@composed Modifier
    val key = remember { Any() }
    DisposableEffect(views, key) {
        views.overlayEntered(key)
        onDispose { views.overlayLeft(key) }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Modifier.onGloballyPositioned { coordinates ->
        val size = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
        views.overlayPositioned(
            key,
            coordinates,
            radiusPx = cornerRadiusOf(shape, size, direction, density),
            shadowPx = with(density) { shadow.toPx() },
            coversWindow = coversWindow,
        )
    }
}

/** The largest corner radius [shape] has at [size], in pixels: what a hole's corners are cut to. */
internal fun cornerRadiusOf(shape: Shape, size: Size, direction: LayoutDirection, density: Density): Float {
    if (size.width <= 0f || size.height <= 0f) return 0f
    return when (val outline = shape.createOutline(size, direction, density)) {
        is Outline.Rectangle -> 0f
        is Outline.Rounded -> with(outline.roundRect) {
            maxOf(
                maxOf(topLeftCornerRadius.x, topRightCornerRadius.x),
                maxOf(bottomLeftCornerRadius.x, bottomRightCornerRadius.x),
            )
        }
        // A path's corners are not known; its bounds are cut square, which shows a corner of
        // the view past a curve rather than a corner of the surface past the view.
        is Outline.Generic -> 0f
    }
}

/** How far a surface's shadow reaches past it for a resting height of [elevation]. */
internal fun shadowReach(elevation: Dp): Dp = if (elevation.value <= 0f) 0.dp else elevation * 1.5f

/**
 * The platform views of one window's content: the coordinator its views and surfaces
 * register with (provided as [LocalNativeViews]), the window's reports, and the loop that
 * sends what the Host is owed.
 */
@Composable
internal fun rememberNativeViews(
    table: NodeTable,
    windowNodeId: Int,
    dispatcher: EventDispatcher,
): NativeViewCoordinator {
    val window = LocalNativeViewWindow.current
    val views = remember(table, windowNodeId, window) { NativeViewCoordinator(table, windowNodeId, window) }
    DisposableEffect(views) {
        window?.events = views
        onDispose {
            // The loop below ends with the composition, so the window's last report goes
            // out here.
            views.close()
            views.flush { event -> dispatcher.dispatch(event) }
        }
    }
    // A Compose node asked for focus: the platform view that has it lets go.
    val sceneFocus = table.sceneFocusRevision
    LaunchedEffect(views, sceneFocus) {
        if (sceneFocus > 0L) views.sceneFocusRequested()
    }
    LaunchedEffect(views) {
        androidx.compose.runtime.snapshotFlow { views.pendingGeneration.intValue }.collect {
            while (views.hasPending) {
                androidx.compose.runtime.withFrameNanos { views.flush { event -> dispatcher.dispatch(event) } }
            }
        }
    }
    return views
}

/**
 * A `NativeView` node: a box the size its modifiers and its parent give it, over which the
 * window places the Host's platform view. What the modifiers draw (a background, a border)
 * shows only where the view does not cover it: before it is attached, while it is hidden.
 */
@Composable
internal fun HostNativeView(node: Node, modifier: Modifier) {
    val views = LocalNativeViews.current
    if (views == null) {
        Box(modifier)
        return
    }
    val nodeId = node.id
    DisposableEffect(views, nodeId) {
        views.enter(nodeId)
        onDispose { views.leave(nodeId) }
    }
    val handle = node.nativeHandle
    DisposableEffect(views, nodeId, handle) {
        views.attach(nodeId, handle)
        onDispose { }
    }
    val visible = node.flag(PropertyKind.NativeVisible, default = true)
    DisposableEffect(views, nodeId, visible) {
        views.setWantsVisible(nodeId, visible)
        onDispose { }
    }
    val focusRequest = node.nativeFocusRequest
    LaunchedEffect(views, nodeId, focusRequest) {
        if (focusRequest != null) views.focusView(nodeId, focusRequest.focus)
    }
    Box(modifier.onGloballyPositioned { coordinates -> views.positioned(nodeId, coordinates) })
}
