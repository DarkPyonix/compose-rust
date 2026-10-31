@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import dev.darkpyonix.composerust.protocol.ColorScheme
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.thisisthepy.compose.window.WindowApplication
import org.thisisthepy.compose.window.contentMinimum
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationDelegateProtocol
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeApplicationDefined
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowDidMoveNotification
import platform.AppKit.NSWindowDidResizeNotification
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.postEvent
import platform.CoreGraphics.CGPointMake
import platform.Foundation.NSMakeSize
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSTimer
import platform.darwin.NSObject

// The window system of the Kotlin/Native macOS renderer for several windows in one process.
// It is the adapter between the contract in WindowBackend.kt and the fork's window
// module (org.thisisthepy.compose.window.macos):
//
//   PlatformWindow members            the fork's MacosWindow / NSWindow, in screen points
//   PlatformWindowListener            MacosWindow.onClose, onFocus and NSWindow's move and
//                                     resize notifications
//   ApplicationListener.onReopen      WindowApplication.onReopen, fired by the application
//                                     delegate installed here
//
// Each window is its own MacosWindow with its own Compose scene. NSApplication.run() owns the
// loop; this file adds the two things the loop does not do for a window: it turns the main
// run loop into the scene's turns (events a window heard are read in a turn) and it follows the
// pointer while a torn-off window is being dragged.

private const val DEFAULT_TITLE = "compose-rust"
private const val DEFAULT_WIDTH = 520
private const val DEFAULT_HEIGHT = 360

/** How often every scene takes a turn, and how often a dragged window follows the pointer. */
private const val TURN_SECONDS = 1.0 / 60.0

/**
 * The frame clock of one window, handed to the scene that draws it.
 *
 * [PlatformWindow.awaitFrame] registers a waiter here and asks the scene for a frame; the
 * scene's own frame clock (`withFrameNanos`, run by the content built in [MacosSceneContent])
 * calls [draw] when the frame comes, which serves every waiter once.
 */
class MacosSceneFrames {
    private val waiting = ArrayList<(Long) -> Unit>()

    /** A signal for the scene to wait for one frame; conflated, so many waiters cost one wait. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    val pending: Int get() = waiting.size

    fun add(waiter: (Long) -> Unit) {
        waiting += waiter
        wake.trySend(Unit)
    }

    fun remove(waiter: (Long) -> Unit) {
        waiting.remove(waiter)
    }

    /** The window drew a frame at [nanos]: everything waiting on its clock is told, once. */
    fun draw(nanos: Long) {
        if (waiting.isEmpty()) return
        val now = waiting.toList()
        waiting.clear()
        now.forEach { it(nanos) }
    }
}

/**
 * Builds the content of the scene of one `Window` node. Null makes windows without a scene,
 * which is what a test of the window system itself needs.
 */
typealias MacosSceneContent =
    (config: PlatformWindowConfig, window: MacosWindow, frames: MacosSceneFrames) -> (@Composable () -> Unit)

class MacosWindowBackend(
    private val scene: MacosSceneContent? = null,
) : WindowBackend {
    override val supportsMultipleWindows: Boolean get() = true

    private val live = ArrayList<MacosPlatformWindow>()
    private var turns: NSTimer? = null
    private var turning = false

    /** The windows that are open now, in the order they were opened. */
    val windows: List<PlatformWindow> get() = live.toList()

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        NSApplication.sharedApplication()
        MacosApplicationDelegate.install()
        val chrome = MacosWindowChrome.of(config.chrome, config.titleBar)
        val x = config.x
        val y = config.y
        val created = MacosWindow(
            name = config.title.ifEmpty { DEFAULT_TITLE },
            width = if (config.width > 0f) wholePoints(config.width) else DEFAULT_WIDTH,
            height = if (config.height > 0f) wholePoints(config.height) else DEFAULT_HEIGHT,
            minimumSize = contentMinimum(wholePoints(config.minWidth), wholePoints(config.minHeight)),
            chrome = org.thisisthepy.compose.window.macos.MacosWindowChrome(
                fullSizeContentView = chrome.fullSizeContentView,
                titlebarAppearsTransparent = chrome.titlebarAppearsTransparent,
                titleHidden = chrome.titleHidden,
                unifiedToolbar = chrome.unifiedToolbar,
            ),
            topLeft = if (x != null && y != null) wholePoints(x) to wholePoints(y) else null,
        )
        val frames = MacosSceneFrames()
        val content = scene?.invoke(config, created, frames)
        if (content != null) {
            @Suppress("DEPRECATION")
            created.setContent(content)
        } else {
            created.initWindow()
        }
        // After setContent: it hands the window's close, focus and event hooks to the scene's
        // own host, which closes the window when asked. Here the application is the one to ask.
        val window = MacosPlatformWindow(created, frames, listener) { gone -> live.remove(gone); stopTurnsWhenIdle() }
        live += window
        if (!config.resizable) window.setResizable(false)
        if (config.colorScheme != ColorScheme.FollowSystem) window.setColorScheme(config.colorScheme)
        startTurns()
        return window
    }

    override fun setApplicationListener(listener: ApplicationListener?) {
        WindowApplication.onReopen = if (listener == null) null else ({ listener.onReopen() })
    }

    /** Asks every window to draw: the batch a frame applied is one snapshot for all of them. */
    fun requestFrameAll() {
        for (window in live.toList()) window.requestFrame()
    }

    // The fork's window reads what it heard in a turn of its scene. NSApplication.run() never
    // takes one, so a timer on the main run loop (in the common modes, so it also runs while a
    // window is being resized or dragged) takes one for every window.
    private fun startTurns() {
        if (turns != null) return
        val timer = NSTimer.timerWithTimeInterval(interval = TURN_SECONDS, repeats = true) { _: NSTimer? ->
            if (!turning) {
                turning = true
                try {
                    for (window in live.toList()) window.turn()
                } finally {
                    turning = false
                }
            }
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        turns = timer
    }

    private fun stopTurnsWhenIdle() {
        if (live.isNotEmpty()) return
        turns?.invalidate()
        turns = null
    }
}

