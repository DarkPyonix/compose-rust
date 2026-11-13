package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.protocol.UserSelect
import dev.darkpyonix.composerust.protocol.WidgetKind
import dev.darkpyonix.composerust.ui.floatProp
import dev.darkpyonix.composerust.ui.node.CLICKABLE_CONTAINERS
import dev.darkpyonix.composerust.ui.node.Node
import dev.darkpyonix.composerust.ui.node.NodeTable

/**
 * Whether what is being drawn is inside a region whose text can be selected.
 *
 * Static because it changes only when a region is added or removed above a node, and every
 * node reads it.
 */
val LocalInSelectionRegion = staticCompositionLocalOf { false }

/**
 * Whether the text being drawn belongs to a node that is selected whole, which is how a
 * `UserSelect::All` node shows its selection. Text reads it to paint itself in the
 * selection colour.
 */
val LocalSelectedWhole = staticCompositionLocalOf { false }

/**
 * A region whose text can be dragged over and copied, as one selection.
 *
 * This is Compose's own `SelectionContainer` and nothing on top of it. Every `Text` drawn
 * inside joins the same selection however deep it is, including text in a list that
 * scrolls sideways, and copying a selection that spans several of them puts a line break
 * between each, which is what Compose does when it gathers the selected text. The copy
 * shortcut, the context menu and the clipboard are the platform's, through Compose's
 * platform text handling, so nothing here reaches the Host: no event is sent while text
 * is selected or when it is copied, and the copied text never crosses the boundary.
 *
 * Text outside a region is not selectable, because nothing outside one is wrapped in this.
 */
@Composable
internal fun SelectableRegion(modifier: Modifier, content: @Composable () -> Unit) {
    SelectionContainer(modifier) {
        CompositionLocalProvider(LocalInSelectionRegion provides true, content = content)
    }
}

/**
 * Draws a control so that it is left out of the selection around it.
 *
 * A button's label is text, and inside a selectable region it would be dragged over and
 * copied with the paragraph next to it. A control is something to press, not something to
 * read, so it is excluded and keeps answering presses exactly as it does anywhere else.
 * Outside a region this adds nothing.
 */
@Composable
internal fun Unselectable(content: @Composable () -> Unit) {
    if (LocalInSelectionRegion.current) {
        DisableSelection(content)
    } else {
        content()
    }
}

/** Whether the node is a container that answers a press, and so is a button. */
private fun Node.isPressable(): Boolean =
    modifiers.any { it is dev.darkpyonix.composerust.protocol.Modifier.Clickable } ||
        (widget in CLICKABLE_CONTAINERS && props[PropertyKind.OnClick] != null)

/** The node's `UserSelect`, with a value this Renderer does not know read as `Auto`. */
internal fun Node.userSelect(): UserSelect {
    val value = (props[PropertyKind.UserSelect] as? PropertyValue.Integer)?.value ?: return UserSelect.Auto
    return if (value in 0L until UserSelect.entries.size.toLong()) UserSelect.entries[value.toInt()] else UserSelect.Auto
}

/**
 * Applies what a node's `UserSelect` asks of the text below it, then draws the node with
 * the modifier [content] is handed.
 *
 * - `None` takes the subtree out of any selection above it, the way CSS `user-select:
 *   none` does: its text is not selected by a drag, a double-click or a drag that starts on
 *   it, and its buttons are pressed as anywhere else. Nothing changes about the layout.
 * - `Text` opens a selection region where none is open yet. So does the root of an HTML
 *   region, because text in a web page is selectable unless the page says otherwise.
 * - `All` selects the node whole on one press, and never a part of it.
 * - `Auto` follows the parent, which is to say it does nothing here.
 *
 * A container that answers a press (an `OnClick` or a `Clickable` modifier) is read as
 * `None` whatever it says, the way a button is: a double-click on its label is two presses,
 * not a word to select.
 *
 * Where a region is opened, the node's modifier goes on the region, so its size, its offset
 * and the weight its parent gave it reach the parent, and the node is drawn inside with none.
 */
@Composable
internal fun UserSelectArea(
    node: Node,
    table: NodeTable,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val select = node.userSelect()
    val islandRoot = node.widget == WidgetKind.AbsoluteBox && node.floatProp(PropertyKind.IslandZoom) != null
    when {
        select == UserSelect.None || node.isPressable() -> DisableSelection {
            CompositionLocalProvider(LocalInSelectionRegion provides false) { content(modifier) }
        }
        select == UserSelect.All -> SelectedWhole(node, table, modifier, content)
        (select == UserSelect.Text || islandRoot) && !LocalInSelectionRegion.current ->
            SelectableRegion(modifier) { content(Modifier) }
        else -> content(modifier)
    }
}

/**
 * A node that is selected as one piece, the way CSS `user-select: all` behaves.
 *
 * Compose's selection has no stable way to select everything in a region from outside it,
 * so this keeps the selection itself: one press selects the node, its text is painted in
 * the selection colour, and the copy shortcut puts all of it on the clipboard, one line for
 * each `Text` in the order they are drawn. A drag inside cannot select part of it, because
 * its text takes no part in a region's selection. The selection ends when focus leaves the
 * node. Like every other selection here it is the Renderer's: nothing reaches the Host.
 */
@Composable
private fun SelectedWhole(
    node: Node,
    table: NodeTable,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    var selected by remember(node.id) { mutableStateOf(false) }
    val focus = remember(node.id) { FocusRequester() }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val whole = Modifier
        .pointerInput(node.id) {
            awaitEachGesture {
                // Seen on the way down, before a button inside takes the press, and left
                // unconsumed so that button still answers it.
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                selected = true
                focus.requestFocus()
            }
        }
        .onKeyEvent { event ->
            val copy = event.type == KeyEventType.KeyDown &&
                ((event.key == Key.C && (event.isMetaPressed || event.isCtrlPressed)) ||
                    (event.key == Key.Insert && event.isCtrlPressed))
            if (selected && copy) {
                clipboard.setText(AnnotatedString(table.textUnder(node)))
                true
            } else {
                false
            }
        }
        .focusRequester(focus)
        .onFocusChanged { state -> if (!state.hasFocus) selected = false }
        .focusable()
    DisableSelection {
        CompositionLocalProvider(
            LocalInSelectionRegion provides true,
            LocalSelectedWhole provides selected,
        ) {
            content(modifier.then(whole))
        }
    }
}

/** Every `Text` at or below [root], in the order they are drawn, one to a line. */
internal fun NodeTable.textUnder(root: Node): String {
    val lines = mutableListOf<String>()
    fun walk(current: Node, depth: Int) {
        if (depth > 1024) return
        if (current.widget == WidgetKind.Text) lines += current.text(PropertyKind.Text)
        for (child in current.children) node(child)?.let { walk(it, depth + 1) }
    }
    walk(root, 0)
    return lines.joinToString("\n")
}
