package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.LastWindowClosed
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.WindowSizeReporter
import dev.darkpyonix.composerust.ui.node.Node
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Maps the `Window` nodes of the interpreted tree to platform windows.
 *
 * Only a multi-window application (`SetWindow` mode `Windows`) has one; a single window
 * application never creates it, and its code path is the one it always had. One manager per
 * process, on the Host's thread: one Host, one node table, one batch that every window reads
 *. After each batch it is told by [ComposeRustHost.afterBatch] and does five things,
 * in this order:
 *
 * 1. closes the windows whose node is gone,
 * 2. opens the windows whose node is new, or tells the application with `WindowUnavailable`
 *    when the platform refuses,
 * 3. applies the properties of the open ones **that changed since the application last
 *    sent them**, so a window the user moved is not put back where the application
 *    last said it was,
 * 4. acts on `FocusWindow`,
 * 5. ends the loop when the last window went and the policy is `Exit`.
 *
 * Events leave with the `Window` node's id as `node_id`; only those with a handler registered
 * on the node cross the boundary. Moves and drags are coalesced and leave from
 * [flushFrame], once per frame.
 */
class WindowManager(
    private val host: ComposeRustHost,
    private val backend: WindowBackend,
    /** Ends the renderer loop with status 0. Called once, after the batch that removed the last window. */
    private val onExit: () -> Unit,
    private val dropTargets: DropTargets = DropTargets.None,
    /** Ends the capture a node holds on [pointerId] when a window is torn off under it (46.2). */
    private val cancelCapture: (pointerId: Int) -> Unit = {},
) {
    private val table get() = host.table

    private val entries = LinkedHashMap<Int, Entry>()
    private val waiting = ArrayList<Waiting>()
    private var openOrder = 0
    private var nextRequest = 1
    private var syncing = false
    private var dirty = false
    private var everHadWindow = false
    private var exited = false

    /** The number of windows the manager holds the state of, so that a test can see that closing windows leaves nothing behind. */
    internal val retained: Int get() = entries.size + waiting.size

    /** The ids of the `Window` nodes that have a platform window open now, in opening order. */
    val openWindows: List<Int> get() = entries.values.filter { it.platform != null }.map { it.nodeId }

    private val appListener = object : ApplicationListener {
        override fun onReopen() {
            host.dispatch(HostEvent.ApplicationReopen(nodeId = 0, handlerId = 0))
        }

        override fun onWindowRequested(key: String, window: PlatformWindow) {
            request(key, window, null)
        }
    }

    /** Starts managing: hooks the Host's batches, listens to the application, opens what exists. */
    fun attach() {
        host.afterBatch = { sync() }
        backend.setApplicationListener(appListener)
        sync()
    }

    /** Stops managing and closes every window. The Host and the table are left as they are. */
    fun shutdown() {
        host.afterBatch = null
        backend.setApplicationListener(null)
        for (entry in entries.values.toList()) closeEntry(entry)
        waiting.clear()
    }

    /** Applies what the batch just applied changed to the platform windows. */
    fun sync() {
        if (syncing) {
            dirty = true
            return
        }
        syncing = true
        try {
            var turns = 0
            do {
                dirty = false
                syncOnce()
                turns++
            } while (dirty && turns < MAX_SYNC_TURNS)
        } finally {
            syncing = false
        }
    }

    private fun syncOnce() {
        if (table.window?.mode != WindowMode.Windows) return
        val wanted = table.roots.filter { table.node(it)?.widget == WidgetKind.Window }
        val wantedSet = wanted.toHashSet()

        for (entry in entries.values.toList()) {
            if (entry.nodeId !in wantedSet) closeEntry(entry)
        }
        for (id in wanted) {
            if (!entries.containsKey(id)) openEntry(id)
        }
        for (id in wanted) {
            entries[id]?.let { applyChanges(it) }
        }
        for (id in table.drainFocusRequests()) {
            entries[id]?.platform?.focus()
        }

        if (wanted.isNotEmpty()) {
            everHadWindow = true
        } else if (everHadWindow && !exited && table.window?.lastWindowClosed == LastWindowClosed.Exit) {
            exited = true
            onExit()
        }
    }

    // ---- opening and closing -------------------------------------------------------------

    private fun openEntry(id: Int) {
        val node = table.node(id) ?: return
        val match = waitingFor(node.text(PropertyKind.WindowRestoreKey))
        // A window torn off a tab gets the drag the application did not ask for.
        val tear = match?.tear
        if (tear != null) {
            node.props[PropertyKind.DragFromPointer] = PropertyValue.Bytes(
                encodeDrag(tear.pointerId, tear.anchorX, tear.anchorY),
            )
        }
        val spec = readSpec(node)
        val entry = Entry(id, openOrder++, spec)
        entries[id] = entry

        val adopted = match?.window
        if (match != null && adopted != null) {
            match.taken = true
            entry.platform = adopted
            adopted.setListener(entry)
            // The window exists already; everything the node says is news to it.
            entry.applied = WindowSpec.Blank
            return
        }
        match?.taken = true

        val platform = when {
            !backend.supportsMultipleWindows && openWindows.isNotEmpty() -> null
            else -> try {
                backend.open(configOf(id, spec), entry)
            } catch (error: Exception) {
                null
            }
        }
        if (platform == null) {
            entry.unavailable = true
            emit(entry, PropertyKind.OnWindowUnavailable) { handler ->
                HostEvent.WindowUnavailable(entry.nodeId, handler)
            }
            return
        }
        entry.platform = platform
        // Read once, at the first opening: a later one is ignored.
        spec.drag?.let { startDrag(entry, it) }
    }

    private fun closeEntry(entry: Entry) {
        entries.remove(entry.nodeId)
        entry.dead = true
        entry.drag = null
        val platform = entry.platform
        entry.platform = null
        platform?.close()
    }

    private fun waitingFor(key: String): Waiting? =
        waiting.firstOrNull { !it.taken && it.key == key }

    private fun request(key: String, window: PlatformWindow?, tear: TearOff?) {
        val pending = Waiting(nextRequest++, key, window, tear)
        waiting += pending
        try {
            host.dispatch(HostEvent.WindowRequested(nodeId = 0, handlerId = 0, request = pending.request, key = key))
        } finally {
            waiting.remove(pending)
        }
        // The application did not put a window up for it in answer.
        if (!pending.taken) window?.close()
    }

    /**
     * A pointer held on a node with a `TearOffKey` left its window. Asks the
     * application for a window under that key; the `Window` node it puts in gets
     * `DragFromPointer` from here and follows the pointer.
     *
     * [anchorX] and [anchorY] are where the node was pressed, in the new window's content.
     * Returns false when the node has no key, and then nothing was asked.
     */
    fun tearOff(nodeId: Int, pointerId: Int, anchorX: Float, anchorY: Float): Boolean {
        val key = table.node(nodeId)?.text(PropertyKind.TearOffKey).orEmpty()
        if (key.isEmpty()) return false
        request(key, null, TearOff(pointerId, anchorX, anchorY))
        return true
    }

    // ---- properties ----------------------------------------------------------------------

    private fun applyChanges(entry: Entry) {
        val platform = entry.platform ?: return
        val node = table.node(entry.nodeId) ?: return
        val now = readSpec(node)
        val before = entry.applied
        if (now == before) return
        if (now.title != before.title) platform.setTitle(now.title)
        if (now.minSize != before.minSize && now.minSize != null) {
            platform.setMinimumSize(now.minSize.a, now.minSize.b)
        }
        if (now.resizable != before.resizable && now.resizable != null) platform.setResizable(now.resizable)
        if (now.size != before.size && now.size != null) platform.setContentSize(now.size.a, now.size.b)
        // Only a value the application changed moves the window. The user's own moves are
        // never written back into the spec, so sending the old value again is no change.
        if (now.position != before.position && now.position != null) {
            platform.setPosition(now.position.a, now.position.b)
        }
        if (now.zoomLevel != before.zoomLevel && now.zoomLevel != null) platform.setZoomLevel(now.zoomLevel)
        if (now.scheme != before.scheme) platform.setColorScheme(now.scheme)
        entry.applied = now
    }

    private fun configOf(id: Int, spec: WindowSpec) = PlatformWindowConfig(
        nodeId = id,
        title = spec.title,
        width = spec.size?.a ?: 0f,
        height = spec.size?.b ?: 0f,
        minWidth = spec.minSize?.a ?: 0f,
        minHeight = spec.minSize?.b ?: 0f,
        resizable = spec.resizable ?: true,
        chrome = spec.chrome,
        titleBar = spec.titleBar,
        icon = spec.icon,
        x = spec.position?.a,
        y = spec.position?.b,
        restoreKey = spec.restoreKey,
        colorScheme = spec.scheme,
        zoomLevel = spec.zoomLevel,
    )

    private fun readSpec(node: Node): WindowSpec = WindowSpec(
        title = node.text(PropertyKind.WindowTitle),
        size = node.pair(PropertyKind.WindowSize),
        minSize = node.pair(PropertyKind.WindowMinSize),
        resizable = (node.property(PropertyKind.WindowResizable) as? PropertyValue.Bool)?.value,
        chrome = if (node.integer(PropertyKind.WindowChrome) == 2L) Chrome.System else Chrome.Modern,
        titleBar = if (node.integer(PropertyKind.WindowTitleBar) == 2L) TitleBar.Simple else TitleBar.Normal,
        icon = node.integer(PropertyKind.WindowIcon)?.toInt() ?: 0,
        position = node.pair(PropertyKind.WindowPosition),
        restoreKey = node.text(PropertyKind.WindowRestoreKey),
        zoomLevel = table.zoomLevelFor(node.id),
        drag = (node.property(PropertyKind.DragFromPointer) as? PropertyValue.Bytes)?.value?.let { decodeDrag(it) },
        scheme = table.themeFor(node.id)?.colorScheme ?: ColorScheme.FollowSystem,
    )

    // ---- events from the windows ---------------------------------------------------------

    /** Sends the event to the window's node, when the node has a handler of [kind]. */
    private fun emit(entry: Entry, kind: PropertyKind, make: (Long) -> HostEvent) {
        if (entry.dead) return
        val handler = table.node(entry.nodeId)?.handler(kind) ?: return
        host.dispatch(make(handler))
    }

    private inner class Entry(
        val nodeId: Int,
        val order: Int,
        var applied: WindowSpec,
    ) : PlatformWindowListener {
        var platform: PlatformWindow? = null
        var unavailable = false
        var dead = false
        var focused = false
        var pendingBounds: ScreenBounds? = null
        var reportedBounds: ScreenBounds? = null
        var drag: DragState? = null
        var lastZoom: HostEvent.ZoomChanged? = null
        val sizeReporter = WindowSizeReporter(nodeId)

        override fun onCloseRequested() {
            // Nothing is closed here. The application decides, and a window with no handler
            // for it stays where it is.
            emit(this, PropertyKind.OnWindowCloseRequested) { handler ->
                HostEvent.WindowCloseRequested(nodeId, handler)
            }
        }

        override fun onFocusChanged(focused: Boolean) {
            if (dead || this.focused == focused) return
            this.focused = focused
            emit(this, PropertyKind.OnWindowFocusChanged) { handler ->
                HostEvent.WindowFocusChanged(nodeId, handler, focused)
            }
        }

        override fun onMoved(bounds: ScreenBounds) {
            if (!dead) pendingBounds = bounds
        }

        override fun onFollowEnded(cancelled: Boolean) {
            if (!dead) endDrag(this, cancelled)
        }

        override fun onClosedBySystem() {
            if (dead) return
            platform = null
            drag = null
            emit(this, PropertyKind.OnWindowClosed) { handler -> HostEvent.WindowClosed(nodeId, handler) }
        }

        override fun onContentSizeChanged(widthDp: Float, heightDp: Float) {
            if (!dead) sizeReporter.report(widthDp, heightDp, host)
        }

        override fun onZoomChanged(k: Float, os: Float, level: Int) {
            if (dead) return
            val event = HostEvent.ZoomChanged(nodeId = nodeId, handlerId = 0, k = k, os = os, level = level)
            if (lastZoom == event) return
            lastZoom = event
            host.dispatch(event)
        }
    }

    // ---- frames --------------------------------------------------------------------------

    /**
     * Waits for the first frame any open window draws, and calls [onFrame] once for it.
     *
     * The frame driver of the whole process waits here, so the Host is asked for a frame once
     * however many windows there are, and a hidden window's stopped clock holds nothing
     * up. With no window able to answer it waits until it is cancelled, which is what lets the
     * driver's stopped-clock rule serve the request directly.
     */
    suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        val windows = entries.values.mapNotNull { it.platform }
        if (windows.isEmpty()) awaitCancellation()
        coroutineScope {
            val first = CompletableDeferred<Unit>()
            val clocks = ArrayList<Job>(windows.size)
            for (window in windows) {
                clocks += launch {
                    window.awaitFrame { nanos ->
                        if (first.complete(Unit)) onFrame(nanos)
                    }
                }
            }
            first.await()
            clocks.forEach { it.cancel() }
        }
    }

    /**
     * Sends what happened since the last frame: each window's last move, once, and each
     * dragged window's pointer. Called once per frame, after the Host's batch is applied.
     */
    fun flushFrame() {
        for (entry in entries.values.toList()) {
            if (entry.dead) continue
            val bounds = entry.pendingBounds
            if (bounds != null) {
                entry.pendingBounds = null
                if (bounds != entry.reportedBounds) {
                    entry.reportedBounds = bounds
                    emit(entry, PropertyKind.OnWindowMoved) { handler ->
                        HostEvent.WindowMoved(entry.nodeId, handler, bounds.x, bounds.y, bounds.width, bounds.height)
                    }
                }
            }
            if (!entry.dead && entry.drag != null) pollDrag(entry)
        }
    }

    // ---- dragging a window --------------------------------------------------------

    private class DragState(val pointerId: Int) {
        var lastX = Float.NaN
        var lastY = Float.NaN
        var over: DropTarget? = null
        var overWindow = 0
    }

    private fun startDrag(entry: Entry, spec: DragSpec) {
        val platform = entry.platform ?: return
        // The node that held the pointer lets go of it (it gets a Cancel), the window takes over.
        cancelCapture(spec.pointerId)
        if (!platform.followPointer(spec.anchorX, spec.anchorY)) return
        entry.drag = DragState(spec.pointerId)
    }

    private fun pollDrag(entry: Entry) {
        val drag = entry.drag ?: return
        val pointer = entry.platform?.pointerOnScreen() ?: return
        if (pointer.x == drag.lastX && pointer.y == drag.lastY) return
        drag.lastX = pointer.x
        drag.lastY = pointer.y
        val under = windowUnder(pointer.x, pointer.y, entry)
        drag.overWindow = under?.nodeId ?: 0
        emit(entry, PropertyKind.OnWindowDrag) { handler ->
            HostEvent.WindowDrag(entry.nodeId, handler, DRAG_MOVE, pointer.x, pointer.y, drag.overWindow)
        }
        if (entry.dead || entry.drag !== drag) return
        val target = under?.let { targetUnder(it, pointer.x, pointer.y) }
        val previous = drag.over
        if (previous != null && target?.nodeId != previous.nodeId) {
            sendDragOver(entry, previous, OVER_LEAVE)
        }
        if (entry.dead || entry.drag !== drag) return
        if (target != null) {
            val kind = if (previous?.nodeId == target.nodeId) OVER_MOVE else OVER_ENTER
            sendDragOver(entry, target, kind)
        }
        if (!entry.dead && entry.drag === drag) drag.over = target
    }

    private fun endDrag(entry: Entry, cancelled: Boolean) {
        val drag = entry.drag ?: return
        entry.drag = null
        val pointer = entry.platform?.pointerOnScreen()
        val x = pointer?.x ?: (if (drag.lastX.isNaN()) 0f else drag.lastX)
        val y = pointer?.y ?: (if (drag.lastY.isNaN()) 0f else drag.lastY)
        val under = if (pointer == null) null else windowUnder(x, y, entry)
        val previous = drag.over
        val target = if (pointer == null) previous else under?.let { targetUnder(it, x, y) }
        when {
            // Released over a subscribed node it is a drop; cancelled, the node is left.
            target != null -> sendDragOver(entry, target, if (cancelled) OVER_LEAVE else OVER_DROP)
            previous != null -> sendDragOver(entry, previous, OVER_LEAVE)
        }
        emit(entry, PropertyKind.OnWindowDrag) { handler ->
            HostEvent.WindowDrag(
                entry.nodeId,
                handler,
                if (cancelled) DRAG_CANCEL else DRAG_END,
                x,
                y,
                if (pointer == null) drag.overWindow else under?.nodeId ?: 0,
            )
        }
    }

    private fun sendDragOver(entry: Entry, target: DropTarget, kind: Int) {
        val handler = table.node(target.nodeId)?.handler(PropertyKind.OnWindowDragOver) ?: return
        host.dispatch(HostEvent.WindowDragOver(target.nodeId, handler, kind, entry.nodeId, target.x, target.y))
    }

    /** The subscribed node under a screen point of [window], or null. */
    private fun targetUnder(window: Entry, screenX: Float, screenY: Float): DropTarget? {
        val origin = window.platform?.contentOrigin() ?: return null
        val found = dropTargets.targetAt(window.nodeId, screenX - origin.x, screenY - origin.y) ?: return null
        // Only a node that registered for it, and still exists, is a target.
        return if (table.node(found.nodeId)?.handler(PropertyKind.OnWindowDragOver) != null) found else null
    }

    /**
     * The window under a screen point other than the one being dragged: the key window when
     * it is there, else the one opened last.
     */
    private fun windowUnder(screenX: Float, screenY: Float, except: Entry): Entry? {
        var best: Entry? = null
        for (candidate in entries.values) {
            if (candidate === except || candidate.dead) continue
            val platform = candidate.platform ?: continue
            val frame = platform.bounds() ?: continue
            if (!frame.contains(screenX, screenY)) continue
            val current = best
            if (current == null) {
                best = candidate
            } else if (platform.isKey) {
                best = candidate
            } else if (current.platform?.isKey != true && candidate.order > current.order) {
                best = candidate
            }
        }
        return best
    }

    // ---- value types ---------------------------------------------------------------------

    private class Waiting(
        val request: Int,
        val key: String,
        val window: PlatformWindow?,
        val tear: TearOff?,
    ) {
        var taken = false
    }

    private class TearOff(val pointerId: Int, val anchorX: Float, val anchorY: Float)

    private data class Pair2(val a: Float, val b: Float)

    private data class DragSpec(val pointerId: Int, val anchorX: Float, val anchorY: Float)

    /** The properties of a `Window` node that the platform window follows. */
    private data class WindowSpec(
        val title: String,
        val size: Pair2?,
        val minSize: Pair2?,
        val resizable: Boolean?,
        val chrome: Chrome,
        val titleBar: TitleBar,
        val icon: Int,
        val position: Pair2?,
        val restoreKey: String,
        val zoomLevel: Int?,
        val drag: DragSpec?,
        val scheme: ColorScheme,
    ) {
        companion object {
            /** What a window the system made already is assumed to have, so all of a node is news to it. */
            val Blank = WindowSpec(
                title = "\u0000",
                size = null,
                minSize = null,
                resizable = null,
                chrome = Chrome.Modern,
                titleBar = TitleBar.Normal,
                icon = 0,
                position = null,
                restoreKey = "",
                zoomLevel = null,
                drag = null,
                scheme = ColorScheme.FollowSystem,
            )
        }
    }

    private fun Node.integer(kind: PropertyKind): Long? = (property(kind) as? PropertyValue.Integer)?.value

    private fun Node.pair(kind: PropertyKind): Pair2? {
        val bytes = bytes(kind) ?: return null
        if (bytes.size < 8) return null
        return Pair2(readFloat(bytes, 0), readFloat(bytes, 4))
    }

    private fun decodeDrag(bytes: ByteArray): DragSpec? {
        if (bytes.size < 12) return null
        return DragSpec(readInt(bytes, 0), readFloat(bytes, 4), readFloat(bytes, 8))
    }

    private fun encodeDrag(pointerId: Int, anchorX: Float, anchorY: Float): ByteArray {
        val out = ByteArray(12)
        writeInt(out, 0, pointerId)
        writeInt(out, 4, anchorX.toRawBits())
        writeInt(out, 8, anchorY.toRawBits())
        return out
    }

    private companion object {
        const val MAX_SYNC_TURNS = 16

        /** `WindowDrag` phases. */
        const val DRAG_MOVE = 1
        const val DRAG_END = 2
        const val DRAG_CANCEL = 3

        /** `WindowDragOver` kinds. */
        const val OVER_ENTER = 1
        const val OVER_MOVE = 2
        const val OVER_LEAVE = 3
        const val OVER_DROP = 4

        fun readInt(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)

        fun readFloat(bytes: ByteArray, at: Int): Float = Float.fromBits(readInt(bytes, at))

        fun writeInt(bytes: ByteArray, at: Int, value: Int) {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
            bytes[at + 2] = (value shr 16).toByte()
            bytes[at + 3] = (value shr 24).toByte()
        }
    }
}
