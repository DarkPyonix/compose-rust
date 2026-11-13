package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import dev.darkpyonix.composerust.design.HostPlatform
import dev.darkpyonix.composerust.design.detectHostPlatform
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.PointerKind
import dev.darkpyonix.composerust.protocol.PointerType
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.WheelDeltaMode
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.hostPlatformOverride
import dev.darkpyonix.composerust.runtime.islandDensity
import dev.darkpyonix.composerust.ui.floatProp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.isShiftPressed as isShiftHeld
import androidx.compose.ui.input.pointer.PointerType as ComposePointerType
import dev.darkpyonix.composerust.ui.node.Node as HostNode

/**
 * Pointer events for the nodes that subscribed to them.
 *
 * A node subscribes with a handler and a mask of kinds. Only such a node carries the
 * modifier below; a node without a subscription has no pointer input of its own, so a screen
 * of plain containers pays nothing for this.
 *
 * What a subscriber is told, and in which order:
 *
 * - The deepest subscriber under the pointer hears an event first. If its handler does not
 *   consume it, the nearest subscribed ancestor hears it next, each with the position in its
 *   own space. `Enter` and `Leave` do not travel: every node gets its own.
 * - A node that took a `Down` keeps that pointer until it is released or cancelled. Its
 *   `Move` and `Up` start at that node wherever the pointer is, and no node is told about
 *   entering or leaving until the press is over.
 * - `Move` is held and sent once a frame for each node, with the latest position. `Wheel`
 *   is held the same way with its travel added up. Every other kind is sent at once, after
 *   whatever was being held, so the order a handler sees is the order things happened in.
 * - A consumed `Down`, `Up` or `ContextMenu` is consumed for Compose as well, so no click
 *   and no menu follows it. A consumed `Move` only stops at the handler: taking the move
 *   from Compose would cancel the click of every pressable thing around it.
 *
 * Positions are in dp from the node's top left corner. Compose has already taken the
 * position back through every layer the node is drawn through, so a rotated node reports
 * where in its own unrotated box the pointer is.
 */

/**
 * The unit a wheel's travel is reported in.
 *
 * Compose hands over travel in its own scroll units, one for a notch of a wheel, which is
 * what a line is. A platform whose window hands over travel in pixels says so here, and the
 * travel is then sent in dp like a position.
 */
var hostWheelDeltaMode: WheelDeltaMode = WheelDeltaMode.Line

internal const val POINTER_BUTTON_PRIMARY = 1
internal const val POINTER_BUTTON_SECONDARY = 2
internal const val POINTER_BUTTON_TERTIARY = 4
internal const val POINTER_BUTTON_BACK = 8
internal const val POINTER_BUTTON_FORWARD = 16

private const val MODIFIER_SHIFT = 1
private const val MODIFIER_CTRL = 2
private const val MODIFIER_ALT = 4
private const val MODIFIER_META = 8

/** How many pointers can be down at once before the oldest capture is forgotten. */
private const val MAX_POINTERS = 16

/** The bit of one kind in a subscription mask: the kind's wire tag, less one, as a shift. */
internal fun pointerKindBit(kind: PointerKind): Int = when (kind) {
    PointerKind.Move -> 1
    PointerKind.Enter -> 1 shl 1
    PointerKind.Leave -> 1 shl 2
    PointerKind.Down -> 1 shl 3
    PointerKind.Up -> 1 shl 4
    PointerKind.Cancel -> 1 shl 5
    PointerKind.Wheel -> 1 shl 6
    PointerKind.ContextMenu -> 1 shl 7
}

/** The kinds a node subscribed to, or 0 for a node that subscribed to none. */
internal fun HostNode.pointerKinds(): Int =
    (property(PropertyKind.PointerKinds) as? PropertyValue.Integer)?.value?.toInt() ?: 0

/** Whether a node has both halves of a subscription: a handler and at least one kind. */
internal fun HostNode.subscribesToPointer(): Boolean =
    handler(PropertyKind.OnPointer) != null && pointerKinds() != 0

/**
 * The modifier of a subscribed node. Call it only for a node that subscribes.
 *
 * The scale positions are divided by is the node's own: inside an HTML island that is the
 * island's, which carries its zoom, so the Host is handed the island's own pixels and can
 * test them against its layout without knowing the zoom. The root of an island is sized
 * among native widgets but reports in the island's pixels too, because the Host tests the
 * same layout against it.
 */
