@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.NotificationImportance
import dev.darkpyonix.composerust.protocol.NotificationPermission
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.value

// The toasts of `desktop/c/win32_notifications.c`, linked into the same executable. The same
// C file and the same names the native image reaches through `NativeDesktopNotifications`,
// called here by symbol name, which is how this renderer reaches its window as well.

@SymbolName("dxc_notify_start")
private external fun notifyStart(): Int

@SymbolName("dxc_notify_request_permission")
private external fun notifyRequestPermission()

@SymbolName("dxc_notify_refresh_permission")
private external fun notifyRefreshPermission()

@SymbolName("dxc_notify_post")
private external fun notifyPost(
    key: CPointer<ByteVar>?,
    title: CPointer<ByteVar>?,
    body: CPointer<ByteVar>?,
    channel: CPointer<ByteVar>?,
    action1: CPointer<ByteVar>?,
    action2: CPointer<ByteVar>?,
    urgent: Int,
)

@SymbolName("dxc_notify_withdraw")
private external fun notifyWithdraw(key: CPointer<ByteVar>?)

@SymbolName("dxc_notify_withdraw_all")
private external fun notifyWithdrawAll()

@SymbolName("dxc_notify_next_event")
private external fun notifyNextEvent(key: CPointer<ByteVar>?, capacity: Int, value: CPointer<IntVar>?): Int

/** What the C side answers when it is still finding out, and has queued the answer. */
private const val FINDING_OUT = 0

private const val EVENT_ACTIVATED = 1
private const val EVENT_PERMISSION = 2

/**
 * How long a key read back from C may be. A key is the application's own name for a
 * notification, a few dozen bytes in practice; anything near this is not one it gave out.
 */
private const val KEY_BYTES = 4096

/**
 * Notifications on Windows: toasts, through the Windows Runtime, answered by the C file.
 *
 * A toast is activated on a thread of the system's. The C side puts the press on a list and
 * asks this renderer for a frame, the same thread-safe request a Host worker makes, and the
 * frame reaches [pump] on the thread the window and the Host live on.
 *
 * Whether there is anything to show depends on an identity the C side looks for: a packaged
 * application has one, an unpackaged one has one when a Start menu shortcut carries it, and a
 * run with neither is told notifications are unsupported rather than posting into nothing.
 */
internal class Win32Notifications : NotificationPlatform {
    private var reports: NotificationReports? = null

    override fun start(reports: NotificationReports) {
        this.reports = reports
        val state = notifyStart()
        if (state != FINDING_OUT) reports.permission(permissionOf(state))
    }

    override fun requestPermission() = notifyRequestPermission()

    override fun refreshPermission() = notifyRefreshPermission()

    override fun post(notification: PlatformNotification) = memScoped {
        // `cstr` encodes UTF-8, which is what the C side reads: a code page would lose most
        // of what a notification says.
        notifyPost(
            notification.key.cstr.ptr,
            notification.title.cstr.ptr,
            notification.body.cstr.ptr,
            notification.channel.cstr.ptr,
            notification.action1.cstr.ptr,
            notification.action2.cstr.ptr,
            if (notification.importance == NotificationImportance.Urgent) 1 else 0,
        )
    }

    override fun withdraw(key: String) = memScoped { notifyWithdraw(key.cstr.ptr) }

    override fun processEnding() = notifyWithdrawAll()

    override fun pump() {
        val reports = reports ?: return
        memScoped {
            val buffer = allocArray<ByteVar>(KEY_BYTES)
            val value = alloc<IntVar>()
            while (true) {
                when (notifyNextEvent(buffer, KEY_BYTES, value.ptr)) {
                    EVENT_ACTIVATED -> {
                        var length = 0
                        while (length < KEY_BYTES && buffer[length] != 0.toByte()) length++
                        val key = if (length == 0) "" else buffer.readBytes(length).decodeToString()
                        reports.activated(key, value.value)
                    }
                    EVENT_PERMISSION -> reports.permission(permissionOf(value.value))
                    else -> return@memScoped
                }
            }
        }
    }

    private fun permissionOf(state: Int): NotificationPermission = when (state) {
        1 -> NotificationPermission.NotDetermined
        2 -> NotificationPermission.Granted
        3 -> NotificationPermission.Denied
        else -> NotificationPermission.Unsupported
    }
}
