@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dioxus.compose.ui.platform

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role

// What the window tells a reader who cannot see it.
//
// The scene already knows: every control Compose draws carries semantics, and a tree of
// them is what a screen reader wants. What is missing is a way for that tree to reach the
// platform, and the platform asks for it on its own thread at moments nobody chose. So
// the tree is flattened and pushed whenever it changes, and the shell answers from what
// it was last given.
//
// Flattened rather than kept as a tree. A first tree that says what is there and where is
// worth more than a perfect shape nobody has tested, and the nesting can follow once a
// reader has been heard using this.

/** What a control is, in the small set both sides agree on. */
internal object ElementRole {
    const val GROUP = 0
    const val BUTTON = 1
    const val TEXT = 2
    const val FIELD = 3
    const val CHECKBOX = 4
    const val IMAGE = 5
}

/** One thing in the window, as a reader would meet it. */
data class AccessibleElement(
    val role: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
)

/**
 * Everything in this tree worth announcing, in the order it is laid out.
 *
 * A node with nothing to say is left out. A group that carries no label and no action is
 * scaffolding: announcing it would make a reader walk through the layout rather than
 * through the screen.
 */
internal fun SemanticsOwner.describe(): List<AccessibleElement> {
    val found = ArrayList<AccessibleElement>()
    fun walk(node: SemanticsNode) {
        node.describe()?.let(found::add)
        for (child in node.children) {
            walk(child)
        }
    }
    walk(rootSemanticsNode)
    return found
}

private fun SemanticsNode.describe(): AccessibleElement? {
    val config = config
    val label = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: config.getOrNull(SemanticsProperties.EditableText)?.text
    val clickable = config.getOrNull(SemanticsActions.OnClick) != null
    val role = when {
        config.getOrNull(SemanticsProperties.Role) == Role.Button -> ElementRole.BUTTON
        config.getOrNull(SemanticsProperties.Role) == Role.Checkbox -> ElementRole.CHECKBOX
        config.getOrNull(SemanticsProperties.Role) == Role.Image -> ElementRole.IMAGE
        config.getOrNull(SemanticsProperties.EditableText) != null -> ElementRole.FIELD
        clickable -> ElementRole.BUTTON
        label != null -> ElementRole.TEXT
        else -> ElementRole.GROUP
    }
    // Nothing to say and nothing to do. The layout is not the screen.
    if (label == null && !clickable) return null
    val bounds = boundsInRoot
    return AccessibleElement(
        role = role,
        x = bounds.left,
        y = bounds.top,
        width = bounds.width,
        height = bounds.height,
        label = label ?: "",
    )
}

/**
 * Keeps the platform's copy of the tree in step with the scene's.
 *
 * Pushed on change rather than asked for. What changes a tree is the screen changing, not
 * a frame being drawn, so this runs rarely even while something is animating.
 */
internal class NativeSemantics(private val push: (List<AccessibleElement>) -> Unit) :
    PlatformContext.SemanticsOwnerListener {

    private var owner: SemanticsOwner? = null
    private var changed = false

    // Noted here and read after the next frame. What these say is that the tree is
    // different, not that it has been placed: asked for its bounds at this moment every
    // control answers with an empty rectangle, and a reader given those finds the whole
    // window stacked in its top left corner. Measured after the frame, they are real.
    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        owner = semanticsOwner
        changed = true
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        if (owner === semanticsOwner) {
            owner = null
            changed = true
        }
    }

    // Every one of these remembers which tree it was about, not only the one whose name
    // says a tree arrived. That one is not always called: a scene can report its first
    // change without ever having reported an appearance, and a listener that waits for
    // the appearance waits for good. This window described nothing at all until the
    // other callbacks were allowed to say which tree they meant.
    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        owner = semanticsOwner
        changed = true
    }

    // A control that moved says the same things from a different place.
    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        owner = semanticsOwner
        changed = true
    }

    /**
     * Hands over the tree if it has changed since the last time.
     *
     * Called after a frame, which is when everything in it has been placed. Doing nothing
     * is the ordinary case: a screen that is not changing has nothing new to say.
     */
    fun pushIfChanged(afterDrawing: Boolean = false) {
        // Read again after a frame that painted, whether or not anything said so. What
        // the listener reports is that a tree changed, and a tree that was placed
        // differently without changing says nothing: a control that moved is at a new
        // place and a reader pointed at the old one finds nothing there.
        if (!changed && !afterDrawing) return
        changed = false
        val owner = owner ?: return
        val described = owner.describe()
        if (described == last) return
        last = described
        push(described)
    }

    // What was last handed over, so that reading again costs a comparison rather than a
    // crossing. A screen that is animating is placed anew on every frame and says the
    // same thing about itself throughout.
    private var last: List<AccessibleElement> = emptyList()
}
