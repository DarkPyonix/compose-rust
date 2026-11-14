package dev.darkpyonix.composerust.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Where a node is drawn inside the larger area that takes its presses.
 *
 * Written by [minimumHitArea] each time the node is measured, and read by the pointer
 * handlers inside that area, which see positions in the larger box.
 */
internal class HitArea {
    /** How far the drawn box sits from the left and the top of the area that takes presses. */
    var left: Int = 0
        private set
    var top: Int = 0
        private set

    /** The drawn box's size, which is also the size the node's neighbours are laid out against. */
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    internal fun update(left: Int, top: Int, width: Int, height: Int) {
        this.left = left
        this.top = top
        this.width = width
        this.height = height
    }

    /** Whether a position in the larger area falls on the drawn box. */
    fun isOnDrawnBox(position: Offset): Boolean =
        position.x >= left && position.x < left + width &&
            position.y >= top && position.y < top + height

    /** A position in the larger area, moved into the drawn box's own coordinates. */
    fun inDrawnBox(position: Offset): Offset = Offset(position.x - left, position.y - top)
}

/**
 * Takes presses over at least [minimum] by [minimum] around what follows, without moving it,
 * resizing it or changing how it is drawn.
 *
 * Compose tests a press against the measured size of the node that listens for it, so a
 * larger area needs a larger node. Two layouts make one without disturbing anything else:
 * the inner one measures what follows as it would have been measured and reports a box
 * grown to [minimum] around it, with what follows centred inside; the outer one reports the
 * original size to the parent and places the grown box so that what follows lands where it
 * always was. [pointerInput] sits between the two, on the grown box, and is the only thing
 * that sees the larger area. Its positions are the grown box's; [area] turns them into the
 * drawn box's.
 *
 * Nothing is drawn in the margin, and a parent that clips its children clips the margin's
 * presses as well, as it would for any child drawn over its edge.
 */
internal fun Modifier.minimumHitArea(minimum: Dp, area: HitArea, pointerInput: Modifier): Modifier =
    this
        .layout { measurable, constraints ->
            val grow = minimum.roundToPx().coerceAtLeast(0)
            val grown = measurable.measure(constraints.growMaximum(grow))
            layout(area.width, area.height) {
                grown.place(-area.left, -area.top)
            }
        }
        .then(pointerInput)
        .layout { measurable, constraints ->
            val grow = minimum.roundToPx().coerceAtLeast(0)
            val drawn = measurable.measure(constraints.shrinkMaximum(grow))
            val width = maxOf(drawn.width, grow)
            val height = maxOf(drawn.height, grow)
            val left = (width - drawn.width) / 2
            val top = (height - drawn.height) / 2
            area.update(left, top, drawn.width, drawn.height)
            layout(width, height) {
                drawn.place(left, top)
            }
        }

/** Room for a box up to [amount] wider and taller than [this] allows. */
private fun Constraints.growMaximum(amount: Int): Constraints = Constraints(
    minWidth = minWidth,
    maxWidth = if (hasBoundedWidth) maxWidth + amount else Constraints.Infinity,
    minHeight = minHeight,
    maxHeight = if (hasBoundedHeight) maxHeight + amount else Constraints.Infinity,
)

/** The constraints [growMaximum] was given back. */
private fun Constraints.shrinkMaximum(amount: Int): Constraints {
    val width = if (hasBoundedWidth) (maxWidth - amount).coerceAtLeast(minWidth) else Constraints.Infinity
    val height = if (hasBoundedHeight) (maxHeight - amount).coerceAtLeast(minHeight) else Constraints.Infinity
    return Constraints(minWidth = minWidth, maxWidth = width, minHeight = minHeight, maxHeight = height)
}
