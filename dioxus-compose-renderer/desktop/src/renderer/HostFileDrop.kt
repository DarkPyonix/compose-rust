@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dioxus.compose.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.PropertyKind
import dioxus.compose.protocol.WidgetKind
import dioxus.compose.runtime.EventDispatcher
import dioxus.compose.ui.node.Node
import dioxus.compose.ui.node.platformFileDrop
import java.awt.datatransfer.DataFlavor
import java.io.File

// Reading what is being dragged goes through an experimental accessor, and this file is
// the whole of where it is allowed to. Compose Desktop exposes the drag payload only as
// the toolkit's own transferable, so there is no stable spelling of "is this files, and
// which ones" to use instead. Pinned to the Compose version this repository builds
// against; if the accessor changes, this file is the one to fix and nothing else is.

/**
 * The byte the paths are separated by on the wire.
 *
 * NUL, because it is the one byte no path on any of the three desktops may contain. A
 * newline would have been easier to read and wrong: a file called "notes\nfor tuesday" is
 * legal on two of them, and splitting on newlines would turn one file into two.
 */
private const val PATH_SEPARATOR = '\u0000'

/**
 * Joins dropped paths for the wire, dropping any the platform cannot give as text.
 *
 * A path that is not valid UTF-8 is thrown away and the rest are delivered, which the
 * requirement asks for by name: one unreadable file must not lose the other nine, and it
 * must not end the process either.
 */
internal fun joinPaths(paths: List<String>): String =
    paths.filter { it.isNotEmpty() }.joinToString(PATH_SEPARATOR.toString())

/**
 * Makes this node a place files may be dropped, where it said it is one.
 *
 * Willingness is the widget rather than a property or the presence of a handler, because
 * a handler is attached whether or not the screen supplied one. On any other node nothing
 * is set up at all: the platform shows no drop cursor over it and nothing is reported,
 * which is what keeps a screen from lighting up every container it has.
 *
 * Desktop only. A phone has no notion of letting a file go over a window, so the modifier
 * is never reached there and the Host code is the same on every platform.
 */
@Composable
internal fun Modifier.desktopFileDrop(node: Node, dispatcher: EventDispatcher): Modifier {
    if (node.widget != WidgetKind.FileDropTarget) return this

    val entered = node.handler(PropertyKind.OnFilesEntered)
    val dropped = node.handler(PropertyKind.OnFilesDropped)
    val target = remember(node.id, entered, dropped, dispatcher) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                if (entered != null) {
                    dispatcher.dispatch(HostEvent.FilesEntered(node.id, entered))
                }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val handler = dropped ?: return false
                val paths = event.filePaths() ?: return false
                if (paths.isEmpty()) return false
                dispatcher.dispatch(HostEvent.FilesDropped(node.id, handler, joinPaths(paths)))
                return true
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event -> event.carriesFiles() },
        target = target,
    )
}

/** Whether what is being dragged is files rather than text or anything else. */
private fun DragAndDropEvent.carriesFiles(): Boolean =
    runCatching { awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) }
        .getOrDefault(false)

/**
 * The paths, or null where what was dropped was not files after all.
 *
 * Read through runCatching because the toolkit throws for a transfer that has gone away
 * between the drop and this call, and a file that cannot be read is not a reason to end
 * the process.
 */
private fun DragAndDropEvent.filePaths(): List<String>? = runCatching {
    @Suppress("UNCHECKED_CAST")
    val files = awtTransferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
    files.map { it.absolutePath }
}.getOrNull()

/**
 * Hands the desktop's answer to the shared tree walk. Called once, before the first frame.
 *
 * Desktop only, and that is why it is a hook rather than a call: the file this lives in
 * reads AWT's clipboard flavours, and a browser or a Kotlin/Native target has no AWT.
 * Those platforms leave the default, which adds nothing to a node's modifier chain.
 */
internal fun installFileDrop() {
    platformFileDrop = { modifier, node, dispatcher -> modifier.desktopFileDrop(node, dispatcher) }
}
