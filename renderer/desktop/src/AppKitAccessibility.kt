@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import org.thisisthepy.compose.window.AccessibleAction
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.AccessibleState
import org.thisisthepy.compose.window.ElementRole

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

/**
 * Everything in this tree worth announcing, in the order it is laid out, in window points
 * ([density] pixels to a point).
 *
 * A node with nothing to say is left out, and its children are attached to the nearest
 * ancestor that was kept. The fork's windows keep the tree and answer a reader from it, so
 * every element carries its id, its parent, its states, the actions it answers and the value
 * it is told as.
 */
internal fun SemanticsOwner.describe(density: Float): List<AccessibleElement> {
    val found = ArrayList<AccessibleElement>()
    describeTree(rootSemanticsNode, -1, density, found)
    return found
}

private fun describeTree(node: SemanticsNode, parentId: Int, density: Float, into: MutableList<AccessibleElement>) {
    val element = node.describe(parentId, density)
    if (element != null) into += element
    val childParent = element?.id ?: parentId
    for (child in node.children) describeTree(child, childParent, density, into)
}

private fun SemanticsNode.describe(parentId: Int, density: Float): AccessibleElement? {
    val config = config
    if (config.getOrNull(SemanticsProperties.HideFromAccessibility) != null) return null
    val editable = config.getOrNull(SemanticsProperties.EditableText)
    val label = config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
        ?: editable?.text
    val composeRole = config.getOrNull(SemanticsProperties.Role)
    val toggle = config.getOrNull(SemanticsProperties.ToggleableState)
    val range = config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
    val canClick = config.getOrNull(SemanticsActions.OnClick) != null
    val canSetText = config.getOrNull(SemanticsActions.SetText) != null
    val canSetProgress = config.getOrNull(SemanticsActions.SetProgress) != null
    val role = when {
        composeRole == Role.Button -> ElementRole.BUTTON
        composeRole == Role.Checkbox -> ElementRole.CHECKBOX
        composeRole == Role.Switch -> ElementRole.SWITCH
        composeRole == Role.RadioButton -> ElementRole.RADIO_BUTTON
        composeRole == Role.Tab -> ElementRole.TAB
        composeRole == Role.Image -> ElementRole.IMAGE
        composeRole == Role.DropdownList -> ElementRole.COMBO_BOX
        editable != null -> ElementRole.FIELD
        range != null -> if (canSetProgress) ElementRole.SLIDER else ElementRole.PROGRESS
        toggle != null -> ElementRole.CHECKBOX
        canClick -> ElementRole.BUTTON
        config.getOrNull(SemanticsProperties.Heading) != null && label != null -> ElementRole.HEADING
        label != null -> ElementRole.TEXT
        else -> return null
    }

    var states = 0
    if (config.getOrNull(SemanticsProperties.Disabled) == null) states = states or AccessibleState.ENABLED
    val focused = config.getOrNull(SemanticsProperties.Focused)
    if (focused == true) states = states or AccessibleState.FOCUSED
    if (focused != null || config.getOrNull(SemanticsActions.RequestFocus) != null) {
        states = states or AccessibleState.FOCUSABLE
    }
    if (toggle != null) {
        states = states or AccessibleState.CHECKABLE
        when (toggle) {
            ToggleableState.On -> states = states or AccessibleState.CHECKED
            ToggleableState.Indeterminate -> states = states or AccessibleState.MIXED
            ToggleableState.Off -> {}
        }
    }
    if (config.getOrNull(SemanticsProperties.Selected) == true) states = states or AccessibleState.SELECTED
    val password = config.getOrNull(SemanticsProperties.Password) != null
    if (editable != null) {
        states = states or if (canSetText) AccessibleState.EDITABLE else AccessibleState.READ_ONLY
        if (password) states = states or AccessibleState.PASSWORD
    }

    var actions = 0
    if (canClick) actions = actions or AccessibleAction.PRESS
    if (config.getOrNull(SemanticsActions.RequestFocus) != null) actions = actions or AccessibleAction.FOCUS
    if (canSetText) actions = actions or AccessibleAction.SET_VALUE
    if (canSetProgress) {
        actions = actions or AccessibleAction.SET_VALUE or AccessibleAction.INCREMENT or AccessibleAction.DECREMENT
    }
    if (config.getOrNull(SemanticsActions.ScrollBy) != null) {
        actions = actions or AccessibleAction.SCROLL_FORWARD or AccessibleAction.SCROLL_BACKWARD
    }
    if (config.getOrNull(SemanticsActions.OnLongClick) != null) actions = actions or AccessibleAction.SHOW_MENU

    val value = when {
        // A password is announced by its length, never its letters.
        editable != null && password -> "\u2022".repeat(editable.text.length)
        editable != null -> editable.text
        range != null -> config.getOrNull(SemanticsProperties.StateDescription) ?: range.current.toString()
        else -> config.getOrNull(SemanticsProperties.StateDescription)
    }
    val bounds = boundsInRoot
    return AccessibleElement(
        role = role,
        x = bounds.left / density,
        y = bounds.top / density,
        width = bounds.width / density,
        height = bounds.height / density,
        label = label ?: "",
        id = id,
        parentId = parentId,
        value = value,
        hint = config.getOrNull(SemanticsActions.OnClick)?.label,
        states = states,
        actions = actions,
        rangeMin = range?.range?.start,
        rangeMax = range?.range?.endInclusive,
        rangeValue = range?.current,
    )
}

