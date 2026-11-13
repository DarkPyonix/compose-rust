package dev.darkpyonix.composerust.foundation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow as ComposeOverflow
import dev.darkpyonix.composerust.protocol.ButtonKind
import dev.darkpyonix.composerust.protocol.ButtonVariant
import dev.darkpyonix.composerust.protocol.ColorRole
import dev.darkpyonix.composerust.protocol.HostEvent
import dev.darkpyonix.composerust.protocol.IconRole
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.SpaceRole
import dev.darkpyonix.composerust.protocol.TextAlign
import dev.darkpyonix.composerust.design.compositeOver
import dev.darkpyonix.composerust.design.ensureContrast
import dev.darkpyonix.composerust.protocol.Modifier as ProtocolModifier
import dev.darkpyonix.composerust.ui.resolvedShape
import dev.darkpyonix.composerust.ui.cornerCurve
import dev.darkpyonix.composerust.ui.withCornerCurve
import dev.darkpyonix.composerust.design.ButtonStyle
import dev.darkpyonix.composerust.design.ResolvedTheme
import dev.darkpyonix.composerust.runtime.EventDispatcher
import dev.darkpyonix.composerust.ui.node.Children
import dev.darkpyonix.composerust.ui.node.LocalInheritedTextColor
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable
import dev.darkpyonix.composerust.ui.pressFeedback
import dev.darkpyonix.composerust.ui.groupAlpha
import dev.darkpyonix.composerust.ui.intProp
import dev.darkpyonix.composerust.ui.overflow
import dev.darkpyonix.composerust.ui.paintProp
import dev.darkpyonix.composerust.ui.role
import dev.darkpyonix.composerust.ui.textStyle
import dev.darkpyonix.composerust.ui.buttonKind
import dev.darkpyonix.composerust.ui.variant

/**
 * A Button drawn by the active design system's component rules.
 *
 * The same rsx deliberately produces different pixels per system: the shape, the label
 * weight and the press feedback all come from [ComponentRules], not from this function.
 */
