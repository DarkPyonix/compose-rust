package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What this file is bound to.
 *
 * Everything below relies on how a Compose layer turns its properties into a matrix, and
 * that is not part of Compose's documented surface. It was read from the Compose release
 * named here, in `prepareTransformationMatrix` of `ui-graphics`: a layer moves its origin
 * to zero, applies the rotation about Z, then Y, then X, then the scale, then the
 * perspective that exists only while X or Y is rotated, and moves the origin back and adds
 * the translation. The perspective term is `-1 / (cameraDistance * 72)`.
 *
 * A test compares [COMPOSE_VERSION] with the version the renderer module depends on, so
 * moving to another Compose release fails that test until this file has been read against
 * the new release.
 */
internal object Compose3DBinding {
    const val COMPOSE_VERSION = "1.11.1-ext.2"

    /** Pixels of camera depth for one unit of `cameraDistance`. */
    const val PIXELS_PER_CAMERA_UNIT = 72f
}

/**
 * How a 4x4 CSS matrix is drawn with Compose layers, and nothing approximate about it.
 *
 * A node is flat: its content lies in the plane z = 0 and what CSS draws is the image of
 * that plane. So only nine entries of the matrix matter, those of the homography
 *
 * ```
 * x' = (m0 x + m4 y + m12) / w     y' = (m1 x + m5 y + m13) / w     w = m3 x + m7 y + m15
 * ```
 *
 * and every such homography is drawn exactly by the layers Compose has. After dividing by
 * `m15`, the translation `t` is `(m12, m13)`, which leaves a homography `K` with a 2x2
 * linear part `F` and a perspective row `(g, h)`.
 *
 * - **No perspective** (`g = h = 0`): an affine map, drawn by the two layers of
 *   [affineTransform].
 * - **One layer** ([Kind.Single]): the one `graphicsLayer` whose rotationX, rotationY,
 *   rotationZ, scale, translation and cameraDistance produce `K`. A layer's own matrix is
 *   `S Q P` with `Q` a rotation, so two rows of `Q` are the two rows of `F` with the
 *   perspective row scaled in, and the three unknown scales and depth follow from those
 *   two rows being orthonormal. The depth falls out of `g h D^2 = -(F row 0 . F row 1)`
 *   when both `g` and `h` are present. With only one of them the depth is free, and it is
 *   taken to be the one that leaves that axis unscaled, which is the camera distance of
 *   the CSS `perspective()` that produced the matrix.
 * - **Stacked** ([Kind.Stacked]): where no single layer matches (a skew together with
 *   perspective is the usual case), a layer with the same perspective row and the
 *   rotations that give it, followed by the affine layers of the remaining 2x2 map. Layers
 *   compose exactly, so the picture is the same one.
 *
 * Every layer is a `graphicsLayer`, so the layout is untouched and Compose maps a pointer
 * through each layer's inverse, which is why input follows the drawn shape.
 *
 * Values are in dp. [Kind.Hidden] is a node that is not drawn and takes no input: a matrix
 * with no inverse, a `w` that is not positive at the origin, or a back face with
 * backface-visibility hidden.
 */
internal class Transform3DPlan {
    enum class Kind { Hidden, Affine, Single, Stacked }

    var kind = Kind.Hidden
        private set

    /** The perspective layer of [Kind.Single] and [Kind.Stacked], angles in degrees. */
    var rotationX = 0f
        private set
    var rotationY = 0f
        private set
    var rotationZ = 0f
        private set
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set

    /** Camera distance in dp. */
    var depth = 0f
        private set

    /** The perspective layer's own translation, dp. Nonzero only for [Kind.Single]. */
    var translationX = 0f
        private set
    var translationY = 0f
        private set

    /** `a, b, c, d, e, f` of the affine layers of [Kind.Affine] and [Kind.Stacked]. */
    val affine = FloatArray(6)
    var affineOneLayer = true
        private set

    /** Whether the node faces away from the viewer: the map reverses orientation. */
    var facingAway = false
        private set

