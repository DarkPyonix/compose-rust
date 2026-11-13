package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A scroll request the Host sent, already checked by the node table, waiting for the
 * container to act on it.
 *
 * A request and never a command: the position is the Renderer's. The container keeps the
 * offset inside what its content allows and leaves the request alone while the user is
 * moving it.
 */
internal sealed interface ScrollRequest {
    /**
     * Go to an offset in physical pixels. An axis that is null is not touched. [item] is
     * the lazy list item the offset is measured from. [forced] is set for the Renderer's
     * own request to keep the same place after the density changed, which is not the Host
     * asking and so is not dropped while the user scrolls.
     */
    class To(
        val item: Int,
        val x: Double?,
        val y: Double?,
        val animate: Boolean,
        val forced: Boolean = false,
    ) : ScrollRequest

    /** Bring a rectangle into view. The words are the wire's, see [ScrollWire]. */
    class IntoView(
        val targetKind: Int,
        val target: Int,
        val alignX: Int,
        val alignY: Int,
        val animate: Boolean,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) : ScrollRequest {
        /** No width and no height means the whole node or the whole item. */
        val whole: Boolean get() = width == 0.0 && height == 0.0
    }

    /**
     * The place in the batch where the node's own scroll properties (`Value`,
     * `Determinate`, `Count`) asked for a move, so that request and the ones around it
     * are applied in the order the batch wrote them.
     */
    object Stated : ScrollRequest
}

/** Where a scroll container is at one moment, every length in physical pixels. */
internal data class ScrollSample(
    val item: Int,
    val x: Int,
    val y: Int,
    /** The furthest offset on each axis, or -1 where the container does not know it. */
    val maxX: Int,
    val maxY: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
    /** A drag, a fling or an animation is still running. Not part of the place. */
    val moving: Boolean,
) {
    fun samePlace(other: ScrollSample): Boolean =
        item == other.item && x == other.x && y == other.y &&
            maxX == other.maxX && maxY == other.maxY &&
            viewportWidth == other.viewportWidth && viewportHeight == other.viewportHeight &&
            canScrollBackward == other.canScrollBackward && canScrollForward == other.canScrollForward
}

/**
 * What a scroll container's node holds for the Host's requests and for its reports.
 *
 * Created with the node, like a code editor's model, because a request can arrive in the
 * batch that creates the container, before anything is composed to receive it.
 */
internal class ScrollPort(val nodeId: Int) {
    private val queue = ArrayList<ScrollRequest>()

    /** Counts up with every request, which is what the container's composable watches. */
    var requestRevision by mutableIntStateOf(0)
        private set

    /** True from a request's arrival until the container has acted on it. */
    private var applying = false

    /** How many moves this side started are running: an animation the Host asked for. */
    var ownMotion = 0

    /**
     * The batch in which the node's own scroll properties last asked for a move, and the
     * revision they asked with, until a scroll request in the same batch gives that move
     * its place in the queue.
     */
    var statedBatch = -1L
    var statedPending = 0L

    /**
     * The revision of the node's own move that is in the queue. The container leaves that
     * one to the queue, so it is applied once and in its place.
     */
    var statedRevision = 0L

    /** The node's own move asked in [batch], which no request has been written after yet. */
    fun stated(batch: Long, revision: Long) {
        if (hasRequests) {
            queueStated(revision)
        } else {
            statedBatch = batch
            statedPending = revision
        }
    }

    /** Called before a request is queued in [batch]: the node's own move came first. */
    fun beforeRequest(batch: Long) {
        if (statedBatch == batch) queueStated(statedPending)
        statedBatch = -1L
    }

    private fun queueStated(revision: Long) {
        statedRevision = revision
        request(ScrollRequest.Stated)
    }

    /** The handler the reports go to, while the node has one and is composed. */
    var handlerId: Long? = null

    /** Reads where the container is now, or null before its first layout. */
    var sampler: (() -> ScrollSample?)? = null

    /** What the Host was last told, and whether that report said the movement had ended. */
    var sent: ScrollSample? = null
    var sentSettled = true