/** One `NSWindow` of the application as the manager sees it. */
internal class MacosPlatformWindow(
    private val window: MacosWindow,
    private val frames: MacosSceneFrames,
    private var sink: PlatformWindowListener,
    private val onClosed: (MacosPlatformWindow) -> Unit,
) : PlatformWindow {
    private val native: NSWindow get() = window.window
    private var closed = false
    private val observers = ArrayList<Any>()
    private var follow: Pair<Float, Float>? = null
    private var followTimer: NSTimer? = null
    private var escapeMonitor: Any? = null
    private var escapePressed = false
    private var lastKey = false

    /** The AppKit window, for a test that acts on it as a user would. */
    val nativeWindow: NSWindow get() = native

    init {
        window.onClose = {
            // Nothing is closed here. The application decides, by removing the `Window` node.
            if (!closed) sink.onCloseRequested()
            false
        }
        window.onFocus = { focused ->
            if (!closed) {
                lastKey = focused
                sink.onFocusChanged(focused)
            }
        }
        val center = NSNotificationCenter.defaultCenter
        for (name in listOf(NSWindowDidMoveNotification, NSWindowDidResizeNotification)) {
            observers += center.addObserverForName(
                name = name,
                `object` = native,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> reportMoved() }
        }
        // The window was made key before these hooks were there to hear it.
        if (native.keyWindow) {
            lastKey = true
            sink.onFocusChanged(true)
        }
    }

    override fun setListener(listener: PlatformWindowListener) {
        sink = listener
    }

    override fun setTitle(title: String) {
        if (!closed) native.setTitle(title)
    }

    override fun setMinimumSize(width: Float, height: Float) {
        if (!closed) native.contentMinSize = NSMakeSize(width.toDouble(), height.toDouble())
    }

    override fun setResizable(resizable: Boolean) {
        if (closed) return
        native.styleMask = if (resizable) {
            native.styleMask or NSWindowStyleMaskResizable
        } else {
            native.styleMask and NSWindowStyleMaskResizable.inv()
        }
    }

    override fun setPosition(x: Float, y: Float) {
        if (closed) return
        native.setFrameTopLeftPoint(topLeftToAppKit(x.toDouble(), y.toDouble()))
    }

    override fun setContentSize(width: Float, height: Float) {
        if (closed) return
        val left = native.frame.useContents { origin.x }
        val top = native.frame.useContents { origin.y + size.height }
        native.setContentSize(NSMakeSize(width.toDouble(), height.toDouble()))
        native.setFrameTopLeftPoint(CGPointMake(left, top))
    }

    override fun bounds(): ScreenBounds? {
        if (closed) return null
        val rect = native.frame.useContents {
            appKitRectToTopLeft(origin.x, origin.y, size.width, size.height, primaryScreenHeight())
        }
        return rect.toScreenBounds()
    }

    override fun contentOrigin(): ScreenPoint? {
        val frame = bounds() ?: return null
        return ScreenPoint(frame.x, frame.y + titleBarHeight().toFloat())
    }

    /** The part of the frame above the content: zero for a window whose content runs under it. */
    private fun titleBarHeight(): Double =
        native.frame.useContents { size.height } -
            native.contentRectForFrameRect(native.frame).useContents { size.height }

    override fun focus() {
        if (closed) return
        if (native.miniaturized) native.deminiaturize(null)
        native.makeKeyAndOrderFront(null)
        NSApplication.sharedApplication().activateIgnoringOtherApps(true)
    }

    override val isKey: Boolean get() = !closed && native.keyWindow

    override fun pointerOnScreen(): ScreenPointer? {
        val primaryHeight = primaryScreenHeight()
        val at = NSEvent.mouseLocation.useContents { flipPoint(x, y, primaryHeight) }
        return ScreenPointer(at.first.toFloat(), at.second.toFloat(), primaryButtonDown(NSEvent.pressedMouseButtons))
    }

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean {
        if (closed || follow != null) return false
        if (pointerOnScreen()?.primaryDown != true) return false
        follow = anchorX to anchorY
        escapePressed = false
        // Escape is read from the application's own key events, so a key typed in another
        // application does not cancel the drag.
        escapeMonitor = NSEvent.addLocalMonitorForEventsMatchingMask(EVENT_MASK_KEY_DOWN) { event: NSEvent? ->
            if (event != null && event.keyCode.toInt() == KEY_CODE_ESCAPE) escapePressed = true
            event
        }
        val timer = NSTimer.timerWithTimeInterval(interval = TURN_SECONDS, repeats = true) { _: NSTimer? ->
            stepFollow()
        }
        NSRunLoop.mainRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        followTimer = timer
        return true
    }

    /** One step of a follow: end it on Escape or on release, else put the anchor under the pointer. */
    private fun stepFollow() {
        val anchor = follow ?: return
        if (closed) {
            endFollow()
            return
        }
        if (escapePressed) {
            endFollow()
            sink.onFollowEnded(true)
            return
        }
        val pointer = pointerOnScreen()
        if (pointer == null || !pointer.primaryDown) {
            endFollow()
            sink.onFollowEnded(false)
            return
        }
        val top = followFrameTopLeft(
            pointer.x.toDouble(), pointer.y.toDouble(), anchor.first.toDouble(), anchor.second.toDouble(),
            titleBarHeight(),
        )
        native.setFrameTopLeftPoint(topLeftToAppKit(top.first, top.second))
    }

    private fun endFollow() {
        follow = null
        followTimer?.invalidate()
        followTimer = null
        escapeMonitor?.let { NSEvent.removeMonitor(it) }
        escapeMonitor = null
    }

    override fun setColorScheme(scheme: ColorScheme) {
        if (closed) return
        native.appearance = when (scheme) {
            ColorScheme.Light -> NSAppearance.appearanceNamed(NSAppearanceNameAqua)
            ColorScheme.Dark -> NSAppearance.appearanceNamed(NSAppearanceNameDarkAqua)
            ColorScheme.FollowSystem -> null
        }
    }

    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        var waiter: ((Long) -> Unit)? = null
        try {
            suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                val callback: (Long) -> Unit = { nanos ->
                    onFrame(nanos)
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
                waiter = callback
                frames.add(callback)
                // The frame is asked for here: an idle window is not drawn, and the scene's
                // clock only answers inside a frame.
                requestFrame()
            }
        } finally {
            waiter?.let { frames.remove(it) }
        }
    }

    fun requestFrame() {
        if (!closed) window.requestFrame()
    }

    /** One turn of the window's scene: what it heard is read and a frame is drawn if one is due. */
    fun turn() {
        if (!closed) window.pumpHost(0L)
    }

    private fun reportMoved() {
        if (closed) return
        bounds()?.let { sink.onMoved(it) }
    }

    /**
     * Takes the window off the screen at once and releases the scene on the next turn of the
     * main queue: the batch that removed the node may be served inside this window's own frame,
     * and a scene cannot be closed while it is drawing.
     */
    override fun close() {
        if (closed) return
        closed = true
        endFollow()
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        native.orderOut(null)
        onClosed(this)
        NSOperationQueue.mainQueue.addOperationWithBlock {
            window.host?.close()
            native.close()
        }
    }
}

