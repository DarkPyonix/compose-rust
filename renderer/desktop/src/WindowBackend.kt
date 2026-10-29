package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.protocol.TitleBar

// What the operating system layers (macOS, Windows, Linux) implement so that one process can
// hold several windows, and the only thing the window manager knows about them.
//
// Plain Kotlin and nothing of a platform, so that it compiles unchanged for the JVM and for
// Kotlin/Native, and so that a fake can stand in for a window system in a test. It mirrors the
// members of the Compose fork's `org.thisisthepy.compose.window.WindowPlatform` (open with a
// config that carries x and y, close, setTitle, setPosition, setContentSize, bounds, focus,
// isKey, pointerOnScreen, followPointer, supportsMultipleWindows) and its listener
// (onCloseRequested, onFocusChanged, onMoved, onFollowEnded) and `WindowApplication.onReopen`,
// so an OS layer is a thin adapter from one to the other. All lengths are dp, and a position is
// in the primary screen's coordinates with the origin at its top left and y growing downward.
//
// Every call is made on the thread that runs the loop and the Host. A backend calls its
// listeners on that thread too.

/** A point on the screen, in dp. */
data class ScreenPoint(val x: Float, val y: Float)

/** Where the pointer is on the screen and whether its primary button is held. */
data class ScreenPointer(val x: Float, val y: Float, val primaryDown: Boolean)

/** A window's outer frame on the screen, in dp. */
data class ScreenBounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean =
        px >= x && px < x + width && py >= y && py < y + height
}

/**
 * What a window is asked to be when it opens, read from its `Window` node.
 *
 * A zero [width] or [height] means the application did not ask and the platform chooses. Null
 * [x] and [y] let the platform place the window (a cascade on macOS, the default position on
 * Windows, the window manager on X11); both are given or neither is. [chrome], [titleBar] and
 * [icon] are fixed when the window is created.
 */
data class PlatformWindowConfig(
    /** The `Window` node this window is for, which names it in every event. */
    val nodeId: Int,
    val title: String = "",
    val width: Float = 0f,
    val height: Float = 0f,
    val minWidth: Float = 0f,
    val minHeight: Float = 0f,
    val resizable: Boolean = true,
    val chrome: Chrome = Chrome.Modern,
    val titleBar: TitleBar = TitleBar.Normal,
    /** An asset id, or 0 for the application's icon. */
    val icon: Int = 0,
    val x: Float? = null,
    val y: Float? = null,
    /** The stable name the application gave the window; empty for none. */
    val restoreKey: String = "",
    val colorScheme: ColorScheme = ColorScheme.FollowSystem,
    /** The zoom level the application asked for the window, or null. */
    val zoomLevel: Int? = null,
)

/**
 * What a platform window tells the manager. The manager is the one listener of every window it
 * opened; a window is told apart by the listener it was opened with.
 */
interface PlatformWindowListener {
    /**
     * The user asked to close the window: the close button, the shortcut, the window menu.
     * The window stays open. Only the application removing the `Window` node closes it.
     */
    fun onCloseRequested()

    /** The window became the key window, or stopped being it. Once per change. */
    fun onFocusChanged(focused: Boolean)

    /** The outer frame moved or changed size, by the user or by the system or by a call. */
    fun onMoved(bounds: ScreenBounds)

    /**
     * [PlatformWindow.followPointer] ended: the button was released ([cancelled] false) or the
     * system took the move over or Escape was pressed ([cancelled] true).
     */
    fun onFollowEnded(cancelled: Boolean)

    /**
     * The system removed the window without asking the application (a swipe in Android's
     * recent tasks, closing a scene in the iPadOS app switcher). The window is gone and its
     * other calls must not be made after this.
     */
    fun onClosedBySystem() {}

    /** The window's content is [widthDp] by [heightDp] now. Drives the size class. */
    fun onContentSizeChanged(widthDp: Float, heightDp: Float) {}

    /** The zoom this window is drawn at changed: [k] the factor, [os] the system's. */
    fun onZoomChanged(k: Float, os: Float, level: Int) {}
}

