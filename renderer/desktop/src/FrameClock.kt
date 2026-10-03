package dev.darkpyonix.composerust.ui.platform

import kotlin.time.TimeSource

/**
 * The time a frame is drawn at, read from a monotonic clock.
 *
 * What a scene is handed with each frame is a time, not a frame number. Compose's frame
 * clock passes it to every animation and the Host is told it as the frame's time, so a
 * value that advanced by a fixed step per painted frame ran every animation at the rate
 * the screen happened to refresh: on a 120 Hz display a fixed 16 ms step is twice the time
 * that really passed, and at 144 Hz more than that.
 *
 * Counted from when the clock was made, so the numbers start small. Monotonic, so a change
 * of the wall clock while the window is open neither stops an animation nor skips it.
 * Kotlin's monotonic source is `System.nanoTime` on the JVM and the platform's monotonic
 * clock on native, which is the clock the other desktop windows already read.
 */
class FrameClock(source: TimeSource = TimeSource.Monotonic) {

    private val opened = source.markNow()

    /** How long the window has been open, in nanoseconds, at the moment of asking. */
    fun frameTimeNanos(): Long = opened.elapsedNow().inWholeNanoseconds
}
