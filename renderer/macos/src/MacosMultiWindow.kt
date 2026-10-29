@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.runtime.ComposeRustContent
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import dev.darkpyonix.composerust.runtime.LocalSystemDarkObserver
import dev.darkpyonix.composerust.runtime.LocalWindowNodeId
import dev.darkpyonix.composerust.runtime.WindowCaption
import dev.darkpyonix.composerust.ui.node.Asset
import java.lang.System
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.thisisthepy.compose.window.DockIcon
import org.thisisthepy.compose.window.SystemDarkMonitor
import org.thisisthepy.compose.window.macos.MacosClipboard
import org.thisisthepy.compose.window.macos.MacosClipboardManager
import org.thisisthepy.compose.window.macos.MacosWindow
import org.thisisthepy.compose.window.macos.observeSystemAppearance
import org.thisisthepy.compose.window.macos.systemIsDark
import platform.AppKit.NSApplication

/**
 * The multi-window run of the macOS renderer (FR-53, `SetWindow` mode `Windows`).
 *
 * The single window run in [runRenderer] is not touched by any of this. Here the Host's
 * `Window` nodes are the windows: a [WindowManager] opens one `MacosWindow` per node through
 * [MacosWindowBackend], each with its own scene drawing that node from the shared node table,
 * and one driver serves the Host's frame requests for all of them ([serveWindowFrames]).
 * `NSApplication.run()` owns the loop. It returns when the manager ends it, which is the last
 * window going under `LastWindowClosed.Exit`, and the renderer then returns 0.
 */
@OptIn(DelicateCoroutinesApi::class)
@Suppress("DEPRECATION")
internal fun runWindows(application: NSApplication, host: ComposeRustHost, asked: Window?): Int {
    // Whether the system is dark, now and as it changes, for every window's scene.
    val dark = mutableStateOf(systemIsDark())
    var backend: MacosWindowBackend? = null
    val appearance = SystemDarkMonitor(
        read = ::systemIsDark,
        requestFrame = { backend?.requestFrameAll() },
        onChange = { dark.value = it },
    )
    observeSystemAppearance(appearance::refresh)
    val clipboard = MacosClipboard()
    val clipboardManager = MacosClipboardManager()
    val dockIcon = DockIcon(
        lookup = { id -> (host.table.assets.asset(id) as? Asset.Raster)?.bitmap },
        apply = { picture -> picture.toNSImage()?.let { application.applicationIconImage = it } },
    )
    val iconAsset = asked?.icon ?: 0
    val cursorBlink = System.getenv("DXC_CURSOR_BLINK")?.let { it != "0" }
        ?: (System.getenv("DXC_IDLE_SOAK_SECONDS") == null)

    val scene: MacosSceneContent = { config, window, frames ->
        val nodeId = config.nodeId
        val body: @Composable () -> Unit = {
            MacosWindowScene(
                host, nodeId, window, frames, dark, clipboard, clipboardManager, cursorBlink, dockIcon, iconAsset,
            )
        }
        body
    }
    val windows = MacosWindowBackend(scene)
    backend = windows
    val manager = WindowManager(host, windows, onExit = { stopApplicationLoop() })
    manager.attach()
    // A batch is one snapshot for every window: each is drawn again after it is applied, not
    // only the one whose frame carried it.
    host.afterBatch = {
        manager.sync()
        windows.requestFrameAll()
    }
    // The one frame driver of the process, on the main queue where the run loop serves it.
    // The scenes run their own work only inside a frame, so a request a Host worker makes is
    // answered by asking the windows for one (PlatformWindow.awaitFrame).
    GlobalScope.launch(Dispatchers.Main) {
        val requests = FrameRequests.global
        serveWindowFrames(
            host,
            manager,
            requests,
            alreadyServed = if (host.startedWith === requests) host.requestsBeforeStart else requests.counter.value,
        )
    }

    application.activateIgnoringOtherApps(true)
    application.run()
    host.table.notifications.shutdown()
    return 0
}

/** What one window's scene draws: its `Window` node, and the clock it answers [PlatformWindow.awaitFrame] with. */
@Suppress("DEPRECATION")
@Composable
private fun MacosWindowScene(
    host: ComposeRustHost,
    nodeId: Int,
    window: MacosWindow,
    frames: MacosSceneFrames,
    dark: MutableState<Boolean>,
    clipboard: MacosClipboard,
    clipboardManager: MacosClipboardManager,
    cursorBlink: Boolean,
    dockIcon: DockIcon<*>,
    iconAsset: Int,
) {
    CompositionLocalProvider(
        LocalWindowNodeId provides nodeId,
        LocalCursorBlinkEnabled provides cursorBlink,
        LocalSystemDarkObserver provides { dark.value },
        LocalClipboard provides clipboard,
        LocalClipboardManager provides clipboardManager,
    ) {
        // The window's frame clock, awake only while something waits on it: an idle window asks
        // for no frames.
        LaunchedEffect(frames) {
            for (wake in frames.wake) {
                withFrameNanos { nanos -> frames.draw(nanos) }
            }
        }
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
                cornerRadius = strip.cornerRadius,
            ),
        )
    }
}
