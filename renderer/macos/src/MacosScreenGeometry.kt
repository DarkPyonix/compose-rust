package dev.darkpyonix.composerust.ui.platform

// Screen coordinates for the multi-window backend.
//
// The renderer measures a screen from the top left of the primary screen with y growing
// downward, in dp. On macOS a dp is a point. AppKit measures from the bottom left of the primary
// screen with y growing upward. Everything that converts between the two is a function of
// numbers here, so that the arithmetic is tested without a window or a screen.

/** A rectangle in the renderer's space: top left origin, y down, in points. */
internal data class TopLeftRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** The renderer-space rectangle of an AppKit frame, given the height of the primary screen. */
internal fun appKitRectToTopLeft(
    originX: Double,
    originY: Double,
    width: Double,
    height: Double,
    primaryHeight: Double,
): TopLeftRect = TopLeftRect(originX, primaryHeight - originY - height, width, height)

/** A point of one space as a point of the other. Its own inverse. */
internal fun flipPoint(x: Double, y: Double, primaryHeight: Double): Pair<Double, Double> =
    x to (primaryHeight - y)

/**
 * Where the window's outer frame has its top left corner, in renderer space, so that the point
 * ([anchorX], [anchorY]) of its content, in points from the content's top left, is under the
 * pointer at ([pointerX], [pointerY]). The content starts [titleBarHeight] below the frame's top.
 */
internal fun followFrameTopLeft(
    pointerX: Double,
    pointerY: Double,
    anchorX: Double,
    anchorY: Double,
    titleBarHeight: Double,
): Pair<Double, Double> = (pointerX - anchorX) to (pointerY - anchorY - titleBarHeight)

/** Whether the primary button is in the bitmask `NSEvent.pressedMouseButtons` reports. */
internal fun primaryButtonDown(pressedMouseButtons: ULong): Boolean = (pressedMouseButtons and 1uL) != 0uL

/** The virtual key code of Escape, which cancels a follow. */
internal const val KEY_CODE_ESCAPE = 53

/** The bit of an `NSEventMask` for key down events (`NSEventTypeKeyDown` is 10). */
internal const val EVENT_MASK_KEY_DOWN = 1024uL

/**
 * A frame the user or the system moved, as the bounds the manager is told: the whole points the
 * window sits at, so a position written back reads as the same number.
 */
internal fun TopLeftRect.toScreenBounds(): ScreenBounds =
    ScreenBounds(
        kotlin.math.round(x).toFloat(),
        kotlin.math.round(y).toFloat(),
        kotlin.math.round(width).toFloat(),
        kotlin.math.round(height).toFloat(),
    )

/** Rounds a size in dp to the whole points AppKit is given. */
internal fun wholePoints(value: Float): Int = kotlin.math.round(value).toInt()
