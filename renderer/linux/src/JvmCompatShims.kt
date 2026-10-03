@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.lang

import kotlinx.cinterop.toKString
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

/**
 * The handful of JVM names the interpreter sources use, supplied for Kotlin/Native.
 *
 * The interpreter is written once, for the desktop native image, and compiled unchanged here
 * through the symlinks in `src/shared/`. It is ordinary Compose code apart from these names,
 * which Kotlin/Native has no equivalent for.
 *
 * They are declared in `java.lang`, the same trick the `java.nio` shims in this module use. A
 * shared source then writes `import java.lang.System` once and gets the real JDK class on the
 * desktop target and this one here, with no expect/actual scaffolding that only one target
 * would ever need.
 *
 * A copy of the iOS shim rather than the shim itself, because two of the three answers differ
 * and both matter:
 *
 * - `os.name` answers `linux`, which is what the host platform detection reads to choose
 *   between the GNOME, KDE and Deepin design systems.
 * - `getenv` is the real one. It has to be: which of those three a session is running is read
 *   from `XDG_CURRENT_DESKTOP` and `DESKTOP_SESSION`, and the iOS shim answers null to
 *   everything because a phone has no environment worth reading.
 * - `System.err.println` goes to the process's standard error, flushed, so a protocol error
 *   reaches a terminal rather than sitting in a buffer while the window keeps running.
 * - `InterruptedException` exists so the catch clauses that re-throw it still compile. Nothing
 *   here throws it: there is no Thread.interrupt.
 */
object System {
    val err: ErrorStream = ErrorStream()

    fun getProperty(name: String): String? = when (name) {
        "os.name" -> "linux"
        else -> null
    }

    fun getenv(name: String): String? = platform.posix.getenv(name)?.toKString()

    class ErrorStream internal constructor() {
        fun println(line: String) {
            fputs(line + "\n", stderr)
            // Flushed rather than left to the buffer. Standard error is unbuffered on a
            // terminal and block buffered everywhere else, and a renderer whose window keeps
            // running is a process that may not flush for minutes: the one line saying what
            // went wrong would arrive long after whoever is reading gave up.
            fflush(stderr)
        }
    }
}

class InterruptedException(message: String? = null) : Exception(message)
