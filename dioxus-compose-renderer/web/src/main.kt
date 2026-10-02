package dioxus.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.HostConnection
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.tooling.m0DemoHost
import dioxus.compose.ui.platform.WebHostConnection

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
    val connection: HostConnection = WebHostConnection.install() ?: m0DemoHost()
    ComposeViewport {
        DioxusContent(rememberDioxusHost(remember { connection }), Modifier.fillMaxSize())
    }
}
