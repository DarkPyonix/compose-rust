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
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSColor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventMaskLeftMouseUp
import platform.AppKit.NSEventType
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSTextField
import platform.AppKit.NSTextView
import platform.AppKit.NSView
import platform.AppKit.nextEventMatchingMask
import platform.AppKit.postEvent
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGPreflightScreenCaptureAccess
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSArray
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSDictionary
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNumber
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.distantPast
import platform.Foundation.runMode
import platform.ScreenCaptureKit.SCContentFilter
import platform.ScreenCaptureKit.SCDisplay
import platform.ScreenCaptureKit.SCFrameStatus
import platform.ScreenCaptureKit.SCShareableContent
import platform.ScreenCaptureKit.SCStream
import platform.ScreenCaptureKit.SCStreamConfiguration
import platform.ScreenCaptureKit.SCStreamFrameInfoStatus
import platform.ScreenCaptureKit.SCStreamOutputProtocol
import platform.ScreenCaptureKit.SCStreamOutputType
import platform.ScreenCaptureKit.SCWindow
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv
import platform.posix.memcpy
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
// blue, and the view is red, so a picture of the window says at a pixel which one is on top.
//
// The pictures come from ScreenCaptureKit: what the window server composited, one picture each
// time it composited the window. Not CGWindowListCreateImage. This window's content view is a
// vibrant NSVisualEffectView, and for such a window CGWindowListCreateImage draws the window
// again when asked rather than returning what was composited: with a view moved in the same
// turn as the frame under it, it put the view a step ahead of that frame in 61 of 96 images,
// while none of 93 ScreenCaptureKit pictures of the same turn had the two apart.
//
// Recording the screen needs the user's Screen Recording permission. A process without it, or
// a runner with no screen to record, prints that the picture checks were not made and what
// they would have checked; every check that does not look at the screen still runs.
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

/** Fewer frames than this and the middle one says little about the rest. */
private const val FRAMES_FOR_A_MEDIAN = 30

// GitHub Actions sets CI=true on every runner.
private fun runsOnCi(): Boolean = getenv("CI")?.toKString() == "true"

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

    override fun setFrameAndVisibleRegion(id: Int, frame: NativeRect, clip: NativeRect?, holes: List<NativeHole>) {
        frames[id] = frame
        this.holes[id] = holes
        inner.setFrameAndVisibleRegion(id, frame, clip, holes)
    }

    override fun setVisible(id: Int, visible: Boolean) {
        this.visible[id] = visible
        inner.setVisible(id, visible)
    }
}

private fun classify(r: Int, g: Int, b: Int): Char = when {
    r > 180 && g < 80 && b < 80 -> 'R'
    g > 140 && r < 80 && b < 80 -> 'G'
    b > 160 && r < 80 && g < 80 -> 'B'
    else -> '?'
}

/**
 * A picture of the window's content as the window server composited it, in BGRA rows [stride]
 * bytes apart, from the content's top left: R the view, G the page, B a marker.
 */
private class WindowImage(
    private val pixels: ByteArray,
    private val width: Int,
    private val height: Int,
    private val stride: Int,
    val scale: Double,
) {
    /** At image pixel ([ix], [iy]). */
    fun pixel(ix: Int, iy: Int): Char {
        if (ix !in 0 until width || iy !in 0 until height) return '?'
        val i = iy * stride + ix * 4
        return classify(pixels[i + 2].toInt() and 0xFF, pixels[i + 1].toInt() and 0xFF, pixels[i].toInt() and 0xFF)
    }

    /** At the content point ([x], [y]). */
    fun at(x: Double, y: Double): Char = pixel(column(x), row(y))

    fun column(x: Double): Int = (x * scale).toInt()
    fun row(y: Double): Int = (y * scale).toInt()
}

/** One column of a picture, down the scrolling column's height from its top, one cell a pixel. */
private class Column(val cells: CharArray) {
    /** Runs of one colour and their length in pixels, for a failure message. */
    fun runs(): String {
        val out = StringBuilder()
        var i = 0
        while (i < cells.size) {
            var j = i
            while (j < cells.size && cells[j] == cells[i]) j++
            out.append(cells[i]).append(j - i).append(' ')
            i = j
        }
        return out.toString().trim()
    }

