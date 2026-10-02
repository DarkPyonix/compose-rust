package dioxus.compose.runtime

import androidx.compose.runtime.compositionLocalOf
import dioxus.compose.protocol.HostEvent
import dioxus.compose.protocol.WindowHeightClass
import dioxus.compose.protocol.WindowSizeClass

/**
 * The width in dp at which each size class begins.
 *
 * These are the Material 3 window size class boundaries, and the Host uses the same two
 * numbers. If the two sides ever disagree, the Compose components adapt at one width and
 * the layout the Host authored switches at another, which is visible on screen.
 */
const val MEDIUM_MIN_WIDTH_DP: Float = 600f
const val EXPANDED_MIN_WIDTH_DP: Float = 840f

/**
 * The height in dp at which each height class begins.
 *
 * From the same table as the width boundaries. A layout folds at the height Compose's own
 * components already treat as short, so the two sides fold together.
 */
const val MEDIUM_MIN_HEIGHT_DP: Float = 480f
const val EXPANDED_MIN_HEIGHT_DP: Float = 900f

/**
 * The size class of the window this content is in.
 *
 * The Host is told about this too, but only so a component can choose what to put on the
 * screen. The widgets that change shape with the window read it here instead, because they
 * are drawn on this side and asking the Host would mean a boundary call, a VirtualDom pass
 * and a rebuilt subtree to arrive at a layout this side could reach by moving the nodes it
 * already has.
 *
 * Not `staticCompositionLocalOf`: this one does change, and only the widgets that read it
 * should be invalidated when it does.
 */
val LocalWindowSizeClass = compositionLocalOf { WindowSizeClass.Compact }

/** The class a window of this width belongs to. Height does not take part. */
fun windowSizeClassOf(widthDp: Float): WindowSizeClass = when {
    widthDp >= EXPANDED_MIN_WIDTH_DP -> WindowSizeClass.Expanded
    widthDp >= MEDIUM_MIN_WIDTH_DP -> WindowSizeClass.Medium
    else -> WindowSizeClass.Compact
}

/** The class a window of this height belongs to. Width does not take part. */
fun windowHeightClassOf(heightDp: Float): WindowHeightClass = when {
    heightDp >= EXPANDED_MIN_HEIGHT_DP -> WindowHeightClass.Expanded
    heightDp >= MEDIUM_MIN_HEIGHT_DP -> WindowHeightClass.Medium
    else -> WindowHeightClass.Compact
}

/**
 * Tells the Host which size class the window is in, and only when that changes.
 *
 * Dragging a window edge produces a layout pass many times a second. Sending the size on
 * each of them would run the Host's VirtualDom just as often, for a value almost every
 * layout would report as unchanged. So the last class sent is remembered and compared, and
 * nothing crosses the boundary until it differs.
 *
 * The remembered class starts at [WindowSizeClass.Compact] because that is what a Host
 * assumes before it hears anything. A window that opens phone-sized is therefore silent:
 * telling the Host what it already believes would cost a boundary call and a VirtualDom
 * pass and change nothing. A window that opens wider reports once, on its first
 * measurement.
 *
 * The dp measurements ride along with the class rather than being reported on their own,
 * because a Host that needs pixel-by-pixel sizes is asking for work that belongs in
 * Compose's own layout.
 */
class WindowSizeReporter {
    private var lastClass: WindowSizeClass = WindowSizeClass.Compact
    private var lastHeightClass: WindowHeightClass = WindowHeightClass.Compact

    /**
     * Reports one measurement. Returns whether an event was sent.
     *
     * Nothing is allocated when neither class has changed, which is the common case. The
     * two classes ride in one record, so a window that gets wider and shorter in the same
     * drag costs one event rather than two.
     */
    fun report(widthDp: Float, heightDp: Float, dispatcher: EventDispatcher): Boolean {
        val sizeClass = windowSizeClassOf(widthDp)
        val heightClass = windowHeightClassOf(heightDp)
        if (sizeClass == lastClass && heightClass == lastHeightClass) return false
        lastClass = sizeClass
        lastHeightClass = heightClass
        dispatcher.dispatch(
            HostEvent.WindowSizeChanged(
                nodeId = 0,
                handlerId = 0,
                widthDp = widthDp,
                heightDp = heightDp,
                sizeClass = sizeClass,
                heightClass = heightClass,
            ),
        )
        return true
    }
}
