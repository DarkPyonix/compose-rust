package dev.darkpyonix.composerust.ui.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.DragEvent
import android.view.View
import dev.darkpyonix.composerust.protocol.ColorScheme
import dev.darkpyonix.composerust.runtime.TearOffDrag
import dev.darkpyonix.composerust.runtime.TearOffRequest
import dev.darkpyonix.composerust.runtime.TearOffTrigger
import kotlin.math.roundToInt

/**
 * One Android [Activity] acting as a [PlatformWindow].
 *
 * A configuration change recreates the Activity while keeping the process and node table alive:
 * [rebind] swaps the Activity reference without firing [PlatformWindowListener.onClosedBySystem].
 * Pressing Back on a non-root Activity calls [backPressed] (`WindowCloseRequested`), and
 * swiping the task away from recents calls [closedBySystem] (`WindowClosed`).
 */
class AndroidActivityWindow(
    var nodeId: Int,
    val restoreKey: String,
    private var activity: Activity?,
    private var listener: PlatformWindowListener,
) : PlatformWindow {
    private var closed = false
    private var focused = activity?.hasWindowFocus() == true

    /**
     * This window's frame clock. The Activity's content serves it from its own frames
     * ([ServeWindowFrames]), so the process's one driver draws when any window draws.
     */
    val frames = WindowFrameClock()

    override fun setListener(listener: PlatformWindowListener) {
        this.listener = listener
    }

    override fun setTitle(title: String) {
        val current = activity ?: return
        current.title = title
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            current.setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(title).build())
        } else {
            @Suppress("DEPRECATION")
            current.setTaskDescription(ActivityManager.TaskDescription(title))
        }
    }

    override fun setPosition(x: Float, y: Float) {
        // Android positions Activities through the system window manager.
    }

    override fun setContentSize(width: Float, height: Float) {
        // Activity dimensions are managed by Android's display and multi-window layout.
    }

    override fun bounds(): ScreenBounds? {
        val decor = activity?.window?.decorView ?: return null
        val density = decor.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val loc = IntArray(2)
        decor.getLocationOnScreen(loc)
        return ScreenBounds(
            x = loc[0] / density,
            y = loc[1] / density,
            width = decor.width / density,
            height = decor.height / density,
        )
    }

    override fun focus() {
        val current = activity ?: return
        if (closed) return
        val intent = Intent(current, current::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        current.startActivity(intent)
    }

    override val isKey: Boolean
        get() = focused

    override fun pointerOnScreen(): ScreenPointer? = null

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false

    override fun setColorScheme(scheme: ColorScheme) {
        // Per-Activity appearance follows system chrome; handled by AndroidSystemChrome.
    }

    override fun adoptedBy(nodeId: Int) {
        this.nodeId = nodeId
    }

    override suspend fun awaitFrame(onFrame: (Long) -> Unit) {
        frames.awaitFrame(onFrame)
    }

    /** The view a drag out of this window starts from, or null while no Activity is attached. */
    fun dragView(): View? = activity?.window?.decorView

    override fun close() {
        if (closed) return
        closed = true
        frames.close()
        val current = activity
        activity = null
        current?.finishAndRemoveTask()
    }

    /** Replaces the Activity reference after a configuration change without closing the window. */
    fun rebind(newActivity: Activity) {
        if (closed) return
        activity = newActivity
        bounds()?.let { listener.onMoved(it) }
    }

    /** Called from `Activity.onWindowFocusChanged`. */
    fun focusChanged(hasFocus: Boolean) {
        if (closed || focused == hasFocus) return
        focused = hasFocus
        listener.onFocusChanged(hasFocus)
    }

    /** Called from `OnBackPressedCallback` on a secondary window Activity. */
    fun backPressed() {
        if (closed) return
        listener.onCloseRequested()
    }

    /** Called when the user dismisses the Activity task from recent tasks. */
    fun closedBySystem() {
        if (closed) return
        closed = true
        frames.close()
        activity = null
        listener.onClosedBySystem()
    }
}

