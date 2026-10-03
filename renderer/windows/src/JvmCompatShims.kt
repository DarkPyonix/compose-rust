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
 * through the symlinks in `src/shared/`. These names are declared in `java.lang` so that a
 * shared source writing `import java.lang.System` gets the JDK class on the desktop target and
 * this one here.
 *
 * The Linux module's, with one answer changed:
 *
 * - `os.name` answers `windows`, which is what the host platform detection reads to choose
 *   Fluent.
 * - `getenv` is the real one, so a variable set to report frames or input is honoured.
 * - `System.err.println` goes to the process's standard error, flushed, so a protocol error
 *   reaches a terminal rather than a buffer.
 * - `InterruptedException` exists so the catch clauses that re-throw it still compile.
 */
object System {
    val err: ErrorStream = ErrorStream()

    fun getProperty(name: String): String? = when (name) {
        "os.name" -> "windows"
        else -> null
    }

    fun getenv(name: String): String? = platform.posix.getenv(name)?.toKString()

    class ErrorStream internal constructor() {
        fun println(line: String) {
            fputs(line + "\n", stderr)
            fflush(stderr)
        }
    }
}

class InterruptedException(message: String? = null) : Exception(message)
