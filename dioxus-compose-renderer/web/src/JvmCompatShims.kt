@file:Suppress("PackageDirectoryMismatch")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package java.lang

/**
 * The handful of JVM names the interpreter sources use, supplied for Kotlin/Wasm.
 *
 * The interpreter is written once, for the desktop native image, and compiled unchanged for
 * the browser through the symlinks in `src/shared/`. It is ordinary Compose code apart from
 * these names, which Kotlin/Wasm has no equivalent for.
 *
 * They are declared in `java.lang`, the same trick the `java.nio` shims in this module use.
 * A shared source then writes `import java.lang.System` once and gets the real JDK class on
 * the desktop target and this one here, with no expect/actual scaffolding that only one
 * target would ever need. The iOS module has a file of the same name doing the same job.
 *
 * Both are real implementations rather than stubs:
 * - `System.getProperty("os.name")` answers `web`, which is what the host platform
 *   detection reads to choose the browser's design system. `getenv` answers null, because a
 *   browser tab has no environment to read.
 * - `System.err.println` goes to the console's error channel, so a protocol error is
 *   visible in the developer tools rather than swallowed.
 * - `InterruptedException` exists so the catch clauses that re-throw it still compile.
 *   Nothing in a browser throws it: there is no thread to interrupt.
 */
object System {
    val err: ErrorStream = ErrorStream()

    fun getProperty(name: String): String? = when (name) {
        "os.name" -> "web"
        else -> null
    }

    fun getenv(name: String): String? = null

    class ErrorStream internal constructor() {
        fun println(line: String) = consoleError(line)
    }
}

class InterruptedException(message: String? = null) : Exception(message)

private fun consoleError(line: String) {
    js("console.error(line)")
}