/**
 * Multi-window [WindowBackend] for Android backed by document-launch Activities.
 *
 * The first `Window` node binds to the initial Activity. Each subsequent `Window` node
 * launches a new document Activity with `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK`
 * (and `FLAG_ACTIVITY_LAUNCH_ADJACENT` on API 24+ when in multi-window mode), carrying the
 * `Window` node id and `RestoreKey` in its Intent extras.
 */
class AndroidWindowBackend(
    private var rootActivity: Activity? = null,
    private val windowActivityClass: Class<out Activity>? = null,
    private val multiWindowSupported: () -> Boolean = { true },
    /**
     * The token of this process, carried with the node id in every window Activity's intent,
     * so a window revived in a later process is not bound by an id that process never issued.
     */
    private val processToken: Long = 0L,
) : WindowBackend {
    private var appListener: ApplicationListener? = null
    private var initialClaimed = false
    private val windows = LinkedHashMap<Int, AndroidActivityWindow>()

    /**
     * Hears a tear-off drag pass over the application's windows ([acceptTearOffDrops]) and
     * tells their drop targets. Set by the runtime to its window manager.
     */
    var tabDrags: WindowManager? = null

    override val supportsMultipleWindows: Boolean
        get() = multiWindowSupported()

    override fun setApplicationListener(listener: ApplicationListener?) {
        appListener = listener
    }

    /** Updates the root Activity reference when recreated by a configuration change. */
    fun attachRootActivity(activity: Activity) {
        rootActivity = activity
    }

    override fun open(
        config: PlatformWindowConfig,
        listener: PlatformWindowListener,
    ): PlatformWindow? {
        val currentRoot = rootActivity
        if (!initialClaimed && currentRoot != null) {
            initialClaimed = true
            val window = AndroidActivityWindow(
                nodeId = config.nodeId,
                restoreKey = config.restoreKey,
                activity = currentRoot,
                listener = listener,
            )
            windows[config.nodeId] = window
            return window
        }

        if (!supportsMultipleWindows || currentRoot == null) return null
        val targetClass = windowActivityClass ?: currentRoot::class.java
        val intent = Intent(currentRoot, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            }
            putExtra(EXTRA_WINDOW_NODE_ID, config.nodeId)
            putExtra(EXTRA_WINDOW_PROCESS, processToken)
            putExtra(EXTRA_WINDOW_RESTORE_KEY, config.restoreKey)
        }
        return try {
            val options = launchBoundsOptions(currentRoot, config)
            if (options != null) {
                currentRoot.startActivity(intent, options)
            } else {
                currentRoot.startActivity(intent)
            }
            val window = AndroidActivityWindow(
                nodeId = config.nodeId,
                restoreKey = config.restoreKey,
                activity = null,
                listener = listener,
            )
            windows[config.nodeId] = window
            window
        } catch (_: Throwable) {
            null
        }
    }

    private fun launchBoundsOptions(activity: Activity, config: PlatformWindowConfig): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val x = config.x ?: return null
        val y = config.y ?: return null
        if (config.width <= 0f || config.height <= 0f) return null
        val density = activity.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val left = (x * density).roundToInt()
        val top = (y * density).roundToInt()
        val right = ((x + config.width) * density).roundToInt()
        val bottom = ((y + config.height) * density).roundToInt()
        return ActivityOptions.makeBasic().setLaunchBounds(Rect(left, top, right, bottom)).toBundle()
    }

    /**
     * Starts the platform drag that tears a `TearOffKey` node out into a window of its own.
     *
     * Works on Android 15 (API 35) and later. The drag carries an immutable [PendingIntent]
     * for the window Activity (`FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK`,
     * [restoreKey] in [EXTRA_WINDOW_RESTORE_KEY]) as the clip item's `IntentSender`, and is
     * started with `DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG`. When the user drops it
     * where no window takes it, the system may start that Activity, for example into split
     * screen or a free-form window. The system decides from the screen size and the windowing
     * mode; when it does not start it, the drag ends as cancelled and nothing is reported.
     * The started Activity reaches [activityCreated] with no node id and the key, which asks
     * the Host for a window under that key (`WindowRequested`).
     *
     * `DRAG_FLAG_GLOBAL_SAME_APPLICATION` keeps the clip, and with it the `IntentSender`,
     * away from other applications while still letting the application's other windows see
     * the drag pass over them.
     *
     * Each key gets its own PendingIntent: the intent's identifier is part of the identity the
     * system matches PendingIntents by, and extras are not, so two keys never share one and
     * a drag never starts a window under an older key. The request code is derived from the
     * key as well.
     *
     * Returns false, and starts nothing, below Android 15, for an empty key, when the device
     * cannot show more than one window, or when no Activity is attached. Before Android 15
     * the public API has no way for an application's own drag to start an Activity, so the
     * application offers the same thing as a menu action that puts a `Window` node in.
     *
     * [shadow] is what the user sees under the finger; by default the whole of [view].
     * [sourceWindow] is the `Window` node the drag starts in. It travels as the drag's local
     * state, which only this process' windows see, so the window it came from is not told it
     * passed over itself.
     */
    fun startTearOffDrag(
        view: View,
        restoreKey: String,
        shadow: View.DragShadowBuilder = View.DragShadowBuilder(view),
        sourceWindow: Int = 0,
    ): Boolean {
        // Spelled out rather than through androidTearOffSupported, so the API check below is
        // one the Android tooling can see.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || restoreKey.isEmpty()) return false
        if (!supportsMultipleWindows) return false
        val currentRoot = rootActivity ?: return false
        val targetClass = windowActivityClass ?: currentRoot::class.java
        val intent = Intent(currentRoot, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_WINDOW_RESTORE_KEY, restoreKey)
            setIdentifier(TEAR_OFF_IDENTIFIER_PREFIX + restoreKey)
        }
        val pendingIntent = PendingIntent.getActivity(
            currentRoot,
            restoreKey.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val clip = ClipData(
            ClipDescription(restoreKey, arrayOf(MIMETYPE_TEAR_OFF)),
            ClipData.Item.Builder().setIntentSender(pendingIntent.intentSender).build(),
        )
        return view.startDragAndDrop(
            clip,
            shadow,
            sourceWindow,
            View.DRAG_FLAG_GLOBAL_SAME_APPLICATION or View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG,
        )
    }

    /**
     * Binds a newly created or recreated Activity to its [AndroidActivityWindow] by [nodeId],
     * or notifies [ApplicationListener.onWindowRequested] if the system revived a task after
     * process death, made a window for a dropped tab, or the launcher started the application
     * again after the user closed every window.
     */
    fun activityCreated(
        activity: Activity,
        nodeId: Int,
        restoreKey: String,
    ): AndroidActivityWindow? {
        if (nodeId != 0) {
            val existing = windows[nodeId]
            if (existing != null) {
                existing.rebind(activity)
                return existing
            }
        }
        val placeholderListener = object : PlatformWindowListener {
            override fun onCloseRequested() {}
            override fun onFocusChanged(focused: Boolean) {}
            override fun onMoved(bounds: ScreenBounds) {}
            override fun onFollowEnded(cancelled: Boolean) {}
        }
        val window = AndroidActivityWindow(
            nodeId = nodeId,
            restoreKey = restoreKey,
            activity = activity,
            listener = placeholderListener,
        )
        appListener?.onWindowRequested(restoreKey, window)
        if (window.nodeId != 0) {
            windows[window.nodeId] = window
        }
        return window
    }

    fun windowFor(nodeId: Int): AndroidActivityWindow? = windows[nodeId]

    /**
     * Makes [view], the root of one of the application's windows, take a tear-off drag dropped
     * on it. A drop the application's own window takes is handled, so the system does not open
     * a window for it: a tab dropped back inside a window stays where it was.
     *
     * While the drag is over the window, its place goes to [tabDrags], which tells the node
     * under it that registered for a drag passing over (`WindowDragOver`). The place is in the
     * view's pixels, which are the pixels the window's content is laid out in: the content
     * fills the window from its top left corner.
     */
    fun acceptTearOffDrops(view: View) {
        view.setOnDragListener { target, event ->
            // Interested in its own drags only; once interested, it hears the rest of them
            // and takes their drop.
            if (event.action == DragEvent.ACTION_DRAG_STARTED) {
                return@setOnDragListener event.clipDescription?.hasMimeType(MIMETYPE_TEAR_OFF) == true
            }
            val manager = tabDrags
            val window = windowOfView(target)
            val source = event.localState as? Int ?: 0
            when (event.action) {
                DragEvent.ACTION_DRAG_LOCATION -> if (manager != null) {
                    manager.tabDragMoved(window, source, event.x, event.y)
                    // Moves are sent once per frame, so a frame is asked for.
                    FrameRequests.request()
                }
                DragEvent.ACTION_DRAG_EXITED -> manager?.tabDragExited(window)
                DragEvent.ACTION_DROP -> manager?.tabDropped(window, source, event.x, event.y)
                DragEvent.ACTION_DRAG_ENDED -> manager?.tabDragEnded()
            }
            true
        }
    }

    /** The `Window` node whose Activity [view] is the root of, or 0. */
    private fun windowOfView(view: View): Int =
        windows.values.firstOrNull { it.dragView() === view }?.nodeId ?: 0

    companion object {
        const val EXTRA_WINDOW_NODE_ID = "dev.darkpyonix.composerust.WINDOW_NODE_ID"

        /** The token of the process that issued [EXTRA_WINDOW_NODE_ID]. */
        const val EXTRA_WINDOW_PROCESS = "dev.darkpyonix.composerust.WINDOW_PROCESS"
        const val EXTRA_WINDOW_RESTORE_KEY = "dev.darkpyonix.composerust.WINDOW_RESTORE_KEY"

        /** The MIME type of a tear-off drag, so a window it passes over can tell it apart. */
        const val MIMETYPE_TEAR_OFF = "application/vnd.dev.darkpyonix.composerust.tear-off"

        private const val TEAR_OFF_IDENTIFIER_PREFIX = "dev.darkpyonix.composerust.tear-off:"
    }
}

/**
 * Android's tear-off: a long press and a drag on a keyed node starts the system drag that may
 * open the node's window as an Activity ([AndroidWindowBackend.startTearOffDrag]).
 */
internal class AndroidTearOffDrag(private val backend: AndroidWindowBackend) : TearOffDrag {
    override val trigger: TearOffTrigger get() = TearOffTrigger.LongPressThenDrag

    override fun start(request: TearOffRequest): Boolean {
        val view = backend.windowFor(request.windowId)?.dragView() ?: return false
        return backend.startTearOffDrag(view, request.key, TearOffShadow(request), request.windowId)
    }
}

/** The node's own outline under the finger, where it was pressed, rather than the whole window. */
private class TearOffShadow(request: TearOffRequest) : View.DragShadowBuilder() {
    private val width = (request.width * request.density).roundToInt().coerceAtLeast(1)
    private val height = (request.height * request.density).roundToInt().coerceAtLeast(1)
    private val touchX = (request.x * request.density).roundToInt().coerceIn(0, width - 1)
    private val touchY = (request.y * request.density).roundToInt().coerceIn(0, height - 1)
    private val radius = 8f * request.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66808080 }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(width, height)
        outShadowTouchPoint.set(touchX, touchY)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, fill)
    }
}
