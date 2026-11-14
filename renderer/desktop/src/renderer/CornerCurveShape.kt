package dev.darkpyonix.composerust.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.design.ContinuousCornerShape
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The smoothing a `CornerCurve` value is drawn with: 0..=1, with anything below 0 (and
 * negative infinity) taken as 0, anything above 1 (and positive infinity) as 1, and NaN as
 * 0. A shape that cannot be drawn as asked is drawn as near to it as possible rather than
 * stopping the program.
 */
internal fun cornerSmoothing(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

/**
 * The smoothing this node's `CornerCurve` asks for, or null when it has none. The last one
 * in the list stands, as for every other modifier that sets one value.
 */
internal fun List<ProtocolModifier>.cornerCurve(): Float? =
    (lastOrNull { it is ProtocolModifier.CornerCurve } as ProtocolModifier.CornerCurve?)
        ?.let { cornerSmoothing(it.smoothing) }

/**
 * [this] shape with its corners drawn at [smoothing], keeping its radii.
 *
 * Null means the node has no `CornerCurve`, and the shape is left as it is, including a
 * design system's own continuous corner. A `CornerCurve` of any value replaces that one,
 * so 0 draws plain circular arcs even in a system that would draw continuous corners.
 */
internal fun Shape.withCornerCurve(smoothing: Float?): Shape {
    if (smoothing == null) return this
    val base = when (this) {
        is CornerCurveShape -> base
        is ContinuousCornerShape -> RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
        else -> this
    }
    return if (smoothing <= 0f) base else CornerCurveShape(base, smoothing)
}

/**
 * A rounded shape whose corners have continuous curvature, the way Figma draws corner
 * smoothing.
 *
 * A circular corner meets the straight side where curvature jumps from zero to 1/r, and
 * the eye reads the jump as a seam. Here the curve starts further along the side,
 * `(1 + smoothing) * r` from the corner instead of `r`, and eases into a shorter circular
 * arc in the middle, so the curvature rises without a step.
 *
 * The radii are [base]'s: whatever rounded outline it makes (a `RoundedCornerShape`, the
 * CSS-scaled corners of a `CornerEach`) is redrawn with the same radius at each corner.
 * A base that makes no rounded outline (a rectangle, a free path) is used as it is.
 */
@Immutable
internal data class CornerCurveShape(val base: Shape, val smoothing: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val outline = base.createOutline(size, layoutDirection, density)
        if (smoothing <= 0f || outline !is Outline.Rounded) return outline
        return Outline.Generic(cornerCurvePath(outline.roundRect, smoothing))
    }
}

/**
 * One corner of a [CornerCurveShape]'s outline, in the order the outline is drawn
 * (clockwise from the top left on screen): the point on the side where the curve leaves the
 * straight line, and the three cubic Beziers that carry it round to the next side, as nine
 * points (two control points and an end point for each).
 *
 * The middle cubic is the circular arc. A cubic matches an arc of at most 90 degrees to
 * within 0.03% of its radius, far below a pixel at any radius a screen draws.
 */
internal class CurvedCorner(val start: Offset, val cubics: List<Offset>) {
    val end: Offset get() = if (cubics.isEmpty()) start else cubics.last()
}

/** The four corners of [rect] at [smoothing]: top right, bottom right, bottom left, top left. */
internal fun cornerCurveCorners(rect: RoundRect, smoothing: Float): List<CurvedCorner> {
    val s = cornerSmoothing(smoothing)
    val width = rect.width
    val height = rect.height
    // No curve may start further from its corner than half the shorter side, so two corners
    // never overlap on one side. The radius is brought down until the curve fits, and the
    // smoothing is kept, so the corner keeps its shape at a smaller size.
    val budget = max(0f, min(width, height) / 2f)
    fun radius(value: Float) = min(max(0f, value), budget / (1f + s))
    val right = rect.right
    val bottom = rect.bottom
    val left = rect.left
    val top = rect.top
    return listOf(
        curvedCorner(Offset(right, top), Offset(1f, 0f), Offset(0f, 1f), radius(rect.topRightCornerRadius.radius()), s),
        curvedCorner(Offset(right, bottom), Offset(0f, 1f), Offset(-1f, 0f), radius(rect.bottomRightCornerRadius.radius()), s),
        curvedCorner(Offset(left, bottom), Offset(-1f, 0f), Offset(0f, -1f), radius(rect.bottomLeftCornerRadius.radius()), s),
        curvedCorner(Offset(left, top), Offset(0f, -1f), Offset(1f, 0f), radius(rect.topLeftCornerRadius.radius()), s),
    )
}

