@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.platformZoomLevelStore
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
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.Foundation.NSProcessInfo
import platform.AppKit.NSEventTypeApplicationDefined
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSWindowAbove
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
 * What one window holds for itself rather than for the application: the zoom it is drawn at,
 * and the `Window` node it draws.
 *
 * The zoom is the window's own, so a level set on one node or a shortcut pressed in one window
 * changes that window and no other. The level is saved the one way the application saves it
 * ([platformZoomLevelStore]), so a new window starts at the last saved level.
 *
 * The node is state because a window the system asked for is opened before the application has
 * a node for it, and is told the node when the application answers ([PlatformWindow.adoptedBy]).
 */
class MacosWindowSlot(nodeId: Int, val zoom: Zoom) {
    private val node = mutableIntStateOf(nodeId)

    /** The `Window` node this window draws, 0 while it waits for one. */
    val nodeId: Int get() = node.intValue

    internal fun drawNode(id: Int) {
        node.intValue = id
    }
}

/**
 * Builds the content of the scene of one `Window` node. Null makes windows without a scene,
 * which is what a test of the window system itself needs.
 */
typealias MacosSceneContent =
    (config: PlatformWindowConfig, window: MacosWindow, frames: MacosSceneFrames, slot: MacosWindowSlot) -> (@Composable () -> Unit)

