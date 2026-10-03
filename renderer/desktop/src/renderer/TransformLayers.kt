package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A 2D affine matrix drawn with two Compose layers.
 *
 * Compose's stable layer takes a translation, a scale, a rotation and an origin, and no
 * skew, so an arbitrary CSS matrix is not one layer. Its linear part splits by singular
 * value decomposition into a rotation, a scale along the axes and a second rotation,
 * `A = R(outer) S(scaleX, scaleY) R(inner)`, and two layers stacked on the same node draw
 * exactly that: the outer one moves, rotates and scales, the inner one only rotates, and
 * both turn about the same origin. Together they are `T(e, f) T(o) A T(-o)`, which is CSS's
 * `transform-origin` applied to `matrix(a, b, c, d, e, f)`.
 *
 * This is the one file that depends on the order a Compose layer applies its properties
 * in (scale, then rotation, about the origin, then translation). If that order changed,
 * the corner positions the transform tests compare against the matrix would move at once.
 *
 * Drawing through layers rather than through a matrix on the canvas is what makes pointer
 * input follow the drawn shape: Compose maps a pointer through each layer's inverse before
 * it asks whether the pointer is inside a node.
 */
internal class AffineParts {
    var translationX = 0f
        private set
    var translationY = 0f
        private set
    var outerDegrees = 0f
        private set
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    var innerDegrees = 0f
        private set

    /** False for a matrix with no inverse, which CSS does not draw at all. */
    var invertible = true
        private set

    /**
     * Splits `matrix(a, b, c, d, e, f)`. Allocates nothing, so it can run in every frame of
     * an animation.
     */
    fun decompose(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        translationX = e
        translationY = f
        invertible = a * d - b * c != 0f
        val sumHalf = (a + d) / 2f
        val differenceHalf = (a - d) / 2f
        val shearSum = (b + c) / 2f
        val shearDifference = (b - c) / 2f
        val q = sqrt(sumHalf * sumHalf + shearDifference * shearDifference)
        val r = sqrt(differenceHalf * differenceHalf + shearSum * shearSum)
        scaleX = q + r
        // Negative for a reflection: one layer then mirrors along its own y axis.
        scaleY = q - r
        val turn = atan2(shearDifference, sumHalf)
        if (differenceHalf == 0f && shearSum == 0f) {
            // A rotation and a uniform scale: the whole turn is the outer layer's, so the
            // inner one can be left out.
            outerDegrees = degrees(turn)
            innerDegrees = 0f
            return
        }
        val axis = atan2(shearSum, differenceHalf)
        outerDegrees = degrees((turn + axis) / 2f)
        innerDegrees = degrees((turn - axis) / 2f)
    }

    private fun degrees(radians: Float): Float = (radians * 180.0 / PI).toFloat()

    companion object {
        /**
         * Whether one layer draws this linear part: a rotation and a uniform scale, with no
         * skew and no reflection.
         */
        fun isOneLayer(a: Float, b: Float, c: Float, d: Float): Boolean = b == -c && a == d
    }
}

/**
 * Draws this node and its content through the affine matrix [matrix] writes, turning about
 * the fraction of the node's size [originX], [originY].
 *
 * [matrix] fills six values, `a, b, c, d, e, f`, with `e` and `f` in dp. It is read inside
 * the layers' own blocks, so a matrix that changes every frame redraws the layers and
 * neither recomposes nor remeasures anything.
 *
 * [oneLayer] leaves the inner layer out, for a matrix known to be a rotation and a uniform
 * scale; the result is the same.
 */
internal fun Modifier.affineTransform(
    originX: Float,
    originY: Float,
    oneLayer: Boolean,
    matrix: (FloatArray) -> Unit,
): Modifier {
    val origin = TransformOrigin(originX, originY)
    val values = FloatArray(6)
    val parts = AffineParts()
    val outer = graphicsLayer {
        matrix(values)
        parts.decompose(values[0], values[1], values[2], values[3], values[4], values[5])
        transformOrigin = origin
        if (!parts.invertible) {
            // Nothing is drawn and nothing is hit: a layer that scales to nothing has no
            // inverse, so no pointer is ever inside it.
            alpha = 0f
            scaleX = 0f
            scaleY = 0f
            translationX = 0f
            translationY = 0f
            rotationZ = 0f
            return@graphicsLayer
        }
        alpha = 1f
        translationX = parts.translationX.dp.toPx()
        translationY = parts.translationY.dp.toPx()
        rotationZ = parts.outerDegrees
        scaleX = parts.scaleX
        scaleY = parts.scaleY
    }
    if (oneLayer) return outer
    return outer.graphicsLayer {
        matrix(values)
        parts.decompose(values[0], values[1], values[2], values[3], values[4], values[5])
        transformOrigin = origin
        rotationZ = if (parts.invertible) parts.innerDegrees else 0f
    }
}