/**
 * The application delegate of a multi-window application: a click on the Dock icon reaches the
 * application, and the application does not end because its last window closed (53.6; the
 * policy is the manager's to apply).
 */
internal class MacosApplicationDelegate : NSObject(), NSApplicationDelegateProtocol {
    override fun applicationShouldHandleReopen(sender: NSApplication, hasVisibleWindows: Boolean): Boolean {
        WindowApplication.onReopen?.invoke()
        return true
    }

    override fun applicationShouldTerminateAfterLastWindowClosed(sender: NSApplication): Boolean = false

    companion object {
        // The delegate is weak on the AppKit side.
        private var installed: MacosApplicationDelegate? = null

        fun install() {
            val app = NSApplication.sharedApplication()
            if (installed != null || app.delegate != null) return
            val delegate = MacosApplicationDelegate()
            installed = delegate
            app.delegate = delegate
        }
    }
}

/** The height of the primary screen, the one with the menu bar, which is the first of `NSScreen.screens`. */
internal fun primaryScreenHeight(): Double {
    val primary = (NSScreen.screens.firstOrNull() as? NSScreen) ?: NSScreen.mainScreen
    return primary?.frame?.useContents { size.height } ?: 0.0
}

/** A point of the renderer's space as an AppKit point. */
internal fun topLeftToAppKit(x: Double, y: Double) =
    flipPoint(x, y, primaryScreenHeight()).let { CGPointMake(it.first, it.second) }

/**
 * Ends `NSApplication.run()`. `stop` only takes effect once the loop has handled an event, so
 * one is posted to make it look.
 */
internal fun stopApplicationLoop() {
    val app = NSApplication.sharedApplication()
    app.stop(null)
    val wake = NSEvent.otherEventWithType(
        type = NSEventTypeApplicationDefined,
        location = CGPointMake(0.0, 0.0),
        modifierFlags = 0u,
        timestamp = 0.0,
        windowNumber = 0L,
        context = null,
        subtype = 0,
        data1 = 0L,
        data2 = 0L,
    ) ?: return
    app.postEvent(wake, atStart = true)
}
