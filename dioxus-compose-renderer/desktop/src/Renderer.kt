package dioxus.compose.ui.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toAwtImage
import dioxus.compose.ui.node.Asset
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import dioxus.compose.runtime.DioxusContent
import dioxus.compose.runtime.asksForWindowMaterial
import dioxus.compose.runtime.HostConnection
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import dioxus.compose.protocol.Chrome
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.runtime.rememberStartedDioxusHost
import dioxus.compose.runtime.rememberDioxusHost
import dioxus.compose.runtime.LocalSystemDarkObserver
import dioxus.compose.runtime.LocalWindowActions

/**
 * Runs the renderer's Compose application on the calling thread until its window closes.
 *
 * The window draws the interpreted Host tree and nothing else: every widget on screen comes
 * from the mutation batches `connection` streams.
 *
 * `exitProcessOnExit` is off because the process belongs to the Rust host: closing the
 * window must return control to it, not terminate it.
 */
/**
 * Runs [work] on AWT's event thread and waits for it.
 *
 * Straight through when already there, because invokeAndWait from the event thread
 * deadlocks rather than reentering.
 */
private fun onEventThread(work: () -> Unit) {
    if (java.awt.EventQueue.isDispatchThread()) {
        work()
    } else {
        java.awt.EventQueue.invokeAndWait(work)
    }
}

internal fun runRenderer(
    autoExitMillis: Long? = null,
    connection: () -> HostConnection,
) {
    // The Host is started before there is a window, because what the window should look
    // like is in the first batch and a window cannot be told afterwards: whether it is
    // decorated is settled when it is created. `dioxus_compose_host_init` answers with
    // that batch, so asking early costs nothing and needs no argument on any boundary
    // function.
    // Before the first frame, because every piece of text drawn after this reads it.
    dioxus.compose.design.installPlatformUiFamily()
    dioxus.compose.ui.installFileDrop()
    dioxus.compose.ui.installReducedMotion()
    val host = DioxusHost(connection())
    // Started on the thread the window will be driven from, which is not the thread this
    // function was called on.
    //
    // The boundary is a direct call on one thread and the Host keeps its state there,
    // in storage that belongs to that thread and to no other. This entry point runs on the thread the C shim created for the renderer, and
    // Compose then drives the window from AWT's event thread, so a Host started here had
    // no state anywhere the application would later call it from: the first batch drew,
    // and after that every click, key and report came back refused with the status that
    // means "not initialised on this thread". A calculator drew and could not count.
    //
    // invokeAndWait rather than invokeLater, because what the window should look like is
    // in the batch this produces and the window is built from it on the next line.
    onEventThread { host.start() }
    val asked = host.table.window
    val chrome = when (asked?.chrome) {
        Chrome.System -> WindowChrome.System
        // A Host that sent nothing is a Host from an older schema, and the handshake
        // would already have refused that, so this is the ordinary modern case.
        else -> WindowChrome.Modern
    }
    runRendererWithHost(host, asked, chrome, autoExitMillis)
}

/** How long the surface is asked to stay transparent while the window settles. */
private const val BACKDROP_ATTEMPTS = 20
private const val BACKDROP_RETRY_MILLIS = 100L

