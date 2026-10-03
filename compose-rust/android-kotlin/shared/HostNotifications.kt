package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.Mutation
import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import dev.darkpyonix.composerust.protocol.NotificationPresentation
import dev.darkpyonix.composerust.runtime.EventDispatcher
import kotlinx.coroutines.channels.Channel

/**
 * One notification, as the platform is asked to show it.
 *
 * The key is never empty here: a notification the Host left unnamed has been given a name
 * of this side's own by the time a platform sees it, so every platform can treat the key as
 * the identity that replacing and withdrawing go by.
 *
 * An empty action label is no button. Action 1 and action 2 keep their numbers whatever is
 * on either side of them, because the number is what comes back when one is pressed.
 */
data class PlatformNotification(
    val key: String,
    val title: String,
    val body: String,
    val channel: String,
    val action1: String,
    val action2: String,
    val importance: NotificationImportance,
)

/**
 * Where a platform tells the interpreter what happened. Safe from any thread.
 *
 * A notification centre answers on threads of its own choosing: a permission prompt
 * completes on a background queue, a toast is activated on a COM thread, a D-Bus signal
 * arrives on a reader. None of those is the thread the Host lives on, so nothing reported
 * here reaches the Host directly. It waits, a frame is asked for, and the frame delivers
 * it on the UI thread.
 */
interface NotificationReports {
    /** What the platform now says about permission. Repeats are fine; only changes travel. */
    fun permission(state: NotificationPermission)

    /**
     * The user pressed a notification: the body for action 0, a button for 1 or 2.
     *
     * Call it once per press. The platform has already done what a press on the body asks
     * of it, which is to bring the application's window to the front; a press on a button
     * brings nothing up, because a button is there to be dealt with without opening the
     * window.
     */
    fun activated(key: String, action: Int)
}

/**
 * One platform's notification centre.
 *
 * Every call is made on the UI thread. Answers go back through the [NotificationReports]
 * handed to [start], from whichever thread the platform answers on.
 */
interface NotificationPlatform {
    /**
     * Called once, before anything else. The platform keeps [reports] and says through it
     * what the permission is, now or when it finds out.
     */
    fun start(reports: NotificationReports)

    /** Shows the platform's own permission request. The answer goes through the reports. */
    fun requestPermission()

    /** Shows [notification], replacing any still showing under the same key. */
    fun post(notification: PlatformNotification)

    /** Takes back the notification showing under [key], if there is one. */
    fun withdraw(key: String)

    /**
     * Asks again what the permission is.
     *
     * The user can change it in the system's settings while the window is somewhere else,
     * and coming back to the window is when that is looked for.
     */
    fun refreshPermission()

    /**
     * The process is ending.
     *
     * A desktop takes back what it posted: a notification left behind would point at work
     * the next process does not know about, and pressing it would start that process for
     * nothing. A phone leaves them, because a phone is where pressing one is how the
     * application starts.
     */
    fun processEnding()

    /**
     * Collects what the platform has queued on its own side and reports it.
     *
     * Called on the UI thread at the top of every delivery, and on every turn of a loop
     * that has no other way to hear from the platform. A platform whose answers already
     * arrive through the reports leaves it alone; one that keeps them in a C queue, or
     * reads them off a socket, reports them from here.
     */
    fun pump() {}
}

/**
 * A platform with no way to show a notification, or a run that cannot: a development shell
 * with no bundle identifier, a Windows executable with no AppUserModelID, a Linux session
 * with no notification daemon.
 *
 * Every request is accepted and nothing happens. That is not a protocol error, because a
 * platform without the feature is not a malformed batch, and the process carries on.
 */
object UnsupportedNotifications : NotificationPlatform {
    override fun start(reports: NotificationReports) =
        reports.permission(NotificationPermission.Unsupported)

    override fun requestPermission() = Unit

    override fun post(notification: PlatformNotification) = Unit

    override fun withdraw(key: String) = Unit

    override fun refreshPermission() = Unit

    override fun processEnding() = Unit
}

/**
 * The notification centre this process uses.
 *
 * Installed by each platform's entry point before the Host starts, the way the frame
 * request source is: there is one process, one window and one Host, and a platform knows
 * which centre it has before it has anything to show. A run nobody installed one for has
 * none, and says so.
 */
object Notifications {
    var platform: NotificationPlatform = UnsupportedNotifications
}

/**
 * What the interpreter does with the notification records, and the one route the platform's
 * answers take back to the Host.
 *
 * The decisions that belong to whoever has the window are made here rather than by the Host:
 * whether the window is the active one, whether permission has been asked for yet, what a
 * notification nobody named is called. The platforms underneath only show, take back and
 * report.
 */