/**
 * Runs the semantics action an [AccessibleAction] stands for. [height] and [width] are the
 * node's size in pixels, the distance one page of scrolling moves.
 */
private fun SemanticsConfiguration.perform(action: Int, argument: String?, height: Float, width: Float): Boolean {
    fun stepped(sign: Int): Boolean {
        val range = getOrNull(SemanticsProperties.ProgressBarRangeInfo) ?: return false
        val set = getOrNull(SemanticsActions.SetProgress)?.action ?: return false
        val span = range.range.endInclusive - range.range.start
        val step = if (range.steps > 0) span / (range.steps + 1) else span / 20f
        val next = (range.current + sign * step).coerceIn(range.range.start, range.range.endInclusive)
        return set(next)
    }
    fun scrolled(sign: Int): Boolean {
        val scroll = getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        val vertical = getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
            getOrNull(SemanticsProperties.HorizontalScrollAxisRange) == null
        return if (vertical) scroll(0f, sign * height) else scroll(sign * width, 0f)
    }
    return when (action) {
        AccessibleAction.PRESS -> getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: false
        AccessibleAction.FOCUS -> getOrNull(SemanticsActions.RequestFocus)?.action?.invoke() ?: false
        AccessibleAction.SET_VALUE -> {
            val text = argument ?: return false
            getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString(text))
                ?: text.toFloatOrNull()?.let { getOrNull(SemanticsActions.SetProgress)?.action?.invoke(it) }
                ?: false
        }
        AccessibleAction.INCREMENT -> stepped(+1)
        AccessibleAction.DECREMENT -> stepped(-1)
        AccessibleAction.SCROLL_FORWARD -> scrolled(+1)
        AccessibleAction.SCROLL_BACKWARD -> scrolled(-1)
        AccessibleAction.SHOW_MENU -> getOrNull(SemanticsActions.OnLongClick)?.action?.invoke() ?: false
        else -> false
    }
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
    fun pushIfChanged(density: Float, afterDrawing: Boolean = false) {
        // Read again after a frame that painted, whether or not anything said so. What
        // the listener reports is that a tree changed, and a tree that was placed
        // differently without changing says nothing: a control that moved is at a new
        // place and a reader pointed at the old one finds nothing there.
        if (!changed && !afterDrawing) return
        changed = false
        val owner = owner ?: return
        val described = owner.describe(density)
        if (described == last) return
        last = described
        push(described)
    }

    /**
     * Does what a reader asked of the control with [id]: [action] is an [AccessibleAction]
     * value and [argument] the new value of a set-value request. Returns whether the
     * control offered it. Called from the window's listener, on the thread that pumps.
     */
    fun perform(id: Int, action: Int, argument: String?): Boolean {
        val node = owner?.getAllSemanticsNodes(mergingEnabled = true)?.firstOrNull { it.id == id } ?: return false
        return node.config.perform(action, argument, node.size.height.toFloat(), node.size.width.toFloat())
    }

    // What was last handed over, so that reading again costs a comparison rather than a
    // crossing. A screen that is animating is placed anew on every frame and says the
    // same thing about itself throughout.
    private var last: List<AccessibleElement> = emptyList()
}
