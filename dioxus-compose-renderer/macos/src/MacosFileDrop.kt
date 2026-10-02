@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dioxus.compose.ui.platform

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import platform.AppKit.NSFilenamesPboardType
import platform.AppKit.NSPasteboard
import platform.Foundation.NSURL

/**
 * Files let go over the window, and where.
 *
 * Compose's own drag and drop does not reach this platform: the event type it hands a
 * target is declared and never filled in, and a target attached through it is never
 * called. So the window takes the drop itself and the screen is told through the same
 * Host event the other platforms send, which is what keeps an application's code the same
 * everywhere.
 *
 * What is carried is paths. A drag of anything else is refused at the edge of the window,
 * so the reader sees the no-entry cursor rather than a drop that quietly does nothing.
 */
internal object FileDrop {

    /** Where the pointer is while something is being dragged over the window. */
    val hovering = mutableStateOf<Offset?>(null)

    /** The paths of the last drop, in the order the platform listed them. */
    val dropped = mutableStateOf<List<String>>(emptyList())

    /** True where what is being dragged is files rather than text or anything else. */
    fun carriesFiles(pasteboard: NSPasteboard): Boolean =
        pasteboard.types?.contains(NSFilenamesPboardType) == true

    /**
     * Reads the paths out of a drag.
     *
     * A path the platform cannot give as text is dropped and the rest are kept: one
     * unreadable name must not lose the other nine.
     */
    fun paths(pasteboard: NSPasteboard): List<String> {
        val items = pasteboard.propertyListForType(NSFilenamesPboardType) as? List<*>
            ?: return emptyList()
        return items.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is NSURL -> entry.path
                else -> null
            }
        }.filter { it.isNotEmpty() }
    }
}
