package dioxus.compose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextOverflow as ComposeOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dioxus.compose.protocol.Alignment
import dioxus.compose.protocol.Arrangement as ProtocolArrangement
import dioxus.compose.protocol.ButtonVariant
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.protocol.SpaceRole
import dioxus.compose.protocol.TextAlign
import dioxus.compose.protocol.TextOverflow
import dioxus.compose.protocol.TypeRole
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.ui.node.Node
import dioxus.compose.design.composeLetterSpacing
import dioxus.compose.design.composeLineHeight
import dioxus.compose.design.composeWeight
import dioxus.compose.design.fontSize

/**
 * Reads design properties off a node and turns them into Compose values.
 *
 * Every role arrives as its wire tag in an integer property, and tag 0 is reserved for
 * "not sent", so an absent role is simply a missing property rather than a guessed default.
 */
internal fun <T : Enum<T>> Node.role(kind: PropertyKind, values: Array<T>): T? {
    val tag = (props[kind] as? PropertyValue.Integer)?.value ?: return null
    val index = tag.toInt() - 1
    return values.getOrNull(index)
}

internal fun Node.floatProp(kind: PropertyKind): Float? =
    when (val value = props[kind]) {
        is PropertyValue.Float -> value.value.takeIf { it != 0f }
        is PropertyValue.Integer -> value.value.toFloat().takeIf { it != 0f }
        else -> null
    }

internal fun Node.intProp(kind: PropertyKind): Long? =
    (props[kind] as? PropertyValue.Integer)?.value?.takeIf { it != 0L }

/** The `Color` property carries `Paint` bits, like every other colour slot. */
internal fun Node.paintProp(kind: PropertyKind): Paint? {
    val bits = intProp(kind) ?: return null
    val value = bits.toInt()
    return when ((bits ushr 32).toInt()) {
        1 -> ColorRole.entries.getOrNull(value - 1)?.let { Paint.Role(it) }
        2 -> Paint.Literal(value)
        else -> null
    }
}

internal fun Node.typeRole(): TypeRole? = role(PropertyKind.TypeRole, TypeRole.entries.toTypedArray())

internal fun Node.variant(): ButtonVariant =
    role(PropertyKind.Variant, ButtonVariant.entries.toTypedArray()) ?: ButtonVariant.Filled

/**
 * The text style for a node: the design system's rung of the ladder, with each override
 * replacing exactly one axis.
 */
internal fun Node.textStyle(theme: ResolvedTheme, defaultRole: TypeRole = TypeRole.Body): TextStyle {
    val role = typeRole() ?: defaultRole
    val token = theme.type(role)
    val paint = paintProp(PropertyKind.Color)
    return TextStyle(
        color = if (paint != null) theme.color(paint) else theme.color(ColorRole.OnSurface),
        fontSize = floatProp(PropertyKind.FontSize)?.sp ?: token.fontSize,
        fontWeight = intProp(PropertyKind.FontWeight)
            ?.let { androidx.compose.ui.text.font.FontWeight(it.toInt()) }
            ?: token.composeWeight,
        lineHeight = floatProp(PropertyKind.LineHeight)?.sp ?: token.composeLineHeight,
        letterSpacing = floatProp(PropertyKind.LetterSpacing)?.sp ?: token.composeLetterSpacing,
        fontFamily = theme.family(role),
        textAlign = when (role(PropertyKind.TextAlign, TextAlign.entries.toTypedArray())) {
            TextAlign.Start -> ComposeTextAlign.Start
            TextAlign.Center -> ComposeTextAlign.Center
            TextAlign.End -> ComposeTextAlign.End
            TextAlign.Justify -> ComposeTextAlign.Justify
            null -> ComposeTextAlign.Unspecified
        },
    )
}

internal fun Node.maxLines(): Int = intProp(PropertyKind.MaxLines)?.toInt() ?: Int.MAX_VALUE

internal fun Node.overflow(): ComposeOverflow =
    when (role(PropertyKind.Overflow, TextOverflow.entries.toTypedArray())) {
        TextOverflow.Clip -> ComposeOverflow.Clip
        TextOverflow.Ellipsis -> ComposeOverflow.Ellipsis
        TextOverflow.Visible -> ComposeOverflow.Visible
        null -> ComposeOverflow.Clip
    }

