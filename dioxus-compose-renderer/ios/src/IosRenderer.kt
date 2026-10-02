package dioxus.compose.ui.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.HostConnection
import dioxus.compose.ui.platform.IosHostConnection
import dioxus.compose.runtime.rememberDioxusHost
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow

/**
 * Runs the renderer's Compose application. This is what `dioxus_compose_renderer_run`
 * calls.
 *
 * The desktop renderer opens a window and returns when the window closes. iOS has no such
 * moment: `UIApplicationMain` installs the run loop and never returns, and the system, not
 * the application, decides when the process ends. So on iOS `run` blocks for the lifetime of
 * the process. A Rust `main` that calls `dioxus_compose::launch(app)` gets the behaviour it
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
 * The minimum UIKit application: one window whose root view controller is Compose.
 *
 * There is no storyboard and no Swift, because on iOS the Host owns the process the way it
 * does on desktop. A Host that wants to embed the renderer in an existing app instead can
 * call `DioxusContent` from its own `ComposeUIViewController`, but that is not the M5 path.
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
        val window = UIWindow(frame = UIScreen.mainScreen.bounds)
        val content = ComposeUIViewController {
            DioxusContent(
                rememberDioxusHost(remember { hostConnectionFactory() }),
                // The whole screen. Where the safe area is is the renderer's to decide:
                // a bar that opens the tree grows up into the status bar and a navigation
                // grows down into the home indicator, the way every application on the
                // platform does, and whatever is left over is kept off the page there.
                // Padding everything here instead left the system's own background
                // showing through both strips.
                Modifier.fillMaxSize(),
            )
        }
        // Where the system draws its own chrome as Liquid Glass, the container that can
        // hold it is built now, before anything has been drawn, and the interpreter is
        // told where to find it. It stands empty and its bar stays hidden until a
        // navigation reaches the tree, so an application that never declares one sees no
        // difference.
        //
        // Below that, the window holds the Compose controller and nothing else, exactly as
        // it did before: there is no system glass down there to take, so a second shell
        // would buy nothing and would put an untested layout in front of the screen.
        window.rootViewController = rendererRootViewController(content)
        window.makeKeyAndVisible()
        mainWindow = window
        return true
    }
}
