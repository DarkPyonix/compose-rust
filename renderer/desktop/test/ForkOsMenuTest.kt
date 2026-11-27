package dev.darkpyonix.composerust.test

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.NativeRect
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Item
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Separator
import dev.darkpyonix.composerust.foundation.OsMenuEntry.Submenu
import dev.darkpyonix.composerust.foundation.OsMenuIconArt
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.forkMenuIconPixels
import dev.darkpyonix.composerust.foundation.forkPopupMenuAnchor
import dev.darkpyonix.composerust.foundation.forkPopupMenuEntries
import dev.darkpyonix.composerust.foundation.forkPopupMenuIcon
import dev.darkpyonix.composerust.protocol.IconRole
import org.thisisthepy.compose.window.PopupMenuAnchor
import org.thisisthepy.compose.window.PopupMenuEntry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The OS menus of the macOS and Windows windows go up through the Compose fork's
 * `PopupMenuEntry` list: [forkPopupMenuEntries] and [forkPopupMenuAnchor] are the
 * whole translation, shared by the Kotlin/Native and GraalVM windows of both systems.
 */
class ForkOsMenuTest {

    private val entries = listOf(
        Item(id = 1, label = "Open", shortcutLabel = "Ctrl+O"),
        Item(id = 2, label = "Wrap", checked = true, enabled = false),
        Separator,
        Submenu(
            label = "Share",
            icon = IconRole.Share,
            children = listOf(Item(id = 3, label = "Mail", icon = IconRole.Send), Separator, Item(id = 4, label = "Link")),
        ),
        Submenu(label = "Recent", children = emptyList(), enabled = false),
    )

    private fun request(
        art: OsMenuIconArt? = null,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = OsMenuRequest(entries, NativeRect(40, 120, 200, 32), direction, art)

    @Test
    fun entriesKeepTheirOrderSeparatorsAndStates() {
        val mapped = forkPopupMenuEntries(request(), iconPixels = 16)
        assertEquals(
            listOf(
                PopupMenuEntry.Item("Open", shortcut = "Ctrl+O"),
                PopupMenuEntry.Item("Wrap", enabled = false, checked = true),
                PopupMenuEntry.Separator,
                PopupMenuEntry.Submenu(
                    "Share",
                    listOf(PopupMenuEntry.Item("Mail"), PopupMenuEntry.Separator, PopupMenuEntry.Item("Link")),
                ),
                PopupMenuEntry.Submenu("Recent", emptyList(), enabled = false),
            ),
            mapped,
        )
    }

    @Test
    fun aPathIntoTheForksListIsAPathIntoTheRequest() {
        // The fork answers indices into the list it was given, separators counted, which is how
        // OsMenuResult.Chosen counts: "Link" is entry 3, then child 2, in both.
        val mapped = forkPopupMenuEntries(request(), iconPixels = 16)
        val share = mapped[3] as PopupMenuEntry.Submenu
        assertEquals(PopupMenuEntry.Item("Link"), share.children[2])
        assertEquals("Link", ((entries[3] as Submenu).children[2] as Item).label)
    }

    @Test
    fun theAnchorStaysInWindowPixelsWithItsStartEdge() {
        assertEquals(PopupMenuAnchor(40f, 120f, 200f, 32f, rightToLeft = false), forkPopupMenuAnchor(request()))
        assertEquals(true, forkPopupMenuAnchor(request(direction = LayoutDirection.Rtl)).rightToLeft)
    }

    @Test
    fun iconsAreTheArtDrawnAtTheMenusSizeOncePerRole() {
        val asked = mutableListOf<Pair<IconRole, Int>>()
        val art = OsMenuIconArt { role, size ->
            asked += role to size
            ImageBitmap(size, size)
        }
        val mapped = forkPopupMenuEntries(request(art), iconPixels = forkMenuIconPixels(2f))
        val share = mapped[3] as PopupMenuEntry.Submenu
        val shareIcon = assertNotNull(share.icon)
        assertEquals(32, shareIcon.width)
        assertNotNull((share.children[0] as PopupMenuEntry.Item).icon)
        assertNull((mapped[0] as PopupMenuEntry.Item).icon)
        assertEquals(listOf(IconRole.Share to 32, IconRole.Send to 32), asked)
        // A role drawn once is reused, not drawn again.
        val twice = OsMenuRequest(
            listOf(Item(id = 1, label = "A", icon = IconRole.Star), Item(id = 2, label = "B", icon = IconRole.Star)),
            NativeRect(0, 0, 1, 1),
            iconArt = art,
        )
        asked.clear()
        val both = forkPopupMenuEntries(twice, iconPixels = 16)
        assertEquals(1, asked.size)
        assertSame((both[0] as PopupMenuEntry.Item).icon, (both[1] as PopupMenuEntry.Item).icon)
    }

    @Test
    fun noArtOrNoPictureMeansNoIcon() {
        assertNull((forkPopupMenuEntries(request(art = null), 16)[3] as PopupMenuEntry.Submenu).icon)
        val none = OsMenuIconArt { _, _ -> null }
        assertNull((forkPopupMenuEntries(request(none), 16)[3] as PopupMenuEntry.Submenu).icon)
    }

    @Test
    fun anIconIsPremultipliedRgba() {
        // One pixel of red at half alpha, one transparent: premultiplied, red is about 128, and
        // the transparent pixel is all zero.
        val bitmap = ImageBitmap(2, 1)
        val paint = Paint().apply { color = Color(1f, 0f, 0f, 0.5f); blendMode = BlendMode.Src }
        Canvas(bitmap).drawRect(Rect(0f, 0f, 1f, 1f), paint)
        val icon = assertNotNull(forkPopupMenuIcon(bitmap))
        assertEquals(2, icon.width)
        assertEquals(1, icon.height)
        val first = icon.rgba.copyOfRange(0, 4).map { it.toInt() and 0xff }
        assertEquals(0, first[1])
        assertEquals(0, first[2])
        assertEquals(true, first[3] in 126..129, "alpha ${first[3]}")
        assertEquals(true, first[0] in 126..129 && first[0] <= first[3] + 1, "red ${first[0]} premultiplied by ${first[3]}")
        assertContentEquals(byteArrayOf(0, 0, 0, 0), icon.rgba.copyOfRange(4, 8))
    }

    @Test
    fun theIconSideFollowsTheScale() {
        assertEquals(16, forkMenuIconPixels(1f))
        assertEquals(24, forkMenuIconPixels(1.5f))
        assertEquals(32, forkMenuIconPixels(2f))
        assertEquals(1, forkMenuIconPixels(0f))
    }
}