    /**
     * True while a request waits or is being applied. No report is sent meanwhile, so the
     * Host is told where the container ended up and not where it was on the way.
     */
    val busy: Boolean get() = applying || queue.isNotEmpty()

    val hasRequests: Boolean get() = queue.isNotEmpty()

    fun request(request: ScrollRequest) {
        queue += request
        requestRevision++
    }

    /** Takes what is waiting, in the order it arrived. */
    fun take(): List<ScrollRequest> {
        if (queue.isEmpty()) return emptyList()
        val taken = queue.toList()
        queue.clear()
        applying = true
        return taken
    }

    fun applied() {
        applying = false
    }
}

/**
 * The scroll reports waiting for the next frame.
 *
 * A container that moved says so here and is read at the start of the next frame, once,
 * for the place it has reached by then. So a container that moves three times between
 * two frames costs one report, and one that nobody listens to costs nothing.
 */
internal class ScrollReports {
    /** Counts up when something starts waiting for a frame, which is what the frame loop watches. */
    val pendingGeneration = mutableIntStateOf(0)

    private val pending = LinkedHashSet<ScrollPort>()

    val hasPending: Boolean get() = pending.isNotEmpty()

    fun changed(port: ScrollPort) {
        if (port.handlerId == null) return
        val first = pending.isEmpty()
        if (pending.add(port) && first) pendingGeneration.intValue++
    }

    fun forget(port: ScrollPort) {
        pending.remove(port)
    }

    fun clear() {
        pending.clear()
    }

    /** Sends one report for every container that moved since the last frame. */
    fun flush(dispatch: (HostEvent) -> Unit) {
        if (pending.isEmpty()) return
        val ports = pending.toList()
        pending.clear()
        for (port in ports) {
            val handler = port.handlerId ?: continue
            // A request still on its way in: the report waits for what it does, and the
            // container says so again once it has acted.
            if (port.busy) continue
            val sample = port.sampler?.invoke() ?: continue
            val before = port.sent
            val moved = before == null || !sample.samePlace(before)
            // A place is reported when it changes. Coming to rest is reported once, with
            // the last place, unless the last report already said so.
            if (!moved && (sample.moving || port.sentSettled)) continue
            val settled = !sample.moving
            port.sent = sample
            port.sentSettled = settled
            var flags = 0
            if (settled) flags = flags or ScrollWire.CHANGED_SETTLED
            if (sample.canScrollBackward) flags = flags or ScrollWire.CHANGED_CAN_SCROLL_BACKWARD
            if (sample.canScrollForward) flags = flags or ScrollWire.CHANGED_CAN_SCROLL_FORWARD
            dispatch(
                HostEvent.ScrollChanged(
                    nodeId = port.nodeId,
                    handlerId = handler,
                    item = sample.item,
                    flags = flags,
                    xPx = sample.x.toDouble(),
                    yPx = sample.y.toDouble(),
                    maxXPx = sample.maxX.toDouble(),
                    maxYPx = sample.maxY.toDouble(),
                    viewportWidthPx = sample.viewportWidth.toDouble(),
                    viewportHeightPx = sample.viewportHeight.toDouble(),
                ),
            )
        }
    }
}

/** What a scroll container can be asked, whatever Compose state it scrolls with. */
internal abstract class ScrollSurface {
    /** True once the container has been laid out and knows its range. */
    abstract fun measured(): Boolean

    abstract fun sample(): ScrollSample?

    /** True while anything is scrolling the container: the user, a fling or an animation. */
    abstract fun moving(): Boolean

    abstract suspend fun scrollTo(request: ScrollRequest.To, animate: Boolean)

    /**
     * Brings the request's rectangle into view. [box] is the target node's box in the
     * viewport's coordinates, for a request that names a node.
     */
    abstract suspend fun bringIntoView(request: ScrollRequest.IntoView, box: Rect?, animate: Boolean)

    /** Applies the move the node's own scroll properties ask for. */
    open suspend fun stated() {}

    /** The request that keeps the same place once every length is [ratio] times what it was. */
    abstract fun rescaled(ratio: Float): ScrollRequest.To?
}

