package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission

/**
 * Notifications in a browser, through the Notifications API.
 *
 * A page can show one only from a secure context (HTTPS, or localhost) and only while it is
 * open; anywhere else the answer is unsupported. Buttons are not offered: a page's own
 * notifications have none, only a service worker's do, and this renderer does not install
 * one. Pressing the body focuses the page's tab and closes the notification.
 *
 * The browser asks for permission only from inside something the user did. The request is
 * applied inside the batch the press produced, on the same call stack as the press, so it
 * still counts.
 */
class WebNotifications : NotificationPlatform {
    private var reports: NotificationReports? = null

    override fun start(reports: NotificationReports) {
        this.reports = reports
        installCallbacks(
            onActivated = { key, action -> reports.activated(key, action) },
            onPermission = { state -> reports.permission(permissionOf(state)) },
        )
        reports.permission(permissionOf(currentPermission()))
    }

    override fun requestPermission() = askForPermission()

    override fun refreshPermission() {
        reports?.permission(permissionOf(currentPermission()))
    }

    override fun post(notification: PlatformNotification) = show(
        notification.key,
        notification.title,
        notification.body,
        notification.importance == NotificationImportance.Urgent,
    )

    override fun withdraw(key: String) = close(key)

    /** A page's notifications go with the page anyway; nothing is left to take back. */
    override fun processEnding() = Unit

    private fun permissionOf(state: Int): NotificationPermission = when (state) {
        1 -> NotificationPermission.NotDetermined
        2 -> NotificationPermission.Granted
        3 -> NotificationPermission.Denied
        else -> NotificationPermission.Unsupported
    }
}

/** 1 not asked, 2 granted, 3 denied, 4 no Notifications API or not a secure context. */
private fun currentPermission(): Int = js(
    """(typeof Notification === 'undefined' || !globalThis.isSecureContext) ? 4
        : ({ 'default': 1, 'granted': 2, 'denied': 3 })[Notification.permission] || 3""",
)

private fun installCallbacks(
    onActivated: (String, Int) -> Unit,
    onPermission: (Int) -> Unit,
): Unit = js(
    """{ globalThis.__composeRustNotifications = {
        shown: new Map(), onActivated: onActivated, onPermission: onPermission }; }""",
)

private fun askForPermission(): Unit = js(
    """{ const state = globalThis.__composeRustNotifications;
        if (typeof Notification === 'undefined' || !globalThis.isSecureContext) {
            state.onPermission(4);
        } else {
            Notification.requestPermission().then(
                (answer) => state.onPermission(answer === 'granted' ? 2 : answer === 'denied' ? 3 : 1),
                () => state.onPermission(3));
        } }""",
)

/** Shows one, replacing what was shown under the same key: the tag is the key. */
private fun show(key: String, title: String, body: String, urgent: Boolean): Unit = js(
    """{ const state = globalThis.__composeRustNotifications;
        if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
        const previous = state.shown.get(key);
        if (previous) { previous.onclose = null; previous.close(); }
        const shown = new Notification(title, { body: body, tag: key, renotify: true,
            requireInteraction: urgent });
        shown.onclick = () => { globalThis.focus(); shown.close(); state.onActivated(key, 0); };
        shown.onclose = () => { if (state.shown.get(key) === shown) state.shown.delete(key); };
        state.shown.set(key, shown); }""",
)

private fun close(key: String): Unit = js(
    """{ const state = globalThis.__composeRustNotifications;
        const shown = state && state.shown.get(key);
        if (shown) { state.shown.delete(key); shown.onclose = null; shown.close(); } }""",
)
