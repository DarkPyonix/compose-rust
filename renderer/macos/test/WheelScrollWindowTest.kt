@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSView
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGEventCreateScrollWheelEvent2
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGScrollEventUnit
import platform.CoreGraphics.kCGScrollEventUnitLine
import platform.CoreGraphics.kCGScrollEventUnitPixel
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// A mouse wheel and a trackpad scroll the renderer's scroll containers in a real Kotlin/Native
// macOS window, the way an AppKit view scrolls.
//
// The scroll events are AppKit's own, made with CGEventCreateScrollWheelEvent2 in lines (a
// wheel) and in pixels (a trackpad), and handed to the view the window finds under the
// pointer, as AppKit hands them. Each container starts in the middle of its content, so a
// scroll the wrong way moves it the wrong way instead of stopping at the top, and none of
// them covers the window's top left corner: a box that does not scroll sits there, so a
// scroll that reached the scene at the corner instead of under the pointer moves nothing.
//
// The scroll position is what the container reports to the Host (`ScrollChanged`), in the
// scene's pixels. AppKit counts a scroll's travel the other way round from a scroll position:
// with natural scrolling off, a wheel turned toward the reader is -3 lines and brings what is
// below into view, so the position grows. A line is 10 points, NSScrollView's line, and a
// trackpad moves the content as far as the fingers went.

private const val ROOT = 1
private const val CORNER = 2
private const val LIST = 3
private const val FILLER = 4
private const val LAZY = 5
private const val FIRST_ITEM = 100
private const val LIST_HANDLER = 61L
private const val LAZY_HANDLER = 62L

/** The window and its parts, in points. */
private const val WINDOW_W = 480
private const val WINDOW_H = 320
private const val CORNER_W = 100f
private const val COLUMN_W = 150f
private const val COLUMN_H = 260f
private const val ITEM_H = 40f
private const val ITEMS = 100

/** Where each container starts: well inside its content, so both directions can move. */
private const val LIST_START_PX = 600.0
private const val LAZY_START_ITEM = 20

/** NSScrollView's line, in points. */
private const val LINE_POINTS = 10.0

private fun spin(seconds: Double) {
    val begun = kotlin.time.TimeSource.Monotonic.markNow()
    while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
        NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.005))
    }
}

/** A Host that answers with the batches a test queues, one per frame, and keeps what it heard. */
private class WheelHost(private val initial: List<Mutation>) : HostConnection {
    val events = mutableListOf<HostEvent>()
    private val queued = ArrayDeque<List<Mutation>>()

    fun queue(mutations: List<Mutation>) = queued.addLast(mutations)

    override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)

    override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
        events += event
        return 0
    }

    override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {
        queued.removeFirstOrNull()?.forEach(onMutation)
    }

    override fun shutdown() {}
}

private class Wheel(val window: MacosWindow, val connection: WheelHost, val host: ComposeRustHost) {
    val scale: Double get() = window.window.backingScaleFactor

    fun send(vararg mutations: Mutation) {
        connection.queue(mutations.toList())
        host.renderFrame(0L)
        window.requestFrame()
        spin(0.25)
    }

    private fun reports(node: Int) = connection.events.filterIsInstance<HostEvent.ScrollChanged>().filter { it.nodeId == node }

    /**
     * Where [node] is once it has stopped moving, in the scene's pixels from the top of its
     * content: the offset for the scroll column, the first item's place plus the offset into
     * it for the lazy column.
     */
    fun settled(node: Int): Double {
        var last = reports(node).size
        var still = 0
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (begun.elapsedNow().inWholeMilliseconds < 3000) {
            spin(0.05)
            val now = reports(node)
            val done = now.lastOrNull()?.let { it.flags and ScrollWire.CHANGED_SETTLED != 0 } == true
            if (now.size == last && done) still++ else still = 0
            last = now.size
            if (still >= 8) break
        }
        val report = assertNotNull(reports(node).lastOrNull(), "node $node never reported where it is")
        return if (node == LAZY) report.item * ITEM_H * scale + report.yPx else report.yPx
    }

    /** A point [x], [y] in content points from the top left, in the window's coordinates. */
    private fun inWindow(x: Double, y: Double): CValue<CGPoint> {
        val content = window.window.contentView!!
        val height = content.bounds.useContents { size.height }
        return content.convertPoint(NSMakePoint(x, height - y), toView = null)
    }