/** One axis of a container that scrolls by an offset. */
internal class ScrollAxis(
    val state: ScrollableState,
    val value: () -> Int,
    val max: () -> Int,
    val viewport: () -> Int,
    val measured: () -> Boolean,
    val jump: suspend (Int) -> Unit,
)

internal fun ScrollState.axis(): ScrollAxis = ScrollAxis(
    state = this,
    value = { value },
    // Until the first measure the furthest offset is unknown, which Compose says with the
    // largest integer there is.
    max = { if (maxValue == Int.MAX_VALUE) 0 else maxValue },
    viewport = { viewportSize },
    measured = { maxValue != Int.MAX_VALUE },
    jump = { target -> scrollTo(target) },
)

/**
 * Where a rectangle that starts at [start] and is [size] long should put the offset of a
 * viewport [viewport] long that is now at [current], or null to leave the axis alone.
 */
internal fun aimAxis(align: Int, start: Double, size: Double, viewport: Int, current: Int): Double? =
    when (align) {
        ScrollWire.ALIGN_START -> start
        ScrollWire.ALIGN_CENTER -> start + size / 2 - viewport / 2.0
        ScrollWire.ALIGN_END -> start + size - viewport
        ScrollWire.ALIGN_NEAREST -> when {
            size > viewport -> start
            start >= current && start + size <= current + viewport -> null
            start < current -> start
            else -> start + size - viewport
        }
        else -> null
    }

