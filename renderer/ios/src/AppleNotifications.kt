package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import platform.Foundation.NSBundle
import platform.Foundation.NSSelectorFromString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The notification centre on Apple's platforms, through `UNUserNotificationCenter`.
 *
 * One file for iOS and for the macOS renderer this module's twin builds, because the
 * framework is the same on both. What differs is passed in: whether a notification is taken
 * back when the process ends, which a desktop does and a phone does not, and how the
 * application's window is brought up when the body of one is pressed.
 *
 * Made as early as the application can make it, which on iOS is while it finishes
 * launching. The delegate has to be in place by then: a press that launched the
 * application is delivered to it straight afterwards, before there is any Host to hand it
 * to, and it is kept until there is.
 */
class AppleNotifications(
    private val withdrawAtExit: Boolean,
    private val bringToFront: () -> Unit,
) : NotificationPlatform {
    /**
     * Null where this run has no bundle identifier. The centre refuses to exist for such a
     * process, and refuses by raising an exception rather than by answering nil, so it is
     * not asked at all.
     */
    private val center: UNUserNotificationCenter? =
        if (NSBundle.mainBundle.bundleIdentifier != null) {
            UNUserNotificationCenter.currentNotificationCenter()
        } else {
            null
        }

    private var reports: NotificationReports? = null

    /** Presses that arrived before [start]: the one that launched the application. */
    private val early = mutableListOf<Pair<String, Int>>()

    /** Every combination of buttons a notification has asked for, by its category name. */
    private val categories = mutableMapOf<String, UNNotificationCategory>()

    /**
     * Kept here because the centre holds its delegate weakly. Without a reference of our own
     * the delegate is collected, and presses stop arriving with nothing to say why.
     */
    private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            val key = didReceiveNotificationResponse.notification.request.identifier
            val action = when (didReceiveNotificationResponse.actionIdentifier) {
                UNNotificationDefaultActionIdentifier -> 0
                ACTION_1 -> 1
                ACTION_2 -> 2
                // Dismissing it is not pressing it.
                else -> null
            }
            if (action != null) {
                onMain {
                    if (action == 0) bringToFront()
                    val sink = reports
                    if (sink == null) early += key to action else sink.activated(key, action)
                }
            }
            withCompletionHandler()
        }

        /**
         * A notification arriving while the application is in front is shown anyway.
         *
         * The platform's default is to swallow it, and whether one should be shown while the
         * window is active has already been decided: a notification that asked not to be was
         * never posted.
         */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(
                UNNotificationPresentationOptionBanner or
                    UNNotificationPresentationOptionList or
                    UNNotificationPresentationOptionSound,
            )
        }
    }

    init {
        center?.delegate = delegate
    }

    override fun start(reports: NotificationReports) {
        this.reports = reports
        early.forEach { (key, action) -> reports.activated(key, action) }
        early.clear()
        if (center == null) {
            reports.permission(NotificationPermission.Unsupported)
        } else {
            refreshPermission()
        }
    }

    override fun refreshPermission() {
        val center = center ?: return
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val state = when (settings?.authorizationStatus) {
                UNAuthorizationStatusNotDetermined -> NotificationPermission.NotDetermined
                UNAuthorizationStatusDenied -> NotificationPermission.Denied
                null -> NotificationPermission.NotDetermined
                // Authorized, provisional and ephemeral all show what is posted.
                else -> NotificationPermission.Granted
            }
            reports?.permission(state)
        }
    }

    override fun requestPermission() {
        val center = center ?: return
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, _ ->
            reports?.permission(
                if (granted) NotificationPermission.Granted else NotificationPermission.Denied,
            )
        }
    }

    override fun post(notification: PlatformNotification) {
        val center = center ?: return
        val content = UNMutableNotificationContent()
        content.setTitle(notification.title)
        content.setBody(notification.body)
        content.setSound(UNNotificationSound.defaultSound())
        // The channel groups them, which is the nearest this platform has to a channel:
        // the notification centre stacks a thread together and settings stay per
        // application.
        if (notification.channel.isNotEmpty()) content.setThreadIdentifier(notification.channel)
        categoryFor(notification)?.let { content.setCategoryIdentifier(it) }
        // Urgent is time sensitive: it is shown through a focus mode that lets those
        // through. Asked about first, because a system older than the level has no such
        // property and setting it would stop the process.
        if (notification.importance == NotificationImportance.Urgent &&
            content.respondsToSelector(NSSelectorFromString("setInterruptionLevel:"))
        ) {
            content.setInterruptionLevel(
                UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive,
            )
        }
        // The key is the request's identifier, so a second request under the same key
        // replaces the first rather than standing beside it.
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = notification.key,
            content = content,
            trigger = null,
        )
        center.addNotificationRequest(request, withCompletionHandler = null)
    }

    override fun withdraw(key: String) {
        val center = center ?: return
        center.removePendingNotificationRequestsWithIdentifiers(listOf(key))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(key))
    }

    override fun processEnding() {
        if (!withdrawAtExit) return
        center?.removeAllPendingNotificationRequests()
        center?.removeAllDeliveredNotifications()
    }

    /**
     * The category carrying this notification's buttons, registered the first time that
     * pair of labels is seen.
     *
     * The buttons are not foreground actions, so pressing one does not bring the
     * application up: a button is there to be dealt with without opening anything.
     */
    private fun categoryFor(notification: PlatformNotification): String? {
        if (notification.action1.isEmpty() && notification.action2.isEmpty()) return null
        val name = "compose-rust/${notification.action1}/${notification.action2}"
        if (name !in categories) {
            val actions = buildList {
                if (notification.action1.isNotEmpty()) {
                    add(
                        UNNotificationAction.actionWithIdentifier(
                            identifier = ACTION_1,
                            title = notification.action1,
                            options = UNNotificationActionOptionNone,
                        ),
                    )
                }
                if (notification.action2.isNotEmpty()) {
                    add(
                        UNNotificationAction.actionWithIdentifier(
                            identifier = ACTION_2,
                            title = notification.action2,
                            options = UNNotificationActionOptionNone,
                        ),
                    )
                }
            }
            categories[name] = UNNotificationCategory.categoryWithIdentifier(
                identifier = name,
                actions = actions,
                intentIdentifiers = emptyList<String>(),
                options = UNNotificationCategoryOptionNone,
            )
            // The whole set every time: registering replaces what was registered before.
            center?.setNotificationCategories(categories.values.toSet())
        }
        return name
    }

    private fun onMain(work: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { work() }
    }

    private companion object {
        const val ACTION_1 = "compose-rust.action.1"
        const val ACTION_2 = "compose-rust.action.2"
    }
}
