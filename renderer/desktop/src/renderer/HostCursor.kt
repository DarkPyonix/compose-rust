package dev.darkpyonix.composerust.foundation

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import dev.darkpyonix.composerust.protocol.PropertyKind
import dev.darkpyonix.composerust.protocol.PropertyValue
import dev.darkpyonix.composerust.ui.node.Node as HostNode

// A node's `Cursor`, a CSS `cursor` keyword the Host has already inherited, is the
// pointer's shape over the node. Which shape each keyword is on Windows, X11 and AppKit is the
// fork's table (`org.thisisthepy.compose.window.CursorShape`, numbered as the wire numbers the
// keywords); applying it is the window's code. This side only carries the keyword from the node
// to the window, and names no window type, because the mobile and web renderers share it.

/** The last keyword the wire names (`None`). */
const val LAST_CURSOR_KEYWORD = 35

/** The pointer icon a `Cursor` keyword asks for: [keyword] is the wire value, 1 to 35. */
data class CursorKeywordIcon(val keyword: Int) : PointerIcon

/**
 * The icon for the wire value [value], or null for `Auto` (the widget decides: a text field's
 * text cursor, the arrow elsewhere), for no value, and for a value past the list, which the
 * decoder has already reported.
 */
fun cursorIconOf(value: Long?): PointerIcon? {
    if (value == null || value !in 1L..LAST_CURSOR_KEYWORD.toLong()) return null
    return CursorKeywordIcon(value.toInt())
}

/**
 * The node's `Cursor` as a hover icon. The deepest node with one wins over its ancestors
 * (Compose's hover icons answer from the innermost), and while a press is held the pressed
 * node's stays, because the pointer's hover does not move on until it is let go.
 */
internal fun Modifier.hostCursor(node: HostNode): Modifier {
    val value = (node.property(PropertyKind.Cursor) as? PropertyValue.Integer)?.value
    val icon = cursorIconOf(value) ?: return this
    return pointerHoverIcon(icon)
}
