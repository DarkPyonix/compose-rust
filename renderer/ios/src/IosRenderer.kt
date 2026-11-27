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
import dev.darkpyonix.composerust.runtime.reduceTransparency
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import platform.Foundation.NSClassFromString
import platform.Foundation.NSItemProvider
import platform.Foundation.NSNotificationCenter
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
 * `UIDragInteraction` delegate for iPadOS tear-off nodes carrying a `TearOffKey`.
 *
 * Supplies a drag item carrying the window `NSUserActivity` so dropping at a screen edge
 * triggers a new `UIWindowScene` carrying the `RestoreKey`.
 */
internal class IosTearOffDragDelegate(
    private val restoreKeyProvider: () -> String?,
) : NSObject(), UIDragInteractionDelegateProtocol {
    override fun dragInteraction(
        interaction: UIDragInteraction,
        itemsForBeginningSession: UIDragSessionProtocol,
    ): List<*> {
        val key = restoreKeyProvider()?.takeIf { it.isNotEmpty() } ?: return emptyList<Any>()
        val activity = IosWindowBackend.tearOffUserActivity(key)
        val item = UIDragItem(itemProvider = NSItemProvider()).apply {
            localObject = activity
        }
        return listOf(item)
    }
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
        installIosBack()
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
            return existing
        }
        val backend = IosWindowBackend(
            initialWindow = initialWindow,
            initialSession = initialSession,
        )
        val manager = WindowManager(currentHost, backend, onExit = {})
        windowBackend = backend
        windowManager = manager
        manager.attach()
        return backend
    }

    fun buildRootViewController(window: UIWindow, host: ComposeRustHost, windowNodeId: Int): UIViewController {
        val nativeViews = IosNativeViewWindow(window)
        val osMenus = IosOsMenuPresenter(window)
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
        val nodeId = (activityInfo?.get(IosWindowBackend.KEY_NODE_ID) as? Number)?.toInt()
            ?: (sessionInfo?.get(IosWindowBackend.KEY_NODE_ID) as? Number)?.toInt()
        val restoreKey = (activityInfo?.get(IosWindowBackend.KEY_RESTORE_KEY) as? String)
            ?: (sessionInfo?.get(IosWindowBackend.KEY_RESTORE_KEY) as? String)
            ?: ""

        val backend = IosRendererSession.ensureWindowManager(
            initialWindow = uiWindow,
            initialSession = willConnectToSession,
        )
        val isMultiWindow = host.table.window?.mode == WindowMode.Windows
        val resolvedNodeId = if (isMultiWindow) {
            val connected = if (nodeId != null || restoreKey.isNotEmpty()) {
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
