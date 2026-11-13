@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.darkpyonix.composerust.test

import androidx.compose.runtime.CompositionLocalProvider
import dev.darkpyonix.composerust.foundation.LocalNativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeHole
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.NativeViewPlatform
import dev.darkpyonix.composerust.foundation.NativeViewWindow
import dev.darkpyonix.composerust.foundation.NativeViewWindowEvents
import dev.darkpyonix.composerust.foundation.pointerKindBit
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.DesignSystem
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.NativeViewWire
import dev.darkpyonix.composerust.protocol.Paint
import dev.darkpyonix.composerust.protocol.PointerKind
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.ScrollWire
import dev.darkpyonix.composerust.protocol.Theme
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.ui.node.TableError
import dev.darkpyonix.composerust.ui.platform.MacosNativeViewWindow
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSColor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventType
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSView
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// `NativeView` in a real Kotlin/Native macOS window, through the fork's native view slot, with
// plain AppKit views standing in for what an application puts there: a solid red `NSView` and
// an `NSTextField`. No web view: the slot is the same for any view, and a web view would only
// make the test slower and the pixels harder to read.
//
// The page is one green colour, the markers the Compose content draws next to the view are
// blue, and the view is red, so the window's own image (CGWindowListCreateImage, which needs no
// permission for the process's own windows) says at a pixel which one is on top. A runner with
// no screen to capture from gives an image with none of those colours; the image checks then
// say so and are skipped, and everything else still runs.
//
// The input method cannot be driven from a test, so the one criterion about it is checked by
// hand, on the macOS Kotlin/Native build of a sample with a NativeView holding an NSTextField
// and a Compose TextField beside it:
//
//   1. Click the NSTextField. Switch the input source to Korean (2-Set).
//   2. Type "한글" one jamo at a time. The marked text and its underline appear inside the
//      NSTextField, and the candidate window (Option+Return, or the hanja key) opens next to
//      the NSTextField, not next to the Compose field.
//   3. The Compose TextField shows nothing of it and has no caret.
//   4. Commit with Return. The NSTextField holds "한글"; the Compose field is still empty.
//   5. Click the Compose TextField (or send FocusNativeView(0) and RequestFocus to it). Type
//      "한글" again. The composition, its underline and the candidate window are the Compose
//      field's, as they are with no NativeView in the window, and the NSTextField does not
//      change.
//   6. Click back into the NSTextField: the input method composes there again.

private const val ROOT = 1
private const val LIST = 2
private const val GAP = 3
private const val MARK_ABOVE = 4
private const val VIEW = 5
private const val MARK_BELOW = 6
private const val FILLER = 7
private const val SECOND = 8
private const val FIELD = 9
private const val POPOVER = 10
private const val ANCHOR = 11
private const val CONTENT = 12
private const val HOVER = 13
private const val FOCUS_HANDLER = 51L
private const val FOCUS_LOST = 52L
private const val POINTER_HANDLER = 53L

private const val GREEN = 0xFF00C000.toInt()
private const val BLUE = 0xFF0000E0.toInt()

/** The view sizes and the column, in points. */
private const val COLUMN_W = 300f
private const val COLUMN_H = 260f
private const val GAP_H = 60f
private const val MARK_H = 40f
private const val VIEW_H = 100f

/** A plain view of one colour that takes the keyboard when clicked, as a web view does. */
private class SolidView : NSView(NSMakeRect(0.0, 0.0, 10.0, 10.0)) {
    init {
        wantsLayer = true
        layer?.backgroundColor = NSColor.redColor.CGColor
    }

    override fun acceptsFirstResponder() = true
    override fun acceptsFirstMouse(event: NSEvent?) = true
}

private fun spin(seconds: Double) {
    val begun = kotlin.time.TimeSource.Monotonic.markNow()
    while (begun.elapsedNow().inWholeMilliseconds < seconds * 1000) {
        NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.005))
    }
}