@Composable
internal fun hostPointerEvents(
    node: HostNode,
    router: PointerRouter,
    dispatcher: EventDispatcher,
): Modifier {
    val outer = LocalDensity.current
    val zoom = LocalZoom.current
    val islandZoom = node.floatProp(PropertyKind.IslandZoom)
    val pxPerUnit = if (islandZoom != null) islandDensity(outer, zoom, islandZoom).density else outer.density
    val view = LocalViewConfiguration.current
    val controlClickIsMenu = remember {
        (hostPlatformOverride ?: detectHostPlatform()) == HostPlatform.MacOs
    }
    return HostPointerElement(
        node,
        router,
        dispatcher,
        pxPerUnit,
        view.doubleTapTimeoutMillis,
        view.longPressTimeoutMillis,
        view.touchSlop,
        controlClickIsMenu,
        hostWheelDeltaMode,
    )
}

private data class HostPointerElement(
    val node: HostNode,
    val router: PointerRouter,
    val dispatcher: EventDispatcher,
    val pxPerUnit: Float,
    val repeatMillis: Long,
    val longPressMillis: Long,
    val slop: Float,
    val controlClickIsMenu: Boolean,
    val wheelMode: WheelDeltaMode,
) : ModifierNodeElement<HostPointerNode>() {
    override fun create(): HostPointerNode = HostPointerNode(node, router, dispatcher).also(::configure)

    override fun update(node: HostPointerNode) {
        if (node.host !== this.node || node.router !== router) {
            // A different node or table behind the same place in the tree: nothing held
            // for the old one belongs to the new one.
            node.router.forget(node)
            if (node.router !== router) {
                node.router.detached(node)
                router.attached(node)
            }
            node.host = this.node
            node.router = router
            node.clearHeld()
        }
        node.dispatcher = dispatcher
        configure(node)
    }

    private fun configure(node: HostPointerNode) {
        node.pxPerUnit = if (pxPerUnit > 0f) pxPerUnit else 1f
        node.repeatMillis = repeatMillis
        node.longPressMillis = longPressMillis
        node.slop = slop
        node.controlClickIsMenu = controlClickIsMenu
        node.wheelMode = wheelMode
    }
}

/**
 * One subscribed node's place in the pointer path, and everything held for it between an
 * event and the frame it is sent in. All of it is fields of a fixed size, so holding a move
 * or adding up a wheel allocates nothing.
 */
