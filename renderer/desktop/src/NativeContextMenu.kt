package dev.darkpyonix.composerust.ui.platform

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** One entry of a menu the system draws: what it says and whether it can be chosen. */
data class NativeMenuEntry(val label: String, val enabled: Boolean)

/**
 * The right-click menu, drawn by the system instead of by Compose.
 *
 * Given as the default of `LocalContextMenuRepresentation`, which is the standard place
 * Compose asks how a context menu looks. Text fields and selections reach it through
 * `LocalTextContextMenu`, whose default builds cut, copy, paste and select all and hands
 * them here, with whatever `ContextMenuDataProvider` added around them. So the items are
 * Compose's, in Compose's order, enabled when Compose says they are; only the drawing is
 * the platform's. A system menu carries what the platform puts in every menu, Services and
 * Look Up on macOS, and looks like every other menu on the machine.
 *
 * An application that provides its own representation, or its own text context menu,
 * replaces this one rather than adding to it: Compose reads one representation, the
 * innermost, so exactly one menu comes up either way.
 *
 * [show] puts the menu up, waits until it closes and answers with the index of the entry
 * chosen, or -1 when the menu was dismissed.
 */
class NativeContextMenuRepresentation(
    private val show: (List<NativeMenuEntry>) -> Int,
) : ContextMenuRepresentation {

    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        LaunchedEffect(status) {
            val offered = items()
            val chosen = if (offered.isEmpty()) -1 else {
                KeyLog.menu("native menu with ${offered.map { it.label }}")
                show(offered.map { NativeMenuEntry(it.label, it.enabled) })
            }
            // Closed before the item runs, so an item that opens something else (a paste
            // that asks the clipboard, say) does so with this menu already gone.
            state.status = ContextMenuState.Status.Closed
            KeyLog.menu("chose $chosen")
            offered.getOrNull(chosen)?.takeIf { it.enabled }?.onClick?.invoke()
        }
    }
}

/**
 * The entries written as one line each, fields separated by a tab: the index, 1 or 0 for
 * enabled, and the label. This is what crosses into C, where a line becomes an
 * `NSMenuItem` whose tag is the index.
 */
internal fun packMenu(entries: List<NativeMenuEntry>): String =
    entries.withIndex().joinToString("\n") { (index, entry) ->
        "$index\t${if (entry.enabled) 1 else 0}\t" +
            entry.label.replace('\t', ' ').replace('\n', ' ')
    }