    /** The rows the view covers, or null when it is not in this column. */
    fun viewRows(): IntRange? {
        val first = cells.indexOf('R')
        return if (first < 0) null else first..cells.lastIndexOf('R')
    }
}

/**
 * The window's content as the window server composites it, from a ScreenCaptureKit stream of
 * that window alone. Its pictures are handed over on the main queue, so they arrive while a
 * test turns the run loop. It keeps the last picture, or, while [watch] is set, the column at
 * one x of every picture.
 */
private class Screen : NSObject(), SCStreamOutputProtocol {
    private var stream: SCStream? = null
    private var scale = 1.0
    private var latest: WindowImage? = null
    private var watchedX: Double? = null
    private val watched = ArrayList<Column>()

    /** Starts the stream of [window]'s content, or answers why it could not. */
    fun start(window: MacosWindow): String? {
        if (!CGPreflightScreenCaptureAccess()) {
            return "this process may not record the screen (it has no Screen Recording permission)"
        }
        var content: SCShareableContent? = null
        var failed: String? = null
        var answered = false
        SCShareableContent.getShareableContentExcludingDesktopWindows(false, onScreenWindowsOnly = true) { found, error ->
            content = found
            failed = error?.localizedDescription
            answered = true
        }
        if (!waitFor { answered }) return "the window server did not say what is on the screen"
        val shareable = content ?: return "the window server would not say what is on the screen: $failed"
        val number = window.window.windowNumber.toUInt()
        val shown = shareable.windows.firstOrNull { (it as SCWindow).windowID == number } as SCWindow?
            ?: return "the window is not among those on the screen"
        val frame = shown.frame.useContents { origin.x to origin.y }
        val display = shareable.displays.firstOrNull { d ->
            (d as SCDisplay).frame.useContents {
                frame.first >= origin.x && frame.first < origin.x + size.width &&
                    frame.second >= origin.y && frame.second < origin.y + size.height
            }
        } as SCDisplay? ?: return "no display holds the window"
        val filter = SCContentFilter(display = display, includingWindows = listOf(shown))
        scale = filter.pointPixelScale.toDouble()
        // The content view's rectangle, in the display's points from its top left.
        val contentView = window.window.contentView!!
        val inWindow: CValue<CGRect> = contentView.convertRect(contentView.bounds, toView = null)
        val windowHeight = window.window.frame.useContents { size.height }
        val (x, y, w, h) = inWindow.useContents {
            listOf(origin.x, windowHeight - (origin.y + size.height), size.width, size.height)
        }
        val displayOrigin = display.frame.useContents { origin.x to origin.y }
        val config = SCStreamConfiguration()
        config.sourceRect = CGRectMake(frame.first - displayOrigin.first + x, frame.second - displayOrigin.second + y, w, h)
        config.width = (w * scale).toULong()
        config.height = (h * scale).toULong()
        config.minimumFrameInterval = CMTimeMake(1, 240)
        config.showsCursor = false
        config.pixelFormat = kCVPixelFormatType_32BGRA
        config.queueDepth = 8
        val made = SCStream(filter, config, null)
        if (!made.addStreamOutput(this, SCStreamOutputType.SCStreamOutputTypeScreen, dispatch_get_main_queue(), null)) {
            return "the screen stream would not take an output"
        }
        var started = false
        var startError: String? = null
        made.startCaptureWithCompletionHandler { error ->
            startError = error?.localizedDescription
            started = true
        }
        if (!waitFor { started }) return "the screen stream did not start"
        startError?.let { return "the screen stream did not start: $it" }
        stream = made
        if (!waitFor { latest != null }) return "the screen stream sent no picture of the window"
        return null
    }

    fun stop() {
        val running = stream ?: return
        stream = null
        var stopped = false
        running.stopCaptureWithCompletionHandler { stopped = true }
        waitFor { stopped }
    }