@Composable
internal fun HostButton(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val enabled = node.flag(PropertyKind.Enabled, default = true)
    val handlerId = node.handler(PropertyKind.OnClick)
    val inMenu = LocalInAMenu.current || node.property(PropertyKind.Checked) != null
    val checked = node.flag(PropertyKind.Checked, default = false)
    val alignStart = inMenu ||
        node.role(PropertyKind.TextAlign, TextAlign.entries.toTypedArray()) == TextAlign.Start
    val style = buttonStyle(node.variant(), node.buttonKind(), theme).let { base ->
        val entry = if (inMenu) theme.rules.menuEntry(base, theme) else base
        if (inMenu && node.variant() == ButtonVariant.Text) {
            entry.copy(
                container = if (checked) theme.color(ColorRole.SurfaceVariant) else entry.container,
                content = theme.color(ColorRole.OnSurface),
            )
        } else {
            entry
        }
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion = theme.rules.motion
    val spec = tween<Float>(
        durationMillis = if (pressed) motion.pressMillis else motion.releaseMillis,
        easing = motion.easing,
    )
    val container by animateColorAsState(
        targetValue = if (pressed) style.pressedContainer else style.container,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) style.pressedBorderColor else style.borderColor,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (pressed) style.pressedContentAlpha else 1f,
        animationSpec = spec,
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) style.pressedElevation else style.restElevation,
        animationSpec = tween(spec.durationMillis, easing = motion.easing),
    )

    // A button that cannot be pressed has to look it. Without this the only way to find
    // out was to press it, because refusing the click was all `enabled` did. The whole
    // control fades as one group rather than each variant naming a disabled colour, so a
    // design system owes one number instead of four, the faded colours are still its own,
    // and a background or border the Host set on the node's modifier chain fades with the
    // label instead of staying opaque behind it. The label is the exception: it is drawn
    // again outside the fade, in a colour that keeps its contrast with the faded fill.
    val available = if (enabled) Modifier else Modifier.groupAlpha { style.disabledAlpha }
    val overlayFocusStack = LocalOverlayFocusStack.current
    val focusRequester = remember(node.id) { FocusRequester() }
    val ownShape = node.modifiers.any {
        it is ProtocolModifier.Shape ||
            it is ProtocolModifier.ShapeRole ||
            it is ProtocolModifier.CornerEach
    }
    val shape = if (ownShape) node.modifiers.resolvedShape(theme) else style.shape.withCornerCurve(node.modifiers.cornerCurve())
    val hasExplicitSize = node.modifiers.any {
        it is ProtocolModifier.Size ||
            it is ProtocolModifier.Width ||
            it is ProtocolModifier.Height ||
            it is ProtocolModifier.RequiredSize
    }
    val setsOwnHeight = node.modifiers.any {
        it is ProtocolModifier.Size ||
            it is ProtocolModifier.Height ||
            it is ProtocolModifier.RequiredSize
    }
    // Read before the decoration, because how much room the button needs depends on it.
    val wordless = node.text(PropertyKind.Text).isEmpty() &&
        node.role(PropertyKind.Icon, IconRole.entries.toTypedArray()) != null
    val labelPadH = if (wordless && hasExplicitSize) 0.dp else if (wordless) style.verticalPadding else style.horizontalPadding
    val labelPadV = if (wordless && hasExplicitSize) 0.dp else style.verticalPadding

    // A label is one line unless the Host asked for more. Left to wrap, a squeezed button
    // is measured at the narrowest a Hangul label can be, one syllable, and '보내' breaks
    // into '보' over '내'. With no soft wrap the label's narrowest width is its whole line.
    val lines = node.intProp(PropertyKind.MaxLines)?.toInt()?.takeIf { it > 0 } ?: 1

    val clickable = if (handlerId == null) {
        available.then(modifier)
    } else {
        // The Host result is not used here: a click that reached a Button is never offered
        // to anything below it. Indication is null because the design system owns the
        // feedback, and a ripple is only one system's answer.
        available.then(modifier)
            .focusRequester(focusRequester)
            .onFocusChanged { state -> overlayFocusStack.noteFocused(focusRequester, state.isFocused) }
            .pressFeedback(
                interactions, enabled, theme, shape,
                node.paintProp(PropertyKind.Color)?.let { theme.color(it) } ?: style.content,
                node.modifiers.lastOrNull { it is dev.darkpyonix.composerust.protocol.Modifier.PressFeedback } as? dev.darkpyonix.composerust.protocol.Modifier.PressFeedback,
            ).clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
            ) { dispatcher.dispatch(HostEvent.Clicked(node.id, handlerId)) }
    }

    // A Host that named its own Background has already had it applied to this node's
    // modifier chain, so painting the variant's fill here would cover it. The shape, the
    // border, the resting height and the padding still come from the design system: only
    // the colour is the application's, which is the rule a Container already follows.
    //
    // A unified sample is what needs it. Its reference names a mint chip and a white
    // pill, and asking for a variant instead hands the question to whichever design
    // system is active, which answers with its own accent.
    val ownFill = node.setsOwnBackground()
    val decorated = theme.rules
        .elevation(clickable, elevation, shape, theme)
        .clip(shape)
        .then(if (ownFill) Modifier else Modifier.background(container, shape))
        .then(
            if (style.borderWidth.value > 0f) {
                Modifier.border(style.borderWidth, borderColor, shape)
            } else {
                Modifier
            },
        )
        .topHighlight(style.topHighlight)
        .let {
            if (setsOwnHeight || (wordless && hasExplicitSize)) it else it.defaultMinSize(minHeight = style.minHeight)
        }
        // A button with a glyph and no word gets the same room on both axes. The
        // horizontal padding is there to keep a word off the button's ends, and a button
        // with no word has nothing to keep off them: using it anyway makes every icon
        // control half as wide again as it needs to be, which two of them in a floating
        // group and one at the head of a composer both showed.
        .labelPadding(
            horizontal = labelPadH,
            vertical = labelPadV,
            alignStart = alignStart,
            labelLines = if (node.children.isEmpty()) lines else 0,
        )

    // The variant decides the label colour, unless the node names one itself. A
    // destructive action is the case that needs it: it is a plain button in every design
    // system, and what marks it is that its label is the error colour.
    val content = node.paintProp(PropertyKind.Color)?.let { theme.color(it) } ?: style.content
    val tint = if (enabled) {
        content.copy(alpha = content.alpha * contentAlpha)
    } else {
        val fill = node.modifiers.filterIsInstance<ProtocolModifier.Background>().lastOrNull()
            ?.let { theme.color(it.paint) } ?: container
        // The surface behind the button is not known here, so the label has to hold its
        // contrast over each surface a screen puts a button on: the page, a card, a raised card.
        val parents = listOf(ColorRole.Background, ColorRole.Surface, ColorRole.SurfaceContainer)
            .map { theme.color(it) }
        val faded = parents.map { compositeOver(fill.copy(alpha = fill.alpha * style.disabledAlpha), it) }
        var result = compositeOver(content.copy(alpha = content.alpha * style.disabledAlpha), faded.first())
        repeat(2) { for (f in faded) result = ensureContrast(result, f, MIN_DISABLED_LABEL_CONTRAST) }
        result
    }
    val label = node.textStyle(theme, style.typeRole).copy(color = tint)
    val text = node.text(PropertyKind.Text)
    val icon = node.role(PropertyKind.Icon, IconRole.entries.toTypedArray())
    val ellipsis = node.overflow(ComposeOverflow.Ellipsis)
    // A button with a glyph and no word is still a button with a name. The role is what
    // the glyph means, so it is what the name is made of, and assistive technology never
    // meets an unnamed control.
    val named = decorated
        .let {
            // The Host's own name for the button is read instead of the icon's meaning.
            if (icon != null && text.isEmpty() && node.text(PropertyKind.AccessibleName).isEmpty()) {
                it.semantics { contentDescription = icon.name }
            } else {
                it
            }
        }
        .let { if (node.property(PropertyKind.Checked) != null) it.semantics { selected = checked } else it }
    // The row gap between the glyph and the word is the design system's smallest step, so a
    // dense language sets them closer than a roomy one does.
    @Composable
    fun ButtonLabel() {
        if (node.children.isNotEmpty()) {
            // Children replace the label. They are laid out as a Row centred vertically, so
            // a weighted Spacer pushes whatever follows it to the button's inner edge.
            CompositionLocalProvider(LocalInheritedTextColor provides ColorProducer { tint }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Children(node, table, dispatcher)
                }
            }
        } else if (icon == null) {
            ThemedText(text = text, style = label, maxLines = lines, softWrap = lines > 1, overflow = ellipsis)
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(theme.space(SpaceRole.Xs)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleIcon(icon, tint, theme)
                if (text.isNotEmpty()) ThemedText(text = text, style = label, maxLines = lines, softWrap = lines > 1, overflow = ellipsis)
            }
        }
    }

    val alignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    // A button with children is faded as one group, children included: its content is the
    // Host's to colour, so there is no label tint to keep readable, and composing the
    // children twice would draw and expose each of them twice.
    if (enabled || node.children.isNotEmpty()) {
        Box(named, contentAlignment = alignment) { ButtonLabel() }
    } else {
        Box {
            Box(named, contentAlignment = alignment) {
                // Only here to give the button its size; the label assistive technology
                // reads and the user sees is the one drawn on top.
                Box(Modifier.alpha(0f).clearAndSetSemantics { }) { ButtonLabel() }
            }
            Box(
                Modifier.matchParentSize().labelPadding(
                    horizontal = labelPadH,
                    vertical = labelPadV,
                    alignStart = alignStart,
                    labelLines = lines,
                ),
                contentAlignment = alignment,
            ) { ButtonLabel() }
        }
    }
}

