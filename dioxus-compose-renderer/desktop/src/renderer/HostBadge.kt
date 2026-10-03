package dioxus.compose.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dioxus.compose.design.BadgePlacement
import dioxus.compose.design.BadgeStyle
import dioxus.compose.design.ResolvedTheme
import dioxus.compose.protocol.ColorRole
import dioxus.compose.protocol.Paint
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.PropertyValue
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.NodeTable
import dioxus.compose.ui.node.RenderNode
import dioxus.compose.ui.node.nodeTestTag
import dioxus.compose.ui.paintProp

/** Test tag of the mark itself, as distinct from the badge node and what it is attached to. */
fun badgeMarkTestTag(nodeId: Int): String = "${nodeTestTag(nodeId)}-mark"

/**
 * What a badge says, before any design system has shortened it.
 *
 * A count wins where both a count and a word arrived, because a count is the more specific
 * of the two. Null is a dot.
 */
internal sealed interface BadgeContent {
    data class Count(val value: Long) : BadgeContent

    data class Word(val text: String) : BadgeContent
}

internal fun Node.badgeContent(): BadgeContent? {
    // Read raw rather than through the helpers that treat zero as unset: a count of zero
    // is a count, and it is not the same thing as a dot.
    val count = (property(PropertyKind.Count) as? PropertyValue.Integer)?.value
    if (count != null) return BadgeContent.Count(count)
    val word = text(PropertyKind.Text)
    return if (word.isEmpty()) null else BadgeContent.Word(word)
}

/** What the mark shows: the count as this system writes it, the word, or nothing for a dot. */
internal fun BadgeContent?.label(style: BadgeStyle): String? = when (this) {
    is BadgeContent.Count -> style.label(value)
    is BadgeContent.Word -> text
    null -> null
}

/**
 * What a reader hears added to the thing the badge is attached to.
 *
 * The count in full, never the shortened label: `99+` is a way of fitting a number into a
 * small mark, and a screen reader has no small mark to fit it into. A dot says that there is
 * something new, which is the only thing a dot means.
 */
internal fun BadgeContent?.description(): String = when (this) {
    is BadgeContent.Count -> value.toString()
    is BadgeContent.Word -> text
    null -> "new"
}

/**
 * The role a badge is filled with. Only a role is read: a literal colour has no path here,
 * so one that arrived anyway is ignored and the badge is drawn in the error role every one
 * of these systems uses for an unread count.
 */
internal fun Node.badgeRole(): ColorRole =
    (paintProp(PropertyKind.Color) as? Paint.Role)?.role ?: ColorRole.Error

/** The colour that is legible on [role], from the same pairs the token table is built of. */
internal fun contentOn(role: ColorRole): ColorRole = when (role) {
    ColorRole.Primary -> ColorRole.OnPrimary
    ColorRole.OnPrimary -> ColorRole.Primary
    ColorRole.Secondary -> ColorRole.OnSecondary
    ColorRole.OnSecondary -> ColorRole.Secondary
    ColorRole.Tertiary -> ColorRole.OnTertiary
    ColorRole.OnTertiary -> ColorRole.Tertiary
    ColorRole.Error -> ColorRole.OnError
    ColorRole.OnError -> ColorRole.Error
    ColorRole.Surface -> ColorRole.OnSurface
    ColorRole.OnSurface -> ColorRole.Surface
    ColorRole.SurfaceVariant -> ColorRole.OnSurfaceVariant
    ColorRole.OnSurfaceVariant -> ColorRole.SurfaceVariant
    ColorRole.SurfaceContainer -> ColorRole.OnSurface
    ColorRole.Background -> ColorRole.OnBackground
    ColorRole.OnBackground -> ColorRole.Background
    ColorRole.Outline -> ColorRole.Surface
    ColorRole.OutlineVariant -> ColorRole.OnSurface
    ColorRole.PrimaryContainer -> ColorRole.OnPrimaryContainer
    ColorRole.OnPrimaryContainer -> ColorRole.PrimaryContainer
    ColorRole.SecondaryContainer -> ColorRole.OnSecondaryContainer
    ColorRole.OnSecondaryContainer -> ColorRole.SecondaryContainer
    ColorRole.TertiaryContainer -> ColorRole.OnTertiaryContainer
    ColorRole.OnTertiaryContainer -> ColorRole.TertiaryContainer
    // The code inks are drawn on the code panel, so that is what reads under them; the
    // diff backgrounds carry body text.
    ColorRole.SyntaxKeyword,
    ColorRole.SyntaxString,
    ColorRole.SyntaxComment,
    ColorRole.SyntaxNumber,
    ColorRole.SyntaxConstant,
    ColorRole.SyntaxType,
    ColorRole.SyntaxFunction,
    ColorRole.SyntaxVariable,
    ColorRole.SyntaxProperty,
    ColorRole.SyntaxOperator,
    ColorRole.SyntaxPunctuation,
    ColorRole.SyntaxTag,
    ColorRole.SyntaxAttribute,
    ColorRole.SyntaxEscape,
    ColorRole.SyntaxMacro,
    ColorRole.DiffAdded,
    ColorRole.DiffRemoved,
    ColorRole.DiffModified,
    -> ColorRole.SurfaceContainer
    ColorRole.DiffAddedContainer,
    ColorRole.DiffRemovedContainer,
    ColorRole.DiffAddedEmphasis,
    ColorRole.DiffRemovedEmphasis,
    -> ColorRole.OnSurface
}

