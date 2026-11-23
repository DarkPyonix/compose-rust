package dev.darkpyonix.composerust.ui.platform

import android.app.Activity
import android.os.Build
import dev.darkpyonix.composerust.foundation.NativeViewPlatform
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The process-wide Host.
 *
 * Android recreates an Activity for a configuration change but not the process, and the
 * VirtualDom is kept for the life of the process. Holding the interpreted tree here, and
 * not in the composition, is what makes a rotation cost nothing: the new Activity composes
 * the same node table again, with no boundary call and no state lost.
 *
 * Everything here runs on the UI thread, which is where the VirtualDom lives.
 */
object ComposeRustRuntime {
    /**
     * The application's cdylib, once it has been named.
     *
     * It is the Rust side of the app: it links this crate, declares its root component
     * through `compose_rust::android_main!`, and carries the generated JNI shims and
     * `JNI_OnLoad` with it. Its file name is the application's to choose, which is why it
     * is not a constant here: an application built by the Dioxus CLI gets one name, a
     * Gradle project put together by hand another.
     */
    private var library: String? = null

    private val connection = AndroidHostConnection()
    private var host: ComposeRustHost? = null
    private var windowBackend: AndroidWindowBackend? = null
    private var windowManager: WindowManager? = null
    private var attachedActivities = 0

    /**
     * This process' token. A window Activity carries it with its node id, in its intent and in
     * its saved state, and a node id carried with another process' token is not trusted: that
     * process' ids name other nodes here, or none.
     */
    val processToken: Long = Random.nextLong().let { if (it == 0L) 1L else it }

    /**
     * The application's files directory, set by [installStorageLocation] before the Host is
     * started, and handed to the Host once when it is.
     */
    internal var storageLocation: String? = null

    /**
     * Loads the application's cdylib. The generated Activity calls this before anything
     * else, with the name the build gave it.
     *
     * Loading more than once is the ordinary case rather than a mistake: an Activity is
     * recreated for a configuration change and the process is not. The second call is the
     * runtime's own no-op.
     */
    fun load(name: String) {
        try {
            System.loadLibrary(name)
        } catch (missing: UnsatisfiedLinkError) {
            // The default message names the library and the directories it looked in,
            // which leaves the reader with a file name and no way to get the file. So the
            // message says what produces it.
            throw UnsatisfiedLinkError(
                "lib$name.so is not in this APK, so there is no Host to draw with. It is " +
                    "the Rust side of this application, built for an Android target and " +
                    "packaged under lib/<abi>/ in the APK. " +
                    "(${missing.message})",
            )
        }
        library = name
    }

    /** The Host, started on first use and kept until the process ends. */
    fun host(): ComposeRustHost {
        host?.let { return it }
        checkNotNull(library) {
            "no cdylib has been loaded, so the boundary functions this is about to call " +
                "are not in the process yet. An Activity calls ComposeRustRuntime.load() with " +
                "the name of the application's own library before asking for the Host."
        }
        installAndroidBack()
        installAndroidOverlayWindow()
        NativeViewPlatform.available = true
        val created = ComposeRustHost(connection)
        created.start()
        // Before the first frame, so an application's first read finds where its values are.
        storageLocation?.let { location ->
            created.dispatch(HostEvent.StorageLocation(NO_NODE, NO_HANDLER, location))
        }
        host = created
        return created
    }

    /**
     * Which of the process' Activities are on screen. The Host has one lifecycle for all of
     * them, so it hears a stop when the last one leaves the screen, not when any one does.
     */
    private val onScreen = WindowsOnScreen { event -> host?.dispatch(event) }

    /** An Activity came on screen. Timers and animations resume if it is the first. */
    fun start() {
        onScreen.started()
    }

    /**
     * An Activity left the screen. The Host suppresses timers and animations once none is
     * left: another Activity of the process may still be on screen, in a split or in front.
     */
    fun stop() {
        onScreen.stopped()
    }

    /**
     * Asks for the whole tree again, for a Renderer that lost its node table.
     *
     * Nothing calls it on the ordinary path, and that is the point: the two sides share a
     * process, so the table can only be lost if the Renderer throws it away, and this
     * object does not. It is here for a Renderer that chooses to, and it costs component
     * state, because the Host keeps no shadow of the tree it already sent.
     */
    fun resync() {
        host?.resync()
    }

    private val scope = MainScope()
    private var unattended: Job? = null

