package dev.darkpyonix.composerust.design

import androidx.compose.ui.geometry.Offset
import dev.darkpyonix.composerust.protocol.IconRole

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
            // 4 cardinal teeth (N, S, W, E)
            listOf(Offset(0.50f, 0.08f), Offset(0.50f, 0.21f)),
            listOf(Offset(0.50f, 0.79f), Offset(0.50f, 0.92f)),
            listOf(Offset(0.08f, 0.50f), Offset(0.21f, 0.50f)),
            listOf(Offset(0.79f, 0.50f), Offset(0.92f, 0.50f)),
            // 4 diagonal teeth (NW, NE, SW, SE) so the icon reads as a gear, not a crosshair
            listOf(Offset(0.20f, 0.20f), Offset(0.29f, 0.29f)),
            listOf(Offset(0.80f, 0.20f), Offset(0.71f, 0.29f)),
            listOf(Offset(0.20f, 0.80f), Offset(0.29f, 0.71f)),
            listOf(Offset(0.80f, 0.80f), Offset(0.71f, 0.71f)),
        ),
        dots = listOf(
            IconDot(Offset(0.50f, 0.50f), 0.29f, filled = false),
            IconDot(Offset(0.50f, 0.50f), 0.12f, filled = false),
        ),
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

    // Up and away. The arrow a composer sends with, which points up because the message
    // goes up the page it is about to join.
    // An arrow that fills the box it is given. It used to sit inside two thirds of it,
    // which in the round key at the end of a composer left ten points of glyph in a
    // thirty five point button: a pale disc with something small and grey on it.
    IconRole.Send -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.92f), Offset(0.50f, 0.12f)),
            listOf(Offset(0.16f, 0.44f), Offset(0.50f, 0.10f), Offset(0.84f, 0.44f)),
        ),
        dots = emptyList(),
    )

    // A capsule on a stand: the head, the arc that catches it, and the post.
    IconRole.Mic -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.38f, 0.30f),
                Offset(0.38f, 0.50f),
                Offset(0.50f, 0.60f),
                Offset(0.62f, 0.50f),
                Offset(0.62f, 0.30f),
                Offset(0.50f, 0.20f),
                Offset(0.38f, 0.30f),
            ),
            listOf(
                Offset(0.24f, 0.50f),
                Offset(0.28f, 0.68f),
                Offset(0.50f, 0.76f),
                Offset(0.72f, 0.68f),
                Offset(0.76f, 0.50f),
            ),
            listOf(Offset(0.50f, 0.76f), Offset(0.50f, 0.88f)),
        ),
        dots = emptyList(),
    )

    // A frame with a hill in it and the sun over the hill, which is what a picture is in
    // every icon set anyone has drawn.
    IconRole.Image -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.14f, 0.18f),
                Offset(0.86f, 0.18f),
                Offset(0.86f, 0.82f),
                Offset(0.14f, 0.82f),
                Offset(0.14f, 0.18f),
            ),
            listOf(Offset(0.14f, 0.66f), Offset(0.36f, 0.44f), Offset(0.58f, 0.66f)),
            listOf(Offset(0.50f, 0.74f), Offset(0.68f, 0.56f), Offset(0.86f, 0.74f)),
        ),
        dots = listOf(IconDot(Offset(0.66f, 0.34f), 0.07f, filled = true)),
    )

    // A frame with a play triangle in it.
    IconRole.Video -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.10f, 0.24f),
                Offset(0.90f, 0.24f),
                Offset(0.90f, 0.76f),
                Offset(0.10f, 0.76f),
                Offset(0.10f, 0.24f),
            ),
            listOf(Offset(0.42f, 0.38f), Offset(0.66f, 0.50f), Offset(0.42f, 0.62f), Offset(0.42f, 0.38f)),
        ),
        dots = emptyList(),
    )

    // Four panes. A collection is more than one of something, arranged.
    IconRole.Library -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.14f, 0.14f),
                Offset(0.44f, 0.14f),
                Offset(0.44f, 0.44f),
                Offset(0.14f, 0.44f),
                Offset(0.14f, 0.14f),
            ),
            listOf(
                Offset(0.56f, 0.14f),
                Offset(0.86f, 0.14f),
                Offset(0.86f, 0.44f),
                Offset(0.56f, 0.44f),
                Offset(0.56f, 0.14f),
            ),
            listOf(
                Offset(0.14f, 0.56f),
                Offset(0.44f, 0.56f),
                Offset(0.44f, 0.86f),
                Offset(0.14f, 0.86f),
                Offset(0.14f, 0.56f),
            ),
            listOf(
                Offset(0.56f, 0.56f),
                Offset(0.86f, 0.56f),
                Offset(0.86f, 0.86f),
                Offset(0.56f, 0.86f),
                Offset(0.56f, 0.56f),
            ),
        ),
        dots = emptyList(),
    )

    // A window with a column ruled off down its leading edge, which is the thing the
    // button puts away and brings back.
    IconRole.Sidebar -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.12f, 0.20f),
                Offset(0.88f, 0.20f),
                Offset(0.88f, 0.80f),
                Offset(0.12f, 0.80f),
                Offset(0.12f, 0.20f),
            ),
            listOf(Offset(0.40f, 0.20f), Offset(0.40f, 0.80f)),
        ),
        dots = emptyList(),
    )

    // A pen laid across the box it writes in, nib to the lower left.
    IconRole.Compose -> IconGeometry(
        strokes = listOf(
            listOf(
                Offset(0.82f, 0.46f),
                Offset(0.82f, 0.84f),
                Offset(0.16f, 0.84f),
                Offset(0.16f, 0.22f),
                Offset(0.52f, 0.22f),
            ),
            listOf(Offset(0.44f, 0.62f), Offset(0.78f, 0.16f), Offset(0.90f, 0.26f), Offset(0.56f, 0.72f), Offset(0.40f, 0.76f), Offset(0.44f, 0.62f)),
        ),
        dots = emptyList(),
    )

    // A chevron pointing down: what is folded is below, and this is the way to it.
    IconRole.Collapse -> IconGeometry(
        strokes = listOf(listOf(Offset(0.24f, 0.38f), Offset(0.50f, 0.66f), Offset(0.76f, 0.38f))),
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

    IconRole.Lock -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.25f, 0.45f), Offset(0.25f, 0.85f), Offset(0.75f, 0.85f), Offset(0.75f, 0.45f), Offset(0.25f, 0.45f)),
            listOf(Offset(0.34f, 0.45f), Offset(0.34f, 0.29f), Offset(0.39f, 0.18f), Offset(0.50f, 0.14f), Offset(0.61f, 0.18f), Offset(0.66f, 0.29f), Offset(0.66f, 0.45f)),
        ),
        dots = emptyList(),
    )

    IconRole.Favorite -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.82f), Offset(0.20f, 0.54f), Offset(0.14f, 0.38f), Offset(0.18f, 0.25f), Offset(0.28f, 0.18f), Offset(0.40f, 0.19f), Offset(0.50f, 0.30f), Offset(0.60f, 0.19f), Offset(0.72f, 0.18f), Offset(0.82f, 0.25f), Offset(0.86f, 0.38f), Offset(0.80f, 0.54f), Offset(0.50f, 0.82f)),
        ),
        dots = emptyList(),
    )

    IconRole.Book -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.82f), Offset(0.35f, 0.74f), Offset(0.14f, 0.74f), Offset(0.14f, 0.20f), Offset(0.35f, 0.20f), Offset(0.50f, 0.28f), Offset(0.65f, 0.20f), Offset(0.86f, 0.20f), Offset(0.86f, 0.74f), Offset(0.65f, 0.74f), Offset(0.50f, 0.82f)),
            listOf(Offset(0.50f, 0.28f), Offset(0.50f, 0.82f)),
        ),
        dots = emptyList(),
    )

    IconRole.Play -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.30f, 0.18f), Offset(0.80f, 0.50f), Offset(0.30f, 0.82f), Offset(0.30f, 0.18f)),
        ),
        dots = emptyList(),
    )

    IconRole.Shuffle -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.12f, 0.24f), Offset(0.29f, 0.24f), Offset(0.71f, 0.76f), Offset(0.88f, 0.76f)),
            listOf(Offset(0.72f, 0.62f), Offset(0.88f, 0.76f), Offset(0.72f, 0.90f)),
            listOf(Offset(0.12f, 0.76f), Offset(0.29f, 0.76f), Offset(0.71f, 0.24f), Offset(0.88f, 0.24f)),
            listOf(Offset(0.72f, 0.10f), Offset(0.88f, 0.24f), Offset(0.72f, 0.38f)),
        ),
        dots = emptyList(),
    )

    IconRole.ExpandMore -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.20f, 0.35f), Offset(0.50f, 0.65f), Offset(0.80f, 0.35f)),
        ),
        dots = emptyList(),
    )

    IconRole.Person -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.18f, 0.84f), Offset(0.20f, 0.72f), Offset(0.30f, 0.62f), Offset(0.50f, 0.58f), Offset(0.70f, 0.62f), Offset(0.80f, 0.72f), Offset(0.82f, 0.84f)),
        ),
        dots = listOf(IconDot(Offset(0.50f, 0.32f), 0.16f, filled = false)),
    )

    IconRole.Clock -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.24f), Offset(0.50f, 0.50f), Offset(0.68f, 0.62f)),
        ),
        dots = listOf(IconDot(Offset(0.50f, 0.50f), 0.40f, filled = false)),
    )

    IconRole.Tune -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.18f, 0.18f), Offset(0.18f, 0.82f)),
            listOf(Offset(0.50f, 0.18f), Offset(0.50f, 0.82f)),
            listOf(Offset(0.82f, 0.18f), Offset(0.82f, 0.82f)),
            listOf(Offset(0.08f, 0.35f), Offset(0.28f, 0.35f)),
            listOf(Offset(0.40f, 0.64f), Offset(0.60f, 0.64f)),
            listOf(Offset(0.72f, 0.42f), Offset(0.92f, 0.42f)),
        ),
        dots = emptyList(),
    )

    IconRole.Chat -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.14f, 0.16f), Offset(0.86f, 0.16f), Offset(0.86f, 0.68f), Offset(0.48f, 0.68f), Offset(0.28f, 0.84f), Offset(0.28f, 0.68f), Offset(0.14f, 0.68f), Offset(0.14f, 0.16f)),
        ),
        dots = emptyList(),
    )

    IconRole.Star -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.10f), Offset(0.61f, 0.36f), Offset(0.90f, 0.38f), Offset(0.68f, 0.57f), Offset(0.75f, 0.86f), Offset(0.50f, 0.70f), Offset(0.25f, 0.86f), Offset(0.32f, 0.57f), Offset(0.10f, 0.38f), Offset(0.39f, 0.36f), Offset(0.50f, 0.10f)),
        ),
        dots = emptyList(),
    )

    IconRole.Bag -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.18f, 0.32f), Offset(0.82f, 0.32f), Offset(0.78f, 0.86f), Offset(0.22f, 0.86f), Offset(0.18f, 0.32f)),
            listOf(Offset(0.35f, 0.34f), Offset(0.35f, 0.23f), Offset(0.41f, 0.15f), Offset(0.50f, 0.12f), Offset(0.59f, 0.15f), Offset(0.65f, 0.23f), Offset(0.65f, 0.34f)),
        ),
        dots = emptyList(),
    )

    IconRole.Bell -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.17f, 0.72f), Offset(0.25f, 0.62f), Offset(0.25f, 0.40f), Offset(0.30f, 0.25f), Offset(0.40f, 0.18f), Offset(0.50f, 0.15f), Offset(0.60f, 0.18f), Offset(0.70f, 0.25f), Offset(0.75f, 0.40f), Offset(0.75f, 0.62f), Offset(0.83f, 0.72f), Offset(0.17f, 0.72f)),
            listOf(Offset(0.40f, 0.80f), Offset(0.45f, 0.86f), Offset(0.55f, 0.86f), Offset(0.60f, 0.80f)),
        ),
        dots = emptyList(),
    )

    IconRole.Share -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.50f, 0.65f), Offset(0.50f, 0.12f)),
            listOf(Offset(0.28f, 0.34f), Offset(0.50f, 0.12f), Offset(0.72f, 0.34f)),
            listOf(Offset(0.20f, 0.48f), Offset(0.16f, 0.48f), Offset(0.16f, 0.86f), Offset(0.84f, 0.86f), Offset(0.84f, 0.48f), Offset(0.80f, 0.48f)),
        ),
        dots = emptyList(),
    )

    IconRole.Language -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.10f, 0.50f), Offset(0.90f, 0.50f)),
            listOf(Offset(0.50f, 0.10f), Offset(0.35f, 0.22f), Offset(0.28f, 0.50f), Offset(0.35f, 0.78f), Offset(0.50f, 0.90f)),
            listOf(Offset(0.50f, 0.10f), Offset(0.65f, 0.22f), Offset(0.72f, 0.50f), Offset(0.65f, 0.78f), Offset(0.50f, 0.90f)),
            listOf(Offset(0.18f, 0.27f), Offset(0.82f, 0.27f)),
            listOf(Offset(0.18f, 0.73f), Offset(0.82f, 0.73f)),
        ),
        dots = listOf(IconDot(Offset(0.50f, 0.50f), 0.40f, filled = false)),
    )

    IconRole.CompactOverlay -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.10f, 0.18f), Offset(0.90f, 0.18f), Offset(0.90f, 0.82f), Offset(0.10f, 0.82f), Offset(0.10f, 0.18f)),
            listOf(Offset(0.50f, 0.48f), Offset(0.80f, 0.48f), Offset(0.80f, 0.73f), Offset(0.50f, 0.73f), Offset(0.50f, 0.48f)),
        ),
        dots = emptyList(),
    )

    IconRole.Calculator -> IconGeometry(
        strokes = listOf(
            listOf(Offset(0.20f, 0.10f), Offset(0.80f, 0.10f), Offset(0.80f, 0.90f), Offset(0.20f, 0.90f), Offset(0.20f, 0.10f)),
            listOf(Offset(0.30f, 0.20f), Offset(0.70f, 0.20f), Offset(0.70f, 0.37f), Offset(0.30f, 0.37f), Offset(0.30f, 0.20f)),
            listOf(Offset(0.32f, 0.54f), Offset(0.39f, 0.54f)),
            listOf(Offset(0.61f, 0.54f), Offset(0.68f, 0.54f)),
            listOf(Offset(0.32f, 0.70f), Offset(0.39f, 0.70f)),
            listOf(Offset(0.61f, 0.70f), Offset(0.68f, 0.70f)),
        ),
        dots = emptyList(),
    )

    IconRole.Refresh -> IconGeometry(
        strokes = listOf(
            // Clockwise open loop from 2 o'clock around the bottom and left back to 1 o'clock.
            listOf(
                Offset(0.84f, 0.50f),
                Offset(0.80f, 0.67f),
                Offset(0.67f, 0.80f),
                Offset(0.50f, 0.84f),
                Offset(0.33f, 0.80f),
                Offset(0.20f, 0.67f),
                Offset(0.16f, 0.50f),
                Offset(0.20f, 0.33f),
                Offset(0.33f, 0.20f),
                Offset(0.50f, 0.16f),
                Offset(0.68f, 0.21f),
                Offset(0.80f, 0.32f),
            ),
            // Arrowhead at the top-right end of the loop.
            listOf(Offset(0.80f, 0.14f), Offset(0.80f, 0.32f), Offset(0.62f, 0.32f)),
        ),
        dots = emptyList(),
    )

    IconRole.Delete -> IconGeometry(
        strokes = listOf(
            // The lid, a line wider than the bin.
            listOf(Offset(0.16f, 0.26f), Offset(0.84f, 0.26f)),
            // The handle on top of the lid.
            listOf(Offset(0.38f, 0.26f), Offset(0.38f, 0.14f), Offset(0.62f, 0.14f), Offset(0.62f, 0.26f)),
            // The bin, narrowing slightly toward the bottom.
            listOf(Offset(0.24f, 0.26f), Offset(0.28f, 0.86f), Offset(0.72f, 0.86f), Offset(0.76f, 0.26f)),
            // Two ribs down the front.
            listOf(Offset(0.42f, 0.40f), Offset(0.43f, 0.72f)),
            listOf(Offset(0.58f, 0.40f), Offset(0.57f, 0.72f)),
        ),
        dots = emptyList(),
    )
}
