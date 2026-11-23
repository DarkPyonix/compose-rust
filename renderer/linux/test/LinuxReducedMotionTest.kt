package dev.darkpyonix.composerust.ui.platform

import dev.darkpyonix.composerust.protocol.ReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotion
import dev.darkpyonix.composerust.ui.node.platformReducedMotionSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxReducedMotionTest {
    @Test
    fun fr41_2_fr51_linux_reduced_motion_wires_both_hooks_and_caches_queries() {
        val previousSetting = platformReducedMotionSetting
        val previousMotion = platformReducedMotion
        try {
            var current = ReducedMotion.On
            var queries = 0
            installLinuxReducedMotion(ask = {
                queries++
                current
            })
            assertEquals(ReducedMotion.On, platformReducedMotionSetting())
            assertEquals(1, queries)
            assertTrue(
                platformReducedMotion(),
                "reduced motion hook must report true when the desktop setting is on",
            )
            assertEquals(
                1,
                queries,
                "boolean reduced motion hook must reuse the cached setting without spawning a new query",
            )
            current = ReducedMotion.Off
            assertEquals(ReducedMotion.Off, platformReducedMotionSetting())
            assertEquals(2, queries)
            assertFalse(
                platformReducedMotion(),
                "reduced motion hook must update when the setting is re-queried",
            )
            current = ReducedMotion.Unknown
            assertEquals(ReducedMotion.Unknown, platformReducedMotionSetting())
            assertFalse(
                platformReducedMotion(),
                "unknown reduced motion setting must map to false",
            )
        } finally {
            platformReducedMotionSetting = previousSetting
            platformReducedMotion = previousMotion
        }
    }
}
