@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr

@SymbolName("dxc_native_context_menu")
private external fun showContextMenu(window: COpaquePointer?, items: CPointer<ByteVar>?): Int

/**
 * What a selection offers when it is asked, as the system's own popup menu.
 *
 * The Windows twin of MacosTextToolbar: Compose decides which actions exist, and the
 * system draws them. Windows shows an action that cannot run greyed rather than leaving it
 * out, so Paste is there and greyed when the clipboard holds no text, which Compose says by
 * passing no paste action.
 */
internal class Win32TextToolbar(private val window: () -> COpaquePointer?) : TextToolbar {

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        // In the order Windows applications put them.
        val actions = listOf(
            "Cut" to onCutRequested,
            "Copy" to onCopyRequested,
            "Paste" to onPasteRequested,
            "Select All" to onSelectAllRequested,
        )
        if (actions.all { it.second == null }) return
        val packed = actions.withIndex().joinToString("\n") { (index, entry) ->
            "$index\t${if (entry.second != null) 1 else 0}\t${entry.first}"
        }
        status = TextToolbarStatus.Shown
        // Returns when the menu closes, with the entry chosen or -1.
        val chosen = memScoped { showContextMenu(window(), packed.cstr.ptr) }
        status = TextToolbarStatus.Hidden
        actions.getOrNull(chosen)?.second?.invoke()
    }

    override fun hide() {
        // The system takes the menu down itself.
        status = TextToolbarStatus.Hidden
    }
}
