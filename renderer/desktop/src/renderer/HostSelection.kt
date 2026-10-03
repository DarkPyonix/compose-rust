package dev.darkpyonix.composerust.foundation

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Whether what is being drawn is inside a region whose text can be selected.
 *
 * Static because it changes only when a region is added or removed above a node, and every
 * node reads it.
 */
val LocalInSelectionRegion = staticCompositionLocalOf { false }

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
