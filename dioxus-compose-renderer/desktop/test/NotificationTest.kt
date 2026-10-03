package dioxus.compose.test

import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.Mutation
import dioxus.compose.protocol.NotificationImportance
import dioxus.compose.protocol.NotificationPermission
import dioxus.compose.protocol.NotificationPresentation
import dioxus.compose.runtime.DioxusHost
import dioxus.compose.tooling.FakeHostConnection
import dioxus.compose.tooling.HostResponse
import dioxus.compose.ui.platform.NotificationPlatform
import dioxus.compose.ui.platform.NotificationReports
import dioxus.compose.ui.platform.Notifications
import dioxus.compose.ui.platform.PlatformNotification
import dioxus.compose.ui.platform.UnsupportedNotifications
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A notification centre that remembers instead of showing.
 *
 * It keeps what a real one keeps, one entry per key, so replacing and withdrawing can be
 * checked as what is left on show rather than as which calls were made.
 */
private class RecordingPlatform(
    private val initial: NotificationPermission = NotificationPermission.NotDetermined,
    private val launchedBy: Pair<String, Int>? = null,
) : NotificationPlatform {
    lateinit var reports: NotificationReports
    val showing = linkedMapOf<String, PlatformNotification>()
    var requests = 0
    var refreshes = 0
    var ended = false

    override fun start(reports: NotificationReports) {
        this.reports = reports
        // A press that started the process is known before anything else is.
        launchedBy?.let { (key, action) -> reports.activated(key, action) }
        reports.permission(initial)
    }

    override fun requestPermission() {
        requests++
    }

    override fun post(notification: PlatformNotification) {
        showing[notification.key] = notification
    }

    override fun withdraw(key: String) {
        showing.remove(key)
    }

    override fun refreshPermission() {
        refreshes++
    }

    override fun processEnding() {
        ended = true
        showing.clear()
    }
}

private fun post(
    key: String,
    title: String = "세션이 끝났습니다",
    presentation: NotificationPresentation = NotificationPresentation.Always,
) = Mutation.PostNotification(
    key = key,
    title = title,
    body = "테스트 214개 통과",
    channel = "세션",
    action1 = "열기",
    action2 = "",
    importance = NotificationImportance.Normal,
    presentation = presentation,
)

/**
 * The interpreter's half of notifications: what the window's own state decides, and the one
 * way the platform's answers reach the Host.
 *
 * Whether a real notification centre shows what it is given is a manual check per platform.
 * What is checked here is everything that happens on this side of it.
 */
class NotificationTest {
    @AfterTest
    fun restore() {
        Notifications.platform = UnsupportedNotifications
    }

    private fun hostWith(
        platform: NotificationPlatform,
        initial: List<Mutation> = emptyList(),
    ): Pair<DioxusHost, FakeHostConnection> {
        Notifications.platform = platform
        val connection = FakeHostConnection(initial)
        val host = DioxusHost(connection)
        host.start()
        return host to connection
    }

    private fun FakeHostConnection.permissions() =
        events.filterIsInstance<HostEvent.NotificationPermissionChanged>().map { it.state }

    /** Delivers what the platform reported, the way the frame a report asks for does. */
    private fun DioxusHost.frame() = renderFrame(0)

    @Test
    fun fr36_the_first_post_asks_for_permission_and_a_yes_shows_it() {
        val platform = RecordingPlatform()
        val (host, connection) = hostWith(platform, listOf(post("session/7")))

        assertEquals(1, platform.requests, "the first post is when permission is asked for")
        assertTrue(platform.showing.isEmpty(), "nothing is shown before the answer")

        platform.reports.permission(NotificationPermission.Granted)
        host.frame()
        assertEquals(listOf("session/7"), platform.showing.keys.toList())
        assertEquals(listOf(NotificationPermission.Granted), connection.permissions())

        // Granted is remembered: the next one goes straight out, and nothing asks again.
        host.table.apply(post("session/8"))
        host.frame()
        assertEquals(listOf("session/7", "session/8"), platform.showing.keys.toList())
        assertEquals(1, platform.requests)
        assertEquals(listOf(NotificationPermission.Granted), connection.permissions())
    }

    @Test
    fun fr36_a_no_drops_the_notification_reports_denied_once_and_is_never_asked_again() {
        val platform = RecordingPlatform()
        val (host, connection) = hostWith(platform, listOf(post("session/7")))
        platform.reports.permission(NotificationPermission.Denied)
        host.frame()

        assertTrue(platform.showing.isEmpty())
        assertEquals(listOf(NotificationPermission.Denied), connection.permissions())

        host.table.apply(post("session/8"))
        host.table.apply(Mutation.RequestNotificationPermission)
        host.frame()
        assertEquals(1, platform.requests, "a no is not asked again")
        assertTrue(platform.showing.isEmpty())
        assertEquals(listOf(NotificationPermission.Denied), connection.permissions())
        assertTrue(connection.events.none { it is HostEvent.ProtocolError })
    }

    @Test
    fun fr36_the_application_can_ask_first_and_only_one_prompt_is_up_at_a_time() {
        val platform = RecordingPlatform()
        val (host, _) = hostWith(platform)
        host.table.apply(Mutation.RequestNotificationPermission)
        host.table.apply(Mutation.RequestNotificationPermission)
        host.table.apply(post("session/7"))
        assertEquals(1, platform.requests)

        platform.reports.permission(NotificationPermission.Granted)
        host.frame()
        host.table.apply(Mutation.RequestNotificationPermission)
        assertEquals(1, platform.requests, "a yes is not asked again either")
        assertEquals(listOf("session/7"), platform.showing.keys.toList())
    }

