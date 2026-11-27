package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.ColorScheme
import kotlinx.cinterop.useContents
import kotlinx.coroutines.awaitCancellation
import platform.Foundation.NSUserActivity
import platform.UIKit.UIApplication
import platform.UIKit.UISceneSession
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * One iOS/iPadOS `UIWindowScene` acting as a [PlatformWindow].
 *
 * When the system disconnects a background scene (`sceneDidDisconnect`), [disconnect] drops
 * the `UIWindow` while keeping this handle and its `Window` node alive; [reconnect] binds the
 * new `UIWindow` when the session comes back. Only [closedBySystem] (`didDiscardSceneSessions`)
 * reports that the user dismissed the scene from the app switcher.
 */
internal class IosSceneWindow(
    var nodeId: Int,
    val restoreKey: String,
    var session: UISceneSession?,
    var window: UIWindow?,
    private var listener: PlatformWindowListener,
    private val onRequestActivation: (UISceneSession?, Int, String) -> Unit,
    private val onRequestDestruction: (UISceneSession?) -> Unit,
) : PlatformWindow {
    private var closed = false
    private var keyWindow = window?.isKeyWindow() == true

    override fun setListener(listener: PlatformWindowListener) {
        this.listener = listener
    }

    override fun setTitle(title: String) {
        window?.windowScene?.title = title
    }

    override fun setPosition(x: Float, y: Float) {
        // iOS and iPadOS place scenes through the system window manager.
    }

    override fun setContentSize(width: Float, height: Float) {
        // Scene dimensions are controlled by the system on iOS and iPadOS.
    }

    override fun bounds(): ScreenBounds? {
        val w = window ?: return null
        return w.frame.useContents {
            ScreenBounds(
                x = origin.x.toFloat(),
                y = origin.y.toFloat(),
                width = size.width.toFloat(),
                height = size.height.toFloat(),
            )
        }
    }

    override fun focus() {
        if (closed) return
        val currentSession = session
        if (currentSession != null) {
            onRequestActivation(currentSession, nodeId, restoreKey)
        } else {
            window?.makeKeyAndVisible()
        }
    }

    override val isKey: Boolean
        get() = keyWindow

    override fun pointerOnScreen(): ScreenPointer? = null

    override fun followPointer(anchorX: Float, anchorY: Float): Boolean = false

    override fun setColorScheme(scheme: ColorScheme) {
        val style = when (scheme) {
            ColorScheme.Light -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleLight
            ColorScheme.Dark -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleDark
            ColorScheme.FollowSystem -> platform.UIKit.UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
        }
        window?.overrideUserInterfaceStyle = style
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
        val currentSession = session
        window = null
        session = null
        if (currentSession != null) {
            onRequestDestruction(currentSession)
        }
    }

    /** Called when the scene becomes active or resigns active. */
    fun focusChanged(focused: Boolean) {
        if (closed || keyWindow == focused) return
        keyWindow = focused
        listener.onFocusChanged(focused)
    }

    /** Called on `sceneDidDisconnect`: drops the view hierarchy without closing the `Window` node. */
    fun disconnect() {
        window = null
        if (keyWindow) {
            keyWindow = false
            listener.onFocusChanged(false)
        }
    }

    /** Called when a disconnected session reconnects in `scene(_:willConnectTo:)`. */
    fun reconnect(newSession: UISceneSession?, newWindow: UIWindow) {
        if (closed) return
        session = newSession
        window = newWindow
        bounds()?.let { listener.onMoved(it) }
    }

    /** Called on `didDiscardSceneSessions`: the user dismissed the scene from the app switcher. */
    fun closedBySystem() {
        if (closed) return
        closed = true
        window = null
        session = null
        listener.onClosedBySystem()
    }
}

/**
 * Multi-window [WindowBackend] for iOS and iPadOS backed by `UIWindowScene` sessions.
 *
 * The first `Window` node binds to the initial launch window. Each additional `Window` node
 * requests a new scene session via `UIApplication.requestSceneSessionActivation` carrying the
 * node id and `RestoreKey` in an `NSUserActivity`.
 */
