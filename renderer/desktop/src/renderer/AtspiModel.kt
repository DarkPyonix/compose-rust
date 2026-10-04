package dev.darkpyonix.composerust.ui.platform

// What the window tells a reader who cannot see it, in the words AT-SPI uses.
//
// Orca and Accerciser read a desktop through the accessibility registry, and what they ask a
// window is a tree of objects with a role, a name, a set of states and a list of actions. This
// file is that vocabulary and the rule that turns what Compose knows about a control into it.
// It names neither Compose's semantics classes nor the bus, which is what makes the rule
// checkable: `AtspiSemantics.kt` reads the scene and fills in [NodeFacts], `AtspiServer.kt`
// answers the bus from an [AtspiTree], and the arithmetic in between is here.
//
// The numbers are the ones in `atspi-constants.h`, which is the registry's own list. A role
// off by one is a button announced as something else, and the control still looks right, so
// they are written out here and asserted by name in the tests.

/** The roles this renderer announces. The values are `AtspiRole`'s. */
internal object AtspiRole {
    const val CHECK_BOX = 7
    const val COMBO_BOX = 11
    const val FILLER = 20
    const val FRAME = 23
    const val IMAGE = 27
    const val LABEL = 29
    const val LIST = 31
    const val LIST_ITEM = 32
    const val PAGE_TAB = 37
    const val PANEL = 39
    const val PASSWORD_TEXT = 40
    const val PROGRESS_BAR = 42
    const val PUSH_BUTTON = 43
    const val RADIO_BUTTON = 44
    const val SLIDER = 51
    const val TOGGLE_BUTTON = 62
    const val APPLICATION = 75
    const val ENTRY = 79
    const val HEADING = 83

    /** The name a reader speaks for a role, which is `atspi_role_get_name`'s. */
    fun nameOf(role: Int): String = when (role) {
        CHECK_BOX -> "check box"
        COMBO_BOX -> "combo box"
        FILLER -> "filler"
        FRAME -> "frame"
        IMAGE -> "image"
        LABEL -> "label"
        LIST -> "list"
        LIST_ITEM -> "list item"
        PAGE_TAB -> "page tab"
        PANEL -> "panel"
        PASSWORD_TEXT -> "password text"
        PROGRESS_BAR -> "progress bar"
        PUSH_BUTTON -> "push button"
        RADIO_BUTTON -> "radio button"
        SLIDER -> "slider"
        TOGGLE_BUTTON -> "toggle button"
        APPLICATION -> "application"
        ENTRY -> "text"
        HEADING -> "heading"
        else -> "unknown"
    }
}

/** The states this renderer reports, as bit positions. The values are `AtspiStateType`'s. */
internal object AtspiState {
    const val ACTIVE = 1
    const val CHECKED = 4
    const val EDITABLE = 7
    const val ENABLED = 8
    const val FOCUSABLE = 11
    const val FOCUSED = 12
    const val MULTI_LINE = 17
    const val SELECTABLE = 22
    const val SELECTED = 23
    const val SENSITIVE = 24
    const val SHOWING = 25
    const val SINGLE_LINE = 26
    const val VISIBLE = 30
    const val INDETERMINATE = 32

    fun bit(state: Int): Long = 1L shl state

    /** The name of a state in an event, which is what a listener subscribes to. */
    fun nameOf(state: Int): String = when (state) {
        ACTIVE -> "active"
        CHECKED -> "checked"
        EDITABLE -> "editable"
        ENABLED -> "enabled"
        FOCUSABLE -> "focusable"
        FOCUSED -> "focused"
        SELECTED -> "selected"
        SHOWING -> "showing"
        VISIBLE -> "visible"
        INDETERMINATE -> "indeterminate"
        else -> "state-$state"
    }
}

/** The one thing a control can be asked to do that this renderer offers. */
internal const val ACTION_CLICK = "click"

/**
 * What the scene knows about one control, with nothing of the scene in it.
 *
 * The adapter reads these out of Compose's semantics configuration; the rule in
 * [describeFacts] reads them and nothing else. Strings rather than Compose's own types for the
 * role and the toggle, so that a test builds one without a scene.
 */
internal data class NodeFacts(
    /** Compose's `Role`, by name: Button, Checkbox, Switch, RadioButton, Tab, Image, DropdownList. */
    val composeRole: String? = null,
    /** The content description, else the text the control shows. */
    val label: String? = null,
    /** What is typed in the field, when the control is one. */
    val editableText: String? = null,
    val password: Boolean = false,
    val multiline: Boolean = false,
    /** On, Off or Indeterminate for something that toggles; null for something that does not. */
    val toggle: String? = null,
    /** Present for something that can be chosen from a set, and whether it is. */
    val selected: Boolean? = null,
    val focused: Boolean = false,
    val disabled: Boolean = false,
    val clickable: Boolean = false,
    val canTakeFocus: Boolean = false,
    val heading: Boolean = false,
    /** A progress indicator or a slider: something with a value in a range. */
    val ranged: Boolean = false,
    /** A range the reader can move, which makes it a slider and not a progress bar. */
    val adjustable: Boolean = false,
)