internal class HostPointerNode(
    var host: HostNode,
    var router: PointerRouter,
    var dispatcher: EventDispatcher,
) : Modifier.Node(), PointerInputModifierNode, KeyInputModifierNode {
    var pxPerUnit = 1f
    var repeatMillis = 300L
    var longPressMillis = 500L
    var slop = 0f
    var controlClickIsMenu = false
    var wheelMode = WheelDeltaMode.Line

    /** The event being dispatched, as this node sees it: its positions are this node's own. */
    var current: PointerEvent? = null

    // The latest the node was told by Compose, which is what the next record carries.
    var lastX = 0f
    var lastY = 0f
    var lastPointerId = 1
    var lastPointerType = PointerType.Mouse
    var lastButtons = 0
    var lastModifiers = 0
    var lastUptimeMillis = 0L

    /** Whether the Host has been told the pointer is inside. */
    var inside = false

    /** Whether the pointer is inside, which the Host is told once no press holds it back. */
    var wantInside = false

    /** Inside only because a finger is down: there is no hover to say it left. */
    var insideWhilePressed = false

    /** Pointers down in this node or under it. */
    var pressed = 0

    var movePending = false
    var wheelPending = false
    var wheelX = 0f
    var wheelY = 0f
    var wheelPendingMode = WheelDeltaMode.Line

    /**
     * What the handler answered to the last wheel. A wheel is added up and sent at the next
     * frame, and by then Compose has finished with the event, so whether Compose may scroll
     * with it is decided by the last answer.
     */
    var wheelTaken = false

    private var longPress: Job? = null

    fun mask(): Int = host.pointerKinds()

    fun wants(kind: PointerKind): Boolean = mask() and pointerKindBit(kind) != 0

    override fun onAttach() {
        router.attached(this)
    }

    override fun onDetach() {
        router.forget(this)
        router.detached(this)
        clearHeld()
    }

    fun clearHeld() {
        current = null
        inside = false
        wantInside = false
        insideWhilePressed = false
        pressed = 0
        movePending = false
        wheelPending = false
        wheelX = 0f
        wheelY = 0f
        wheelTaken = false
        cancelLongPress()
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        when (pass) {
            // On the way down every subscriber in the path says it is there. The deepest
            // one is the first to see the main pass, and by then the whole path is known.
            PointerEventPass.Initial -> {
                current = pointerEvent
                router.begin(this)
                if (pointerEvent.type == PointerEventType.Scroll && wheelTaken && wants(PointerKind.Wheel)) {
                    val changes = pointerEvent.changes
                    for (index in changes.indices) changes[index].consume()
                }
            }
            PointerEventPass.Main -> router.finish(this, pointerEvent)
            else -> Unit
        }
    }

    override fun onCancelPointerInput() {
        router.cancel(this)
    }

    /** The menu key and Shift with F10 ask for the menu of whatever holds the focus. */
    override fun onKeyEvent(event: ComposeKeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val menuKey = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftHeld)
        if (!menuKey || !wants(PointerKind.ContextMenu)) return false
        router.flush()
        lastButtons = 0
        return send(PointerKind.ContextMenu)
    }

    override fun onPreKeyEvent(event: ComposeKeyEvent): Boolean = false

    /** Keeps what Compose said about one pointer, in this node's space. */
    fun remember(change: PointerInputChange, event: PointerEvent) {
        lastX = change.position.x
        lastY = change.position.y
        val type = change.type
        lastPointerType = when (type) {
            ComposePointerType.Touch -> PointerType.Touch
            ComposePointerType.Stylus, ComposePointerType.Eraser -> PointerType.Pen
            else -> PointerType.Mouse
        }
        lastPointerId = if (lastPointerType == PointerType.Mouse) 1 else change.id.value.toInt()
        lastButtons = buttonsOf(event, change)
        lastModifiers = modifiersOf(event)
        lastUptimeMillis = change.uptimeMillis
    }

    fun changeFor(pointer: Long): PointerInputChange? {
        val changes = current?.changes ?: return null
        for (index in changes.indices) {
            val change = changes[index]
            if (change.id.value == pointer) return change
        }
        return null
    }

    /**
     * Sends one record built from what the node last heard, and answers whether the handler
     * consumed it. A node the Host has already removed is sent nothing: the Host has no
     * handler left to hear it.
     */
    fun send(
        kind: PointerKind,
        button: Int = 0,
        clickCount: Int = 0,
        deltaX: Float = 0f,
        deltaY: Float = 0f,
        deltaMode: WheelDeltaMode = WheelDeltaMode.Pixel,
    ): Boolean {
        val handler = host.handler(PropertyKind.OnPointer) ?: return false
        if (!router.alive(host.id)) return false
        return dispatcher.dispatch(
            HostEvent.Pointer(
                nodeId = host.id,
                handlerId = handler,
                kind = kind,
                pointerId = lastPointerId,
                pointerType = lastPointerType,
                x = lastX / pxPerUnit,
                y = lastY / pxPerUnit,
                buttons = lastButtons,
                button = button,
                modifiers = lastModifiers,
                clickCount = clickCount,
                deltaX = deltaX,
                deltaY = deltaY,
                deltaMode = deltaMode,
                timestampNanos = lastUptimeMillis * 1_000_000L,
            ),
        )
    }

    fun armLongPress() {
        cancelLongPress()
        if (!isAttached) return
        val wait = longPressMillis
        longPress = coroutineScope.launch {
            delay(wait)
            longPress = null
            router.longPressed(this@HostPointerNode)
        }
    }

    fun cancelLongPress() {
        longPress?.cancel()
        longPress = null
    }
}

/** The buttons held, as the wire's bits. A finger or a pen tip that is down is the primary one. */
private fun buttonsOf(event: PointerEvent, change: PointerInputChange): Int {
    val buttons = event.buttons
    var bits = 0
    if (buttons.isPrimaryPressed) bits = bits or POINTER_BUTTON_PRIMARY
    if (buttons.isSecondaryPressed) bits = bits or POINTER_BUTTON_SECONDARY
    if (buttons.isTertiaryPressed) bits = bits or POINTER_BUTTON_TERTIARY
    if (buttons.isBackPressed) bits = bits or POINTER_BUTTON_BACK
    if (buttons.isForwardPressed) bits = bits or POINTER_BUTTON_FORWARD
    if (bits == 0 && change.type != ComposePointerType.Mouse && change.pressed) bits = POINTER_BUTTON_PRIMARY
    return bits
}