/** A move a higher priority scroll refused is a request ignored, not a failure. */
private suspend inline fun unlessRefused(move: () -> Unit) {
    try {
        move()
    } catch (refused: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

/**
 * A container that scrolls by a pixel offset on one axis or two: `ScrollColumn`,
 * `ScrollRow` and the code editor. An axis the container does not scroll on is null and
 * reads as zero.
 */
internal class OffsetScrollSurface(
    private val x: ScrollAxis?,
    private val y: ScrollAxis?,
    private val mainVertical: Boolean,
    /** The viewport's size on the axis the container does not scroll on. */
    private val cross: () -> Int = { 0 },
    private val statedMove: (suspend () -> Unit)? = null,
) : ScrollSurface() {
    private val main: ScrollAxis get() = (if (mainVertical) y else x) ?: x ?: y!!

    override fun measured(): Boolean = main.measured() && main.viewport() > 0

    override fun moving(): Boolean =
        x?.state?.isScrollInProgress == true || y?.state?.isScrollInProgress == true

    override fun sample(): ScrollSample? {
        if (!measured()) return null
        val main = main
        return ScrollSample(
            item = 0,
            x = x?.value?.invoke() ?: 0,
            y = y?.value?.invoke() ?: 0,
            maxX = x?.max?.invoke() ?: 0,
            maxY = y?.max?.invoke() ?: 0,
            viewportWidth = x?.viewport?.invoke() ?: cross(),
            viewportHeight = y?.viewport?.invoke() ?: cross(),
            canScrollBackward = main.value() > 0,
            canScrollForward = main.value() < main.max(),
            moving = moving(),
        )
    }

    private suspend fun move(toX: Double?, toY: Double?, animate: Boolean) = coroutineScope {
        for ((axis, wanted) in listOf(x to toX, y to toY)) {
            if (axis == null || wanted == null) continue
            val target = wanted.coerceIn(0.0, axis.max().toDouble()).roundToInt()
            if (target == axis.value()) continue
            // Each axis has its own state, so two of them animate side by side.
            launch {
                unlessRefused {
                    if (animate) {
                        axis.state.animateScrollBy((target - axis.value()).toFloat())
                    } else {
                        axis.jump(target)
                    }
                }
            }
        }
    }

    override suspend fun scrollTo(request: ScrollRequest.To, animate: Boolean) =
        move(request.x, request.y, animate)

    override suspend fun bringIntoView(request: ScrollRequest.IntoView, box: Rect?, animate: Boolean) {
        val currentX = x?.value?.invoke() ?: 0
        val currentY = y?.value?.invoke() ?: 0
        // The rectangle in the content's coordinates: as sent, or from the node's box,
        // which was read in the viewport's and so sits one offset further in.
        var left = request.x
        var top = request.y
        var width = request.width
        var height = request.height
        if (request.targetKind == ScrollWire.TARGET_NODE) {
            if (box == null) return
            left += box.left + currentX
            top += box.top + currentY
            if (request.whole) {
                width = box.width.toDouble()
                height = box.height.toDouble()
            }
        }
        move(
            x?.let { aimAxis(request.alignX, left, width, it.viewport(), currentX) },
            y?.let { aimAxis(request.alignY, top, height, it.viewport(), currentY) },
            animate,
        )
    }

    override suspend fun stated() {
        statedMove?.invoke()
    }

    override fun rescaled(ratio: Float): ScrollRequest.To? {
        if (!measured()) return null
        return ScrollRequest.To(
            item = 0,
            x = x?.let { it.value() * ratio.toDouble() },
            y = y?.let { it.value() * ratio.toDouble() },
            animate = false,
            forced = true,
        )
    }
}

/**
 * A container that scrolls by item: `LazyColumn`, `LazyRow` and `LazyGrid`. Its place is
 * the first visible item and the distance into it, and it does not know how long the
 * whole list is.
 */
internal class LazyScrollSurface(
    private val vertical: Boolean,
    private val state: ScrollableState,
    private val firstIndex: () -> Int,
    private val firstOffset: () -> Int,
    private val viewport: () -> IntSize,
    /** Where a visible item starts in the viewport and how long it is, on the main axis. */
    private val itemBox: (Int) -> Pair<Int, Int>?,
    private val jump: suspend (Int, Int) -> Unit,
    private val animateTo: suspend (Int, Int) -> Unit,
) : ScrollSurface() {
    private fun viewportMain(): Int = viewport().let { if (vertical) it.height else it.width }

    override fun measured(): Boolean = viewportMain() > 0

    override fun moving(): Boolean = state.isScrollInProgress

    override fun sample(): ScrollSample? {
        if (!measured()) return null
        val size = viewport()
        val offset = firstOffset()
        return ScrollSample(
            item = firstIndex(),
            x = if (vertical) 0 else offset,
            y = if (vertical) offset else 0,
            maxX = if (vertical) 0 else -1,
            maxY = if (vertical) -1 else 0,
            viewportWidth = size.width,
            viewportHeight = size.height,
            canScrollBackward = state.canScrollBackward,
            canScrollForward = state.canScrollForward,
            moving = moving(),
        )
    }

    private suspend fun go(item: Int, offset: Int, animate: Boolean) {
        if (item == firstIndex() && offset == firstOffset()) return
        unlessRefused { if (animate) animateTo(item, offset) else jump(item, offset) }
    }

    override suspend fun scrollTo(request: ScrollRequest.To, animate: Boolean) {
        // Only the main axis moves. The other one is always at zero.
        val wanted = (if (vertical) request.y else request.x) ?: return
        go(request.item, wanted.coerceIn(0.0, Int.MAX_VALUE.toDouble()).roundToInt(), animate)
    }

    override suspend fun bringIntoView(request: ScrollRequest.IntoView, box: Rect?, animate: Boolean) {
        val align = if (vertical) request.alignY else request.alignX
        if (align == ScrollWire.ALIGN_KEEP) return
        val viewport = viewportMain()
        val within = if (request.whole) 0.0 else if (vertical) request.y else request.x
        val asked = if (request.whole) null else if (vertical) request.height else request.width
        if (request.targetKind == ScrollWire.TARGET_NODE) {
            // A node is found by where it is drawn, so one that is not composed (an item
            // off the screen) is not found and nothing moves.
            if (box == null) return
            val start = (if (vertical) box.top else box.left) + within
            val size = asked ?: (if (vertical) box.height else box.width).toDouble()
            val by = aimAxis(align, start, size, viewport, 0) ?: return
            if (by.roundToInt() == 0) return
            unlessRefused {
                if (animate) state.animateScrollBy(by.toFloat()) else state.scrollBy(by.toFloat())
            }
            return
        }
        val item = request.target
        var seen = itemBox(item)
        // `Nearest` for an item off the screen is the edge it would come in from.
        val edge = when {
            align != ScrollWire.ALIGN_NEAREST -> align
            seen != null -> ScrollWire.ALIGN_NEAREST
            item < firstIndex() -> ScrollWire.ALIGN_START
            else -> ScrollWire.ALIGN_END
        }
        if (edge == ScrollWire.ALIGN_START) {
            go(item, within.roundToInt(), animate)
            return
        }
        var animated = animate
        if (seen == null && asked == null) {
            // How long the item is decides where it goes, and an item off the screen has
            // not been measured. It is put at the start first, which measures it.
            unlessRefused { jump(item, 0) }
            seen = itemBox(item) ?: return
            animated = false
        }
        val size = asked ?: seen!!.second.toDouble()
        if (edge == ScrollWire.ALIGN_NEAREST) {
            val start = seen!!.first + within
            val by = aimAxis(edge, start, size, viewport, 0) ?: return
            // Relative to the item's own start, which is what the list scrolls by.
            go(item, (by - seen.first).roundToInt(), animated)
            return
        }
        val offset = aimAxis(edge, within, size, viewport, 0) ?: return
        go(item, offset.roundToInt(), animated)
    }

    override fun rescaled(ratio: Float): ScrollRequest.To? {
        if (!measured()) return null
        val offset = firstOffset() * ratio.toDouble()
        return ScrollRequest.To(
            item = firstIndex(),
            x = if (vertical) null else offset,
            y = if (vertical) offset else null,
            animate = false,
            forced = true,
        )
    }
}

internal fun LazyListState.scrollSurface(vertical: Boolean): ScrollSurface = LazyScrollSurface(
    vertical = vertical,
    state = this,
    firstIndex = { firstVisibleItemIndex },
    firstOffset = { firstVisibleItemScrollOffset },
    viewport = { layoutInfo.viewportSize },
    itemBox = { index ->
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { it.offset to it.size }
    },
    jump = { item, offset -> scrollToItem(item, offset) },
    animateTo = { item, offset -> animateScrollToItem(item, offset) },
)

internal fun LazyGridState.scrollSurface(): ScrollSurface = LazyScrollSurface(
    vertical = true,
    state = this,
    firstIndex = { firstVisibleItemIndex },
    firstOffset = { firstVisibleItemScrollOffset },
    viewport = { layoutInfo.viewportSize },
    itemBox = { index ->
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { it.offset.y to it.size.height }
    },
    jump = { item, offset -> scrollToItem(item, offset) },
    animateTo = { item, offset -> animateScrollToItem(item, offset) },
)

/**
 * The box of the node whose innermost coordinates are [inner], in the coordinates of
 * [viewport].
 *
 * A node's padding is inside the node, so the box grows outwards through the node's own
 * modifiers for as long as each one contains the last. It stops at one that does not,
 * which is a modifier that moved the node (an offset) and so is not where the node is.
 */
internal fun nodeBox(viewport: LayoutCoordinates, inner: LayoutCoordinates): Rect {
    var box = viewport.localBoundingBoxOf(inner, clipBounds = false)
    val stop = inner.parentLayoutCoordinates
    var outer = inner.parentCoordinates
    var steps = 64
    while (outer != null && outer !== stop && outer.isAttached && steps-- > 0) {
        val bounds = viewport.localBoundingBoxOf(outer, clipBounds = false)
        val contains = bounds.left <= box.left && bounds.top <= box.top &&
            bounds.right >= box.right && bounds.bottom >= box.bottom
        if (!contains) break
        box = bounds
        outer = outer.parentCoordinates
    }
    return box
}

/** How many frames a request waits for the node it names to be placed before giving up. */
private const val PLACEMENT_FRAMES = 3

/**
 * Wires a scroll container's node to its Compose state: applies the Host's requests,
 * reports the position to the node's handler, and keeps the same place when the density
 * changes.
 *
 * A container that was never sent a request and has no handler composes nothing here
 * beyond the density check, and sends nothing.
 */
@Composable
internal fun HostScroll(node: Node, table: NodeTable, surface: ScrollSurface) {
    val port = node.scroll ?: return
    val reports = table.scrolls

    // The same place at a new density is the same fraction of every length, so the offset
    // is scaled by what the density was scaled by. Read before the layout that follows.
    val density = LocalDensity.current.density
    val seen = remember(port) { floatArrayOf(density) }
    if (seen[0] != density) {
        SideEffect {
            val before = seen[0]
            if (before != density) {
                seen[0] = density
                surface.rescaled(density / before)?.let(port::request)
            }
        }
    }

    if (port.requestRevision != 0) {
        LaunchedEffect(port, surface) {
            // A newer request replaces whatever the last one was still doing, an animation
            // included.
            snapshotFlow { port.requestRevision }.collectLatest {
                if (!port.hasRequests) return@collectLatest
                var animation: ScrollRequest? = null
                try {
                    // A request sent with the container waits for its first layout, so it
                    // is kept inside a range that exists.
                    snapshotFlow { surface.measured() }.first { it }
                    val requests = port.take()
                    if (requests.any { it is ScrollRequest.To && it.forced }) {
                        // The layout at the new density has to have happened first.
                        withFrameNanos { }
                    }
                    for ((index, request) in requests.withIndex()) {
                        val forced = request is ScrollRequest.To && request.forced
                        // The user's hand wins: a request that arrives during a drag, a
                        // wheel turn or the fling after one is dropped.
                        if (!forced && surface.moving() && port.ownMotion == 0) continue
                        val animate = when (request) {
                            is ScrollRequest.To -> request.animate
                            is ScrollRequest.IntoView -> request.animate
                            ScrollRequest.Stated -> false
                        }
                        // Applied in order, so the result is the last one's. Only the
                        // last may take time over it.
                        if (animate && index == requests.lastIndex) {
                            animation = request
                        } else {
                            apply(node, table, surface, request, animate = false)
                        }
                    }
                } finally {
                    port.applied()
                    reports.changed(port)
                }
                animation?.let { request ->
                    port.ownMotion++
                    try {
                        apply(node, table, surface, request, animate = true)
                    } finally {
                        port.ownMotion--
                    }
                }
            }
        }
    }

    val handlerId = node.handler(PropertyKind.OnScrollChanged)
    if (handlerId != null) {
        DisposableEffect(port, handlerId, surface) {
            port.handlerId = handlerId
            port.sampler = surface::sample
            // A handler attached now is told where the container is, whatever an earlier
            // one heard.
            port.sent = null
            port.sentSettled = true
            reports.changed(port)
            onDispose {
                port.handlerId = null
                port.sampler = null
                reports.forget(port)
            }
        }
        LaunchedEffect(port, handlerId, surface) {
            snapshotFlow { surface.sample() }.collect { reports.changed(port) }
        }
    }
}

private suspend fun apply(
    node: Node,
    table: NodeTable,
    surface: ScrollSurface,
    request: ScrollRequest,
    animate: Boolean,
) {
    when (request) {
        is ScrollRequest.To -> surface.scrollTo(request, animate)
        is ScrollRequest.IntoView -> {
            val box = if (request.targetKind == ScrollWire.TARGET_NODE) {
                placedBox(node, table.node(request.target)) ?: return
            } else {
                null
            }
            surface.bringIntoView(request, box, animate)
        }
        ScrollRequest.Stated -> surface.stated()
    }
}

/**
 * Where [target] is drawn inside [container]'s viewport, or null when it is not composed.
 *
 * Nothing records where nodes are until it is asked for. The two nodes are asked to say,
 * which takes a frame, and are let go of again afterwards.
 */
private suspend fun placedBox(container: Node, target: Node?): Rect? {
    if (target == null) return null
    container.placementWanted = true
    target.placementWanted = true
    try {
        repeat(PLACEMENT_FRAMES) {
            val viewport = container.placement
            val inner = target.placement
            if (viewport != null && inner != null && viewport.isAttached && inner.isAttached) {
                return nodeBox(viewport, inner)
            }
            withFrameNanos { }
        }
        val viewport = container.placement
        val inner = target.placement
        if (viewport != null && inner != null && viewport.isAttached && inner.isAttached) {
            return nodeBox(viewport, inner)
        }
        return null
    } finally {
        container.placementWanted = false
        target.placementWanted = false
        container.placement = null
        target.placement = null
    }
}
