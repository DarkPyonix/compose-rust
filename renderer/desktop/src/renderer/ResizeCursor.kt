package dev.darkpyonix.composerust.ui

import androidx.compose.ui.input.pointer.PointerIcon
import dev.darkpyonix.composerust.foundation.platformResizeCursor
import java.awt.Cursor

/**
 * Gives the split pane's divider the desktop's left and right resize pointer.
 *
 * Desktop only, because the shape is the toolkit's cursor and a phone has no pointer to
 * shape. The window shells that draw their own pointer map this one to their resize shape.
 */
internal fun installResizeCursor() {
    platformResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
}