    /**
     * The last picture the window server composited. A picture is sent only when the window
     * changed, so the run loop is turned a moment first for one still on its way.
     */
    fun picture(): WindowImage? {
        spin(0.1)
        return latest
    }

    /** From now on, keeps the column at content [x] of every picture. */
    fun watch(x: Double) {
        watched.clear()
        watchedX = x
    }

    /** Stops keeping columns, and gives those kept since [watch], in the order composited. */
    fun stopWatching(): List<Column> {
        spin(0.05)
        watchedX = null
        return watched.toList()
    }

    private fun waitFor(done: () -> Boolean): Boolean {
        val begun = kotlin.time.TimeSource.Monotonic.markNow()
        while (!done()) {
            if (begun.elapsedNow().inWholeMilliseconds > 5000) return false
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
        return true
    }

    override fun stream(stream: SCStream, didOutputSampleBuffer: CMSampleBufferRef?, ofType: SCStreamOutputType) {
        if (ofType != SCStreamOutputType.SCStreamOutputTypeScreen) return
        val sample = didOutputSampleBuffer ?: return
        // Only a picture the window server composited anew; an idle one repeats the last.
        val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false)
            ?.let { interpretObjCPointerOrNull<NSArray>(it.rawValue) }
        val status = (attachments?.takeIf { it.count > 0uL }?.objectAtIndex(0uL) as? NSDictionary)
            ?.objectForKey(SCStreamFrameInfoStatus) as? NSNumber
        if (status?.longValue != SCFrameStatus.SCFrameStatusComplete.value) return
        val buffer = CMSampleBufferGetImageBuffer(sample) ?: return
        CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        try {
            val width = CVPixelBufferGetWidth(buffer).toInt()
            val height = CVPixelBufferGetHeight(buffer).toInt()
            val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
            val address = CVPixelBufferGetBaseAddress(buffer) ?: return
            val x = watchedX
            if (x == null) {
                // One copy of the whole buffer. readBytes would read it a byte at a time.
                val pixels = ByteArray(stride * height)
                pixels.usePinned { pinned -> memcpy(pinned.addressOf(0), address, pixels.size.convert()) }
                latest = WindowImage(pixels, width, height, stride, scale)
                return
            }
            // While watching, only the one column is read: a whole picture each time the
            // window is composited would be the test's own load on the frames it looks at.
            val base: CPointer<UByteVar> = address.reinterpret()
            val ix = (x * scale).toInt()
            if (ix !in 0 until width) return
            val rows = (COLUMN_H * scale).toInt().coerceAtMost(height)
            watched += Column(
                CharArray(rows) { iy ->
                    val pixel = base + (iy * stride + ix * 4)
                    classify(pixel!![2].toInt(), pixel[1].toInt(), pixel[0].toInt())
                },
            )
        } finally {
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
        }
    }
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
        // A view that follows the mouse itself, as a text field does, takes the mouse-up out of the
        // event queue inside its mouse-down, so it is in the queue before the mouse-down is sent.
        // Where nothing took it, the window is handed it after, as it would have been.
        val application = NSApplication.sharedApplication()
        application.postEvent(mouse(NSEventTypeLeftMouseUp, x, y), atStart = false)
        window.window.sendEvent(mouse(NSEventTypeLeftMouseDown, x, y))
        application.nextEventMatchingMask(NSEventMaskLeftMouseUp, NSDate.distantPast, NSDefaultRunLoopMode, true)
            ?.let { window.window.sendEvent(it) }
        spin(0.2)
    }

    /** The scene's own view: what the window finds under a point of the page away from every native view. */
    val sceneView: NSView
        get() {
            val content = window.window.contentView!!
            val point = content.superview!!.convertPoint(inWindow(COLUMN_W + 150.0, COLUMN_H + 40.0), fromView = null)
            return content.hitTest(point)!!
        }

    fun move(x: Double, y: Double) {
        // A pointer move reaches a view through its tracking area, from the window server; one
        // made here and sent to the window reaches no view. The scene's tracking area covers the
        // whole window, native views included, so the scene's view is given it, as AppKit would.
        sceneView.mouseMoved(mouse(NSEventTypeMouseMoved, x, y))
        spin(0.1)
    }

    private var screen: Screen? = null
    private var noScreen: String? = null

    /**
     * The stream of what the window server composites for this window, started the first time
     * it is asked for, or null, with a printed line saying that [unchecked] was not checked,
     * where the screen cannot be recorded.
     */
    fun screen(unchecked: String): Screen? {
        screen?.let { return it }
        if (noScreen == null) {
            val started = Screen()
            noScreen = started.start(window)
            if (noScreen == null) screen = started else started.stop()
        }
        screen?.let { return it }
        println("native view: $noScreen, so nothing on the screen was looked at: $unchecked was not checked")
        return null
    }

    /**
     * The window's content as last composited, or null, with a printed line saying that
     * [unchecked] was not checked, where there is no picture of the window to look at.
     */
    fun image(unchecked: String): WindowImage? {
        val read = screen(unchecked)?.picture() ?: return null
        // A screen that is locked or asleep gives a picture with no page colour in it.
        if (read.at(COLUMN_W + 20.0, 10.0) != 'G') {
            println("native view: the picture of the window shows no page, so the screen is not showing it: $unchecked was not checked")
            return null
        }
        return read
    }

    fun close() {
        screen?.stop()
        screen = null
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
    var fixture: Fixture? = null
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
        if (!window.window.keyWindow) {
            // No key window to be had: this process is not the active application, and macOS
            // does not let a process take the front from another by asking. The window is told
            // it is key, as AppKit would tell it, so it reports focus as a key window does.
            println("native view: the window cannot become key here; told it is key directly")
            window.window.becomeKeyWindow()
        }
        fixture = Fixture(window, slot, connection, host)
        body(fixture)
    } finally {
        fixture?.close()
        timer.invalidate()
        window.window.orderOut(null)
        NativeViewPlatform.available = false
    }
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

            // While an animated scroll runs, every picture the window server composites has
            // the view where the content is: right under the blue marker above it and right
            // over the one below. A view a frame behind or ahead of the content would leave the
            // green page showing between it and the marker on one side.
            f.send(scrollTo(0.0), settle = 0.3)
            val screen = f.screen("where the view is in each picture of an animated scroll") ?: return@withWindow
            screen.watch(COLUMN_W / 2.0)
            f.connection.queue(listOf(scrollTo(120.0 * scale, animate = true)))
            f.host.renderFrame(0L)
            f.window.requestFrame()
            spin(0.7)
            val pictures = screen.stopWatching()
            var looked = 0
            val places = HashSet<Int>()
            val apart = ArrayList<String>()
            pictures.forEachIndexed { index, column ->
                val rows = column.viewRows() ?: return@forEachIndexed
                val cells = column.cells
                if (rows.first > 3) {
                    val above = (rows.first - 3 until rows.first).map { cells[it] }
                    if ('G' in above || 'B' !in above) apart += "picture $index: above the view is $above"
                }
                if (rows.last < cells.size - 3) {
                    val below = (rows.last + 1..rows.last + 3).map { cells[it] }
                    if ('G' in below || 'B' !in below) apart += "picture $index: below the view is $below"
                }
                places += rows.last
                looked++
            }
            val everyPicture = pictures.mapIndexed { index, column -> "$index: ${column.runs()}" }.joinToString("; ")
            assertTrue(
                apart.isEmpty(),
                "in ${apart.size} of $looked pictures the view was not beside its markers: $apart. " +
                    "Down the column in each picture: $everyPicture",
            )
            println("native view: the view was beside its markers in all $looked pictures of the scroll, at ${places.size} places")
            assertTrue(looked >= 5, "the scroll was looked at in $looked of ${pictures.size} pictures")
            // Pictures of a page standing still would pass the checks above without showing
            // anything about a scroll.
            assertTrue(places.size >= 3, "the view was seen at only ${places.size} places in the $looked pictures")
        }
    }

    @Test
    fun fr81_view_is_clipped_by_its_ancestors() {
        val view = SolidView()
        withWindow(page(view.objcPtr().toLong())) { f ->
            val scale = f.scale
            // The view (100 points from y 100) half below the 260 point column: from y 210.
            f.send(Mutation.SetModifier(GAP, 0, ProtocolModifier.Size(COLUMN_W, GAP_H + 110f)))
            f.image("that only the half of the view inside the column shows")?.let { image ->
                assertEquals('R', image.at(COLUMN_W / 2.0, 230.0), "the half inside the column shows")
                assertEquals('G', image.at(COLUMN_W / 2.0, 280.0), "the half below it does not")
            }
            assertEquals(true, f.slot.visible[VIEW])
            // All the way out: hidden.
            f.send(Mutation.SetModifier(GAP, 0, ProtocolModifier.Size(COLUMN_W, GAP_H + 300f)))
            assertEquals(false, f.slot.visible[VIEW])
            assertTrue(view.superview!!.hidden)
            f.image("that nothing of the hidden view shows")?.let { shot ->
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
            val image = f.image("that the popover shows through the hole and the view in its rounded-off corner")
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
            }
            f.send(Mutation.SetProp(POPOVER, PropertyKind.Open, PropertyValue.Bool(false)), settle = 0.4)
            assertTrue(f.slot.holes[VIEW].orEmpty().isEmpty(), "closed, the hole is gone")
            f.image("that the view shows again once the popover closed")?.let {
                assertEquals('R', it.at(COLUMN_W / 2.0, GAP_H + MARK_H + VIEW_H / 2.0), "closed, the view shows again")
            }
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
            // Compared by the object's address: Kotlin/Native can hand out two references for one
            // Objective-C object, so assertSame failed with both printed at the same address.
            val current = platform.AppKit.NSCursor.currentCursor
            assertEquals(cursor.objcPtr(), current.objcPtr(), "the view's cursor stays: $cursor, now $current")
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
            f.image("that the later view is drawn on top")?.let { image ->
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
            val notAView = platform.darwin.NSObject()
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
                // Back and forth until there are frames enough for a median. A slow display
                // (a shared runner draws a few frames a second) needs more passes than three.
                var pass = 0
                while (pass < 3 || (times.size < FRAMES_FOR_A_MEDIAN && pass < 24)) {
                    f.send(scrollTo(if (pass % 2 == 0) 900.0 * f.scale else 0.0, animate = true), settle = 1.2)
                    pass++
                }
                f.window.onFrameRendered = null
                times.sort()
                assertTrue(times.size >= FRAMES_FOR_A_MEDIAN, "frames drawn: ${times.size} in $pass passes")
                result = times[times.size / 2] / 1e6
            }
            return result
        }
        // Each page is measured three times in turn, so a burst of outside load hits both, and
        // each side keeps its lowest median: other processes only ever add time, so the lowest
        // is the figure least inflated by them.
        val withoutRuns = ArrayList<Double>()
        val withRuns = ArrayList<Double>()
        repeat(3) {
            withoutRuns += median(listOf(0L, 0L, 0L), 0L)
            val views = List(3) { SolidView() }
            val first = SolidView()
            withRuns += median(views.map { it.objcPtr().toLong() }, first.objcPtr().toLong())
        }
        val without = withoutRuns.min()
        val with = withRuns.min()
        fun ms(value: Double) = (value * 1000).toLong() / 1000.0
        println("fr81 frame time medians: without views ${withoutRuns.map(::ms)} ms, with four ${withRuns.map(::ms)} ms")
        println("fr81 frame time while scrolling: without views ${ms(without)} ms, with four ${ms(with)} ms")
        // A shared runner's frame times swing by more than the tenth this budget allows, so
        // there the two medians are printed and not judged. The budget is judged where the
        // numbers mean something: on a developer's machine and in the merge gate.
        if (runsOnCi()) {
            println("fr81 frame budget not judged on CI: without views ${ms(without)} ms, with four ${ms(with)} ms")
            return
        }
        assertTrue(with <= without * 1.10 + 0.05, "four native views cost ${with - without} ms a frame over $without ms")
    }
}
