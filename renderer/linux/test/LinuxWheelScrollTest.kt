@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
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
import dev.darkpyonix.composerust.runtime.Zoom
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.getenv
import x11.ButtonPress
import x11.ButtonPressMask
import x11.ButtonRelease
import x11.ButtonReleaseMask
import x11.MotionNotify
import x11.PointerMotionMask
import x11.XCloseDisplay
import x11.XEvent
import x11.XFlush
import x11.XOpenDisplay
import x11.XSendEvent
import x11.glXGetCurrentDrawable

/**
 * The X11 wheel scrolls the renderer's scroll containers in a real window the way it turns: a
 * notch down (button 5, toward the reader) brings what is below into view and a notch up
 * (button 4) goes back toward the top, each as far as a desktop Compose application on the
 * same system scrolls (three lines a notch, a line the square root of the container's height
 * in pixels).
 *
 * The presses come from another client of the display server, as XSendEvent, after a move
 * that puts the pointer over the container: a wheel press carries no place for the scene, so
 * the scene scrolls what is under the last move. Each container starts in the middle of its
 * content, so a scroll the wrong way moves it the wrong way instead of stopping at the top.
 * The scroll position is what the container reports to the Host (`ScrollChanged`), in pixels.
 *
 * Needs a display; CI runs it under Xvfb. Every test closes the window it opened, because the
 * windows of a process share one connection that closes with the last one.
 */
class LinuxWheelScrollTest {

    private class Recorder : PlatformWindowListener {
        override fun onCloseRequested() {}
        override fun onFocusChanged(focused: Boolean) {}
        override fun onMoved(bounds: ScreenBounds) {}
        override fun onFollowEnded(cancelled: Boolean) {}
        override fun onZoomChanged(k: Float, os: Float, level: Int) {}
    }

    /** A Host that plays one first batch and keeps the events it was sent. */
    private class WheelHost(private val initial: List<Mutation>) : HostConnection {
        val events = mutableListOf<HostEvent>()
        override fun init(onMutation: (Mutation) -> Unit) = initial.forEach(onMutation)
        override fun dispatchEvent(event: HostEvent, onMutation: (Mutation) -> Unit): Long {
            events += event
            return 0
        }
        override fun renderFrame(frameTimeNanos: Long, onMutation: (Mutation) -> Unit) {}
        override fun shutdown() {}
    }

    /** Another client of the display server, sending the window what the server would. */
    private class Peer(private val id: ULong) {
        private val display = XOpenDisplay(null)!!

        fun motion(x: Int, y: Int) {
            memScoped {
                val event = alloc<XEvent>()
                event.xmotion.type = MotionNotify
                event.xmotion.window = id
                event.xmotion.x = x
                event.xmotion.y = y
                event.xmotion.same_screen = 1
                XSendEvent(display, id, 0, PointerMotionMask, event.ptr)
            }
            XFlush(display)
        }

        /** A notch of the wheel: X11 reports it as a press and a release of [button]. */
        fun notch(button: Int, x: Int, y: Int) {
            for (type in listOf(ButtonPress, ButtonRelease)) memScoped {
                val event = alloc<XEvent>()
                event.xbutton.type = type
                event.xbutton.window = id
                event.xbutton.x = x
                event.xbutton.y = y
                event.xbutton.same_screen = 1
                event.xbutton.button = button.toUInt()
                val mask = if (type == ButtonPress) ButtonPressMask else ButtonReleaseMask
                XSendEvent(display, id, 0, mask, event.ptr)
            }
            XFlush(display)
        }

        fun close() {
            XCloseDisplay(display)
        }
    }

