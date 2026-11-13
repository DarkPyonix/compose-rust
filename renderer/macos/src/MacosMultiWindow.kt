@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import dev.darkpyonix.composerust.runtime.LocalZoom
import dev.darkpyonix.composerust.runtime.Zoom
import dev.darkpyonix.composerust.runtime.composeZoomShortcut
import dev.darkpyonix.composerust.runtime.zoomedDensity
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import dev.darkpyonix.composerust.protocol.Window
import dev.darkpyonix.composerust.runtime.WindowContent
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
import org.thisisthepy.compose.window.macos.MacosWindow
import org.thisisthepy.compose.window.macos.observeSystemAppearance
import org.thisisthepy.compose.window.macos.systemIsDark
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.WidgetKind
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationWillTerminateNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSUserDefaults

/**
 * The multi-window run of the macOS renderer (`SetWindow` mode `Windows`).
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
    val dockIcon = DockIcon(
        lookup = { id -> (host.table.assets.asset(id) as? Asset.Raster)?.bitmap },
        apply = { picture -> applyDockIcon(picture) },
    )
    val iconAsset = asked?.icon ?: 0
    val cursorBlink = System.getenv("DXC_CURSOR_BLINK")?.let { it != "0" }
        ?: !IdleSoakSetting.requested()

    // A `Window` node's own `Icon` is not drawn on macOS. AppKit gives a window no icon of its
    // own: the title bar of a window that is not a document's shows none, and the Dock and the
    // application switcher show one icon for the application. The application's icon
    // (`SetWindow`'s) is the one that applies, to every window.
    val scene: MacosSceneContent = { config, window, frames, slot ->
        val body: @Composable () -> Unit = {
            MacosWindowScene(
                host, slot, window, frames, dark, cursorBlink, dockIcon, iconAsset, config.chrome == dev.darkpyonix.composerust.protocol.Chrome.FullSize,
            )
        }
        body
    }
    val windows = MacosWindowBackend(scene)
    backend = windows
    // The start report and the parity run the single window has, here for the windows the
    // nodes open: the report for the first one, the made-up input and the idle soak for the one
    // `DXC_SYNTH_WINDOW` names (the first when unset).
    val starts = MultiWindowStarts(MultiWindowStarts.parityWindowFrom(System.getenv("DXC_SYNTH_WINDOW")))
    windows.onOpened = { window ->
        val duties = starts.next()
        if (duties.startReport) reportWindowStart(window)
        if (duties.parityRun) startParityRun(window)
    }
    // A dialog on node 0 has no `Window` node of its own here, so it goes to the window in
    // use, or the first one open.
    (FileDialogs.platform as? MacosFileDialogPlatform)?.rootWindow = {
        keyOrFirstWindow(windows.windows.mapNotNull { (it as? MacosPlatformWindow)?.nativeWindow })
    }
    // A dragged window finds the nodes of the other windows that asked to hear it from where
    // each scene laid them out. The screen is measured in points and a scene in pixels.
    val manager = WindowManager(
        host,
        windows,
        onExit = { stopApplicationLoop() },
        dropTargets = DropTargets.registered(windows::contentScale),
    )
    manager.attach()
    // The windows the application had open when it was last quit come back, when the system
    // is set to keep windows across a quit: each is asked for by its restore key, and the
    // application answers with the node or the window is closed again.
    val restore = WindowRestore(UserDefaultsRestoreKeys, keepsWindows = ::systemKeepsWindows)
    for (key in restore.keysToRequest(openRestoreKeys(host))) windows.requestWindow(key)
    // A quit with windows open is the one that remembers them. The loop ending because the
    // last window went leaves nothing to bring back.
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = NSApplicationWillTerminateNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> restore.remember(openRestoreKeys(host)) }
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
    restore.remember(emptyList())
    host.table.notifications.shutdown()
    return 0
}

/** The restore keys of the `Window` nodes at the root now, in order, without the empty ones. */
internal fun openRestoreKeys(host: ComposeRustHost): List<String> =
    host.table.roots.mapNotNull { id ->
        host.table.node(id)
            ?.takeIf { it.widget == WidgetKind.Window }
            ?.text(PropertyKind.WindowRestoreKey)
            ?.takeIf { it.isNotEmpty() }
    }

/** Where the restore keys of the windows open at a quit are kept until the next start. */
interface RestoreKeyStore {
    fun load(): List<String>

    fun save(keys: List<String>)
}

/**
 * The windows a quit leaves to come back at the next start, by their restore keys.
 *
 * Only keys are kept. What a window showed is the application's to save and to put back when
 * it answers the request, as it is on every platform that restores windows.
 */
internal class WindowRestore(
    private val store: RestoreKeyStore,
    private val keepsWindows: () -> Boolean,
) {
    /**
     * The keys to ask the application for now: the ones the last quit left, less the ones a
     * window the application already opened has. None when the system does not keep windows.
     */
    fun keysToRequest(present: Collection<String>): List<String> {
        if (!keepsWindows()) return emptyList()
        val have = present.toHashSet()
        return store.load().filter { it.isNotEmpty() && it !in have }.distinct()
    }

    fun remember(open: List<String>) {
        store.save(open.filter { it.isNotEmpty() }.distinct())
    }
}

