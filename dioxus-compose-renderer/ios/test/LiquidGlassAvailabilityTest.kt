package dioxus.compose.ui.platform

import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The gate that decides whether the renderer stands up UIKit chrome or draws its own.
 *
 * It runs on whichever simulator runtime the test was launched against, so it asserts
 * agreement with that runtime's own version rather than a fixed answer. Run it on an iOS 18
 * runtime and it is the pre-26 case; run it on 26 and it is the other one. Both are real.
 */
class LiquidGlassAvailabilityTest {
    private val major: Long
        get() = NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion }

    @Test
    fun fr14_9_the_gate_answers_for_the_major_version_this_is_running_on() {
        assertEquals(
            major >= LIQUID_GLASS_IOS_MAJOR.toLong(),
            systemDrawsLiquidGlass(),
            "this runtime reports major version $major",
        )
    }

    @Test
    fun fr14_9_the_gate_compares_and_does_not_always_answer_the_same_thing() {
        assertTrue(systemIsAtLeast(1), "every iOS is at least 1")
        assertFalse(systemIsAtLeast(999), "no iOS is 999")
    }

    /**
     * The threshold is 26 and not 25 or 27.
     *
     * Worth its own line because the whole of the decision is that one number: one off in
     * either direction either puts UIKit chrome in front of a system that cannot glass it,
     * or withholds it from the first one that can.
     */
    @Test
    fun fr14_9_the_threshold_is_the_first_ios_that_draws_glass() {
        assertEquals(26, LIQUID_GLASS_IOS_MAJOR)
        assertTrue(systemIsAtLeast(LIQUID_GLASS_IOS_MAJOR - 1) || !systemDrawsLiquidGlass())
    }
}
