package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.ReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting

// The Windows window's reduced-motion setting: Windows' "Show animations in
// Windows", SPI_GETCLIENTAREAANIMATION. Shared with the Kotlin/Native Windows renderer through
// a symlink, which hands in the system call; compiled on the JVM too, where it is tested.

/**
 * The protocol's setting for Windows' animation switch: animations off is motion reduced.
 * [animations] is null when the system could not be asked, which is said as Unknown.
 */
fun reducedMotionFromClientAreaAnimation(animations: Boolean?): ReducedMotion = when (animations) {
    true -> ReducedMotion.Off
    false -> ReducedMotion.On
    null -> ReducedMotion.Unknown
}

/**
 * Installs [read] (SPI_GETCLIENTAREAANIMATION; null when the call failed) as both of the
 * renderer's hooks.
 *
 * The setting the Host is told is read afresh each time it is asked: at start and whenever
 * the window comes back into use, which is when it may have changed. What the press feedback
 * and the motion roles read is that last answer, asked of the system once if nothing asked
 * yet, because the tree asks it for every node that declared motion. Unknown counts as not
 * reduced, as on the other desktops.
 */
fun installWindowsReducedMotion(read: () -> Boolean?) {
    var last: ReducedMotion? = null
    platformReducedMotionSetting = {
        reducedMotionFromClientAreaAnimation(read()).also { last = it }
    }
    platformReducedMotion = {
        (last ?: reducedMotionFromClientAreaAnimation(read()).also { last = it }) == ReducedMotion.On
    }
}
