@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import platform.AppKit.NSImage
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * The smallest content area the window may be dragged to, in points, or null where the
 * application named none. A measurement of zero means it did not ask; one axis may be
 * named without the other.
 */
internal fun contentMinimum(minWidth: Int, minHeight: Int): Pair<Double, Double>? =
    if (minWidth <= 0 && minHeight <= 0) {
        null
    } else {
        minWidth.coerceAtLeast(0).toDouble() to minHeight.coerceAtLeast(0).toDouble()
    }

/**
 * Puts the application's picture on the Dock, once the asset it named has arrived.
 *
 * The id is known from the first batch and the bitmap a little later, so [tryApply] is
 * asked until it succeeds and does nothing afterwards. What it looks the picture up in and
 * where it puts it are parameters, so the waiting can be exercised without a Dock.
 */
internal class DockIcon(
    private val lookup: (Int) -> ImageBitmap?,
    private val apply: (ImageBitmap) -> Unit,
) {
    var applied = false
        private set

    /** True once the picture is on the Dock, from this call or an earlier one. */
    fun tryApply(assetId: Int): Boolean {
        if (applied) return true
        if (assetId == 0) return false
        val picture = lookup(assetId) ?: return false
        apply(picture)
        applied = true
        return true
    }
}

/** The picture as the system's image type, by way of the encoded form every reader of images accepts. */
internal fun ImageBitmap.toNSImage(): NSImage? {
    val bytes = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: return null
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    return NSImage(data = data)
}