/** `spacing` is a literal dp, `space_role` goes through the table; the literal wins. */
internal fun Node.spacing(theme: ResolvedTheme): Dp {
    floatProp(PropertyKind.Spacing)?.let { return it.dp }
    val spaceRole = role(PropertyKind.SpaceRole, SpaceRole.entries.toTypedArray()) ?: return 0.dp
    return theme.space(spaceRole)
}

private fun Node.arrangementRole(): ProtocolArrangement? =
    role(PropertyKind.Arrangement, ProtocolArrangement.entries.toTypedArray())

internal fun Node.verticalArrangement(theme: ResolvedTheme): Arrangement.Vertical {
    val space = spacing(theme)
    val arrangement = arrangementRole()
    if (space.value > 0f) {
        return Arrangement.spacedBy(
            space,
            when (arrangement) {
                ProtocolArrangement.Center -> ComposeAlignment.CenterVertically
                ProtocolArrangement.End -> ComposeAlignment.Bottom
                else -> ComposeAlignment.Top
            },
        )
    }
    return when (arrangement) {
        ProtocolArrangement.Center -> Arrangement.Center
        ProtocolArrangement.End -> Arrangement.Bottom
        ProtocolArrangement.SpaceBetween -> Arrangement.SpaceBetween
        ProtocolArrangement.SpaceAround -> Arrangement.SpaceAround
        ProtocolArrangement.SpaceEvenly -> Arrangement.SpaceEvenly
        ProtocolArrangement.Start, null -> Arrangement.Top
    }
}

internal fun Node.horizontalArrangement(theme: ResolvedTheme): Arrangement.Horizontal {
    val space = spacing(theme)
    val arrangement = arrangementRole()
    if (space.value > 0f) {
        return Arrangement.spacedBy(
            space,
            when (arrangement) {
                ProtocolArrangement.Center -> ComposeAlignment.CenterHorizontally
                ProtocolArrangement.End -> ComposeAlignment.End
                else -> ComposeAlignment.Start
            },
        )
    }
    return when (arrangement) {
        ProtocolArrangement.Center -> Arrangement.Center
        ProtocolArrangement.End -> Arrangement.End
        ProtocolArrangement.SpaceBetween -> Arrangement.SpaceBetween
        ProtocolArrangement.SpaceAround -> Arrangement.SpaceAround
        ProtocolArrangement.SpaceEvenly -> Arrangement.SpaceEvenly
        ProtocolArrangement.Start, null -> Arrangement.Start
    }
}

private fun Node.alignmentRole(): Alignment? =
    role(PropertyKind.Alignment, Alignment.entries.toTypedArray())

/** A Column aligns its children on the horizontal axis. */
internal fun Node.horizontalAlignment(): ComposeAlignment.Horizontal =
    when (alignmentRole()) {
        Alignment.TopCenter, Alignment.Center, Alignment.BottomCenter ->
            ComposeAlignment.CenterHorizontally

        Alignment.TopEnd, Alignment.CenterEnd, Alignment.BottomEnd -> ComposeAlignment.End
        else -> ComposeAlignment.Start
    }

/** A Row aligns its children on the vertical axis. */
internal fun Node.verticalAlignment(): ComposeAlignment.Vertical =
    when (alignmentRole()) {
        Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd ->
            ComposeAlignment.CenterVertically

        Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd -> ComposeAlignment.Bottom
        else -> ComposeAlignment.Top
    }

/** A Box takes the full nine point alignment. */
internal fun Node.boxAlignment(): ComposeAlignment = when (alignmentRole()) {
    Alignment.TopStart, null -> ComposeAlignment.TopStart
    Alignment.TopCenter -> ComposeAlignment.TopCenter
    Alignment.TopEnd -> ComposeAlignment.TopEnd
    Alignment.CenterStart -> ComposeAlignment.CenterStart
    Alignment.Center -> ComposeAlignment.Center
    Alignment.CenterEnd -> ComposeAlignment.CenterEnd
    Alignment.BottomStart -> ComposeAlignment.BottomStart
    Alignment.BottomCenter -> ComposeAlignment.BottomCenter
    Alignment.BottomEnd -> ComposeAlignment.BottomEnd
}