    private class Wheel(
        val backend: LinuxWindowBackend,
        val window: LinuxPlatformWindow,
        val connection: WheelHost,
        val peer: Peer,
    ) {
        val scale: Float get() = window.window.x11.measure().scale

        fun reports(node: Int) =
            connection.events.filterIsInstance<HostEvent.ScrollChanged>().filter { it.nodeId == node }

        /** Turns the loop for about [millis] milliseconds. */
        fun turn(millis: Int) {
            repeat(maxOf(1, millis / 16)) { backend.turn() }
        }

        /**
         * Where [node] is once it has stopped moving, in pixels from the top of its content:
         * the offset for the scroll column, the first item's place plus the offset into it for
         * the lazy column.
         */
        fun settled(node: Int): Double {
            var last = reports(node).size
            var still = 0
            for (turn in 0 until 200) {
                backend.turn()
                val now = reports(node)
                val done = now.lastOrNull()?.let { it.flags and ScrollWire.CHANGED_SETTLED != 0 } == true
                if (now.size == last && done) still++ else still = 0
                last = now.size
                if (still >= 10) break
            }
            val report = assertNotNull(reports(node).lastOrNull(), "node $node never reported where it is")
            return if (node == LAZY) report.item * ITEM_H * scale + report.yPx else report.yPx
        }

        /** The height the container reported, which is what the wheel's line is worked out from. */
        fun viewportHeight(node: Int): Double = reports(node).last().viewportHeightPx

        fun at(points: Pair<Float, Float>): Pair<Int, Int> =
            (points.first * scale).toInt() to (points.second * scale).toInt()
    }

    private fun hasDisplay(): Boolean {
        if (!getenv("DISPLAY")?.toKString().isNullOrEmpty()) return true
        assertTrue(getenv("CI") == null, "no DISPLAY under CI, so the X11 wheel was not tested")
        return false
    }

    private fun box(id: Int, width: Float, height: Float, argb: Int): List<Mutation> = listOf(
        Mutation.Create(id, WidgetKind.Box),
        Mutation.SetModifier(id, 0, ProtocolModifier.Size(width, height)),
        Mutation.SetModifier(id, 1, ProtocolModifier.Background(Paint.Literal(argb))),
    )

