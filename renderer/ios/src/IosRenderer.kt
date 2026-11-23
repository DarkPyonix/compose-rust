package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.darkpyonix.composerust.foundation.LocalNativeViewWindow
import dev.darkpyonix.composerust.foundation.LocalOsMenuPresenter
import dev.darkpyonix.composerust.foundation.NativeViewPlatform
import dev.darkpyonix.composerust.foundation.SystemChrome
import dev.darkpyonix.composerust.foundation.systemChrome
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.TearOffDrag
import dev.darkpyonix.composerust.runtime.TearOffRequest
import dev.darkpyonix.composerust.runtime.TearOffTargets
import dev.darkpyonix.composerust.runtime.TearOffTrigger
import dev.darkpyonix.composerust.runtime.reduceTransparency
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.random.Random
import platform.Foundation.NSClassFromString
import platform.Foundation.NSItemProvider
import platform.Foundation.NSItemProviderRepresentationVisibilityAll
import platform.Foundation.NSItemProviderWritingProtocol
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSStringFromClass
import platform.Foundation.NSUserActivity
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled
import platform.UIKit.UIAccessibilityReduceTransparencyStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIDragInteraction
import platform.UIKit.UIDragInteractionDelegateProtocol
import platform.UIKit.UIDragItem
import platform.UIKit.UIDragSessionProtocol
import platform.UIKit.UIDropInteraction
import platform.UIKit.UIDropInteractionDelegateProtocol
import platform.UIKit.UIDropOperationCancel
import platform.UIKit.UIDropOperationMove
import platform.UIKit.UIDropProposal
import platform.UIKit.UIDropSessionProtocol
import platform.UIKit.UIView
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScene
import platform.UIKit.UISceneConfiguration
import platform.UIKit.UISceneConnectionOptions
import platform.UIKit.UISceneSession
import platform.UIKit.UIScreen
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneDelegateProtocol
import platform.UIKit.UIWindowSceneDelegateProtocolMeta
import platform.UIKit.addInteraction
import platform.darwin.NSObject

/**
 * Runs the renderer's Compose application. This is what `compose_rust_renderer_run`
 * calls.
 *
 * The desktop renderer opens a window and returns when the window closes. iOS has no such
 * moment: `UIApplicationMain` installs the run loop and never returns, and the system, not
 * the application, decides when the process ends. So on iOS `run` blocks for the lifetime of
 * the process. A Rust `main` that calls `compose_rust::launch(app)` gets the behaviour it
 * already expects on desktop, which is that nothing after the call runs while the UI is up.
 *
 * The screen is drawn entirely from the mutation batches the Host streams.
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    hostConnectionFactory = connection
    UIApplicationMain(0, null, null, NSStringFromClass(RendererAppDelegate))
    return 0
}

private var hostConnectionFactory: () -> HostConnection = { IosHostConnection() }

/**
 * Updates the shared [reduceTransparency] flag from the iOS accessibility setting so Liquid Glass
 * surfaces switch to their opaque fallback and skip blur passes when Reduce Transparency is on.
 */
internal fun syncReduceTransparency(
    enabled: Boolean = UIAccessibilityIsReduceTransparencyEnabled(),
) {
    reduceTransparency = enabled
}

/**
 * Installs [systemChrome] so apps that force Light or Dark `ColorScheme` independent of the OS
 * setting keep the status bar icons legible against the resolved theme across all windows.
 */
internal fun installIosSystemChrome(windowsProvider: () -> List<UIWindow>) {
    systemChrome = object : SystemChrome {
        override fun setDarkIcons(dark: Boolean) {
            val style = if (dark) {
                UIUserInterfaceStyle.UIUserInterfaceStyleLight
            } else {
                UIUserInterfaceStyle.UIUserInterfaceStyleDark
            }
            for (window in windowsProvider()) {
                window.overrideUserInterfaceStyle = style
                window.rootViewController?.setNeedsStatusBarAppearanceUpdate()
            }
        }
    }
}

