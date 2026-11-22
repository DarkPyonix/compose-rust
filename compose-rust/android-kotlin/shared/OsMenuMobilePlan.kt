package dev.darkpyonix.composerust.foundation

import dev.darkpyonix.composerust.protocol.IconRole

// Pure Kotlin mapping from the shared OS menu entry list to the group structure Android's
// `PopupMenu` and iOS's `UIMenu` expect. Neither platform has a separator entry of its own;
// both draw a separator at the boundary between adjacent groups while preserving the caller's
// original entry indices (separators counted) in every chosen path.

/**
 * One non-separator entry inside an [OsMenuGroup], carrying its full [path] back into the
 * original [OsMenuRequest.entries] tree.
 */
sealed interface OsMenuMappedEntry {
    val path: List<Int>
    val label: String
    val enabled: Boolean
    val icon: IconRole?

    data class Action(
        override val path: List<Int>,
        override val label: String,
        val shortcutLabel: String? = null,
        override val enabled: Boolean = true,
        val checked: Boolean = false,
        override val icon: IconRole? = null,
    ) : OsMenuMappedEntry

    data class Submenu(
        override val path: List<Int>,
        override val label: String,
        override val enabled: Boolean = true,
        override val icon: IconRole? = null,
        val groups: List<OsMenuGroup> = emptyList(),
    ) : OsMenuMappedEntry
}

/**
 * One contiguous run of non-separator entries between separators. Adjacent groups have
 * different [groupIndex] values so Android can assign distinct group IDs and iOS can wrap
 * each group in an inline `UIMenu`.
 */
data class OsMenuGroup(
    val groupIndex: Int,
    val entries: List<OsMenuMappedEntry>,
)

/**
 * Anchor bounds in logical points for UIKit (`pixels / scale`), clamped to at least 1x1 pt
 * so an empty anchor still has a valid frame to present a menu from.
 */
data class OsMenuPointRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Splits [entries] on [OsMenuEntry.Separator] into non-empty [OsMenuGroup]s while keeping
 * every entry's original index path (separators counted) intact.
 *
 * Disabled submenus and submenus whose children contain no non-separator entries produce a
 * disabled [OsMenuMappedEntry.Submenu] with no child groups.
 */
fun buildOsMenuGroups(
    entries: List<OsMenuEntry>,
    prefix: List<Int> = emptyList(),
): List<OsMenuGroup> {
    val groups = mutableListOf<OsMenuGroup>()
    val current = mutableListOf<OsMenuMappedEntry>()

    fun flush() {
        if (current.isNotEmpty()) {
            groups += OsMenuGroup(groupIndex = groups.size, entries = current.toList())
            current.clear()
        }
    }

    for ((index, entry) in entries.withIndex()) {
        when (entry) {
            is OsMenuEntry.Separator -> flush()
            is OsMenuEntry.Item -> {
                current += OsMenuMappedEntry.Action(
                    path = prefix + index,
                    label = entry.label,
                    shortcutLabel = entry.shortcutLabel,
                    enabled = entry.enabled,
                    checked = entry.checked,
                    icon = entry.icon,
                )
            }
            is OsMenuEntry.Submenu -> {
                val path = prefix + index
                val childGroups = if (entry.enabled) {
                    buildOsMenuGroups(entry.children, path)
                } else {
                    emptyList()
                }
                val effectiveEnabled = entry.enabled && childGroups.isNotEmpty()
                current += OsMenuMappedEntry.Submenu(
                    path = path,
                    label = entry.label,
                    enabled = effectiveEnabled,
                    icon = entry.icon,
                    groups = if (effectiveEnabled) childGroups else emptyList(),
                )
            }
        }
    }
    flush()
    return groups
}

/**
 * Formats a label for Android's `PopupMenu`, which has no separate shortcut column. Appends
 * the shortcut text in parentheses without registering any hardware key with the system.
 */
fun formatAndroidMenuTitle(label: String, shortcutLabel: String?): String {
    val hint = shortcutLabel?.trim()
    return if (hint.isNullOrEmpty()) label else "$label ($hint)"
}

/**
 * Clamps an anchor rectangle in window pixels for Android's temporary anchor `View` so it
 * stays within non-negative coordinates and has at least a 1x1 px box.
 */
fun androidAnchorRect(anchor: NativeRect): NativeRect = NativeRect(
    x = anchor.x.coerceAtLeast(0),
    y = anchor.y.coerceAtLeast(0),
    width = anchor.width.coerceAtLeast(1),
    height = anchor.height.coerceAtLeast(1),
)

/**
 * Converts an anchor rectangle in window pixels to UIKit points using [scale].
 */
fun iosAnchorPoints(anchor: NativeRect, scale: Double): OsMenuPointRect {
    val s = if (scale > 0.0) scale else 1.0
    return OsMenuPointRect(
        x = (anchor.x.toDouble() / s).coerceAtLeast(0.0),
        y = (anchor.y.toDouble() / s).coerceAtLeast(0.0),
        width = (anchor.width.toDouble() / s).coerceAtLeast(1.0),
        height = (anchor.height.toDouble() / s).coerceAtLeast(1.0),
    )
}
