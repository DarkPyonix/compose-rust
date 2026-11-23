package dev.darkpyonix.composerust.test

import dev.darkpyonix.composerust.protocol.ReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting
import dev.darkpyonix.composerust.ui.platform.installWindowsReducedMotion
import dev.darkpyonix.composerust.ui.platform.reducedMotionFromClientAreaAnimation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reduced motion on the Windows Kotlin/Native window: the setting is Windows' "Show
 * animations in Windows" (SPI_GETCLIENTAREAANIMATION). Turned off, motion is reduced: the
 * Host is told `On` (ReducedMotionChanged), and the press feedback and motion roles read
 * `platformReducedMotion` as true. Before this the Windows window installed neither hook, so
 * the Host always heard Unknown and nothing was ever reduced.
 */
class WindowsReducedMotionTest {
    @AfterTest
    fun putTheHooksBack() {
        platformReducedMotion = { false }
        platformReducedMotionSetting = { ReducedMotion.Unknown }
    }

    @Test
    fun fr41_2_windows_client_area_animation_is_the_reduced_motion_setting() {
        assertEquals(ReducedMotion.On, reducedMotionFromClientAreaAnimation(false), "animations off: motion reduced")
        assertEquals(ReducedMotion.Off, reducedMotionFromClientAreaAnimation(true), "animations on: not reduced")
        assertEquals(ReducedMotion.Unknown, reducedMotionFromClientAreaAnimation(null), "a failed read says it cannot say")
    }

    @Test
    fun fr51_windows_setting_reaches_the_host_and_the_motion_hooks() {
        var animations: Boolean? = false
        var reads = 0
        installWindowsReducedMotion { reads++; animations }

        assertEquals(ReducedMotion.On, platformReducedMotionSetting())
        assertTrue(platformReducedMotion(), "press feedback and motion roles must reduce while animations are off")
        val readsSoFar = reads
        // The tree asks for every node that declared motion; the system is not asked again for that.
        repeat(100) { platformReducedMotion() }
        assertEquals(readsSoFar, reads, "platformReducedMotion went to the system on every node")

        // The setting is asked again when the window comes back into use, and both follow it.
        animations = true
        assertEquals(ReducedMotion.Off, platformReducedMotionSetting())
        assertFalse(platformReducedMotion())

        animations = null
        assertEquals(ReducedMotion.Unknown, platformReducedMotionSetting())
        assertFalse(platformReducedMotion(), "unknown counts as not reduced")
    }
}
