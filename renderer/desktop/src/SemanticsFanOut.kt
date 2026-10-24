@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.semantics.SemanticsOwner

/**
 * One semantics listener that tells several.
 *
 * A scene reports its semantics to one listener, and a window can have more than one reader
 * of them: the elements the window keeps for its own accessibility records, and a screen
 * reader on the AT-SPI bus answered from a tree of its own. Each hears every change.
 */
internal class SemanticsFanOut(
    private vararg val listeners: PlatformContext.SemanticsOwnerListener,
) : PlatformContext.SemanticsOwnerListener {
    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        for (listener in listeners) listener.onSemanticsOwnerAppended(semanticsOwner)
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        for (listener in listeners) listener.onSemanticsOwnerRemoved(semanticsOwner)
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        for (listener in listeners) listener.onSemanticsChange(semanticsOwner)
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        for (listener in listeners) listener.onLayoutChange(semanticsOwner, semanticsNodeId)
    }
}
