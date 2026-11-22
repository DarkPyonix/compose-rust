package dev.darkpyonix.composerust.ui.platform

import android.app.Activity
import dev.darkpyonix.composerust.foundation.NativeViewPlatform
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.protocol.WindowMode
import dev.darkpyonix.composerust.runtime.ComposeRustHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

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

    /** The UI came back on screen. Timers and animations resume. */
    fun start() {
        host?.dispatch(HostEvent.LifecycleStart(NO_NODE, NO_HANDLER))
    }

    /** The UI left the screen. The Host suppresses timers and animations. */
    fun stop() {
        host?.dispatch(HostEvent.LifecycleStop(NO_NODE, NO_HANDLER))
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
     */
    fun attachActivity(
        activity: Activity,
        nodeId: Int,
        restoreKey: String,
    ): AndroidActivityWindow? {
        val currentHost = host()
        val backend = windowBackend ?: AndroidWindowBackend(rootActivity = activity).also { createdBackend ->
            windowBackend = createdBackend
            val manager = WindowManager(currentHost, createdBackend, onExit = {})
            windowManager = manager
            manager.attach()
        }
        if (currentHost.table.window?.mode != WindowMode.Windows) {
            backend.attachRootActivity(activity)
            return null
        }
        if (nodeId == 0 && restoreKey.isEmpty()) {
            backend.attachRootActivity(activity)
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
        return backend.activityCreated(activity, nodeId, restoreKey)
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
    }

    /**
     * One Activity was destroyed. Only when no Activity remains attached does the unattended
     * loop take over so worker notifications still go out while the process is backgrounded.
     */
    fun detached() {
        attachedActivities = (attachedActivities - 1).coerceAtLeast(0)
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