internal class IosWindowBackend(
    private var initialWindow: UIWindow? = null,
    private var initialSession: UISceneSession? = null,
    private val multipleScenesSupported: () -> Boolean = {
        UIApplication.sharedApplication.supportsMultipleScenes
    },
    private val activateScene: (UISceneSession?, Int, String) -> Boolean = { session, nodeId, restoreKey ->
        val activity = NSUserActivity(activityType = WINDOW_ACTIVITY_TYPE)
        activity.userInfo = mapOf(
            KEY_NODE_ID to nodeId,
            KEY_RESTORE_KEY to restoreKey,
        )
        UIApplication.sharedApplication.requestSceneSessionActivation(
            sceneSession = session,
            userActivity = activity,
            options = null,
            errorHandler = null,
        )
        true
    },
    private val destroyScene: (UISceneSession?) -> Unit = { session ->
        if (session != null) {
            UIApplication.sharedApplication.requestSceneSessionDestruction(
                sceneSession = session,
                options = null,
                errorHandler = null,
            )
        }
    },
) : WindowBackend {
    private var appListener: ApplicationListener? = null
    private var initialClaimed = false
    private val windows = LinkedHashMap<Int, IosSceneWindow>()

    override val supportsMultipleWindows: Boolean
        get() = multipleScenesSupported()

    override fun setApplicationListener(listener: ApplicationListener?) {
        appListener = listener
    }

    override fun open(
        config: PlatformWindowConfig,
        listener: PlatformWindowListener,
    ): PlatformWindow? {
        if (!initialClaimed && initialWindow != null) {
            initialClaimed = true
            val sceneWindow = IosSceneWindow(
                nodeId = config.nodeId,
                restoreKey = config.restoreKey,
                session = initialSession,
                window = initialWindow,
                listener = listener,
                onRequestActivation = { s, id, key -> activateScene(s, id, key) },
                onRequestDestruction = destroyScene,
            )
            windows[config.nodeId] = sceneWindow
            return sceneWindow
        }

        if (!supportsMultipleWindows) return null
        val requested = activateScene(null, config.nodeId, config.restoreKey)
        if (!requested) return null

        val sceneWindow = IosSceneWindow(
            nodeId = config.nodeId,
            restoreKey = config.restoreKey,
            session = null,
            window = null,
            listener = listener,
            onRequestActivation = { s, id, key -> activateScene(s, id, key) },
            onRequestDestruction = destroyScene,
        )
        windows[config.nodeId] = sceneWindow
        return sceneWindow
    }

    /**
     * Binds a newly connected `UIWindowScene` to an existing `Window` node (by [nodeId]) or
     * notifies [ApplicationListener.onWindowRequested] when the system created a fresh or
     * restored scene.
     */
    fun sceneConnected(
        nodeId: Int?,
        restoreKey: String,
        session: UISceneSession?,
        window: UIWindow,
    ): IosSceneWindow? {
        if (nodeId != null) {
            val existing = windows[nodeId]
            if (existing != null) {
                existing.reconnect(session, window)
                return existing
            }
        }
        val placeholderListener = object : PlatformWindowListener {
            override fun onCloseRequested() {}
            override fun onFocusChanged(focused: Boolean) {}
            override fun onMoved(bounds: ScreenBounds) {}
            override fun onFollowEnded(cancelled: Boolean) {}
        }
        val sceneWindow = IosSceneWindow(
            nodeId = nodeId ?: 0,
            restoreKey = restoreKey,
            session = session,
            window = window,
            listener = placeholderListener,
            onRequestActivation = { s, id, key -> activateScene(s, id, key) },
            onRequestDestruction = destroyScene,
        )
        appListener?.onWindowRequested(restoreKey, sceneWindow)
        if (sceneWindow.nodeId != 0) {
            windows[sceneWindow.nodeId] = sceneWindow
        }
        return sceneWindow
    }

    /** Binds the initial launch window before the first `Window` node claims it. */
    fun attachInitialWindow(window: UIWindow, session: UISceneSession? = null) {
        initialWindow = window
        initialSession = session
    }

    /** Every currently connected `UIWindow`, for system chrome updates across all scenes. */
    fun allWindows(): List<UIWindow> {
        val result = ArrayList<UIWindow>()
        for (entry in windows.values) {
            entry.window?.let { result += it }
        }
        val initial = initialWindow
        if (initial != null && initial !in result) {
            result += initial
        }
        return result
    }

    /** Looks up the tracked scene window for a `Window` node id. */
    fun windowFor(nodeId: Int): IosSceneWindow? = windows[nodeId]

    /** Looks up the tracked scene window for a `UISceneSession` (e.g. on `didDiscardSceneSessions`). */
    fun windowForSession(session: UISceneSession): IosSceneWindow? =
        windows.values.firstOrNull {
            it.session === session || it.session?.persistentIdentifier == session.persistentIdentifier
        }

    companion object {
        const val WINDOW_ACTIVITY_TYPE = "dev.darkpyonix.composerust.window"
        const val KEY_NODE_ID = "nodeId"
        const val KEY_RESTORE_KEY = "restoreKey"

        /** Builds the `NSUserActivity` that carries a `TearOffKey` into a new scene session. */
        fun tearOffUserActivity(restoreKey: String): NSUserActivity =
            NSUserActivity(activityType = WINDOW_ACTIVITY_TYPE).apply {
                userInfo = mapOf(KEY_RESTORE_KEY to restoreKey)
            }
    }
}