/**
 * The `UIDragInteraction` delegate of one scene's window, which pulls a node carrying a
 * `TearOffKey` out into a scene of its own on iPadOS.
 *
 * UIKit's drag interaction recognises the long press and the lift itself; an application
 * cannot start a drag session any other way. When it lifts, this asks the interpreter which
 * keyed node of the scene's `Window` node is under the touch ([TearOffTargets]) and offers a
 * drag item whose provider carries the window `NSUserActivity` with that key, the way Safari's
 * tabs do. Dropped at the edge of the screen, the system makes a new scene from it, and the
 * scene delegate asks the application for a window under the key. Anywhere else nothing is
 * offered and the touch goes on to the content. On an iPhone the interaction is disabled by
 * default, so no drag starts there.
 */
internal class IosTearOffDragDelegate(
    private val windowNodeId: () -> Int,
) : NSObject(), UIDragInteractionDelegateProtocol {
    override fun dragInteraction(
        interaction: UIDragInteraction,
        itemsForBeginningSession: UIDragSessionProtocol,
    ): List<*> {
        val view = interaction.view ?: return emptyList<Any>()
        val windowId = windowNodeId()
        if (windowId == 0) return emptyList<Any>()
        // The interpreter laid the nodes out in pixels; UIKit measures the touch in points.
        val scale = view.window?.screen?.scale ?: UIScreen.mainScreen.scale
        val key = itemsForBeginningSession.locationInView(view).useContents {
            TearOffTargets.keyAt(windowId, (x * scale).toFloat(), (y * scale).toFloat())
        } ?: return emptyList<Any>()
        // Which window the tab comes from, for the other scenes it passes over. A session's
        // local context is seen only inside this application, as Android's local state is.
        itemsForBeginningSession.localContext = windowId
        val activity = IosWindowBackend.tearOffUserActivity(key)
        val provider = NSItemProvider()
        provider.registerObject(activity as NSItemProviderWritingProtocol, NSItemProviderRepresentationVisibilityAll)
        val item = UIDragItem(itemProvider = provider)
        item.localObject = activity
        return listOf(item)
    }
}

/**
 * The `UIDropInteraction` delegate of one scene's window, which tells the scene's drop targets
 * about a tab dragged out of another scene (`WindowDragOver`).
 *
 * Only a drag this application started as a tear-off is taken: its session is local and its
 * local context is the `Window` node the tab came from. Every place the tab passes goes to the
 * window manager, which decides as it does for Android: the registered node under the tab in
 * this window hears Enter, a Move once per frame, Leave and Drop, and the window the tab came
 * from is never a target. The window proposes to take the drop only over such a node; anywhere
 * else the drop stays the system's, so a tab let go at the edge of the screen still becomes a
 * scene of its own. A place is the touch in points, which are this window's dp; the drop
 * targets scale it by the screen's pixels per point, the pixels the content was laid out in.
 */
internal class IosTabDropDelegate(
    private val windowNodeId: () -> Int,
    private val manager: () -> WindowManager?,
) : NSObject(), UIDropInteractionDelegateProtocol {
    /** The `Window` node the tab of [session] came from, or null for a drag that is not a tear-off. */
    private fun sourceOf(session: UIDropSessionProtocol): Int? =
        when (val context = session.localDragSession?.localContext) {
            is Number -> context.toInt()
            is NSNumber -> context.intValue
            else -> null
        }

    private fun place(interaction: UIDropInteraction, session: UIDropSessionProtocol): Pair<Float, Float>? {
        val view: UIView = interaction.view ?: return null
        return session.locationInView(view).useContents { x.toFloat() to y.toFloat() }
    }

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, canHandleSession: UIDropSessionProtocol): Boolean =
        sourceOf(canHandleSession) != null

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, sessionDidEnter: UIDropSessionProtocol) {
        // The first place arrives with the first update.
    }

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, sessionDidUpdate: UIDropSessionProtocol): UIDropProposal {
        val source = sourceOf(sessionDidUpdate)
        val windows = manager()
        val window = windowNodeId()
        val at = place(interaction, sessionDidUpdate)
        if (source == null || windows == null || window == 0 || at == null) {
            return UIDropProposal(dropOperation = UIDropOperationCancel)
        }
        windows.tabDragMoved(window, source, at.first, at.second)
        // Moves are sent once per frame, so a frame is asked for.
        FrameRequests.request()
        val lands = windows.tabDragLands(window, source, at.first, at.second)
        return UIDropProposal(dropOperation = if (lands) UIDropOperationMove else UIDropOperationCancel)
    }

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, sessionDidExit: UIDropSessionProtocol) {
        manager()?.tabDragExited(windowNodeId())
    }

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, performDrop: UIDropSessionProtocol) {
        val source = sourceOf(performDrop) ?: return
        val at = place(interaction, performDrop) ?: return
        manager()?.tabDropped(windowNodeId(), source, at.first, at.second)
    }

    @ObjCSignatureOverride
    override fun dropInteraction(interaction: UIDropInteraction, sessionDidEnd: UIDropSessionProtocol) {
        manager()?.tabDragEnded()
    }
}

