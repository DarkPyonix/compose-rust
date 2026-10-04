package dev.darkpyonix.composerust.ui

import androidx.compose.ui.input.pointer.PointerIcon
import dev.darkpyonix.composerust.foundation.platformResizeCursor

/**
 * Gives the split pane's divider the desktop's left and right resize pointer.
 *
 * macOS draws its own pointer and maps this one to its resize shape, so it gets an icon of
 * our own: building a toolkit cursor would load the Java toolkit for the sake of an identity
 * nothing else reads. The windows that are still the toolkit's get its cursor.
 */
internal fun installResizeCursor() {
    platformResizeCursor =
        if (System.getProperty("os.name", "").startsWith("Mac")) ResizeLeftRightIcon
        else toolkitResizeIcon()
}

/** The pointer macOS draws for dragging something left or right. */
private object ResizeLeftRightIcon : PointerIcon {
    override fun toString(): String = "ResizeLeftRightIcon"
}
