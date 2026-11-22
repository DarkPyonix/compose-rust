package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.unit.LayoutDirection
import dev.darkpyonix.composerust.foundation.OsMenuGroup
import dev.darkpyonix.composerust.foundation.OsMenuHandle
import dev.darkpyonix.composerust.foundation.OsMenuMappedEntry
import dev.darkpyonix.composerust.foundation.OsMenuPresenter
import dev.darkpyonix.composerust.foundation.OsMenuRequest
import dev.darkpyonix.composerust.foundation.OsMenuResult
import dev.darkpyonix.composerust.foundation.OsMenuResultOnce
import dev.darkpyonix.composerust.foundation.buildOsMenuGroups
import dev.darkpyonix.composerust.foundation.iosAnchorPoints
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIContextMenuConfiguration
import platform.UIKit.UIContextMenuInteraction
import platform.UIKit.UIContextMenuInteractionAnimatingProtocol
import platform.UIKit.UIContextMenuInteractionDelegateProtocol
import platform.UIKit.UIControlEventTouchDown
import platform.UIKit.UIImage
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElement
import platform.UIKit.UIMenuElementAttributesDisabled
import platform.UIKit.UIMenuElementState
import platform.UIKit.UIMenuOptionsDisplayInline
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIWindow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Presents OS entry menus on iOS using UIKit's [UIMenu] attached to a temporary transparent
 * [UIButton] positioned over the [UIWindow] at the requested anchor bounds.
 *
 * Opening a [UIMenu] from code uses `UIControl.performPrimaryAction` (iOS 17.4+) on a button
 * with `showsMenuAsPrimaryAction = true`, falling back to `sendActionsForControlEvents` on
 * older iOS releases. Dismissal is observed through the button's context menu interaction
 * delegate so both user dismissal and application-side [OsMenuHandle.close] report once.
 */
internal class IosOsMenuPresenter(private val window: UIWindow) : OsMenuPresenter {

    private var activeHandle: OsMenuHandle? = null

    override fun open(request: OsMenuRequest, onResult: (OsMenuResult) -> Unit): OsMenuHandle {
        activeHandle?.close()

        val once = OsMenuResultOnce(onResult)
        val groups = buildOsMenuGroups(request.entries)
        if (groups.isEmpty()) {
            once.dismissed()
            return OsMenuHandle {}
        }

        val scale = window.screen.scale.takeIf { it > 0.0 } ?: 1.0
        val rect = iosAnchorPoints(request.anchor, scale)

        var cleanedUp = false
        lateinit var button: OsMenuAnchorButton
        lateinit var handle: OsMenuHandle

        fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            button.removeFromSuperview()
            if (activeHandle === handle) {
                activeHandle = null
            }
        }

        val menu = buildUiMenu(groups = groups, once = once, onCleanup = ::cleanup)
        button = OsMenuAnchorButton {
            cleanup()
            once.dismissed()
        }.apply {
            setFrame(CGRectMake(rect.x, rect.y, rect.width, rect.height))
            backgroundColor = UIColor.clearColor
            showsMenuAsPrimaryAction = true
            semanticContentAttribute = when (request.layoutDirection) {
                LayoutDirection.Rtl -> UISemanticContentAttributeForceRightToLeft
                LayoutDirection.Ltr -> UISemanticContentAttributeForceLeftToRight
            }
            this.menu = menu
        }

        handle = OsMenuHandle {
            button.contextMenuInteraction?.dismissMenu()
            cleanup()
            once.dismissed()
        }
        activeHandle = handle

        window.addSubview(button)
        triggerMenuPresentation(button)
        return handle
    }

    internal fun buildUiMenu(
        groups: List<OsMenuGroup>,
        once: OsMenuResultOnce,
        onCleanup: () -> Unit = {},
    ): UIMenu = UIMenu.menuWithTitle(
        title = "",
        image = null,
        identifier = null,
        options = 0uL,
        children = buildElements(groups, once, onCleanup),
    )

    private fun buildElements(
        groups: List<OsMenuGroup>,
        once: OsMenuResultOnce,
        onCleanup: () -> Unit,
    ): List<UIMenuElement> {
        if (groups.size <= 1) {
            return groups.firstOrNull()?.entries?.map { buildEntry(it, once, onCleanup) }.orEmpty()
        }
        return groups.map { group ->
            UIMenu.menuWithTitle(
                title = "",
                image = null,
                identifier = null,
                options = UIMenuOptionsDisplayInline,
                children = group.entries.map { buildEntry(it, once, onCleanup) },
            )
        }
    }

    private fun buildEntry(
        entry: OsMenuMappedEntry,
        once: OsMenuResultOnce,
        onCleanup: () -> Unit,
    ): UIMenuElement = when (entry) {
        is OsMenuMappedEntry.Action -> {
            val image = symbolName(entry.icon)?.let { UIImage.systemImageNamed(it) }
            UIAction.actionWithTitle(
                title = entry.label,
                image = image,
                identifier = null,
                handler = { _ ->
                    onCleanup()
                    once.chosen(entry.path)
                },
            ).apply {
                if (!entry.enabled) {
                    attributes = UIMenuElementAttributesDisabled
                }
                state = if (entry.checked) {
                    UIMenuElementState.UIMenuElementStateOn
                } else {
                    UIMenuElementState.UIMenuElementStateOff
                }
                entry.shortcutLabel?.let { discoverabilityTitle = it }
            }
        }
        is OsMenuMappedEntry.Submenu -> {
            val image = symbolName(entry.icon)?.let { UIImage.systemImageNamed(it) }
            if (!entry.enabled || entry.groups.isEmpty()) {
                // UIMenu cannot be disabled; show a disabled action with the same label and icon.
                UIAction.actionWithTitle(
                    title = entry.label,
                    image = image,
                    identifier = null,
                    handler = { _ -> },
                ).apply {
                    attributes = UIMenuElementAttributesDisabled
                }
            } else {
                UIMenu.menuWithTitle(
                    title = entry.label,
                    image = image,
                    identifier = null,
                    options = 0uL,
                    children = buildElements(entry.groups, once, onCleanup),
                )
            }
        }
    }

    private fun triggerMenuPresentation(button: UIButton) {
        val primary = NSSelectorFromString("performPrimaryAction")
        if (button.respondsToSelector(primary)) {
            button.performSelector(primary)
        } else {
            button.sendActionsForControlEvents(UIControlEventTouchDown)
        }
    }
}

private class OsMenuAnchorButton(
    private val onEnded: () -> Unit,
) : UIButton(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)), UIContextMenuInteractionDelegateProtocol {

    override fun contextMenuInteraction(
        interaction: UIContextMenuInteraction,
        configurationForMenuAtLocation: kotlinx.cinterop.CValue<platform.CoreGraphics.CGPoint>,
    ): UIContextMenuConfiguration? = null

    @ObjCSignatureOverride
    override fun contextMenuInteraction(
        interaction: UIContextMenuInteraction,
        willEndForConfiguration: UIContextMenuConfiguration,
        animator: UIContextMenuInteractionAnimatingProtocol?,
    ) {
        // Defer dismissal to the next main-queue turn after UIKit's dismissal animation so
        // any selected UIAction handler fires first and wins the OsMenuResultOnce race.
        val finish = {
            dispatch_async(dispatch_get_main_queue()) {
                onEnded()
            }
        }
        if (animator != null) {
            animator.addCompletion(finish)
        } else {
            finish()
        }
    }
}