private fun runRendererWithHost(
    host: DioxusHost,
    asked: dioxus.compose.protocol.Window?,
    chrome: WindowChrome,
    autoExitMillis: Long?,
) = application(exitProcessOnExit = false) {
    // Undecorated only where the system will not let the window keep its frame and give
    // up the bar. macOS makes the real bar transparent; Windows keeps the frame and hands
    // the caption strip to the client area. Linux has neither, so the whole decoration
    // goes and every part of it is drawn here, including the resize edges.
    // A window that wants to show what is behind it has to be able to draw
    // transparently, and the toolkit allows that only for a window it was asked to leave
    // undecorated. What is given up in the asking is given up on this side alone: the
    // window is a real one, and the shell puts the platform's own frame back on it, so
    // the title bar and its three buttons are still the system's and not ours.
    val wantsBackdrop = host.table.asksForWindowMaterial(host.roots)
    val undecorated =
        (chrome == WindowChrome.Modern && !platformKeepsSystemFrame()) || wantsBackdrop
    // A measurement of zero means the application did not ask, so the choice stays the
    // window's own rather than becoming a window of no size.
    val state = if (asked != null && asked.width > 0 && asked.height > 0) {
        rememberWindowState(size = DpSize(asked.width.dp, asked.height.dp))
    } else {
        rememberWindowState()
    }
    Window(
        onCloseRequest = ::exitApplication,
        // The application's own name where it gave one. A desktop lists windows by their
        // title, and every window this ever opened was listed as the renderer's name,
        // which is the library's name and not any application's.
        title = asked?.title?.takeIf { it.isNotEmpty() } ?: "DioxusCompose",
        undecorated = undecorated,
        transparent = wantsBackdrop,
        resizable = asked?.resizable ?: true,
        state = state,
    ) {
        // AWT reads the macOS client properties when the peer is realised, so this runs
        // once the window exists rather than as a constructor argument.
        LaunchedEffect(chrome) { applyWindowChrome(window, chrome) }

        // The surface clears itself opaque unless told otherwise, and a window that never
        // asked keeps that, because it is the cheaper of the two.
        //
        // Said again for a moment rather than once. Giving the window its frame back
        // happens on the platform's side, a little after this, and it rebuilds the views
        // the surface hangs from; a switch pressed before that is a switch on something
        // that is about to be replaced. A second of it is enough and then it stops, so
        // nothing here runs for the life of the window.
        LaunchedEffect(wantsBackdrop) {
            if (!wantsBackdrop) return@LaunchedEffect
            repeat(BACKDROP_ATTEMPTS) {
                dioxus.compose.ui.letWindowShowItsBackdrop(window)
                kotlinx.coroutines.delay(BACKDROP_RETRY_MILLIS)
            }
        }

        // What the window believes the display's scale is, on request.
        //
        // Declaring that this process understands scaling is one half; the toolkit picking
        // that up is the other, and the difference between them is invisible from a
        // screenshot. A blurred window and a window drawn sharp at the wrong size look
        // alike in a description, and "it still looks soft" is not something the next
        // change can be aimed at.
        //
        // Off unless asked for, because this is a diagnostic and not a log line.
        // Which typeface the toolkit's default actually resolves to here. Asked rather
        // than assumed: a design system that names no face gets whatever this is, and
        // whether that is the platform's own UI font decides whether the text reads as a
        // native control or as something drawn.
        if (System.getenv("DXC_REPORT_FONT") != null) {
            LaunchedEffect(Unit) {
                val manager = org.jetbrains.skia.FontMgr.default
                val forLetter = manager.matchFamilyStyleCharacter(
                    null,
                    org.jetbrains.skia.FontStyle.NORMAL,
                    null,
                    'A'.code,
                )
                // What this window actually writes in, next to what the toolkit would
                // have chosen on its own.
                dioxus.compose.design.platformUiFamily
                System.err.println(
                    "dioxus-compose: using ${dioxus.compose.design.platformUiFamilyName}" +
                        ", toolkit default would be ${forLetter?.familyName}",
                )
            }
        }
        if (System.getenv("DXC_REPORT_SCALE") != null) {
            LaunchedEffect(Unit) {
                val transform = window.graphicsConfiguration?.defaultTransform
                val density = window.graphicsConfiguration?.device?.displayMode
                System.err.println(
                    "dioxus-compose: display scale x=${transform?.scaleX} y=${transform?.scaleY}" +
                        ", mode ${density?.width}x${density?.height}" +
                        ", window ${window.width}x${window.height}",
                )
            }
        }

        // The application's own picture, once the asset it named has arrived.
        //
        // The two come from the same batch but not at the same moment: the window is stood
        // up from the batch before the batch is applied, so the id is known here first and
        // the bitmap a little later. The cache is a snapshot state map, so reading it in
        // composition subscribes to it and the effect runs again when the picture lands.
        //
        // Nothing happens where the application named nothing, which leaves the toolkit's
        // own icon. Every window this project opened wore that one until now, and on
        // Windows it is visible in the list Task Manager draws under a process.
        //
        // Handed to WindowIcon rather than set on this window, because this window is not
        // the only one. A dialog or a popup that will not fit inside its parent becomes a
        // platform window of its own, and one set here would leave those wearing the cup.
        val iconId = asked?.icon ?: 0
        val icon = if (iconId == 0) {
            null
        } else {
            (host.table.assets.asset(iconId) as? Asset.Raster)?.bitmap
        }
        LaunchedEffect(icon) {
            WindowIcon.use(icon?.toAwtImage())
        }

        // The tracing agent writes its output only on a clean shutdown, so unattended
        // metadata collection needs the window to close by itself.
        autoExitMillis?.let { timeout ->
            LaunchedEffect(Unit) {
                delay(timeout)
                exitApplication()
            }
        }
        // Compose's own reading of the system appearance is taken once on this platform, so
        // the window is given one that keeps looking. It is provided here, around this
        // window's content, rather than stored anywhere a later composition could inherit
        // it: the observer polls in a loop that never ends, which a window wants and a
        // test clock cannot survive.
        CompositionLocalProvider(
            LocalSystemDarkObserver provides { rememberSystemDark().value },
            // Only where we draw the caption ourselves. macOS keeps the system's own
            // traffic lights, and a second set drawn beside them would be two sets of
            // buttons on one window.
            LocalWindowActions provides windowActions(window, chrome),
        ) {
            val caption = windowCaption(window, chrome)
            Box(Modifier.fillMaxSize()) {
                // A strip across the top of the window that moves it when dragged, drawn
                // before the content rather than over it. Compose hit-tests front to
                // back, so a widget in the caption gets the press and the strip only sees
                // the empty room around it, which is what "drag the caption" has to mean.
                if (caption.height > 0.dp) {
                    WindowDraggableArea(Modifier.fillMaxWidth().height(caption.height)) {}
                }
                DioxusContent(
                    rememberStartedDioxusHost(host),
                    Modifier.fillMaxSize(),
                    // Content runs under the caption on purpose, but a widget sitting
                    // where the window buttons are would leave both unusable. The strip
                    // goes inside the content's own background so the window has one
                    // continuous surface, and a tree that opens with a bar hands it to
                    // the bar instead.
                    caption = caption,
                )
                // Last, so they sit over the content. A window with no system frame has
                // no system resize either, and a desktop window that cannot be resized by
                // its edges is not one. macOS never gets them: it keeps its real title
                // bar and the system does this.
                if (undecorated && (asked?.resizable ?: true)) {
                    WindowResizeEdges(
                        window,
                        minWidth = asked?.minWidth?.takeIf { it > 0 } ?: MIN_WINDOW_SIDE,
                        minHeight = asked?.minHeight?.takeIf { it > 0 } ?: MIN_WINDOW_SIDE,
                    )
                }
            }
        }
    }
}

/**
 * How small a window may get when the application named no minimum.
 *
 * Not zero. A window dragged to nothing is a window nobody can find again, and the
 * platforms that need these grips are the ones with no system frame to stop at.
 */
private const val MIN_WINDOW_SIDE = 240