/** One platform window: a window on a desktop, an Activity or a scene on a phone. */
interface PlatformWindow {
    /** Replaces the listener, for a window the system made before the manager knew of it. */
    fun setListener(listener: PlatformWindowListener)

    fun setTitle(title: String)

    fun setMinimumSize(width: Float, height: Float) {}

    fun setResizable(resizable: Boolean) {}

    /** Moves the outer frame so its top left corner is at ([x], [y]). */
    fun setPosition(x: Float, y: Float)

    /** Sets the size of the content, keeping the top left corner. */
    fun setContentSize(width: Float, height: Float)

    /** The outer frame, or null where the platform cannot say. */
    fun bounds(): ScreenBounds?

    /**
     * Where the content's top left corner is on the screen. The frame's own corner by default,
     * which is right for a window with no caption of its own.
     */
    fun contentOrigin(): ScreenPoint? = bounds()?.let { ScreenPoint(it.x, it.y) }

    /**
     * Brings the window to the front and makes it the key window. A platform that refuses to
     * let a process take focus flashes the window instead and reports no focus change.
     */
    fun focus()

    /** Whether the window is the key window now. */
    val isKey: Boolean

    /** Where the pointer is on the screen and whether the primary button is held, or null. */
    fun pointerOnScreen(): ScreenPointer?

    /**
     * Moves the window under the pointer until the primary button is released, keeping the
     * point ([anchorX], [anchorY]) of its content, in dp from the content's top left, under the
     * pointer. Ends with [PlatformWindowListener.onFollowEnded]. Returns false where the
     * platform cannot do it or the button is not held now, and then nothing follows.
     */
    fun followPointer(anchorX: Float, anchorY: Float): Boolean

    /** The window's own light or dark appearance, when its theme overrides the app's. */
    fun setColorScheme(scheme: ColorScheme) {}

    /** The zoom level the application set on the window's node. */
    fun setZoomLevel(level: Int) {}

    /**
     * This window's frame clock: suspends until the window draws its next frame and calls
     * [onFrame] with the frame time in nanoseconds. A window that is hidden or minimised may
     * never answer; the caller waits for the first of all of them.
     */
    suspend fun awaitFrame(onFrame: (Long) -> Unit)

    /** Destroys the window and everything the layer holds for it. Idempotent. */
    fun close()
}

/** The process-wide events of the application, as distinct from one window's. */
interface ApplicationListener {
    /** The user activated the application while it has no window (the Dock icon on macOS). */
    fun onReopen()

    /**
     * The system made a window the application has no node for yet: one revived after the
     * process restarted, a new scene from iPadOS' menu, an Activity dropped into a split.
     * [key] is its restore key, empty for a new window. The manager takes it over if the
     * application answers with a `Window` node and closes it if not.
     */
    fun onWindowRequested(key: String, window: PlatformWindow)
}

/** The window system of a platform. */
interface WindowBackend {
    /** Whether this platform can have more than one window open at a time. */
    val supportsMultipleWindows: Boolean

    /**
     * Opens a window, or returns null when the platform refuses (no more windows allowed, the
     * display is gone). The manager then tells the application with `WindowUnavailable`.
     */
    fun open(config: PlatformWindowConfig, listener: PlatformWindowListener): PlatformWindow?

    /** Installs the listener of application-level events; null removes it. */
    fun setApplicationListener(listener: ApplicationListener?)
}

/**
 * A node of a window that asked to hear a dragged window pass over it (`OnWindowDragOver`),
 * with the point in the node's own coordinates.
 */
data class DropTarget(val nodeId: Int, val x: Float, val y: Float)

/**
 * Finds which subscribed node of a window is under a point of its content. Answered by the
 * scene, which is the one that knows where nodes were laid out; the manager has no layout.
 */
fun interface DropTargets {
    /** The deepest node of [windowId] with a drag-over handler at ([x], [y]) of its content, or null. */
    fun targetAt(windowId: Int, x: Float, y: Float): DropTarget?

    companion object {
        val None = DropTargets { _, _, _ -> null }
    }
}