/**
 * iPadOS' tear-off. The drag interaction on each scene's window starts the drag, so the
 * interpreter only records where keyed nodes are and is never asked to start one.
 */
private object IosTearOffGesture : TearOffDrag {
    override val trigger: TearOffTrigger get() = TearOffTrigger.PlatformGesture

    override fun start(request: TearOffRequest): Boolean = false
}

/**
 * Process-wide iOS renderer state shared across all `UIWindowScene` sessions so one Host and
 * one node table drive every scene on the main thread.
 */
private object IosRendererSession {
    private var platformConfigured = false
    private var host: ComposeRustHost? = null
    private var windowBackend: IosWindowBackend? = null
    private var windowManager: WindowManager? = null
    private var fallbackWindow: UIWindow? = null
    private var reduceTransparencyObserver: Any? = null
    private var windowFrames: Job? = null

    /** The drag interactions' delegates, which UIKit holds weakly, one per scene window. */
    private val tearOffDelegates = HashMap<UIWindow, IosTearOffDragDelegate>()

    /** The drop interactions' delegates, which UIKit also holds weakly, one per scene window. */
    private val tabDropDelegates = HashMap<UIWindow, IosTabDropDelegate>()

    /**
     * This process' token. A scene session keeps its `userInfo` after the process dies, and a
     * node id saved there with another process' token names another node here, or none.
     */
    val processToken: Long = Random.nextLong().let { if (it == 0L) 1L else it }

    /** Whether a scene has connected in this process yet; the first one draws the first window. */
    var anySceneConnected = false