class NotificationCenter(
    private val platform: NotificationPlatform = Notifications.platform,
) {
    private sealed interface Report {
        data class Permission(val state: NotificationPermission) : Report
        data class Activated(val key: String, val action: Int) : Report
    }

    /**
     * What the platform has said and the Host has not yet been told.
     *
     * A channel because it is the one queue every target this compiles for can share across
     * threads without a lock of its own, and unlimited because what goes in is a handful of
     * presses and answers, never a stream.
     */
    private val inbox = Channel<Report>(Channel.UNLIMITED)

    /**
     * How a report asks for the frame that delivers it.
     *
     * The frame loop installs its own request; until it has, a report waits for the next
     * thing that drains, which the Host's start does.
     */
    var wake: () -> Unit = {}

    private val reports = object : NotificationReports {
        override fun permission(state: NotificationPermission) {
            inbox.trySend(Report.Permission(state))
            wake()
        }

        override fun activated(key: String, action: Int) {
            inbox.trySend(Report.Activated(key, action))
            wake()
        }
    }

    private var started = false
    private var dispatcher: EventDispatcher? = null

    /** What the platform last said. */
    var permission: NotificationPermission = NotificationPermission.NotDetermined
        private set

    /** What the Host was last told. Both sides start from not determined. */
    private var reported = NotificationPermission.NotDetermined

    /** Whether the platform's prompt is up. One at a time, and never again after a no. */
    private var asking = false

    /** Posted while permission was being asked for, shown if the answer is yes. */
    private val waiting = mutableListOf<PlatformNotification>()

    /** Whether the application's window is the active one. */
    var windowActive: Boolean = false
        private set

    private var unnamed = 0L

    /** Presses that arrived before there was a Host to hand them to. */
    private val early = mutableListOf<Report.Activated>()

    private fun ensureStarted() {
        if (started) return
        started = true
        platform.start(reports)
    }

    /**
     * Connects the centre to the Host, once the Host's first batch has been applied.
     *
     * A press that started the application, which is how a phone launches one from its
     * notification centre, has been waiting for this: there was no Host to send it to. It is
     * the first thing the Host hears after its first batch, before anything the window
     * reports about itself.
     */
    fun attach(host: EventDispatcher) {
        dispatcher = host
        ensureStarted()
        drain()
    }

    /** The process is ending. */
    fun shutdown() {
        dispatcher = null
        if (started) platform.processEnding()
    }

    /** The interpreter's half of the three notification records. */
    fun apply(mutation: Mutation.PostNotification) {
        ensureStarted()
        // Dropped rather than shown when the window is the active one and the application
        // asked for that. The user is looking at the window, and calling them from outside
        // it to say what is already in front of them is noise.
        if (mutation.presentation == NotificationPresentation.WhenInactive && windowActive) return
        val notification = PlatformNotification(
            key = mutation.key.ifEmpty { "compose-rust/${++unnamed}" },
            title = mutation.title,
            body = mutation.body,
            channel = mutation.channel,
            action1 = mutation.action1,
            action2 = mutation.action2,
            importance = mutation.importance,
        )
        when (permission) {
            NotificationPermission.Granted -> platform.post(notification)
            // The first post is when permission is asked for: asking the moment the
            // application starts is asking before the user knows what for, which is when a
            // no is likeliest. The notification waits for the answer.
            NotificationPermission.NotDetermined -> {
                waiting.removeAll { it.key == notification.key }
                waiting += notification
                ask()
            }
            // A no is not asked again, and a run that cannot show one does nothing. Neither
            // is an error. Nor does a message appear in the window instead: the moment a
            // notification is for is the moment nobody is looking at the window.
            NotificationPermission.Denied, NotificationPermission.Unsupported -> Unit
        }
    }

    fun withdraw(key: String) {
        ensureStarted()
        waiting.removeAll { it.key == key }
        if (permission == NotificationPermission.Granted) platform.withdraw(key)
    }

    /**
     * Asks for permission because the application asked, for instance from a settings
     * switch the user just turned on.
     *
     * Applied inside the call that carried it, which is inside the call that handled the
     * press, so a browser still counts it as something the user did.
     */
    fun requestPermission() {
        ensureStarted()
        if (permission == NotificationPermission.NotDetermined) ask()
    }

    private fun ask() {
        if (asking) return
        asking = true
        platform.requestPermission()
    }

    /**
     * Whether the window is the one in use, as the composition last saw it.
     *
     * Becoming active is also when the permission is looked at again, because the user may
     * have changed it in the system's settings while the window was not.
     */
    fun windowFocusChanged(active: Boolean) {
        val becameActive = active && !windowActive
        windowActive = active
        if (becameActive && started) platform.refreshPermission()
    }

    /** One turn of a loop that hears the platform by asking it. */
    fun pump() {
        if (started) platform.pump()
    }

    /**
     * Hands everything the platform reported to the Host, on the UI thread.
     *
     * Called where the Host can be entered: after its start, and at the top of every frame
     * that a report asked for.
     */
    fun drain() {
        // A platform that queues its answers on its own side is asked for them first, so a
        // frame its own wake asked for delivers what it was asked for.
        if (started) platform.pump()
        dispatcher?.let { host ->
            if (early.isNotEmpty()) {
                val held = early.toList()
                early.clear()
                held.forEach { activate(host, it) }
            }
        }
        while (true) {
            val report = inbox.tryReceive().getOrNull() ?: break
            when (report) {
                is Report.Permission -> answered(report.state)
                is Report.Activated -> {
                    val host = dispatcher
                    if (host == null) early += report else activate(host, report)
                }
            }
        }
        val host = dispatcher ?: return
        if (reported != permission) {
            reported = permission
            host.dispatch(
                HostEvent.NotificationPermissionChanged(nodeId = 0, handlerId = 0, state = permission),
            )
        }
    }

    private fun answered(state: NotificationPermission) {
        permission = state
        if (state != NotificationPermission.NotDetermined) asking = false
        when (state) {
            NotificationPermission.Granted -> {
                val held = waiting.toList()
                waiting.clear()
                held.forEach(platform::post)
            }
            NotificationPermission.Denied, NotificationPermission.Unsupported -> waiting.clear()
            NotificationPermission.NotDetermined -> Unit
        }
    }

    private fun activate(host: EventDispatcher, report: Report.Activated) {
        host.dispatch(
            HostEvent.NotificationActivated(
                nodeId = 0,
                handlerId = 0,
                action = report.action,
                key = report.key,
            ),
        )
    }
}