/** The outline of [rect] with its corners drawn at [smoothing]. */
internal fun cornerCurvePath(rect: RoundRect, smoothing: Float): Path {
    val corners = cornerCurveCorners(rect, smoothing)
    return Path().apply {
        val first = corners.last().end
        moveTo(first.x, first.y)
        for (corner in corners) {
            lineTo(corner.start.x, corner.start.y)
            for (index in corner.cubics.indices step 3) {
                val one = corner.cubics[index]
                val two = corner.cubics[index + 1]
                val end = corner.cubics[index + 2]
                cubicTo(one.x, one.y, two.x, two.y, end.x, end.y)
            }
        }
        close()
    }
}

private fun androidx.compose.ui.geometry.CornerRadius.radius(): Float = min(x, y)

/**
 * One corner, worked out in the corner's own frame and then placed: [corner] is the corner
 * of the rectangle, [along] the direction the outline arrives in, [across] the direction it
 * leaves in.
 *
 * The numbers are Figma's corner smoothing as published in figma-squircle: with
 * `p = (1 + s) r` the distance from the corner where the curve starts, the arc in the
 * middle spans `90 (1 - s)` degrees, and the two cubics either side of it are fitted so
 * that the curvature is zero where they meet the sides and matches the arc where they meet
 * it.
 */
private fun curvedCorner(corner: Offset, along: Offset, across: Offset, r: Float, s: Float): CurvedCorner {
    if (r <= 0f) return CurvedCorner(corner, emptyList())
    val p = (1f + s) * r
    val arcDegrees = 90.0 * (1.0 - s)
    val arcLength = (sin(radians(arcDegrees / 2.0)) * r * sqrt(2.0)).toFloat()
    val alpha = (90.0 - arcDegrees) / 2.0
    val p3ToP4 = r * tan(radians(alpha / 2.0))
    val beta = 45.0 * s
    val c = (p3ToP4 * cos(radians(beta))).toFloat()
    val d = (c * tan(radians(beta))).toFloat()
    val b = (p - arcLength - c - d) / 3f
    val a = 2f * b

    // A point `back` before the corner along the arriving side and `out` along the leaving
    // one.
    fun at(back: Float, out: Float) = Offset(
        corner.x - back * along.x + out * across.x,
        corner.y - back * along.y + out * across.y,
    )

    val arcStart = at(arcLength + d, d)
    val arcEnd = at(d, d + arcLength)
    // The arc's centre is r back and r out from the corner. In that frame the arc runs from
    // -45 - arc/2 degrees to -45 + arc/2, measured from the arriving direction towards the
    // leaving one, and a cubic follows it with its control points k r along the tangents.
    val theta = radians(arcDegrees)
    val k = (4.0 / 3.0 * tan(theta / 4.0)).toFloat() * r
    val startAngle = radians(-45.0 - arcDegrees / 2.0)
    val endAngle = radians(-45.0 + arcDegrees / 2.0)
    // The tangent in the direction of travel at angle phi is (-sin phi, cos phi) in the
    // (along, across) frame; `at` takes distances back along the arriving side, so its
    // first argument is the negated along component.
    fun tangent(phi: Double) = Offset(-sin(phi).toFloat(), cos(phi).toFloat())
    val ts = tangent(startAngle)
    val te = tangent(endAngle)
    val arcOne = at(arcLength + d - k * ts.x, d + k * ts.y)
    val arcTwo = at(d + k * te.x, d + arcLength - k * te.y)

    return CurvedCorner(
        start = at(p, 0f),
        cubics = listOf(
            at(p - a, 0f),
            at(p - a - b, 0f),
            arcStart,
            arcOne,
            arcTwo,
            arcEnd,
            at(0f, d + arcLength + c),
            at(0f, d + arcLength + b + c),
            at(0f, p),
        ),
    )
}

private fun radians(degrees: Double): Double = degrees * PI / 180.0