/** What a control is announced as. */
internal data class Description(
    val role: Int,
    val name: String,
    val states: Long,
    val actions: List<String>,
)

/**
 * The rule: what a reader is told about a control, or null for one with nothing to say.
 *
 * A node with no name, no value and nothing to do is layout. Announcing it would make a reader
 * walk through the arrangement of the screen rather than through what is on it, so it is left
 * out and its children are attached to its parent. A group that carries a name or an action is
 * kept, because that is what the author said it is.
 */
internal fun describeFacts(facts: NodeFacts): Description? {
    val editable = facts.editableText != null
    val name = facts.label ?: ""
    val hasName = facts.label != null
    val role = when {
        editable && facts.password -> AtspiRole.PASSWORD_TEXT
        editable -> AtspiRole.ENTRY
        facts.composeRole == "Checkbox" || facts.composeRole == "TriStateCheckbox" -> AtspiRole.CHECK_BOX
        facts.composeRole == "Switch" -> AtspiRole.TOGGLE_BUTTON
        facts.composeRole == "RadioButton" -> AtspiRole.RADIO_BUTTON
        facts.composeRole == "Tab" -> AtspiRole.PAGE_TAB
        facts.composeRole == "Image" -> AtspiRole.IMAGE
        facts.composeRole == "DropdownList" -> AtspiRole.COMBO_BOX
        facts.composeRole == "Button" -> AtspiRole.PUSH_BUTTON
        facts.ranged && facts.adjustable -> AtspiRole.SLIDER
        facts.ranged -> AtspiRole.PROGRESS_BAR
        facts.toggle != null -> AtspiRole.CHECK_BOX
        facts.clickable -> AtspiRole.PUSH_BUTTON
        facts.heading && hasName -> AtspiRole.HEADING
        hasName -> AtspiRole.LABEL
        else -> AtspiRole.FILLER
    }
    if (role == AtspiRole.FILLER) return null
    var states = AtspiState.bit(AtspiState.SHOWING) or AtspiState.bit(AtspiState.VISIBLE)
    if (!facts.disabled) {
        states = states or AtspiState.bit(AtspiState.ENABLED) or AtspiState.bit(AtspiState.SENSITIVE)
    }
    if (facts.clickable || facts.canTakeFocus || editable) {
        states = states or AtspiState.bit(AtspiState.FOCUSABLE)
    }
    if (facts.focused) states = states or AtspiState.bit(AtspiState.FOCUSED)
    when (facts.toggle) {
        "On" -> states = states or AtspiState.bit(AtspiState.CHECKED)
        "Indeterminate" -> states = states or AtspiState.bit(AtspiState.INDETERMINATE)
    }
    if (facts.selected != null) {
        states = states or AtspiState.bit(AtspiState.SELECTABLE)
        if (facts.selected) states = states or AtspiState.bit(AtspiState.SELECTED)
    }
    if (editable) {
        states = states or AtspiState.bit(AtspiState.EDITABLE)
        states = states or AtspiState.bit(if (facts.multiline) AtspiState.MULTI_LINE else AtspiState.SINGLE_LINE)
    }
    val actions = if (facts.clickable && !facts.disabled) listOf(ACTION_CLICK) else emptyList()
    return Description(role, name, states, actions)
}

/** One object in the tree a reader walks. */
internal data class AtspiNode(
    /** Compose's own id for the control, stable for as long as the control exists. */
    val id: Int,
    /** The id of the parent, or [AtspiTree.FRAME] for a child of the window. */
    val parent: Int,
    val children: List<Int>,
    val role: Int,
    val name: String,
    val states: Long,
    val actions: List<String>,
    /** In the window's pixels, from its top left corner. */
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** What a field holds, or null for something that is not one. */
    val text: String?,
    /** Where the caret is in [text], in characters. */
    val caret: Int = 0,
)

/**
 * The tree for one window: what [describeFacts] kept, nested the way the scene nests it.
 *
 * Equality is what decides whether anything is said: a frame that left the tree as it was
 * leaves the bus quiet.
 */
internal data class AtspiTree(
    val title: String,
    val nodes: Map<Int, AtspiNode>,
    /** The window's own children, in the order they are laid out. */
    val roots: List<Int>,
    /** The node that has the keyboard, or null where none does. */
    val focused: Int?,
    /** The window's size in pixels, which is the frame's own extents. */
    val width: Int = 0,
    val height: Int = 0,
) {
    /** The id of the deepest node under a point in the window's pixels, or null for none. */
    fun nodeAt(x: Int, y: Int): Int? {
        // The deepest node containing the point, and the last one laid out when two overlap,
        // because the one drawn later is the one on top.
        var found: Int? = null
        var depth = -1
        fun visit(id: Int, level: Int) {
            val node = nodes[id] ?: return
            if (x >= node.x && x < node.x + node.width && y >= node.y && y < node.y + node.height && level >= depth) {
                found = id
                depth = level
            }
            for (child in node.children) visit(child, level + 1)
        }
        for (root in roots) visit(root, 0)
        return found
    }

    companion object {
        /** The parent id of what sits directly in the window. */
        const val FRAME = -1

        val EMPTY = AtspiTree("", emptyMap(), emptyList(), null)
    }
}
