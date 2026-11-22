package dev.darkpyonix.composerust.ui.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.View
import dev.darkpyonix.composerust.protocol.ColorScheme
import kotlinx.coroutines.awaitCancellation
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
        awaitCancellation()
    }

    override fun close() {
        if (closed) return
        closed = true
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
) : WindowBackend {
    private var appListener: ApplicationListener? = null
    private var initialClaimed = false
    private val windows = LinkedHashMap<Int, AndroidActivityWindow>()

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
     * Starts an Android 12L+ drag-to-split gesture for a `TearOffKey` node.
     *
     * Drops onto a split-screen edge launch a new document Activity carrying [restoreKey],
     * which reports `WindowRequested` back to the Host. Returns false on older API levels or
     * when no root Activity is attached.
     */
    fun startTearOffDrag(view: View, restoreKey: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 || restoreKey.isEmpty()) return false
        val currentRoot = rootActivity ?: return false
        val targetClass = windowActivityClass ?: currentRoot::class.java
        val intent = Intent(currentRoot, targetClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(EXTRA_WINDOW_RESTORE_KEY, restoreKey)
        }
        val clip = ClipData(
            ClipDescription(restoreKey, arrayOf(ClipDescription.MIMETYPE_APPLICATION_ACTIVITY)),
            ClipData.Item(intent),
        )
        return view.startDragAndDrop(clip, View.DragShadowBuilder(view), null, View.DRAG_FLAG_GLOBAL)
    }

    /**
     * Binds a newly created or recreated Activity to its [AndroidActivityWindow] by [nodeId],
     * or notifies [ApplicationListener.onWindowRequested] if the system revived a task after
     * process death.
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

    companion object {
        const val EXTRA_WINDOW_NODE_ID = "dev.darkpyonix.composerust.WINDOW_NODE_ID"
        const val EXTRA_WINDOW_RESTORE_KEY = "dev.darkpyonix.composerust.WINDOW_RESTORE_KEY"
    }
}
