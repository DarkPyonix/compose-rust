package dev.darkpyonix.composerust.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.resolvedShape

/**
 * The colours and size the application asked a Switch, Slider or Checkbox to draw with.
 *
 * Every slot is null until the node sent it, and a slot that is null keeps what the design
 * system draws. A look with nothing in it changes nothing, which is how a control that
 * sends none of these properties stays exactly as it was.
 */
class ControlLook(
    /** The track of a Switch, or the part of a Slider's track that is not filled. */
    val track: Color? = null,
    /** The travelling thumb of a Switch or a Slider. */
    val thumb: Color? = null,
    /** The filled part of a Slider's track. */
    val activeTrack: Color? = null,
    /** The box of a checked Checkbox. */
    val container: Color? = null,
    /** The check mark. */
    val check: Color? = null,
    /** A Switch's width, or a Checkbox's side. */
    val width: Dp? = null,
    /** A Switch's height. */
    val height: Dp? = null,
    /** The corner shape the node asked for, or null where the design system's applies. */
    val shape: Shape? = null,
) {
    /** Whether a Switch or a Checkbox has to be drawn from this look rather than by default. */
    fun appliesTo(role: ToggleRole): Boolean = when (role) {
        ToggleRole.Switch -> track != null || thumb != null || width != null || height != null
        ToggleRole.Checkbox -> container != null || check != null || width != null
        ToggleRole.RadioButton -> false
    }

    companion object {
        val None = ControlLook()
    }
}

/** Reads the look a node asked for. Colours go through the theme like every other paint. */
internal fun Node.controlLook(theme: ResolvedTheme): ControlLook {
    fun colour(kind: PropertyKind): Color? = paintProp(kind)?.let { theme.color(it) }
    val size = bytes(PropertyKind.ControlSize)?.takeIf { it.size >= 8 }
    val width = size?.let { littleEndianFloat(it, 0) }?.takeIf { it > 0f }?.dp
    val height = size?.let { littleEndianFloat(it, 4) }?.takeIf { it > 0f }?.dp
    val named = modifiers.any {
        it is dev.darkpyonix.composerust.protocol.Modifier.Shape ||
            it is dev.darkpyonix.composerust.protocol.Modifier.ShapeRole
    }
    return ControlLook(
        track = colour(PropertyKind.TrackColor),
        thumb = colour(PropertyKind.ThumbColor),
        activeTrack = colour(PropertyKind.ActiveTrackColor),
        container = colour(PropertyKind.ContainerColor),
        check = colour(PropertyKind.CheckColor),
        width = width,
        height = height,
        shape = if (named) modifiers.resolvedShape(theme) else null,
    )
}

private fun littleEndianFloat(bytes: ByteArray, at: Int): Float {
    var bits = 0
    for (index in 3 downTo 0) bits = (bits shl 8) or (bytes[at + index].toInt() and 0xff)
    return Float.fromBits(bits)
}
