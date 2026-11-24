package dev.darkpyonix.composerust.foundation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.protocol.IconRole

// A menu made only of entries, put up by the operating system instead of drawn by Compose:
// `NSMenu` on macOS, a Win32 popup menu on Windows, `PopupMenu` on Android, `UIMenu` on iOS.
// The interpreter cannot name a platform type, so it hands a platform the entries and where
// the menu belongs, and hears back what was chosen. The list rules are in `OsMenuEntries.kt`.

/**
 * One entry of a menu the operating system puts up: an item, a separator or a submenu. No
 * colour, layout or content of the application's own, since every platform's menu can show
 * these and nothing more.
 */
sealed interface OsMenuEntry {

    /**
     * An entry that can be chosen.
     *
     * [id] is the caller's tag, handed back by [resolveOsMenuPath]; the renderer puts the node
     * id there. A presenter never reads it.
     *
     * [shortcutLabel] is text at the trailing edge, such as "Ctrl+S". A presenter must not
     * register the key with the system: the application handles the key already and would
     * otherwise see it twice.
     *
     * [icon] is the design system's role, or null. A presenter shows the platform's symbol
     * for the role or the pixels [OsMenuRequest.iconArt] draws, and shows no picture when it
     * has neither.
     */
    data class Item(
        val id: Int,
        val label: String,
        val shortcutLabel: String? = null,
        val enabled: Boolean = true,
        val checked: Boolean = false,
        val icon: IconRole? = null,
    ) : OsMenuEntry

    /**
     * A line between groups. Android and iOS have no separator entry and show one as the edge
     * between two groups (dividers on a `PopupMenu` group, an inline `UIMenu`); after
     * [normalizeOsMenu] every separator sits between two entries, so that form shows them all.
     */
    data object Separator : OsMenuEntry

    /**
     * An entry that opens a menu of [children]. A disabled one shows greyed, does not open,
     * and has no children after [normalizeOsMenu]. `UIMenu` cannot be disabled, so iOS shows
     * a disabled submenu as a disabled action with the same label and icon.
     */
    data class Submenu(
        val label: String,
        val children: List<OsMenuEntry>,
        val enabled: Boolean = true,
        val icon: IconRole? = null,
    ) : OsMenuEntry
}

/** How a menu ended. Reported exactly once per [OsMenuPresenter.open]. */
sealed interface OsMenuResult {

    /**
     * An item was chosen. [path] is its index in [OsMenuRequest.entries], then its index in
     * each submenu on the way down, separators counted.
     */
    data class Chosen(val path: List<Int>) : OsMenuResult

    /** Closed with nothing chosen: a click outside, Escape, a window switch, or [OsMenuHandle.close]. */
    data object Dismissed : OsMenuResult
}

/**
 * Draws a design system icon as a square of [sizePx] platform pixels, for a platform with no
 * symbol for the role. Null when it cannot, and the entry shows no picture.
 */
fun interface OsMenuIconArt {
    fun draw(role: IconRole, sizePx: Int): ImageBitmap?
}

/**
 * One menu to put up.
 *
 * [entries] has been through [normalizeOsMenu] and is never empty.
 *
 * [anchor] is in the window's pixels from the top left of its content: the space of Compose's
 * `boundsInWindow`, of a popup's anchor bounds, and of [NativeRect] for platform views. A
 * presenter converts it to its platform's units (points on macOS and iOS). The menu opens
 * below the anchor's bottom edge with its start edge on the anchor's start edge, left for
 * [LayoutDirection.Ltr] and right for [LayoutDirection.Rtl]; the platform may move it to stay
 * on screen. A presenter may use the anchor's width as a minimum width where the platform
 * has one (`NSMenu.minimumWidth`), so a dropdown's list is as wide as its field.
 *
 * [iconArt] is null when nobody supplied one: use the platform's symbol for the role, or no
 * picture.
 */
data class OsMenuRequest(
    val entries: List<OsMenuEntry>,
    val anchor: NativeRect,
    val layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    val iconArt: OsMenuIconArt? = null,
)

/** A menu that [OsMenuPresenter.open] put up. */
fun interface OsMenuHandle {
    /**
     * Takes the menu down from the application's side, as when its node leaves the
     * composition. If nothing was reported yet, [OsMenuResult.Dismissed] is, once, before
     * this returns or later on the UI thread, and nothing is chosen after. Calling it after
     * the result, or twice, does nothing.
     */
    fun close()
}

/**
 * Puts entry menus up as the operating system's own, for one window.
 *
 * [open] and [OsMenuHandle.close] are called on the UI thread, and `onResult` runs on it.
 * The result comes exactly once per [open]. A blocking platform call (`NSMenu` popping up,
 * `TrackPopupMenuEx`) reports before [open] returns and returns a handle whose close does
 * nothing; a listener based one (`PopupMenu`, `UIMenu`) returns at once and reports from its
 * listener. Wrap the callback in [OsMenuResultOnce] to keep the once rule in either shape.
 *
 * The caller has at most one menu open per window and checks a chosen path with
 * [resolveOsMenuPath], so a presenter reports what the platform said without checking it.
 */
interface OsMenuPresenter {
    fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle
}

/**
 * Passes on the first result and drops the rest. Platforms report a close more than once
 * (Android calls its dismiss listener after an item listener, and an application side close
 * can race a choice), and the caller counts on one answer.
 */
class OsMenuResultOnce(private val onResult: (OsMenuResult) -> Unit) {
    var reported: Boolean = false
        private set

    fun chosen(path: List<Int>) = report(OsMenuResult.Chosen(path))

    fun dismissed() = report(OsMenuResult.Dismissed)

    private fun report(result: OsMenuResult) {
        if (reported) return
        reported = true
        onResult(result)
    }
}

/**
 * The OS menu presenter of the window being composed, or null where the backend has none and
 * entry menus are drawn (Linux's X11 windows, the web, tests that provide none). Per window
 * rather than a global, because the anchor is in one window's pixels, as with
 * [LocalNativeViewWindow].
 */
val LocalOsMenuPresenter = staticCompositionLocalOf<OsMenuPresenter?> { null }
