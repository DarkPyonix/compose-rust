@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import org.thisisthepy.compose.window.SystemDarkMonitor
import org.thisisthepy.compose.window.windows.c.dxc_native_system_dark
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetEnvironmentVariableW
import platform.windows.GetModuleFileNameW
import platform.windows.HANDLE
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.WriteFile

// What the Windows window asks of the system besides drawing: whether the system is in dark
// mode, and where the application's zoom level is kept between runs.

/**
 * Whether Windows is in dark mode now: `AppsUseLightTheme` under the user's Personalize key,
 * read by the fork's window (`dxc_native_system_dark`, the same read its caption follows).
 * A check replaces it to turn the setting over without touching the machine's.
 */
internal var win32SystemDarkSource: () -> Boolean = { dxc_native_system_dark() != 0 }

/**
 * Whether the system is in dark mode, kept current while the composition lives.
 *
 * Windows announces a change of the setting with a WM_SETTINGCHANGE broadcast the window
 * answers in C (its caption) without telling Kotlin, so this reads the setting again every
 * [SYSTEM_DARK_POLL_MILLIS], as the desktop renderer does with the toolkit's query: a
 * registry read a second costs nothing and a change shows within a second. The loop ends
 * with the composition, so a window that closes leaves nothing waiting.
 */
@Composable
internal fun rememberWin32SystemDark(): State<Boolean> {
    val state = remember { mutableStateOf(win32SystemDarkSource()) }
    val monitor = remember { SystemDarkMonitor(read = { win32SystemDarkSource() }, onChange = { state.value = it }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SYSTEM_DARK_POLL_MILLIS)
            monitor.refresh()
        }
    }
    return state
}

/** How often the setting is read again. */
internal const val SYSTEM_DARK_POLL_MILLIS = 1_000L

/**
 * Where this application keeps its zoom level: `%APPDATA%\<executable name>\zoom-level`,
 * or nowhere when the environment names no `APPDATA`.
 */
internal fun windowsZoomLevelStore(): ZoomLevelStore {
    val path = zoomLevelPath(ConfigLayout.Windows, applicationIdentity(executablePath()), ::environmentVariable)
        ?: return ZoomLevelStore.None
    return ZoomLevelFile(path, read = ::readTextFile, write = ::writeTextFile)
}

/** An environment variable through the wide call, so a user name outside ASCII survives. */
private fun environmentVariable(name: String): String? = memScoped {
    val buffer = allocArray<UShortVar>(PATH_CHARS)
    val length = GetEnvironmentVariableW(name, buffer, PATH_CHARS.toUInt()).toInt()
    if (length <= 0 || length >= PATH_CHARS) null else buffer.toKStringFromUtf16()
}

/** The executable this renderer is linked into. */
private fun executablePath(): String? = memScoped {
    val buffer = allocArray<UShortVar>(PATH_CHARS)
    val length = GetModuleFileNameW(null, buffer, PATH_CHARS.toUInt()).toInt()
    if (length <= 0 || length >= PATH_CHARS) null else buffer.toKStringFromUtf16()
}

/** A handle CreateFileW gave, or null for INVALID_HANDLE_VALUE (-1) and null. */
private fun opened(handle: HANDLE?): HANDLE? = handle?.takeIf { it.toLong() != -1L }

/**
 * Writes a whole text file, making each missing directory above it. Answers whether the
 * file was written.
 */
private fun writeTextFile(path: String, text: String): Boolean {
    var slash = path.indexOf('\\', startIndex = 3)
    while (slash > 0) {
        // A directory that is already there answers with an error, which is the answer
        // wanted; one that cannot be made is found out when the file cannot be opened.
        CreateDirectoryW(path.substring(0, slash), null)
        slash = path.indexOf('\\', startIndex = slash + 1)
    }
    val file = opened(
        CreateFileW(path, GENERIC_WRITE.toUInt(), 0u, null, CREATE_ALWAYS.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null),
    ) ?: return false
    try {
        val bytes = text.encodeToByteArray()
        return memScoped {
            val buffer = allocArray<ByteVar>(bytes.size.coerceAtLeast(1))
            for (index in bytes.indices) buffer[index] = bytes[index]
            val written = alloc<UIntVar>()
            WriteFile(file, buffer, bytes.size.toUInt(), written.ptr, null) != 0 && written.value.toInt() == bytes.size
        }
    } finally {
        CloseHandle(file)
    }
}

/** A whole text file, or null where it cannot be read. */
private fun readTextFile(path: String): String? {
    val file = opened(
        CreateFileW(path, GENERIC_READ.toUInt(), FILE_SHARE_READ.toUInt(), null, OPEN_EXISTING.toUInt(), FILE_ATTRIBUTE_NORMAL.toUInt(), null),
    ) ?: return null
    try {
        val bytes = ArrayList<Byte>()
        memScoped {
            val chunk = allocArray<ByteVar>(FILE_CHUNK)
            val read = alloc<UIntVar>()
            while (ReadFile(file, chunk, FILE_CHUNK.toUInt(), read.ptr, null) != 0 && read.value > 0u) {
                bytes.addAll(chunk.readBytes(read.value.toInt()).toList())
            }
        }
        return bytes.toByteArray().decodeToString()
    } finally {
        CloseHandle(file)
    }
}

private const val PATH_CHARS = 32_768
private const val FILE_CHUNK = 4096
