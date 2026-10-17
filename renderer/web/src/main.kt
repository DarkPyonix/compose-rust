package dev.darkpyonix.composerust

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.m0DemoHost
import dev.darkpyonix.composerust.ui.platform.WebHostConnection

/**
 * The web renderer's entry point.
 *
 * `ComposeViewport` is the browser's counterpart of the desktop `Window` and of the iOS
 * `ComposeUIViewController`: it takes over the page's canvas and composes into it. The
 * screen is drawn entirely from the mutation batches the Host streams, exactly as it is on
 * every other target, because the interpreter under it is the same source.
 *
 * The Host is installed here rather than on the page, because this is the first moment at
 * which both modules exist. The page compiled the Host's module before this one was
 * evaluated, and instantiating this one is what created the memory the Host imports, so
 * `install` has both halves in hand and is synchronous.
 *
 * A page served without a Host beside it falls back to the scripted [m0DemoHost], which is
 * the same development shell the JVM target has: the renderer has to be workable without
 * the other side being built.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Before the Host: its first batch may already post a notification.
    dev.darkpyonix.composerust.ui.platform.Notifications.platform = dev.darkpyonix.composerust.ui.platform.WebNotifications()
    // The browser's own answer to the media query a page would ask.
    dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting = {
        if (prefersReducedMotion()) {
            dev.darkpyonix.composerust.protocol.ReducedMotion.On
        } else {
            dev.darkpyonix.composerust.protocol.ReducedMotion.Off
        }
    }
    val connection: HostConnection = WebHostConnection.install() ?: m0DemoHost()
    ComposeViewport {
        ComposeRustContent(rememberComposeRustHost(remember { connection }), Modifier.fillMaxSize())
    }
}

/** `matchMedia('(prefers-reduced-motion: reduce)').matches`, or false where it cannot ask. */
private fun prefersReducedMotion(): Boolean =
    js("typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches")