/** A Host that answers with the batches a test queues, one per frame. */
private class QueuedHost(private val initial: List<Mutation>) : HostConnection {
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

/** The window's slot, with every region it was told kept for the test to read. */
private class RecordingSlot(private val inner: NativeViewWindow) : NativeViewWindow by inner {
    val frames = HashMap<Int, NativeRect>()
    val holes = HashMap<Int, List<NativeHole>>()
    val visible = HashMap<Int, Boolean>()
    val attachOrder = ArrayList<Int>()

    override var events: NativeViewWindowEvents?
        get() = inner.events
        set(value) {
            inner.events = value
        }

    override fun attach(id: Int, handle: Long): Boolean = inner.attach(id, handle).also { if (it) attachOrder += id }

    override fun setFrame(id: Int, x: Int, y: Int, width: Int, height: Int) {
        frames[id] = NativeRect(x, y, width, height)
        inner.setFrame(id, x, y, width, height)
    }

    override fun setVisibleRegion(id: Int, clip: NativeRect?, holes: List<NativeHole>) {
        this.holes[id] = holes
        inner.setVisibleRegion(id, clip, holes)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        this.visible[id] = visible
        inner.setVisible(id, visible)
    }
}

/** What the window's image shows at a point of its content, in points: R the view, G the page, B a marker. */
private class WindowImage(
    private val pixels: ByteArray,
    private val width: Int,
    private val height: Int,
    private val left: Double,
    private val top: Double,
    val scale: Double,
) {
    /** At image pixel ([ix], [iy]). */
    fun pixel(ix: Int, iy: Int): Char {
        if (ix !in 0 until width || iy !in 0 until height) return '?'
        val i = (iy * width + ix) * 4
        val r = pixels[i].toInt() and 0xFF
        val g = pixels[i + 1].toInt() and 0xFF
        val b = pixels[i + 2].toInt() and 0xFF
        return when {
            r > 180 && g < 80 && b < 80 -> 'R'
            g > 140 && r < 80 && b < 80 -> 'G'
            b > 160 && r < 80 && g < 80 -> 'B'
            else -> '?'
        }
    }

    /** At the content point ([x], [y]). */
    fun at(x: Double, y: Double): Char = pixel(column(x), row(y))

    fun column(x: Double): Int = (left + x * scale).toInt()
    fun row(y: Double): Int = (top + y * scale).toInt()
}

private class Fixture(
    val window: MacosWindow,
    val slot: RecordingSlot,
    val connection: QueuedHost,
    val host: ComposeRustHost,
) {
    val scale: Double get() = window.window.backingScaleFactor

    /** Sends one batch, the way a frame the Host asked for would, and lets it draw. */
    fun send(vararg mutations: Mutation, settle: Double = 0.25) {
        connection.queue(mutations.toList())
        host.renderFrame(0L)
        window.requestFrame()
        spin(settle)
    }

    val focus: List<Boolean> get() = connection.events.filterIsInstance<HostEvent.NativeViewFocus>().map { it.focused }
    val errors: List<Int> get() = connection.events.filterIsInstance<HostEvent.ProtocolError>().map { it.code }

    /** A point [x], [y] in content points from the top left, in the window's coordinates. */
    fun inWindow(x: Double, y: Double): CValue<CGPoint> {
        val content = window.window.contentView!!
        val height = content.bounds.useContents { size.height }
        return content.convertPoint(NSMakePoint(x, height - y), toView = null)
    }

    fun mouse(type: NSEventType, x: Double, y: Double): NSEvent =
        NSEvent.mouseEventWithType(
            type = type,
            location = inWindow(x, y),
            modifierFlags = 0u,
            timestamp = NSProcessInfo.processInfo.systemUptime,
            windowNumber = window.window.windowNumber,
            context = null,
            eventNumber = 0L,
            clickCount = 1,
            pressure = if (type == NSEventTypeLeftMouseDown) 1f else 0f,
        )!!

    fun click(x: Double, y: Double) {
        window.window.sendEvent(mouse(NSEventTypeLeftMouseDown, x, y))
        window.window.sendEvent(mouse(NSEventTypeLeftMouseUp, x, y))
        spin(0.2)
    }

    fun move(x: Double, y: Double) {
        window.window.sendEvent(mouse(NSEventTypeMouseMoved, x, y))
        spin(0.1)
    }

    /** The window's own image, or null where there is no screen to take one from. */
    fun image(): WindowImage? {
        @Suppress("DEPRECATION")
        val image: CGImageRef = platform.CoreGraphics.CGWindowListCreateImage(
            platform.CoreGraphics.CGRectNull.readValue(),
            1u shl 3,
            window.window.windowNumber.toUInt(),
            (1u shl 0) or (1u shl 3),
        ) ?: return null
        val width = platform.CoreGraphics.CGImageGetWidth(image).toInt()
        val height = platform.CoreGraphics.CGImageGetHeight(image).toInt()
        val pixels = ByteArray(width * height * 4)
        pixels.usePinned { pinned ->
            val space = platform.CoreGraphics.CGColorSpaceCreateDeviceRGB()
            val context = platform.CoreGraphics.CGBitmapContextCreate(
                pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
                platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            )
            platform.CoreGraphics.CGContextDrawImage(
                context, platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image,
            )
            platform.CoreGraphics.CGContextRelease(context)
            platform.CoreGraphics.CGColorSpaceRelease(space)
        }
        platform.CoreGraphics.CGImageRelease(image)
        val content = window.window.contentView!!
        val rect: CValue<CGRect> = content.convertRect(content.bounds, toView = null)
        val windowHeight = window.window.frame.useContents { size.height }
        val imageScale = width / window.window.frame.useContents { size.width }
        val left = rect.useContents { origin.x } * imageScale
        val top = rect.useContents { windowHeight - (origin.y + size.height) } * imageScale
        val read = WindowImage(pixels, width, height, left, top, imageScale)
        // A screen that is locked or absent gives an image with no page colour in it.
        if (read.at(COLUMN_W + 20.0, 10.0) != 'G') return null
        return read
    }
}

private fun theme() = Mutation.SetTheme(Theme(DesignSystem.Material3, DesignSystem.Material3, ColorScheme.Light, adaptive = false))

private fun box(id: Int, width: Float, height: Float, argb: Int? = null): List<Mutation> = buildList {
    add(Mutation.Create(id, WidgetKind.Box))
    add(Mutation.SetModifier(id, 0, ProtocolModifier.Size(width, height)))
    if (argb != null) add(Mutation.SetModifier(id, 1, ProtocolModifier.Background(Paint.Literal(argb))))
}

private fun nativeView(id: Int, width: Float, height: Float, handle: Long): List<Mutation> = listOf(
    Mutation.Create(id, WidgetKind.NativeView),
    Mutation.SetModifier(id, 0, ProtocolModifier.Size(width, height)),
    Mutation.SetProp(id, PropertyKind.OnNativeViewFocus, PropertyValue.Integer(FOCUS_HANDLER)),
    Mutation.AttachNativeView(id, handle),
)

/**
 * A green page, and on it a column [COLUMN_W] by [COLUMN_H] points that scrolls: a gap, a blue
 * marker, the view, another blue marker, and a long filler. The view starts at y 100.
 */
private fun page(handle: Long, viewCount: Int = 1, extraHandles: List<Long> = emptyList()): List<Mutation> = buildList {
    add(theme())
    add(Mutation.Create(ROOT, WidgetKind.Row))
    add(Mutation.SetModifier(ROOT, 0, ProtocolModifier.FillMaxWidth))
    add(Mutation.SetModifier(ROOT, 1, ProtocolModifier.FillMaxHeight))
    add(Mutation.SetModifier(ROOT, 2, ProtocolModifier.Background(Paint.Literal(GREEN))))
    add(Mutation.Create(LIST, WidgetKind.ScrollColumn))
    add(Mutation.SetModifier(LIST, 0, ProtocolModifier.Size(COLUMN_W, COLUMN_H)))
    add(Mutation.Insert(ROOT, LIST, 0))
    addAll(box(GAP, COLUMN_W, GAP_H))
    add(Mutation.Insert(LIST, GAP, 0))
    addAll(box(MARK_ABOVE, COLUMN_W, MARK_H, BLUE))
    add(Mutation.Insert(LIST, MARK_ABOVE, 1))
    if (handle != 0L) {
        addAll(nativeView(VIEW, COLUMN_W, VIEW_H, handle))
    } else {
        addAll(box(VIEW, COLUMN_W, VIEW_H))
    }
    add(Mutation.Insert(LIST, VIEW, 2))
    addAll(box(MARK_BELOW, COLUMN_W, MARK_H, BLUE))
    add(Mutation.Insert(LIST, MARK_BELOW, 3))
    // More views below, for the benchmark.
    extraHandles.forEachIndexed { index, extra ->
        val id = 100 + index
        if (extra != 0L) addAll(nativeView(id, COLUMN_W, 60f, extra)) else addAll(box(id, COLUMN_W, 60f))
        add(Mutation.Insert(LIST, id, 4 + index))
    }
    addAll(box(FILLER, COLUMN_W, 2000f))
    add(Mutation.Insert(LIST, FILLER, 4 + extraHandles.size))
}

private fun scrollTo(y: Double, animate: Boolean = false) =
    Mutation.ScrollTo(LIST, 0, ScrollWire.TO_Y or (if (animate) ScrollWire.TO_ANIMATE else 0), 0.0, y)

/**
 * Opens a window drawing [initial] with a native view slot, and runs [body] in it.
 *
 * `DXC_NATIVE_VIEW_CAPTURE` is not read here: an image this test looked at is not saved.
 */
private fun withWindow(initial: List<Mutation>, body: (Fixture) -> Unit) {
    val application = NSApplication.sharedApplication()
    application.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)
    application.finishLaunching()
    application.activateIgnoringOtherApps(true)
    NativeViewPlatform.available = true
    val window = MacosWindow(name = "fr81 native view", width = 480, height = 320)
    val slot = RecordingSlot(MacosNativeViewWindow(window))
    val connection = QueuedHost(initial)
    val host = ComposeRustHost(connection)
    host.start()
    val timer = NSTimer.timerWithTimeInterval(1.0 / 60.0, repeats = true) { _: NSTimer? -> window.pumpHost(0L) }
    try {
        @Suppress("DEPRECATION")
        window.setContent {
            CompositionLocalProvider(LocalNativeViewWindow provides slot) {
                ComposeRustContent(host)
            }
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        window.window.makeKeyAndOrderFront(null)
        spin(0.6)
        body(Fixture(window, slot, connection, host))
    } finally {
        timer.invalidate()
        window.window.orderOut(null)
        NativeViewPlatform.available = false
    }
}

/** The view at [x] in [image]: the rows it covers, in image pixels, or null when it is not there. */
private fun viewRows(image: WindowImage, x: Double): IntRange? {
    val ix = image.column(x)
    var first = -1
    var last = -1
    for (iy in image.row(0.0) until image.row(COLUMN_H.toDouble())) {
        if (image.pixel(ix, iy) == 'R') {
            if (first < 0) first = iy
            last = iy
        }
    }
    return if (first < 0) null else first..last
}

class NativeViewWindowTest {
    @Test
    fun fr81_parent_handle_is_reported_once_per_window() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            val parents = f.connection.events.filterIsInstance<HostEvent.NativeViewParent>()
            val parent = parents.singleOrNull()
            assertNotNull(parent, "one report for the window: $parents")
            assertEquals(0, parent.nodeId)
            assertTrue(parent.handle != 0L, "the content view's handle")
            assertEquals(f.window.window.contentView!!.objcPtr().toLong(), parent.handle)
            assertEquals(NativeViewWire.PARENT_APPKIT, parent.kind)
            assertEquals(f.window.window.backingScaleFactor.toFloat(), parent.scale)
            // A second view in the window is not a second report.
            val second = SolidView()
            f.send(*nativeView(SECOND, 100f, 40f, second.objcPtr().toLong()).toTypedArray(), Mutation.Insert(ROOT, SECOND, 1))
            assertEquals(1, f.connection.events.count { it is HostEvent.NativeViewParent })
        }
    }

    @Test
    fun fr81_view_follows_layout_and_scroll_in_the_same_frame() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            val scale = f.scale
            val frame = f.slot.frames[VIEW]
            assertEquals(NativeRect(0, ((GAP_H + MARK_H) * scale).toInt(), (COLUMN_W * scale).toInt(), (VIEW_H * scale).toInt()), frame)
            // The NSView's frame, in points from the bottom of its container, is the node's bounds.
            val container = view.superview!!
            val contentHeight = f.window.window.contentView!!.bounds.useContents { size.height }
            container.frame.useContents {
                assertEquals(0.0, origin.x)
                assertEquals(contentHeight - (GAP_H + MARK_H) - VIEW_H, origin.y, 0.5)
                assertEquals(VIEW_H.toDouble(), size.height, 0.5)
            }

            // A 100 point scroll moves the frame 100 points in the frame it is drawn in.
            f.send(scrollTo(100.0 * scale))
            assertEquals(((GAP_H + MARK_H - 100f) * scale).toInt(), f.slot.frames[VIEW]!!.y)

            // While an animated scroll runs, every picture the window shows has the view
            // where the content is: right under the blue marker above it and right over the
            // one below. A view a frame behind the content would leave the green page showing
            // between the two on one side.
            f.send(scrollTo(0.0), settle = 0.3)
            f.connection.queue(listOf(scrollTo(120.0 * scale, animate = true)))
            f.host.renderFrame(0L)
            f.window.requestFrame()
            val x = COLUMN_W / 2.0
            var looked = 0
            val begun = kotlin.time.TimeSource.Monotonic.markNow()
            while (begun.elapsedNow().inWholeMilliseconds < 700) {
                spin(1.0 / 120.0)
                val image = f.image() ?: run {
                    println("native view: no image of the window to look at")
                    return@withWindow
                }
                val rows = viewRows(image, x) ?: continue
                val top = image.row(0.0)
                val bottom = image.row(COLUMN_H.toDouble())
                if (rows.first > top + 3) {
                    val above = (rows.first - 3 until rows.first).map { image.pixel(image.column(x), it) }
                    assertTrue('G' !in above && 'B' in above, "frame $looked: above the view is $above, not the marker")
                }
                if (rows.last < bottom - 3) {
                    val below = (rows.last + 1..rows.last + 3).map { image.pixel(image.column(x), it) }
                    assertTrue('G' !in below && 'B' in below, "frame $looked: below the view is $below, not the marker")
                }
                looked++
            }
            assertTrue(looked >= 5, "the scroll was looked at in $looked pictures")
        }
    }

    @Test
    fun fr81_view_is_clipped_by_its_ancestors() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            val scale = f.scale
            // The view (100 points from y 100) half below the 260 point column: from y 210.
            f.send(Mutation.SetModifier(GAP, 0, ProtocolModifier.Size(COLUMN_W, GAP_H + 110f)))
            val image = f.image()
            if (image != null) {
                assertEquals('R', image.at(COLUMN_W / 2.0, 230.0), "the half inside the column shows")
                assertEquals('G', image.at(COLUMN_W / 2.0, 280.0), "the half below it does not")
            } else {
                println("native view: no image of the window to look at")
            }
            assertEquals(true, f.slot.visible[VIEW])
            // All the way out: hidden.
            f.send(Mutation.SetModifier(GAP, 0, ProtocolModifier.Size(COLUMN_W, GAP_H + 300f)))
            assertEquals(false, f.slot.visible[VIEW])
            assertTrue(view.superview!!.hidden)
            f.image()?.let { shot ->
                assertEquals('G', shot.at(COLUMN_W / 2.0, 250.0), "nothing of the view shows")
            }
            assertTrue(scale > 0)
        }
    }

    @Test
    fun fr81_overlay_above_the_view_shows_through_a_rounded_hole() {
        val view = SolidView()
        val initial = buildList {
            addAll(page(view.objcPtr().toLong()))
            add(Mutation.Create(POPOVER, WidgetKind.Popover))
            add(Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)))
            addAll(box(ANCHOR, 120f, 20f))
            add(Mutation.Insert(POPOVER, ANCHOR, 0))
            addAll(box(CONTENT, 160f, 160f))
            add(Mutation.Insert(POPOVER, CONTENT, 1))
            add(Mutation.Insert(ROOT, POPOVER, 1))
        }
        withWindow(initial) { f ->
            assertTrue(f.slot.holes[VIEW].orEmpty().isEmpty())
            // Opened, its box hangs over the view to its left.
            f.send(
                Mutation.SetProp(POPOVER, PropertyKind.Placement, PropertyValue.Integer(3)),
                Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(true)),
                settle = 0.5,
            )
            val hole = f.slot.holes[VIEW].orEmpty().singleOrNull()
            assertNotNull(hole, "the open popover is a hole in the view: ${f.slot.holes[VIEW]}")
            assertTrue(hole.cornerRadius > 0, "with rounded corners: $hole")
            val scale = f.scale
            val image = f.image()
            if (image != null) {
                val left = hole.rect.x / scale
                val top = hole.rect.y / scale
                val right = hole.rect.right / scale
                val bottom = hole.rect.bottom / scale
                val viewTop = (GAP_H + MARK_H).toDouble()
                val inside = ((maxOf(left, 0.0) + minOf(right, COLUMN_W.toDouble())) / 2) to
                    ((maxOf(top, viewTop) + minOf(bottom, viewTop + VIEW_H)) / 2)
                assertTrue(image.at(inside.first, inside.second) != 'R', "the popover shows in the hole")
                // The corner of the hole's square that the rounding leaves out shows the view.
                if (left > 1 && top >= viewTop && top + 2 < viewTop + VIEW_H) {
                    assertEquals('R', image.at(left + 1, top + 1), "the view in the hole's rounded-off corner")
                }
            } else {
                println("native view: no image of the window to look at")
            }
            f.send(Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)), settle = 0.4)
            assertTrue(f.slot.holes[VIEW].orEmpty().isEmpty(), "closed, the hole is gone")
            f.image()?.let { assertEquals('R', it.at(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0)) }
        }
    }

    @Test
    fun fr81_hidden_view_keeps_its_state() {
        val field = NSTextField(NSMakeRect(0.0, 0.0, 10.0, 10.0))
        withWindow(page(field.objcPtr().toLong())) { f ->
            f.click(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0)
            val editor = f.window.window.firstResponder as? NSTextView
            assertNotNull(editor, "the field is being edited")
            editor.insertText("가나다")
            spin(0.1)
            f.send(Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(false)))
            assertTrue(field.superview!!.hidden, "hidden")
            assertNotNull(field.window, "and still in the window")
            f.send(Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(true)))
            assertFalse(field.superview!!.hidden)
            assertEquals("가나다", field.stringValue, "what was typed is still there")
            assertEquals(listOf(VIEW), f.slot.attachOrder, "the same view, never attached again")
        }
    }

    @Test
    fun fr81_focus_moves_between_the_view_and_the_scene() {
        val view = SolidView()
        val initial = buildList {
            addAll(page(view.objcPtr().toLong()))
            add(Mutation.Create(FIELD, WidgetKind.TextField))
            add(Mutation.SetModifier(FIELD, 0, ProtocolModifier.Width(140f)))
            add(Mutation.SetProp(FIELD, PropertyKind.OnFocusLost, PropertyValue.Integer(FOCUS_LOST)))
            add(Mutation.Insert(ROOT, FIELD, 1))
            add(Mutation.RequestFocus(FIELD))
        }
        withWindow(initial) { f ->
            f.click(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0)
            assertEquals(listOf(true), f.focus)
            assertSame(view, f.window.window.firstResponder, "the view has the keyboard")
            assertTrue(
                f.connection.events.any { it is HostEvent.FocusLost && it.nodeId == FIELD },
                "the Compose field let go of focus",
            )
            f.send(Mutation.FocusNativeView(VIEW, false))
            assertEquals(listOf(true, false), f.focus)
            assertFalse(f.window.window.firstResponder === view)
        }
    }

    @Test
    fun fr81_focus_returns_to_the_scene_on_remove_hide_and_deactivate() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            f.send(Mutation.FocusNativeView(VIEW, true))
            f.send(Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(false)))
            assertEquals(listOf(true, false), f.focus, "hiding gives focus back")
            f.send(Mutation.SetProp(VIEW, PropertyKind.NativeVisible, PropertyValue.Bool(true)))

            f.send(Mutation.FocusNativeView(VIEW, true))
            f.window.window.resignKeyWindow()
            spin(0.2)
            f.window.window.becomeKeyWindow()
            spin(0.2)
            assertEquals(listOf(true, false, true, false, true), f.focus, "deactivated and back")

            f.send(Mutation.Remove(VIEW))
            assertEquals(listOf(true, false, true, false, true, false), f.focus, "removing gives focus back")
            assertFalse(f.window.window.firstResponder === view)
        }
    }

    @Test
    fun fr81_pointer_inside_the_view_belongs_to_the_view() {
        val view = SolidView()
        val initial = buildList {
            addAll(page(view.objcPtr().toLong()))
            addAll(box(HOVER, 100f, 100f))
            add(Mutation.SetProp(HOVER, PropertyKind.OnPointer, PropertyValue.Integer(POINTER_HANDLER)))
            val mask = listOf(PointerKind.Move, PointerKind.Enter, PointerKind.Leave, PointerKind.Down)
                .fold(0) { bits, kind -> bits or pointerKindBit(kind) }
            add(Mutation.SetProp(HOVER, PropertyKind.PointerKinds, PropertyValue.Integer(mask.toLong())))
            add(Mutation.Insert(ROOT, HOVER, 1))
        }
        withWindow(initial) { f ->
            fun kinds() = f.connection.events.filterIsInstance<HostEvent.Pointer>().map { it.kind }
            // Onto the box beside the column, then straight into the view.
            f.move(COLUMN_W + 50.0, 50.0)
            assertTrue(PointerKind.Enter in kinds() || PointerKind.Move in kinds(), "the box hears the pointer: ${kinds()}")
            val before = kinds().size
            f.move(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0)
            assertTrue(PointerKind.Leave in kinds().drop(before), "the box hears the pointer leave: ${kinds()}")
            // Inside the view the scene hears nothing.
            val inside = kinds().size
            f.move(COLUMN_W / 2.0 + 10, GAP_H + MARK_H + VIEW_H / 2.0 + 10)
            f.click(COLUMN_W / 2.0 + 10, GAP_H + MARK_H + VIEW_H / 2.0 + 10)
            assertEquals(inside, kinds().size, "the scene hears no pointer inside the view: ${kinds()}")
            // The scene's cursor does not cover the view's.
            val cursor = platform.AppKit.NSCursor.IBeamCursor
            cursor.set()
            f.move(COLUMN_W / 2.0 + 20, GAP_H + MARK_H + VIEW_H / 2.0)
            assertSame(cursor, platform.AppKit.NSCursor.currentCursor, "the view's cursor stays")
        }
    }

    @Test
    fun fr81_later_attached_view_is_above() {
        val first = SolidView()
        val second = NSView(NSMakeRect(0.0, 0.0, 10.0, 10.0)).apply {
            wantsLayer = true
            layer?.backgroundColor = NSColor.blueColor.CGColor
        }
        withWindow(page(first.objcPtr().toLong())) { f ->
            // The second view over the first, attached after it.
            val overlapping = listOf(
                Mutation.Create(SECOND, WidgetKind.NativeView),
                Mutation.SetModifier(SECOND, 0, ProtocolModifier.Size(COLUMN_W, VIEW_H)),
                Mutation.SetModifier(SECOND, 1, ProtocolModifier.Offset(0f, -(VIEW_H + MARK_H))),
                Mutation.AttachNativeView(SECOND, second.objcPtr().toLong()),
                Mutation.Insert(LIST, SECOND, 4),
            )
            f.send(*overlapping.toTypedArray())
            assertEquals(listOf(VIEW, SECOND), f.slot.attachOrder)
            val content = f.window.window.contentView!!
            val a = content.subviews.indexOf(first.superview)
            val b = content.subviews.indexOf(second.superview)
            assertTrue(b > a, "the later view's container is above the earlier one's: $a, $b")
            f.image()?.let { image ->
                assertEquals('B', image.at(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0), "the later view is on top")
            }
        }
    }

    @Test
    fun fr81_detach_removes_without_destroying() {
        val view = SolidView()
        val retained = view
        withWindow(page(view.objcPtr().toLong())) { f ->
            f.send(Mutation.AttachNativeView(VIEW, 0L))
            assertEquals(null, retained.window, "out of the window")
            assertEquals(null, retained.superview)
            // Still alive, and can go back in.
            f.send(Mutation.AttachNativeView(VIEW, retained.objcPtr().toLong()))
            assertNotNull(retained.window)
            f.send(Mutation.Remove(VIEW))
            assertEquals(null, retained.window, "removing the node takes it out")
            retained.setNeedsDisplay(true)
            assertTrue(retained.wantsLayer, "and the view the Host holds is still a working view")
        }
    }

    @Test
    fun fr81_native_view_errors_are_protocol_errors() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            f.send(
                Mutation.AttachNativeView(999, 0x10L),
                Mutation.AttachNativeView(GAP, 0x10L),
                Mutation.Create(SECOND, WidgetKind.NativeView),
                Mutation.Insert(ROOT, SECOND, 1),
                Mutation.AttachNativeView(SECOND, view.objcPtr().toLong()),
                Mutation.SetModifier(GAP, 0, ProtocolModifier.Size(COLUMN_W, GAP_H + 10f)),
            )
            assertEquals(
                listOf(TableError.UNKNOWN_NODE, TableError.NOT_NATIVE_VIEW, TableError.NATIVE_VIEW_REJECTED),
                f.errors,
            )
            assertEquals(((GAP_H + 10f + MARK_H) * f.scale).toInt(), f.slot.frames[VIEW]!!.y, "the rest of the batch applied")
            // Something that is not a view at all: the window refuses it.
            val notAView = platform.Foundation.NSObject()
            f.send(Mutation.AttachNativeView(SECOND, notAView.objcPtr().toLong()))
            assertEquals(TableError.NATIVE_VIEW_REJECTED, f.errors.last())
        }
    }

    /**
     * The frame time of an animated scroll with four views on the page, against the same page
     * with plain boxes in their places. The budget is a tenth on top. Prints both medians.
     */
    @Test
    fun fr81_native_views_stay_inside_the_frame_budget() {
        fun median(handles: List<Long>, first: Long): Double {
            var result = 0.0
            withWindow(page(first, extraHandles = handles)) { f ->
                val times = ArrayList<Long>()
                f.window.onFrameRendered = { nanos -> times += nanos }
                repeat(3) { pass ->
                    f.send(scrollTo(if (pass % 2 == 0) 900.0 * f.scale else 0.0, animate = true), settle = 1.2)
                }
                f.window.onFrameRendered = null
                times.sort()
                assertTrue(times.size >= 30, "frames drawn: ${times.size}")
                result = times[times.size / 2] / 1e6
            }
            return result
        }
        val views = List(3) { SolidView() }
        val first = SolidView()
        val without = median(listOf(0L, 0L, 0L), 0L)
        val with = median(views.map { it.objcPtr().toLong() }, first.objcPtr().toLong())
        fun ms(value: Double) = (value * 1000).toLong() / 1000.0
        println("fr81 frame time while scrolling: without views ${ms(without)} ms, with four ${ms(with)} ms")
        assertTrue(with <= without * 1.10 + 0.05, "four native views cost ${with - without} ms a frame over $without ms")
    }
}
