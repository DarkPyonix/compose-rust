@file:JvmName("NativeTextScale")

package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.clampZoomLevel
import org.graalvm.nativeimage.PinnedObject
import org.graalvm.nativeimage.StackValue
import org.graalvm.nativeimage.c.function.CFunction
import org.graalvm.nativeimage.c.type.CCharPointer
import org.graalvm.nativeimage.c.type.CIntPointer

// The reader's text size, as the windows of the native image ask the platform for it, and
// where each of them keeps the application's zoom level between runs.
//
// Beside the windows and for the same reason: these name GraalVM types, so a development
// run on a JVM never loads them. Every C file of a window answers for all of these symbols,
// because only one of those files is linked into an image and the Kotlin is the same on
// every platform; a platform where a question has no meaning answers it with nothing.

@CFunction("dxc_native_text_scale")
private external fun nativeTextScale(): Float

@CFunction("dxc_native_text_settings_serial")
private external fun nativeTextSettingsSerial(): Int

@CFunction("dxc_native_text_settings")
private external fun nativeTextSettings(which: Int, out: CCharPointer?, capacity: Int): Int

@CFunction("dxc_native_zoom_level_load")
private external fun nativeZoomLevelLoad(out: CIntPointer?): Int

@CFunction("dxc_native_zoom_level_store")
private external fun nativeZoomLevelStore(level: Int)

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

/**
 * Where the AppKit window keeps the zoom level: the user defaults, which are already one
 * domain per application. Reached through the window's C, which is where AppKit is.
 */
fun appKitZoomLevelStore(): ZoomLevelStore = object : ZoomLevelStore {
    override fun load(): Int? {
        val out = StackValue.get<CIntPointer>(4)
        return if (nativeZoomLevelLoad(out) != 0) clampZoomLevel(out.read()) else null
    }

    override fun save(level: Int) = nativeZoomLevelStore(clampZoomLevel(level))
}

/**
 * Where the Win32 and X11 windows keep the zoom level: a file in the application's own
 * configuration directory, named after its executable.
 */
fun fileZoomLevelStore(layout: ConfigLayout): ZoomLevelStore {
    val executable = try {
        ProcessHandle.current().info().command().orElse(null)
    } catch (_: Exception) {
        // A platform that cannot say which program this is: the shared name below.
        null
    }
    val path = zoomLevelPath(layout, applicationIdentity(executable), System::getenv)
        ?: return ZoomLevelStore.None
    return ZoomLevelFile(
        path,
        read = { file ->
            try {
                java.io.File(file).takeIf { it.isFile }?.readText()
            } catch (_: java.io.IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        },
        write = { file, text ->
            try {
                val target = java.io.File(file)
                target.parentFile?.mkdirs()
                target.writeText(text)
                true
            } catch (_: java.io.IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        },
    )
}