/**
 * A badge: a count, a word or a dot, attached to its child or standing on its own.
 *
 * Attached, the badge is placed by the design system, over the corner or at the end of the
 * line, and what it says is added to the child's own accessibility description. The mark
 * itself is hidden from the accessibility tree, so it is never read as a separate thing.
 *
 * Only the mark reads the count. The child is drawn through a modifier that is created once
 * for this node and never replaced, so a new count redraws the mark and refreshes the
 * description without composing the child again.
 */
@Composable
internal fun HostBadge(
    node: Node,
    modifier: Modifier,
    table: NodeTable,
    dispatcher: EventDispatcher,
    theme: ResolvedTheme,
) {
    val style = theme.rules.badge(theme)
    val children = node.children
    if (children.isEmpty()) {
        // On its own there is nothing to add the description to, so the mark carries it,
        // in full rather than in the shortened form the mark may show.
        BadgeMark(node, style, theme, modifier, standalone = true)
        return
    }
    val description = remember(node) { derivedStateOf { node.badgeContent().description() } }
    val describe = remember(description) { Modifier.then(BadgeDescriptionElement(description)) }
    // The first child is what the badge is attached to. A badge is declared with one; any
    // more are drawn with it, stacked as a Box stacks them, rather than lost.
    val attached: @Composable () -> Unit = {
        Box {
            children.forEachIndexed { index, childId ->
                key(childId) {
                    RenderNode(
                        childId,
                        table,
                        dispatcher,
                        if (index == 0) describe else Modifier,
                    )
                }
            }
        }
    }
    when (style.placement) {
        BadgePlacement.Overlap -> Layout(
            content = {
                attached()
                BadgeMark(node, style, theme, Modifier, standalone = false)
            },
            modifier = modifier,
        ) { measurables, constraints ->
            val child = measurables[0].measure(constraints)
            val mark = measurables[1].measure(Constraints())
            // Centred on the child's top trailing corner, then moved by the system's
            // offset. It is allowed to hang outside the child's bounds, which is what an
            // overlapping badge does, and it does not grow the space the child takes.
            val x = child.width - mark.width / 2 + style.offsetX.roundToPx()
            val y = -mark.height / 2 + style.offsetY.roundToPx()
            layout(child.width, child.height) {
                child.place(0, 0)
                mark.place(x, y, zIndex = 1f)
            }
        }

        BadgePlacement.Trailing -> Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(style.gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attached()
            BadgeMark(node, style, theme, Modifier, standalone = false)
        }
    }
}

/**
 * The mark itself. Its own composable, so that a new count recomposes this and nothing
 * around it.
 */
@Composable
private fun BadgeMark(
    node: Node,
    style: BadgeStyle,
    theme: ResolvedTheme,
    modifier: Modifier,
    standalone: Boolean,
) {
    val content = node.badgeContent()
    val label = content.label(style)
    val role = node.badgeRole()
    val fill = theme.color(role)
    val ink = theme.color(contentOn(role))
    val described = if (standalone) {
        val spoken = content.description()
        modifier.clearAndSetSemantics { contentDescription = spoken }
    } else {
        modifier.clearAndSetSemantics { }
    }
    val tagged = described.testTag(badgeMarkTestTag(node.id))
    val ringed = if (style.ringWidth > 0.dp) {
        tagged.border(style.ringWidth, style.ring, style.shape)
    } else {
        tagged
    }
    if (label == null) {
        Box(ringed.size(style.dotSize).background(fill, style.shape))
        return
    }
    Box(
        ringed
            .height(style.height)
            .defaultMinSize(minWidth = style.height)
            .background(fill, style.shape)
            .padding(horizontal = style.horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = ink,
                fontSize = style.labelSize,
                fontWeight = style.labelWeight,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Adds what a badge says to the description of the thing it is attached to.
 *
 * A modifier node rather than `Modifier.semantics { }`, because the badge's content changes
 * while the child stays as it is. A semantics block captured in composition would have to
 * be rebuilt for every new count, and a new modifier on the child composes the child again.
 * This one is created once per badge and asks for its semantics to be read again when the
 * description changes.
 *
 * It is a state description rather than a content description. A content description
 * replaces the child's own text on some platforms, so "Inbox" would be read as "3"; a state
 * description is read after it, which is "Inbox, 3".
 */
private class BadgeDescriptionElement(
    private val description: State<String>,
) : ModifierNodeElement<BadgeDescriptionNode>() {
    override fun create(): BadgeDescriptionNode = BadgeDescriptionNode(description)

    override fun update(node: BadgeDescriptionNode) {
        node.description = description
        node.invalidateSemantics()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "badgeDescription"
    }

    override fun equals(other: Any?): Boolean =
        other is BadgeDescriptionElement && other.description === description

    override fun hashCode(): Int = description.hashCode()
}

private class BadgeDescriptionNode(
    var description: State<String>,
) : Modifier.Node(), SemanticsModifierNode {
    override fun onAttach() {
        coroutineScope.launch {
            snapshotFlow { description.value }.collect { invalidateSemantics() }
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        stateDescription = description.value
    }
}
