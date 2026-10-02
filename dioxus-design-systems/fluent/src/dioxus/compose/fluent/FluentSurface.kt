package dioxus.compose.fluent

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dioxus.compose.ElevationStyle

/**
 * Draws a raised Fluent surface: its fill, its shadow, and the lit line along its top.
 *
 * Nothing else in this project draws the top stroke, and it is the detail that separates
 * a Fluent card from a rectangle with a shadow under it, so the drawing lives here rather
 * than being left as a value in [ElevationStyle] for every caller to remember.
 *
 * The shadow is drawn twice on purpose. Fluent specifies each of its depths as two
 * layers: a tight, darker one that sits right under the edge and reads as contact, and a
 * wide, fainter one that reads as distance from the page. One shadow at the full depth
 * gives a soft blob with no edge; one at the tight depth gives a hard edge with no
 * depth. Material needs neither because it tints the surface instead.
 */
fun Modifier.fluentSurface(style: ElevationStyle, shape: Shape): Modifier {
    if (style.shadowElevation <= 0.dp) {
        return this.background(color = style.surface, shape = shape)
    }
    val ambient = style.shadowColor.copy(alpha = style.shadowColor.alpha * AMBIENT_SHARE)
    return this
        .shadow(
            elevation = style.shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = ambient,
            spotColor = ambient,
        )
        .shadow(
            elevation = style.shadowElevation * CONTACT_SHARE,
            shape = shape,
            clip = false,
            ambientColor = style.shadowColor,
            spotColor = style.shadowColor,
        )
        .background(color = style.surface, shape = shape)
        .then(if (style.strokeTop == null) Modifier else TopStroke(style))
}

private fun TopStroke(style: ElevationStyle): Modifier = Modifier.drawWithContent {
    drawContent()
    val stroke = style.strokeTop ?: return@drawWithContent
    val width = 1.dp.toPx()
    // Inset by the corner radius so the line stops where the top edge starts curving
    // away, which is where a real lit edge would fall off.
    val inset = 4.dp.toPx()
    drawLine(
        color = stroke,
        start = Offset(inset, width / 2f),
        end = Offset(size.width - inset, width / 2f),
        strokeWidth = width,
    )
}

/** How much of the specified shadow the wide, distant layer carries. */
private const val AMBIENT_SHARE = 0.6f

/** How tight the contact layer is relative to the specified depth. */
private const val CONTACT_SHARE = 0.25f