private const val RESTORE_KEYS_DEFAULT = "ComposeRustWindowRestoreKeys"

/** The application's own defaults, which is where AppKit keeps what it restores too. */
internal object UserDefaultsRestoreKeys : RestoreKeyStore {
    override fun load(): List<String> =
        NSUserDefaults.standardUserDefaults.stringArrayForKey(RESTORE_KEYS_DEFAULT)?.filterIsInstance<String>().orEmpty()

    override fun save(keys: List<String>) {
        NSUserDefaults.standardUserDefaults.setObject(keys, forKey = RESTORE_KEYS_DEFAULT)
    }
}

/**
 * Whether the system brings an application's windows back when it starts again: the setting
 * "Close windows when quitting an application" turned off, which is `NSQuitAlwaysKeepsWindows`
 * in the global defaults. That setting is on by default, so by default no window comes back.
 */
internal fun systemKeepsWindows(): Boolean =
    NSUserDefaults.standardUserDefaults.boolForKey("NSQuitAlwaysKeepsWindows")

/**
 * Which of the windows a multi-window run opens carry the start report and the parity run.
 *
 * The report goes with the first window, as the single window prints it for its one. The parity
 * run goes with the [parityWindow]th window opened, counted from 1 in opening order, because a
 * script driving the run knows how many windows the application opens and in what order, and
 * not the ids of their nodes. A window past the ones the run opens is never chosen, and then
 * no parity run starts.
 */
internal class MultiWindowStarts(private val parityWindow: Int) {
    data class Duties(val startReport: Boolean, val parityRun: Boolean)

    private var opened = 0

    /** What the window opened now carries. */
    fun next(): Duties {
        opened++
        return Duties(startReport = opened == 1, parityRun = opened == parityWindow)
    }

    companion object {
        /** The window `DXC_SYNTH_WINDOW` names, or the first for a value that is unset or not a positive number. */
        fun parityWindowFrom(value: String?): Int = value?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
    }
}

/** The lines the smoke runs read, for one window, in the words the single window uses. */
internal fun reportWindowStart(window: MacosWindow) {
    val opened = window.measure()
    WindowStartReport.windowOpened(opened.width, opened.height, opened.scale)
    WindowStartReport.sceneCreated(opened.width, opened.height, opened.scale)
    val previous = window.onFrameDrawn
    window.onFrameDrawn = { width, height ->
        previous?.invoke(width, height)
        WindowStartReport.frameDrawn(width, height)
    }
}

/** What one window's scene draws: its `Window` node, and the clock it answers [PlatformWindow.awaitFrame] with. */
@Suppress("DEPRECATION")
@Composable
private fun MacosWindowScene(
    host: ComposeRustHost,
    slot: MacosWindowSlot,
    window: MacosWindow,
    frames: MacosSceneFrames,
    dark: MutableState<Boolean>,
    cursorBlink: Boolean,
    dockIcon: DockIcon<*>,
    iconAsset: Int,
    fullSize: Boolean,
) {
    // A window the system made waits for the application's node and draws nothing until then.
    if (slot.nodeId == 0) return
    CompositionLocalProvider(
        LocalWindowNodeId provides slot.nodeId,
        LocalCursorBlinkEnabled provides cursorBlink,
        LocalSystemDarkObserver provides { dark.value },
        dev.darkpyonix.composerust.runtime.LocalWindowDrag provides {
            beginMacosWindowDrag(window.window)
        },
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
        MacosWindowZoom(slot.zoom) { zoomKeys ->
            WindowContent(
                host,
                modifier = zoomKeys,
                caption = WindowCaption(
                    height = strip.height,
                    buttonsWidth = strip.buttonsWidth,
                    buttonsAtStart = strip.buttonsAtStart,
                    insetTop = strip.insetTop,
                    cornerRadius = strip.cornerRadius,
                    fullSize = fullSize,
                ),
            )
        }
    }
}

/**
 * Draws [content] at the window's own [zoom]: the scene's density times the level, and the
 * system's text size as the font scale. [content] is handed the modifier that takes the zoom
 * shortcuts (Command with plus, minus or zero) for this window, after the application has had
 * the key. The content reports the zoom under the window's node, which is how a level change
 * on one node reaches the Host as that node's `ZoomChanged` and no other's.
 */
@Composable
fun MacosWindowZoom(zoom: Zoom, content: @Composable (zoomKeys: Modifier) -> Unit) {
    val base = LocalDensity.current
    SideEffect { zoom.followOs(base.fontScale) }
    CompositionLocalProvider(
        LocalDensity provides zoomedDensity(base.density, zoom.app, base.fontScale),
        LocalZoom provides zoom,
    ) {
        content(
            Modifier.onKeyEvent { event ->
                zoom.takeShortcut(composeZoomShortcut(event, apple = true), consumedByApplication = false)
            },
        )
    }
}
