@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package java.lang

import kotlinx.cinterop.toKString
import platform.Foundation.NSLog
import platform.posix.getenv

/**
 * The few things the shared renderer asks of `java.lang.System`, answered for macOS.
 *
 * The renderer is one body of code compiled for every platform, and some of it reads its
 * surroundings through the names the JVM uses. This is not a JVM, so those names are
 * answered here.
 *
 * Not iOS's copy, which this used to be a symlink to. That one answers `ios` to every
 * question about the platform and null to every question about the environment, which is
 * right on a phone and wrong here: this is a desktop, it has an environment, and code that
 * asks whether transparency should be reduced or which desktop session is running was
 * being told nothing at all. It went unnoticed because being told nothing looks exactly
 * like being told no.
 */
object System {
    val err: ErrorStream = ErrorStream()

    fun getProperty(name: String): String? = when (name) {
        // What the JVM says on this platform, because that is what the code reading it
        // was written against.
        "os.name" -> "Mac OS X"
        else -> null
    }

    fun getenv(name: String): String? = platform.posix.getenv(name)?.toKString()

    class ErrorStream internal constructor() {
        fun println(line: String) = NSLog("%s", line)
    }
}

class InterruptedException(message: String? = null) : Exception(message)
