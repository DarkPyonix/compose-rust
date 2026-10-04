package dev.darkpyonix.composerust.ui

import androidx.compose.ui.input.pointer.PointerIcon
import dev.darkpyonix.composerust.foundation.platformResizeCursor

/**
 * Gives the split pane's divider the left and right resize pointer.
 *
 * An icon of our own rather than one of the toolkit's cursors: the window shells draw
 * their own pointer and map this one to their resize shape, and building a toolkit cursor
 * would load the toolkit for the sake of an identity nothing else reads.
 */
internal fun installResizeCursor() {
    platformResizeCursor = ResizeLeftRightIcon
}

/** The pointer the platform draws for dragging something left or right. */
private object ResizeLeftRightIcon : PointerIcon {
    override fun toString(): String = "ResizeLeftRightIcon"
}