// Slightly above 3:1 so anti-aliased glyph edges still measure 3:1.
private const val MIN_DISABLED_LABEL_CONTRAST = 3.3f

/**
 * How a button of this variant and kind looks under this design system.
 *
 * The variant chooses the fill and the emphasis, whatever the kind. An action key, one key
 * of a dense grid such as a calculator's pad, takes its silhouette from the system's own
 * answer for that grid instead of from the variant: a circle on the Apple systems, a small
 * radius on Windows 11, a rounder one on Deepin. A standard button is exactly what it was
 * before kinds existed.
 */
internal fun buttonStyle(variant: ButtonVariant, kind: ButtonKind, theme: ResolvedTheme): ButtonStyle {
    val base = theme.rules.button(variant, theme)
    return when (kind) {
        ButtonKind.Standard -> base
        ButtonKind.ActionKey -> base.copy(shape = theme.rules.actionKeyShape(theme))
    }
}

/** Fluent's lighter top edge. Other systems pass null and pay nothing. */
private fun Modifier.topHighlight(color: Color?): Modifier {
    if (color == null) return this
    return drawWithContent {
        drawContent()
        val thickness = 1.dpPx(density)
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(0f, thickness / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, thickness / 2f),
            strokeWidth = thickness,
        )
    }
}

