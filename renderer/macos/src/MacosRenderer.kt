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
import org.thisisthepy.compose.window.macos.MacosWindow
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSApplicationWillTerminateNotification
import platform.AppKit.NSWindow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import dev.darkpyonix.composerust.protocol.Chrome
import dev.darkpyonix.composerust.protocol.TitleBar

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
    // On, because the new path is the one with a place to say what the menu is: a text
    // field or selection asks `LocalTextContextMenuDropdownProvider`, and the Compose this
    // links answers it on this platform with an `NSMenu` holding Compose's own items. The
    // old path has no such place here and draws a menu of its own, which came up beside
    // the one the window put up, two menus for one click. The window puts up none now,
    // so the one that appears is the system's, the same one the native image shows.
    ComposeFoundationFlags.isNewContextMenuEnabled = true

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
    // How the title bar is built, by the same decision the native image's window takes, so
    // both macOS renderers have the same corners and start the content at the same height.
    val chrome = MacosWindowChrome.of(
        chrome = asked?.chrome ?: Chrome.Modern,
        titleBar = asked?.titleBar ?: TitleBar.Normal,
    )
    val window = MacosWindow(
        name = asked?.title?.takeIf { it.isNotEmpty() } ?: "compose-rust",
        width = if (asked != null && asked.width > 0) asked.width else 520,
        height = if (asked != null && asked.height > 0) asked.height else 360,
        chrome = org.thisisthepy.compose.window.macos.MacosWindowChrome(
            fullSizeContentView = chrome.fullSizeContentView,
            titlebarAppearsTransparent = chrome.titlebarAppearsTransparent,
            titleHidden = chrome.titleHidden,
            unifiedToolbar = chrome.unifiedToolbar,
        ),
    )
    window.setContent {
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
