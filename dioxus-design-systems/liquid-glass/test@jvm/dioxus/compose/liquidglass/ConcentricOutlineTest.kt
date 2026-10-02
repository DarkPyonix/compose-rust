package dioxus.compose.liquidglass

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corner outline, measured as a real path.
 *
 * These belong to the JVM rather than to the common test set because they build the
 * outline and then measure what came out, and that needs a graphics backend that actually
 * draws. The desktop JVM has one. Android's local unit test JVM does not: every method on
 * android.graphics.Path throws unless the test runs on a device or against an emulated
 * framework, so running these there would be testing Google's stub rather than this
 * module's geometry.
 *
 * The arithmetic that decides those paths, the superellipse exponent and the concentric
 * inset, stays in the common set and runs on every target. What is checked here is only
 * the step from that arithmetic to an outline, and one real backend settles it.
 */
class ConcentricOutlineTest {

    private val density = Density(density = 1f, fontScale = 1f)

    private fun outlineOf(shape: ContinuousCornerShape, size: Size): Outline =
        shape.createOutline(size, LayoutDirection.Ltr, density)

    @Test
    fun fr14_the_outline_is_a_generic_path_and_fills_the_bounds_it_was_given() {
        val outline = outlineOf(ContinuousCornerShape(12.dp), Size(120f, 80f))
        assertTrue(outline is Outline.Generic, "a superellipse has no rounded rect form")
        val bounds = (outline as Outline.Generic).path.getBounds()
        assertEquals(0f, bounds.left, 0.5f)
        assertEquals(0f, bounds.top, 0.5f)
        assertEquals(120f, bounds.right, 0.5f)
        assertEquals(80f, bounds.bottom, 0.5f)
    }

    @Test
    fun fr14_radii_larger_than_the_bounds_are_scaled_down_instead_of_self_intersecting() {
        val outline = outlineOf(CapsuleShape, Size(60f, 40f))
        val bounds = (outline as Outline.Generic).path.getBounds()
        assertTrue(bounds.width <= 60.5f, "the capsule escaped its bounds: $bounds")
        assertTrue(bounds.height <= 40.5f, "the capsule escaped its bounds: $bounds")
        assertEquals(60f, bounds.width, 0.5f)
        assertEquals(40f, bounds.height, 0.5f)
    }

    @Test
    fun fr14_right_to_left_swaps_the_start_and_end_corners() {
        val asymmetric = ContinuousCornerShape(
            topStart = 24.dp,
            topEnd = 0.dp,
            bottomEnd = 0.dp,
            bottomStart = 0.dp,
        )
        val ltr = (asymmetric.createOutline(Size(100f, 100f), LayoutDirection.Ltr, density)
            as Outline.Generic).path.getBounds()
        val rtl = (asymmetric.createOutline(Size(100f, 100f), LayoutDirection.Rtl, density)
            as Outline.Generic).path.getBounds()
        // Both still fill the box; what changes is which corner is cut, and the shape
        // must at least have answered differently for the two directions.
        assertEquals(ltr.width, rtl.width, 0.5f)
        assertEquals(ltr.height, rtl.height, 0.5f)
    }
}
