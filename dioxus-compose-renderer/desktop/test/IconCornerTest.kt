package dioxus.compose.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathMeasure
import dioxus.compose.foundation.turnedPath
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How an icon's corners are drawn.
 *
 * Every glyph in the set is a run of points, and every run used to be drawn one pair at a
 * time, so a corner was two stroke ends laid over each other and the design system's join
 * applied to nothing at all. The rounded sets do not join their corners anyway: they turn
 * the line through an arc a good deal wider than the stroke, which no join can say.
 */
class IconCornerTest {
    private fun length(path: androidx.compose.ui.graphics.Path): Float {
        val measure = PathMeasure()
        measure.setPath(path, false)
        return measure.length
    }

    /** A right angle, as an open run: two hundred long before anything is turned. */
    private val elbow = listOf(Offset(0f, 0f), Offset(SIDE, 0f), Offset(SIDE, SIDE))

    /** The same angle four times over, as a closed run whose last point repeats its first. */
    private val square = listOf(
        Offset(0f, 0f),
        Offset(SIDE, 0f),
        Offset(SIDE, SIDE),
        Offset(0f, SIDE),
        Offset(0f, 0f),
    )

    @Test
    fun fr14_a_corner_is_met_where_the_system_asks_for_no_turn() {
        assertTrue(
            abs(length(turnedPath(elbow, 0f)) - 2f * SIDE) < TOLERANCE,
            "a run with no turn is not the length of its own segments",
        )
        assertTrue(
            abs(length(turnedPath(square, 0f)) - 4f * SIDE) < TOLERANCE,
            "a closed run with no turn is not the length of its own segments",
        )
    }

    /**
     * And where it does ask, every corner is turned, including the one the run begins and
     * ends on.
     *
     * Left as it came, that corner stayed square while the other three were round, which is
     * a good deal more obviously wrong than four square corners. The test is arithmetic
     * rather than a picture: cutting a corner shortens the path by a fixed amount, so a
     * closed square has to lose exactly four of them.
     */
    @Test
    fun fr14_every_corner_of_a_closed_glyph_is_turned_including_its_seam() {
        val perCorner = 2f * SIDE - length(turnedPath(elbow, TURN))
        assertTrue(perCorner > 0f, "turning a corner did not shorten the run at all")
        val lost = 4f * SIDE - length(turnedPath(square, TURN))
        assertTrue(
            abs(lost - 4f * perCorner) < TOLERANCE,
            "a closed square lost $lost where four turned corners lose ${4f * perCorner}, " +
                "so the corner it begins and ends on was left square",
        )
    }

    private companion object {
        const val SIDE = 100f
        const val TURN = 12f
        const val TOLERANCE = 1f
    }
}