    fun ensurePlatformConfigured() {
        if (platformConfigured) return
        platformConfigured = true
        // Before anything else, and before the Host: a notification pressed while the
        // application was not running is delivered as soon as launching finishes, and the
        // delegate that hears it has to be in place by then.
        Notifications.platform = AppleNotifications(withdrawAtExit = false, bringToFront = {})
        // The accessibility setting a page's `prefers-reduced-motion` answers to, asked at
        // start and whenever the application comes back into use.
        dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting = {
            if (platform.UIKit.UIAccessibilityIsReduceMotionEnabled()) {
                dev.darkpyonix.composerust.protocol.ReducedMotion.On
            } else {
                dev.darkpyonix.composerust.protocol.ReducedMotion.Off
            }
        }
        // Respect OS "Reduce Transparency" at startup and whenever the user toggles it live.
        syncReduceTransparency()
        reduceTransparencyObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityReduceTransparencyStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            syncReduceTransparency()
        }
        // Keep status bar icons legible against the resolved theme on every window.
        installIosSystemChrome {
            val sceneWindows = windowBackend?.allWindows().orEmpty()
            val fallback = fallbackWindow
            if (fallback != null && fallback !in sceneWindows) sceneWindows + fallback else sceneWindows
        }
        // Where the application's zoom level is kept between runs: the user defaults, which
        // are already the application's own.
        dev.darkpyonix.composerust.runtime.platformZoomLevelStore = { UserDefaultsZoomLevel }
        NativeViewPlatform.available = true
    }

    fun ensureHost(): ComposeRustHost {
        host?.let { return it }
        ensurePlatformConfigured()
        val created = ComposeRustHost(hostConnectionFactory())
        created.start()
        host = created
        return created
    }

    fun ensureWindowManager(initialWindow: UIWindow? = null, initialSession: UISceneSession? = null): IosWindowBackend {
        val currentHost = ensureHost()
        val existing = windowBackend
        if (existing != null) {
            if (initialWindow != null) existing.attachInitialWindow(initialWindow, initialSession)
            windowManager?.let { startWindowFrames(currentHost, it) }
            return existing
        }
        val backend = IosWindowBackend(
            initialWindow = initialWindow,
            initialSession = initialSession,
            processToken = processToken,
        )
        // A tab dragged over a scene finds the node under it from where the scene's content
        // laid its registered nodes out, in pixels: a point of the touch is the screen's
        // scale in pixels.
        val manager = WindowManager(
            currentHost,
            backend,
            onExit = {},
            dropTargets = DropTargets.registered { _ -> UIScreen.mainScreen.scale.toFloat() },
        )
        if (backend.supportsMultipleWindows) manager.tearOffDrag = IosTearOffGesture
        windowBackend = backend
        windowManager = manager
        manager.attach()
        startWindowFrames(currentHost, manager)
        return backend
    }

    /**
     * Starts the one frame driver of an application of several windows, once. Each scene of
     * such an application draws one `Window` node and serves no frames of its own; the driver
     * asks the Host once per frame, when the first of the scenes' clocks ticks, on the main
     * thread.
     */
    private fun startWindowFrames(host: ComposeRustHost, manager: WindowManager) {
        if (windowFrames != null) return
        if (!windowFrameDriverWanted(host.table.window?.mode, manager.openWindows.size, attachedWindows = 1)) return
        val requests = FrameRequests.global
        windowFrames = MainScope().launch {
            serveWindowFrames(
                host,
                manager,
                requests,
                alreadyServed = if (host.startedWith === requests) host.requestsBeforeStart else requests.counter.value,
            )
        }
    }

    fun buildRootViewController(window: UIWindow, host: ComposeRustHost, windowNodeId: Int): UIViewController {
        val nativeViews = IosNativeViewWindow(window)
        val osMenus = IosOsMenuPresenter(window)
        // One scene of several draws its `Window` node and answers the driver from its frames.
        val frames = if (windowNodeId == 0) null else windowBackend?.windowFor(windowNodeId)?.frames
        if (windowNodeId != 0 && windowBackend?.supportsMultipleWindows == true) {
            val delegate = IosTearOffDragDelegate { windowNodeId }
            tearOffDelegates[window] = delegate
            val drops = IosTabDropDelegate({ windowNodeId }, { windowManager })
            tabDropDelegates[window] = drops
            window.addInteraction(UIDropInteraction(delegate = drops))
            window.addInteraction(UIDragInteraction(delegate = delegate))
        }
        val content = ComposeUIViewController(
            configure = {
                // The renderer stops the page above the keyboard itself and lifts a sheet's
                // surface by the same inset. Left on, Compose would also slide the whole
                // view up to keep the focused field clear, and everything would move twice.
                onFocusBehavior = androidx.compose.ui.uikit.OnFocusBehavior.DoNothing
            },
        ) {
            CompositionLocalProvider(
                LocalNativeViewWindow provides nativeViews,
                LocalOsMenuPresenter provides osMenus,
            ) {
                ServeWindowFrames(frames)
                ComposeRustContent(
                    host,
                    // The whole screen. Where the safe area is is the renderer's to decide:
                    // a bar that opens the tree grows up into the status bar and a navigation
                    // grows down into the home indicator, the way every application on the
                    // platform does, and whatever is left over is kept off the page there.
                    Modifier.fillMaxSize(),
                    windowNodeId = windowNodeId,
                )
            }
        }
        return rendererRootViewController(content)
    }

    fun registerFallbackWindow(window: UIWindow) {
        fallbackWindow = window
    }

    /** A scene's window went away with its scene: its drag delegate goes too. */
    fun forgetWindow(window: UIWindow) {
        tearOffDelegates.remove(window)
        tabDropDelegates.remove(window)
    }

    fun discardSessions(sessions: Set<*>) {
        val backend = windowBackend ?: return
        for (item in sessions) {
            val session = item as? UISceneSession ?: continue
            backend.windowForSession(session)?.closedBySystem()
        }
    }
}