    /**
     * Works out how [m], sixteen values in the column-major order of CSS `matrix3d`, is
     * drawn. Allocates only the intermediate arithmetic, none of it kept.
     */
    fun solve(m: FloatArray, backfaceHidden: Boolean) {
        kind = Kind.Hidden
        facingAway = false
        affineOneLayer = true
        val w0 = m[15].toDouble()
        if (!(w0 > 0.0)) return
        val inverse = 1.0 / w0
        val tx = m[12] * inverse
        val ty = m[13] * inverse
        val g = m[3] * inverse
        val h = m[7] * inverse
        val a00 = m[0] * inverse - tx * g
        val a01 = m[1] * inverse - ty * g
        val a10 = m[4] * inverse - tx * h
        val a11 = m[5] * inverse - ty * h
        val determinant = a00 * a11 - a01 * a10
        if (determinant == 0.0 || !determinant.isFinite()) return
        facingAway = determinant < 0.0
        if (backfaceHidden && facingAway) return

        if (g == 0.0 && h == 0.0) {
            affine[0] = a00.toFloat()
            affine[1] = a01.toFloat()
            affine[2] = a10.toFloat()
            affine[3] = a11.toFloat()
            affine[4] = tx.toFloat()
            affine[5] = ty.toFloat()
            affineOneLayer = AffineParts.isOneLayer(affine[0], affine[1], affine[2], affine[3])
            kind = if (affine.all { it.isFinite() }) Kind.Affine else Kind.Hidden
            return
        }
        if (solveSingleLayer(a00, a01, a10, a11, g, h, tx, ty)) return
        solveStacked(a00, a01, a10, a11, g, h, tx, ty)
    }

    private fun solveSingleLayer(
        a00: Double, a01: Double, a10: Double, a11: Double,
        g: Double, h: Double, tx: Double, ty: Double,
    ): Boolean {
        val depthSquared = when {
            g != 0.0 && h != 0.0 -> -(a00 * a10 + a01 * a11) / (g * h)
            g != 0.0 -> unscaledDepthSquared(a00 * a00 + a01 * a01, g)
            else -> unscaledDepthSquared(a10 * a10 + a11 * a11, h)
        }
        if (!(depthSquared > 0.0) || !depthSquared.isFinite()) return false
        val distance = sqrt(depthSquared)
        val row0x = a00
        val row0y = a01
        val row0z = -g * distance
        val row1x = a10
        val row1y = a11
        val row1z = -h * distance
        val length0 = sqrt(row0x * row0x + row0y * row0y + row0z * row0z)
        val length1 = sqrt(row1x * row1x + row1y * row1y + row1z * row1z)
        if (!(length0 > 1e-12) || !(length1 > 1e-12)) return false
        val q00 = row0x / length0
        val q01 = row0y / length0
        val q02 = row0z / length0
        val q10 = row1x / length1
        val q11 = row1y / length1
        val q12 = row1z / length1
        // The two rows have to be at right angles, or no rotation has them.
        if (abs(q00 * q10 + q01 * q11 + q02 * q12) > ORTHOGONAL_TOLERANCE) return false
        // The third row of the rotation is the cross product of the first two.
        val q20 = q01 * q12 - q02 * q11
        val q21 = q02 * q10 - q00 * q12
        val q22 = q00 * q11 - q01 * q10
        val sinY = q20.coerceIn(-1.0, 1.0)
        val cosY = sqrt(1.0 - sinY * sinY)
        val x: Double
        val z: Double
        if (cosY > GIMBAL_LIMIT) {
            x = atan2(-q21, q22)
            z = atan2(-q10, q00)
        } else {
            // Looking along the Y axis, where X and Z turn the same way: all of it is Z.
            x = 0.0
            z = atan2(q01, q11)
        }
        val results = floatArrayOf(
            degrees(x), degrees(asin(sinY)), degrees(z),
            length0.toFloat(), length1.toFloat(), distance.toFloat(),
            tx.toFloat(), ty.toFloat(),
        )
        if (!results.all { it.isFinite() }) return false
        rotationX = results[0]
        rotationY = results[1]
        rotationZ = results[2]
        scaleX = results[3]
        scaleY = results[4]
        depth = results[5]
        translationX = results[6]
        translationY = results[7]
        kind = Kind.Single
        return true
    }

