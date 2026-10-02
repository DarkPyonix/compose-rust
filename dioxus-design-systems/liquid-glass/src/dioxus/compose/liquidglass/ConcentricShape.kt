package dioxus.compose.liquidglass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * A rounded rectangle whose corners are continuous rather than circular.
 *
 * A circular corner joins the straight edge at a point where curvature jumps from zero to
 * 1/r. The eye reads that jump as a seam, which is why a circularly rounded rectangle
 * looks slightly pinched at the corners next to Apple's. A continuous corner spreads the
 * curvature over a longer stretch of the edge so it rises from zero smoothly, and the
 * corner reads as one shape rather than an arc glued between two lines.
 *
 * The curve here is a superellipse quarter, |x|^n + |y|^n = 1 with n = [EXPONENT]. That is
 * not the exact spline Apple uses, which is unpublished, but it has the property that
 * matters: curvature at the join is continuous, and the corner is visibly fuller than a
 * circular one of the same radius.
 *
 * The outline is a sampled polyline rather than an analytic curve, because a superellipse
 * of arbitrary exponent has no exact bezier form. [SEGMENTS] samples per corner is far
 * below the point where a sample step is visible at any plausible corner radius.
 */
@Immutable
data class ContinuousCornerShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) : Shape {

    constructor(all: Dp) : this(all, all, all, all)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val mirror = layoutDirection == LayoutDirection.Rtl
        val px = { dp: Dp -> with(density) { dp.toPx() } }
        // Corner radii are named by writing order; the outline is built in visual order,
        // so right-to-left swaps the start and end of each side.
        val tl = px(if (mirror) topEnd else topStart)
        val tr = px(if (mirror) topStart else topEnd)
        val br = px(if (mirror) bottomStart else bottomEnd)
        val bl = px(if (mirror) bottomEnd else bottomStart)

        if (size.minDimension <= 0f) return Outline.Rectangle(size.toRect())

        // No pair of radii on one side may exceed that side's length, or the corners
        // overlap and the outline self-intersects. Scaling all four by the tightest
        // offender keeps the shape proportional instead of clipping one corner.
        val scale = listOf(
            size.width / max(tl + tr, 0.0001f),
            size.width / max(bl + br, 0.0001f),
            size.height / max(tl + bl, 0.0001f),
            size.height / max(tr + br, 0.0001f),
        ).minOrNull()?.coerceAtMost(1f) ?: 1f

        val path = Path().apply {
            val r = floatArrayOf(tl * scale, tr * scale, br * scale, bl * scale)
            val w = size.width
            val h = size.height

            moveTo(r[0], 0f)
            lineTo(w - r[1], 0f)
            corner(this, w - r[1], r[1], r[1], Quadrant.TopEnd)
            lineTo(w, h - r[2])
            corner(this, w - r[2], h - r[2], r[2], Quadrant.BottomEnd)
            lineTo(r[3], h)
            corner(this, r[3], h - r[3], r[3], Quadrant.BottomStart)
            lineTo(0f, r[0])
            corner(this, r[0], r[0], r[0], Quadrant.TopStart)
            close()
        }
        return Outline.Generic(path)
    }

    private enum class Quadrant { TopStart, TopEnd, BottomEnd, BottomStart }

    private fun corner(path: Path, cx: Float, cy: Float, r: Float, quadrant: Quadrant) {
        if (r <= 0f) return
        for (i in 1..SEGMENTS) {
            val t = (i.toFloat() / SEGMENTS) * (PI / 2.0)
            // Superellipse quarter, signed into the quadrant being drawn.
            val u = abs(cos(t)).pow(2.0 / EXPONENT).toFloat()
            val v = abs(sin(t)).pow(2.0 / EXPONENT).toFloat()
            when (quadrant) {
                // Each quadrant starts on the edge it was entered from and ends on the
                // next edge, so u and v swap roles between the horizontal and vertical
                // entries.
                Quadrant.TopEnd -> path.lineTo(cx + r * v, cy - r * u)
                Quadrant.BottomEnd -> path.lineTo(cx + r * u, cy + r * v)
                Quadrant.BottomStart -> path.lineTo(cx - r * v, cy + r * u)
                Quadrant.TopStart -> path.lineTo(cx - r * u, cy - r * v)
            }
        }
    }

    companion object {
        /**
         * The superellipse exponent. 2 is a circle; higher is squarer with a longer,
         * gentler approach along the edge. 5 sits close to the corner Apple draws.
         */
        const val EXPONENT: Double = 5.0

        /** Samples per corner. */
        const val SEGMENTS: Int = 24
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

/**
 * The radius an inner element needs so that its corner stays concentric with the
 * container it sits [inset] inside.
 *
 * Two rounded rectangles are concentric when the gap between their outlines is the same
 * all the way round, including through the corner. That happens only when the inner
 * radius is the outer radius minus the inset. Give the inner element the container's
 * radius instead and the gap pinches at the corners; give it a fixed radius of its own
 * and it pinches or splays depending on the size, which is why a single corner constant
 * cannot express this.
 *
 * The result is floored at zero: an element inset further than the container's radius has
 * a square corner, which is correct rather than a special case.
 */
fun concentricRadius(outer: Dp, inset: Dp): Dp = max(0f, outer.value - inset.value).dp

/** [shape] re-cut for an element inset by [inset] on every side. */
fun ContinuousCornerShape.inset(inset: Dp): ContinuousCornerShape = ContinuousCornerShape(
    topStart = concentricRadius(topStart, inset),
    topEnd = concentricRadius(topEnd, inset),
    bottomEnd = concentricRadius(bottomEnd, inset),
    bottomStart = concentricRadius(bottomStart, inset),
)

/** A capsule: the continuous corner taken to the largest radius the bounds allow. */
val CapsuleShape: ContinuousCornerShape = ContinuousCornerShape(10_000.dp)
