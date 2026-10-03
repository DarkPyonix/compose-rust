@file:JvmName("NativeDesktopNotificationCalls")

package dioxus.compose.ui.platform

import dioxus.compose.protocol.NotificationImportance
import dioxus.compose.protocol.NotificationPermission
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.UnmanagedMemory
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer

// The notification centres of the macOS and Windows native images, reached through the C
// beside them: `c/macos_notifications.m` and `c/win32_notifications.c`. Linux answers the
// same names with stubs, because there the renderer speaks D-Bus from Kotlin instead.
//
// In its own file and kept out of the renderer's shared sources for the reason the windows
// beside it are: a development run on a JVM must never load a GraalVM type, and this is
// only ever installed by the native image's own entry point.

@CFunction("dxc_notify_start")
private external fun notifyStart(): Int

@CFunction("dxc_notify_request_permission")
private external fun notifyRequestPermission()

@CFunction("dxc_notify_refresh_permission")
private external fun notifyRefreshPermission()

@CFunction("dxc_notify_post")
private external fun notifyPost(
    key: CCharPointer?,
    title: CCharPointer?,
    body: CCharPointer?,
    channel: CCharPointer?,
    action1: CCharPointer?,
    action2: CCharPointer?,
    urgent: Int,
)

@CFunction("dxc_notify_withdraw")
private external fun notifyWithdraw(key: CCharPointer?)

@CFunction("dxc_notify_withdraw_all")
private external fun notifyWithdrawAll()

@CFunction("dxc_notify_next_event")
private external fun notifyNextEvent(key: CCharPointer?, capacity: Int, value: CIntPointer?): Int

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
 * A notification centre whose answers wait in C until this side asks for them.
 *
 * The C side asks the renderer for a frame whenever it queues something, which is the same
 * thread-safe request a Host worker makes, and the frame reaches [pump] on the UI thread.
 */
internal class NativeDesktopNotifications : NotificationPlatform {
    private var reports: NotificationReports? = null

    override fun start(reports: NotificationReports) {
        this.reports = reports
        val state = notifyStart()
        if (state != FINDING_OUT) reports.permission(permissionOf(state))
    }

    override fun requestPermission() = notifyRequestPermission()

    override fun refreshPermission() = notifyRefreshPermission()

    override fun post(notification: PlatformNotification) {
        val texts = listOf(
            notification.key,
            notification.title,
            notification.body,
            notification.channel,
            notification.action1,
            notification.action2,
        ).map { it.toByteArray(Charsets.UTF_8) }
        val offsets = offsetsOf(texts)
        // Pointers are read and used inside this one method and carried nowhere else: a
        // native image accepts a word in straight-line code and not in a field or a lambda.
        val block: CCharPointer = UnmanagedMemory.malloc(offsets.last())
        try {
            for (index in texts.indices) {
                val bytes = texts[index]
                val start = offsets[index]
                for (position in bytes.indices) block.write(start + position, bytes[position])
                block.write(start + bytes.size, 0)
            }
            notifyPost(
                block.addressOf(offsets[0]),
                block.addressOf(offsets[1]),
                block.addressOf(offsets[2]),
                block.addressOf(offsets[3]),
                block.addressOf(offsets[4]),
                block.addressOf(offsets[5]),
                if (notification.importance == NotificationImportance.Urgent) 1 else 0,
            )
        } finally {
            UnmanagedMemory.free(block)
        }
    }

    override fun withdraw(key: String) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        val block: CCharPointer = UnmanagedMemory.malloc(bytes.size + 1)
        try {
            for (position in bytes.indices) block.write(position, bytes[position])
            block.write(bytes.size, 0)
            notifyWithdraw(block)
        } finally {
            UnmanagedMemory.free(block)
        }
    }

    override fun processEnding() = notifyWithdrawAll()

    override fun pump() {
        val reports = reports ?: return
        val buffer = StackValue.get<CCharPointer>(KEY_BYTES)
        val value = StackValue.get<CIntPointer>(4)
        while (true) {
            when (notifyNextEvent(buffer, KEY_BYTES, value)) {
                EVENT_ACTIVATED -> {
                    var length = 0
                    while (length < KEY_BYTES && buffer.read(length) != 0.toByte()) length++
                    val bytes = ByteArray(length)
                    for (index in 0 until length) bytes[index] = buffer.read(index)
                    reports.activated(String(bytes, Charsets.UTF_8), value.read())
                }
                EVENT_PERMISSION -> reports.permission(permissionOf(value.read()))
                else -> return
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

/**
 * Where each of several terminated strings starts in one block, and, last, how long the
 * block is.
 *
 * Encoded by this side rather than by the conversion GraalVM offers, because that one uses
 * the platform's default character set, which on Windows is a code page that cannot carry
 * most of what a notification says.
 */
private fun offsetsOf(texts: List<ByteArray>): IntArray {
    val offsets = IntArray(texts.size + 1)
    for (index in texts.indices) offsets[index + 1] = offsets[index] + texts[index].size + 1
    return offsets
}
