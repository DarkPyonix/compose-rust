package java.lang

import platform.Foundation.NSLog

/**
 * The handful of JVM names the interpreter sources use, supplied for Kotlin/Native.
 *
 * The interpreter is written once, for the desktop native image, and compiled unchanged
 * for iOS through the symlinks in `src/shared/`. It is ordinary Compose code apart from
 * these names, which Kotlin/Native has no equivalent for.
 *
 * They are declared in `java.lang`, the same trick the `java.nio` shims in this module
 * use. A shared source then writes `import java.lang.System` once and gets the real JDK
 * class on the desktop target and this one here, with no expect/actual scaffolding that
 * only one target would ever need.
 *
 * Both are real implementations rather than stubs:
 * - `System.getProperty("os.name")` answers `ios`, which is what the host platform
 *   detection reads to choose the Cupertino design system here. `getenv` answers null,
 *   because iOS has no meaningful environment to read.
 * - `System.err.println` goes to NSLog, so a protocol error reaches the device console.
 * - `InterruptedException` exists so the catch clauses that re-throw it still compile.
 *   Nothing on iOS throws it: there is no Thread.interrupt.
 */
object System {
    val err: ErrorStream = ErrorStream()

    fun getProperty(name: String): String? = when (name) {
        "os.name" -> "ios"
        else -> null
    }

    fun getenv(name: String): String? = null

    class ErrorStream internal constructor() {
        fun println(line: String) = NSLog("%s", line)
    }
}

class InterruptedException(message: String? = null) : Exception(message)
