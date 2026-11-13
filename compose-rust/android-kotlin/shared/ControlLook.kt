package dev.darkpyonix.composerust.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.cornerCurve

/**
 * The colours and size the application asked a Switch, Slider, Checkbox or RadioButton to
 * draw with.
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
    /** The box of a checked Checkbox, or a RadioButton's ring. */
    val container: Color? = null,
    /** The check mark, or a RadioButton's dot. */
    val check: Color? = null,
    /** A Switch's width, or a Checkbox's side or a RadioButton's diameter. */
    val width: Dp? = null,
    /** A Switch's height. */
    val height: Dp? = null,
    /** The corner shape the node asked for, or null where the design system's applies. */
    val shape: Shape? = null,
) {
    /** Whether a toggle has to be drawn from this look rather than by default. */
    fun appliesTo(role: ToggleRole): Boolean = when (role) {
        ToggleRole.Switch -> track != null || thumb != null || width != null || height != null
        ToggleRole.Checkbox, ToggleRole.RadioButton -> container != null || check != null || width != null
    }

    companion object {
        val None = ControlLook()
    }
}

/**
 * The look of [node], rebuilt only when something it reads has changed.
 *
 * A recomposition that changes none of the six properties, the corner modifier or the theme
 * hands back the look it made last time, and a node that sends none of them gets the shared
 * [ControlLook.None], so the steady state allocates nothing. A property that is removed
 * makes its slot null, which differs from the value it held, so the look returns to the
 * defaults.
 */
@Composable
internal fun rememberControlLook(node: Node, theme: ResolvedTheme): ControlLook {
    val cache = remember { ControlLookCache() }
    return cache.read(node, theme)
}

private class ControlLookCache {
    private var primed = false
    private var theme: ResolvedTheme? = null
    private var track: PropertyValue? = null
    private var thumb: PropertyValue? = null
    private var activeTrack: PropertyValue? = null
    private var container: PropertyValue? = null
    private var check: PropertyValue? = null
    private var size: PropertyValue? = null
    private var corner: dev.darkpyonix.composerust.protocol.Modifier? = null
    private var named = false
    private var curve: Float? = null
    private var look: ControlLook = ControlLook.None

    fun read(node: Node, current: ResolvedTheme): ControlLook {
        val props = node.props
        val newTrack = props[PropertyKind.TrackColor]
        val newThumb = props[PropertyKind.ThumbColor]
        val newActive = props[PropertyKind.ActiveTrackColor]
        val newContainer = props[PropertyKind.ContainerColor]
        val newCheck = props[PropertyKind.CheckColor]
        val newSize = props[PropertyKind.ControlSize]
        var newNamed = false
        var newCorner: dev.darkpyonix.composerust.protocol.Modifier? = null
        val modifiers = node.modifiers
        val newCurve = modifiers.cornerCurve()
        for (index in modifiers.indices) {
            val value = modifiers[index]
            val isNamed = value is dev.darkpyonix.composerust.protocol.Modifier.Shape ||
                value is dev.darkpyonix.composerust.protocol.Modifier.ShapeRole
            if (isNamed) newNamed = true
            if (isNamed || value is dev.darkpyonix.composerust.protocol.Modifier.CornerEach) newCorner = value
        }
        if (primed && theme === current && track == newTrack && thumb == newThumb &&
            activeTrack == newActive && container == newContainer && check == newCheck &&
            size == newSize && named == newNamed && corner == newCorner && curve == newCurve
        ) {
            return look
        }
        primed = true
        theme = current
        track = newTrack; thumb = newThumb; activeTrack = newActive
        container = newContainer; check = newCheck; size = newSize
        named = newNamed; corner = newCorner; curve = newCurve
        val empty = newTrack == null && newThumb == null && newActive == null &&
            newContainer == null && newCheck == null && newSize == null && !newNamed
        look = if (empty) ControlLook.None else node.controlLook(current)
        return look
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