private fun modifiersOf(event: PointerEvent): Int {
    val held = event.keyboardModifiers
    var bits = 0
    if (held.isShiftPressed) bits = bits or MODIFIER_SHIFT
    if (held.isCtrlPressed) bits = bits or MODIFIER_CTRL
    if (held.isAltPressed) bits = bits or MODIFIER_ALT
    if (held.isMetaPressed) bits = bits or MODIFIER_META
    return bits
}

/**
 * Routes what Compose dispatches to the subscribed nodes of one tree.
 *
 * Compose walks the nodes under a pointer twice: outermost to innermost, then back out.
 * Every subscriber joins [stack] on the way in, so when the innermost one is reached on the
 * way out the whole chain of subscribers is known and the event is routed along it once,
 * innermost first. The subscribers further out then find the pointer already routed and do
 * nothing.
 *
 * [alive] answers whether the Host still has a node. A node the Host removed stays composed
 * until the next frame, and nothing is sent for it in between.
 */
internal class PointerRouter(val alive: (Int) -> Boolean) {
    /** Counts up when something starts waiting for a frame, which is what the frame loop watches. */
    val pendingGeneration = mutableIntStateOf(0)

    /** How many nodes carry the pointer modifier, which is how many subscribed. */
    var subscribedNodes = 0
        private set

    private var stack = arrayOfNulls<HostPointerNode>(16)
    private var depth = 0

    /** The pointers already routed in the dispatch that is under way. */
    private val routed = LongArray(MAX_POINTERS)
    private var routedCount = 0

    private val capturePointers = LongArray(MAX_POINTERS)
    private val captureNodes = arrayOfNulls<HostPointerNode>(MAX_POINTERS)

    /** Each held pointer as the Host knows it: the `pointer_id` its records carry. */
    private val captureWireIds = IntArray(MAX_POINTERS)
    private var captures = 0

    /**
     * Pointers whose press a torn-off window took over ([cancelPointer]). The node that held
     * one has had its Cancel, so nothing more of that press is sent.
     */
    private val takenPointers = LongArray(MAX_POINTERS)
    private var takenCount = 0

    /** Nodes with a move or a wheel waiting for the frame, innermost first. */
    private val pending = ArrayList<HostPointerNode>()
    private var scheduled = false
    private var flushing = false

    /** Nodes the pointer entered or left during a press, told once the press is over. */
    private val unsettled = ArrayList<HostPointerNode>()

    // The run of presses in one place, which is what a click count is.
    private var runNode: HostPointerNode? = null
    private var runButton = 0
    private var runCount = 0
    private var runUptimeMillis = 0L
    private var runX = 0f
    private var runY = 0f

    /** The buttons held after the last event seen, which the next one is compared with. */
    private var heldButtons = 0
    private var seenButtons = 0

    // The touch that may turn into a long press, and the chain it would be routed along.
    private var longPressChain = arrayOfNulls<HostPointerNode>(16)
    private var longPressDepth = 0
    private var longPressPointer = 0L
    private var longPressX = 0f
    private var longPressY = 0f

    val hasPending: Boolean get() = pending.isNotEmpty()

    fun attached(node: HostPointerNode) {
        subscribedNodes++
    }

    fun detached(node: HostPointerNode) {
        subscribedNodes--
    }

    /** Drops everything held for a node that is going away. Nothing is sent. */
    fun forget(node: HostPointerNode) {
        pending.remove(node)
        unsettled.remove(node)
        releaseCapturesOf(node)
        if (runNode === node) runNode = null
        for (index in 0 until longPressDepth) {
            if (longPressChain[index] === node) {
                disarmLongPress()
                break
            }
        }
        val at = stackIndexOf(node)
        if (at >= 0) {
            for (index in at until depth - 1) stack[index] = stack[index + 1]
            stack[--depth] = null
        }
        node.current = null
    }

    fun begin(node: HostPointerNode) {
        // A node still on the stack was left there by a dispatch that did not finish.
        val stale = stackIndexOf(node)
        if (stale >= 0) truncate(stale)
        if (depth == stack.size) stack = stack.copyOf(depth * 2)
        stack[depth++] = node
    }

    fun finish(node: HostPointerNode, event: PointerEvent) {
        val top = stackIndexOf(node)
        if (top < 0) return
        truncate(top + 1)
        val changes = event.changes
        for (index in changes.indices) {
            val change = changes[index]
            if (markRouted(change.id.value)) route(top, event, change)
        }
        node.current = null
        stack[top] = null
        depth = top
        if (depth == 0) {
            routedCount = 0
            heldButtons = seenButtons
        }
    }