    @Test
    fun fr36_when_inactive_is_dropped_while_the_window_is_active_and_always_is_shown() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, _) = hostWith(platform)
        host.frame()
        host.table.notifications.windowFocusChanged(true)

        host.table.apply(post("quiet", presentation = NotificationPresentation.WhenInactive))
        host.table.apply(post("loud", presentation = NotificationPresentation.Always))
        assertEquals(listOf("loud"), platform.showing.keys.toList())

        host.table.notifications.windowFocusChanged(false)
        host.table.apply(post("quiet", presentation = NotificationPresentation.WhenInactive))
        assertEquals(listOf("loud", "quiet"), platform.showing.keys.toList())
    }

    @Test
    fun fr36_the_same_key_replaces_and_a_withdrawal_takes_it_away() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, _) = hostWith(platform)
        host.frame()

        host.table.apply(post("approval/3", title = "first"))
        host.table.apply(post("approval/3", title = "second"))
        assertEquals(1, platform.showing.size, "one key is one notification")
        assertEquals("second", platform.showing.getValue("approval/3").title)

        host.table.apply(Mutation.WithdrawNotification("approval/3"))
        assertTrue(platform.showing.isEmpty())
    }

    @Test
    fun fr36_a_notification_with_no_key_is_given_a_name_of_its_own() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, _) = hostWith(platform)
        host.frame()
        host.table.apply(post(""))
        host.table.apply(post(""))
        assertEquals(2, platform.showing.size, "two unnamed notifications are two")
        assertTrue(platform.showing.keys.none { it.isEmpty() })
    }

    @Test
    fun fr36_a_press_reaches_the_host_exactly_once_with_its_key_and_action() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, connection) = hostWith(platform)
        platform.reports.activated("session/7", 0)
        platform.reports.activated("approval/3", 2)
        host.frame()
        host.frame()

        val presses = connection.events.filterIsInstance<HostEvent.NotificationActivated>()
        assertEquals(
            listOf("session/7" to 0, "approval/3" to 2),
            presses.map { it.key to it.action },
        )
        assertTrue(presses.all { it.nodeId == 0 && it.handlerId == 0L })
    }

    @Test
    fun fr36_a_press_that_started_the_application_is_the_first_event_after_init() {
        val platform = RecordingPlatform(
            NotificationPermission.Granted,
            launchedBy = "session/7" to 0,
        )
        val (_, connection) = hostWith(platform)
        val first = connection.events.first()
        assertTrue(
            first is HostEvent.NotificationActivated && first.key == "session/7",
            "the first event was $first",
        )
    }

    @Test
    fun fr36_a_run_that_cannot_show_notifications_says_so_and_carries_on() {
        val (host, connection) = hostWith(
            UnsupportedNotifications,
            listOf(post("session/7"), Mutation.RequestNotificationPermission),
        )
        host.table.apply(Mutation.WithdrawNotification("session/7"))
        host.frame()
        assertEquals(listOf(NotificationPermission.Unsupported), connection.permissions())
        assertTrue(connection.events.none { it is HostEvent.ProtocolError })
    }

    @Test
    fun fr36_permission_is_reported_once_and_then_only_when_it_changes() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, connection) = hostWith(platform)
        platform.reports.permission(NotificationPermission.Granted)
        host.frame()
        assertEquals(listOf(NotificationPermission.Granted), connection.permissions())

        // Turned off in the system's settings while the window was elsewhere, and looked
        // for again when the window comes back.
        host.table.notifications.windowFocusChanged(false)
        host.table.notifications.windowFocusChanged(true)
        assertEquals(1, platform.refreshes)
        platform.reports.permission(NotificationPermission.Denied)
        host.frame()
        assertEquals(
            listOf(NotificationPermission.Granted, NotificationPermission.Denied),
            connection.permissions(),
        )
    }

    @Test
    fun fr36_a_platform_answer_waits_for_a_frame_rather_than_entering_the_host() {
        val platform = RecordingPlatform()
        var asked = 0
        val (host, connection) = hostWith(platform)
        host.table.notifications.wake = { asked++ }
        val before = connection.events.size
        platform.reports.activated("session/7", 0)
        assertEquals(1, asked, "a report asks for a frame")
        assertEquals(before, connection.events.size, "and does not call the Host itself")
        host.frame()
        assertEquals(before + 1, connection.events.size)
    }

    @Test
    fun fr36_ending_the_process_takes_back_what_a_desktop_showed() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val (host, _) = hostWith(platform)
        host.frame()
        host.table.apply(post("session/7"))
        host.shutdown()
        assertTrue(platform.ended)
        assertTrue(platform.showing.isEmpty())
    }

    @Test
    fun fr36_a_resync_keeps_what_the_host_was_told() {
        val platform = RecordingPlatform(NotificationPermission.Granted)
        val connection = FakeHostConnection()
        connection.respondWith { HostResponse() }
        Notifications.platform = platform
        val host = DioxusHost(connection)
        host.start()
        host.resync()
        host.frame()
        assertEquals(listOf(NotificationPermission.Granted), connection.permissions())
    }
}
