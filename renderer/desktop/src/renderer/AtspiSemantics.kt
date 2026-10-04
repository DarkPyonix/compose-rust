@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Compose's semantics tree, read into the tree a reader walks.
 *
 * This is the only file that knows both vocabularies. It is told when a tree changed, reads it
 * after the frame that placed it (a control asked for its bounds before that answers with an
 * empty rectangle, and a reader given those finds the whole window stacked in one corner), and
 * keeps the live nodes so that an action asked for over the bus is performed on the control it
 * was asked of.
 *
 * Everything runs on the thread that draws. The bus is read on that thread too, so an action
 * arrives between frames and nothing here is shared.
 */
internal class AtspiSemanticsSource : PlatformContext.SemanticsOwnerListener {

    private val owners = ArrayList<SemanticsOwner>()
    private var changed = true
    private var live: Map<Int, SemanticsNode> = emptyMap()

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (semanticsOwner !in owners) owners += semanticsOwner
        changed = true
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        owners.remove(semanticsOwner)
        changed = true
    }

    // Every callback remembers which tree it was about, as the other listener does: a scene
    // can report a change without ever having reported an appearance.
    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        if (semanticsOwner !in owners) owners += semanticsOwner
        changed = true
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        if (semanticsOwner !in owners) owners += semanticsOwner
        changed = true
    }

    /**
     * The tree as it is now, or null when nothing has changed since it was last read.
     *
     * A tree equal to the last one is not worth saying either, but that comparison is the
     * caller's: it holds the last one.
     */
    fun capture(title: String): AtspiTree? {
        if (!changed) return null
        changed = false
        val nodes = LinkedHashMap<Int, AtspiNode>()
        val nodesLive = HashMap<Int, SemanticsNode>()
        val roots = ArrayList<Int>()
        var focused: Int? = null
        var width = 0
        var height = 0

        fun walk(node: SemanticsNode, parent: Int, siblings: MutableList<Int>) {
            val facts = node.facts()
            val described = describeFacts(facts)
            if (described == null) {
                // Layout. What is inside it belongs to whatever it was inside.
                for (child in node.children) walk(child, parent, siblings)
                return
            }
            val children = ArrayList<Int>()
            for (child in node.children) walk(child, node.id, children)
            val bounds = node.boundsInRoot
            nodes[node.id] = AtspiNode(
                id = node.id,
                parent = parent,
                children = children,
                role = described.role,
                name = described.name,
                states = described.states,
                actions = described.actions,
                x = bounds.left.toInt(),
                y = bounds.top.toInt(),
                width = bounds.width.toInt(),
                height = bounds.height.toInt(),
                text = facts.editableText,
                caret = node.config.getOrNull(SemanticsProperties.TextSelectionRange)?.end
                    ?: facts.editableText?.length ?: 0,
            )
            nodesLive[node.id] = node
            siblings += node.id
            if (facts.focused) focused = node.id
        }

        for (owner in owners) {
            val root = owner.rootSemanticsNode
            width = maxOf(width, root.boundsInRoot.right.toInt())
            height = maxOf(height, root.boundsInRoot.bottom.toInt())
            walk(root, AtspiTree.FRAME, roots)
        }
        live = nodesLive
        return AtspiTree(title, nodes, roots, focused, width, height)
    }

    /** Presses the control, as a click on it would. False where it is gone or cannot be. */
    fun click(id: Int): Boolean =
        live[id]?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false

    /** Gives the control the keyboard. False where it is gone or cannot take it. */
    fun focus(id: Int): Boolean =
        live[id]?.config?.getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() ?: false
}

private fun SemanticsNode.facts(): NodeFacts {
    val config = config
    val description = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    val shown = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    val editable = config.getOrNull(SemanticsProperties.EditableText)?.text
    // A field's own text is its value and not its name. Where the text the field shows is the
    // text it holds, it is left out of the name so that a reader does not say it twice.
    val label = description ?: shown?.takeIf { it != editable }
    return NodeFacts(
        composeRole = when (config.getOrNull(SemanticsProperties.Role)) {
            Role.Button -> "Button"
            Role.Checkbox -> "Checkbox"
            Role.Switch -> "Switch"
            Role.RadioButton -> "RadioButton"
            Role.Tab -> "Tab"
            Role.Image -> "Image"
            Role.DropdownList -> "DropdownList"
            else -> null
        },
        label = label,
        editableText = editable,
        password = config.contains(SemanticsProperties.Password),
        multiline = false,
        toggle = config.getOrNull(SemanticsProperties.ToggleableState)?.name,
        selected = config.getOrNull(SemanticsProperties.Selected),
        focused = config.getOrNull(SemanticsProperties.Focused) == true,
        disabled = config.contains(SemanticsProperties.Disabled),
        clickable = config.getOrNull(SemanticsActions.OnClick) != null,
        canTakeFocus = config.getOrNull(SemanticsActions.RequestFocus) != null,
        heading = config.contains(SemanticsProperties.Heading),
        ranged = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null,
        adjustable = config.getOrNull(SemanticsActions.SetProgress) != null,
    )
}