    /**
     * Binds an Activity to the process-wide [WindowManager] and [AndroidWindowBackend].
     *
     * For single-window applications this returns null and the Activity renders the implicit
     * root (`windowNodeId = 0`). For multi-window applications (`WindowMode.Windows`), the
     * first Activity claims the first `Window` node, recreated Activities rebind by [nodeId],
     * and system-revived tasks notify the Host with `WindowRequested(restoreKey)`.
     *
     * [nodeId] is trusted only when [carriedProcess], the token it was saved or sent with, is
     * this process' [processToken]. After the process died, the saved state and the intent
     * still carry the dead process' id; such a window is matched by its restore key instead,
     * and one the application does not answer for is closed and recorded nowhere.
     */
    fun attachActivity(
        activity: Activity,
        nodeId: Int,
        restoreKey: String,
        carriedProcess: Long,
    ): AndroidActivityWindow? {
        val currentHost = host()
        val backend = windowBackend ?: AndroidWindowBackend(
            rootActivity = activity,
            processToken = processToken,
        ).also { createdBackend ->
            windowBackend = createdBackend
            // A tab dragged over a window finds the node under it from where the window's
            // content laid its registered nodes out, in the same pixels the drag reports.
            val manager = WindowManager(currentHost, createdBackend, onExit = {}, dropTargets = DropTargets.Registered)
            createdBackend.tabDrags = manager
            // A long press and a drag on a tab carrying a `TearOffKey` starts the system drag
            // that may open it as an Activity of its own. Before Android 15 the public API has
            // no such drag, and the tab is an ordinary node.
            if (androidTearOffSupported(Build.VERSION.SDK_INT)) manager.tearOffDrag = AndroidTearOffDrag(createdBackend)
            windowManager = manager
            manager.attach()
        }
        if (currentHost.table.window?.mode != WindowMode.Windows) {
            backend.attachRootActivity(activity)
            return null
        }
        activity.window?.decorView?.let { backend.acceptTearOffDrops(it) }
        val trustedId = carriedWindowNodeId(nodeId, carriedProcess, processToken)
        // Only the renderer's own launches (a window it opened, a tab torn off) carry these
        // extras, and the intent survives recreation and process death, so they tell a window
        // Activity from the one the application was started with.
        val launchedAsWindow = activity.intent?.let {
            it.hasExtra(AndroidWindowBackend.EXTRA_WINDOW_NODE_ID) ||
                it.hasExtra(AndroidWindowBackend.EXTRA_WINDOW_RESTORE_KEY)
        } == true
        // The Activity the application was started with launches the others, recreated or not.
        if (!launchedAsWindow) backend.attachRootActivity(activity)
        if (trustedId == 0 && !launchedAsWindow) {
            val firstWindowId = currentHost.table.roots.firstOrNull {
                currentHost.table.node(it)?.widget == WidgetKind.Window
            }
            if (firstWindowId != null) {
                val existing = backend.windowFor(firstWindowId)
                if (existing != null) {
                    existing.rebind(activity)
                    return existing
                }
            }
            return null
        }
        return backend.activityCreated(activity, trustedId, restoreKey)
    }

    /** The active multi-window backend, once an Activity has attached. */
    fun windowBackend(): AndroidWindowBackend? = windowBackend

    /**
     * An Activity is drawing the Host's tree again, and its frame loop serves the Host's
     * frame requests.
     */
    fun attached() {
        attachedActivities += 1
        unattended?.cancel()
        unattended = null
        updateWindowFrames()
    }

    private var windowFrames: Job? = null

    /**
     * Starts or stops the one frame driver of a multi-window process. Each Activity of such
     * an application draws one `Window` node and serves no frames of its own; the driver asks
     * the Host once per frame, when the first of the windows' clocks ticks, on this UI thread.
     */
    private fun updateWindowFrames() {
        val currentHost = host ?: return
        val manager = windowManager ?: return
        val wanted = windowFrameDriverWanted(
            currentHost.table.window?.mode,
            manager.openWindows.size,
            attachedActivities,
        )
        if (!wanted) {
            windowFrames?.cancel()
            windowFrames = null
            return
        }
        if (windowFrames != null) return
        val requests = FrameRequests.global
        windowFrames = scope.launch {
            serveWindowFrames(
                currentHost,
                manager,
                requests,
                alreadyServed = if (currentHost.startedWith === requests) currentHost.requestsBeforeStart else requests.counter.value,
            )
        }
    }

    /**
     * One Activity was destroyed. Only when no Activity remains attached does the unattended
     * loop take over so worker notifications still go out while the process is backgrounded.
     */
    fun detached() {
        attachedActivities = (attachedActivities - 1).coerceAtLeast(0)
        updateWindowFrames()
        if (attachedActivities > 0) return
        val host = host ?: return
        if (unattended != null) return
        unattended = scope.launch {
            serveFrameRequests(
                FrameRequests.global.counter,
                awaitFrame = { awaitCancellation() },
                serve = host::renderFrame,
                stoppedAfterMillis = 0,
            )
        }
    }

    /** True once the Host exists, so a recreated Activity can tell the two cases apart. */
    val isStarted: Boolean get() = host != null

    /** A lifecycle event addresses the Host itself, so it carries no node and no handler. */
    private const val NO_NODE = 0
    private const val NO_HANDLER = 0L
}