    private fun stackIndexOf(node: HostPointerNode): Int {
        for (index in depth - 1 downTo 0) if (stack[index] === node) return index
        return -1
    }

    private fun truncate(to: Int) {
        while (depth > to) {
            depth--
            stack[depth]?.current = null
            stack[depth] = null
        }
        if (depth == 0) routedCount = 0
    }

    private fun markRouted(pointer: Long): Boolean {
        for (index in 0 until routedCount) if (routed[index] == pointer) return false
        if (routedCount < routed.size) routed[routedCount++] = pointer
        return true
    }

    private fun route(top: Int, event: PointerEvent, change: PointerInputChange) {
        if (takenCount > 0 && isTaken(change.id.value) && swallowTaken(top, event, change)) return
        when (event.type) {
            PointerEventType.Press -> press(top, event, change)
            PointerEventType.Release -> release(top, event, change)
            PointerEventType.Scroll -> wheel(top, change)
            PointerEventType.Move, PointerEventType.Enter, PointerEventType.Exit -> motion(top, change)
            else -> Unit
        }
    }

    private fun press(top: Int, event: PointerEvent, change: PointerInputChange) {
        val deepest = stack[top] ?: return
        val pointer = change.id.value
        val mouse = change.type == ComposePointerType.Mouse
        val buttons = buttonsOf(event, change)
        seenButtons = buttons
        var button = (buttons and heldButtons.inv()).takeLowestOneBit()
        if (button == 0) button = buttons.takeLowestOneBit()
        if (button == 0) button = POINTER_BUTTON_PRIMARY
        val modifiers = modifiersOf(event)
        // The platform's way of asking for a menu is a menu and not a press: the secondary
        // button anywhere, and Control with the primary one where that is the convention.
        val menu = mouse && (
            button == POINTER_BUTTON_SECONDARY ||
                (button == POINTER_BUTTON_PRIMARY && deepest.controlClickIsMenu && modifiers and MODIFIER_CTRL != 0)
            )
        val kind = if (menu) PointerKind.ContextMenu else PointerKind.Down
        val count = if (menu) 0 else countPress(deepest, change, button)
        val newPointer = change.changedToDownIgnoreConsumed()
        val start = captureStart(pointer, top)
        flush()
        // A press lands inside every node of the chain. A finger has no hover to have said
        // so already, so the node is entered here and left when the finger lifts.
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            val own = entry.changeFor(pointer) ?: continue
            entry.remember(own, entry.current ?: event)
            if (newPointer) entry.pressed++
            if (!entry.inside && captures == 0) {
                entry.inside = true
                entry.wantInside = true
                if (!mouse) entry.insideWhilePressed = true
                if (entry.wants(PointerKind.Enter)) entry.send(PointerKind.Enter)
            }
        }
        var consumed = false
        for (index in start downTo 0) {
            val entry = stack[index] ?: continue
            if (entry.changeFor(pointer) == null || !entry.wants(kind)) continue
            if (kind == PointerKind.Down && captureOf(pointer) == null) capture(pointer, entry)
            if (entry.send(kind, button = if (menu) 0 else button, clickCount = count)) {
                consumed = true
                break
            }
        }
        if (consumed) change.consume()
        if (!mouse && newPointer) armLongPress(top, pointer, change)
    }

    private fun release(top: Int, event: PointerEvent, change: PointerInputChange) {
        val pointer = change.id.value
        val buttons = buttonsOf(event, change)
        seenButtons = buttons
        var button = (heldButtons and buttons.inv()).takeLowestOneBit()
        if (button == 0) button = POINTER_BUTTON_PRIMARY
        val count = if (runNode != null && runButton == button) runCount else 1
        val start = captureStart(pointer, top)
        flush()
        var consumed = false
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            val own = entry.changeFor(pointer) ?: continue
            entry.remember(own, entry.current ?: event)
            if (index > start || consumed || !entry.wants(PointerKind.Up)) continue
            if (entry.send(PointerKind.Up, button = button, clickCount = count)) consumed = true
        }
        if (consumed) change.consume()
        if (change.pressed) return
        // Nothing is held any more: the press is over, and so is everything that hung on it.
        releaseCapture(pointer)
        if (longPressPointer == pointer) disarmLongPress()
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            if (entry.changeFor(pointer) == null) continue
            if (entry.pressed > 0) entry.pressed--
            if (entry.insideWhilePressed && entry.pressed == 0) {
                entry.insideWhilePressed = false
                entry.inside = false
                entry.wantInside = false
                if (entry.wants(PointerKind.Leave)) entry.send(PointerKind.Leave)
            }
        }
        settle()
    }

    private fun motion(top: Int, change: PointerInputChange) {
        val pointer = change.id.value
        val start = captureStart(pointer, top)
        if (longPressDepth > 0 && longPressPointer == pointer) {
            val armed = longPressChain[longPressDepth - 1]
            val moved = armed == null ||
                armed !== stack[top] ||
                abs(change.position.x - longPressX) > armed.slop ||
                abs(change.position.y - longPressY) > armed.slop
            if (moved) disarmLongPress()
        }
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            val event = entry.current ?: continue
            val own = entry.changeFor(pointer) ?: continue
            when (event.type) {
                PointerEventType.Enter -> hover(entry, own, event, inside = true)
                PointerEventType.Exit -> hover(entry, own, event, inside = false)
                else -> {
                    entry.remember(own, event)
                    if (index <= start && entry.wants(PointerKind.Move)) {
                        entry.movePending = true
                        enqueue(entry)
                    }
                }
            }
        }
        stack[top]?.let { seenButtons = it.lastButtons }
    }

    private fun hover(entry: HostPointerNode, change: PointerInputChange, event: PointerEvent, inside: Boolean) {
        entry.wantInside = inside
        if (captures > 0 || takenCount > 0) {
            // A press holds the pointer. Who it is over is told when the press ends.
            entry.remember(change, event)
            if (!unsettled.contains(entry)) unsettled.add(entry)
            return
        }
        if (entry.inside == inside) {
            entry.remember(change, event)
            return
        }
        val kind = if (inside) PointerKind.Enter else PointerKind.Leave
        // What was held goes first, with the position it was held with.
        if (entry.wants(kind)) flush()
        entry.remember(change, event)
        entry.inside = inside
        if (!inside) entry.insideWhilePressed = false
        if (entry.wants(kind)) entry.send(kind)
    }

    /** Tells the nodes the pointer entered or left during a press, now that it is over. */
    private fun settle() {
        if (captures > 0 || takenCount > 0 || unsettled.isEmpty()) return
        var index = 0
        while (index < unsettled.size) {
            val entry = unsettled[index++]
            if (entry.inside == entry.wantInside) continue
            entry.inside = entry.wantInside
            val kind = if (entry.inside) PointerKind.Enter else PointerKind.Leave
            if (entry.wants(kind)) {
                flush()
                entry.send(kind)
            }
        }
        unsettled.clear()
    }

    private fun wheel(top: Int, change: PointerInputChange) {
        val pointer = change.id.value
        val deltaX = change.scrollDelta.x
        val deltaY = change.scrollDelta.y
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            val event = entry.current ?: continue
            val own = entry.changeFor(pointer) ?: continue
            entry.remember(own, event)
            if (!entry.wants(PointerKind.Wheel)) continue
            val mode = entry.wheelMode
            // Travel that is not added to what is held is sent first, not mixed into it.
            if (entry.wheelPending && entry.wheelPendingMode != mode) flush()
            // Pixels are a length like a position is, so they are sent in the same unit.
            val scale = if (mode == WheelDeltaMode.Pixel) 1f / entry.pxPerUnit else 1f
            entry.wheelX += deltaX * scale
            entry.wheelY += deltaY * scale
            entry.wheelPendingMode = mode
            entry.wheelPending = true
            enqueue(entry)
        }
    }

    /**
     * Compose took the pointer back: the window lost it, a system gesture began, a touch
     * was cancelled. A node that had a press is told once, and the press and its capture end.
     */
    fun cancel(node: HostPointerNode) {
        val at = stackIndexOf(node)
        if (at >= 0) truncate(at)
        val held = node.pressed > 0 || holdsCapture(node)
        if (held) flush()
        node.pressed = 0
        node.movePending = false
        node.wheelPending = false
        node.wheelX = 0f
        node.wheelY = 0f
        // A Host that answers a held Move by tearing a window off cancels from inside the
        // flush that sent it. The flush is walking the list and empties it when it is done.
        if (!flushing) pending.remove(node)
        unsettled.remove(node)
        releaseCapturesOf(node)
        for (index in 0 until longPressDepth) {
            if (longPressChain[index] === node) {
                disarmLongPress()
                break
            }
        }
        heldButtons = 0
        seenButtons = 0
        node.lastButtons = 0
        if (held && node.wants(PointerKind.Cancel)) node.send(PointerKind.Cancel)
        // A finger that was down is gone with its press. A mouse is still where it was, and
        // Compose says so itself when it leaves.
        if (node.insideWhilePressed) {
            node.inside = false
            node.wantInside = false
            node.insideWhilePressed = false
            if (node.wants(PointerKind.Leave)) node.send(PointerKind.Leave)
        }
        settle()
    }

    /**
     * A window was torn off under a held pointer and follows it from here on: the node that
     * holds [pointerId] (the id its records carry, 1 for the mouse) is told once with a
     * Cancel, and its press and capture end. Answers whether a node held it.
     *
     * The window the press began in goes on hearing that press from the system, its drags and
     * its release. None of it is sent: after the Cancel there is no press for a Move or an Up
     * to belong to.
     */
    fun cancelPointer(pointerId: Int): Boolean {
        for (index in 0 until captures) {
            if (captureWireIds[index] != pointerId) continue
            val node = captureNodes[index] ?: continue
            if (takenCount == takenPointers.size) removeTakenAt(0)
            takenPointers[takenCount++] = capturePointers[index]
            cancel(node)
            return true
        }
        return false
    }

    /**
     * What is left of a press a torn-off window took over. Answers true when the event was
     * part of that press and has been dropped, and false when the press turns out to be over
     * already, so the event is routed as any other.
     *
     * Where the pointer is goes on being kept, and is told as an Enter or a Leave once the
     * button is let go, as after any press.
     */
    private fun swallowTaken(top: Int, event: PointerEvent, change: PointerInputChange): Boolean {
        val pointer = change.id.value
        val released = event.type == PointerEventType.Release && !change.pressed
        // A button that is up, or a press that begins, with no release heard in between: the
        // release went somewhere this tree did not hear it. The press is over all the same.
        if (!released && (!change.pressed || change.changedToDownIgnoreConsumed())) {
            releaseTaken(pointer)
            settle()
            return false
        }
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            val own = entry.changeFor(pointer) ?: continue
            val seen = entry.current ?: event
            when (seen.type) {
                PointerEventType.Enter -> hover(entry, own, seen, inside = true)
                PointerEventType.Exit -> hover(entry, own, seen, inside = false)
                else -> entry.remember(own, seen)
            }
        }
        seenButtons = buttonsOf(event, change)
        if (!released) return true
        releaseTaken(pointer)
        if (longPressPointer == pointer) disarmLongPress()
        for (index in top downTo 0) {
            val entry = stack[index] ?: continue
            if (entry.changeFor(pointer) == null) continue
            if (entry.pressed > 0) entry.pressed--
            if (entry.insideWhilePressed && entry.pressed == 0) {
                entry.insideWhilePressed = false
                entry.inside = false
                entry.wantInside = false
                if (entry.wants(PointerKind.Leave)) entry.send(PointerKind.Leave)
            }
        }
        settle()
        return true
    }

    private fun isTaken(pointer: Long): Boolean {
        for (index in 0 until takenCount) if (takenPointers[index] == pointer) return true
        return false
    }

    private fun releaseTaken(pointer: Long) {
        for (index in 0 until takenCount) {
            if (takenPointers[index] == pointer) {
                removeTakenAt(index)
                return
            }
        }
    }

    private fun removeTakenAt(at: Int) {
        for (index in at until takenCount - 1) takenPointers[index] = takenPointers[index + 1]
        takenCount--
    }

    private fun countPress(deepest: HostPointerNode, change: PointerInputChange, button: Int): Int {
        val x = change.position.x
        val y = change.position.y
        val again = runNode === deepest &&
            runButton == button &&
            change.uptimeMillis - runUptimeMillis <= deepest.repeatMillis &&
            abs(x - runX) <= deepest.slop &&
            abs(y - runY) <= deepest.slop
        runCount = if (again) runCount + 1 else 1
        runNode = deepest
        runButton = button
        runUptimeMillis = change.uptimeMillis
        runX = x
        runY = y
        return runCount
    }

    private fun enqueue(node: HostPointerNode) {
        // Taken out and put back, so the list stays innermost first whichever node was
        // already waiting.
        pending.remove(node)
        pending.add(node)
        if (!scheduled) {
            scheduled = true
            pendingGeneration.intValue++
        }
    }

    /**
     * Sends every held move and wheel, innermost node first. Called at the start of a frame,
     * and before anything that is sent at once so nothing overtakes a move that came first.
     */
    fun flush() {
        if (flushing) return
        scheduled = false
        if (pending.isEmpty()) return
        flushing = true
        try {
            var moveTaken = false
            var moveTakenPointer = 0
            var wheelTaken = false
            var index = 0
            while (index < pending.size) {
                val node = pending[index++]
                if (node.movePending) {
                    node.movePending = false
                    if (!(moveTaken && moveTakenPointer == node.lastPointerId) && node.send(PointerKind.Move)) {
                        moveTaken = true
                        moveTakenPointer = node.lastPointerId
                    }
                }
                if (node.wheelPending) {
                    node.wheelPending = false
                    val deltaX = node.wheelX
                    val deltaY = node.wheelY
                    node.wheelX = 0f
                    node.wheelY = 0f
                    if (!wheelTaken) {
                        val taken = node.send(
                            PointerKind.Wheel,
                            deltaX = deltaX,
                            deltaY = deltaY,
                            deltaMode = node.wheelPendingMode,
                        )
                        node.wheelTaken = taken
                        wheelTaken = taken
                    }
                }
            }
        } finally {
            pending.clear()
            flushing = false
        }
    }

    private fun captureOf(pointer: Long): HostPointerNode? {
        for (index in 0 until captures) if (capturePointers[index] == pointer) return captureNodes[index]
        return null
    }

    /** Where in the stack routing starts: at the node holding the pointer, or at the innermost. */
    private fun captureStart(pointer: Long, top: Int): Int {
        val holder = captureOf(pointer) ?: return top
        for (index in top downTo 0) if (stack[index] === holder) return index
        return top
    }

    private fun capture(pointer: Long, node: HostPointerNode) {
        if (captures == MAX_POINTERS) removeCaptureAt(0)
        capturePointers[captures] = pointer
        captureNodes[captures] = node
        captureWireIds[captures] = node.lastPointerId
        captures++
    }

    private fun holdsCapture(node: HostPointerNode): Boolean {
        for (index in 0 until captures) if (captureNodes[index] === node) return true
        return false
    }

    private fun releaseCapture(pointer: Long) {
        for (index in 0 until captures) {
            if (capturePointers[index] == pointer) {
                removeCaptureAt(index)
                return
            }
        }
    }

    private fun releaseCapturesOf(node: HostPointerNode) {
        var index = 0
        while (index < captures) {
            if (captureNodes[index] === node) removeCaptureAt(index) else index++
        }
    }

    private fun removeCaptureAt(at: Int) {
        for (index in at until captures - 1) {
            capturePointers[index] = capturePointers[index + 1]
            captureNodes[index] = captureNodes[index + 1]
            captureWireIds[index] = captureWireIds[index + 1]
        }
        captures--
        captureNodes[captures] = null
    }

    /** A finger held still long enough asks for the menu. Only armed where a node wants one. */
    private fun armLongPress(top: Int, pointer: Long, change: PointerInputChange) {
        disarmLongPress()
        var wanted = false
        for (index in top downTo 0) if (stack[index]?.wants(PointerKind.ContextMenu) == true) wanted = true
        if (!wanted) return
        if (longPressChain.size <= top) longPressChain = arrayOfNulls(stack.size)
        for (index in 0..top) longPressChain[index] = stack[index]
        longPressDepth = top + 1
        longPressPointer = pointer
        longPressX = change.position.x
        longPressY = change.position.y
        stack[top]?.armLongPress()
    }

    private fun disarmLongPress() {
        if (longPressDepth == 0) return
        longPressChain[longPressDepth - 1]?.cancelLongPress()
        for (index in 0 until longPressDepth) longPressChain[index] = null
        longPressDepth = 0
    }

    fun longPressed(owner: HostPointerNode) {
        if (longPressDepth == 0 || longPressChain[longPressDepth - 1] !== owner) return
        flush()
        for (index in longPressDepth - 1 downTo 0) {
            val entry = longPressChain[index] ?: continue
            if (entry.wants(PointerKind.ContextMenu) && entry.send(PointerKind.ContextMenu)) break
        }
        for (index in 0 until longPressDepth) longPressChain[index] = null
        longPressDepth = 0
    }
}
