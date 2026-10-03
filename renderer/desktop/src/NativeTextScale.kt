@file:JvmName("NativeTextScale")

package dev.darkpyonix.composerust.ui.platform

import org.graalvm.nativeimage.PinnedObject
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer

// The reader's text size, as the windows of the native image ask the platform for it.
//
// Beside the windows and for the same reason: these name GraalVM types, so a development
// run on a JVM never loads them. Every C file of a window answers for all three symbols,
// because only one of those files is linked into an image and the Kotlin is the same on
// every platform; a platform where a question has no meaning answers it with nothing.

@CFunction("dxc_native_text_scale")
private external fun nativeTextScale(): Float

@CFunction("dxc_native_text_settings_serial")
private external fun nativeTextSettingsSerial(): Int

@CFunction("dxc_native_text_settings")
private external fun nativeTextSettings(which: Int, out: CCharPointer?, capacity: Int): Int

/**
 * The reader's text size on Windows: Settings, Accessibility, Text size.
 *
 * Read through the Windows Runtime's `UISettings.TextScaleFactor` by the window's C, which
 * also listens for `TextScaleFactorChanged` and keeps the newest answer, so asking once a
 * frame costs a call and a load.
 */
fun windowsTextScale(): Float = nativeTextScale()

/**
 * The reader's text size on Linux, from the desktop's settings as an X client sees them.
 *
 * The C keeps an X connection of its own for this and counts the property changes that
 * matter; the bytes it hands back are read by the same code the Kotlin/Native window uses.
 */
fun x11TextScale(): () -> Float = linuxTextScale(
    LinuxTextSettings(
        serial = ::nativeTextSettingsSerial,
        xsettings = { x11Property(TEXT_SETTINGS_XSETTINGS) },
        resources = { x11Property(TEXT_SETTINGS_RESOURCES)?.decodeToString() },
        readFile = { path ->
            try {
                java.io.File(path).takeIf { it.isFile }?.readText()
            } catch (_: java.io.IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        },
        environment = System::getenv,
    ),
)

/**
 * One of the two properties the C reads, or null where it is not there.
 *
 * Asked twice when the first buffer was too small. The answer is the property's whole
 * length, so the second ask is the right size. A property that grew again between the two
 * is left unread rather than read in part, and the change that grew it is counted, so the
 * next frame asks again.
 */
private fun x11Property(which: Int): ByteArray? {
    var buffer = ByteArray(PROPERTY_GUESS)
    repeat(2) {
        val pinned = PinnedObject.create(buffer)
        val length = try {
            nativeTextSettings(which, pinned.addressOfArrayElement(0), buffer.size)
        } finally {
            pinned.close()
        }
        if (length < 0) return null
        if (length <= buffer.size) return buffer.copyOf(length)
        buffer = ByteArray(length)
    }
    return null
}

private const val TEXT_SETTINGS_XSETTINGS = 0
private const val TEXT_SETTINGS_RESOURCES = 1

/** Enough for a desktop's whole XSETTINGS, which is a few kilobytes. */
private const val PROPERTY_GUESS = 16 * 1024