private fun Int.dpPx(density: Float): Float = this * density

/**
 * Padding that gives way before the label does.
 *
 * Both paddings are the design system's, but only as far as the size the button was given
 * leaves room for. When the height is smaller than the vertical padding plus the content's
 * own height, the padding shrinks equally top and bottom, down to nothing, so the content
 * keeps its whole height and sits centred. Only when even no padding fits does the content
 * overflow and get clipped by the button's shape.
 *
 * The width works the same way for a label: one the button draws from its own text and
 * icon, as opposed to children the Host laid out ([labelLines] is above zero). The padding
 * on each side is the design system's value only as far as the width leaves room for the
 * label, and otherwise shrinks equally on both sides, down to nothing. A fixed padding
 * wider than the button left the label no room at all: Material's 24dp a side in a 36dp
 * round button measured '+' at zero width, and it came out as a lone ellipsis dot. The
 * room a one-line label needs is its whole line; a label allowed more lines needs its
 * longest word, since wrapping does not lose it. Children fill the width they are offered,
 * so there is no width of theirs to keep, and they keep the fixed padding.
 */
internal fun Modifier.labelPadding(
    horizontal: Dp,
    vertical: Dp,
    alignStart: Boolean = false,
    labelLines: Int = 0,
): Modifier = layout { measurable, constraints ->
    val wantedSides = horizontal.roundToPx()
    val wanted = vertical.roundToPx()
    val sides = if (labelLines > 0 && constraints.hasBoundedWidth && wantedSides > 0) {
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
        val needed = if (labelLines == 1) {
            measurable.maxIntrinsicWidth(height)
        } else {
            measurable.minIntrinsicWidth(height)
        }
        minOf(wantedSides, ((constraints.maxWidth - needed) / 2).coerceAtLeast(0))
    } else {
        wantedSides
    }
    val inner = Constraints(
        minWidth = (constraints.minWidth - 2 * sides).coerceAtLeast(0),
        maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * sides).coerceAtLeast(0) else Constraints.Infinity,
        minHeight = 0,
        maxHeight = constraints.maxHeight,
    )
    val placeable = measurable.measure(inner)
    val room = if (constraints.hasBoundedHeight) {
        ((constraints.maxHeight - placeable.height) / 2).coerceAtLeast(0)
    } else {
        wanted
    }
    val padding = minOf(wanted, room)
    val width = (placeable.width + 2 * sides).coerceIn(constraints.minWidth, constraints.maxWidth)
    val height = (placeable.height + 2 * padding).coerceIn(constraints.minHeight, constraints.maxHeight)
    val x = if (alignStart) sides.coerceAtMost((width - placeable.width).coerceAtLeast(0)) else (width - placeable.width) / 2
    layout(width, height) {
        placeable.place(x, (height - placeable.height) / 2)
    }
}
