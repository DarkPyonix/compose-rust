package dev.darkpyonix.composerust.ui.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.thisisthepy.compose.window.macos.setApplicationIcon

// When the icon is put on, and what size a window is given, are decided in the window module's
// WindowParity.kt. Putting the picture on the Dock is the window module's call too.

/** The pixels the window module's `setApplicationIcon` takes: RGBA, premultiplied, rows unpadded. */
internal class IconPixels(val rgba: ByteArray, val width: Int, val height: Int)

/** [picture] as [IconPixels], or null where Skia cannot read it in that form. */
internal fun iconPixels(picture: ImageBitmap): IconPixels? {
    val bitmap = picture.asSkiaBitmap()
    val info = ImageInfo(bitmap.width, bitmap.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    val rgba = bitmap.readPixels(info, info.minRowBytes) ?: return null
    return IconPixels(rgba, bitmap.width, bitmap.height)
}

/**
 * Puts [picture] on the Dock and the application switcher through the window module, the
 * same call and pixel form as the native image's window. Answers whether it was accepted.
 */
internal fun applyDockIcon(picture: ImageBitmap): Boolean {
    // TRIAL (root cause, 2026-11-22, not for merging): `DXC_ICON=none` leaves the Dock icon
    // unset; `DXC_ICON=srgb` puts it on and then retags its pixels as sRGB instead of device RGB.
    val mode = java.lang.System.getenv("DXC_ICON")
    if (mode == "none") {
        java.lang.System.err.println("compose-rust: trial icon left unset")
        return true
    }
    val pixels = iconPixels(picture) ?: return false
    val ok = setApplicationIcon(pixels.rgba, pixels.width, pixels.height)
    if (ok && mode == "srgb") {
        val app = platform.AppKit.NSApplication.sharedApplication()
        val image = app.applicationIconImage
        val rep = image?.representations?.firstOrNull() as? platform.AppKit.NSBitmapImageRep
        val tagged = rep?.bitmapImageRepByRetaggingWithColorSpace(platform.AppKit.NSColorSpace.sRGBColorSpace)
        if (image != null && tagged != null) {
            val retagged = platform.AppKit.NSImage(size = image.size)
            retagged.addRepresentation(tagged)
            app.applicationIconImage = retagged
            java.lang.System.err.println("compose-rust: trial icon retagged sRGB")
        }
    }
    return ok
}