    /** The view the window finds under content point [x], [y]: the scene's own view. */
    private fun viewAt(x: Double, y: Double): NSView {
        val content = window.window.contentView!!
        return content.hitTest(content.superview!!.convertPoint(inWindow(x, y), fromView = null))!!
    }

    private var pointer = 0.0 to 0.0

    /**
     * Moves the pointer to [x], [y]. A move reaches a view through its tracking area, from the
     * window server; one made here is handed to the view under the point, as AppKit would.
     */
    fun moveTo(x: Double, y: Double) {
        pointer = x to y
        viewAt(x, y).mouseMoved(
            NSEvent.mouseEventWithType(
                type = NSEventTypeMouseMoved,
                location = inWindow(x, y),
                modifierFlags = 0u,
                timestamp = NSProcessInfo.processInfo.systemUptime,
                windowNumber = window.window.windowNumber,
                context = null,
                eventNumber = 0L,
                clickCount = 1,
                pressure = 0f,
            )!!,
        )
        spin(0.1)
    }

    /**
     * AppKit's scroll of [vertical] in [unit] where the pointer is: positive asks for what is
     * above, as AppKit's scrollingDeltaY counts, and negative for what is below.
     */
    fun scroll(vertical: Int, unit: CGScrollEventUnit) {
        val made = CGEventCreateScrollWheelEvent2(
            source = null,
            units = unit,
            wheelCount = 1u,
            wheel1 = vertical,
            wheel2 = 0,
            wheel3 = 0,
        )!!
        try {
            viewAt(pointer.first, pointer.second).scrollWheel(NSEvent.eventWithCGEvent(made)!!)
        } finally {
            CFRelease(made)
        }
    }

    /** [points] in the scene's pixels. */
    fun px(points: Double): Double = points * scale
}

private fun box(id: Int, width: Float, height: Float, argb: Int? = null): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Box))
    add(Mutation.SetModifier(id, 0, ProtocolModifier.Size(width, height)))
    if (argb != null) add(Mutation.SetModifier(id, 1, ProtocolModifier.Background(Paint.Literal(argb))))
}

/**
 * A row filling the window: a box that does not scroll at the top left corner, then a scroll
 * column and a lazy column, each [COLUMN_W] by [COLUMN_H] points and far taller inside.
 */
private fun page(): List<Mutation> = buildList {
    add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
    add(Mutation.Create(ROOT, WidgetKind.Row))
    add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.FillMaxHeight))
    addAll(box(CORNER, CORNER_W, WINDOW_H.toFloat(), 0xFFE0E0E0.toInt()))
    add(Mutation.Insert(ROOT, CORNER, 0))

    add(Mutation.Create(LIST, WidgetKind.ScrollColumn))
    add(Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(COLUMN_W, COLUMN_H)))
    add(Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(LIST_HANDLER)))
    add(Mutation.Insert(ROOT, LIST, 1))
    addAll(box(FILLER, COLUMN_W, 3000f, 0xFF00C000.toInt()))
    add(Mutation.Insert(LIST, FILLER, 0))

    add(Mutation.Create(LAZY, WidgetKind.LazyColumn))
    add(Mutation.SetModifier(LAZY, 0, ProtocolModifier.Size(COLUMN_W, COLUMN_H)))
    add(Mutation.SetProp(LAZY, PropertyKind.ItemCount, PropertyValue.Integer(ITEMS.toLong())))
    add(Mutation.SetProp(LAZY, PropertyKind.OnScrollChanged, PropertyValue.Integer(LAZY_HANDLER)))
    add(Mutation.Insert(ROOT, LAZY, 2))
    for (index in 0 until ITEMS) {
        val id = FIRST_ITEM + index
        addAll(box(id, COLUMN_W, ITEM_H, if (index % 2 == 0) 0xFF0000E0.toInt() else 0xFF8080FF.toInt()))
        add(Mutation.SetProp(id, PropertyKind.ItemKey, PropertyValue.Text("item-$index")))
        add(Mutation.Insert(LAZY, id, index))
    }
}

/** The middle of the scroll column and of the lazy column, in content points. */
private val LIST_CENTER = (CORNER_W + COLUMN_W / 2.0) to (COLUMN_H / 2.0)
private val LAZY_CENTER = (CORNER_W + COLUMN_W + COLUMN_W / 2.0) to (COLUMN_H / 2.0)