class MacosWindowBackend(
    private val scene: MacosSceneContent? = null,
    private val makeZoom: () -> Zoom = { Zoom(store = platformZoomLevelStore()) },
) : WindowBackend {
    override val supportsMultipleWindows: Boolean get() = true

    private val live = ArrayList<MacosPlatformWindow>()
    private val byNode = HashMap<Int, MacosPlatformWindow>()
    private var turns: NSTimer? = null
    private var turning = false

    /** The windows that are open now, in the order they were opened. */
    val windows: List<PlatformWindow> get() = live.toList()

    /**
     * Told of every window this opens, once its scene is set and before the loop draws it:
     * where the multi-window run prints the start report and starts a parity run.
     */
    var onOpened: ((MacosWindow) -> Unit)? = null

    /**
     * Where the pointer is on the screen and whether its primary button is held. The system's
     * own answer, except in a test, which has no pointer to move and scripts one here.
     */
    internal var pointer: () -> ScreenPointer? = ::systemPointerOnScreen

    /**
     * How many pixels of the scene of the window of [nodeId] make one screen point: the
     * backing scale of the screen the window is on, and 1 for a node with no window open.
     */
    fun contentScale(nodeId: Int): Float = byNode[nodeId]?.backingScale ?: 1f

    override fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow? {
        NSApplication.sharedApplication()
        installMacosApplicationDelegate()
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
        val slot = MacosWindowSlot(config.nodeId, makeZoom())
        config.zoomLevel?.let { slot.zoom.setLevel(it) }
        val content = scene?.invoke(config, created, frames, slot)
        if (content != null) {
            created.leaveRightClickMenuToCompose()
            @Suppress("DEPRECATION")
            created.setContent(content)
            created.listenForReader()
            val previousOnFrameDrawn = created.onFrameDrawn
            created.onFrameDrawn = { width, height ->
                previousOnFrameDrawn?.invoke(width, height)
                created.host?.describeForReaders(drew = true)
            }
        } else {
            created.initWindow()
        }
        // After setContent: it hands the window's close, focus and event hooks to the scene's
        // own host, which closes the window when asked. Here the application is the one to ask.
        val window = MacosPlatformWindow(created, frames, slot, listener, { pointer() }) { gone ->
            live.remove(gone)
            if (byNode[config.nodeId] === gone) byNode.remove(config.nodeId)
            stopTurnsWhenIdle()
        }
        live += window
        byNode[config.nodeId] = window
        if (!config.resizable) window.setResizable(false)
        if (config.colorScheme != ColorScheme.FollowSystem) window.setColorScheme(config.colorScheme)
        startTurns()
        onOpened?.invoke(created)
        return window
    }

    override fun setApplicationListener(listener: ApplicationListener?) {
        WindowApplication.onReopen = if (listener == null) null else ({ listener.onReopen() })
        application = listener
        macosNewWindowForTab = if (listener == null) null else ({ sender -> requestWindow("", tabWith = sender) })
    }

    private var application: ApplicationListener? = null

    /**
     * Opens a window the application did not ask for and asks the application for its node:
     * a window the system made (the new tab button of a tabbed window) or one restored after
     * the process started again. [key] is the restore key, empty for a new window.
     *
     * The window is opened first and handed over with the request, so the application's answer
     * is drawn in it rather than in a second one; until then it draws nothing. The manager
     * closes it when the application answers with no `Window` node of that key. A new tab is
     * put beside [tabWith]. Returns whether the application took the window.
     */
    fun requestWindow(key: String, tabWith: NSWindow? = null): Boolean {
        val listener = application ?: return false
        val window = open(PlatformWindowConfig(nodeId = 0, restoreKey = key), UnclaimedWindow) as? MacosPlatformWindow
            ?: return false
        listener.onWindowRequested(key, window)
        if (window.isClosed) return false
        if (tabWith != null && tabWith !== window.nativeWindow) {
            tabWith.addTabbedWindow(window.nativeWindow, ordered = NSWindowAbove)
            window.focus()
        }
        return true
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
    private val slot: MacosWindowSlot,
    private var sink: PlatformWindowListener,
    private val pointer: () -> ScreenPointer? = ::systemPointerOnScreen,
    private val onClosed: (MacosPlatformWindow) -> Unit,
) : PlatformWindow {
    private val native: NSWindow get() = window.window
    private var closed = false

    /** Whether the window has been closed, by the application or because nobody claimed it. */
    val isClosed: Boolean get() = closed
    private val observers = ArrayList<Any>()
    private var follow: Pair<Float, Float>? = null
    private var followTimer: NSTimer? = null
    private var escapeMonitor: Any? = null
    private var escapePressed = false
    private var lastKey = false

    /** The AppKit window, for a test that acts on it as a user would. */
    val nativeWindow: NSWindow get() = native

    /** The window module's window, for a test that asks what it was set up to do. */
    val macosWindow: MacosWindow get() = window

    /** How many pixels the window's scene draws for one screen point. */
    val backingScale: Float get() = if (closed) 1f else native.backingScaleFactor.toFloat()

    /** Whether the window's accessibility tree is active for external readers. */
    val accessibilityActive: Boolean get() = window.accessibilityActive

    /** The zoom this window is drawn at, its own and no other window's. */
    val zoom: Zoom get() = slot.zoom

    /** The `Window` node the window draws. */
    val nodeId: Int get() = slot.nodeId

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

    override fun pointerOnScreen(): ScreenPointer? = pointer()

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

    // The scene reports the change, naming this window's node, when it composes at the new level.
    override fun setZoomLevel(level: Int) {
        if (closed) return
        if (slot.zoom.setLevel(level)) requestFrame()
    }

    override fun adoptedBy(nodeId: Int) {
        if (closed) return
        slot.drawNode(nodeId)
        requestFrame()
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

/** The listener of a window between its opening and the application claiming it: it hears nothing worth telling. */
private object UnclaimedWindow : PlatformWindowListener {
    override fun onCloseRequested() {}
    override fun onFocusChanged(focused: Boolean) {}
    override fun onMoved(bounds: ScreenBounds) {}
    override fun onFollowEnded(cancelled: Boolean) {}
}

/** Where the application delegate sends the new tab button's request, while a manager is listening. */
internal var macosNewWindowForTab: ((NSWindow?) -> Unit)? = null

// The delegate is weak on the AppKit side, and Kotlin/Native forbids fields on the companion
// of an Objective-C subclass.
private var installedDelegate: MacosApplicationDelegate? = null

internal fun installMacosApplicationDelegate() {
    val app = NSApplication.sharedApplication()
    if (installedDelegate != null || app.delegate != null) return
    val delegate = MacosApplicationDelegate()
    installedDelegate = delegate
    app.delegate = delegate
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

    /**
     * The new tab button of a tabbed window, which AppKit shows when something in the
     * responder chain answers this, and sends here through it. The system is the one making a
     * window, so the application is asked for one with an empty restore key.
     */
    @ObjCAction
    fun newWindowForTab(sender: NSObject?) {
        macosNewWindowForTab?.invoke(sender as? NSWindow)
    }
}

/** Where the system says the pointer is, in the renderer's screen space, and whether its primary button is held. */
internal fun systemPointerOnScreen(): ScreenPointer? {
    val primaryHeight = primaryScreenHeight()
    val at = NSEvent.mouseLocation.useContents { flipPoint(x, y, primaryHeight) }
    return ScreenPointer(at.first.toFloat(), at.second.toFloat(), primaryButtonDown(NSEvent.pressedMouseButtons))
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


/**
 * Starts the system window drag for [window] from the pointer's current place.
 *
 * `performWindowDragWithEvent` wants the mouse down that begins the drag. By the time the
 * pointer handler runs, that event has been consumed, so a left mouse down is built at the
 * pointer's place in the window instead; the system only reads its place and window.
 */
internal fun beginMacosWindowDrag(window: NSWindow) {
    val event = NSEvent.mouseEventWithType(
        type = NSEventTypeLeftMouseDown,
        location = window.mouseLocationOutsideOfEventStream,
        modifierFlags = 0u,
        timestamp = NSProcessInfo.processInfo.systemUptime,
        windowNumber = window.windowNumber,
        context = null,
        eventNumber = 0,
        clickCount = 1,
        pressure = 1.0f,
    ) ?: return
    window.performWindowDragWithEvent(event)
}

/**
 * Leaves the right-click menu to Compose. The text field's menu is already an `NSMenu` here,
 * the system's own, and an application that sets `LocalTextContextMenu` or
 * `LocalContextMenuRepresentation` shows its menu instead. A second menu from the window
 * would come up beside either of them, so the window puts up none. Called before
 * `setContent`, which is where the window hands the choice to its platform.
 */
internal fun MacosWindow.leaveRightClickMenuToCompose() {
    editMenuOnRightClick = false
}
