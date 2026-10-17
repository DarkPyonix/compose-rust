package dev.darkpyonix.composerust.ui.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.ComponentActivity
import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission

/**
 * Notifications on Android, through `NotificationManager`.
 *
 * One for the life of the process, like the Host: an Activity is recreated for a rotation
 * and the notification centre the Host was started with has to be the same one afterwards.
 * The generated Activity installs it before it asks for the Host and hands it each intent
 * it is started with, which is how a press on a notification reaches the application.
 *
 * Android 13 and later ask for `POST_NOTIFICATIONS` at run time. The permission is declared
 * in the application's manifest by the build that generates the Activity; the prompt is
 * shown through the Activity, because only an Activity can show one.
 *
 * Channels are the user's switches. Each `channel` the application names becomes one, under
 * that name, the first time a notification is posted to it; an empty one is the
 * application's default channel, named after the application.
 */
object AndroidNotifications {
    private var platform: Platform? = null

    /**
     * Installs the notification centre, once, and gives it [activity] to ask for permission
     * through. Called by the generated Activity before it asks for the Host, on every
     * creation, so the newest Activity is the one a prompt appears in.
     */
    fun install(activity: ComponentActivity, requestPermission: () -> Unit) {
        val installed = platform ?: Platform(activity.applicationContext).also {
            platform = it
            Notifications.platform = it
        }
        // Read through the same Activity, which is what every platform setting is asked
        // through here: the animator duration scale, where zero is the system's way of
        // saying remove animations.
        val context = activity.applicationContext
        dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting = {
            val scale = android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
            if (scale == 0f) {
                dev.darkpyonix.composerust.protocol.ReducedMotion.On
            } else {
                dev.darkpyonix.composerust.protocol.ReducedMotion.Off
            }
        }
        installed.askThrough = requestPermission
    }

    /**
     * Reads a press out of the intent an Activity was started or resumed with.
     *
     * A press on a notification's body starts the Activity, or brings it back, with the key
     * in the intent. When it started the process there is no Host yet; the press is kept and
     * becomes the Host's first event after its first batch.
     */
    fun handle(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_KEY) ?: return
        val action = intent.getIntExtra(EXTRA_ACTION, 0)
        // Consumed, so a recreated Activity reading the same intent again does not press it
        // a second time.
        intent.removeExtra(EXTRA_KEY)
        platform?.activated(key, action)
    }

    /** The answer to the permission prompt the Activity showed. */
    fun permissionAnswered() {
        platform?.report()
    }

    private const val EXTRA_KEY = "compose-rust.notification.key"
    private const val EXTRA_ACTION = "compose-rust.notification.action"
    private const val ACTION_BUTTON = "compose-rust.notification.BUTTON"
    private const val NOTIFICATION_ID = 1
    private const val PREFERENCES = "compose-rust.notifications"
    private const val ASKED = "asked"
    private const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    private class Platform(private val context: Context) : NotificationPlatform {
        private val manager: NotificationManager? =
            context.getSystemService(NotificationManager::class.java)
        private var reports: NotificationReports? = null
        private val early = mutableListOf<Pair<String, Int>>()
        private val channels = mutableSetOf<String>()
        var askThrough: (() -> Unit)? = null

        /** A button press. Registered for this process only, so nothing else can send one. */
        private val buttons = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val key = intent.getStringExtra(EXTRA_KEY) ?: return
                val action = intent.getIntExtra(EXTRA_ACTION, 0)
                // A button does not take its notification away by itself on this platform.
                // Pressing one has dealt with it, so it goes.
                manager?.cancel(key, NOTIFICATION_ID)
                activated(key, action)
            }
        }

        override fun start(reports: NotificationReports) {
            this.reports = reports
            early.forEach { (key, action) -> reports.activated(key, action) }
            early.clear()
            val filter = IntentFilter(ACTION_BUTTON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(buttons, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(buttons, filter)
            }
            report()
        }

        fun activated(key: String, action: Int) {
            val sink = reports
            if (sink == null) early += key to action else sink.activated(key, action)
        }

        fun report() {
            reports?.permission(current())
        }

        private fun current(): NotificationPermission {
            val manager = manager ?: return NotificationPermission.Unsupported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    // Not granted is two answers here: never asked, and asked and refused.
                    // The system does not say which, so whether this process asked is kept.
                    val asked = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .getBoolean(ASKED, false)
                    return if (asked) NotificationPermission.Denied else NotificationPermission.NotDetermined
                }
            }
            return if (manager.areNotificationsEnabled()) {
                NotificationPermission.Granted
            } else {
                NotificationPermission.Denied
            }
        }

        override fun requestPermission() {
            val ask = askThrough
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ask != null &&
                current() == NotificationPermission.NotDetermined
            ) {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(ASKED, true).apply()
                ask()
            } else {
                // Before Android 13 there is nothing to ask: notifications are on until the
                // user turns them off.
                report()
            }
        }

        override fun refreshPermission() = report()

        override fun post(notification: PlatformNotification) {
            val manager = manager ?: return
            val channel = channelFor(manager, notification)
            val builder = Notification.Builder(context, channel)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setStyle(Notification.BigTextStyle().bigText(notification.body))
                .setAutoCancel(true)
                .setContentIntent(bodyIntent(notification.key))
            if (notification.importance == NotificationImportance.Urgent) {
                builder.setCategory(Notification.CATEGORY_REMINDER)
            }
            if (notification.action1.isNotEmpty()) {
                builder.addAction(button(notification.key, 1, notification.action1))
            }
            if (notification.action2.isNotEmpty()) {
                builder.addAction(button(notification.key, 2, notification.action2))
            }
            // The key is the tag, so a second post under it replaces the first.
            manager.notify(notification.key, NOTIFICATION_ID, builder.build())
        }

        override fun withdraw(key: String) {
            manager?.cancel(key, NOTIFICATION_ID)
        }

        /** A phone leaves them: pressing one is how the application is started again. */
        override fun processEnding() = Unit

        private fun channelFor(manager: NotificationManager, notification: PlatformNotification): String {
            val id = "compose-rust." + notification.channel.ifEmpty { "default" }
            if (id !in channels) {
                val name = notification.channel.ifEmpty {
                    context.applicationInfo.loadLabel(context.packageManager).toString()
                }
                // A channel's importance is fixed when it is made and is the user's after
                // that, so the first notification posted to it decides where it starts.
                val importance = if (notification.importance == NotificationImportance.Urgent) {
                    NotificationManager.IMPORTANCE_HIGH
                } else {
                    NotificationManager.IMPORTANCE_DEFAULT
                }
                manager.createNotificationChannel(NotificationChannel(id, name, importance))
                channels += id
            }
            return id
        }

        /** Starts or brings back the application, with the key, when the body is pressed. */
        private fun bodyIntent(key: String): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null
            launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK
            launch.putExtra(EXTRA_KEY, key)
            launch.putExtra(EXTRA_ACTION, 0)
            return PendingIntent.getActivity(
                context,
                requestCode(key, 0),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** A button reports to this process without starting or bringing up anything. */
        private fun button(key: String, action: Int, label: String): Notification.Action {
            val intent = Intent(ACTION_BUTTON)
                .setPackage(context.packageName)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_ACTION, action)
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(key, action),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Action.Builder(null as Icon?, label, pending).build()
        }

        /**
         * One request code per key and part, so the intents of two notifications, or of a
         * notification's body and its button, never stand in for each other.
         */
        private fun requestCode(key: String, action: Int): Int = key.hashCode() * 3 + action
    }
}
