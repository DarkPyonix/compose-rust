package dev.darkpyonix.composerust.foundation

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.protocol.IconRole
import org.thisisthepy.compose.window.PopupMenuAnchor
import org.thisisthepy.compose.window.PopupMenuEntry
import org.thisisthepy.compose.window.PopupMenuIcon
import kotlin.math.roundToInt

// The Compose fork's window modules put an entry menu up as the operating system's own
// (`org.thisisthepy.compose.window.PopupMenuEntry`): `NSMenu` on macOS and
// `TrackPopupMenuEx` on Windows, on the Kotlin/Native windows and the GraalVM ones alike. The
// fork's list has the shape of [OsMenuEntry] and its anchor the space of [OsMenuRequest.anchor],
// so the four [BlockingOsMenu]s over it share this mapping and differ only in the call.

/** The side of a menu entry's picture in points, as `NSMenu` and a Win32 popup menu show one. */
const val FORK_MENU_ICON_POINTS = 16

/** A menu icon's side in the window's pixels at [scale] (pixels per point). */
fun forkMenuIconPixels(scale: Float): Int = (FORK_MENU_ICON_POINTS * scale).roundToInt().coerceAtLeast(1)

/**
 * [request]'s entries as the fork's. An entry's icon is the picture [OsMenuRequest.iconArt]
 * draws for its role at [iconPixels] a side, or none: the fork shows pixels, not a platform
 * symbol. The order is kept, separators included, so the fork's answer is a path into
 * [OsMenuRequest.entries] as [OsMenuResult.Chosen] counts it.
 */
fun forkPopupMenuEntries(request: OsMenuRequest, iconPixels: Int): List<PopupMenuEntry> {
    val drawn = HashMap<IconRole, PopupMenuIcon?>()
    fun icon(role: IconRole?): PopupMenuIcon? {
        val art = request.iconArt ?: return null
        role ?: return null
        return drawn.getOrPut(role) { art.draw(role, iconPixels)?.let(::forkPopupMenuIcon) }
    }
    fun map(entries: List<OsMenuEntry>): List<PopupMenuEntry> = entries.map { entry ->
        when (entry) {
            is OsMenuEntry.Item -> PopupMenuEntry.Item(
                label = entry.label,
                shortcut = entry.shortcutLabel ?: "",
                enabled = entry.enabled,
                checked = entry.checked,
                icon = icon(entry.icon),
            )
            OsMenuEntry.Separator -> PopupMenuEntry.Separator
            is OsMenuEntry.Submenu -> {
                // The submenu's own picture is drawn before its children's, in menu order.
                val submenuIcon = icon(entry.icon)
                PopupMenuEntry.Submenu(
                    label = entry.label,
                    children = map(entry.children),
                    enabled = entry.enabled,
                    icon = submenuIcon,
                )
            }
        }
    }
    return map(request.entries)
}

/** [request]'s anchor as the fork's: the same window pixels, the start edge by layout direction. */
fun forkPopupMenuAnchor(request: OsMenuRequest): PopupMenuAnchor = PopupMenuAnchor(
    x = request.anchor.x.toFloat(),
    y = request.anchor.y.toFloat(),
    width = request.anchor.width.toFloat(),
    height = request.anchor.height.toFloat(),
    rightToLeft = request.layoutDirection == LayoutDirection.Rtl,
)

/**
 * [bitmap] as the fork's menu picture: premultiplied eight-bit red, green, blue and alpha, rows
 * unpadded. [ImageBitmap.readPixels] answers unpremultiplied ARGB. Null for an empty bitmap.
 */
fun forkPopupMenuIcon(bitmap: ImageBitmap): PopupMenuIcon? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val argb = IntArray(width * height)
    bitmap.readPixels(argb)
    val rgba = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val pixel = argb[i]
        val alpha = pixel ushr 24 and 0xff
        rgba[i * 4] = premultiply(pixel ushr 16 and 0xff, alpha)
        rgba[i * 4 + 1] = premultiply(pixel ushr 8 and 0xff, alpha)
        rgba[i * 4 + 2] = premultiply(pixel and 0xff, alpha)
        rgba[i * 4 + 3] = alpha.toByte()
    }
    return PopupMenuIcon(rgba, width, height)
}

private fun premultiply(channel: Int, alpha: Int): Byte = ((channel * alpha + 127) / 255).toByte()
