package dioxus.compose.test

import dioxus.compose.design.ComponentRules
import dioxus.compose.design.rulesFor
import dioxus.compose.protocol.DesignSystem
import dioxus.compose.protocol.MotionRole
import dioxus.compose.ui.node.platformReducedMotion
import dioxus.compose.ui.readAnswer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How importance becomes a length.
 *
 * A screen says how much a change matters. Every design system answers with its own
 * number, and the acceptance this defends is that those numbers differ: one table copied
 * seven times would be the same failure as drawing one date picker three times.
 */
class MotionTest {

    @AfterTest
    fun putTheSettingBack() {
        platformReducedMotion = { false }
    }

    private fun allSystems(): List<ComponentRules> = DesignSystem.entries.map { rulesFor(it) }

    @Test
    fun fr24_each_design_system_answers_standard_with_its_own_length() {
        val lengths = allSystems().map { it.motion.millis(MotionRole.Standard) }
        assertEquals(lengths.size, lengths.toSet().size, "two systems gave the same length")
    }

    @Test
    fun fr24_importance_orders_the_lengths_within_a_system() {
        for (rules in allSystems()) {
            val motion = rules.motion
            assertTrue(motion.millis(MotionRole.Instant) == 0)
            assertTrue(motion.millis(MotionRole.Quick) < motion.millis(MotionRole.Standard))
            assertTrue(motion.millis(MotionRole.Standard) < motion.millis(MotionRole.Slow))
            assertTrue(motion.millis(MotionRole.Emphasized) > motion.millis(MotionRole.Quick))
        }
    }

    @Test
    fun fr24_a_system_asked_to_hold_still_answers_every_role_with_no_run() {
        platformReducedMotion = { true }
        for (rules in allSystems()) {
            for (role in MotionRole.entries) {
                assertEquals(
                    0,
                    rules.motion.millis(
                        if (platformReducedMotion()) MotionRole.Instant else role,
                    ),
                )
            }
        }
    }

    @Test
    fun fr24_a_platform_answer_is_read_the_way_that_platform_phrases_it() {
        // macOS and Windows say whether motion is reduced; GNOME says whether animations
        // are on, which is the same question the other way round.
        assertTrue(readAnswer("1", inverted = false))
        assertTrue(!readAnswer("0", inverted = false))
        assertTrue(readAnswer("    MinAnimate    REG_SZ    0", inverted = true))
        assertTrue(!readAnswer("true", inverted = true))
        assertTrue(readAnswer("false", inverted = true))
        // A machine that cannot be asked has not asked for anything.
        assertTrue(!readAnswer("command not found", inverted = false))
        assertTrue(!readAnswer("", inverted = true))
    }
}
