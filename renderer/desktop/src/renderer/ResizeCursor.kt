package dev.darkpyonix.composerust.ui

import androidx.compose.ui.input.pointer.PointerIcon
import dev.darkpyonix.composerust.foundation.platformResizeCursor
import dev.darkpyonix.composerust.ui.platform.ToolkitWindow

/**
 * Gives the split pane's divider the desktop's left and right resize pointer.
 *
 * macOS and Linux draw their own pointer and map this one to its resize shape, so it gets an icon of
 * our own: building a toolkit cursor would load the Java toolkit for the sake of an identity
 * nothing else reads. The windows that are still the toolkit's get its cursor.
 */
internal fun installResizeCursor() {
    // An image built without the toolkit's window cannot name the toolkit's cursor at all.
    if (!ToolkitWindow.available) {
        platformResizeCursor = ResizeLeftRightIcon
        return
    }
    val os = System.getProperty("os.name", "")
    platformResizeCursor =
        if (os.startsWith("Mac") || os.startsWith("Linux")) ResizeLeftRightIcon else toolkitResizeIcon()
}

/** The pointer macOS and Linux draw for dragging something left or right. */
private object ResizeLeftRightIcon : PointerIcon {
    override fun toString(): String = "ResizeLeftRightIcon"
}
