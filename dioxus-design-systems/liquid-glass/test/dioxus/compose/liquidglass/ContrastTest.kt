package dioxus.compose.liquidglass

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The colour arithmetic the fallback guarantee rests on.
 *
 * If these are wrong then every contrast claim in this module is wrong in the same
 * direction and nothing downstream would notice, which is why they are checked against
 * the published WCAG numbers rather than against themselves.
 */
class ContrastTest {

    @Test
    fun fr14_relative_luminance_matches_the_wcag_endpoints() {
        assertEquals(0f, relativeLuminance(Color.Black), 1e-6f)
        assertEquals(1f, relativeLuminance(Color.White), 1e-6f)
        // The sRGB primaries carry the coefficients in the formula.
        assertEquals(0.2126f, relativeLuminance(Color.Red), 1e-4f)
        assertEquals(0.7152f, relativeLuminance(Color.Green), 1e-4f)
        assertEquals(0.0722f, relativeLuminance(Color.Blue), 1e-4f)
    }

    @Test
    fun fr14_contrast_runs_from_one_to_twenty_one_and_does_not_care_about_order() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 1e-4f)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 1e-4f)
        assertEquals(1f, contrastRatio(Color.Gray, Color.Gray), 1e-6f)
    }

    @Test
    fun fr14_compositing_a_translucent_colour_lands_between_the_two_and_is_opaque() {
        val result = compositeOver(Color.Black.copy(alpha = 0.5f), Color.White)
        assertEquals(1f, result.alpha)
        // Compose stores an sRGB colour with eight bits per channel, so a half way mix
        // lands on 127/255 rather than on exactly 0.5. One step of that quantisation is
        // the tightest any assertion about a channel can honestly be.
        assertEquals(0.5f, result.red, 1f / 255f)
        assertTrue(abs(relativeLuminance(result) - relativeLuminance(Color.White)) > 0.1f)
    }

    @Test
    fun fr14_compositing_at_the_extremes_returns_one_side_or_the_other() {
        assertEquals(Color.White, compositeOver(Color.Transparent, Color.White))
        assertEquals(
            Color.Black,
            compositeOver(Color.Black, Color.White),
            "a fully opaque top hides what is under it",
        )
    }

    @Test
    fun fr14_a_colour_that_already_meets_the_ratio_is_returned_untouched() {
        val pale = Color(0xFFF7F7FA)
        assertEquals(pale, ensureContrast(pale, Color.Black, 4.5f))
    }

    @Test
    fun fr14_a_colour_that_does_not_meet_the_ratio_is_moved_until_it_does() {
        // 0x6A reads at about 3.9 against black, so it genuinely fails the 4.5 body
        // minimum and the function has to move it. 0x777777 does not: it already reads at
        // about 4.7, and asking it to move was asking for a change that is not required.
        val tooClose = Color(0xFF6A6A6A)
        val fixed = ensureContrast(tooClose, Color.Black, 4.5f)
        assertTrue(contrastRatio(fixed, Color.Black) >= 4.5f)
        assertTrue(
            relativeLuminance(fixed) > relativeLuminance(tooClose),
            "moving towards black would have had to pass through the content colour first",
        )
    }

    @Test
    fun fr14_the_move_is_the_smallest_one_that_satisfies_the_requirement() {
        val start = Color(0xFF6A6A6A)
        val fixed = ensureContrast(start, Color.Black, 4.5f)
        // Anything materially closer to the original fails the ratio, so the search did
        // not overshoot into a colour the design never asked for.
        val undershoot = Color(
            red = fixed.red - (fixed.red - start.red) * 0.2f,
            green = fixed.green - (fixed.green - start.green) * 0.2f,
            blue = fixed.blue - (fixed.blue - start.blue) * 0.2f,
        )
        assertTrue(contrastRatio(undershoot, Color.Black) < 4.5f)
    }

    @Test
    fun fr14_an_impossible_ratio_returns_the_best_that_colour_can_do() {
        // Nothing reaches 21 against mid grey, so the answer is the far extreme rather
        // than a failure or the unchanged input.
        val best = ensureContrast(Color(0xFF808080), Color.Gray, 21f)
        assertTrue(
            best == Color.White || best == Color.Black,
            "the extreme is the honest answer when the requirement cannot be met: $best",
        )
    }

    @Test
    fun fr14_ensure_contrast_returns_an_opaque_colour_so_it_can_be_trusted_as_a_fallback() {
        assertEquals(1f, ensureContrast(Color(0x80777777), Color.Black, 4.5f).alpha)
    }

    /**
     * A surface exactly the colour of its own text, and one a shade the wrong side of it.
     *
     * Neither has an away direction that can be read off which colour is lighter: the
     * first has none at all, and the second has one that runs out of room. Deducing the
     * direction returned white for a white surface carrying white text, which is not a
     * fallback, it is the failure the fallback exists to prevent.
     */
    @Test
    fun fr14_a_surface_the_colour_of_its_own_text_still_reaches_the_ratio() {
        listOf(
            Color.White to Color.White,
            Color.Black to Color.Black,
            Color(0xFFFAFAFA) to Color(0xFFF4F4F4),
            Color(0xFF050505) to Color(0xFF0B0B0B),
        ).forEach { (surface, content) ->
            val fixed = ensureContrast(surface, content, 4.5f)
            assertTrue(
                contrastRatio(fixed, content) >= 4.5f,
                "a $surface surface carrying $content text fell back to $fixed, which reads " +
                    "at ${contrastRatio(fixed, content)}",
            )
        }
    }
}