private fun withWheelWindow(body: (Wheel) -> Unit) {
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    application.finishLaunching()
    application.activateIgnoringOtherApps(true)
    val window = MacosWindow(name = "fr13 wheel", width = WINDOW_W, height = WINDOW_H)
    val connection = WheelHost(page())
    val host = ComposeRustHost(connection)
    host.start()
    val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
    try {
        @Suppress("DEPRECATION")
        window.setContent { ComposeRustContent(host) }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        window.window.makeKeyAndOrderFront(null)
        spin(0.6)
        if (!window.window.keyWindow) {
            // This process is not the active application, and macOS does not let it take the
            // front by asking. The window is told it is key, as AppKit would tell it.
            println("wheel: the window cannot become key here; told it is key directly")
            window.window.becomeKeyWindow()
        }
        val wheel = Wheel(window, connection, host)
        wheel.send(
            Mutation.ScrollTo(LIST, 0, ScrollWire.TO_Y, 0.0, LIST_START_PX),
            Mutation.ScrollTo(LAZY, LAZY_START_ITEM, ScrollWire.TO_Y, 0.0, 0.0),
        )
        body(wheel)
    } finally {
        timer.invalidate()
        window.window.orderOut(null)
        host.shutdown()
    }
}

/** Whether [moved] pixels is [expected] give or take a tenth, and never less than two pixels. */
private fun near(moved: Double, expected: Double): Boolean = abs(moved - expected) <= maxOf(2.0, abs(expected) / 10)

class WheelScrollWindowTest {
    private fun wheelTurnsTheContainerUnderThePointer(node: Int, other: Int, at: Pair<Double, Double>) = withWheelWindow { w ->
        val start = w.settled(node)
        val otherStart = w.settled(other)
        w.moveTo(at.first, at.second)

        // Toward the reader: what is below comes into view, three lines of it.
        w.scroll(-3, kCGScrollEventUnitLine)
        val down = w.settled(node) - start
        val three = w.px(3 * LINE_POINTS)
        assertTrue(
            down > 0,
            "a wheel turned toward the reader over node $node moved it by $down pixels; " +
                "it should bring what is below into view, $three pixels (3 lines of $LINE_POINTS points)",
        )
        assertTrue(near(down, three), "three lines down moved node $node by $down pixels, not $three")

        // Away from the reader: back up by the same three lines.
        w.scroll(3, kCGScrollEventUnitLine)
        val back = w.settled(node) - start
        assertTrue(near(back, 0.0), "three lines back up left node $node $back pixels from where it started")

        // Only the container under the pointer moved, not the other one and not what lies at
        // the window's corner.
        assertTrue(
            near(w.settled(other) - otherStart, 0.0),
            "node $other, not under the pointer, moved by ${w.settled(other) - otherStart} pixels",
        )
    }

    @Test
    fun fr13_a_wheel_over_a_scroll_column_scrolls_it_the_way_it_turned() =
        wheelTurnsTheContainerUnderThePointer(LIST, LAZY, LIST_CENTER)

    @Test
    fun fr13_a_wheel_over_a_lazy_column_scrolls_it_the_way_it_turned() =
        wheelTurnsTheContainerUnderThePointer(LAZY, LIST, LAZY_CENTER)

    @Test
    fun fr13_a_trackpad_moves_a_scroll_container_as_far_as_the_fingers_moved() = withWheelWindow { w ->
        for ((node, at) in listOf(LIST to LIST_CENTER, LAZY to LAZY_CENTER)) {
            val start = w.settled(node)
            w.moveTo(at.first, at.second)
            // Two fingers pushed up with natural scrolling on: AppKit's -40 points, and the
            // content follows the fingers up by forty points.
            w.scroll(-40, kCGScrollEventUnitPixel)
            val up = w.settled(node) - start
            val forty = w.px(40.0)
            assertTrue(up > 0, "a trackpad push up over node $node moved it by $up pixels; it should be $forty")
            assertTrue(near(up, forty), "forty points of trackpad moved node $node by $up pixels, not $forty")
            w.scroll(40, kCGScrollEventUnitPixel)
            val back = w.settled(node) - start
            assertTrue(near(back, 0.0), "forty points back left node $node $back pixels from where it started")
        }
    }
}
