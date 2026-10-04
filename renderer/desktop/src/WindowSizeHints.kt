package dev.darkpyonix.composerust.ui.platform

/**
 * The sizes an X11 window tells its window manager it may take, in pixels.
 *
 * What the application asked for, turned into the two hints a manager reads: a smallest size,
 * and a largest. A window that cannot be resized is held to the size it opened at, which is
 * both at once; otherwise only the smallest is stated, and only when the application named one.
 * One rule for both X11 windows, because a window that lets itself be dragged below the size its
 * application asked for is a layout that has stopped fitting.
 */
internal data class SizeHints(
    val min: Pair<Int, Int>?,
    val max: Pair<Int, Int>?,
)

/** The hints for a window, or null where there is nothing to tell the manager. */
internal fun sizeHintsFor(
    minWidth: Int,
    minHeight: Int,
    resizable: Boolean,
    width: Int,
    height: Int,
    scale: Float = 1f,
): SizeHints? {
    var min: Pair<Int, Int>? = null
    var max: Pair<Int, Int>? = null
    if (minWidth > 0 || minHeight > 0) {
        min = (minWidth * scale + 0.5f).toInt() to (minHeight * scale + 0.5f).toInt()
    }
    if (!resizable) {
        min = width to height
        max = width to height
    }
    return if (min == null && max == null) null else SizeHints(min, max)
}
