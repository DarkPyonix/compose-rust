package dioxus.compose.design

import androidx.compose.ui.geometry.Offset
import dioxus.compose.protocol.IconRole

/**
 * One icon's shape, in a unit box whose corners are (0, 0) and (1, 1).
 *
 * The geometry is shared because the meaning is: `Back` points the same way in every design
 * system. What each system decides is how the shape is drawn, which is [IconStyle]: the
 * stroke weight, the shape of the ends, and the optical size the box is scaled to. That is
 * enough to tell the three sets apart at a glance and is the reason the Host registers a
 * role rather than a picture.
 */
data class IconGeometry(
    /** Open paths, each a run of points to be joined in order. */
    val strokes: List<List<Offset>>,
    /** Closed round shapes, stroked or filled. */
    val dots: List<IconDot>,
)

data class IconDot(val center: Offset, val radius: Float, val filled: Boolean)

/** The artwork for one meaning. Every role in the closed set has one, so there is no miss. */
fun iconGeometry(role: IconRole): IconGeometry = when (role) {
    IconRole.Back -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.60f, 0.20f), Offset(0.28f, 0.50f), Offset(0.60f, 0.80f)),
            listOf(Offset(0.28f, 0.50f), Offset(0.80f, 0.50f)),
        ),
        dots = emptyList(),
    )

    IconRole.Forward -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.40f, 0.20f), Offset(0.72f, 0.50f), Offset(0.40f, 0.80f)),
            listOf(Offset(0.20f, 0.50f), Offset(0.72f, 0.50f)),
        ),
        dots = emptyList(),
    )

    IconRole.Close -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.24f, 0.24f), Offset(0.76f, 0.76f)),
            listOf(Offset(0.76f, 0.24f), Offset(0.24f, 0.76f)),
        ),
        dots = emptyList(),
    )

    // A lens with a handle running out of its lower right.
    IconRole.Search -> IconGeometry(
        strokes = listOf(listOf(Offset(0.64f, 0.64f), Offset(0.84f, 0.84f))),
        dots = listOf(IconDot(Offset(0.45f, 0.45f), 0.26f, filled = false)),
    )

    IconRole.Add -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.20f), Offset(0.50f, 0.80f)),
            listOf(Offset(0.20f, 0.50f), Offset(0.80f, 0.50f)),
        ),
        dots = emptyList(),
    )

    IconRole.Check -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.20f, 0.52f), Offset(0.42f, 0.74f), Offset(0.80f, 0.28f)),
        ),
        dots = emptyList(),
    )

    // A dial: a ring with the spokes that read as a gear at small sizes.
    IconRole.Settings -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.06f), Offset(0.50f, 0.22f)),
            listOf(Offset(0.50f, 0.78f), Offset(0.50f, 0.94f)),
            listOf(Offset(0.06f, 0.50f), Offset(0.22f, 0.50f)),
            listOf(Offset(0.78f, 0.50f), Offset(0.94f, 0.50f)),
        ),
        dots = listOf(IconDot(Offset(0.50f, 0.50f), 0.28f, filled = false)),
    )

    IconRole.More -> IconGeometry(
        strokes = emptyList(),
        dots = listOf(
            IconDot(Offset(0.20f, 0.50f), 0.08f, filled = true),
            IconDot(Offset(0.50f, 0.50f), 0.08f, filled = true),
            IconDot(Offset(0.80f, 0.50f), 0.08f, filled = true),
        ),
    )

    // A pitched roof over a wall, which is the house every icon set draws for "where this
    // application starts".
    IconRole.Home -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.12f, 0.48f), Offset(0.50f, 0.16f), Offset(0.88f, 0.48f)),
            listOf(
                Offset(0.22f, 0.44f),
                Offset(0.22f, 0.84f),
                Offset(0.78f, 0.84f),
                Offset(0.78f, 0.44f),
            ),
        ),
        dots = emptyList(),
    )

    // Three rules with a bullet against each: a list of things, not a paragraph.
    IconRole.List -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.38f, 0.26f), Offset(0.86f, 0.26f)),
            listOf(Offset(0.38f, 0.50f), Offset(0.86f, 0.50f)),
            listOf(Offset(0.38f, 0.74f), Offset(0.86f, 0.74f)),
        ),
        dots = listOf(
            IconDot(Offset(0.18f, 0.26f), 0.06f, filled = true),
            IconDot(Offset(0.18f, 0.50f), 0.06f, filled = true),
            IconDot(Offset(0.18f, 0.74f), 0.06f, filled = true),
        ),
    )

    // Three rules stacked, which is what every desktop has meant by a menu button since
    // the phone made it common. Not the dotted list above: that one is a list of things,
    // this one is the way in to what the window does not have room to show.
    IconRole.Menu -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.14f, 0.28f), Offset(0.86f, 0.28f)),
            listOf(Offset(0.14f, 0.50f), Offset(0.86f, 0.50f)),
            listOf(Offset(0.14f, 0.72f), Offset(0.86f, 0.72f)),
        ),
        dots = emptyList(),
    )

    // A clock with its hand turned back, which is the one drawing that says the past
    // rather than the time: the arrow around the rim is what separates it from a timer.
    IconRole.History -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.16f, 0.36f),
                Offset(0.28f, 0.22f),
                Offset(0.48f, 0.14f),
                Offset(0.70f, 0.20f),
                Offset(0.84f, 0.38f),
                Offset(0.86f, 0.58f),
                Offset(0.74f, 0.78f),
                Offset(0.52f, 0.86f),
                Offset(0.30f, 0.80f),
                Offset(0.18f, 0.66f),
            ),
            // The hand, from the middle out to noon and across to three.
            listOf(Offset(0.50f, 0.32f), Offset(0.50f, 0.52f), Offset(0.68f, 0.60f)),
            // The arrowhead on the open end of the rim, pointing back the way it came.
            listOf(Offset(0.06f, 0.30f), Offset(0.16f, 0.36f), Offset(0.14f, 0.20f)),
        ),
        dots = emptyList(),
    )

    // A tray: the open box with a lip that things arrive in.
    IconRole.Inbox -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.12f, 0.24f),
                Offset(0.12f, 0.80f),
                Offset(0.88f, 0.80f),
                Offset(0.88f, 0.24f),
            ),
            listOf(
                Offset(0.12f, 0.56f),
                Offset(0.32f, 0.56f),
                Offset(0.40f, 0.70f),
                Offset(0.60f, 0.70f),
                Offset(0.68f, 0.56f),
                Offset(0.88f, 0.56f),
            ),
        ),
        dots = emptyList(),
    )
}
