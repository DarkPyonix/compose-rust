package dev.darkpyonix.composerust.foundation

// The rules every OS menu presenter applies to an entry list, so that `NSMenu`, a Win32 popup
// menu, Android's `PopupMenu`, iOS's `UIMenu` and the drawn menu all show the same entries.
//
// Each platform treats an odd list differently. `NSMenu` and Win32 show a leading, trailing
// or doubled separator as it is, while Android and iOS can only show a separator as the edge
// between two groups and so drop those. Win32 and `NSMenu` open an empty submenu as an empty
// box; iOS leaves a `UIMenu` with no children out. Normalising the list once, before any
// presenter sees it, leaves nothing for them to disagree on.

/**
 * The list as every presenter shows it.
 *
 * - A separator is kept only between two other entries: leading, trailing and repeated
 *   separators are dropped, at every level.
 * - A submenu is enabled only when it was declared enabled and still has an entry once its
 *   own list is normalised. A disabled submenu carries no children, since no platform opens
 *   one.
 * - Line breaks and tabs in a label or shortcut label become spaces: a menu entry is one
 *   line on every platform, and Win32 reads a tab in an entry's text as the start of its
 *   shortcut column. A shortcut label that is blank becomes null.
 *
 * Anything else is a presenter's own business, such as doubling `&` for Win32, which
 * otherwise reads it as a mnemonic marker.
 */
fun normalizeOsMenu(entries: List<OsMenuEntry>): List<OsMenuEntry> {
    val out = ArrayList<OsMenuEntry>(entries.size)
    for (entry in entries) {
        when (entry) {
            is OsMenuEntry.Separator -> {
                if (out.isNotEmpty() && out.last() !is OsMenuEntry.Separator) out += entry
            }
            is OsMenuEntry.Item -> out += entry.copy(
                label = oneLine(entry.label),
                shortcutLabel = entry.shortcutLabel?.let(::oneLine)?.takeIf { it.isNotBlank() },
            )
            is OsMenuEntry.Submenu -> {
                val children = normalizeOsMenu(entry.children)
                val enabled = entry.enabled && children.isNotEmpty()
                out += entry.copy(
                    label = oneLine(entry.label),
                    children = if (enabled) children else emptyList(),
                    enabled = enabled,
                )
            }
        }
    }
    if (out.isNotEmpty() && out.last() is OsMenuEntry.Separator) out.removeAt(out.lastIndex)
    return out
}

/**
 * The item a chosen [path] points at in [entries], or null when it points at nothing that
 * can be chosen: an empty path, an index out of range, a step into an entry that is not a
 * submenu or into a disabled submenu, or an end at a separator, a submenu or a disabled item.
 *
 * The caller treats null as a dismissal. A platform should never report such a path, and if
 * one does the menu has still closed, so the application hears that it closed and nothing
 * more.
 */
fun resolveOsMenuPath(entries: List<OsMenuEntry>, path: List<Int>): OsMenuEntry.Item? {
    if (path.isEmpty()) return null
    var level = entries
    for ((depth, index) in path.withIndex()) {
        val entry = level.getOrNull(index) ?: return null
        val last = depth == path.lastIndex
        when (entry) {
            is OsMenuEntry.Item -> return if (last && entry.enabled) entry else null
            is OsMenuEntry.Separator -> return null
            is OsMenuEntry.Submenu -> {
                if (last || !entry.enabled) return null
                level = entry.children
            }
        }
    }
    return null
}

private fun oneLine(text: String): String =
    text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