/**
 * The UIKit application delegate: initializes the process-wide Host and routes multi-window
 * scene sessions to [RendererSceneDelegate].
 */
private class RendererAppDelegate : UIResponder, UIApplicationDelegateProtocol {
    @OverrideInit
    constructor() : super()

    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    private var mainWindow: UIWindow? = null

    override fun window(): UIWindow? = mainWindow

    override fun setWindow(window: UIWindow?) {
        mainWindow = window
    }

    override fun application(
        application: UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?,
    ): Boolean {
        IosRendererSession.ensurePlatformConfigured()
        // When the Info.plist declares a scene manifest (multi-window iPadOS/iOS), UIKit
        // connects the initial window through RendererSceneDelegate instead of here.
        if (application.supportsMultipleScenes) {
            IosRendererSession.ensureHost()
            return true
        }
        val window = UIWindow(frame = UIScreen.mainScreen.bounds)
        IosRendererSession.registerFallbackWindow(window)
        val host = IosRendererSession.ensureHost()
        val backend = IosRendererSession.ensureWindowManager(initialWindow = window)
        val windowNodeId = if (host.table.window?.mode == WindowMode.Windows) {
            host.table.roots.firstOrNull { host.table.node(it)?.widget == dev.darkpyonix.composerust.protocol.WidgetKind.Window }
                ?: 0
        } else {
            0
        }
        window.rootViewController = IosRendererSession.buildRootViewController(window, host, windowNodeId)
        window.makeKeyAndVisible()
        mainWindow = window
        // Ensure the backend tracks the initial window if the first batch opened Window nodes.
        backend.attachInitialWindow(window)
        return true
    }

    override fun application(
        application: UIApplication,
        configurationForConnectingSceneSession: UISceneSession,
        options: UISceneConnectionOptions,
    ): UISceneConfiguration {
        val config = UISceneConfiguration(
            name = "Default Configuration",
            sessionRole = configurationForConnectingSceneSession.role,
        )
        config.delegateClass = NSClassFromString(NSStringFromClass(RendererSceneDelegate))
        return config
    }

    override fun application(
        application: UIApplication,
        didDiscardSceneSessions: Set<*>,
    ) {
        IosRendererSession.discardSessions(didDiscardSceneSessions)
    }
}

/**
 * Scene delegate for one iOS/iPadOS `UIWindowScene`.
 *
 * Each scene mounts its own `UIWindow` and `ComposeUIViewController` over the process-wide
 * [ComposeRustHost], rendering only its `Window` node's subtree in multi-window mode.
 */
private class RendererSceneDelegate : UIResponder, UIWindowSceneDelegateProtocol {
    @OverrideInit
    constructor() : super()

    companion object : UIResponderMeta(), UIWindowSceneDelegateProtocolMeta

    private var sceneWindowHandle: UIWindow? = null
    private var trackedScene: IosSceneWindow? = null

    override fun window(): UIWindow? = sceneWindowHandle

    override fun setWindow(window: UIWindow?) {
        sceneWindowHandle = window
    }

