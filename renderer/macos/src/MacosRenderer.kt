// Asking the platform to draw the text context menu is behind a flag while it is being
// finished upstream. It is read here and nowhere else, and pinned to Compose 1.11.1: a
// version that settles the question removes the flag and fails this file rather than
// quietly going back to a menu Compose drew.
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.ComposeFoundationFlags
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.WindowCaption
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.ui.node.Asset
import kotlinx.coroutines.delay
import org.thisisthepy.compose.window.DockIcon
import org.thisisthepy.compose.window.SystemDarkMonitor
import org.thisisthepy.compose.window.contentMinimum
import org.thisisthepy.compose.window.macos.MacosClipboard
import org.thisisthepy.compose.window.macos.MacosClipboardManager
import org.thisisthepy.compose.window.macos.MacosWindow
import org.thisisthepy.compose.window.macos.observeSystemAppearance
import org.thisisthepy.compose.window.macos.systemIsDark
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationWillTerminateNotification
import platform.AppKit.NSWindow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import dev.darkpyonix.composerust.protocol.TitleBar
import dev.darkpyonix.composerust.design.resolveTheme
import dev.darkpyonix.composerust.design.HostPlatform

/**
 * Runs the renderer's Compose application. This is what `compose_rust_renderer_run`
 * calls.
 *
 * The window is this renderer's own. Compose opens one for this platform and it draws
 * correctly, but nothing it opens says what is in it: a reader is given the three title
 * bar buttons and the title, and every control the application drew is invisible to it.
 *
 * Returns when the application stops, which is when the window closes.
 */
internal fun runRenderer(connection: () -> HostConnection): Int {
    // Asked for by a build that proves the executable shapes and wraps Korean with no ICU data
    // file beside it. A failure stops here, before a window, so the build sees it.
    if (runTextSelfCheckIfAsked() == false) return RendererApi.RUN_FAILED
    val application = NSApplication.sharedApplication()
    // An executable that is not inside a bundle is not, by default, something the system
    // will put in front of anything else: it has no place in the dock and cannot take the
    // keyboard. Said before the window is made, because what kind of application this is
    // decides what its windows are allowed to be sent.
    application.setActivationPolicy(
        NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular,
    )

    // Started before there is a window, because what the window should look like is in
    // the first batch and a window cannot be told afterwards: how big it is and what it
    // is called are settled when it is made.
    // The menu a selection offers, drawn by the system rather than by Compose.
    //
    // Off, and that is the opposite of what it was. The new path was turned on because it
    // is the one that asks the platform for a menu and this platform answers, through the
    // `NSMenu` the text toolbar builds. It does not ask on this platform: what it draws is
    // a menu of its own, at the window's top left corner rather than under the pointer,
    // with every item in it dead. The old path goes through the toolbar, which is ours.
    //
    // To be turned back on when the new path reaches this platform, and the way to tell is
    // that the menu comes up where the pointer is.
    ComposeFoundationFlags.isNewContextMenuEnabled = false

    declareWindowBackdrop()
    // The notification centre, before the Host exists: the Host's first batch may already
    // post one. A press on the body brings this application to the front and its window
    // back from the Dock, which is what the platform does for an application it launches.
    Notifications.platform = AppleNotifications(
        withdrawAtExit = true,
        bringToFront = {
            application.activateIgnoringOtherApps(true)
            application.windows.forEach { (it as? NSWindow)?.deminiaturize(null) }
        },
    )
    val host = ComposeRustHost(connection())
    host.start()
    // Quitting ends the process from inside the run loop, so `run` below never returns to
    // say so. What this application posted is taken back when the platform says it is
    // about to go: a notification left behind would point at work no process knows about.
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = NSApplicationWillTerminateNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> host.table.notifications.shutdown() }
    // What the application asked for. A window that said nothing is listed under whatever
    // this renderer happens to be called, which is the library's name and not any
    // application's, and a measurement of zero means it did not ask.
    val asked = host.table.window
    // What the two title bar modes are worth here. Asked of the design system rather than
    // written down, and asked before the window is made because both answers are things a
    // window is built with rather than things it is told later.
    //
    // The theme is resolved for this platform at the narrowest class: neither answer
    // depends on how wide the window is, and the window does not exist yet to be measured.
    // Whether the system is dark, now and as it changes. Held here so the first answer is the
    // real one and the window is not drawn light and corrected a moment later.
    var requestFrame: () -> Unit = {}
    val dark = mutableStateOf(systemIsDark())
    val appearance = SystemDarkMonitor(
        read = ::systemIsDark,
        requestFrame = { requestFrame() },
        onChange = { dark.value = it },
    )
    observeSystemAppearance(appearance::refresh)
    val dressing = resolveTheme(
        theme = host.table.theme,
        platform = HostPlatform.MacOs,
        systemDark = dark.value,
    ).let { it.rules.caption(it, asked?.titleBar ?: TitleBar.Normal) }
    val clipboard = MacosClipboard()
    @Suppress("DEPRECATION")
    val clipboardManager = MacosClipboardManager()
    val window = MacosWindow(
        name = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else 520,
        height = if (asked != null && asked.height > 0) asked.height else 360,
        buttonInset = dressing.platformButtonInset,
        cornerRadius = dressing.windowCornerRadius,
        minimumSize = contentMinimum(asked?.minWidth ?: 0, asked?.minHeight ?: 0),
        clipboardHasText = { clipboard.hasText() },
    )
    requestFrame = window::requestFrame
    val dockIcon = DockIcon(
        lookup = { id -> (host.table.assets.asset(id) as? Asset.Raster)?.bitmap },
        apply = { picture -> picture.toNSImage()?.let { application.applicationIconImage = it } },
    )
    val iconAsset = asked?.icon ?: 0
    @Suppress("DEPRECATION")
    window.setContent {
        CompositionLocalProvider(
            LocalSystemDarkObserver provides { dark.value },
            LocalClipboard provides clipboard,
            LocalClipboardManager provides clipboardManager,
        ) {
            // The asset arrives a little after the first batch names it, so it is looked for
            // until it is there.
            if (iconAsset != 0) {
                LaunchedEffect(Unit) {
                    while (!dockIcon.tryApply(iconAsset)) delay(100)
                }
            }
            val strip = window.caption.value
            ComposeRustContent(
                host,
                caption = WindowCaption(
                    height = strip.height,
                    buttonsWidth = strip.buttonsWidth,
                    buttonsAtStart = strip.buttonsAtStart,
                    insetTop = strip.insetTop,
                ),
            )
        }
    }

    application.activateIgnoringOtherApps(true)
    application.run()
    return 0
}

/**
 * Tells the design systems that a page or a piece of chrome drawn with alpha has something
 * behind it to show.
 *
 * True because the fork's `MacosWindow` puts the system's own material behind everything it draws.
 * Said here rather than read off the operating system's name: that name is true of every
 * build for this platform and describes only the ones that put a material there.
 *
 * Its own function so that the answer this renderer gives can be exercised. What went
 * wrong before was not the answer but that nothing checked it against what the window had
 * actually done, and a claim of a backdrop that is not there costs the whole look of the
 * window: chrome takes the recipe meant to sit on the desktop and the page is made
 * translucent for a desktop that never arrives, so both come out grey.
 */
internal fun declareWindowBackdrop() {
    dev.darkpyonix.composerust.runtime.platformBacksWindowWithMaterial = { true }
}
