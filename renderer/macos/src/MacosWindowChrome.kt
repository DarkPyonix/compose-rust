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

// The operating system calls only. When the icon is put on, and what size a window is
// given, are decided in WindowParity.kt.

/** The picture as the system's image type, by way of the encoded form every reader of images accepts. */
internal fun ImageBitmap.toNSImage(): NSImage? {
    val bytes = Image.makeFromBitmap(asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
        ?: return null
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    return NSImage(data = data)
}
