package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.runtime.ZoomLevelStore
import dev.darkpyonix.composerust.runtime.clampZoomLevel

// Where Windows and Linux keep an application's zoom level between runs: a small file in
// the application's own configuration directory, `%APPDATA%\<app>\` on Windows and
// `$XDG_CONFIG_HOME/<app>/` on Linux. macOS keeps it in the user defaults instead, which
// are already per application.
//
// Shared by the native image's windows and the Kotlin/Native Linux window through a
// symlink. What reads and writes a file differs between the two, so that is handed in.

/**
 * A zoom level kept in one file, as the decimal number and nothing else.
 *
 * A file that is missing, unreadable or holds anything but a number is no level at all, and
 * the window starts at zero: a damaged setting is not worth refusing to open over.
 *
 * @param write Writes the whole file, making its directory where it is missing. Answers
 *   whether it could; a level that could not be saved is still the level on screen.
 */
class ZoomLevelFile(
    private val path: String,
    private val read: (String) -> String?,
    private val write: (String, String) -> Boolean,
) : ZoomLevelStore {

    override fun load(): Int? = read(path)?.trim()?.toIntOrNull()?.let(::clampZoomLevel)

    override fun save(level: Int) {
        write(path, clampZoomLevel(level).toString())
    }
}

/** Which of the two layouts a configuration directory follows. */
enum class ConfigLayout { Windows, Linux }

/**
 * The file an application's zoom level is kept in, or null where the environment names no
 * configuration directory at all.
 */
fun zoomLevelPath(layout: ConfigLayout, application: String, environment: (String) -> String?): String? =
    when (layout) {
        ConfigLayout.Windows ->
            environment("APPDATA")?.takeIf { it.isNotEmpty() }?.let { "$it\\$application\\$ZOOM_FILE" }
        ConfigLayout.Linux -> {
            val config = environment("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") }
                ?: environment("HOME")?.takeIf { it.isNotEmpty() }?.let { "$it/.config" }
            config?.let { "$it/$application/$ZOOM_FILE" }
        }
    }

/**
 * The name an application is kept under: its executable's file name, without a `.exe`.
 *
 * The executable is the application's own, since the renderer is a library linked into it.
 * A name that cannot be found, or that could step outside the directory it names, falls
 * back to the library's own name, so every such application shares one level rather than
 * none keeping any.
 */
fun applicationIdentity(executable: String?): String {
    val name = executable
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.removeSuffix(".exe")
        ?.removeSuffix(".EXE")
        ?.trim()
    return if (name.isNullOrEmpty() || name == "." || name == "..") FALLBACK_IDENTITY else name
}

private const val ZOOM_FILE = "zoom-level"
private const val FALLBACK_IDENTITY = "compose-rust"