    /**
     * The depth at which the one perspective axis leaves its own scale at one, which is the
     * camera of the CSS `perspective()` that gave the matrix. A row already longer than
     * one cannot have that, and takes the depth that turns it by a half right angle.
     */
    private fun unscaledDepthSquared(rowLengthSquared: Double, perspective: Double): Double =
        if (rowLengthSquared < 1.0) {
            (1.0 - rowLengthSquared) / (perspective * perspective)
        } else {
            rowLengthSquared / (perspective * perspective)
        }

    private fun solveStacked(
        a00: Double, a01: Double, a10: Double, a11: Double,
        g: Double, h: Double, tx: Double, ty: Double,
    ) {
        // A layer with this perspective row and no scale: sin(rotX) = -h D, and
        // sin(rotY) cos(rotX) = g D. D keeps both below one half, so the cosines are fine.
        val distance = 0.5 / max(abs(g), abs(h))
        val sinX = -h * distance
        val cosX = sqrt(1.0 - sinX * sinX)
        val sinY = g * distance / cosX
        val cosY = sqrt(1.0 - sinY * sinY)
        // That layer's own 2x2 map is [[cosY, sinY sinX], [0, cosX]], with determinant
        // cosY cosX. What is left for the affine layers is its inverse times the target.
        val determinant = cosY * cosX
        val n00 = (cosX * a00 - sinY * sinX * a10) / determinant
        val n01 = (cosX * a01 - sinY * sinX * a11) / determinant
        val n10 = cosY * a10 / determinant
        val n11 = cosY * a11 / determinant
        val results = floatArrayOf(
            degrees(asin(sinX)), degrees(asin(sinY)), distance.toFloat(),
            n00.toFloat(), n01.toFloat(), n10.toFloat(), n11.toFloat(), tx.toFloat(), ty.toFloat(),
        )
        if (!results.all { it.isFinite() }) return
        rotationX = results[0]
        rotationY = results[1]
        rotationZ = 0f
        scaleX = 1f
        scaleY = 1f
        depth = results[2]
        translationX = 0f
        translationY = 0f
        for (index in 0 until 6) affine[index] = results[3 + index]
        affineOneLayer = AffineParts.isOneLayer(affine[0], affine[1], affine[2], affine[3])
        kind = Kind.Stacked
    }

    private fun degrees(radians: Double): Float = (radians * 180.0 / PI).toFloat()

    private companion object {
        const val ORTHOGONAL_TOLERANCE = 1e-4

        /** Below this cosine of the Y angle the X and Z angles cannot be told apart. */
        const val GIMBAL_LIMIT = 1e-6
    }
}

/**
 * Draws this node and its content through [plan], turning about the fraction of the node's
 * size [originX], [originY]. Which layers that takes, and why they are exact, is on
 * [Transform3DPlan].
 */
internal fun Modifier.perspectiveTransform(plan: Transform3DPlan, originX: Float, originY: Float): Modifier {
    val origin = TransformOrigin(originX, originY)
    return when (plan.kind) {
        Transform3DPlan.Kind.Hidden -> graphicsLayer {
            // A layer that scales to nothing has no inverse, so nothing is drawn and no
            // pointer is ever inside it.
            transformOrigin = origin
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
        }
        Transform3DPlan.Kind.Affine -> affineTransform(originX, originY, plan.affineOneLayer) { matrix ->
            plan.affine.copyInto(matrix)
        }
        Transform3DPlan.Kind.Single -> graphicsLayer { perspectiveLayer(plan, origin) }
        Transform3DPlan.Kind.Stacked -> affineTransform(originX, originY, plan.affineOneLayer) { matrix ->
            plan.affine.copyInto(matrix)
        }.graphicsLayer { perspectiveLayer(plan, origin) }
    }
}

private fun GraphicsLayerScope.perspectiveLayer(plan: Transform3DPlan, origin: TransformOrigin) {
    transformOrigin = origin
    translationX = plan.translationX.dp.toPx()
    translationY = plan.translationY.dp.toPx()
    rotationX = plan.rotationX
    rotationY = plan.rotationY
    rotationZ = plan.rotationZ
    scaleX = plan.scaleX
    scaleY = plan.scaleY
    cameraDistance = plan.depth.dp.toPx() / Compose3DBinding.PIXELS_PER_CAMERA_UNIT
}
