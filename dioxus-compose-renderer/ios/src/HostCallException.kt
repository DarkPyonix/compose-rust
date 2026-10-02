package dioxus.compose.ui.platform

/**
 * A boundary call into the Host returned a non-zero status.
 *
 * Thrown rather than aborting: a Host that reports a protocol error is still a running
 * process, and the renderer's job is to surface the error, not to take the application
 * down with it.
 *
 * The desktop renderer declares the same type in NativeHostConnection.kt, which is
 * GraalVM only and therefore not shared with this target.
 */
class HostCallException(message: String) : RuntimeException(message)
