@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.darkpyonix.composerust

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.HostConnection
import dev.darkpyonix.composerust.runtime.clampZoomLevel
import dev.darkpyonix.composerust.runtime.rememberComposeRustHost
import dev.darkpyonix.composerust.tooling.m0DemoHost
import dev.darkpyonix.composerust.ui.platform.FrameRequests
import dev.darkpyonix.composerust.ui.platform.WebHostConnection
import dev.darkpyonix.composerust.ui.platform.WebWindowBackend
import dev.darkpyonix.composerust.ui.platform.WindowManager
import dev.darkpyonix.composerust.ui.platform.serveFrameRequests
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

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
 * `install` has both halves in hand. It finishes asynchronously, because a browser refuses
 * to instantiate a module over 8MB synchronously on its main thread, so the viewport is
 * composed once it has. Nothing calls the Host before then: its table slots are empty.
 *
 * A page served without a Host beside it falls back to the scripted [m0DemoHost], which is
 * the same development shell the JVM target has: the renderer has to be workable without
 * the other side being built.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Before the Host: its first batch may already post a notification.
    dev.darkpyonix.composerust.ui.platform.Notifications.platform = dev.darkpyonix.composerust.ui.platform.WebNotifications()
    // The browser reads its clipboard only asynchronously, after asking the user.
    dev.darkpyonix.composerust.ui.platform.ClipboardReads.platform = { clipboard ->
        dev.darkpyonix.composerust.ui.platform.readWebClipboardText(clipboard)
    }
    // The user's files: the browser's pickers, and the handles they give, behind the same
    // dialog and file requests every platform answers. The event buffer grows as far as the
    // largest one the Host lends, so an answer longer than that is refused whole instead of
    // cut.
    val files = dev.darkpyonix.composerust.ui.platform.WebFiles()
    var appIdentifier: String? = readPageAppIdentifier().takeIf { it.isNotBlank() }
    files.pickerId = appIdentifier
    dev.darkpyonix.composerust.ui.platform.FileDialogs.platform = files
    dev.darkpyonix.composerust.ui.platform.FileCalls.platform = files
    dev.darkpyonix.composerust.ui.platform.FileDialogs.maxEventBytes =
        dev.darkpyonix.composerust.ui.platform.EVENT_BUFFER_MAX_BYTES
    // The application's storage is the browser's here, so this side carries the Host's
    // storage requests out, in IndexedDB. An answer travels in the event buffer, so one
    // larger than the largest buffer the Host lends is reported as too large rather than
    // cut short. Observing the store name also learns the Host's application identifier so
    // file pickers and zoom persistence scope by application on the same origin.
    dev.darkpyonix.composerust.ui.platform.Storage.platform =
        dev.darkpyonix.composerust.ui.platform.WebStorage { observed ->
            appIdentifier = observed
            files.pickerId = observed
        }
    dev.darkpyonix.composerust.ui.platform.Storage.maxAnswerBytes =
        dev.darkpyonix.composerust.ui.platform.EVENT_BUFFER_MAX_BYTES - IO_ANSWER_RECORD_BYTES
    // The browser's own answer to the media query a page would ask.
    dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting = {
        if (prefersReducedMotion()) {
            dev.darkpyonix.composerust.protocol.ReducedMotion.On
        } else {
            dev.darkpyonix.composerust.protocol.ReducedMotion.Off
        }
    }
    // Where the application's zoom level is kept between visits: the page's local storage,
    // scoped per application identifier (or page path when none was declared) so apps on the
    // same origin do not overwrite each other's zoom level.
    dev.darkpyonix.composerust.runtime.platformZoomLevelStore = {
        LocalStorageZoomLevel { webZoomStorageKey(appIdentifier, currentPathname()) }
    }
    MainScope().launch {
        // The default family's real faces, fetched while the Host is instantiated, so the
        // first frame is already set in them: a canvas has none of the machine's fonts.
        val fonts = async { dev.darkpyonix.composerust.ui.platform.installBundledFonts() }
        val connection: HostConnection = WebHostConnection.install() ?: m0DemoHost()
        fonts.await()
        ComposeViewport {
            val host = rememberComposeRustHost(remember { connection })
            val windowBackend = remember(host) { WebWindowBackend() }
            val windowManager = remember(host, windowBackend) {
                WindowManager(host, windowBackend, onExit = {})
            }
            DisposableEffect(windowManager) {
                windowManager.attach()
                onDispose { windowManager.shutdown() }
            }
            val multiWindow = host.table.window?.mode == WindowMode.Windows
            if (multiWindow) {
                // Multi-window mode renders only the first Window node in the page; any
                // additional Window node receives WindowUnavailable from WindowManager.
                // Because ComposeRustContent skips its own frame loop when windowNodeId != 0,
                // this single effect drives frame requests for the process.
                LaunchedEffect(host) {
                    serveFrameRequests(
                        FrameRequests.global.counter,
                        awaitFrame = { onFrame -> withFrameNanos(onFrame) },
                        serve = host::renderFrame,
                        alreadyServed = FrameRequests.global.counter.value,
                    )
                }
                val activeNodeId = windowBackend.activeNodeId
                if (activeNodeId != 0) {
                    ComposeRustContent(host, Modifier.fillMaxSize(), windowNodeId = activeNodeId)
                } else {
                    Box(Modifier.fillMaxSize())
                }
            } else {
                ComposeRustContent(host, Modifier.fillMaxSize())
            }
        }
    }
}

