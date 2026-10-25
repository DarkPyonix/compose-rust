@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.PlatformContext
import org.thisisthepy.compose.window.AccessibilityCache
import org.thisisthepy.compose.window.AccessibleElement
import org.thisisthepy.compose.window.scene.SceneSemantics

/**
 * What a screen reader is told about the scene, and how it presses things in it.
 *
 * The fork's X11 windows join the AT-SPI bus themselves and publish whatever list of
 * [AccessibleElement] they are given through `setAccessibility`. This reads the scene into that
 * list with the fork's own reader ([SceneSemantics]), which gives every control the id, parent,
 * state and actions a reader needs, and carries a reader's request back to the control through
 * [perform]. It is the scene's semantics listener, and [pushIfChanged] runs after each frame.
 *
 * [density] is the number of pixels to a window point.
 */
internal class ReaderSemantics private constructor(
    private val scene: SceneSemantics,
    density: () -> Float,
    push: (List<AccessibleElement>) -> Unit,
) : PlatformContext.SemanticsOwnerListener by scene {
    constructor(density: () -> Float, push: (List<AccessibleElement>) -> Unit) :
        this(SceneSemantics(), density, push)

    private val cache = AccessibilityCache({ scene.describe(density()) }, push)

    /**
     * Pushes the tree if it changed. After a frame that drew, it is read again whether or not
     * the scene said so, because a control that moved is at a new place without having changed.
     */
    fun pushIfChanged(afterDrawing: Boolean = false) {
        if (scene.changed) cache.noteChanged()
        cache.pushIfChanged(afterDrawing)
    }

    /** Does what a reader asked of the control with [id]; false where it does not offer it. */
    fun perform(id: Int, action: Int, argument: String?): Boolean = scene.perform(id, action, argument)
}
