package dev.darkpyonix.composerust.ui.platform

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.OsMenuGroup
import dev.darkpyonix.composerust.foundation.OsMenuHandle
import dev.darkpyonix.composerust.foundation.OsMenuIconArt
import dev.darkpyonix.composerust.foundation.OsMenuMappedEntry
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.OsMenuResultOnce
import dev.darkpyonix.composerust.foundation.androidAnchorRect
import dev.darkpyonix.composerust.foundation.buildOsMenuGroups
import dev.darkpyonix.composerust.foundation.formatAndroidMenuTitle
import dev.darkpyonix.composerust.protocol.IconRole

/**
 * Presents OS entry menus on Android using [PopupMenu] anchored to a temporary invisible
 * [View] placed over the Activity's root [FrameLayout] at the requested window pixel bounds.
 */
class AndroidOsMenuPresenter(private val root: FrameLayout) : OsMenuPresenter {

    private var activeHandle: OsMenuHandle? = null

    override fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle {
        activeHandle?.close()

        val once = OsMenuResultOnce(onResult)
        val groups = buildOsMenuGroups(request.entries)
        if (groups.isEmpty()) {
            once.dismissed()
            return OsMenuHandle {}
        }

        val rect = androidAnchorRect(request.anchor)
        val anchorView = View(root.context).apply {
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            layoutDirection = when (request.layoutDirection) {
                LayoutDirection.Rtl -> View.LAYOUT_DIRECTION_RTL
                LayoutDirection.Ltr -> View.LAYOUT_DIRECTION_LTR
            }
        }
        val params = FrameLayout.LayoutParams(rect.width, rect.height).apply {
            leftMargin = rect.x
            topMargin = rect.y
        }
        root.addView(anchorView, params)
        // Lay the temporary anchor out synchronously so PopupMenu reads its exact window
        // position immediately rather than waiting for the next frame.
        anchorView.measure(
            View.MeasureSpec.makeMeasureSpec(rect.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height, View.MeasureSpec.EXACTLY),
        )
        anchorView.layout(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)

        val gravity = when (request.layoutDirection) {
            LayoutDirection.Rtl -> Gravity.END
            LayoutDirection.Ltr -> Gravity.START
        }
        val popup = PopupMenu(root.context, anchorView, gravity)
        val pathsByItemId = HashMap<Int, List<Int>>()
        var nextItemId = 1
        var hasIcons = false
        val iconSizePx = iconSizePx()

        fun iconDrawable(role: IconRole?, iconArt: OsMenuIconArt?): BitmapDrawable? {
            if (role == null || iconArt == null) return null
            val bitmap = iconArt.draw(role, iconSizePx)?.asAndroidBitmap() ?: return null
            hasIcons = true
            return BitmapDrawable(root.resources, bitmap)
        }

        fun populate(menu: Menu, menuGroups: List<OsMenuGroup>) {
            var order = 0
            for (group in menuGroups) {
                // Non-zero group IDs so Android draws group dividers between adjacent groups.
                val groupId = group.groupIndex + 1
                for (entry in group.entries) {
                    when (entry) {
                        is OsMenuMappedEntry.Action -> {
                            val itemId = nextItemId++
                            pathsByItemId[itemId] = entry.path
                            val title = formatAndroidMenuTitle(entry.label, entry.shortcutLabel)
                            val item = menu.add(groupId, itemId, order++, title)
                            item.isEnabled = entry.enabled
                            if (entry.checked) {
                                item.isCheckable = true
                                item.isChecked = true
                            }
                            iconDrawable(entry.icon, request.iconArt)?.let { item.icon = it }
                        }
                        is OsMenuMappedEntry.Submenu -> {
                            if (!entry.enabled || entry.groups.isEmpty()) {
                                val disabled = menu.add(groupId, Menu.NONE, order++, entry.label)
                                disabled.isEnabled = false
                                iconDrawable(entry.icon, request.iconArt)?.let { disabled.icon = it }
                            } else {
                                val sub = menu.addSubMenu(groupId, Menu.NONE, order++, entry.label)
                                iconDrawable(entry.icon, request.iconArt)?.let { sub.item.icon = it }
                                populate(sub, entry.groups)
                            }
                        }
                    }
                }
            }
            if (menuGroups.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                menu.setGroupDividerEnabled(true)
            }
        }

        populate(popup.menu, groups)
        if (hasIcons && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            popup.setForceShowIcon(true)
        }

        var cleanedUp = false
        lateinit var handle: OsMenuHandle
        fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            if (anchorView.parent === root) {
                root.removeView(anchorView)
            }
            if (activeHandle === handle) {
                activeHandle = null
            }
        }

        handle = OsMenuHandle {
            popup.dismiss()
            cleanup()
            once.dismissed()
        }
        activeHandle = handle

        popup.setOnMenuItemClickListener { item ->
            val path = pathsByItemId[item.itemId] ?: return@setOnMenuItemClickListener false
            cleanup()
            once.chosen(path)
            true
        }
        popup.setOnDismissListener {
            cleanup()
            once.dismissed()
        }

        popup.show()
        return handle
    }

    /** Dismisses any open popup when the hosting Activity is destroyed. */
    fun close() {
        activeHandle?.close()
        activeHandle = null
    }

    private fun iconSizePx(): Int {
        val density = root.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        return (ICON_SIZE_DP * density).toInt().coerceAtLeast(1)
    }

    private companion object {
        private const val ICON_SIZE_DP = 20f
    }
}