    override fun scene(
        scene: UIScene,
        willConnectToSession: UISceneSession,
        options: UISceneConnectionOptions,
    ) {
        val windowScene = scene as? UIWindowScene ?: return
        val uiWindow = UIWindow(windowScene = windowScene)
        val host = IosRendererSession.ensureHost()

        val activity = options.userActivities
            .filterIsInstance<NSUserActivity>()
            .firstOrNull { it.activityType == IosWindowBackend.WINDOW_ACTIVITY_TYPE }
        val activityInfo = activity?.userInfo
        val sessionInfo = willConnectToSession.userInfo
        // A node id is taken with the token saved beside it, from the same place. A session
        // reconnected after the process died carries the dead process' id, which is not trusted:
        // the scene is matched by its restore key instead.
        val carriedFrom = if (activityInfo?.get(IosWindowBackend.KEY_NODE_ID) != null) activityInfo else sessionInfo
        val carriedId = (carriedFrom?.get(IosWindowBackend.KEY_NODE_ID) as? Number)?.toInt() ?: 0
        val carriedProcess = (carriedFrom?.get(IosWindowBackend.KEY_PROCESS) as? Number)?.toLong() ?: 0L
        val nodeId = carriedWindowNodeId(carriedId, carriedProcess, IosRendererSession.processToken).takeIf { it != 0 }
        // The first scene of a process draws the first window, as it did before the process
        // died, rather than asking the application for a window it already has.
        val firstScene = !IosRendererSession.anySceneConnected
        IosRendererSession.anySceneConnected = true
        val restoreKey = (activityInfo?.get(IosWindowBackend.KEY_RESTORE_KEY) as? String)
            ?: (sessionInfo?.get(IosWindowBackend.KEY_RESTORE_KEY) as? String)
            ?: ""

        val backend = IosRendererSession.ensureWindowManager(
            initialWindow = uiWindow,
            initialSession = willConnectToSession,
        )
        val isMultiWindow = host.table.window?.mode == WindowMode.Windows
        val resolvedNodeId = if (isMultiWindow) {
            val connected = if (nodeId != null || (restoreKey.isNotEmpty() && !firstScene)) {
                backend.sceneConnected(nodeId, restoreKey, willConnectToSession, uiWindow)
            } else {
                val firstId = host.table.roots.firstOrNull {
                    host.table.node(it)?.widget == dev.darkpyonix.composerust.protocol.WidgetKind.Window
                }
                firstId?.let { backend.windowFor(it) }
                    ?: backend.sceneConnected(null, "", willConnectToSession, uiWindow)
            }
            trackedScene = connected
            val assignedId = connected?.nodeId ?: 0
            if (assignedId != 0) {
                willConnectToSession.userInfo = mapOf(
                    IosWindowBackend.KEY_NODE_ID to assignedId,
                    IosWindowBackend.KEY_PROCESS to IosRendererSession.processToken,
                    IosWindowBackend.KEY_RESTORE_KEY to (connected?.restoreKey ?: restoreKey),
                )
            }
            assignedId
        } else {
            0
        }

        uiWindow.rootViewController = IosRendererSession.buildRootViewController(uiWindow, host, resolvedNodeId)
        uiWindow.makeKeyAndVisible()
        sceneWindowHandle = uiWindow
    }

    override fun sceneDidDisconnect(scene: UIScene) {
        trackedScene?.disconnect()
        sceneWindowHandle?.let { IosRendererSession.forgetWindow(it) }
        sceneWindowHandle = null
    }

    override fun sceneDidBecomeActive(scene: UIScene) {
        trackedScene?.focusChanged(true)
    }

    override fun sceneWillResignActive(scene: UIScene) {
        trackedScene?.focusChanged(false)
    }
}

/** The application's zoom level in the user defaults. */
private object UserDefaultsZoomLevel : dev.darkpyonix.composerust.runtime.ZoomLevelStore {
    private const val KEY = "ComposeRustZoomLevel"

    override fun load(): Int? {
        val defaults = platform.Foundation.NSUserDefaults.standardUserDefaults
        if (defaults.objectForKey(KEY) == null) return null
        return dev.darkpyonix.composerust.runtime.clampZoomLevel(defaults.integerForKey(KEY).toInt())
    }

    override fun save(level: Int) {
        platform.Foundation.NSUserDefaults.standardUserDefaults.setInteger(
            dev.darkpyonix.composerust.runtime.clampZoomLevel(level).toLong(),
            KEY,
        )
    }
}