/** An `IoAnswer` record before the bytes it carries: the header, two words, one reference. */
private const val IO_ANSWER_RECORD_BYTES = 32

/** `matchMedia('(prefers-reduced-motion: reduce)').matches`, or false where it cannot ask. */
private fun prefersReducedMotion(): Boolean =
    js("typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches")

/**
 * The `localStorage` key for an application's zoom level.
 *
 * Uses [appIdentifier] when known, otherwise normalizes [pathname] so multiple applications
 * hosted under different paths on the same origin keep separate zoom levels.
 */
internal fun webZoomStorageKey(appIdentifier: String?, pathname: String): String {
    val explicit = appIdentifier?.trim().orEmpty()
    if (explicit.isNotEmpty()) return "$ZOOM_KEY_PREFIX:$explicit"
    val path = pathname.trim().trimEnd('/').ifEmpty { "/" }
    return "$ZOOM_KEY_PREFIX:$path"
}

private const val ZOOM_KEY_PREFIX = "compose-rust.zoom-level"

/** The application's zoom level in the page's local storage, keyed per application. */
private class LocalStorageZoomLevel(
    private val keyProvider: () -> String,
) : dev.darkpyonix.composerust.runtime.ZoomLevelStore {
    override fun load(): Int? = loadZoomLevel(keyProvider()).takeIf { it != NO_LEVEL }?.let { clampZoomLevel(it) }

    override fun save(level: Int) = saveZoomLevel(keyProvider(), clampZoomLevel(level))
}

/** What [loadZoomLevel] answers where nothing is saved, outside any level there can be. */
private const val NO_LEVEL = -1000

private fun readPageAppIdentifier(): String = js(
    """(() => {
        try {
            if (typeof globalThis.__composeRustAppId === 'string' && globalThis.__composeRustAppId) {
                return globalThis.__composeRustAppId;
            }
            if (typeof document !== 'undefined') {
                const meta = document.querySelector('meta[name="compose-rust-app-id"]');
                if (meta && typeof meta.content === 'string') return meta.content;
            }
        } catch (e) {
        }
        return '';
    })()""",
)

private fun currentPathname(): String = js(
    """(() => {
        try {
            return (typeof window !== 'undefined' && window.location && window.location.pathname) || '/';
        } catch (e) {
            return '/';
        }
    })()""",
)

// Storage a browser refuses (a private window, a blocked origin) is no saved level, and a
// level that cannot be saved is still the level on screen.
@Suppress("UNUSED_PARAMETER")
private fun loadZoomLevel(key: String): Int = js(
    """(() => {
        try {
            const saved = window.localStorage.getItem(key);
            const level = saved === null ? NaN : parseInt(saved, 10);
            return Number.isNaN(level) ? -1000 : level;
        } catch (e) {
            return -1000;
        }
    })()""",
)

@Suppress("UNUSED_PARAMETER")
private fun saveZoomLevel(key: String, level: Int): Unit = js(
    """{
        try {
            window.localStorage.setItem(key, String(level));
        } catch (e) {
        }
    }""",
)