    /**
     * A row filling the window: a box that does not scroll at the top left corner, then a
     * scroll column and a lazy column, each [COLUMN_W] by [COLUMN_H] points and far taller
     * inside, each already scrolled into the middle of what it holds.
     */
    private fun page(): List<Mutation> = buildList {
        add(Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false)))
        add(Mutation.Create(ROOT, WidgetKind.Row))
        add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.FillMaxWidth))
        add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.FillMaxHeight))
        addAll(box(CORNER, CORNER_W, 320f, 0xFFE0E0E0.toInt()))
        add(Mutation.Insert(ROOT, CORNER, 0))

        add(Mutation.Create(LIST, WidgetKind.ScrollColumn))
        add(Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(COLUMN_W, COLUMN_H)))
        add(Mutation.SetProp(LIST, PropertyKind.OnScrollChanged, PropertyValue.Integer(61L)))
        add(Mutation.Insert(ROOT, LIST, 1))
        addAll(box(FILLER, COLUMN_W, 3000f, 0xFF00C000.toInt()))
        add(Mutation.Insert(LIST, FILLER, 0))

        add(Mutation.Create(LAZY, WidgetKind.LazyColumn))
        add(Mutation.SetModifier(LAZY, 0, ProtocolModifier.Size(COLUMN_W, COLUMN_H)))
        add(Mutation.SetProp(LAZY, PropertyKind.ItemCount, PropertyValue.Integer(ITEMS.toLong())))
        add(Mutation.SetProp(LAZY, PropertyKind.OnScrollChanged, PropertyValue.Integer(62L)))
        add(Mutation.Insert(ROOT, LAZY, 2))
        for (index in 0 until ITEMS) {
            val id = FIRST_ITEM + index
            addAll(box(id, COLUMN_W, ITEM_H, if (index % 2 == 0) 0xFF0000E0.toInt() else 0xFF8080FF.toInt()))
            add(Mutation.SetProp(id, PropertyKind.ItemKey, PropertyValue.Text("item-$index")))
            add(Mutation.Insert(LAZY, id, index))
        }
        // Applied once each container has been measured.
        add(Mutation.ScrollTo(LIST, 0, ScrollWire.TO_Y, 0.0, LIST_START_PX))
        add(Mutation.ScrollTo(LAZY, LAZY_START_ITEM, ScrollWire.TO_Y, 0.0, 0.0))
    }

    private fun withWheelWindow(body: (Wheel) -> Unit) {
        if (!hasDisplay()) return
        val connection = WheelHost(page())
        val host = ComposeRustHost(connection)
        host.start()
        val backend = LinuxWindowBackend(mainForTests(), makeZoom = { Zoom() }) { { ComposeRustContent(host) } }
        try {
            // System chrome: the content starts at the window's top left, with no strip drawn
            // above it, so a point in the content is the same point in the X window.
            val config = PlatformWindowConfig(nodeId = 1, title = "fr13 wheel", width = 480f, height = 320f, chrome = Chrome.System)
            val window = assertNotNull(backend.open(config, Recorder()), "no window on a display that is there") as LinuxPlatformWindow
            backend.turn()
            // The window's GL context is current on its own window, which is the X window.
            val id = glXGetCurrentDrawable()
            assertTrue(id != 0uL, "the window made no GL drawable current")
            val peer = Peer(id)
            try {
                body(Wheel(backend, window, connection, peer))
            } finally {
                peer.close()
            }
        } finally {
            backend.closeAll()
            host.shutdown()
        }
    }

    private fun near(moved: Double, expected: Double): Boolean = abs(moved - expected) <= maxOf(2.0, abs(expected) / 10)

    private fun wheelScrollsTheContainerUnderThePointer(node: Int, other: Int, center: Pair<Float, Float>) = withWheelWindow { w ->
        val start = w.settled(node)
        val otherStart = w.settled(other)
        val (x, y) = w.at(center)
        w.peer.motion(x, y)
        w.turn(100)

        // A notch down, toward the reader: what is below comes into view.
        w.peer.notch(BUTTON_DOWN, x, y)
        val down = w.settled(node) - start
        val notch = 3 * sqrt(w.viewportHeight(node))
        assertTrue(
            down > 0,
            "a wheel notch down (button 5) over node $node moved it by $down pixels; it should bring what is " +
                "below into view, $notch pixels (3 lines of the square root of its height)",
        )
        assertTrue(near(down, notch), "a notch down moved node $node by $down pixels, not $notch")

        // A notch up, away from the reader: back toward the top by as much.
        w.peer.notch(BUTTON_UP, x, y)
        val back = w.settled(node) - start
        assertTrue(near(back, 0.0), "a notch down and a notch up left node $node $back pixels from where it started")

        val otherMoved = w.settled(other) - otherStart
        assertTrue(near(otherMoved, 0.0), "node $other, not under the pointer, moved by $otherMoved pixels")
    }

    @Test
    fun fr13_linux_a_wheel_over_a_scroll_column_scrolls_it_the_way_it_turned() =
        wheelScrollsTheContainerUnderThePointer(LIST, LAZY, (CORNER_W + COLUMN_W / 2) to (COLUMN_H / 2))

    @Test
    fun fr13_linux_a_wheel_over_a_lazy_column_scrolls_it_the_way_it_turned() =
        wheelScrollsTheContainerUnderThePointer(LAZY, LIST, (CORNER_W + COLUMN_W + COLUMN_W / 2) to (COLUMN_H / 2))

    private companion object {
        const val ROOT = 1
        const val CORNER = 2
        const val LIST = 3
        const val FILLER = 4
        const val LAZY = 5
        const val FIRST_ITEM = 100
        const val CORNER_W = 100f
        const val COLUMN_W = 150f
        const val COLUMN_H = 260f
        const val ITEM_H = 40f
        const val ITEMS = 100
        const val LIST_START_PX = 600.0
        const val LAZY_START_ITEM = 20

        /** X11's wheel buttons: 4 is a notch away from the reader, 5 toward. */
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
    }
}
